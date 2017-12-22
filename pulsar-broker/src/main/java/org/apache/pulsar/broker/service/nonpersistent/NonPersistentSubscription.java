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
package org.apache.pulsar.broker.service.nonpersistent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.pulsar.broker.service.BrokerServiceException;
import org.apache.pulsar.broker.service.BrokerServiceException.ServerMetadataException;
import org.apache.pulsar.broker.service.BrokerServiceException.SubscriptionBusyException;
import org.apache.pulsar.broker.service.BrokerServiceException.SubscriptionFencedException;
import org.apache.pulsar.broker.service.Consumer;
import org.apache.pulsar.broker.service.Dispatcher;
import org.apache.pulsar.broker.service.Subscription;
import org.apache.pulsar.broker.service.Topic;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandAck.AckType;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSubscribe.SubType;
import org.apache.pulsar.common.naming.DestinationName;
import org.apache.pulsar.common.policies.data.ConsumerStats;
import org.apache.pulsar.common.policies.data.NonPersistentSubscriptionStats;
import org.apache.pulsar.common.policies.data.SubscriptionStats;
import org.apache.pulsar.utils.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;

public class NonPersistentSubscription implements Subscription {
    private final NonPersistentTopic topic;
    private volatile NonPersistentDispatcher dispatcher;
    private final String topicName;
    private final String subName;

    private static final int FALSE = 0;
    private static final int TRUE = 1;
    private static final AtomicIntegerFieldUpdater<NonPersistentSubscription> IS_FENCED_UPDATER = AtomicIntegerFieldUpdater
            .newUpdater(NonPersistentSubscription.class, "isFenced");
    private volatile int isFenced = FALSE;

    public NonPersistentSubscription(NonPersistentTopic topic, String subscriptionName) {
        this.topic = topic;
        this.topicName = topic.getName();
        this.subName = subscriptionName;
        IS_FENCED_UPDATER.set(this, FALSE);
    }

    @Override
    public String getName() {
        return this.subName;
    }

    @Override
    public Topic getTopic() {
        return topic;
    }

    @Override
    public synchronized void addConsumer(Consumer consumer) throws BrokerServiceException {
        if (IS_FENCED_UPDATER.get(this) == TRUE) {
            log.warn("Attempting to add consumer {} on a fenced subscription", consumer);
            throw new SubscriptionFencedException("Subscription is fenced");
        }

        if (dispatcher == null || !dispatcher.isConsumerConnected()) {
            switch (consumer.subType()) {
            case Exclusive:
                if (dispatcher == null || dispatcher.getType() != SubType.Exclusive) {
                    dispatcher = new NonPersistentDispatcherSingleActiveConsumer(SubType.Exclusive, 0, topic, this.subName);
                }
                break;
            case Shared:
                if (dispatcher == null || dispatcher.getType() != SubType.Shared) {
                    dispatcher = new NonPersistentDispatcherMultipleConsumers(topic, this.subName);
                }
                break;
            case Failover:
                int partitionIndex = DestinationName.getPartitionIndex(topicName);
                if (partitionIndex < 0) {
                    // For non partition topics, assume index 0 to pick a predictable consumer
                    partitionIndex = 0;
                }

                if (dispatcher == null || dispatcher.getType() != SubType.Failover) {
                    dispatcher = new NonPersistentDispatcherSingleActiveConsumer(SubType.Failover, partitionIndex,
                            topic, this.subName);
                }
                break;
            default:
                throw new ServerMetadataException("Unsupported subscription type");
            }
        } else {
            if (consumer.subType() != dispatcher.getType()) {
                throw new SubscriptionBusyException("Subscription is of different type");
            }
        }

        dispatcher.addConsumer(consumer);
    }

    @Override
    public synchronized void removeConsumer(Consumer consumer) throws BrokerServiceException {
        if (dispatcher != null) {
            dispatcher.removeConsumer(consumer);
        }

        // invalid consumer remove will throw an exception
        // decrement usage is triggered only for valid consumer close
        NonPersistentTopic.USAGE_COUNT_UPDATER.decrementAndGet(topic);
        if (log.isDebugEnabled()) {
            log.debug("[{}] [{}] [{}] Removed consumer -- count: {}", topic.getName(), subName, consumer.consumerName(),
                    NonPersistentTopic.USAGE_COUNT_UPDATER.get(topic));
        }
    }

    @Override
    public void consumerFlow(Consumer consumer, int additionalNumberOfMessages) {
        dispatcher.consumerFlow(consumer, additionalNumberOfMessages);
    }

    @Override
    public void acknowledgeMessage(PositionImpl position, AckType ackType) {
        // No-op
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("topic", topicName).add("name", subName).toString();
    }

    @Override
    public String getDestination() {
        return this.topicName;
    }

    @Override
    public SubType getType() {
        return dispatcher != null ? dispatcher.getType() : null;
    }

    @Override
    public String getTypeString() {
        SubType type = getType();
        if (type == null) {
            return "None";
        }

        switch (type) {
        case Exclusive:
            return "Exclusive";
        case Failover:
            return "Failover";
        case Shared:
            return "Shared";
        }

        return "Null";
    }

