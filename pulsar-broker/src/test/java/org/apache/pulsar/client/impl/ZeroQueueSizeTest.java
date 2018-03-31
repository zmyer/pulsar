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

import static org.testng.Assert.assertEquals;

import java.util.concurrent.TimeUnit;

import org.apache.pulsar.broker.service.BrokerTestBase;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class ZeroQueueSizeTest extends BrokerTestBase {
    private static final Logger log = LoggerFactory.getLogger(ZeroQueueSizeTest.class);
    private final int totalMessages = 10;

    @BeforeClass
    @Override
    public void setup() throws Exception {
        baseSetup();
    }

    @AfterClass
    @Override
    protected void cleanup() throws Exception {
        internalCleanup();
    }

    @Test
    public void validQueueSizeConfig() {
        pulsarClient.newConsumer().receiverQueueSize(0);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void InvalidQueueSizeConfig() {
        pulsarClient.newConsumer().receiverQueueSize(-1);
    }

    @Test(expectedExceptions = PulsarClientException.InvalidConfigurationException.class)
    public void zeroQueueSizeReceieveAsyncInCompatibility() throws PulsarClientException {
        String key = "zeroQueueSizeReceieveAsyncInCompatibility";
        final String topicName = "persistent://prop/use/ns-abc/topic-" + key;
        final String subscriptionName = "my-ex-subscription-" + key;

        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic(topicName).subscriptionName(subscriptionName)
                .receiverQueueSize(0).subscribe();
        consumer.receive(10, TimeUnit.SECONDS);
    }

    @Test(expectedExceptions = PulsarClientException.class)
    public void zeroQueueSizePartitionedTopicInCompatibility() throws PulsarClientException, PulsarAdminException {
        String key = "zeroQueueSizePartitionedTopicInCompatibility";
        final String topicName = "persistent://prop/use/ns-abc/topic-" + key;
        final String subscriptionName = "my-ex-subscription-" + key;
        int numberOfPartitions = 3;
        admin.persistentTopics().createPartitionedTopic(topicName, numberOfPartitions);
        pulsarClient.newConsumer().topic(topicName).subscriptionName(subscriptionName).receiverQueueSize(0).subscribe();
    }

    @Test()
    public void zeroQueueSizeNormalConsumer() throws PulsarClientException {
        String key = "nonZeroQueueSizeNormalConsumer";

        // 1. Config
        final String topicName = "persistent://prop/use/ns-abc/topic-" + key;
        final String subscriptionName = "my-ex-subscription-" + key;
        final String messagePredicate = "my-message-" + key + "-";

        // 2. Create Producer
        Producer<byte[]> producer = pulsarClient.newProducer().topic(topicName).create();

        // 3. Create Consumer
        ConsumerImpl<byte[]> consumer = (ConsumerImpl<byte[]>) pulsarClient.newConsumer().topic(topicName)
                .subscriptionName(subscriptionName).receiverQueueSize(0).subscribe();

        // 3. producer publish messages
        for (int i = 0; i < totalMessages; i++) {
            String message = messagePredicate + i;
            log.info("Producer produced: " + message);
            producer.send(message.getBytes());
        }

        // 4. Receiver receives the message
        Message<byte[]> message;
        for (int i = 0; i < totalMessages; i++) {
            assertEquals(consumer.numMessagesInQueue(), 0);
            message = consumer.receive();
            assertEquals(new String(message.getData()), messagePredicate + i);
            assertEquals(consumer.numMessagesInQueue(), 0);
            log.info("Consumer received : " + new String(message.getData()));
        }
    }

    @Test()
    public void zeroQueueSizeSharedSubscription() throws PulsarClientException {
        String key = "zeroQueueSizeSharedSubscription";

        // 1. Config
        final String topicName = "persistent://prop/use/ns-abc/topic-" + key;
        final String subscriptionName = "my-ex-subscription-" + key;
        final String messagePredicate = "my-message-" + key + "-";

        // 2. Create Producer
        Producer<byte[]> producer = pulsarClient.newProducer().topic(topicName).create();

        // 3. Create Consumer
        int numOfSubscribers = 4;
        ConsumerImpl<?>[] consumers = new ConsumerImpl[numOfSubscribers];
        for (int i = 0; i < numOfSubscribers; i++) {
            consumers[i] = (ConsumerImpl<byte[]>) pulsarClient.newConsumer().topic(topicName)
                    .subscriptionName(subscriptionName).receiverQueueSize(0).subscriptionType(SubscriptionType.Shared)
                    .subscribe();
        }

        // 4. Produce Messages
        for (int i = 0; i < totalMessages; i++) {
            String message = messagePredicate + i;
            producer.send(message.getBytes());
        }

        // 5. Consume messages
        Message<?> message;
        for (int i = 0; i < totalMessages; i++) {
            assertEquals(consumers[i % numOfSubscribers].numMessagesInQueue(), 0);
            message = consumers[i % numOfSubscribers].receive();
            assertEquals(new String(message.getData()), messagePredicate + i);
            assertEquals(consumers[i % numOfSubscribers].numMessagesInQueue(), 0);
            log.info("Consumer received : " + new String(message.getData()));
        }
    }

    @Test()
    public void zeroQueueSizeFailoverSubscription() throws PulsarClientException {
        String key = "zeroQueueSizeFailoverSubscription";

        // 1. Config
        final String topicName = "persistent://prop/use/ns-abc/topic-" + key;
        final String subscriptionName = "my-ex-subscription-" + key;
        final String messagePredicate = "my-message-" + key + "-";

        // 2. Create Producer
        Producer<byte[]> producer = pulsarClient.newProducer().topic(topicName).create();

        // 3. Create Consumer
        ConsumerImpl<byte[]> consumer1 = (ConsumerImpl<byte[]>) pulsarClient.newConsumer().topic(topicName)
                .subscriptionName(subscriptionName).receiverQueueSize(0).subscriptionType(SubscriptionType.Failover)
                .consumerName("consumer-1").subscribe();
        ConsumerImpl<byte[]> consumer2 = (ConsumerImpl<byte[]>) pulsarClient.newConsumer().topic(topicName)
                .subscriptionName(subscriptionName).receiverQueueSize(0).subscriptionType(SubscriptionType.Failover)
                .consumerName("consumer-2").subscribe();

        // 4. Produce Messages
        for (int i = 0; i < totalMessages; i++) {
            String message = messagePredicate + i;
            producer.send(message.getBytes());
        }

        // 5. Consume messages
        Message<byte[]> message;
        for (int i = 0; i < totalMessages / 2; i++) {
            assertEquals(consumer1.numMessagesInQueue(), 0);
            message = consumer1.receive();
            assertEquals(new String(message.getData()), messagePredicate + i);
            assertEquals(consumer1.numMessagesInQueue(), 0);
            log.info("Consumer received : " + new String(message.getData()));
        }

        // 6. Trigger redelivery
        consumer1.redeliverUnacknowledgedMessages();

        // 7. Trigger Failover
        consumer1.close();

        // 8. Receive messages on failed over consumer
        for (int i = 0; i < totalMessages / 2; i++) {
            assertEquals(consumer2.numMessagesInQueue(), 0);
            message = consumer2.receive();
            assertEquals(new String(message.getData()), messagePredicate + i);
            assertEquals(consumer2.numMessagesInQueue(), 0);
            log.info("Consumer received : " + new String(message.getData()));
        }
    }

    @Test()
    public void testFailedZeroQueueSizeBatchMessage() throws PulsarClientException {

        int batchMessageDelayMs = 100;
        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic("persistent://prop-xyz/use/ns-abc/topic1")
                .subscriptionName("my-subscriber-name").subscriptionType(SubscriptionType.Shared).receiverQueueSize(0)
                .subscribe();

        ProducerBuilder<byte[]> producerBuilder = pulsarClient.newProducer()
                .topic("persistent://prop-xyz/use/ns-abc/topic1");

        if (batchMessageDelayMs != 0) {
            producerBuilder.enableBatching(true).batchingMaxPublishDelay(batchMessageDelayMs, TimeUnit.MILLISECONDS)
                    .batchingMaxMessages(5);
        }

        Producer<byte[]> producer = producerBuilder.create();
        for (int i = 0; i < 10; i++) {
            String message = "my-message-" + i;
            producer.send(message.getBytes());
        }

        try {
            consumer.receiveAsync().handle((ok, e) -> {
                if (e == null) {
                    // as zero receiverQueueSize doesn't support batch message, must receive exception at callback.
                    Assert.fail();
                }
                return null;
            });
        } finally {
            consumer.close();
        }
    }
}
