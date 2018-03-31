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
package org.apache.pulsar.broker.admin;

import static org.testng.Assert.assertEquals;

import org.apache.pulsar.broker.admin.AdminApiTest.MockedPulsarService;
import org.apache.pulsar.broker.auth.MockedPulsarServiceBaseTest;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.PropertyAdmin;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class IncrementPartitionsTest extends MockedPulsarServiceBaseTest {

    private MockedPulsarService mockPulsarSetup;

    @BeforeMethod
    @Override
    public void setup() throws Exception {
        conf.setLoadBalancerEnabled(true);
        super.internalSetup();

        // create otherbroker to test redirect on calls that need
        // namespace ownership
        mockPulsarSetup = new MockedPulsarService(this.conf);
        mockPulsarSetup.setup();

        // Setup namespaces
        admin.clusters().createCluster("use", new ClusterData("http://127.0.0.1" + ":" + BROKER_WEBSERVICE_PORT));
        PropertyAdmin propertyAdmin = new PropertyAdmin(Lists.newArrayList("role1", "role2"), Sets.newHashSet("use"));
        admin.properties().createProperty("prop-xyz", propertyAdmin);
        admin.namespaces().createNamespace("prop-xyz/use/ns1");
    }

    @AfterMethod
    @Override
    public void cleanup() throws Exception {
        super.internalCleanup();
        mockPulsarSetup.cleanup();
    }

    @Test
    public void testIncrementPartitionsOfTopicOnUnusedTopic() throws Exception {
        final String partitionedTopicName = "persistent://prop-xyz/use/ns1/test-topic";

        admin.persistentTopics().createPartitionedTopic(partitionedTopicName, 10);
        assertEquals(admin.persistentTopics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 10);

        admin.persistentTopics().updatePartitionedTopic(partitionedTopicName, 20);
        assertEquals(admin.persistentTopics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 20);
    }

    @Test
    public void testIncrementPartitionsOfTopic() throws Exception {
        final String partitionedTopicName = "persistent://prop-xyz/use/ns1/test-topic-2";

        admin.persistentTopics().createPartitionedTopic(partitionedTopicName, 10);
        assertEquals(admin.persistentTopics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 10);

        Consumer<byte[]> consumer = pulsarClient.newConsumer().topic(partitionedTopicName).subscriptionName("sub-1")
                .subscribe();

        admin.persistentTopics().updatePartitionedTopic(partitionedTopicName, 20);
        assertEquals(admin.persistentTopics().getPartitionedTopicMetadata(partitionedTopicName).partitions, 20);

        assertEquals(admin.persistentTopics().getSubscriptions(
                TopicName.get(partitionedTopicName).getPartition(15).toString()), Lists.newArrayList("sub-1"));

        consumer.close();
    }
}