    @Override
    public CompletableFuture<Void> clearBacklog() {
        // No-op
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> skipMessages(int numMessagesToSkip) {
        // No-op
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> resetCursor(long timestamp) {
        // No-op
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Entry> peekNthMessage(int messagePosition) {
        // No-op
        return CompletableFuture.completedFuture(null);// TODO: throw exception
    }

    @Override
    public long getNumberOfEntriesInBacklog() {
        // No-op
        return 0;
    }

    @Override
    public NonPersistentDispatcher getDispatcher() {
        return this.dispatcher;
    }

    @Override
    public CompletableFuture<Void> close() {
        IS_FENCED_UPDATER.set(this, TRUE);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Disconnect all consumers attached to the dispatcher and close this subscription
     *
     * @return CompletableFuture indicating the completion of disconnect operation
     */
    @Override
    public synchronized CompletableFuture<Void> disconnect() {
        CompletableFuture<Void> disconnectFuture = new CompletableFuture<>();

        // block any further consumers on this subscription
        IS_FENCED_UPDATER.set(this, TRUE);

        (dispatcher != null ? dispatcher.close() : CompletableFuture.completedFuture(null)).thenCompose(v -> close())
                .thenRun(() -> {
                    log.info("[{}][{}] Successfully disconnected and closed subscription", topicName, subName);
                    disconnectFuture.complete(null);
                }).exceptionally(exception -> {
                    IS_FENCED_UPDATER.set(this, FALSE);
                    dispatcher.reset();
                    log.error("[{}][{}] Error disconnecting consumers from subscription", topicName, subName,
                            exception);
                    disconnectFuture.completeExceptionally(exception);
                    return null;
                });

        return disconnectFuture;
    }

    /**
     * Delete the subscription by closing and deleting its managed cursor if no consumers are connected to it. Handle
     * unsubscribe call from admin layer.
     *
     * @return CompletableFuture indicating the completion of delete operation
     */
    @Override
    public CompletableFuture<Void> delete() {
        CompletableFuture<Void> deleteFuture = new CompletableFuture<>();

        log.info("[{}][{}] Unsubscribing", topicName, subName);

        // cursor close handles pending delete (ack) operations
        this.close().thenCompose(v -> topic.unsubscribe(subName)).thenAccept(v -> deleteFuture.complete(null))
                .exceptionally(exception -> {
                    IS_FENCED_UPDATER.set(this, FALSE);
                    log.error("[{}][{}] Error deleting subscription", topicName, subName, exception);
                    deleteFuture.completeExceptionally(exception);
                    return null;
                });

        return deleteFuture;
    }

    /**
     * Handle unsubscribe command from the client API Check with the dispatcher is this consumer can proceed with
     * unsubscribe
     *
     * @param consumer
     *            consumer object that is initiating the unsubscribe operation
     * @return CompletableFuture indicating the completion of ubsubscribe operation
     */
    @Override
    public CompletableFuture<Void> doUnsubscribe(Consumer consumer) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            if (dispatcher.canUnsubscribe(consumer)) {
                consumer.close();
                return delete();
            }
            future.completeExceptionally(
                    new ServerMetadataException("Unconnected or shared consumer attempting to unsubscribe"));
        } catch (BrokerServiceException e) {
            log.warn("Error removing consumer {}", consumer);
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public CopyOnWriteArrayList<Consumer> getConsumers() {
        Dispatcher dispatcher = this.dispatcher;
        if (dispatcher != null) {
            return dispatcher.getConsumers();
        } else {
            return CopyOnWriteArrayList.empty();
        }
    }

    @Override
    public void expireMessages(int messageTTLInSeconds) {
        // No-op
    }

    public NonPersistentSubscriptionStats getStats() {
        NonPersistentSubscriptionStats subStats = new NonPersistentSubscriptionStats();

        NonPersistentDispatcher dispatcher = this.dispatcher;
        if (dispatcher != null) {
            dispatcher.getConsumers().forEach(consumer -> {
                ConsumerStats consumerStats = consumer.getStats();
                subStats.consumers.add(consumerStats);
                subStats.msgRateOut += consumerStats.msgRateOut;
                subStats.msgThroughputOut += consumerStats.msgThroughputOut;
                subStats.msgRateRedeliver += consumerStats.msgRateRedeliver;
            });
        }

        subStats.type = getType();
        subStats.msgDropRate = dispatcher.getMesssageDropRate().getRate();
        return subStats;
    }

    @Override
    public synchronized void redeliverUnacknowledgedMessages(Consumer consumer) {
     // No-op
    }

    @Override
    public synchronized void redeliverUnacknowledgedMessages(Consumer consumer, List<PositionImpl> positions) {
        // No-op
    }

    @Override
    public void addUnAckedMessages(int unAckMessages) {
        // No-op
    }

    @Override
    public double getExpiredMessageRate() {
        // No-op
        return 0;
    }

    @Override
    public void markTopicWithBatchMessagePublished() {
        topic.markBatchMessagePublished();
    }

    @Override
    public CompletableFuture<Void> resetCursor(Position position) {
        return CompletableFuture.completedFuture(null);
    }

    private static final Logger log = LoggerFactory.getLogger(NonPersistentSubscription.class);

}
