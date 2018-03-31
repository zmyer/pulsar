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
package org.apache.pulsar.broker.service.persistent;

import static java.util.stream.Collectors.toSet;
import static org.apache.pulsar.broker.cache.ConfigurationCacheService.POLICIES;
import static org.apache.pulsar.broker.service.persistent.PersistentTopic.MESSAGE_RATE_BACKOFF_MS;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.apache.bookkeeper.mledger.AsyncCallbacks.ReadEntriesCallback;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedCursor;
import org.apache.bookkeeper.mledger.ManagedLedgerException;
import org.apache.bookkeeper.mledger.ManagedLedgerException.NoMoreEntriesToReadException;
import org.apache.bookkeeper.mledger.ManagedLedgerException.TooManyRequestsException;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.admin.AdminResource;
import org.apache.pulsar.broker.service.AbstractDispatcherMultipleConsumers;
import org.apache.pulsar.broker.service.BrokerServiceException;
import org.apache.pulsar.broker.service.BrokerServiceException.ConsumerBusyException;
import org.apache.pulsar.broker.service.Consumer;
import org.apache.pulsar.broker.service.Consumer.SendMessageInfo;
import org.apache.pulsar.broker.service.Dispatcher;
import org.apache.pulsar.client.impl.Backoff;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSubscribe.SubType;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.Policies;
import org.apache.pulsar.common.util.Codec;
import org.apache.pulsar.common.util.collections.ConcurrentLongPairSet;
import org.apache.pulsar.utils.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Lists;

/**
 */
public class PersistentDispatcherMultipleConsumers  extends AbstractDispatcherMultipleConsumers implements Dispatcher, ReadEntriesCallback {

    private static final int MaxReadBatchSize = 100;
    private static final int MaxRoundRobinBatchSize = 20;

    private final PersistentTopic topic;
    private final ManagedCursor cursor;

    private CompletableFuture<Void> closeFuture = null;
    private ConcurrentLongPairSet messagesToReplay;

    private boolean havePendingRead = false;
    private boolean havePendingReplayRead = false;
    private boolean shouldRewindBeforeReadingOrReplaying = false;
    private final String name;

    private int totalAvailablePermits = 0;
    private int readBatchSize;
    private final Backoff readFailureBackoff = new Backoff(15, TimeUnit.SECONDS, 1, TimeUnit.MINUTES, 0, TimeUnit.MILLISECONDS);
    private static final AtomicIntegerFieldUpdater<PersistentDispatcherMultipleConsumers> TOTAL_UNACKED_MESSAGES_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(PersistentDispatcherMultipleConsumers.class, "totalUnackedMessages");
    private volatile int totalUnackedMessages = 0;
    private final int maxUnackedMessages;
    private volatile int blockedDispatcherOnUnackedMsgs = FALSE;
    private static final AtomicIntegerFieldUpdater<PersistentDispatcherMultipleConsumers> BLOCKED_DISPATCHER_ON_UNACKMSG_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(PersistentDispatcherMultipleConsumers.class, "blockedDispatcherOnUnackedMsgs");
    private final ServiceConfiguration serviceConfig;

    enum ReadType {
        Normal, Replay
    }

    public PersistentDispatcherMultipleConsumers(PersistentTopic topic, ManagedCursor cursor) {
        this.cursor = cursor;
        this.name = topic.getName() + " / " + Codec.decode(cursor.getName());
        this.topic = topic;
        this.messagesToReplay = new ConcurrentLongPairSet(512, 2);
        this.readBatchSize = MaxReadBatchSize;
        this.maxUnackedMessages = topic.getBrokerService().pulsar().getConfiguration()
                .getMaxUnackedMessagesPerSubscription();
        this.serviceConfig = topic.getBrokerService().pulsar().getConfiguration();
    }

    @Override
    public synchronized void addConsumer(Consumer consumer) throws BrokerServiceException {
        if (IS_CLOSED_UPDATER.get(this) == TRUE) {
            log.warn("[{}] Dispatcher is already closed. Closing consumer ", name, consumer);
            consumer.disconnect();
            return;
        }
        if (consumerList.isEmpty()) {
            if (havePendingRead || havePendingReplayRead) {
                // There is a pending read from previous run. We must wait for it to complete and then rewind
                shouldRewindBeforeReadingOrReplaying = true;
            } else {
                cursor.rewind();
                shouldRewindBeforeReadingOrReplaying = false;
            }
            messagesToReplay.clear();
        }

        if (isConsumersExceededOnTopic()) {
            log.warn("[{}] Attempting to add consumer to topic which reached max consumers limit", name);
            throw new ConsumerBusyException("Topic reached max consumers limit");
        }

        if (isConsumersExceededOnSubscription()) {
            log.warn("[{}] Attempting to add consumer to subscription which reached max consumers limit", name);
            throw new ConsumerBusyException("Subscription reached max consumers limit");
        }

        consumerList.add(consumer);
        consumerList.sort((c1, c2) -> c1.getPriorityLevel() - c2.getPriorityLevel());
        consumerSet.add(consumer);
    }

