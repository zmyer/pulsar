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
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import com.google.common.collect.Lists;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerConsumerBase;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.common.policies.data.PropertyAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@SuppressWarnings({ "unchecked", "rawtypes" })
public class TopicsConsumerImplTest extends ProducerConsumerBase {
    private static final long testTimeout = 90000; // 1.5 min
    private static final Logger log = LoggerFactory.getLogger(TopicsConsumerImplTest.class);
    private final long ackTimeOutMillis = TimeUnit.SECONDS.toMillis(2);

    @Override
    @BeforeMethod
    public void setup() throws Exception {
        super.internalSetup();
    }

    @Override
    @AfterMethod
    public void cleanup() throws Exception {
        super.internalCleanup();
    }

    // Verify subscribe topics from different namespace should return error.
    @Test(timeOut = testTimeout)
    public void testDifferentTopicsNameSubscribe() throws Exception {
        String key = "TopicsFromDifferentNamespace";
        final String subscriptionName = "my-ex-subscription-" + key;

        final String topicName1 = "persistent://prop/use/ns-abc1/topic-1-" + key;
        final String topicName2 = "persistent://prop/use/ns-abc2/topic-2-" + key;
        final String topicName3 = "persistent://prop/use/ns-abc3/topic-3-" + key;
        List<String> topicNames = Lists.newArrayList(topicName1, topicName2, topicName3);

        admin.properties().createProperty("prop", new PropertyAdmin());
        admin.persistentTopics().createPartitionedTopic(topicName2, 2);
        admin.persistentTopics().createPartitionedTopic(topicName3, 3);

        // 2. Create consumer
        try {
            pulsarClient.newConsumer()
                .topics(topicNames)
                .subscriptionName(subscriptionName)
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
                .subscribe();
            fail("subscribe for topics from different namespace should fail.");
        } catch (IllegalArgumentException e) {
            // expected for have different namespace
        }
    }

    @Test(timeOut = testTimeout)
    public void testGetConsumersAndGetTopics() throws Exception {
        String key = "TopicsConsumerGet";
        final String subscriptionName = "my-ex-subscription-" + key;

        final String topicName1 = "persistent://prop/use/ns-abc/topic-1-" + key;
        final String topicName2 = "persistent://prop/use/ns-abc/topic-2-" + key;
        final String topicName3 = "persistent://prop/use/ns-abc/topic-3-" + key;
        List<String> topicNames = Lists.newArrayList(topicName1, topicName2);

        admin.properties().createProperty("prop", new PropertyAdmin());
        admin.persistentTopics().createPartitionedTopic(topicName2, 2);
        admin.persistentTopics().createPartitionedTopic(topicName3, 3);

        // 2. Create consumer
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
            .topics(topicNames)
            .topic(topicName3)
            .subscriptionName(subscriptionName)
            .subscriptionType(SubscriptionType.Shared)
            .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
            .receiverQueueSize(4)
            .subscribe();
        assertTrue(consumer instanceof TopicsConsumerImpl);

        List<String> topics = ((TopicsConsumerImpl<byte[]>) consumer).getPartitionedTopics();
        List<ConsumerImpl<byte[]>> consumers = ((TopicsConsumerImpl) consumer).getConsumers();

        topics.forEach(topic -> log.info("topic: {}", topic));
        consumers.forEach(c -> log.info("consumer: {}", c.getTopic()));

        IntStream.range(0, 6).forEach(index ->
            assertTrue(topics.get(index).equals(consumers.get(index).getTopic())));

        assertTrue(((TopicsConsumerImpl<byte[]>) consumer).getTopics().size() == 3);

        consumer.unsubscribe();
        consumer.close();
    }

