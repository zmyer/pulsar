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
package org.apache.pulsar.proxy.server;

import static org.mockito.Mockito.doReturn;
import static org.testng.Assert.assertTrue;

import org.apache.bookkeeper.test.PortManager;
import org.apache.pulsar.broker.auth.MockedPulsarServiceBaseTest;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class ProxyLookupThrottlingTest extends MockedPulsarServiceBaseTest {

    private final String DUMMY_VALUE = "DUMMY_VALUE";
    private final int NUM_CONCURRENT_LOOKUP = 3;
    private final int NUM_CONCURRENT_INBOUND_CONNECTION = 5;
    private ProxyService proxyService;
    private ProxyConfiguration proxyConfig = new ProxyConfiguration();

    @Override
    @BeforeClass
    protected void setup() throws Exception {
        internalSetup();

        proxyConfig.setServicePort(PortManager.nextFreePort());
        proxyConfig.setZookeeperServers(DUMMY_VALUE);
        proxyConfig.setGlobalZookeeperServers(DUMMY_VALUE);
        proxyConfig.setMaxConcurrentLookupRequests(NUM_CONCURRENT_LOOKUP);
        proxyConfig.setMaxConcurrentInboundConnections(NUM_CONCURRENT_INBOUND_CONNECTION);
        proxyService = Mockito.spy(new ProxyService(proxyConfig));
        doReturn(mockZooKeeperClientFactory).when(proxyService).getZooKeeperClientFactory();

        proxyService.start();
    }

    @Override
    @AfterClass
    protected void cleanup() throws Exception {
        internalCleanup();
        proxyService.close();
    }

    @Test
    public void testLookup() throws Exception {
        PulsarClient client = PulsarClient.builder().serviceUrl("pulsar://localhost:" + proxyConfig.getServicePort())
                .connectionsPerBroker(5).ioThreads(5).build();
        assertTrue(proxyService.getLookupRequestSemaphore().tryAcquire());
        assertTrue(proxyService.getLookupRequestSemaphore().tryAcquire());
        Producer<byte[]> producer1 = client.newProducer().topic("persistent://sample/test/local/producer-topic")
                .create();
        assertTrue(proxyService.getLookupRequestSemaphore().tryAcquire());
        try {
            Producer<byte[]> producer2 = client.newProducer().topic("persistent://sample/test/local/producer-topic")
                    .create();
            Assert.fail("Should have failed since can't acquire LookupRequestSemaphore");
        } catch (Exception ex) {
            // Ignore
        }
        Assert.assertEquals(LookupProxyHandler.rejectedPartitionsMetadataRequests.get(), 1.0d);
        proxyService.getLookupRequestSemaphore().release();
        try {
            Producer<byte[]> producer3 = client.newProducer().topic("persistent://sample/test/local/producer-topic")
                    .create();
        } catch (Exception ex) {
            Assert.fail("Should not have failed since can acquire LookupRequestSemaphore");
        }
        Assert.assertEquals(LookupProxyHandler.rejectedPartitionsMetadataRequests.get(), 1.0d);
        client.close();
    }
}