    private boolean isConsumersExceededOnTopic() {
        Policies policies;
        try {
            policies = topic.getBrokerService().pulsar().getConfigurationCache().policiesCache()
                    .get(AdminResource.path(POLICIES, TopicName.get(topic.getName()).getNamespace()))
                    .orElseGet(() -> new Policies());
        } catch (Exception e) {
            policies = new Policies();
        }
        final int maxConsumersPerTopic = policies.max_consumers_per_topic > 0 ?
                policies.max_consumers_per_topic :
                serviceConfig.getMaxConsumersPerTopic();
        if (maxConsumersPerTopic > 0 && maxConsumersPerTopic <= topic.getNumberOfConsumers()) {
            return true;
        }
        return false;
    }

    private boolean isConsumersExceededOnSubscription() {
        Policies policies;
        try {
            policies = topic.getBrokerService().pulsar().getConfigurationCache().policiesCache()
                    .get(AdminResource.path(POLICIES, TopicName.get(topic.getName()).getNamespace()))
                    .orElseGet(() -> new Policies());
        } catch (Exception e) {
            policies = new Policies();
        }
        final int maxConsumersPerSubscription = policies.max_consumers_per_subscription > 0 ?
                policies.max_consumers_per_subscription :
                serviceConfig.getMaxConsumersPerSubscription();
        if (maxConsumersPerSubscription > 0 && maxConsumersPerSubscription <= consumerList.size()) {
            return true;
        }
        return false;
    }