    @Test(timeOut = testTimeout)
    public void testSyncProducerAndConsumer() throws Exception {
        String key = "TopicsConsumerSyncTest";
        final String subscriptionName = "my-ex-subscription-" + key;
        final String messagePredicate = "my-message-" + key + "-";
        final int totalMessages = 30;

        final String topicName1 = "persistent://prop/use/ns-abc/topic-1-" + key;
        final String topicName2 = "persistent://prop/use/ns-abc/topic-2-" + key;
        final String topicName3 = "persistent://prop/use/ns-abc/topic-3-" + key;
        List<String> topicNames = Lists.newArrayList(topicName1, topicName2, topicName3);

        admin.properties().createProperty("prop", new PropertyAdmin());
        admin.persistentTopics().createPartitionedTopic(topicName2, 2);
        admin.persistentTopics().createPartitionedTopic(topicName3, 3);

        // 1. producer connect
        Producer<byte[]> producer1 = pulsarClient.newProducer().topic(topicName1)
            .create();
        Producer<byte[]> producer2 = pulsarClient.newProducer().topic(topicName2)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();
        Producer<byte[]> producer3 = pulsarClient.newProducer().topic(topicName3)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();

        // 2. Create consumer
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
            .topics(topicNames)
            .subscriptionName(subscriptionName)
            .subscriptionType(SubscriptionType.Shared)
            .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
            .receiverQueueSize(4)
            .subscribe();
        assertTrue(consumer instanceof TopicsConsumerImpl);

        // 3. producer publish messages
        for (int i = 0; i < totalMessages / 3; i++) {
            producer1.send((messagePredicate + "producer1-" + i).getBytes());
            producer2.send((messagePredicate + "producer2-" + i).getBytes());
            producer3.send((messagePredicate + "producer3-" + i).getBytes());
        }

        int messageSet = 0;
        Message<byte[]> message = consumer.receive();
        do {
            assertTrue(message instanceof TopicMessageImpl);
            messageSet ++;
            consumer.acknowledge(message);
            log.debug("Consumer acknowledged : " + new String(message.getData()));
            message = consumer.receive(500, TimeUnit.MILLISECONDS);
        } while (message != null);
        assertEquals(messageSet, totalMessages);

        consumer.unsubscribe();
        consumer.close();
        producer1.close();
        producer2.close();
        producer3.close();
    }

    @Test(timeOut = testTimeout)
    public void testAsyncConsumer() throws Exception {
        String key = "TopicsConsumerAsyncTest";
        final String subscriptionName = "my-ex-subscription-" + key;
        final String messagePredicate = "my-message-" + key + "-";
        final int totalMessages = 30;

        final String topicName1 = "persistent://prop/use/ns-abc/topic-1-" + key;
        final String topicName2 = "persistent://prop/use/ns-abc/topic-2-" + key;
        final String topicName3 = "persistent://prop/use/ns-abc/topic-3-" + key;
        List<String> topicNames = Lists.newArrayList(topicName1, topicName2, topicName3);

        admin.properties().createProperty("prop", new PropertyAdmin());
        admin.persistentTopics().createPartitionedTopic(topicName2, 2);
        admin.persistentTopics().createPartitionedTopic(topicName3, 3);

        // 1. producer connect
        Producer<byte[]> producer1 = pulsarClient.newProducer().topic(topicName1)
            .create();
        Producer<byte[]> producer2 = pulsarClient.newProducer().topic(topicName2)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();
        Producer<byte[]> producer3 = pulsarClient.newProducer().topic(topicName3)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();

        // 2. Create consumer
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
            .topics(topicNames)
            .subscriptionName(subscriptionName)
            .subscriptionType(SubscriptionType.Shared)
            .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
            .receiverQueueSize(4)
            .subscribe();
        assertTrue(consumer instanceof TopicsConsumerImpl);

        // Asynchronously produce messages
        List<Future<MessageId>> futures = Lists.newArrayList();
        for (int i = 0; i < totalMessages / 3; i++) {
            futures.add(producer1.sendAsync((messagePredicate + "producer1-" + i).getBytes()));
            futures.add(producer2.sendAsync((messagePredicate + "producer2-" + i).getBytes()));
            futures.add(producer3.sendAsync((messagePredicate + "producer3-" + i).getBytes()));
        }
        log.info("Waiting for async publish to complete : {}", futures.size());
        for (Future<MessageId> future : futures) {
            future.get();
        }

        log.info("start async consume");
        CountDownLatch latch = new CountDownLatch(totalMessages);
        ExecutorService executor = Executors.newFixedThreadPool(1);
        executor.execute(() -> IntStream.range(0, totalMessages).forEach(index ->
            consumer.receiveAsync()
                .thenAccept(msg -> {
                    assertTrue(msg instanceof TopicMessageImpl);
                    try {
                        consumer.acknowledge(msg);
                    } catch (PulsarClientException e1) {
                        fail("message acknowledge failed", e1);
                    }
                    latch.countDown();
                    log.info("receive index: {}, latch countDown: {}", index, latch.getCount());
                })
                .exceptionally(ex -> {
                    log.warn("receive index: {}, failed receive message {}", index, ex.getMessage());
                    ex.printStackTrace();
                    return null;
                })));

        latch.await();
        log.info("success latch wait");

        consumer.unsubscribe();
        consumer.close();
        producer1.close();
        producer2.close();
        producer3.close();
    }

