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

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;

import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.common.api.proto.PulsarApi;
import org.apache.pulsar.common.api.proto.PulsarApi.MessageIdData;
import org.apache.pulsar.common.util.protobuf.ByteBufCodedInputStream;
import org.apache.pulsar.common.util.protobuf.ByteBufCodedOutputStream;

import com.google.common.collect.ComparisonChain;
import com.google.protobuf.UninitializedMessageException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class MessageIdImpl implements MessageId, Comparable<MessageIdImpl> {
    protected final long ledgerId;
    protected final long entryId;
    protected final int partitionIndex;

    // Private constructor used only for json deserialization
    @SuppressWarnings("unused")
    private MessageIdImpl() {
        this(-1, -1, -1);
    }

    public MessageIdImpl(long ledgerId, long entryId, int partitionIndex) {
        this.ledgerId = ledgerId;
        this.entryId = entryId;
        this.partitionIndex = partitionIndex;
    }

    public long getLedgerId() {
        return ledgerId;
    }

    public long getEntryId() {
        return entryId;
    }

    public int getPartitionIndex() {
        return partitionIndex;
    }

    @Override
    public int compareTo(MessageIdImpl other) {

        return ComparisonChain.start().compare(this.ledgerId, other.ledgerId).compare(this.entryId, other.entryId)
                .compare(this.getPartitionIndex(), other.getPartitionIndex()).result();
    }

    @Override
    public int hashCode() {
        return (int) (31 * (ledgerId + 31 * entryId) + partitionIndex);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof MessageIdImpl) {
            MessageIdImpl other = (MessageIdImpl) obj;
            return ledgerId == other.ledgerId && entryId == other.entryId && partitionIndex == other.partitionIndex;
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format("%d:%d:%d", ledgerId, entryId, partitionIndex);
    }

    // / Serialization

    public static MessageId fromByteArray(byte[] data) throws IOException {
        checkNotNull(data);
        ByteBufCodedInputStream inputStream = ByteBufCodedInputStream.get(Unpooled.wrappedBuffer(data, 0, data.length));
        PulsarApi.MessageIdData.Builder builder = PulsarApi.MessageIdData.newBuilder();

        PulsarApi.MessageIdData idData;
        try {
            idData = builder.mergeFrom(inputStream, null).build();
        } catch (UninitializedMessageException e) {
            throw new IOException(e);
        }

        MessageIdImpl messageId;
        if (idData.hasBatchIndex()) {
            messageId = new BatchMessageIdImpl(idData.getLedgerId(), idData.getEntryId(), idData.getPartition(),
                    idData.getBatchIndex());
        } else {
            messageId = new MessageIdImpl(idData.getLedgerId(), idData.getEntryId(), idData.getPartition());
        }

        inputStream.recycle();
        builder.recycle();
        idData.recycle();
        return messageId;
    }

    // batchIndex is -1 if message is non-batched message and has the batchIndex for a batch message
    protected byte[] toByteArray(int batchIndex) {
        MessageIdData.Builder builder = MessageIdData.newBuilder();
        builder.setLedgerId(ledgerId);
        builder.setEntryId(entryId);
        if (partitionIndex >= 0) {
            builder.setPartition(partitionIndex);
        }

        if (batchIndex != -1) {
            builder.setBatchIndex(batchIndex);
        }

        MessageIdData msgId = builder.build();
        int size = msgId.getSerializedSize();
        ByteBuf serialized = Unpooled.buffer(size, size);
        ByteBufCodedOutputStream stream = ByteBufCodedOutputStream.get(serialized);
        try {
            msgId.writeTo(stream);
        } catch (IOException e) {
            // This is in-memory serialization, should not fail
            throw new RuntimeException(e);
        }

        msgId.recycle();
        builder.recycle();
        stream.recycle();
        return serialized.array();
    }

    @Override
    public byte[] toByteArray() {
        // there is no message batch so we pass -1
        return toByteArray(-1);
    }
}