    @Override
    public synchronized void removeConsumer(Consumer consumer) throws BrokerServiceException {
        // decrement unack-message count for removed consumer
        addUnAckedMessages(-consumer.getUnackedMessages());
        if (consumerSet.removeAll(consumer) == 1) {
            consumerList.remove(consumer);
            log.info("Removed consumer {} with pending {} acks", consumer, consumer.getPendingAcks().size());
            if (consumerList.isEmpty()) {
                if (havePendingRead && cursor.cancelPendingReadRequest()) {
                    havePendingRead = false;
                }

                messagesToReplay.clear();
                if (closeFuture != null) {
                    log.info("[{}] All consumers removed. Subscription is disconnected", name);
                    closeFuture.complete(null);
                }
                totalAvailablePermits = 0;
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Consumer are left, reading more entries", name);
                }
                consumer.getPendingAcks().forEach((ledgerId, entryId, batchSize, none) -> {
                    messagesToReplay.add(ledgerId, entryId);
                });
                totalAvailablePermits -= consumer.getAvailablePermits();
                readMoreEntries();
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Trying to remove a non-connected consumer: {}", name, consumer);
            }
        }
    }

    @Override
    public synchronized void consumerFlow(Consumer consumer, int additionalNumberOfMessages) {
        if (!consumerSet.contains(consumer)) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Ignoring flow control from disconnected consumer {}", name, consumer);
            }
            return;
        }

        totalAvailablePermits += additionalNumberOfMessages;
        if (log.isDebugEnabled()) {
            log.debug("[{}-{}] Trigger new read after receiving flow control message with permits {}", name, consumer,
                    totalAvailablePermits);
        }
        readMoreEntries();
    }

    public void readMoreEntries() {
        if (totalAvailablePermits > 0 && isAtleastOneConsumerAvailable()) {
            int messagesToRead = Math.min(totalAvailablePermits, readBatchSize);

            // throttle only if: (1) cursor is not active (or flag for throttle-nonBacklogConsumer is enabled) bcz
            // active-cursor reads message from cache rather from bookkeeper (2) if topic has reached message-rate
            // threshold: then schedule the read after MESSAGE_RATE_BACKOFF_MS
            if (serviceConfig.isDispatchThrottlingOnNonBacklogConsumerEnabled() || !cursor.isActive()) {
                DispatchRateLimiter rateLimiter = topic.getDispatchRateLimiter();
                if (rateLimiter.isDispatchRateLimitingEnabled()) {
                    if (!rateLimiter.hasMessageDispatchPermit()) {
                        if (log.isDebugEnabled()) {
                            log.debug("[{}] message-read exceeded message-rate {}/{}, schedule after a {}", name,
                                    rateLimiter.getDispatchRateOnMsg(), rateLimiter.getDispatchRateOnByte(),
                                    MESSAGE_RATE_BACKOFF_MS);
                        }
                        topic.getBrokerService().executor().schedule(() -> readMoreEntries(), MESSAGE_RATE_BACKOFF_MS,
                                TimeUnit.MILLISECONDS);
                        return;
                    } else {
                        // if dispatch-rate is in msg then read only msg according to available permit
                        long availablePermitsOnMsg = rateLimiter.getAvailableDispatchRateLimitOnMsg();
                        if (availablePermitsOnMsg > 0) {
                            messagesToRead = Math.min(messagesToRead, (int) availablePermitsOnMsg);
                        }
                    }
                }
            }

            if (!messagesToReplay.isEmpty()) {
                if (havePendingReplayRead) {
                    log.debug("[{}] Skipping replay while awaiting previous read to complete", name);
                    return;
                }

                Set<PositionImpl> messagesToReplayNow = messagesToReplay.items(messagesToRead).stream()
                        .map(pair -> new PositionImpl(pair.first, pair.second)).collect(toSet());

                if (log.isDebugEnabled()) {
                    log.debug("[{}] Schedule replay of {} messages for {} consumers", name, messagesToReplayNow.size(),
                            consumerList.size());
                }

                havePendingReplayRead = true;
                Set<? extends Position> deletedMessages = cursor.asyncReplayEntries(messagesToReplayNow, this,
                        ReadType.Replay);
                // clear already acked positions from replay bucket

                deletedMessages.forEach(position -> messagesToReplay.remove(((PositionImpl) position).getLedgerId(),
                        ((PositionImpl) position).getEntryId()));
                // if all the entries are acked-entries and cleared up from messagesToReplay, try to read
                // next entries as readCompletedEntries-callback was never called
                if ((messagesToReplayNow.size() - deletedMessages.size()) == 0) {
                    havePendingReplayRead = false;
                    readMoreEntries();
                }
            } else if (BLOCKED_DISPATCHER_ON_UNACKMSG_UPDATER.get(this) == TRUE) {
                log.warn("[{}] Dispatcher read is blocked due to unackMessages {} reached to max {}", name,
                        totalUnackedMessages, maxUnackedMessages);
            } else if (!havePendingRead) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Schedule read of {} messages for {} consumers", name, messagesToRead,
                            consumerList.size());
                }
                havePendingRead = true;
                cursor.asyncReadEntriesOrWait(messagesToRead, this, ReadType.Normal);
            } else {
                log.debug("[{}] Cannot schedule next read until previous one is done", name);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Consumer buffer is full, pause reading", name);
            }
        }
    }

    @Override
    public boolean isConsumerConnected() {
        return !consumerList.isEmpty();
    }

    @Override
    public CopyOnWriteArrayList<Consumer> getConsumers() {
        return consumerList;
    }

    @Override
    public synchronized boolean canUnsubscribe(Consumer consumer) {
        return consumerList.size() == 1 && consumerSet.contains(consumer);
    }

    @Override
    public CompletableFuture<Void> close() {
        IS_CLOSED_UPDATER.set(this, TRUE);
        return disconnectAllConsumers();
    }

    @Override
    public synchronized CompletableFuture<Void> disconnectAllConsumers() {
        closeFuture = new CompletableFuture<>();
        if (consumerList.isEmpty()) {
            closeFuture.complete(null);
        } else {
            consumerList.forEach(Consumer::disconnect);
            if (havePendingRead && cursor.cancelPendingReadRequest()) {
                havePendingRead = false;
            }
        }
        return closeFuture;
    }

    @Override
    public void reset() {
        IS_CLOSED_UPDATER.set(this, FALSE);
    }

    @Override
    public SubType getType() {
        return SubType.Shared;
    }

    @Override
    public synchronized void readEntriesComplete(List<Entry> entries, Object ctx) {
        ReadType readType = (ReadType) ctx;
        int start = 0;
        int entriesToDispatch = entries.size();

        if (readType == ReadType.Normal) {
            havePendingRead = false;
        } else {
            havePendingReplayRead = false;
        }

        if (readBatchSize < MaxReadBatchSize) {
            int newReadBatchSize = Math.min(readBatchSize * 2, MaxReadBatchSize);
            if (log.isDebugEnabled()) {
                log.debug("[{}] Increasing read batch size from {} to {}", name, readBatchSize, newReadBatchSize);
            }

            readBatchSize = newReadBatchSize;
        }

        readFailureBackoff.reduceToHalf();

        if (shouldRewindBeforeReadingOrReplaying && readType == ReadType.Normal) {
            // All consumers got disconnected before the completion of the read operation
            entries.forEach(Entry::release);
            cursor.rewind();
            shouldRewindBeforeReadingOrReplaying = false;
            readMoreEntries();
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("[{}] Distributing {} messages to {} consumers", name, entries.size(), consumerList.size());
        }

        long totalMessagesSent = 0;
        long totalBytesSent = 0;
        while (entriesToDispatch > 0 && totalAvailablePermits > 0 && isAtleastOneConsumerAvailable()) {
            Consumer c = getNextConsumer();
            if (c == null) {
                // Do nothing, cursor will be rewind at reconnection
                log.info("[{}] rewind because no available consumer found from total {}", name, consumerList.size());
                entries.subList(start, entries.size()).forEach(Entry::release);
                cursor.rewind();
                return;
            }

            // round-robin dispatch batch size for this consumer
            int messagesForC = Math.min(Math.min(entriesToDispatch, c.getAvailablePermits()), MaxRoundRobinBatchSize);

            if (messagesForC > 0) {

                // remove positions first from replay list first : sendMessages recycles entries
                if (readType == ReadType.Replay) {
                    entries.subList(start, start + messagesForC).forEach(entry -> {
                        messagesToReplay.remove(entry.getLedgerId(), entry.getEntryId());
                    });
                }

                SendMessageInfo sentMsgInfo = c.sendMessages(entries.subList(start, start + messagesForC));

                long msgSent = sentMsgInfo.getTotalSentMessages();
                start += messagesForC;
                entriesToDispatch -= messagesForC;
                totalAvailablePermits -= msgSent;
                totalMessagesSent += sentMsgInfo.getTotalSentMessages();
                totalBytesSent += sentMsgInfo.getTotalSentMessageBytes();
            }
        }

        // acquire message-dispatch permits for already delivered messages
        if (serviceConfig.isDispatchThrottlingOnNonBacklogConsumerEnabled() || !cursor.isActive()) {
            topic.getDispatchRateLimiter().tryDispatchPermit(totalMessagesSent, totalBytesSent);
        }

        if (entriesToDispatch > 0) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] No consumers found with available permits, storing {} positions for later replay", name,
                        entries.size() - start);
            }
            entries.subList(start, entries.size()).forEach(entry -> {
                messagesToReplay.add(entry.getLedgerId(), entry.getEntryId());
                entry.release();
            });
        }

        readMoreEntries();
    }

    @Override
    public synchronized void readEntriesFailed(ManagedLedgerException exception, Object ctx) {

        ReadType readType = (ReadType) ctx;
        long waitTimeMillis = readFailureBackoff.next();

        if (exception instanceof NoMoreEntriesToReadException) {
            if (cursor.getNumberOfEntriesInBacklog() == 0) {
                // Topic has been terminated and there are no more entries to read
                // Notify the consumer only if all the messages were already acknowledged
                consumerList.forEach(Consumer::reachedEndOfTopic);
            }
        } else if (!(exception instanceof TooManyRequestsException)) {
            log.error("[{}] Error reading entries at {} : {}, Read Type {} - Retrying to read in {} seconds", name,
                    cursor.getReadPosition(), exception.getMessage(), readType, waitTimeMillis / 1000.0);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Error reading entries at {} : {}, Read Type {} - Retrying to read in {} seconds", name,
                        cursor.getReadPosition(), exception.getMessage(), readType, waitTimeMillis / 1000.0);
            }
        }

        if (shouldRewindBeforeReadingOrReplaying) {
            shouldRewindBeforeReadingOrReplaying = false;
            cursor.rewind();
        }

        if (readType == ReadType.Normal) {
            havePendingRead = false;
        } else {
            havePendingReplayRead = false;
            if (exception instanceof ManagedLedgerException.InvalidReplayPositionException) {
                PositionImpl markDeletePosition = (PositionImpl) cursor.getMarkDeletedPosition();
                messagesToReplay.removeIf((ledgerId, entryId) -> {
                    return ComparisonChain.start().compare(ledgerId, markDeletePosition.getLedgerId())
                            .compare(entryId, markDeletePosition.getEntryId()).result() <= 0;
                });
            }
        }

        readBatchSize = 1;

        topic.getBrokerService().executor().schedule(() -> {
            synchronized (PersistentDispatcherMultipleConsumers.this) {
                if (!havePendingRead) {
                    log.info("[{}] Retrying read operation", name);
                    readMoreEntries();
                } else {
                    log.info("[{}] Skipping read retry: havePendingRead {}", name, havePendingRead, exception);
                }
            }
        }, waitTimeMillis, TimeUnit.MILLISECONDS);

    }


    /**
     * returns true only if {@link consumerList} has atleast one unblocked consumer and have available permits
     *
     * @return
     */
    private boolean isAtleastOneConsumerAvailable() {
        if (consumerList.isEmpty() || IS_CLOSED_UPDATER.get(this) == TRUE) {
            // abort read if no consumers are connected or if disconnect is initiated
            return false;
        }
        for(Consumer consumer : consumerList) {
            if (isConsumerAvailable(consumer)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isConsumerAvailable(Consumer consumer) {
        return consumer != null && !consumer.isBlocked() && consumer.getAvailablePermits() > 0;
    }

    @Override
    public synchronized void redeliverUnacknowledgedMessages(Consumer consumer) {
        consumer.getPendingAcks().forEach((ledgerId, entryId, batchSize, none) -> {
            messagesToReplay.add(ledgerId, entryId);
        });
        if (log.isDebugEnabled()) {
            log.debug("[{}-{}] Redelivering unacknowledged messages for consumer {}", name, consumer, messagesToReplay);
        }
        readMoreEntries();
    }

    @Override
    public synchronized void redeliverUnacknowledgedMessages(Consumer consumer, List<PositionImpl> positions) {
        positions.forEach(position -> messagesToReplay.add(position.getLedgerId(), position.getEntryId()));
        if (log.isDebugEnabled()) {
            log.debug("[{}-{}] Redelivering unacknowledged messages for consumer {}", name, consumer, positions);
        }
        readMoreEntries();
    }

    @Override
    public void addUnAckedMessages(int numberOfMessages) {
        // don't block dispatching if maxUnackedMessages = 0
        if (maxUnackedMessages <= 0) {
            return;
        }
        int unAckedMessages = TOTAL_UNACKED_MESSAGES_UPDATER.addAndGet(this, numberOfMessages);
        if (unAckedMessages >= maxUnackedMessages
                && BLOCKED_DISPATCHER_ON_UNACKMSG_UPDATER.compareAndSet(this, FALSE, TRUE)) {
            // block dispatcher if it reaches maxUnAckMsg limit
            log.info("[{}] Dispatcher is blocked due to unackMessages {} reached to max {}", name,
                    TOTAL_UNACKED_MESSAGES_UPDATER.get(this), maxUnackedMessages);
        } else if (topic.getBrokerService().isBrokerDispatchingBlocked()
                && blockedDispatcherOnUnackedMsgs == TRUE) {
            // unblock dispatcher: if dispatcher is blocked due to broker-unackMsg limit and if it ack back enough
            // messages
            if (totalUnackedMessages < (topic.getBrokerService().maxUnackedMsgsPerDispatcher / 2)) {
                if (BLOCKED_DISPATCHER_ON_UNACKMSG_UPDATER.compareAndSet(this, TRUE, FALSE)) {
                    // it removes dispatcher from blocked list and unblocks dispatcher by scheduling read
                    topic.getBrokerService().unblockDispatchersOnUnAckMessages(Lists.newArrayList(this));
                }
            }
        } else if (blockedDispatcherOnUnackedMsgs == TRUE && unAckedMessages < maxUnackedMessages / 2) {
            // unblock dispatcher if it acks back enough messages
            if (BLOCKED_DISPATCHER_ON_UNACKMSG_UPDATER.compareAndSet(this, TRUE, FALSE)) {
                log.info("[{}] Dispatcher is unblocked", name);
                topic.getBrokerService().executor().submit(() -> readMoreEntries());
            }
        }
        // increment broker-level count
        topic.getBrokerService().addUnAckedMessages(this, numberOfMessages);
    }

    public boolean isBlockedDispatcherOnUnackedMsgs() {
        return blockedDispatcherOnUnackedMsgs == TRUE;
    }

    public void blockDispatcherOnUnackedMsgs() {
        blockedDispatcherOnUnackedMsgs = TRUE;
    }

    public void unBlockDispatcherOnUnackedMsgs() {
        blockedDispatcherOnUnackedMsgs = FALSE;
    }

    public int getTotalUnackedMessages() {
        return totalUnackedMessages;
    }

    public String getName() {
        return name;
    }

    private static final Logger log = LoggerFactory.getLogger(PersistentDispatcherMultipleConsumers.class);
}