    @Test(timeOut = testTimeout)
    public void testConsumerUnackedRedelivery() throws Exception {
        String key = "TopicsConsumerRedeliveryTest";
        final String subscriptionName = "my-ex-subscription-" + key;
        final String messagePredicate = "my-message-" + key + "-";
        final int totalMessages = 30;

        final String topicName1 = "persistent://prop/use/ns-abc/topic-1-" + key;
        final String topicName2 = "persistent://prop/use/ns-abc/topic-2-" + key;
        final String topicName3 = "persistent://prop/use/ns-abc/topic-3-" + key;
        List<String> topicNames = Lists.newArrayList(topicName1, topicName2, topicName3);

        admin.properties().createProperty("prop", new PropertyAdmin());
        admin.persistentTopics().createPartitionedTopic(topicName2, 2);
        admin.persistentTopics().createPartitionedTopic(topicName3, 3);

        // 1. producer connect
        Producer<byte[]> producer1 = pulsarClient.newProducer().topic(topicName1)
            .create();
        Producer<byte[]> producer2 = pulsarClient.newProducer().topic(topicName2)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();
        Producer<byte[]> producer3 = pulsarClient.newProducer().topic(topicName3)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();

        // 2. Create consumer
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
            .topics(topicNames)
            .subscriptionName(subscriptionName)
            .subscriptionType(SubscriptionType.Shared)
            .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
            .receiverQueueSize(4)
            .subscribe();
        assertTrue(consumer instanceof TopicsConsumerImpl);

        // 3. producer publish messages
        for (int i = 0; i < totalMessages / 3; i++) {
            producer1.send((messagePredicate + "producer1-" + i).getBytes());
            producer2.send((messagePredicate + "producer2-" + i).getBytes());
            producer3.send((messagePredicate + "producer3-" + i).getBytes());
        }

        // 4. Receiver receives the message, not ack, Unacked Message Tracker size should be totalMessages.
        Message<byte[]> message = consumer.receive();
        while (message != null) {
            assertTrue(message instanceof TopicMessageImpl);
            log.debug("Consumer received : " + new String(message.getData()));
            message = consumer.receive(500, TimeUnit.MILLISECONDS);
        }
        long size = ((TopicsConsumerImpl<byte[]>) consumer).getUnAckedMessageTracker().size();
        log.debug(key + " Unacked Message Tracker size is " + size);
        assertEquals(size, totalMessages);

        // 5. Blocking call, redeliver should kick in, after receive and ack, Unacked Message Tracker size should be 0.
        message = consumer.receive();
        HashSet<String> hSet = new HashSet<>();
        do {
            assertTrue(message instanceof TopicMessageImpl);
            hSet.add(new String(message.getData()));
            consumer.acknowledge(message);
            log.debug("Consumer acknowledged : " + new String(message.getData()));
            message = consumer.receive(500, TimeUnit.MILLISECONDS);
        } while (message != null);

        size = ((TopicsConsumerImpl<byte[]>) consumer).getUnAckedMessageTracker().size();
        log.debug(key + " Unacked Message Tracker size is " + size);
        assertEquals(size, 0);
        assertEquals(hSet.size(), totalMessages);

        // 6. producer publish more messages
        for (int i = 0; i < totalMessages / 3; i++) {
            producer1.send((messagePredicate + "producer1-round2" + i).getBytes());
            producer2.send((messagePredicate + "producer2-round2" + i).getBytes());
            producer3.send((messagePredicate + "producer3-round2" + i).getBytes());
        }

        // 7. Receiver receives the message, ack them
        message = consumer.receive();
        int received = 0;
        while (message != null) {
            assertTrue(message instanceof TopicMessageImpl);
            received++;
            String data = new String(message.getData());
            log.debug("Consumer received : " + data);
            consumer.acknowledge(message);
            message = consumer.receive(100, TimeUnit.MILLISECONDS);
        }
        size = ((TopicsConsumerImpl<byte[]>) consumer).getUnAckedMessageTracker().size();
        log.debug(key + " Unacked Message Tracker size is " + size);
        assertEquals(size, 0);
        assertEquals(received, totalMessages);

        // 8. Simulate ackTimeout
        ((TopicsConsumerImpl<byte[]>) consumer).getUnAckedMessageTracker().toggle();
        ((TopicsConsumerImpl<byte[]>) consumer).getConsumers().forEach(c -> c.getUnAckedMessageTracker().toggle());

        // 9. producer publish more messages
        for (int i = 0; i < totalMessages / 3; i++) {
            producer1.send((messagePredicate + "producer1-round3" + i).getBytes());
            producer2.send((messagePredicate + "producer2-round3" + i).getBytes());
            producer3.send((messagePredicate + "producer3-round3" + i).getBytes());
        }

        // 10. Receiver receives the message, doesn't ack
        message = consumer.receive();
        while (message != null) {
            String data = new String(message.getData());
            log.debug("Consumer received : " + data);
            message = consumer.receive(100, TimeUnit.MILLISECONDS);
        }
        size = ((TopicsConsumerImpl<byte[]>) consumer).getUnAckedMessageTracker().size();
        log.debug(key + " Unacked Message Tracker size is " + size);
        assertEquals(size, 30);

        Thread.sleep(ackTimeOutMillis);

        // 11. Receiver receives redelivered messages
        message = consumer.receive();
        int redelivered = 0;
        while (message != null) {
            assertTrue(message instanceof TopicMessageImpl);
            redelivered++;
            String data = new String(message.getData());
            log.debug("Consumer received : " + data);
            consumer.acknowledge(message);
            message = consumer.receive(100, TimeUnit.MILLISECONDS);
        }
        assertEquals(redelivered, 30);
        size =  ((TopicsConsumerImpl<byte[]>) consumer).getUnAckedMessageTracker().size();
        log.info(key + " Unacked Message Tracker size is " + size);
        assertEquals(size, 0);

        consumer.unsubscribe();
        consumer.close();
        producer1.close();
        producer2.close();
        producer3.close();
    }

