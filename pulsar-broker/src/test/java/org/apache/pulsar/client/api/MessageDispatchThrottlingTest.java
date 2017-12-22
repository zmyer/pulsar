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
package org.apache.pulsar.client.api;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.bookkeeper.mledger.impl.ManagedLedgerImpl;
import org.apache.pulsar.broker.service.BrokerService;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.DispatchRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;

public class MessageDispatchThrottlingTest extends ProducerConsumerBase {
    private static final Logger log = LoggerFactory.getLogger(MessageDispatchThrottlingTest.class);

    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        super.internalSetup();
        super.producerBaseSetup();
        this.conf.setClusterName("use");
    }

    @AfterMethod
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
        super.resetConfig();
    }

    @DataProvider(name = "subscriptions")
    public Object[][] subscriptionsProvider() {
        return new Object[][] { new Object[] { SubscriptionType.Shared }, { SubscriptionType.Exclusive } };
    }

    @DataProvider(name = "dispatchRateType")
    public Object[][] dispatchRateProvider() {
        return new Object[][] { { DispatchRateType.messageRate }, { DispatchRateType.byteRate } };
    }

    @DataProvider(name = "subscriptionAndDispatchRateType")
    public Object[][] subDisTypeProvider() {
        List<Object[]> mergeList = new LinkedList<Object[]>();
        for (Object[] sub : subscriptionsProvider()) {
            for (Object[] dispatch : dispatchRateProvider()) {
                mergeList.add(merge(sub, dispatch));
            }
        }
        return mergeList.toArray(new Object[0][0]);
    }

    public static <T> T[] merge(T[] first, T[] last) {
        int totalLength = first.length + last.length;
        T[] result = Arrays.copyOf(first, totalLength);
        int offset = first.length;
        System.arraycopy(last, 0, result, offset, first.length);
        return result;
    }

    enum DispatchRateType {
        messageRate, byteRate;
    }

    /**
     * verifies: message-rate change gets reflected immediately into topic at runtime
     * 
     * @throws Exception
     */
    @Test
    public void testMessageRateDynamicallyChange() throws Exception {

        log.info("-- Starting {} test --", methodName);

        final String namespace = "my-property/use/throttling_ns";
        final String topicName = "persistent://" + namespace + "/throttlingBlock";

        admin.namespaces().createNamespace(namespace);
        // create producer and topic
        Producer producer = pulsarClient.createProducer(topicName);
        PersistentTopic topic = (PersistentTopic) pulsar.getBrokerService().getTopic(topicName).get();
        // (1) verify message-rate is -1 initially
        Assert.assertEquals(topic.getDispatchRateLimiter().getDispatchRateOnMsg(), -1);

        // (1) change to 100
        int messageRate = 100;
        DispatchRate dispatchRate = new DispatchRate(messageRate, -1, 360);
        admin.namespaces().setDispatchRate(namespace, dispatchRate);
        boolean isDispatchRateUpdate = false;
        int retry = 5;
        for (int i = 0; i < retry; i++) {
            if (topic.getDispatchRateLimiter().getDispatchRateOnMsg() > 0) {
                isDispatchRateUpdate = true;
                break;
            } else {
                if (i != retry - 1) {
                    Thread.sleep(100);
                }
            }
        }
        Assert.assertTrue(isDispatchRateUpdate);
        Assert.assertEquals(admin.namespaces().getDispatchRate(namespace), dispatchRate);

        // (1) change to 500
        messageRate = 500;
        dispatchRate = new DispatchRate(-1, messageRate, 360);
        admin.namespaces().setDispatchRate(namespace, dispatchRate);
        isDispatchRateUpdate = false;
        for (int i = 0; i < retry; i++) {
            if (topic.getDispatchRateLimiter().getDispatchRateOnByte() == messageRate) {
                isDispatchRateUpdate = true;
                break;
            } else {
                if (i != retry - 1) {
                    Thread.sleep(100);
                }
            }
        }
        Assert.assertTrue(isDispatchRateUpdate);
        Assert.assertEquals(admin.namespaces().getDispatchRate(namespace), dispatchRate);

        producer.close();
    }

    /**
     * verify: consumer should not receive all messages due to message-rate throttling
     * 
     * @param subscription
     * @throws Exception
     */
    @Test(dataProvider = "subscriptionAndDispatchRateType", timeOut = 5000)
    public void testMessageRateLimitingNotReceiveAllMessages(SubscriptionType subscription,
            DispatchRateType dispatchRateType) throws Exception {
        log.info("-- Starting {} test --", methodName);

        final String namespace = "my-property/use/throttling_ns";
        final String topicName = "persistent://" + namespace + "/throttlingBlock";

        final int messageRate = 100;
        DispatchRate dispatchRate = null;
        if (DispatchRateType.messageRate.equals(dispatchRateType)) {
            dispatchRate = new DispatchRate(messageRate, -1, 360);
        } else {
            dispatchRate = new DispatchRate(-1, messageRate, 360);
        }

        admin.namespaces().createNamespace(namespace);
        admin.namespaces().setDispatchRate(namespace, dispatchRate);
        // create producer and topic
        Producer producer = pulsarClient.createProducer(topicName);
        PersistentTopic topic = (PersistentTopic) pulsar.getBrokerService().getTopic(topicName).get();
        boolean isMessageRateUpdate = false;
        int retry = 5;
        for (int i = 0; i < retry; i++) {
            if (topic.getDispatchRateLimiter().getDispatchRateOnMsg() > 0
                    || topic.getDispatchRateLimiter().getDispatchRateOnByte() > 0) {
                isMessageRateUpdate = true;
                break;
            } else {
                if (i != retry - 1) {
                    Thread.sleep(100);
                }
            }
        }
        Assert.assertTrue(isMessageRateUpdate);
        Assert.assertEquals(admin.namespaces().getDispatchRate(namespace), dispatchRate);

        int numMessages = 500;

        final AtomicInteger totalReceived = new AtomicInteger(0);

        ConsumerConfiguration conf = new ConsumerConfiguration();
        conf.setSubscriptionType(subscription);
        conf.setMessageListener((consumer, msg) -> {
            Assert.assertNotNull(msg, "Message cannot be null");
            String receivedMessage = new String(msg.getData());
            log.debug("Received message [{}] in the listener", receivedMessage);
            totalReceived.incrementAndGet();
        });
        Consumer consumer = pulsarClient.subscribe(topicName, "my-subscriber-name", conf);
        // deactive cursors
        deactiveCursors((ManagedLedgerImpl) topic.getManagedLedger());

        // Asynchronously produce messages
        for (int i = 0; i < numMessages; i++) {
            producer.send(new byte[80]);
        }

        // consumer should not have received all publihsed message due to message-rate throttling
        Assert.assertTrue(totalReceived.get() < messageRate * 2);

        consumer.close();
        producer.close();
        log.info("-- Exiting {} test --", methodName);
    }

    /**
     * It verifies that dispatch-rate throttling with cluster-configuration
     * 
     * @param subscription
     * @param dispatchRateType
     * @throws Exception
     */
    @Test()
    public void testClusterMsgByteRateLimitingClusterConfig() throws Exception {
        log.info("-- Starting {} test --", methodName);

        final String namespace = "my-property/use/throttling_ns";
        final String topicName = "persistent://" + namespace + "/throttlingBlock";
        final int messageRate = 5;
        final long byteRate = 1024 * 1024;// 1MB rate enough to let all msg to be delivered

        int initValue = pulsar.getConfiguration().getDispatchThrottlingRatePerTopicInMsg();
        // (1) Update message-dispatch-rate limit
        admin.brokers().updateDynamicConfiguration("dispatchThrottlingRatePerTopicInMsg", Integer.toString(messageRate));
        admin.brokers().updateDynamicConfiguration("dispatchThrottlingRatePerTopicInByte", Long.toString(byteRate));
        // sleep incrementally as zk-watch notification is async and may take some time
        for (int i = 0; i < 5; i++) {
            if (pulsar.getConfiguration().getDispatchThrottlingRatePerTopicInMsg() != initValue) {
                Thread.sleep(50 + (i * 10));
            }
        }
        Assert.assertNotEquals(pulsar.getConfiguration().getDispatchThrottlingRatePerTopicInMsg(), initValue);

        admin.namespaces().createNamespace(namespace);
        // create producer and topic
        Producer producer = pulsarClient.createProducer(topicName);
        PersistentTopic topic = (PersistentTopic) pulsar.getBrokerService().getTopic(topicName).get();
        int numMessages = 500;

        final AtomicInteger totalReceived = new AtomicInteger(0);

        ConsumerConfiguration conf = new ConsumerConfiguration();
        conf.setSubscriptionType(SubscriptionType.Shared);
        conf.setMessageListener((consumer, msg) -> {
            Assert.assertNotNull(msg, "Message cannot be null");
            String receivedMessage = new String(msg.getData());
            log.debug("Received message [{}] in the listener", receivedMessage);
            totalReceived.incrementAndGet();
        });
        Consumer consumer = pulsarClient.subscribe(topicName, "my-subscriber-name", conf);
        // deactive cursors
        deactiveCursors((ManagedLedgerImpl) topic.getManagedLedger());

        // Asynchronously produce messages
        for (int i = 0; i < numMessages; i++) {
            final String message = "my-message-" + i;
            producer.send(message.getBytes());
        }

        // it can make sure that consumer had enough time to consume message but couldn't consume due to throttling
        Thread.sleep(500);

        // consumer should not have received all published message due to message-rate throttling
        Assert.assertNotEquals(totalReceived.get(), numMessages);

        consumer.close();
        producer.close();
        pulsar.getConfiguration().setDispatchThrottlingRatePerTopicInMsg(initValue);
        log.info("-- Exiting {} test --", methodName);
    }

    /**
     * verify rate-limiting should throttle message-dispatching based on message-rate
     * 
     * <pre>
     *  1. dispatch-msg-rate = 10 msg/sec
     *  2. send 20 msgs 
     *  3. it should take up to 2 second to receive all messages
     * </pre>
     * 
     * @param subscription
     * @throws Exception
     */
    @Test(dataProvider = "subscriptions", timeOut = 5000)
    public void testMessageRateLimitingReceiveAllMessagesAfterThrottling(SubscriptionType subscription)
            throws Exception {
        log.info("-- Starting {} test --", methodName);

        final String namespace = "my-property/use/throttling_ns";
        final String topicName = "persistent://" + namespace + "/throttlingAll";

        final int messageRate = 10;
        DispatchRate dispatchRate = new DispatchRate(messageRate, -1, 1);
        admin.namespaces().createNamespace(namespace);
        admin.namespaces().setDispatchRate(namespace, dispatchRate);
        // create producer and topic
        Producer producer = pulsarClient.createProducer(topicName);
        PersistentTopic topic = (PersistentTopic) pulsar.getBrokerService().getTopic(topicName).get();
        boolean isMessageRateUpdate = false;
        int retry = 5;
        for (int i = 0; i < retry; i++) {
            if (topic.getDispatchRateLimiter().getDispatchRateOnMsg() > 0) {
                isMessageRateUpdate = true;
                break;
            } else {
                if (i != retry - 1) {
                    Thread.sleep(100);
                }
            }
        }
        Assert.assertTrue(isMessageRateUpdate);
        Assert.assertEquals(admin.namespaces().getDispatchRate(namespace), dispatchRate);

        final int numProducedMessages = 20;
        final CountDownLatch latch = new CountDownLatch(numProducedMessages);

        final AtomicInteger totalReceived = new AtomicInteger(0);

        ConsumerConfiguration conf = new ConsumerConfiguration();
        conf.setSubscriptionType(subscription);
        conf.setMessageListener((consumer, msg) -> {
            Assert.assertNotNull(msg, "Message cannot be null");
            String receivedMessage = new String(msg.getData());
            log.debug("Received message [{}] in the listener", receivedMessage);
            totalReceived.incrementAndGet();
            latch.countDown();
        });
        Consumer consumer = pulsarClient.subscribe(topicName, "my-subscriber-name", conf);
        // deactive cursors
        deactiveCursors((ManagedLedgerImpl) topic.getManagedLedger());

        // Asynchronously produce messages
        for (int i = 0; i < numProducedMessages; i++) {
            final String message = "my-message-" + i;
            producer.send(message.getBytes());
        }

        latch.await();
        Assert.assertEquals(totalReceived.get(), numProducedMessages);

        consumer.close();
        producer.close();
        log.info("-- Exiting {} test --", methodName);
    }

    /**
     * verify rate-limiting should throttle message-dispatching based on byte-rate
     * 
     * <pre>
     *  1. dispatch-byte-rate = 100 bytes/sec
     *  2. send 20 msgs : each with 10 byte
     *  3. it should take up to 2 second to receive all messages
     * </pre>
     * 
     * @param subscription
     * @throws Exception
     */
    @Test(dataProvider = "subscriptions", timeOut = 5000)
    public void testBytesRateLimitingReceiveAllMessagesAfterThrottling(SubscriptionType subscription) throws Exception {
        log.info("-- Starting {} test --", methodName);

        final String namespace = "my-property/use/throttling_ns";
        final String topicName = "persistent://" + namespace + "/throttlingAll";

        final int byteRate = 100;
        DispatchRate dispatchRate = new DispatchRate(-1, byteRate, 1);
        admin.namespaces().createNamespace(namespace);
        admin.namespaces().setDispatchRate(namespace, dispatchRate);
        // create producer and topic
        Producer producer = pulsarClient.createProducer(topicName);
        PersistentTopic topic = (PersistentTopic) pulsar.getBrokerService().getTopic(topicName).get();
        boolean isMessageRateUpdate = false;
        int retry = 5;
        for (int i = 0; i < retry; i++) {
            if (topic.getDispatchRateLimiter().getDispatchRateOnByte() > 0) {
                isMessageRateUpdate = true;
                break;
            } else {
                if (i != retry - 1) {
                    Thread.sleep(100);
                }
            }
        }
        Assert.assertTrue(isMessageRateUpdate);
        Assert.assertEquals(admin.namespaces().getDispatchRate(namespace), dispatchRate);

        final int numProducedMessages = 20;
        final CountDownLatch latch = new CountDownLatch(numProducedMessages);

        final AtomicInteger totalReceived = new AtomicInteger(0);

        ConsumerConfiguration conf = new ConsumerConfiguration();
        conf.setSubscriptionType(subscription);
        conf.setMessageListener((consumer, msg) -> {
            Assert.assertNotNull(msg, "Message cannot be null");
            String receivedMessage = new String(msg.getData());
            log.debug("Received message [{}] in the listener", receivedMessage);
            totalReceived.incrementAndGet();
            latch.countDown();
        });
        Consumer consumer = pulsarClient.subscribe(topicName, "my-subscriber-name-" + subscription, conf);
        // deactive cursors
        deactiveCursors((ManagedLedgerImpl) topic.getManagedLedger());

        // Asynchronously produce messages
        for (int i = 0; i < numProducedMessages; i++) {
            producer.send(new byte[byteRate / 10]);
        }

        latch.await();
        Assert.assertEquals(totalReceived.get(), numProducedMessages);

        consumer.close();
        producer.close();
        log.info("-- Exiting {} test --", methodName);
    }

    /**
     * verify message-rate on multiple consumers with shared-subscription
     * 
     * @throws Exception
     */
    @Test(timeOut = 5000)
    public void testRateLimitingMultipleConsumers() throws Exception {
        log.info("-- Starting {} test --", methodName);

        final String namespace = "my-property/use/throttling_ns";
        final String topicName = "persistent://" + namespace + "/throttlingMultipleConsumers";

        final int messageRate = 5;
        DispatchRate dispatchRate = new DispatchRate(messageRate, -1, 360);
        admin.namespaces().createNamespace(namespace);
        admin.namespaces().setDispatchRate(namespace, dispatchRate);
        // create producer and topic
        Producer producer = pulsarClient.createProducer(topicName);
        PersistentTopic topic = (PersistentTopic) pulsar.getBrokerService().getTopic(topicName).get();
        boolean isMessageRateUpdate = false;
        int retry = 5;
        for (int i = 0; i < retry; i++) {
            if (topic.getDispatchRateLimiter().getDispatchRateOnMsg() > 0) {
                isMessageRateUpdate = true;
                break;
            } else {
                if (i != retry - 1) {
                    Thread.sleep(100);
                }
            }
        }
        Assert.assertTrue(isMessageRateUpdate);
        Assert.assertEquals(admin.namespaces().getDispatchRate(namespace), dispatchRate);

        final int numProducedMessages = 500;

        final AtomicInteger totalReceived = new AtomicInteger(0);

        ConsumerConfiguration conf = new ConsumerConfiguration();
        conf.setSubscriptionType(SubscriptionType.Shared);
        conf.setMessageListener((consumer, msg) -> {
            Assert.assertNotNull(msg, "Message cannot be null");
            String receivedMessage = new String(msg.getData());
            log.debug("Received message [{}] in the listener", receivedMessage);
            totalReceived.incrementAndGet();
        });
        Consumer consumer1 = pulsarClient.subscribe(topicName, "my-subscriber-name", conf);
        Consumer consumer2 = pulsarClient.subscribe(topicName, "my-subscriber-name", conf);
        Consumer consumer3 = pulsarClient.subscribe(topicName, "my-subscriber-name", conf);
        Consumer consumer4 = pulsarClient.subscribe(topicName, "my-subscriber-name", conf);
        Consumer consumer5 = pulsarClient.subscribe(topicName, "my-subscriber-name", conf);
        // deactive cursors
        deactiveCursors((ManagedLedgerImpl) topic.getManagedLedger());

        // Asynchronously produce messages
        for (int i = 0; i < numProducedMessages; i++) {
            final String message = "my-message-" + i;
            producer.send(message.getBytes());
        }

        // it can make sure that consumer had enough time to consume message but couldn't consume due to throttling
        Thread.sleep(500);

        // consumer should not have received all published message due to message-rate throttling
        Assert.assertNotEquals(totalReceived.get(), numProducedMessages);

        consumer1.close();
        consumer2.close();
        consumer3.close();
        consumer4.close();
        consumer5.close();
        producer.close();
        log.info("-- Exiting {} test --", methodName);
    }

    @Test(dataProvider = "subscriptions", timeOut = 5000)
    public void testClusterRateLimitingConfiguration(SubscriptionType subscription) throws Exception {
        log.info("-- Starting {} test --", methodName);

        final String namespace = "my-property/use/throttling_ns";
        final String topicName = "persistent://" + namespace + "/throttlingBlock";
        final int messageRate = 5;

        int initValue = pulsar.getConfiguration().getDispatchThrottlingRatePerTopicInMsg();
        // (1) Update message-dispatch-rate limit
        admin.brokers().updateDynamicConfiguration("dispatchThrottlingRatePerTopicInMsg", Integer.toString(messageRate));
        // sleep incrementally as zk-watch notification is async and may take some time
        for (int i = 0; i < 5; i++) {
            if (pulsar.getConfiguration().getDispatchThrottlingRatePerTopicInMsg() != initValue) {
                Thread.sleep(50 + (i * 10));
            }
        }
        Assert.assertNotEquals(pulsar.getConfiguration().getDispatchThrottlingRatePerTopicInMsg(), initValue);

        admin.namespaces().createNamespace(namespace);
        // create producer and topic
        Producer producer = pulsarClient.createProducer(topicName);
        PersistentTopic topic = (PersistentTopic) pulsar.getBrokerService().getTopic(topicName).get();
        int numMessages = 500;

        final AtomicInteger totalReceived = new AtomicInteger(0);

        ConsumerConfiguration conf = new ConsumerConfiguration();
        conf.setSubscriptionType(subscription);
        conf.setMessageListener((consumer, msg) -> {
            Assert.assertNotNull(msg, "Message cannot be null");
            String receivedMessage = new String(msg.getData());
            log.debug("Received message [{}] in the listener", receivedMessage);
            totalReceived.incrementAndGet();
        });
        Consumer consumer = pulsarClient.subscribe(topicName, "my-subscriber-name", conf);
        // deactive cursors
        deactiveCursors((ManagedLedgerImpl) topic.getManagedLedger());

        // Asynchronously produce messages
        for (int i = 0; i < numMessages; i++) {
            final String message = "my-message-" + i;
            producer.send(message.getBytes());
        }

        // it can make sure that consumer had enough time to consume message but couldn't consume due to throttling
        Thread.sleep(500);

        // consumer should not have received all published message due to message-rate throttling
        Assert.assertNotEquals(totalReceived.get(), numMessages);

        consumer.close();
        producer.close();
        pulsar.getConfiguration().setDispatchThrottlingRatePerTopicInMsg(initValue);
        log.info("-- Exiting {} test --", methodName);
    }

    /**
     * It verifies that that dispatch-throttling considers both msg/byte rate if both of them are configured together
     * 
     * @param subscription
     * @throws Exception
     */
    @Test(dataProvider = "subscriptions", timeOut = 5000)
    public void testMessageByteRateThrottlingCombined(SubscriptionType subscription) throws Exception {
        log.info("-- Starting {} test --", methodName);

        final String namespace = "my-property/use/throttling_ns";
        final String topicName = "persistent://" + namespace + "/throttlingAll";

        final int messageRate = 5; // 5 msgs per second
        final long byteRate = 10; // 10 bytes per second
        DispatchRate dispatchRate = new DispatchRate(messageRate, byteRate, 360);
        admin.namespaces().createNamespace(namespace);
        admin.namespaces().setDispatchRate(namespace, dispatchRate);
        // create producer and topic
        Producer producer = pulsarClient.createProducer(topicName);
        PersistentTopic topic = (PersistentTopic) pulsar.getBrokerService().getTopic(topicName).get();
        boolean isMessageRateUpdate = false;
        int retry = 5;
        for (int i = 0; i < retry; i++) {
            if (topic.getDispatchRateLimiter().getDispatchRateOnMsg() > 0
                    && topic.getDispatchRateLimiter().getDispatchRateOnByte() > 0) {
                isMessageRateUpdate = true;
                break;
            } else {
                if (i != retry - 1) {
                    Thread.sleep(100);
                }
            }
        }
        Assert.assertTrue(isMessageRateUpdate);
        Assert.assertEquals(admin.namespaces().getDispatchRate(namespace), dispatchRate);

        final int numProducedMessages = 200;

        final AtomicInteger totalReceived = new AtomicInteger(0);

        ConsumerConfiguration conf = new ConsumerConfiguration();
        conf.setSubscriptionType(subscription);
        conf.setMessageListener((consumer, msg) -> {
            Assert.assertNotNull(msg, "Message cannot be null");
            String receivedMessage = new String(msg.getData());
            log.debug("Received message [{}] in the listener", receivedMessage);
            totalReceived.incrementAndGet();
        });
        Consumer consumer = pulsarClient.subscribe(topicName, "my-subscriber-name", conf);
        // deactive cursors
        deactiveCursors((ManagedLedgerImpl) topic.getManagedLedger());
        consumer.close();

        // Asynchronously produce messages
        final int dataSize = 50;
        final byte[] data = new byte[dataSize];
        for (int i = 0; i < numProducedMessages; i++) {
            producer.send(data);
        }

        consumer = pulsarClient.subscribe(topicName, "my-subscriber-name", conf);
        final int totalReceivedBytes = dataSize * totalReceived.get();
        Assert.assertNotEquals(totalReceivedBytes, byteRate * 2);

        consumer.close();
        producer.close();
        log.info("-- Exiting {} test --", methodName);
    }

    /**
     * <pre>
     * Verifies setting dispatch-rate on global namespace.
     * 1. It sets dispatch-rate for a local cluster into global-zk.policies
     * 2. Topic fetches dispatch-rate for the local cluster from policies
     * 3. applies dispatch rate
     * 
     * </pre>
     * @throws Exception
     */
    @Test
    public void testGlobalNamespaceThrottling() throws Exception {
        log.info("-- Starting {} test --", methodName);

        final String namespace = "my-property/global/throttling_ns";
        final String topicName = "persistent://" + namespace + "/throttlingBlock";
        

        final int messageRate = 5;
        DispatchRate dispatchRate = new DispatchRate(messageRate, -1, 360);

        admin.clusters().createCluster("global", new ClusterData("http://global:8080"));
        admin.namespaces().createNamespace(namespace);
        admin.namespaces().setNamespaceReplicationClusters(namespace, Lists.newArrayList("use"));
        admin.namespaces().setDispatchRate(namespace, dispatchRate);
        
        // create producer and topic
        Producer producer = pulsarClient.createProducer(topicName);
        PersistentTopic topic = (PersistentTopic) pulsar.getBrokerService().getTopic(topicName).get();
        boolean isMessageRateUpdate = false;
        int retry = 5;
        for (int i = 0; i < retry; i++) {
            if (topic.getDispatchRateLimiter().getDispatchRateOnMsg() > 0
                    || topic.getDispatchRateLimiter().getDispatchRateOnByte() > 0) {
                isMessageRateUpdate = true;
                break;
            } else {
                if (i != retry - 1) {
                    Thread.sleep(100);
                }
            }
        }
        Assert.assertTrue(isMessageRateUpdate);
        Assert.assertEquals(admin.namespaces().getDispatchRate(namespace), dispatchRate);

        int numMessages = 500;

        final AtomicInteger totalReceived = new AtomicInteger(0);

        ConsumerConfiguration conf = new ConsumerConfiguration();
        conf.setSubscriptionType(SubscriptionType.Shared);
        conf.setMessageListener((consumer, msg) -> {
            Assert.assertNotNull(msg, "Message cannot be null");
            String receivedMessage = new String(msg.getData());
            log.debug("Received message [{}] in the listener", receivedMessage);
            totalReceived.incrementAndGet();
        });
        Consumer consumer = pulsarClient.subscribe(topicName, "my-subscriber-name", conf);
        // deactive cursors
        deactiveCursors((ManagedLedgerImpl) topic.getManagedLedger());

        // Asynchronously produce messages
        for (int i = 0; i < numMessages; i++) {
            producer.send(new byte[80]);
        }

        // it can make sure that consumer had enough time to consume message but couldn't consume due to throttling
        Thread.sleep(500);

        // consumer should not have received all published message due to message-rate throttling
        Assert.assertNotEquals(totalReceived.get(), numMessages);

        consumer.close();
        producer.close();
        log.info("-- Exiting {} test --", methodName);
    }

    /**
     * It verifies that broker throttles already caught-up consumer which doesn't have backlog if the flag is enabled
     * 
     * @param subscription
     * @throws Exception
     */
    @Test(dataProvider = "subscriptions", timeOut = 5000)
    public void testNonBacklogConsumerWithThrottlingEnabled(SubscriptionType subscription) throws Exception {
        log.info("-- Starting {} test --", methodName);

        final String namespace = "my-property/use/throttling_ns";
        final String topicName = "persistent://" + namespace + "/throttlingBlock";

        final int messageRate = 10;
        DispatchRate dispatchRate = new DispatchRate(messageRate, -1, 360);

        admin.namespaces().createNamespace(namespace);
        admin.namespaces().setDispatchRate(namespace, dispatchRate);
        admin.brokers().updateDynamicConfiguration("dispatchThrottlingOnNonBacklogConsumerEnabled",
                Boolean.TRUE.toString());
        // create producer and topic
        Producer producer = pulsarClient.createProducer(topicName);
        PersistentTopic topic = (PersistentTopic) pulsar.getBrokerService().getTopic(topicName).get();
        boolean isUpdated = false;
        int retry = 5;
        for (int i = 0; i < retry; i++) {
            if (topic.getDispatchRateLimiter().getDispatchRateOnMsg() > 0) {
                isUpdated = true;
                break;
            } else {
                if (i != retry - 1) {
                    Thread.sleep(100);
                }
            }
        }
        Assert.assertTrue(isUpdated);
        Assert.assertEquals(admin.namespaces().getDispatchRate(namespace), dispatchRate);

        // enable throttling for nonBacklog consumers
        conf.setDispatchThrottlingOnNonBacklogConsumerEnabled(true);

        int numMessages = 500;

        final AtomicInteger totalReceived = new AtomicInteger(0);

        ConsumerConfiguration consumerConf = new ConsumerConfiguration();
        consumerConf.setSubscriptionType(subscription);
        consumerConf.setMessageListener((consumer, msg) -> {
            Assert.assertNotNull(msg, "Message cannot be null");
            String receivedMessage = new String(msg.getData());
            log.debug("Received message [{}] in the listener", receivedMessage);
            totalReceived.incrementAndGet();
        });
        Consumer consumer = pulsarClient.subscribe(topicName, "my-subscriber-name", consumerConf);

        // Asynchronously produce messages
        for (int i = 0; i < numMessages; i++) {
            producer.send(new byte[80]);
        }

        // consumer should not have received all publihsed message due to message-rate throttling
        Assert.assertTrue(totalReceived.get() < messageRate * 2);

        consumer.close();
        producer.close();
        // revert default value
        this.conf.setDispatchThrottlingOnNonBacklogConsumerEnabled(false);
        log.info("-- Exiting {} test --", methodName);
    }

     /**   
     * <pre>
     * It verifies that cluster-throttling value gets considered when namespace-policy throttling is disabled.
     * 
     *  1. Update cluster-throttling-config: topic rate-limiter has cluster-config
     *  2. Update namespace-throttling-config: topic rate-limiter has namespace-config
     *  3. Disable namespace-throttling-config: topic rate-limiter has cluster-config
     *  4. Create new topic with disable namespace-config and enabled cluster-config: it takes cluster-config
     * 
     * </pre>
     * 
     * @throws Exception
     */
    @Test
    public void testClusterPolicyOverrideConfiguration() throws Exception {
        log.info("-- Starting {} test --", methodName);

        final String namespace = "my-property/use/throttling_ns";
        final String topicName1 = "persistent://" + namespace + "/throttlingOverride1";
        final String topicName2 = "persistent://" + namespace + "/throttlingOverride2";
        final int clusterMessageRate = 100;

        int initValue = pulsar.getConfiguration().getDispatchThrottlingRatePerTopicInMsg();
        // (1) Update message-dispatch-rate limit
        admin.brokers().updateDynamicConfiguration("dispatchThrottlingRatePerTopicInMsg",
                Integer.toString(clusterMessageRate));
        // sleep incrementally as zk-watch notification is async and may take some time
        for (int i = 0; i < 5; i++) {
            if (pulsar.getConfiguration().getDispatchThrottlingRatePerTopicInMsg() != initValue) {
                Thread.sleep(50 + (i * 10));
            }
        }
        Assert.assertNotEquals(pulsar.getConfiguration().getDispatchThrottlingRatePerTopicInMsg(), initValue);

        admin.namespaces().createNamespace(namespace);
        // create producer and topic
        Producer producer = pulsarClient.createProducer(topicName1);
        PersistentTopic topic = (PersistentTopic) pulsar.getBrokerService().getTopic(topicName1).get();

        // (1) Update dispatch rate on cluster-config update
        Assert.assertEquals(clusterMessageRate, topic.getDispatchRateLimiter().getDispatchRateOnMsg());

        // (2) Update namespace throttling limit
        int nsMessageRate = 500;
        DispatchRate dispatchRate = new DispatchRate(nsMessageRate, 0, 1);
        admin.namespaces().setDispatchRate(namespace, dispatchRate);
        for (int i = 0; i < 5; i++) {
            if (topic.getDispatchRateLimiter().getDispatchRateOnMsg() != nsMessageRate) {
                Thread.sleep(50 + (i * 10));
            }
        }
        Assert.assertEquals(nsMessageRate, topic.getDispatchRateLimiter().getDispatchRateOnMsg());

        // (3) Disable namespace throttling limit will force to take cluster-config
        dispatchRate = new DispatchRate(0, 0, 1);
        admin.namespaces().setDispatchRate(namespace, dispatchRate);
        for (int i = 0; i < 5; i++) {
            if (topic.getDispatchRateLimiter().getDispatchRateOnMsg() == nsMessageRate) {
                Thread.sleep(50 + (i * 10));
            }
        }
        Assert.assertEquals(clusterMessageRate, topic.getDispatchRateLimiter().getDispatchRateOnMsg());

        // (5) Namespace throttling is disabled so, new topic should take cluster throttling limit
        Producer producer2 = pulsarClient.createProducer(topicName2);
        PersistentTopic topic2 = (PersistentTopic) pulsar.getBrokerService().getTopic(topicName2).get();
        Assert.assertEquals(clusterMessageRate, topic2.getDispatchRateLimiter().getDispatchRateOnMsg());

        producer.close();
        producer2.close();

        log.info("-- Exiting {} test --", methodName);
    }
    
    private void deactiveCursors(ManagedLedgerImpl ledger) throws Exception {
        Field statsUpdaterField = BrokerService.class.getDeclaredField("statsUpdater");
        statsUpdaterField.setAccessible(true);
        ScheduledExecutorService statsUpdater = (ScheduledExecutorService) statsUpdaterField
                .get(pulsar.getBrokerService());
        statsUpdater.shutdownNow();
        ledger.getCursors().forEach(cursor -> {
            ledger.deactivateCursor(cursor);
        });
    }

}