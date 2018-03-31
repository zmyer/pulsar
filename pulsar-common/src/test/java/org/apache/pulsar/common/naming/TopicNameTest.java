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
package org.apache.pulsar.common.naming;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.fail;

import org.apache.pulsar.common.naming.TopicDomain;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.util.Codec;
import org.testng.annotations.Test;

@Test
public class TopicNameTest {

    @Test
    void topic() {
        try {
            assertEquals(TopicName.get("property.namespace:topic").getNamespace(), "property.namespace");
            fail("Should have thrown exception");
        } catch (IllegalArgumentException e) {
            // Expected
        }

        assertEquals(TopicName.get("persistent://property/cluster/namespace/topic").getNamespace(),
                "property/cluster/namespace");
        assertEquals(TopicName.get("persistent://property/cluster/namespace/topic").getNamespace(),
                "property/cluster/namespace");

        assertEquals(TopicName.get("persistent://property/cluster/namespace/topic"),
                TopicName.get("persistent", "property", "cluster", "namespace", "topic"));

        assertEquals(TopicName.get("persistent://property/cluster/namespace/topic").hashCode(),
                TopicName.get("persistent", "property", "cluster", "namespace", "topic").hashCode());

        assertEquals(TopicName.get("persistent://property/cluster/namespace/topic").toString(),
                "persistent://property/cluster/namespace/topic");

        assertFalse(TopicName.get("persistent://property/cluster/namespace/topic")
                .equals("persistent://property/cluster/namespace/topic"));

        assertEquals(TopicName.get("persistent://property/cluster/namespace/topic").getDomain(),
                TopicDomain.persistent);
        assertEquals(TopicName.get("persistent://property/cluster/namespace/topic").getProperty(),
                "property");
        assertEquals(TopicName.get("persistent://property/cluster/namespace/topic").getCluster(),
                "cluster");
        assertEquals(TopicName.get("persistent://property/cluster/namespace/topic").getNamespacePortion(),
                "namespace");
        assertEquals(TopicName.get("persistent://property/cluster/namespace/topic").getNamespace(),
                "property/cluster/namespace");
        assertEquals(TopicName.get("persistent://property/cluster/namespace/topic").getLocalName(),
                "topic");

        try {
            TopicName.get("property.namespace:my-topic").getDomain();
            fail("Should have raised exception");
        } catch (IllegalArgumentException e) {
            // Ok
        }

        try {
            TopicName.get("property.namespace:my-topic").getProperty();
            fail("Should have raised exception");
        } catch (IllegalArgumentException e) {
            // Ok
        }

        try {
            TopicName.get("property.namespace:my-topic").getCluster();
            fail("Should have raised exception");
        } catch (IllegalArgumentException e) {
            // Ok
        }

        try {
            TopicName.get("property.namespace:my-topic").getNamespacePortion();
            fail("Should have raised exception");
        } catch (IllegalArgumentException e) {
            // Ok
        }

        try {
            TopicName.get("property.namespace:my-topic").getLocalName();
            fail("Should have raised exception");
        } catch (IllegalArgumentException e) {
            // Ok
        }

        try {
            TopicName.get("property.namespace");
            fail("Should have raised exception");
        } catch (IllegalArgumentException e) {
            // Ok
        }

        try {
            TopicName.get("invalid://property/cluster/namespace/topic");
            fail("Should have raied exception");
        } catch (IllegalArgumentException e) {
            // Ok
        }

        try {
            TopicName.get("property/cluster/namespace/topic");
            fail("Should have raised exception");
        } catch (IllegalArgumentException e) {
            // Ok
        }

        try {
            TopicName.get("persistent:///cluster/namespace/mydest-1");
            fail("Should have raised exception");
        } catch (IllegalArgumentException e) {
            // Ok
        }

        try {
            TopicName.get("persistent://pulsar//namespace/mydest-1");
            fail("Should have raised exception");
        } catch (IllegalArgumentException e) {
            // Ok
        }

        try {
            TopicName.get("persistent://pulsar/cluster//mydest-1");
            fail("Should have raised exception");
        } catch (IllegalArgumentException e) {
            // Ok
        }

        try {
            TopicName.get("persistent://pulsar/cluster/namespace/");
            fail("Should have raised exception");
        } catch (IllegalArgumentException e) {
            // Ok
        }

        try {
            TopicName.get("://pulsar/cluster/namespace/");
            fail("Should have raised exception");
        } catch (IllegalArgumentException e) {
            // Ok
        }

        assertEquals(TopicName.get("persistent://property/cluster/namespace/topic")
                .getPersistenceNamingEncoding(), "property/cluster/namespace/persistent/topic");

        try {
            TopicName.get("property.namespace");
            fail("Should have raied exception");
        } catch (IllegalArgumentException e) {
            // Ok
        }

        try {
            TopicName.get("property/cluster/namespace");
            fail("Should have raied exception");
        } catch (IllegalArgumentException e) {
            // Ok
        }

        TopicName nameWithSlash = TopicName.get("persistent://property/cluster/namespace/ns-abc/table/1");
        assertEquals(nameWithSlash.getEncodedLocalName(), Codec.encode("ns-abc/table/1"));

        TopicName nameEndingInSlash = TopicName
                .get("persistent://property/cluster/namespace/ns-abc/table/1/");
        assertEquals(nameEndingInSlash.getEncodedLocalName(), Codec.encode("ns-abc/table/1/"));

        TopicName nameWithTwoSlashes = TopicName
                .get("persistent://property/cluster/namespace//ns-abc//table//1//");
        assertEquals(nameWithTwoSlashes.getEncodedLocalName(), Codec.encode("/ns-abc//table//1//"));

        TopicName nameWithRandomCharacters = TopicName
                .get("persistent://property/cluster/namespace/$#3rpa/table/1");
        assertEquals(nameWithRandomCharacters.getEncodedLocalName(), Codec.encode("$#3rpa/table/1"));

        TopicName topicName = TopicName.get("persistent://myprop/mycolo/myns/mytopic");
        assertEquals(topicName.getPartition(0).toString(), "persistent://myprop/mycolo/myns/mytopic-partition-0");

        TopicName partitionedDn = TopicName.get("persistent://myprop/mycolo/myns/mytopic").getPartition(2);
        assertEquals(partitionedDn.getPartitionIndex(), 2);
        assertEquals(topicName.getPartitionIndex(), -1);

        assertEquals(TopicName.getPartitionIndex("persistent://myprop/mycolo/myns/mytopic-partition-4"), 4);
    }

