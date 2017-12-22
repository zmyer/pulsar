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
package org.apache.pulsar.broker.service;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.apache.bookkeeper.mledger.AsyncCallbacks.ReadEntriesCallback;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedCursor;
import org.apache.bookkeeper.mledger.ManagedLedgerException;
import org.apache.bookkeeper.mledger.ManagedLedgerException.NoMoreEntriesToReadException;
import org.apache.bookkeeper.mledger.ManagedLedgerException.TooManyRequestsException;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.pulsar.broker.service.BrokerServiceException;
import org.apache.pulsar.broker.service.Consumer;
import org.apache.pulsar.broker.service.Dispatcher;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.broker.service.BrokerServiceException.ConsumerBusyException;
import org.apache.pulsar.broker.service.BrokerServiceException.ServerMetadataException;
import org.apache.pulsar.client.impl.Backoff;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSubscribe.SubType;
import org.apache.pulsar.utils.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractDispatcherSingleActiveConsumer {

    protected final String topicName;
    protected static final AtomicReferenceFieldUpdater<AbstractDispatcherSingleActiveConsumer, Consumer> ACTIVE_CONSUMER_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(AbstractDispatcherSingleActiveConsumer.class, Consumer.class, "activeConsumer");
    private volatile Consumer activeConsumer = null;
    protected final CopyOnWriteArrayList<Consumer> consumers;
    protected CompletableFuture<Void> closeFuture = null;
    protected final int partitionIndex;

    // This dispatcher supports both the Exclusive and Failover subscription types
    protected final SubType subscriptionType;

    protected static final int FALSE = 0;
    protected static final int TRUE = 1;
    protected static final AtomicIntegerFieldUpdater<AbstractDispatcherSingleActiveConsumer> IS_CLOSED_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractDispatcherSingleActiveConsumer.class, "isClosed");
    private volatile int isClosed = FALSE;

    public AbstractDispatcherSingleActiveConsumer(SubType subscriptionType, int partitionIndex,
            String topicName) {
        this.topicName = topicName;
        this.consumers = new CopyOnWriteArrayList<>();
        this.partitionIndex = partitionIndex;
        this.subscriptionType = subscriptionType;
        ACTIVE_CONSUMER_UPDATER.set(this, null);
    }

    protected abstract void scheduleReadOnActiveConsumer();

    protected abstract void readMoreEntries(Consumer consumer);

    protected abstract void cancelPendingRead();

    protected void pickAndScheduleActiveConsumer() {
        checkArgument(!consumers.isEmpty());

        consumers.sort((c1, c2) -> c1.consumerName().compareTo(c2.consumerName()));

        int index = partitionIndex % consumers.size();
        Consumer prevConsumer = ACTIVE_CONSUMER_UPDATER.getAndSet(this, consumers.get(index));

        if (prevConsumer == ACTIVE_CONSUMER_UPDATER.get(this)) {
            // Active consumer did not change. Do nothing at this point
            return;
        }

        scheduleReadOnActiveConsumer();
    }

    public synchronized void addConsumer(Consumer consumer) throws BrokerServiceException {
        if (IS_CLOSED_UPDATER.get(this) == TRUE) {
            log.warn("[{}] Dispatcher is already closed. Closing consumer ", this.topicName, consumer);
            consumer.disconnect();
        }
        if (subscriptionType == SubType.Exclusive && !consumers.isEmpty()) {
            throw new ConsumerBusyException("Exclusive consumer is already connected");
        }

        consumers.add(consumer);

        // Pick an active consumer and start it
        pickAndScheduleActiveConsumer();

    }

    public synchronized void removeConsumer(Consumer consumer) throws BrokerServiceException {
        log.info("Removing consumer {}", consumer);
        if (!consumers.remove(consumer)) {
            throw new ServerMetadataException("Consumer was not connected");
        }

        if (consumers.isEmpty()) {
            ACTIVE_CONSUMER_UPDATER.set(this, null);
        }

        if (closeFuture == null && !consumers.isEmpty()) {
            pickAndScheduleActiveConsumer();
            return;
        }

        cancelPendingRead();

        if (consumers.isEmpty() && closeFuture != null && !closeFuture.isDone()) {
            // Control reaches here only when closeFuture is created
            // and no more connected consumers left.
            closeFuture.complete(null);
        }
    }

    /**
     * Handle unsubscribe command from the client API For failover subscription, if consumer is connected consumer, we
     * can unsubscribe.
     *
     * @param consumer
     *            Calling consumer object
     */
    public synchronized boolean canUnsubscribe(Consumer consumer) {
        return (consumers.size() == 1) && Objects.equals(consumer, ACTIVE_CONSUMER_UPDATER.get(this));
    }

    public CompletableFuture<Void> close() {
        IS_CLOSED_UPDATER.set(this, TRUE);
        return disconnectAllConsumers();
    }

    /**
     * Disconnect all consumers on this dispatcher (server side close). This triggers channelInactive on the inbound
     * handler which calls dispatcher.removeConsumer(), where the closeFuture is completed
     *
     * @return
     */
    public synchronized CompletableFuture<Void> disconnectAllConsumers() {
        closeFuture = new CompletableFuture<>();

        if (!consumers.isEmpty()) {
            consumers.forEach(Consumer::disconnect);
            cancelPendingRead();
        } else {
            // no consumer connected, complete disconnect immediately
            closeFuture.complete(null);
        }
        return closeFuture;
    }

    public void reset() {
        IS_CLOSED_UPDATER.set(this, FALSE);
    }

    public SubType getType() {
        return subscriptionType;
    }
    
    public Consumer getActiveConsumer() {
        return ACTIVE_CONSUMER_UPDATER.get(this);
    }

    public CopyOnWriteArrayList<Consumer> getConsumers() {
        return consumers;
    }
    
    public boolean isConsumerConnected() {
        return ACTIVE_CONSUMER_UPDATER.get(this) != null;
    }

    private static final Logger log = LoggerFactory.getLogger(AbstractDispatcherSingleActiveConsumer.class);
    
}
