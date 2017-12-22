/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.impl;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableMap;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerConfiguration;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.util.FutureUtil;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandAck.AckType;
import org.apache.pulsar.common.naming.DestinationName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

public class PartitionedConsumerImpl extends ConsumerBase {

    private final List<ConsumerImpl> consumers;

    // Queue of partition consumers on which we have stopped calling receiveAsync() because the
    // shared incoming queue was full
    private final ConcurrentLinkedQueue<ConsumerImpl> pausedConsumers;

    // Threshold for the shared queue. When the size of the shared queue goes below the threshold, we are going to
    // resume receiving from the paused consumer partitions
    private final int sharedQueueResumeThreshold;

    private final int numPartitions;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final ConsumerStats stats;
    private final UnAckedMessageTracker unAckedMessageTracker;

    PartitionedConsumerImpl(PulsarClientImpl client, String topic, String subscription, ConsumerConfiguration conf,
            int numPartitions, ExecutorService listenerExecutor, CompletableFuture<Consumer> subscribeFuture) {
        super(client, topic, subscription, conf, Math.max(Math.max(2, numPartitions), conf.getReceiverQueueSize()), listenerExecutor,
                subscribeFuture);
        this.consumers = Lists.newArrayListWithCapacity(numPartitions);
        this.pausedConsumers = new ConcurrentLinkedQueue<>();
        this.sharedQueueResumeThreshold = maxReceiverQueueSize / 2;
        this.numPartitions = numPartitions;

        if (conf.getAckTimeoutMillis() != 0) {
            this.unAckedMessageTracker = new UnAckedMessageTracker(client, this, conf.getAckTimeoutMillis());
        } else {
            this.unAckedMessageTracker = UnAckedMessageTracker.UNACKED_MESSAGE_TRACKER_DISABLED;
        }

        stats = client.getConfiguration().getStatsIntervalSeconds() > 0 ? new ConsumerStats() : null;
        checkArgument(conf.getReceiverQueueSize() > 0,
                "Receiver queue size needs to be greater than 0 for Partitioned Topics");
        start();
    }

    private void start() {
        AtomicReference<Throwable> subscribeFail = new AtomicReference<Throwable>();
        AtomicInteger completed = new AtomicInteger();
        ConsumerConfiguration internalConfig = getInternalConsumerConfig();
        for (int partitionIndex = 0; partitionIndex < numPartitions; partitionIndex++) {
            String partitionName = DestinationName.get(topic).getPartition(partitionIndex).toString();
            ConsumerImpl consumer = new ConsumerImpl(client, partitionName, subscription, internalConfig,
                    client.externalExecutorProvider().getExecutor(), partitionIndex, new CompletableFuture<Consumer>());
            consumers.add(consumer);
            consumer.subscribeFuture().handle((cons, subscribeException) -> {
                if (subscribeException != null) {
                    setState(State.Failed);
                    subscribeFail.compareAndSet(null, subscribeException);
                    client.cleanupConsumer(this);
                }
                if (completed.incrementAndGet() == numPartitions) {
                    if (subscribeFail.get() == null) {
                        try {
                            // We have successfully created N consumers, so we can start receiving messages now
                            starReceivingMessages();
                            setState(State.Ready);
                            subscribeFuture().complete(PartitionedConsumerImpl.this);
                            log.info("[{}] [{}] Created partitioned consumer", topic, subscription);
                            return null;
                        } catch (PulsarClientException e) {
                            subscribeFail.set(e);
                        }
                    }
                    closeAsync().handle((ok, closeException) -> {
                        subscribeFuture().completeExceptionally(subscribeFail.get());
                        client.cleanupConsumer(this);
                        return null;
                    });
                    log.error("[{}] [{}] Could not create partitioned consumer.", topic, subscription,
                            subscribeFail.get().getCause());
                }
                return null;
            });
        }
    }

    private void starReceivingMessages() throws PulsarClientException {
        for (ConsumerImpl consumer : consumers) {
            consumer.sendFlowPermitsToBroker(consumer.cnx(), conf.getReceiverQueueSize());
            receiveMessageFromConsumer(consumer);
        }
    }

