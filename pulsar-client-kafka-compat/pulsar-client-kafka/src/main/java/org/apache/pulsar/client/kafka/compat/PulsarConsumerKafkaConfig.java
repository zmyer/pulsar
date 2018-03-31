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
package org.apache.pulsar.client.kafka.compat;

import java.util.Properties;

import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.PulsarClient;

public class PulsarConsumerKafkaConfig {

    /// Config variables
    public static final String CONSUMER_NAME = "pulsar.consumer.name";
    public static final String RECEIVER_QUEUE_SIZE = "pulsar.consumer.receiver.queue.size";
    public static final String TOTAL_RECEIVER_QUEUE_SIZE_ACROSS_PARTITIONS = "pulsar.consumer.total.receiver.queue.size.across.partitions";

    public static ConsumerBuilder<byte[]> getConsumerBuilder(PulsarClient client, Properties properties) {
        ConsumerBuilder<byte[]> consumerBuilder = client.newConsumer();

        if (properties.containsKey(CONSUMER_NAME)) {
            consumerBuilder.consumerName(properties.getProperty(CONSUMER_NAME));
        }

        if (properties.containsKey(RECEIVER_QUEUE_SIZE)) {
            consumerBuilder.receiverQueueSize(Integer.parseInt(properties.getProperty(RECEIVER_QUEUE_SIZE)));
        }

        if (properties.containsKey(TOTAL_RECEIVER_QUEUE_SIZE_ACROSS_PARTITIONS)) {
            consumerBuilder.maxTotalReceiverQueueSizeAcrossPartitions(
                    Integer.parseInt(properties.getProperty(TOTAL_RECEIVER_QUEUE_SIZE_ACROSS_PARTITIONS)));
        }

        return consumerBuilder;
    }
}
