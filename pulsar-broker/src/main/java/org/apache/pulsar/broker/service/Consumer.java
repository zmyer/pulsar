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
import static org.apache.pulsar.common.api.Commands.readChecksum;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.bookkeeper.mledger.util.Rate;
import org.apache.bookkeeper.util.collections.ConcurrentLongLongPairHashMap;
import org.apache.bookkeeper.util.collections.ConcurrentLongLongPairHashMap.LongPair;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.common.api.Commands;
import org.apache.pulsar.common.api.proto.PulsarApi;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandAck;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandAck.AckType;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSubscribe.SubType;
import org.apache.pulsar.common.api.proto.PulsarApi.MessageIdData;
import org.apache.pulsar.common.api.proto.PulsarApi.ProtocolVersion;
import org.apache.pulsar.common.naming.DestinationName;
import org.apache.pulsar.common.policies.data.ConsumerStats;
import org.apache.pulsar.common.util.DateFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * A Consumer is a consumer currently connected and associated with a Subscription
 */
public class Consumer {
    private final Subscription subscription;
    private final SubType subType;
    private final ServerCnx cnx;
    private final String appId;
    private final String topicName;

    private final long consumerId;
    private final int priorityLevel;
    private final String consumerName;
    private final Rate msgOut;
    private final Rate msgRedeliver;

    // Represents how many messages we can safely send to the consumer without
    // overflowing its receiving queue. The consumer will use Flow commands to
    // increase its availability
    private static final AtomicIntegerFieldUpdater<Consumer> MESSAGE_PERMITS_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(Consumer.class, "messagePermits");
    private volatile int messagePermits = 0;
    // It starts keep tracking of messagePermits once consumer gets blocked, as consumer needs two separate counts:
    // messagePermits (1) before and (2) after being blocked: to dispatch only blockedPermit number of messages at the
    // time of redelivery
    private static final AtomicIntegerFieldUpdater<Consumer> PERMITS_RECEIVED_WHILE_CONSUMER_BLOCKED_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(Consumer.class, "permitsReceivedWhileConsumerBlocked");
    private volatile int permitsReceivedWhileConsumerBlocked = 0;

    private final ConcurrentLongLongPairHashMap pendingAcks;

    private final ConsumerStats stats;

    private final int maxUnackedMessages;
    private static final AtomicIntegerFieldUpdater<Consumer> UNACKED_MESSAGES_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(Consumer.class, "unackedMessages");
    private volatile int unackedMessages = 0;
    private volatile boolean blockedConsumerOnUnackedMsgs = false;

    public Consumer(Subscription subscription, SubType subType, String topicName, long consumerId, int priorityLevel, String consumerName,
            int maxUnackedMessages, ServerCnx cnx, String appId) throws BrokerServiceException {

        this.subscription = subscription;
        this.subType = subType;
        this.topicName = topicName;
        this.consumerId = consumerId;
        this.priorityLevel = priorityLevel;
        this.consumerName = consumerName;
        this.maxUnackedMessages = maxUnackedMessages;
        this.cnx = cnx;
        this.msgOut = new Rate();
        this.msgRedeliver = new Rate();
        this.appId = appId;
        PERMITS_RECEIVED_WHILE_CONSUMER_BLOCKED_UPDATER.set(this, 0);
        MESSAGE_PERMITS_UPDATER.set(this, 0);
        UNACKED_MESSAGES_UPDATER.set(this, 0);

        stats = new ConsumerStats();
        stats.address = cnx.clientAddress().toString();
        stats.consumerName = consumerName;
        stats.connectedSince = DateFormatter.now();
        stats.clientVersion = cnx.getClientVersion();

        if (subType == SubType.Shared) {
            this.pendingAcks = new ConcurrentLongLongPairHashMap(256, 1);
        } else {
            // We don't need to keep track of pending acks if the subscription is not shared
            this.pendingAcks = null;
        }
    }

    public SubType subType() {
        return subType;
    }

    public long consumerId() {
        return consumerId;
    }

    public String consumerName() {
        return consumerName;
    }