    private void receiveMessageFromConsumer(ConsumerImpl consumer) {
        consumer.receiveAsync().thenAccept(message -> {
            // Process the message, add to the queue and trigger listener or async callback
            messageReceived(message);

            // we're modifying pausedConsumers
            lock.writeLock().lock();
            try {
                int size = incomingMessages.size();
                if (size >= maxReceiverQueueSize
                        || (size > sharedQueueResumeThreshold && !pausedConsumers.isEmpty())) {
                    // mark this consumer to be resumed later: if No more space left in shared queue,
                    // or if any consumer is already paused (to create fair chance for already paused consumers)
                    pausedConsumers.add(consumer);
                } else {
                    // Schedule next receiveAsync() if the incoming queue is not full. Use a different thread to avoid
                    // recursion and stack overflow
                    client.eventLoopGroup().execute(() -> {
                        receiveMessageFromConsumer(consumer);
                    });
                }
            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    private void resumeReceivingFromPausedConsumersIfNeeded() {
        lock.readLock().lock();
        try {
            if (incomingMessages.size() <= sharedQueueResumeThreshold && !pausedConsumers.isEmpty()) {
                while (true) {
                    ConsumerImpl consumer = pausedConsumers.poll();
                    if (consumer == null) {
                        break;
                    }

                    // if messages are readily available on consumer we will attempt to writeLock on the same thread
                    client.eventLoopGroup().execute(() -> {
                        receiveMessageFromConsumer(consumer);
                    });
                }
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    protected Message internalReceive() throws PulsarClientException {
        Message message;
        try {
            message = incomingMessages.take();
            unAckedMessageTracker.add((MessageIdImpl) message.getMessageId());
            resumeReceivingFromPausedConsumersIfNeeded();
            return message;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PulsarClientException(e);
        }
    }

    @Override
    protected Message internalReceive(int timeout, TimeUnit unit) throws PulsarClientException {
        Message message;
        try {
            message = incomingMessages.poll(timeout, unit);
            if (message != null) {
                unAckedMessageTracker.add((MessageIdImpl) message.getMessageId());
            }
            resumeReceivingFromPausedConsumersIfNeeded();
            return message;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PulsarClientException(e);
        }
    }

    @Override
    protected CompletableFuture<Message> internalReceiveAsync() {
        CompletableFuture<Message> result = new CompletableFuture<>();
        Message message;
        try {
            lock.writeLock().lock();
            message = incomingMessages.poll(0, TimeUnit.SECONDS);
            if (message == null) {
                pendingReceives.add(result);
            } else {
                unAckedMessageTracker.add((MessageIdImpl) message.getMessageId());
                resumeReceivingFromPausedConsumersIfNeeded();
                result.complete(message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.completeExceptionally(new PulsarClientException(e));
        } finally {
            lock.writeLock().unlock();
        }

        return result;
    }

    @Override
    protected CompletableFuture<Void> doAcknowledge(MessageId messageId, AckType ackType) {
        checkArgument(messageId instanceof MessageIdImpl);

        if (getState() != State.Ready) {
            return FutureUtil.failedFuture(new PulsarClientException("Consumer already closed"));
        }

        if (ackType == AckType.Cumulative) {
            return FutureUtil.failedFuture(new PulsarClientException.NotSupportedException(
                    "Cumulative acknowledge not supported for partitioned topics"));
        } else {

            ConsumerImpl consumer = consumers.get(((MessageIdImpl) messageId).getPartitionIndex());
            return consumer.doAcknowledge(messageId, ackType).thenRun(() ->
                    unAckedMessageTracker.remove((MessageIdImpl) messageId));
        }

    }

    @Override
    public CompletableFuture<Void> unsubscribeAsync() {
        if (getState() == State.Closing || getState() == State.Closed) {
            return FutureUtil.failedFuture(
                    new PulsarClientException.AlreadyClosedException("Partitioned Consumer was already closed"));
        }
        setState(State.Closing);

        AtomicReference<Throwable> unsubscribeFail = new AtomicReference<Throwable>();
        AtomicInteger completed = new AtomicInteger(numPartitions);
        CompletableFuture<Void> unsubscribeFuture = new CompletableFuture<>();
        for (Consumer consumer : consumers) {
            if (consumer != null) {
                consumer.unsubscribeAsync().handle((unsubscribed, ex) -> {
                    if (ex != null) {
                        unsubscribeFail.compareAndSet(null, ex);
                    }
                    if (completed.decrementAndGet() == 0) {
                        if (unsubscribeFail.get() == null) {
                            setState(State.Closed);
                            unAckedMessageTracker.close();
                            unsubscribeFuture.complete(null);
                            log.info("[{}] [{}] Unsubscribed Partitioned Consumer", topic, subscription);
                        } else {
                            setState(State.Failed);
                            unsubscribeFuture.completeExceptionally(unsubscribeFail.get());
                            log.error("[{}] [{}] Could not unsubscribe Partitioned Consumer", topic, subscription,
                                    unsubscribeFail.get().getCause());
                        }
                    }

                    return null;
                });
            }

        }

        return unsubscribeFuture;
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        if (getState() == State.Closing || getState() == State.Closed) {
            unAckedMessageTracker.close();
            return CompletableFuture.completedFuture(null);
        }
        setState(State.Closing);

        AtomicReference<Throwable> closeFail = new AtomicReference<Throwable>();
        AtomicInteger completed = new AtomicInteger(numPartitions);
        CompletableFuture<Void> closeFuture = new CompletableFuture<>();
        for (Consumer consumer : consumers) {
            if (consumer != null) {
                consumer.closeAsync().handle((closed, ex) -> {
                    if (ex != null) {
                        closeFail.compareAndSet(null, ex);
                    }
                    if (completed.decrementAndGet() == 0) {
                        if (closeFail.get() == null) {
                            setState(State.Closed);
                            unAckedMessageTracker.close();
                            closeFuture.complete(null);
                            log.info("[{}] [{}] Closed Partitioned Consumer", topic, subscription);
                            client.cleanupConsumer(this);
                            // fail all pending-receive futures to notify application
                            failPendingReceive();
                        } else {
                            setState(State.Failed);
                            closeFuture.completeExceptionally(closeFail.get());
                            log.error("[{}] [{}] Could not close Partitioned Consumer", topic, subscription,
                                    closeFail.get().getCause());
                        }
                    }

                    return null;
                });
            }

        }

        return closeFuture;
    }

    private void failPendingReceive() {
        lock.readLock().lock();
        try {
            if (listenerExecutor != null && !listenerExecutor.isShutdown()) {
                while (!pendingReceives.isEmpty()) {
                    CompletableFuture<Message> receiveFuture = pendingReceives.poll();
                    if (receiveFuture != null) {
                        receiveFuture.completeExceptionally(
                                new PulsarClientException.AlreadyClosedException("Consumer is already closed"));
                    } else {
                        break;
                    }
                }
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean isConnected() {
        for (ConsumerImpl consumer : consumers) {
            if (!consumer.isConnected()) {
                return false;
            }
        }
        return true;
    }

    @Override
    void connectionFailed(PulsarClientException exception) {
        // noop

    }

    @Override
    void connectionOpened(ClientCnx cnx) {
        // noop

    }

    void messageReceived(Message message) {
        lock.writeLock().lock();
        try {
            unAckedMessageTracker.add((MessageIdImpl) message.getMessageId());
            if (log.isDebugEnabled()) {
                log.debug("[{}][{}] Received message from partitioned-consumer {}", topic, subscription, message.getMessageId());
            }
            // if asyncReceive is waiting : return message to callback without adding to incomingMessages queue
            if (!pendingReceives.isEmpty()) {
                CompletableFuture<Message> receivedFuture = pendingReceives.poll();
                listenerExecutor.execute(() -> receivedFuture.complete(message));
            } else {
                // Enqueue the message so that it can be retrieved when application calls receive()
                // Waits for the queue to have space for the message
                // This should never block cause PartitonedConsumerImpl should always use GrowableArrayBlockingQueue
                incomingMessages.put(message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.writeLock().unlock();
        }

        if (listener != null) {
            // Trigger the notification on the message listener in a separate thread to avoid blocking the networking
            // thread while the message processing happens
            listenerExecutor.execute(() -> {
                Message msg;
                try {
                    msg = internalReceive();
                } catch (PulsarClientException e) {
                    log.warn("[{}] [{}] Failed to dequeue the message for listener", topic, subscription, e);
                    return;
                }

                try {
                    if (log.isDebugEnabled()) {
                        log.debug("[{}][{}] Calling message listener for message {}", topic, subscription, message.getMessageId());
                    }
                    listener.received(PartitionedConsumerImpl.this, msg);
                } catch (Throwable t) {
                    log.error("[{}][{}] Message listener error in processing message: {}", topic, subscription, message,
                            t);
                }
            });
        }
    }

    @Override
    String getHandlerName() {
        return subscription;
    }

    private ConsumerConfiguration getInternalConsumerConfig() {
        ConsumerConfiguration internalConsumerConfig = new ConsumerConfiguration();
        internalConsumerConfig.setReceiverQueueSize(conf.getReceiverQueueSize());
        internalConsumerConfig.setSubscriptionType(conf.getSubscriptionType());
        internalConsumerConfig.setConsumerName(consumerName);
        if (conf.getCryptoKeyReader() != null) {
            internalConsumerConfig.setCryptoKeyReader(conf.getCryptoKeyReader());
            internalConsumerConfig.setCryptoFailureAction(conf.getCryptoFailureAction());
        }
        if (conf.getAckTimeoutMillis() != 0) {
            internalConsumerConfig.setAckTimeout(conf.getAckTimeoutMillis(), TimeUnit.MILLISECONDS);
        }

        return internalConsumerConfig;
    }

    @Override
    public void redeliverUnacknowledgedMessages() {
        synchronized (this) {
            for (ConsumerImpl c : consumers) {
                c.redeliverUnacknowledgedMessages();
            }
            incomingMessages.clear();
            unAckedMessageTracker.clear();
            resumeReceivingFromPausedConsumersIfNeeded();
        }
    }

    @Override
    public void redeliverUnacknowledgedMessages(Set<MessageIdImpl> messageIds) {
        if (conf.getSubscriptionType() != SubscriptionType.Shared) {
            // We cannot redeliver single messages if subscription type is not Shared
            redeliverUnacknowledgedMessages();
            return;
        }
        removeExpiredMessagesFromQueue(messageIds);
        messageIds.stream()
                .collect(Collectors.groupingBy(MessageIdImpl::getPartitionIndex, Collectors.toSet()))
                .forEach((partitionIndex, messageIds1) ->
                        consumers.get(partitionIndex).redeliverUnacknowledgedMessages(messageIds1));
        resumeReceivingFromPausedConsumersIfNeeded();
    }

    @Override
    public void seek(MessageId messageId) throws PulsarClientException {
        try {
            seekAsync(messageId).get();
        } catch (ExecutionException e) {
            throw new PulsarClientException(e.getCause());
        } catch (InterruptedException e) {
            throw new PulsarClientException(e);
        }
    }

    @Override
    public CompletableFuture<Void> seekAsync(MessageId messageId) {
        return FutureUtil.failedFuture(new PulsarClientException("Seek operation not supported on partitioned topics"));
    }

    /**
     * helper method that returns current state of data structure used to track acks for batch messages
     *
     * @return true if all batch messages have been acknowledged
     */
    public boolean isBatchingAckTrackerEmpty() {
        boolean state = true;
        for (Consumer consumer : consumers) {
            state &= ((ConsumerImpl) consumer).isBatchingAckTrackerEmpty();
        }
        return state;
    }

    List<ConsumerImpl> getConsumers() {
        return consumers;
    }

    @Override
    public int getAvailablePermits() {
        return consumers.stream().mapToInt(ConsumerImpl::getAvailablePermits).sum();
    }

    @Override
    public boolean hasReachedEndOfTopic() {
        return consumers.stream().allMatch(Consumer::hasReachedEndOfTopic);
    }

    @Override
    public int numMessagesInQueue() {
        return incomingMessages.size() + consumers.stream().mapToInt(ConsumerImpl::numMessagesInQueue).sum();
    }

    @Override
    public synchronized ConsumerStats getStats() {
        if (stats == null) {
            return null;
        }
        stats.reset();
        for (int i = 0; i < numPartitions; i++) {
            stats.updateCumulativeStats(consumers.get(i).getStats());
        }
        return stats;
    }

    public UnAckedMessageTracker getUnAckedMessageTracker() {
        return unAckedMessageTracker;
    }

    private void removeExpiredMessagesFromQueue(Set<MessageIdImpl> messageIds) {
        Message peek = incomingMessages.peek();
        if (peek != null) {
            if (!messageIds.contains((MessageIdImpl) peek.getMessageId())) {
                // first message is not expired, then no message is expired in queue.
                return;
            }

            // try not to remove elements that are added while we remove
            Message message = incomingMessages.poll();
            while (message != null) {
                MessageIdImpl messageId = (MessageIdImpl) message.getMessageId();
                if (!messageIds.contains(messageId)) {
                    messageIds.add(messageId);
                    break;
                }
                message = incomingMessages.poll();
            }
        }
    }

    private static final Logger log = LoggerFactory.getLogger(PartitionedConsumerImpl.class);
}