    @Test
    public void testSubscribeUnsubscribeSingleTopic() throws Exception {
        String key = "TopicsConsumerSubscribeUnsubscribeSingleTopicTest";
        final String subscriptionName = "my-ex-subscription-" + key;
        final String messagePredicate = "my-message-" + key + "-";
        final int totalMessages = 30;

        final String topicName1 = "persistent://prop/use/ns-abc/topic-1-" + key;
        final String topicName2 = "persistent://prop/use/ns-abc/topic-2-" + key;
        final String topicName3 = "persistent://prop/use/ns-abc/topic-3-" + key;
        List<String> topicNames = Lists.newArrayList(topicName1, topicName2, topicName3);

        admin.properties().createProperty("prop", new PropertyAdmin());
        admin.persistentTopics().createPartitionedTopic(topicName2, 2);
        admin.persistentTopics().createPartitionedTopic(topicName3, 3);

        // 1. producer connect
        Producer<byte[]> producer1 = pulsarClient.newProducer().topic(topicName1)
            .create();
        Producer<byte[]> producer2 = pulsarClient.newProducer().topic(topicName2)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();
        Producer<byte[]> producer3 = pulsarClient.newProducer().topic(topicName3)
            .messageRoutingMode(org.apache.pulsar.client.api.MessageRoutingMode.RoundRobinPartition)
            .create();

        // 2. Create consumer
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
            .topics(topicNames)
            .subscriptionName(subscriptionName)
            .subscriptionType(SubscriptionType.Shared)
            .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
            .receiverQueueSize(4)
            .subscribe();
        assertTrue(consumer instanceof TopicsConsumerImpl);

        // 3. producer publish messages
        for (int i = 0; i < totalMessages / 3; i++) {
            producer1.send((messagePredicate + "producer1-" + i).getBytes());
            producer2.send((messagePredicate + "producer2-" + i).getBytes());
            producer3.send((messagePredicate + "producer3-" + i).getBytes());
        }

        int messageSet = 0;
        Message<byte[]> message = consumer.receive();
        do {
            assertTrue(message instanceof TopicMessageImpl);
            messageSet ++;
            consumer.acknowledge(message);
            log.debug("Consumer acknowledged : " + new String(message.getData()));
            message = consumer.receive(500, TimeUnit.MILLISECONDS);
        } while (message != null);
        assertEquals(messageSet, totalMessages);

        // 4, unsubscribe topic3
        CompletableFuture<Void> unsubFuture = ((TopicsConsumerImpl<byte[]>) consumer).unsubscribeAsync(topicName3);
        unsubFuture.get();

        // 5. producer publish messages
        for (int i = 0; i < totalMessages / 3; i++) {
            producer1.send((messagePredicate + "producer1-round2" + i).getBytes());
            producer2.send((messagePredicate + "producer2-round2" + i).getBytes());
            producer3.send((messagePredicate + "producer3-round2" + i).getBytes());
        }

        // 6. should not receive messages from topic3, verify get 2/3 of all messages
        messageSet = 0;
        message = consumer.receive();
        do {
            assertTrue(message instanceof TopicMessageImpl);
            messageSet ++;
            consumer.acknowledge(message);
            log.debug("Consumer acknowledged : " + new String(message.getData()));
            message = consumer.receive(500, TimeUnit.MILLISECONDS);
        } while (message != null);
        assertEquals(messageSet, totalMessages * 2 / 3);

        // 7. use getter to verify internal topics number after un-subscribe topic3
        List<String> topics = ((TopicsConsumerImpl<byte[]>) consumer).getPartitionedTopics();
        List<ConsumerImpl<byte[]>> consumers = ((TopicsConsumerImpl) consumer).getConsumers();

        assertEquals(topics.size(), 3);
        assertEquals(consumers.size(), 3);
        assertTrue(((TopicsConsumerImpl<byte[]>) consumer).getTopics().size() == 2);

        // 8. re-subscribe topic3
        CompletableFuture<Void> subFuture = ((TopicsConsumerImpl<byte[]>)consumer).subscribeAsync(topicName3);
        subFuture.get();

        // 9. producer publish messages
        for (int i = 0; i < totalMessages / 3; i++) {
            producer1.send((messagePredicate + "producer1-round3" + i).getBytes());
            producer2.send((messagePredicate + "producer2-round3" + i).getBytes());
            producer3.send((messagePredicate + "producer3-round3" + i).getBytes());
        }

        // 10. should receive messages from all 3 topics
        messageSet = 0;
        message = consumer.receive();
        do {
            assertTrue(message instanceof TopicMessageImpl);
            messageSet ++;
            consumer.acknowledge(message);
            log.debug("Consumer acknowledged : " + new String(message.getData()));
            message = consumer.receive(500, TimeUnit.MILLISECONDS);
        } while (message != null);
        assertEquals(messageSet, totalMessages);

        // 11. use getter to verify internal topics number after subscribe topic3
        topics = ((TopicsConsumerImpl<byte[]>) consumer).getPartitionedTopics();
        consumers = ((TopicsConsumerImpl) consumer).getConsumers();

        assertEquals(topics.size(), 6);
        assertEquals(consumers.size(), 6);
        assertTrue(((TopicsConsumerImpl<byte[]>) consumer).getTopics().size() == 3);

        consumer.unsubscribe();
        consumer.close();
        producer1.close();
        producer2.close();
        producer3.close();
    }


