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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandAck.AckType;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSubscribe.SubType;

public interface Subscription {

    Topic getTopic();

    String getName();

    void addConsumer(Consumer consumer) throws BrokerServiceException;

    void removeConsumer(Consumer consumer) throws BrokerServiceException;

    void consumerFlow(Consumer consumer, int additionalNumberOfMessages);

    void acknowledgeMessage(PositionImpl position, AckType ackType);

    String getDestination();

    Dispatcher getDispatcher();

    long getNumberOfEntriesInBacklog();

    List<Consumer> getConsumers();

    CompletableFuture<Void> close();

    CompletableFuture<Void> delete();

    CompletableFuture<Void> disconnect();

    CompletableFuture<Void> doUnsubscribe(Consumer consumer);

    CompletableFuture<Void> clearBacklog();

    CompletableFuture<Void> skipMessages(int numMessagesToSkip);

    CompletableFuture<Void> resetCursor(long timestamp);

    CompletableFuture<Void> resetCursor(Position position);

    CompletableFuture<Entry> peekNthMessage(int messagePosition);

    void expireMessages(int messageTTLInSeconds);

    void redeliverUnacknowledgedMessages(Consumer consumer);

    void redeliverUnacknowledgedMessages(Consumer consumer, List<PositionImpl> positions);

    void markTopicWithBatchMessagePublished();

    double getExpiredMessageRate();

    SubType getType();

    String getTypeString();

    void addUnAckedMessages(int unAckMessages);
}
