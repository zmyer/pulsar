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

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.pulsar.broker.cache.ConfigurationCacheService.POLICIES;
import static org.apache.pulsar.broker.service.persistent.PersistentTopic.MESSAGE_RATE_BACKOFF_MS;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.bookkeeper.mledger.AsyncCallbacks.ReadEntriesCallback;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedCursor;
import org.apache.bookkeeper.mledger.ManagedLedgerException;
import org.apache.bookkeeper.mledger.ManagedLedgerException.NoMoreEntriesToReadException;
import org.apache.bookkeeper.mledger.ManagedLedgerException.TooManyRequestsException;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.admin.AdminResource;
import org.apache.pulsar.broker.service.AbstractDispatcherSingleActiveConsumer;
import org.apache.pulsar.broker.service.Consumer;
import org.apache.pulsar.broker.service.Consumer.SendMessageInfo;
import org.apache.pulsar.broker.service.Dispatcher;
import org.apache.pulsar.client.impl.Backoff;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSubscribe.SubType;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.Policies;
import org.apache.pulsar.common.util.Codec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PersistentDispatcherSingleActiveConsumer extends AbstractDispatcherSingleActiveConsumer implements Dispatcher, ReadEntriesCallback {

    private final PersistentTopic topic;
    private final ManagedCursor cursor;
    private final String name;

    private boolean havePendingRead = false;

    private static final int MaxReadBatchSize = 100;
    private int readBatchSize;
    private final Backoff readFailureBackoff = new Backoff(15, TimeUnit.SECONDS, 1, TimeUnit.MINUTES, 0, TimeUnit.MILLISECONDS);
    private final ServiceConfiguration serviceConfig;
    private ScheduledFuture<?> readOnActiveConsumerTask = null;

    public PersistentDispatcherSingleActiveConsumer(ManagedCursor cursor, SubType subscriptionType, int partitionIndex,
            PersistentTopic topic) {
        super(subscriptionType, partitionIndex, topic.getName());
        this.topic = topic;
        this.name = topic.getName() + " / " + (cursor.getName() != null ? Codec.decode(cursor.getName())
                : ""/* NonDurableCursor doesn't have name */);
        this.cursor = cursor;
        this.readBatchSize = MaxReadBatchSize;
        this.serviceConfig = topic.getBrokerService().pulsar().getConfiguration();
    }

    protected void scheduleReadOnActiveConsumer() {
        if (havePendingRead && cursor.cancelPendingReadRequest()) {
            havePendingRead = false;
        }

        if (havePendingRead) {
            return;
        }

        // When a new consumer is chosen, start delivery from unacked message.
        // If there is any pending read operation, let it finish and then rewind

        if (subscriptionType != SubType.Failover || serviceConfig.getActiveConsumerFailoverDelayTimeMillis() <= 0) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Rewind cursor and read more entries without delay", name);
            }
            cursor.rewind();

            Consumer activeConsumer = ACTIVE_CONSUMER_UPDATER.get(this);
            notifyActiveConsumerChanged(activeConsumer);
            readMoreEntries(activeConsumer);
            return;
        }

        // If subscription type is Failover, delay rewinding cursor and
        // reading more entries in order to prevent message duplication

        if (readOnActiveConsumerTask != null) {
            return;
        }

        readOnActiveConsumerTask = topic.getBrokerService().executor().schedule(() -> {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Rewind cursor and read more entries after {} ms delay", name,
                        serviceConfig.getActiveConsumerFailoverDelayTimeMillis());
            }
            cursor.rewind();

            Consumer activeConsumer = ACTIVE_CONSUMER_UPDATER.get(this);
            notifyActiveConsumerChanged(activeConsumer);
            readMoreEntries(activeConsumer);
            readOnActiveConsumerTask = null;
        }, serviceConfig.getActiveConsumerFailoverDelayTimeMillis(), TimeUnit.MILLISECONDS);
    }

    protected boolean isConsumersExceededOnTopic() {
        Policies policies;
        try {
            policies = topic.getBrokerService().pulsar().getConfigurationCache().policiesCache()
                    .get(AdminResource.path(POLICIES, TopicName.get(topicName).getNamespace()))
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

    protected boolean isConsumersExceededOnSubscription() {
        Policies policies;
        try {
            policies = topic.getBrokerService().pulsar().getConfigurationCache().policiesCache()
                    .get(AdminResource.path(POLICIES, TopicName.get(topicName).getNamespace()))
                    .orElseGet(() -> new Policies());
        } catch (Exception e) {
            policies = new Policies();
        }
        final int maxConsumersPerSubscription = policies.max_consumers_per_subscription > 0 ?
                policies.max_consumers_per_subscription :
                serviceConfig.getMaxConsumersPerSubscription();
        if (maxConsumersPerSubscription > 0 && maxConsumersPerSubscription <= consumers.size()) {
            return true;
        }
        return false;
    }

    protected void cancelPendingRead() {
        if (havePendingRead && cursor.cancelPendingReadRequest()) {
            havePendingRead = false;
        }
    }

    @Override
    public synchronized void readEntriesComplete(final List<Entry> entries, Object obj) {
        Consumer readConsumer = (Consumer) obj;
        if (log.isDebugEnabled()) {
            log.debug("[{}-{}] Got messages: {}", name, readConsumer, entries.size());
        }

        havePendingRead = false;

        if (readBatchSize < MaxReadBatchSize) {
            int newReadBatchSize = Math.min(readBatchSize * 2, MaxReadBatchSize);
            if (log.isDebugEnabled()) {
                log.debug("[{}-{}] Increasing read batch size from {} to {}", name, readConsumer, readBatchSize,
                        newReadBatchSize);
            }

            readBatchSize = newReadBatchSize;
        }

        readFailureBackoff.reduceToHalf();

        Consumer currentConsumer = ACTIVE_CONSUMER_UPDATER.get(this);
        if (currentConsumer == null || readConsumer != currentConsumer) {
            // Active consumer has changed since the read request has been issued. We need to rewind the cursor and
            // re-issue the read request for the new consumer
            if (log.isDebugEnabled()) {
                log.debug("[{}] rewind because no available consumer found", name);
            }
            entries.forEach(Entry::release);
            cursor.rewind();
            if (currentConsumer != null) {
                readMoreEntries(currentConsumer);
            }
        } else {
            SendMessageInfo sentMsgInfo = currentConsumer.sendMessages(entries);
            final long totalMessagesSent = sentMsgInfo.getTotalSentMessages();
            final long totalBytesSent = sentMsgInfo.getTotalSentMessageBytes();
            sentMsgInfo.getChannelPromse().addListener(future -> {
                if (future.isSuccess()) {
                    // acquire message-dispatch permits for already delivered messages
                    if (serviceConfig.isDispatchThrottlingOnNonBacklogConsumerEnabled() || !cursor.isActive()) {
                        topic.getDispatchRateLimiter().tryDispatchPermit(totalMessagesSent, totalBytesSent);
                    }
                    // Schedule a new read batch operation only after the previous batch has been written to the socket
                    synchronized (PersistentDispatcherSingleActiveConsumer.this) {
                        Consumer newConsumer = ACTIVE_CONSUMER_UPDATER.get(this);
                        if (newConsumer != null && !havePendingRead) {
                            readMoreEntries(newConsumer);
                        } else {
                            if (log.isDebugEnabled()) {
                                log.debug(
                                        "[{}-{}] Ignoring write future complete. consumerAvailable={} havePendingRead={}",
                                        name, newConsumer, newConsumer != null, havePendingRead);
                            }
                        }
                    }
                }
            });
        }
    }

    @Override
    public synchronized void consumerFlow(Consumer consumer, int additionalNumberOfMessages) {
        if (havePendingRead) {
            if (log.isDebugEnabled()) {
                log.debug("[{}-{}] Ignoring flow control message since we already have a pending read req", name,
                        consumer);
            }
        } else if (ACTIVE_CONSUMER_UPDATER.get(this) != consumer) {
            if (log.isDebugEnabled()) {
                log.debug("[{}-{}] Ignoring flow control message since consumer is not active partition consumer", name,
                        consumer);
            }
        } else if (readOnActiveConsumerTask != null) {
            if (log.isDebugEnabled()) {
                log.debug("[{}-{}] Ignoring flow control message since consumer is waiting for cursor to be rewinded",
                        name, consumer);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}-{}] Trigger new read after receiving flow control message", name, consumer);
            }
            readMoreEntries(consumer);
        }
    }

    @Override
    public synchronized void redeliverUnacknowledgedMessages(Consumer consumer) {
        if (consumer != ACTIVE_CONSUMER_UPDATER.get(this)) {
            log.info("[{}-{}] Ignoring reDeliverUnAcknowledgedMessages: Only the active consumer can call resend",
                    name, consumer);
            return;
        }

        if (readOnActiveConsumerTask != null) {
            log.info("[{}-{}] Ignoring reDeliverUnAcknowledgedMessages: consumer is waiting for cursor to be rewinded",
                    name, consumer);
            return;
        }

        if (havePendingRead && cursor.cancelPendingReadRequest()) {
            havePendingRead = false;
        }

        if (!havePendingRead) {
            cursor.rewind();
            if (log.isDebugEnabled()) {
                log.debug("[{}-{}] Cursor rewinded, redelivering unacknowledged messages. ", name, consumer);
            }
            readMoreEntries(consumer);
        } else {
            log.info("[{}-{}] Ignoring reDeliverUnAcknowledgedMessages: cancelPendingRequest on cursor failed", name,
                    consumer);
        }

    }

    @Override
    public void redeliverUnacknowledgedMessages(Consumer consumer, List<PositionImpl> positions) {
        // We cannot redeliver single messages to single consumers to preserve ordering.
        redeliverUnacknowledgedMessages(consumer);
    }

    @Override
    protected void readMoreEntries(Consumer consumer) {
        // consumer can be null when all consumers are disconnected from broker.
        // so skip reading more entries if currently there is no active consumer.
        if (null == consumer) {
            return;
        }

        int availablePermits = consumer.getAvailablePermits();

        if (availablePermits > 0) {
            if (!consumer.isWritable()) {
                // If the connection is not currently writable, we issue the read request anyway, but for a single
                // message. The intent here is to keep use the request as a notification mechanism while avoiding to
                // read and dispatch a big batch of messages which will need to wait before getting written to the
                // socket.
                availablePermits = 1;
            }

            int messagesToRead = Math.min(availablePermits, readBatchSize);

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
                        topic.getBrokerService().executor().schedule(() -> {
                            Consumer currentConsumer = ACTIVE_CONSUMER_UPDATER.get(this);
                            if (currentConsumer != null && !havePendingRead) {
                                readMoreEntries(currentConsumer);
                            } else {
                                if (log.isDebugEnabled()) {
                                    log.info("[{}] Skipping read retry: Current Consumer {}, havePendingRead {}",
                                            topic.getName(), currentConsumer, havePendingRead);
                                }
                            }
                        }, MESSAGE_RATE_BACKOFF_MS, TimeUnit.MILLISECONDS);
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

            // Schedule read
            if (log.isDebugEnabled()) {
                log.debug("[{}-{}] Schedule read of {} messages", name, consumer, messagesToRead);
            }
            havePendingRead = true;
            if (consumer.readCompacted()) {
                topic.compactedTopic.asyncReadEntriesOrWait(cursor, messagesToRead, this, consumer);
            } else {
                cursor.asyncReadEntriesOrWait(messagesToRead, this, consumer);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}-{}] Consumer buffer is full, pause reading", name, consumer);
            }
        }
    }

    @Override
    public synchronized void readEntriesFailed(ManagedLedgerException exception, Object ctx) {

        havePendingRead = false;
        Consumer c = (Consumer) ctx;

        long waitTimeMillis = readFailureBackoff.next();

        if (exception instanceof NoMoreEntriesToReadException) {
            if (cursor.getNumberOfEntriesInBacklog() == 0) {
                // Topic has been terminated and there are no more entries to read
                // Notify the consumer only if all the messages were already acknowledged
                consumers.forEach(Consumer::reachedEndOfTopic);
            }
        } else if (!(exception instanceof TooManyRequestsException)) {
            log.error("[{}-{}] Error reading entries at {} : {} - Retrying to read in {} seconds", name, c,
                    cursor.getReadPosition(), exception.getMessage(), waitTimeMillis / 1000.0);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}-{}] Got throttled by bookies while reading at {} : {} - Retrying to read in {} seconds",
                        name, c, cursor.getReadPosition(), exception.getMessage(), waitTimeMillis / 1000.0);
            }
        }

        checkNotNull(c);

        // Reduce read batch size to avoid flooding bookies with retries
        readBatchSize = 1;

        topic.getBrokerService().executor().schedule(() -> {
            synchronized (PersistentDispatcherSingleActiveConsumer.this) {
                Consumer currentConsumer = ACTIVE_CONSUMER_UPDATER.get(this);
                // we should retry the read if we have an active consumer and there is no pending read
                if (currentConsumer != null && !havePendingRead) {
                    if (log.isDebugEnabled()) {
                        log.debug("[{}-{}] Retrying read operation", name, c);
                    }
                    readMoreEntries(currentConsumer);
                } else {
                    log.info("[{}-{}] Skipping read retry: Current Consumer {}, havePendingRead {}", name, c,
                            currentConsumer, havePendingRead);
                }
            }
        }, waitTimeMillis, TimeUnit.MILLISECONDS);

    }

    @Override
    public void addUnAckedMessages(int unAckMessages) {
        // No-op
    }

    private static final Logger log = LoggerFactory.getLogger(PersistentDispatcherSingleActiveConsumer.class);
}
