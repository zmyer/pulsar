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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.apache.pulsar.broker.service.persistent.PersistentSubscription;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 */
@Test
public class SubscriptionSeekTest extends BrokerTestBase {
    @BeforeClass
    @Override
    protected void setup() throws Exception {
        super.baseSetup();
    }

    @AfterClass
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    @Test
    public void testSeek() throws Exception {
        final String topicName = "persistent://prop/use/ns-abc/testSeek";

        Producer<byte[]> producer = pulsarClient.newProducer().topic(topicName).create();

        // Disable pre-fetch in consumer to track the messages received
        org.apache.pulsar.client.api.Consumer<byte[]> consumer = pulsarClient.newConsumer().topic(topicName)
                .subscriptionName("my-subscription").receiverQueueSize(0).subscribe();

        PersistentTopic topicRef = (PersistentTopic) pulsar.getBrokerService().getTopicReference(topicName);
        assertNotNull(topicRef);
        assertEquals(topicRef.getProducers().size(), 1);
        assertEquals(topicRef.getSubscriptions().size(), 1);

        List<MessageId> messageIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String message = "my-message-" + i;
            MessageId msgId = producer.send(message.getBytes());
            messageIds.add(msgId);
        }

        PersistentSubscription sub = topicRef.getSubscription("my-subscription");
        assertEquals(sub.getNumberOfEntriesInBacklog(), 10);

        consumer.seek(MessageId.latest);
        assertEquals(sub.getNumberOfEntriesInBacklog(), 0);

        // Wait for consumer to reconnect
        Thread.sleep(500);
        consumer.seek(MessageId.earliest);
        assertEquals(sub.getNumberOfEntriesInBacklog(), 10);

        Thread.sleep(500);
        consumer.seek(messageIds.get(5));
        assertEquals(sub.getNumberOfEntriesInBacklog(), 5);
    }

    @Test
    public void testSeekOnPartitionedTopic() throws Exception {
        final String topicName = "persistent://prop/use/ns-abc/testSeekPartitions";

        admin.persistentTopics().createPartitionedTopic(topicName, 2);
        org.apache.pulsar.client.api.Consumer<byte[]> consumer = pulsarClient.newConsumer().topic(topicName)
                .subscriptionName("my-subscription").subscribe();

        try {
            consumer.seek(MessageId.latest);
            fail("Should not have succeeded");
        } catch (PulsarClientException e) {
            // Expected
        }
    }

}