    @Test
    public void testDecodeEncode() throws Exception {
        String encodedName = "a%3Aen-in_in_business_content_item_20150312173022_https%5C%3A%2F%2Fin.news.example.com%2Fr";
        String rawName = "a:en-in_in_business_content_item_20150312173022_https\\://in.news.example.com/r";
        assertEquals(Codec.decode(encodedName), rawName);
        assertEquals(Codec.encode(rawName), encodedName);

        String topicName = "persistent://prop/colo/ns/" + rawName;
        TopicName name = TopicName.get(topicName);

        assertEquals(name.getLocalName(), rawName);
        assertEquals(name.getEncodedLocalName(), encodedName);
        assertEquals(name.getPersistenceNamingEncoding(), "prop/colo/ns/persistent/" + encodedName);
    }

    @Test
    public void testTopicNameWithoutCluster() throws Exception {
        TopicName topicName = TopicName.get("persistent://property/namespace/topic");

        assertEquals(topicName.getNamespace(), "property/namespace");

        assertEquals(topicName, TopicName.get("persistent", "property", "namespace", "topic"));

        assertEquals(topicName.hashCode(),
                TopicName.get("persistent", "property", "namespace", "topic").hashCode());

        assertEquals(topicName.toString(), "persistent://property/namespace/topic");
        assertEquals(topicName.getDomain(), TopicDomain.persistent);
        assertEquals(topicName.getProperty(), "property");
        assertEquals(topicName.getCluster(), null);
        assertEquals(topicName.getNamespacePortion(), "namespace");
        assertEquals(topicName.getNamespace(), "property/namespace");
        assertEquals(topicName.getLocalName(), "topic");

        assertEquals(topicName.getEncodedLocalName(), "topic");
        assertEquals(topicName.getPartitionedTopicName(), "persistent://property/namespace/topic");
        assertEquals(topicName.getPersistenceNamingEncoding(), "property/namespace/persistent/topic");
    }
}
