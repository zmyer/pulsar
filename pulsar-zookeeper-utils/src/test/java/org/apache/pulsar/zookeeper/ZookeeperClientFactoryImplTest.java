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
package org.apache.pulsar.zookeeper;

import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.bookkeeper.test.PortManager;
import org.apache.pulsar.zookeeper.ZooKeeperClientFactory;
import org.apache.pulsar.zookeeper.ZookeeperClientFactoryImpl;
import org.apache.pulsar.zookeeper.ZooKeeperClientFactory.SessionType;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test
public class ZookeeperClientFactoryImplTest {

    private ZookeeperServerTest localZkS;
    private ZooKeeper localZkc;
    private final int LOCAL_ZOOKEEPER_PORT = PortManager.nextFreePort();
    private final long ZOOKEEPER_SESSION_TIMEOUT_MILLIS = 5000;

    @BeforeMethod
    void setup() throws Exception {
        localZkS = new ZookeeperServerTest(LOCAL_ZOOKEEPER_PORT);
        localZkS.start();
    }

    @AfterMethod
    void teardown() throws Exception {
        localZkS.close();
    }

    @Test
    void testZKCreationRW() throws Exception {
        ZooKeeperClientFactory zkf = new ZookeeperClientFactoryImpl();
        CompletableFuture<ZooKeeper> zkFuture = zkf.create("127.0.0.1:" + LOCAL_ZOOKEEPER_PORT, SessionType.ReadWrite,
                (int) ZOOKEEPER_SESSION_TIMEOUT_MILLIS);
        localZkc = zkFuture.get(ZOOKEEPER_SESSION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        assertTrue(localZkc.getState().isConnected());
        assertNotEquals(localZkc.getState(), States.CONNECTEDREADONLY);
        localZkc.close();
    }

    @Test
    void testZKCreationRO() throws Exception {
        ZooKeeperClientFactory zkf = new ZookeeperClientFactoryImpl();
        CompletableFuture<ZooKeeper> zkFuture = zkf.create("127.0.0.1:" + LOCAL_ZOOKEEPER_PORT,
                SessionType.AllowReadOnly, (int) ZOOKEEPER_SESSION_TIMEOUT_MILLIS);
        localZkc = zkFuture.get(ZOOKEEPER_SESSION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        assertTrue(localZkc.getState().isConnected());
        localZkc.close();
    }

    @Test
    void testZKCreationFailure() throws Exception {
        ZooKeeperClientFactory zkf = new ZookeeperClientFactoryImpl();
        CompletableFuture<ZooKeeper> zkFuture = zkf.create("invalid", SessionType.ReadWrite,
                (int) ZOOKEEPER_SESSION_TIMEOUT_MILLIS);
        assertTrue(zkFuture.isCompletedExceptionally());
    }
}