    @Test(timeOut = testTimeout)
    public void testTopicsNameSubscribeWithBuilderFail() throws Exception {
        String key = "TopicsNameSubscribeWithBuilder";
        final String subscriptionName = "my-ex-subscription-" + key;

        final String topicName2 = "persistent://prop/use/ns-abc/topic-2-" + key;
        final String topicName3 = "persistent://prop/use/ns-abc/topic-3-" + key;

        admin.properties().createProperty("prop", new PropertyAdmin());
        admin.persistentTopics().createPartitionedTopic(topicName2, 2);
        admin.persistentTopics().createPartitionedTopic(topicName3, 3);

        // test failing builder with empty topics
        try {
            pulsarClient.newConsumer()
                .subscriptionName(subscriptionName)
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
                .subscribe();
            fail("subscribe1 with no topicName should fail.");
        } catch (PulsarClientException e) {
            // expected
        }

        try {
            pulsarClient.newConsumer()
                .topic()
                .subscriptionName(subscriptionName)
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
                .subscribe();
            fail("subscribe2 with no topicName should fail.");
        } catch (IllegalArgumentException e) {
            // expected
        }

        try {
            pulsarClient.newConsumer()
                .topics(null)
                .subscriptionName(subscriptionName)
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
                .subscribe();
            fail("subscribe3 with no topicName should fail.");
        } catch (IllegalArgumentException e) {
            // expected
        }

        try {
            pulsarClient.newConsumer()
                .topics(Lists.newArrayList())
                .subscriptionName(subscriptionName)
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(ackTimeOutMillis, TimeUnit.MILLISECONDS)
                .subscribe();
            fail("subscribe4 with no topicName should fail.");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

}
