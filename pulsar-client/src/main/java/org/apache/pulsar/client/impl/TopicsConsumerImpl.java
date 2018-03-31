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
import static com.google.common.base.Preconditions.checkState;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerStats;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.conf.ConsumerConfigurationData;
import org.apache.pulsar.client.util.ConsumerName;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandAck.AckType;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.util.FutureUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TopicsConsumerImpl<T> extends ConsumerBase<T> {

    // All topics should be in same namespace
    protected NamespaceName namespaceName;

    // Map <topic+partition, consumer>, when get do ACK, consumer will by find by topic name
    private final ConcurrentHashMap<String, ConsumerImpl<T>> consumers;

    // Map <topic, partitionNumber>, store partition number for each topic
    protected final ConcurrentHashMap<String, Integer> topics;

    // Queue of partition consumers on which we have stopped calling receiveAsync() because the
    // shared incoming queue was full
    private final ConcurrentLinkedQueue<ConsumerImpl<T>> pausedConsumers;

    // Threshold for the shared queue. When the size of the shared queue goes below the threshold, we are going to
    // resume receiving from the paused consumer partitions
    private final int sharedQueueResumeThreshold;

    // sum of topicPartitions, simple topic has 1, partitioned topic equals to partition number.
    AtomicInteger numberTopicPartitions;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final ConsumerStatsRecorder stats;
    private final UnAckedMessageTracker unAckedMessageTracker;
    private final ConsumerConfigurationData<T> internalConfig;

    TopicsConsumerImpl(PulsarClientImpl client, ConsumerConfigurationData<T> conf, ExecutorService listenerExecutor,
                       CompletableFuture<Consumer<T>> subscribeFuture, Schema<T> schema) {
        super(client, "TopicsConsumerFakeTopicName" + ConsumerName.generateRandomName(), conf,
                Math.max(2, conf.getReceiverQueueSize()), listenerExecutor, subscribeFuture, schema);

        checkArgument(conf.getReceiverQueueSize() > 0,
            "Receiver queue size needs to be greater than 0 for Topics Consumer");

        this.topics = new ConcurrentHashMap<>();
        this.consumers = new ConcurrentHashMap<>();
        this.pausedConsumers = new ConcurrentLinkedQueue<>();
        this.sharedQueueResumeThreshold = maxReceiverQueueSize / 2;
        this.numberTopicPartitions = new AtomicInteger(0);

        if (conf.getAckTimeoutMillis() != 0) {
            this.unAckedMessageTracker = new UnAckedTopicMessageTracker(client, this, conf.getAckTimeoutMillis());
        } else {
            this.unAckedMessageTracker = UnAckedMessageTracker.UNACKED_MESSAGE_TRACKER_DISABLED;
        }

        this.internalConfig = getInternalConsumerConfig();
        this.stats = client.getConfiguration().getStatsIntervalSeconds() > 0 ? new ConsumerStatsRecorderImpl() : null;

        if (conf.getTopicNames().isEmpty()) {
            this.namespaceName = null;
            setState(State.Ready);
            subscribeFuture().complete(TopicsConsumerImpl.this);
            return;
        }

        checkArgument(conf.getTopicNames().isEmpty() || topicNamesValid(conf.getTopicNames()), "Topics should have same namespace.");
        this.namespaceName = conf.getTopicNames().stream().findFirst()
                .flatMap(s -> Optional.of(TopicName.get(s).getNamespaceObject())).get();

        List<CompletableFuture<Void>> futures = conf.getTopicNames().stream().map(t -> subscribeAsync(t))
                .collect(Collectors.toList());
        FutureUtil.waitForAll(futures)
            .thenAccept(finalFuture -> {
                try {
                    if (numberTopicPartitions.get() > maxReceiverQueueSize) {
                        setMaxReceiverQueueSize(numberTopicPartitions.get());
                    }
                    setState(State.Ready);
                    // We have successfully created N consumers, so we can start receiving messages now
                    startReceivingMessages(consumers.values().stream().collect(Collectors.toList()));
                    subscribeFuture().complete(TopicsConsumerImpl.this);
                    log.info("[{}] [{}] Created topics consumer with {} sub-consumers",
                        topic, subscription, numberTopicPartitions.get());
                } catch (PulsarClientException e) {
                    log.warn("[{}] Failed startReceivingMessages while subscribe topics: {}", topic, e.getMessage());
                    subscribeFuture.completeExceptionally(e);
                }})
            .exceptionally(ex -> {
                log.warn("[{}] Failed to subscribe topics: {}", topic, ex.getMessage());
                subscribeFuture.completeExceptionally(ex);
                return null;
            });
    }

    // Check topics are valid.
    // - each topic is valid,
    // - every topic has same namespace,
    // - topic names are unique.
    private static boolean topicNamesValid(Collection<String> topics) {
        checkState(topics != null && topics.size() >= 1,
            "topics should should contain more than 1 topic");

        final String namespace = TopicName.get(topics.stream().findFirst().get()).getNamespace();

        Optional<String> result = topics.stream()
            .filter(topic -> {
                boolean topicInvalid = !TopicName.isValid(topic);
                if (topicInvalid) {
                    return true;
                }

                String newNamespace =  TopicName.get(topic).getNamespace();
                if (!namespace.equals(newNamespace)) {
                    return true;
                } else {
                    return false;
                }
            }).findFirst();

        if (result.isPresent()) {
            log.warn("[{}] Received invalid topic name.  {}/{}", result.get());
            return false;
        }

        // check topic names are unique
        HashSet<String> set = new HashSet<>(topics);
        if (set.size() == topics.size()) {
            return true;
        } else {
            log.warn("Topic names not unique. unique/all : {}/{}", set.size(), topics.size());
            return false;
        }
    }

    private void startReceivingMessages(List<ConsumerImpl<T>> newConsumers) throws PulsarClientException {
        if (log.isDebugEnabled()) {
            log.debug("[{}] startReceivingMessages for {} new consumers in topics consumer, state: {}",
                topic, newConsumers.size(), getState());
        }
        if (getState() == State.Ready) {
            newConsumers.forEach(consumer -> {
                consumer.sendFlowPermitsToBroker(consumer.getConnectionHandler().cnx(), conf.getReceiverQueueSize());
                receiveMessageFromConsumer(consumer);
            });
        }
    }

    private void receiveMessageFromConsumer(ConsumerImpl<T> consumer) {
        consumer.receiveAsync().thenAccept(message -> {
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] Receive message from sub consumer:{}",
                    topic, subscription, consumer.getTopic());
            }
            // Process the message, add to the queue and trigger listener or async callback
            messageReceived(consumer, message);

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

    private void messageReceived(ConsumerImpl<T> consumer, Message<T> message) {
        checkArgument(message instanceof MessageImpl);
        lock.writeLock().lock();
        try {
            TopicMessageImpl<T> topicMessage = new TopicMessageImpl<>(consumer.getTopic(), message);
            unAckedMessageTracker.add(topicMessage.getMessageId());

            if (log.isDebugEnabled()) {
                log.debug("[{}][{}] Received message from topics-consumer {}",
                    topic, subscription, message.getMessageId());
            }

            // if asyncReceive is waiting : return message to callback without adding to incomingMessages queue
            if (!pendingReceives.isEmpty()) {
                CompletableFuture<Message<T>> receivedFuture = pendingReceives.poll();
                listenerExecutor.execute(() -> receivedFuture.complete(topicMessage));
            } else {
                // Enqueue the message so that it can be retrieved when application calls receive()
                // Waits for the queue to have space for the message
                // This should never block cause TopicsConsumerImpl should always use GrowableArrayBlockingQueue
                incomingMessages.put(topicMessage);
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
                Message<T> msg;
                try {
                    msg = internalReceive();
                } catch (PulsarClientException e) {
                    log.warn("[{}] [{}] Failed to dequeue the message for listener", topic, subscription, e);
                    return;
                }

                try {
                    if (log.isDebugEnabled()) {
                        log.debug("[{}][{}] Calling message listener for message {}",
                            topic, subscription, message.getMessageId());
                    }
                    listener.received(TopicsConsumerImpl.this, msg);
                } catch (Throwable t) {
                    log.error("[{}][{}] Message listener error in processing message: {}",
                        topic, subscription, message, t);
                }
            });
        }
    }

    private void resumeReceivingFromPausedConsumersIfNeeded() {
        lock.readLock().lock();
        try {
            if (incomingMessages.size() <= sharedQueueResumeThreshold && !pausedConsumers.isEmpty()) {
                while (true) {
                    ConsumerImpl<T> consumer = pausedConsumers.poll();
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
    protected Message<T> internalReceive() throws PulsarClientException {
        Message<T> message;
        try {
            message = incomingMessages.take();
            checkState(message instanceof TopicMessageImpl);
            unAckedMessageTracker.add(message.getMessageId());
            resumeReceivingFromPausedConsumersIfNeeded();
            return message;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PulsarClientException(e);
        }
    }

    @Override
    protected Message<T> internalReceive(int timeout, TimeUnit unit) throws PulsarClientException {
        Message<T> message;
        try {
            message = incomingMessages.poll(timeout, unit);
            if (message != null) {
                checkArgument(message instanceof TopicMessageImpl);
                unAckedMessageTracker.add(message.getMessageId());
            }
            resumeReceivingFromPausedConsumersIfNeeded();
            return message;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PulsarClientException(e);
        }
    }

    @Override
    protected CompletableFuture<Message<T>> internalReceiveAsync() {
        CompletableFuture<Message<T>> result = new CompletableFuture<>();
        Message<T> message;
        try {
            lock.writeLock().lock();
            message = incomingMessages.poll(0, TimeUnit.SECONDS);
            if (message == null) {
                pendingReceives.add(result);
            } else {
                checkState(message instanceof TopicMessageImpl);
                unAckedMessageTracker.add(message.getMessageId());
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
    protected CompletableFuture<Void> doAcknowledge(MessageId messageId, AckType ackType,
                                                    Map<String,Long> properties) {
        checkArgument(messageId instanceof TopicMessageIdImpl);
        TopicMessageIdImpl messageId1 = (TopicMessageIdImpl) messageId;

        if (getState() != State.Ready) {
            return FutureUtil.failedFuture(new PulsarClientException("Consumer already closed"));
        }

        if (ackType == AckType.Cumulative) {
            return FutureUtil.failedFuture(new PulsarClientException.NotSupportedException(
                    "Cumulative acknowledge not supported for topics consumer"));
        } else {
            ConsumerImpl<T> consumer = consumers.get(messageId1.getTopicName());

            MessageId innerId = messageId1.getInnerMessageId();
            return consumer.doAcknowledge(innerId, ackType, properties)
                .thenRun(() ->
                    unAckedMessageTracker.remove(messageId1));
        }
    }

    @Override
    public CompletableFuture<Void> unsubscribeAsync() {
        if (getState() == State.Closing || getState() == State.Closed) {
            return FutureUtil.failedFuture(
                    new PulsarClientException.AlreadyClosedException("Topics Consumer was already closed"));
        }
        setState(State.Closing);

        CompletableFuture<Void> unsubscribeFuture = new CompletableFuture<>();
        List<CompletableFuture<Void>> futureList = consumers.values().stream()
            .map(c -> c.unsubscribeAsync()).collect(Collectors.toList());

        FutureUtil.waitForAll(futureList)
            .whenComplete((r, ex) -> {
                if (ex == null) {
                    setState(State.Closed);
                    unAckedMessageTracker.close();
                    unsubscribeFuture.complete(null);
                    log.info("[{}] [{}] [{}] Unsubscribed Topics Consumer",
                        topic, subscription, consumerName);
                } else {
                    setState(State.Failed);
                    unsubscribeFuture.completeExceptionally(ex);
                    log.error("[{}] [{}] [{}] Could not unsubscribe Topics Consumer",
                        topic, subscription, consumerName, ex.getCause());
                }
            });

        return unsubscribeFuture;
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        if (getState() == State.Closing || getState() == State.Closed) {
            unAckedMessageTracker.close();
            return CompletableFuture.completedFuture(null);
        }
        setState(State.Closing);

        CompletableFuture<Void> closeFuture = new CompletableFuture<>();
        List<CompletableFuture<Void>> futureList = consumers.values().stream()
            .map(c -> c.closeAsync()).collect(Collectors.toList());

        FutureUtil.waitForAll(futureList)
            .whenComplete((r, ex) -> {
                if (ex == null) {
                    setState(State.Closed);
                    unAckedMessageTracker.close();
                    closeFuture.complete(null);
                    log.info("[{}] [{}] Closed Topics Consumer", topic, subscription);
                    client.cleanupConsumer(this);
                    // fail all pending-receive futures to notify application
                    failPendingReceive();
                } else {
                    setState(State.Failed);
                    closeFuture.completeExceptionally(ex);
                    log.error("[{}] [{}] Could not close Topics Consumer", topic, subscription,
                        ex.getCause());
                }
            });

        return closeFuture;
    }

    private void failPendingReceive() {
        lock.readLock().lock();
        try {
            if (listenerExecutor != null && !listenerExecutor.isShutdown()) {
                while (!pendingReceives.isEmpty()) {
                    CompletableFuture<Message<T>> receiveFuture = pendingReceives.poll();
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
        return consumers.values().stream().allMatch(consumer -> consumer.isConnected());
    }

    @Override
    String getHandlerName() {
        return subscription;
    }

    private ConsumerConfigurationData<T> getInternalConsumerConfig() {
        ConsumerConfigurationData<T> internalConsumerConfig = new ConsumerConfigurationData<>();
        internalConsumerConfig.setSubscriptionName(subscription);
        internalConsumerConfig.setReceiverQueueSize(conf.getReceiverQueueSize());
        internalConsumerConfig.setSubscriptionType(conf.getSubscriptionType());
        internalConsumerConfig.setConsumerName(consumerName);
        if (conf.getCryptoKeyReader() != null) {
            internalConsumerConfig.setCryptoKeyReader(conf.getCryptoKeyReader());
            internalConsumerConfig.setCryptoFailureAction(conf.getCryptoFailureAction());
        }
        if (conf.getAckTimeoutMillis() != 0) {
            internalConsumerConfig.setAckTimeoutMillis(conf.getAckTimeoutMillis());
        }

        return internalConsumerConfig;
    }

    @Override
    public void redeliverUnacknowledgedMessages() {
        synchronized (this) {
            consumers.values().stream().forEach(consumer -> consumer.redeliverUnacknowledgedMessages());
            incomingMessages.clear();
            unAckedMessageTracker.clear();
            resumeReceivingFromPausedConsumersIfNeeded();
        }
    }

    @Override
    public void redeliverUnacknowledgedMessages(Set<MessageId> messageIds) {
        checkArgument(messageIds.stream().findFirst().get() instanceof TopicMessageIdImpl);

        if (conf.getSubscriptionType() != SubscriptionType.Shared) {
            // We cannot redeliver single messages if subscription type is not Shared
            redeliverUnacknowledgedMessages();
            return;
        }
        removeExpiredMessagesFromQueue(messageIds);
        messageIds.stream().map(messageId -> (TopicMessageIdImpl)messageId)
            .collect(Collectors.groupingBy(TopicMessageIdImpl::getTopicName, Collectors.toSet()))
            .forEach((topicName, messageIds1) ->
                consumers.get(topicName)
                    .redeliverUnacknowledgedMessages(messageIds1.stream()
                        .map(mid -> mid.getInnerMessageId()).collect(Collectors.toSet())));
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
        return FutureUtil.failedFuture(new PulsarClientException("Seek operation not supported on topics consumer"));
    }

    @Override
    public int getAvailablePermits() {
        return consumers.values().stream().mapToInt(ConsumerImpl::getAvailablePermits).sum();
    }

    @Override
    public boolean hasReachedEndOfTopic() {
        return consumers.values().stream().allMatch(Consumer::hasReachedEndOfTopic);
    }

    @Override
    public int numMessagesInQueue() {
        return incomingMessages.size() + consumers.values().stream().mapToInt(ConsumerImpl::numMessagesInQueue).sum();
    }

    @Override
    public synchronized ConsumerStats getStats() {
        if (stats == null) {
            return null;
        }
        stats.reset();

        consumers.values().stream().forEach(consumer -> stats.updateCumulativeStats(consumer.getStats()));
        return stats;
    }

    public UnAckedMessageTracker getUnAckedMessageTracker() {
        return unAckedMessageTracker;
    }

    private void removeExpiredMessagesFromQueue(Set<MessageId> messageIds) {
        Message<T> peek = incomingMessages.peek();
        if (peek != null) {
            if (!messageIds.contains(peek.getMessageId())) {
                // first message is not expired, then no message is expired in queue.
                return;
            }

            // try not to remove elements that are added while we remove
            Message<T> message = incomingMessages.poll();
            checkState(message instanceof TopicMessageImpl);
            while (message != null) {
                MessageId messageId = message.getMessageId();
                if (!messageIds.contains(messageId)) {
                    messageIds.add(messageId);
                    break;
                }
                message = incomingMessages.poll();
            }
        }
    }

    private boolean topicNameValid(String topicName) {
        checkArgument(TopicName.isValid(topicName), "Invalid topic name:" + topicName);
        checkArgument(!topics.containsKey(topicName), "Topics already contains topic:" + topicName);

        if (this.namespaceName != null) {
            checkArgument(TopicName.get(topicName).getNamespace().toString().equals(this.namespaceName.toString()),
                "Topic " + topicName + " not in same namespace with Topics");
        }

        return true;
    }

    // subscribe one more given topic
    public CompletableFuture<Void> subscribeAsync(String topicName) {
        if (!topicNameValid(topicName)) {
            return FutureUtil.failedFuture(
                new PulsarClientException.AlreadyClosedException("Topic name not valid"));
        }

        if (getState() == State.Closing || getState() == State.Closed) {
            return FutureUtil.failedFuture(
                new PulsarClientException.AlreadyClosedException("Topics Consumer was already closed"));
        }

        CompletableFuture<Void> subscribeResult = new CompletableFuture<>();
        final AtomicInteger partitionNumber = new AtomicInteger(0);

        client.getPartitionedTopicMetadata(topicName).thenAccept(metadata -> {
            if (log.isDebugEnabled()) {
                log.debug("Received topic {} metadata.partitions: {}", topicName, metadata.partitions);
            }

            List<CompletableFuture<Consumer<T>>> futureList;

            if (metadata.partitions > 1) {
                this.topics.putIfAbsent(topicName, metadata.partitions);
                numberTopicPartitions.addAndGet(metadata.partitions);
                partitionNumber.addAndGet(metadata.partitions);

                futureList = IntStream
                    .range(0, partitionNumber.get())
                    .mapToObj(
                        partitionIndex -> {
                            String partitionName = TopicName.get(topicName).getPartition(partitionIndex).toString();
                            CompletableFuture<Consumer<T>> subFuture = new CompletableFuture<>();
                            ConsumerImpl<T> newConsumer = new ConsumerImpl<>(client, partitionName, internalConfig,
                                    client.externalExecutorProvider().getExecutor(), partitionIndex, subFuture, schema);
                            consumers.putIfAbsent(newConsumer.getTopic(), newConsumer);
                            return subFuture;
                        })
                    .collect(Collectors.toList());
            } else {
                this.topics.putIfAbsent(topicName, 1);
                numberTopicPartitions.incrementAndGet();
                partitionNumber.incrementAndGet();

                CompletableFuture<Consumer<T>> subFuture = new CompletableFuture<>();
                ConsumerImpl<T> newConsumer = new ConsumerImpl<>(client, topicName, internalConfig,
                        client.externalExecutorProvider().getExecutor(), 0, subFuture, schema);
                consumers.putIfAbsent(newConsumer.getTopic(), newConsumer);

                futureList = Collections.singletonList(subFuture);
            }

            FutureUtil.waitForAll(futureList)
                .thenAccept(finalFuture -> {
                    try {
                        if (numberTopicPartitions.get() > maxReceiverQueueSize) {
                            setMaxReceiverQueueSize(numberTopicPartitions.get());
                        }
                        int numTopics = this.topics.values().stream().mapToInt(Integer::intValue).sum();
                        checkState(numberTopicPartitions.get() == numTopics,
                            "numberTopicPartitions " + numberTopicPartitions.get()
                                + " not equals expected: " + numTopics);

                        // We have successfully created new consumers, so we can start receiving messages for them
                        startReceivingMessages(
                            consumers.values().stream()
                                .filter(consumer1 -> {
                                    String consumerTopicName = consumer1.getTopic();
                                    if (TopicName.get(consumerTopicName).getPartitionedTopicName().equals(
                                        TopicName.get(topicName).getPartitionedTopicName().toString())) {
                                        return true;
                                    } else {
                                        return false;
                                    }
                                })
                                .collect(Collectors.toList()));

                        subscribeResult.complete(null);
                        log.info("[{}] [{}] Success subscribe new topic {} in topics consumer, numberTopicPartitions {}",
                            topic, subscription, topicName, numberTopicPartitions.get());
                        if (this.namespaceName == null) {
                            this.namespaceName = TopicName.get(topicName).getNamespaceObject();
                        }
                        return;
                    } catch (PulsarClientException e) {
                        handleSubscribeOneTopicError(topicName, e);
                        subscribeResult.completeExceptionally(e);
                    }
                })
                .exceptionally(ex -> {
                    handleSubscribeOneTopicError(topicName, ex);
                    subscribeResult.completeExceptionally(ex);
                    return null;
                });
        }).exceptionally(ex1 -> {
            log.warn("[{}] Failed to get partitioned topic metadata: {}", topicName, ex1.getMessage());
            subscribeResult.completeExceptionally(ex1);
            return null;
        });

        return subscribeResult;
    }

    // handling failure during subscribe new topic, unsubscribe success created partitions
    private void handleSubscribeOneTopicError(String topicName, Throwable error) {
        log.warn("[{}] Failed to subscribe for topic [{}] in topics consumer ", topic, topicName, error.getMessage());

        consumers.values().stream().filter(consumer1 -> {
            String consumerTopicName = consumer1.getTopic();
            if (TopicName.get(consumerTopicName).getPartitionedTopicName().equals(topicName)) {
                return true;
            } else {
                return false;
            }
        }).forEach(consumer2 ->  {
            consumer2.closeAsync().handle((ok, closeException) -> {
                consumer2.subscribeFuture().completeExceptionally(error);
                return null;
            });
            consumers.remove(consumer2.getTopic());
        });

        topics.remove(topicName);
        checkState(numberTopicPartitions.get() == consumers.values().size());
    }

    // un-subscribe a given topic
    public CompletableFuture<Void> unsubscribeAsync(String topicName) {
        checkArgument(TopicName.isValid(topicName), "Invalid topic name:" + topicName);

        if (getState() == State.Closing || getState() == State.Closed) {
            return FutureUtil.failedFuture(
                new PulsarClientException.AlreadyClosedException("Topics Consumer was already closed"));
        }

        CompletableFuture<Void> unsubscribeFuture = new CompletableFuture<>();
        String topicPartName = TopicName.get(topicName).getPartitionedTopicName();

        List<ConsumerImpl<T>> consumersToUnsub = consumers.values().stream()
            .filter(consumer -> {
                String consumerTopicName = consumer.getTopic();
                if (TopicName.get(consumerTopicName).getPartitionedTopicName().equals(topicPartName)) {
                    return true;
                } else {
                    return false;
                }
            }).collect(Collectors.toList());

        List<CompletableFuture<Void>> futureList = consumersToUnsub.stream()
            .map(ConsumerImpl::unsubscribeAsync).collect(Collectors.toList());

        FutureUtil.waitForAll(futureList)
            .whenComplete((r, ex) -> {
                if (ex == null) {
                    consumersToUnsub.forEach(consumer1 -> {
                        consumers.remove(consumer1.getTopic());
                        pausedConsumers.remove(consumer1);
                        numberTopicPartitions.decrementAndGet();
                    });

                    topics.remove(topicName);
                    ((UnAckedTopicMessageTracker) unAckedMessageTracker).removeTopicMessages(topicName);

                    unsubscribeFuture.complete(null);
                    log.info("[{}] [{}] [{}] Unsubscribed Topics Consumer, numberTopicPartitions: {}",
                        topicName, subscription, consumerName, numberTopicPartitions);
                } else {
                    unsubscribeFuture.completeExceptionally(ex);
                    setState(State.Failed);
                    log.error("[{}] [{}] [{}] Could not unsubscribe Topics Consumer",
                        topicName, subscription, consumerName, ex.getCause());
                }
            });

        return unsubscribeFuture;
    }

    // get topics name
    public List<String> getTopics() {
        return topics.keySet().stream().collect(Collectors.toList());
    }

    // get partitioned topics name
    public List<String> getPartitionedTopics() {
        return consumers.keySet().stream().collect(Collectors.toList());
    }

    // get partitioned consumers
    public List<ConsumerImpl<T>> getConsumers() {
        return consumers.values().stream().collect(Collectors.toList());
    }

    private static final Logger log = LoggerFactory.getLogger(TopicsConsumerImpl.class);
}