    /**
     * Dispatch a list of entries to the consumer. <br/>
     * <b>It is also responsible to release entries data and recycle entries object.</b>
     *
     * @return a promise that can be use to track when all the data has been written into the socket
     */
    public SendMessageInfo sendMessages(final List<Entry> entries) {
        final ChannelHandlerContext ctx = cnx.ctx();
        final SendMessageInfo sentMessages = new SendMessageInfo();
        final ChannelPromise writePromise = ctx.newPromise();
        sentMessages.channelPromse = writePromise;
        if (entries.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[{}-{}] List of messages is empty, triggering write future immediately for consumerId {}",
                        topicName, subscription, consumerId);
            }
            writePromise.setSuccess();
            sentMessages.totalSentMessages = 0;
            sentMessages.totalSentMessageBytes = 0;
            return sentMessages;
        }

        try {
            updatePermitsAndPendingAcks(entries, sentMessages);
        } catch (PulsarServerException pe) {
            log.warn("[{}] [{}] consumer doesn't support batch-message {}", subscription, consumerId,
                    cnx.getRemoteEndpointProtocolVersion());

            subscription.markTopicWithBatchMessagePublished();
            sentMessages.totalSentMessages = 0;
            sentMessages.totalSentMessageBytes = 0;
            // disconnect consumer: it will update dispatcher's availablePermits and resend pendingAck-messages of this
            // consumer to other consumer
            disconnect();
            return sentMessages;
        }

        ctx.channel().eventLoop().execute(() -> {
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                PositionImpl pos = (PositionImpl) entry.getPosition();
                MessageIdData.Builder messageIdBuilder = MessageIdData.newBuilder();
                MessageIdData messageId = messageIdBuilder.setLedgerId(pos.getLedgerId()).setEntryId(pos.getEntryId())
                        .build();

                ByteBuf metadataAndPayload = entry.getDataBuffer();
                // increment ref-count of data and release at the end of process: so, we can get chance to call entry.release
                metadataAndPayload.retain();
                // skip checksum by incrementing reader-index if consumer-client doesn't support checksum verification
                if (cnx.getRemoteEndpointProtocolVersion() < ProtocolVersion.v6.getNumber()) {
                    readChecksum(metadataAndPayload);
                }

                if (log.isDebugEnabled()) {
                    log.debug("[{}-{}] Sending message to consumerId {}, entry id {}", topicName, subscription,
                            consumerId, pos.getEntryId());
                }

                // We only want to pass the "real" promise on the last entry written
                ChannelPromise promise = ctx.voidPromise();
                if (i == (entries.size() - 1)) {
                    promise = writePromise;
                }
                ctx.write(Commands.newMessage(consumerId, messageId, metadataAndPayload), promise);
                messageId.recycle();
                messageIdBuilder.recycle();
                entry.release();
            }

            ctx.flush();
        });

        return sentMessages;
    }

    private void incrementUnackedMessages(int ackedMessages) {
        if (shouldBlockConsumerOnUnackMsgs() && addAndGetUnAckedMsgs(this, ackedMessages) >= maxUnackedMessages) {
            blockedConsumerOnUnackedMsgs = true;
        }
    }

    public static int getBatchSizeforEntry(ByteBuf metadataAndPayload, String subscription, long consumerId) {
        try {
            // save the reader index and restore after parsing
            metadataAndPayload.markReaderIndex();
            PulsarApi.MessageMetadata metadata = Commands.parseMessageMetadata(metadataAndPayload);
            metadataAndPayload.resetReaderIndex();
            int batchSize = metadata.getNumMessagesInBatch();
            metadata.recycle();
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] num messages in batch are {} ", subscription, consumerId, batchSize);
            }
            return batchSize;
        } catch (Throwable t) {
            log.error("[{}] [{}] Failed to parse message metadata", subscription, consumerId, t);
        }
        return -1;
    }

    void updatePermitsAndPendingAcks(final List<Entry> entries, SendMessageInfo sentMessages) throws PulsarServerException {
        int permitsToReduce = 0;
        Iterator<Entry> iter = entries.iterator();
        boolean unsupportedVersion = false;
        long totalReadableBytes = 0;
        boolean clientSupportBatchMessages = cnx.isBatchMessageCompatibleVersion();
        while (iter.hasNext()) {
            Entry entry = iter.next();
            ByteBuf metadataAndPayload = entry.getDataBuffer();
            int batchSize = getBatchSizeforEntry(metadataAndPayload, subscription.toString(), consumerId);
            if (batchSize == -1) {
                // this would suggest that the message might have been corrupted
                iter.remove();
                PositionImpl pos = (PositionImpl) entry.getPosition();
                entry.release();
                subscription.acknowledgeMessage(pos, AckType.Individual);
                continue;
            }
            if (pendingAcks != null) {
                pendingAcks.put(entry.getLedgerId(), entry.getEntryId(), batchSize, 0);
            }
            // check if consumer supports batch message
            if (batchSize > 1 && !clientSupportBatchMessages) {
                unsupportedVersion = true;
            }
            totalReadableBytes += metadataAndPayload.readableBytes();
            permitsToReduce += batchSize;
        }
        // reduce permit and increment unackedMsg count with total number of messages in batch-msgs
        int permits = MESSAGE_PERMITS_UPDATER.addAndGet(this, -permitsToReduce);
        incrementUnackedMessages(permitsToReduce);
        if (unsupportedVersion) {
            throw new PulsarServerException("Consumer does not support batch-message");
        }
        if (permits < 0) {
            if (log.isDebugEnabled()) {
                log.debug("[{}-{}] [{}] message permits dropped below 0 - {}", topicName, subscription, consumerId,
                        permits);
            }
        }

        msgOut.recordMultipleEvents(permitsToReduce, totalReadableBytes);
        sentMessages.totalSentMessages = permitsToReduce;
        sentMessages.totalSentMessageBytes = totalReadableBytes;
    }

    public boolean isWritable() {
        return cnx.isWritable();
    }

    public void sendError(ByteBuf error) {
        cnx.ctx().writeAndFlush(error).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Close the consumer if: a. the connection is dropped b. connection is open (graceful close) and there are no
     * pending message acks
     */
    public void close() throws BrokerServiceException {
        subscription.removeConsumer(this);
        cnx.removedConsumer(this);
    }

    public void disconnect() {
        log.info("Disconnecting consumer: {}", this);
        cnx.closeConsumer(this);
        try {
            close();
        } catch (BrokerServiceException e) {
            log.warn("Consumer {} was already closed: {}", this, e.getMessage(), e);
        }
    }

    void doUnsubscribe(final long requestId) {
        final ChannelHandlerContext ctx = cnx.ctx();

        subscription.doUnsubscribe(this).thenAccept(v -> {
            log.info("Unsubscribed successfully from {}", subscription);
            cnx.removedConsumer(this);
            ctx.writeAndFlush(Commands.newSuccess(requestId));
        }).exceptionally(exception -> {
            log.warn("Unsubscribe failed for {}", subscription, exception);
            ctx.writeAndFlush(
                    Commands.newError(requestId, BrokerServiceException.getClientErrorCode(exception.getCause()),
                            exception.getCause().getMessage()));
            return null;
        });
    }

    void messageAcked(CommandAck ack) {
        MessageIdData msgId = ack.getMessageId();
        PositionImpl position = PositionImpl.get(msgId.getLedgerId(), msgId.getEntryId());

        if (ack.hasValidationError()) {
            log.error("[{}] [{}] Received ack for corrupted message at {} - Reason: {}", subscription, consumerId,
                    position, ack.getValidationError());
        }

        if (subType == SubType.Shared) {
            // On shared subscriptions, cumulative ack is not supported
            checkArgument(ack.getAckType() == AckType.Individual);

            // Only ack a single message
            removePendingAcks(position);
            subscription.acknowledgeMessage(position, AckType.Individual);
        } else {
            subscription.acknowledgeMessage(position, ack.getAckType());
        }

    }

    void flowPermits(int additionalNumberOfMessages) {
        checkArgument(additionalNumberOfMessages > 0);

        // block shared consumer when unacked-messages reaches limit
        if (shouldBlockConsumerOnUnackMsgs() && unackedMessages >= maxUnackedMessages) {
            blockedConsumerOnUnackedMsgs = true;
        }
        int oldPermits;
        if (!blockedConsumerOnUnackedMsgs) {
            oldPermits = MESSAGE_PERMITS_UPDATER.getAndAdd(this, additionalNumberOfMessages);
            subscription.consumerFlow(this, additionalNumberOfMessages);
        } else {
            oldPermits = PERMITS_RECEIVED_WHILE_CONSUMER_BLOCKED_UPDATER.getAndAdd(this, additionalNumberOfMessages);
        }

        if (log.isDebugEnabled()) {
            log.debug("[{}-{}] Added more flow control message permits {} (old was: {}), blocked = ", topicName,
                    subscription, additionalNumberOfMessages, oldPermits, blockedConsumerOnUnackedMsgs);
        }

    }

    /**
     * Triggers dispatcher to dispatch {@code blockedPermits} number of messages and adds same number of permits to
     * {@code messagePermits} as it maintains count of actual dispatched message-permits.
     *
     * @param consumer:
     *            Consumer whose blockedPermits needs to be dispatched
     */
    void flowConsumerBlockedPermits(Consumer consumer) {
        int additionalNumberOfPermits = PERMITS_RECEIVED_WHILE_CONSUMER_BLOCKED_UPDATER.getAndSet(consumer, 0);
        // add newly flow permits to actual consumer.messagePermits
        MESSAGE_PERMITS_UPDATER.getAndAdd(consumer, additionalNumberOfPermits);
        // dispatch pending permits to flow more messages: it will add more permits to dispatcher and consumer
        subscription.consumerFlow(consumer, additionalNumberOfPermits);
    }

    public int getAvailablePermits() {
        return MESSAGE_PERMITS_UPDATER.get(this);
    }

    public boolean isBlocked() {
        return blockedConsumerOnUnackedMsgs;
    }

    public void reachedEndOfTopic() {
        // Only send notification if the client understand the command
        if (cnx.getRemoteEndpointProtocolVersion() >= ProtocolVersion.v9_VALUE) {
            log.info("[{}] Notifying consumer that end of topic has been reached", this);
            cnx.ctx().writeAndFlush(Commands.newReachedEndOfTopic(consumerId));
        }
    }

    /**
     * Checks if consumer-blocking on unAckedMessages is allowed for below conditions:<br/>
     * a. consumer must have Shared-subscription<br/>
     * b. {@link maxUnackedMessages} value > 0
     *
     * @return
     */
    private boolean shouldBlockConsumerOnUnackMsgs() {
        return SubType.Shared.equals(subType) && maxUnackedMessages > 0;
    }

    public void updateRates() {
        msgOut.calculateRate();
        msgRedeliver.calculateRate();
        stats.msgRateOut = msgOut.getRate();
        stats.msgThroughputOut = msgOut.getValueRate();
        stats.msgRateRedeliver = msgRedeliver.getRate();
    }

    public ConsumerStats getStats() {
        stats.availablePermits = getAvailablePermits();
        stats.unackedMessages = unackedMessages;
        stats.blockedConsumerOnUnackedMsgs = blockedConsumerOnUnackedMsgs;
        return stats;
    }

    public int getUnackedMessages() {
        return unackedMessages;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("subscription", subscription).add("consumerId", consumerId)
                .add("consumerName", consumerName).add("address", this.cnx.clientAddress()).toString();
    }

    public ChannelHandlerContext ctx() {
        return cnx.ctx();
    }

    public void checkPermissions() {
        DestinationName destination = DestinationName.get(subscription.getDestination());
        if (cnx.getBrokerService().getAuthorizationManager() != null) {
            try {
                if (cnx.getBrokerService().getAuthorizationManager().canConsume(destination, appId)) {
                    return;
                }
            } catch (Exception e) {
                log.warn("[{}] Get unexpected error while autorizing [{}]  {}", appId, subscription.getDestination(),
                        e.getMessage(), e);
            }
            log.info("[{}] is not allowed to consume from Destination" + " [{}] anymore", appId,
                    subscription.getDestination());
            disconnect();
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Consumer) {
            Consumer other = (Consumer) obj;
            return Objects.equals(cnx.clientAddress(), other.cnx.clientAddress()) && consumerId == other.consumerId;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return consumerName.hashCode() + 31 * cnx.hashCode();
    }

    /**
     * first try to remove ack-position from the current_consumer's pendingAcks.
     * if ack-message doesn't present into current_consumer's pendingAcks
     *  a. try to remove from other connected subscribed consumers (It happens when client
     * tries to acknowledge message through different consumer under the same subscription)
     *
     *
     * @param position
     */
    private void removePendingAcks(PositionImpl position) {
        Consumer ackOwnedConsumer = null;
        if (pendingAcks.get(position.getLedgerId(), position.getEntryId()) == null) {
            for (Consumer consumer : subscription.getConsumers()) {
                if (!consumer.equals(this) && consumer.getPendingAcks().containsKey(position.getLedgerId(), position.getEntryId())) {
                    ackOwnedConsumer = consumer;
                    break;
                }
            }
        } else {
            ackOwnedConsumer = this;
        }

        // remove pending message from appropriate consumer and unblock unAckMsg-flow if requires
        if (ackOwnedConsumer != null) {
            int totalAckedMsgs = (int) ackOwnedConsumer.getPendingAcks().get(position.getLedgerId(), position.getEntryId()).first;
            if (!ackOwnedConsumer.getPendingAcks().remove(position.getLedgerId(), position.getEntryId())) {
                // Message was already removed by the other consumer
                return;
            }
            if (log.isDebugEnabled()) {
                log.debug("[{}-{}] consumer {} received ack {}", topicName, subscription, consumerId, position);
            }
            // unblock consumer-throttling when receives half of maxUnackedMessages => consumer can start again
            // consuming messages
            if (((addAndGetUnAckedMsgs(ackOwnedConsumer, -totalAckedMsgs) <= (maxUnackedMessages / 2))
                    && ackOwnedConsumer.blockedConsumerOnUnackedMsgs)
                    && ackOwnedConsumer.shouldBlockConsumerOnUnackMsgs()) {
                ackOwnedConsumer.blockedConsumerOnUnackedMsgs = false;
                flowConsumerBlockedPermits(ackOwnedConsumer);
            }
        }
    }

    public ConcurrentLongLongPairHashMap getPendingAcks() {
        return pendingAcks;
    }

    public int getPriorityLevel() {
        return priorityLevel;
    }

    public void redeliverUnacknowledgedMessages() {
        // cleanup unackedMessage bucket and redeliver those unack-msgs again
        clearUnAckedMsgs(this);
        blockedConsumerOnUnackedMsgs = false;
        if (log.isDebugEnabled()) {
            log.debug("[{}-{}] consumer {} received redelivery", topicName, subscription, consumerId);
        }
        // redeliver unacked-msgs
        subscription.redeliverUnacknowledgedMessages(this);
        flowConsumerBlockedPermits(this);
        if (pendingAcks != null) {
            AtomicInteger totalRedeliveryMessages = new AtomicInteger(0);
            pendingAcks.forEach(
                    (ledgerId, entryId, batchSize, none) -> totalRedeliveryMessages.addAndGet((int) batchSize));
            msgRedeliver.recordMultipleEvents(totalRedeliveryMessages.get(), totalRedeliveryMessages.get());
            pendingAcks.clear();
        }

    }

    public void redeliverUnacknowledgedMessages(List<MessageIdData> messageIds) {

        int totalRedeliveryMessages = 0;
        List<PositionImpl> pendingPositions = Lists.newArrayList();
        for (MessageIdData msg : messageIds) {
            PositionImpl position = PositionImpl.get(msg.getLedgerId(), msg.getEntryId());
            LongPair batchSize = pendingAcks.get(position.getLedgerId(), position.getEntryId());
            if (batchSize != null) {
                pendingAcks.remove(position.getLedgerId(), position.getEntryId());
                totalRedeliveryMessages += batchSize.first;
                pendingPositions.add(position);
            }
        }

        addAndGetUnAckedMsgs(this, -totalRedeliveryMessages);
        blockedConsumerOnUnackedMsgs = false;

        if (log.isDebugEnabled()) {
            log.debug("[{}-{}] consumer {} received {} msg-redelivery {}", topicName, subscription, consumerId,
                    totalRedeliveryMessages, pendingPositions.size());
        }

        subscription.redeliverUnacknowledgedMessages(this, pendingPositions);
        msgRedeliver.recordMultipleEvents(totalRedeliveryMessages, totalRedeliveryMessages);

        int numberOfBlockedPermits = Math.min(totalRedeliveryMessages,
                PERMITS_RECEIVED_WHILE_CONSUMER_BLOCKED_UPDATER.get(this));

        // if permitsReceivedWhileConsumerBlocked has been accumulated then pass it to Dispatcher to flow messages
        if (numberOfBlockedPermits > 0) {
            PERMITS_RECEIVED_WHILE_CONSUMER_BLOCKED_UPDATER.getAndAdd(this, -numberOfBlockedPermits);
            MESSAGE_PERMITS_UPDATER.getAndAdd(this, numberOfBlockedPermits);
            subscription.consumerFlow(this, numberOfBlockedPermits);
        }
    }

    public Subscription getSubscription() {
        return subscription;
    }

    private int addAndGetUnAckedMsgs(Consumer consumer, int ackedMessages) {
        subscription.addUnAckedMessages(ackedMessages);
        return UNACKED_MESSAGES_UPDATER.addAndGet(consumer, ackedMessages);
    }

    private void clearUnAckedMsgs(Consumer consumer) {
        int unaAckedMsgs = UNACKED_MESSAGES_UPDATER.getAndSet(this, 0);
        subscription.addUnAckedMessages(-unaAckedMsgs);
    }

    public static class SendMessageInfo {
        ChannelPromise channelPromse;
        int totalSentMessages;
        long totalSentMessageBytes;

        public ChannelPromise getChannelPromse() {
            return channelPromse;
        }
        public void setChannelPromse(ChannelPromise channelPromse) {
            this.channelPromse = channelPromse;
        }
        public int getTotalSentMessages() {
            return totalSentMessages;
        }
        public void setTotalSentMessages(int totalSentMessages) {
            this.totalSentMessages = totalSentMessages;
        }
        public long getTotalSentMessageBytes() {
            return totalSentMessageBytes;
        }
        public void setTotalSentMessageBytes(long totalSentMessageBytes) {
            this.totalSentMessageBytes = totalSentMessageBytes;
        }

    }

    private static final Logger log = LoggerFactory.getLogger(Consumer.class);
}
