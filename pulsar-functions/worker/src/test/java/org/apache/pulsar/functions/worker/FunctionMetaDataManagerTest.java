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
package org.apache.pulsar.functions.worker;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.functions.proto.Function;
import org.apache.pulsar.functions.proto.Request;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

@Slf4j
public class FunctionMetaDataManagerTest {

    @Test
    public void testListFunctions() throws PulsarClientException {
        FunctionMetaDataManager functionMetaDataManager = spy(
                new FunctionMetaDataManager(new WorkerConfig(),
                        mock(SchedulerManager.class),
                        mock(PulsarClient.class)));

        Map<String, Function.FunctionMetaData> functionMetaDataMap1 = new HashMap<>();
        functionMetaDataMap1.put("func-1", Function.FunctionMetaData.newBuilder().setFunctionConfig(
                        Function.FunctionConfig.newBuilder().setName("func-1")).build());
        functionMetaDataMap1.put("func-2",
                Function.FunctionMetaData.newBuilder().setFunctionConfig(
                        Function.FunctionConfig.newBuilder().setName("func-2")).build());
        Map<String, Function.FunctionMetaData> functionMetaDataInfoMap2 = new HashMap<>();
        functionMetaDataInfoMap2.put("func-3",
                Function.FunctionMetaData.newBuilder().setFunctionConfig(
                        Function.FunctionConfig.newBuilder().setName("func-3")).build());


        functionMetaDataManager.functionMetaDataMap.put("tenant-1", new HashMap<>());
        functionMetaDataManager.functionMetaDataMap.get("tenant-1").put("namespace-1", functionMetaDataMap1);
        functionMetaDataManager.functionMetaDataMap.get("tenant-1").put("namespace-2", functionMetaDataInfoMap2);

        Assert.assertEquals(0, functionMetaDataManager.listFunctions(
                "tenant", "namespace").size());
        Assert.assertEquals(2, functionMetaDataManager.listFunctions(
                "tenant-1", "namespace-1").size());
        Assert.assertTrue(functionMetaDataManager.listFunctions(
                "tenant-1", "namespace-1").contains("func-1"));
        Assert.assertTrue(functionMetaDataManager.listFunctions(
                "tenant-1", "namespace-1").contains("func-2"));
        Assert.assertEquals(1, functionMetaDataManager.listFunctions(
                "tenant-1", "namespace-2").size());
        Assert.assertTrue(functionMetaDataManager.listFunctions(
                "tenant-1", "namespace-2").contains("func-3"));
    }

    @Test
    public void updateFunction() throws PulsarClientException {

        WorkerConfig workerConfig = new WorkerConfig();
        workerConfig.setWorkerId("worker-1");
        FunctionMetaDataManager functionMetaDataManager = spy(
                new FunctionMetaDataManager(workerConfig,
                        mock(SchedulerManager.class),
                        mock(PulsarClient.class)));
        Function.FunctionMetaData m1 = Function.FunctionMetaData.newBuilder()
                .setFunctionConfig(Function.FunctionConfig.newBuilder().setName("func-1")).build();

        Mockito.doReturn(null).when(functionMetaDataManager).submit(any(Request.ServiceRequest.class));
        functionMetaDataManager.updateFunction(m1);
        verify(functionMetaDataManager, times(1)).submit(any(Request.ServiceRequest.class));
        verify(functionMetaDataManager).submit(argThat(new ArgumentMatcher<Request.ServiceRequest>() {
            @Override
            public boolean matches(Object o) {
                if (o instanceof Request.ServiceRequest) {
                    Request.ServiceRequest serviceRequest = (Request.ServiceRequest) o;
                    if (!serviceRequest.getWorkerId().equals(workerConfig.getWorkerId())) {
                        return false;
                    }
                    if (!serviceRequest.getServiceRequestType().equals(Request.ServiceRequest.ServiceRequestType
                            .UPDATE)) {
                        return false;
                    }
                    if (!serviceRequest.getFunctionMetaData().equals(m1)) {
                        return false;
                    }
                    if (serviceRequest.getFunctionMetaData().getVersion() != 0) {
                        return false;
                    }
                    return true;
                }
                return false;
            }
        }));

        // already have record
        long version = 5;
        functionMetaDataManager = spy(
                new FunctionMetaDataManager(workerConfig,
                        mock(SchedulerManager.class),
                        mock(PulsarClient.class)));
        Map<String, Function.FunctionMetaData> functionMetaDataMap = new HashMap<>();
        Function.FunctionMetaData m2 = Function.FunctionMetaData.newBuilder()
                .setFunctionConfig(Function.FunctionConfig.newBuilder().setName("func-1")
                        .setNamespace("namespace-1").setTenant("tenant-1")).setVersion(version).build();
        functionMetaDataMap.put("func-1", m2);
        functionMetaDataManager.functionMetaDataMap.put("tenant-1", new HashMap<>());
        functionMetaDataManager.functionMetaDataMap.get("tenant-1").put("namespace-1", functionMetaDataMap);
        Mockito.doReturn(null).when(functionMetaDataManager).submit(any(Request.ServiceRequest.class));

        functionMetaDataManager.updateFunction(m2);
        verify(functionMetaDataManager, times(1)).submit(any(Request.ServiceRequest.class));
        verify(functionMetaDataManager).submit(argThat(new ArgumentMatcher<Request.ServiceRequest>() {
            @Override
            public boolean matches(Object o) {
                if (o instanceof Request.ServiceRequest) {
                    Request.ServiceRequest serviceRequest = (Request.ServiceRequest) o;
                    if (!serviceRequest.getWorkerId().equals(workerConfig.getWorkerId())) return false;
                    if (!serviceRequest.getServiceRequestType().equals(
                            Request.ServiceRequest.ServiceRequestType.UPDATE)) {
                        return false;
                    }
                    if (!serviceRequest.getFunctionMetaData().getFunctionConfig().equals(m2.getFunctionConfig())) {
                        return false;
                    }
                    if (serviceRequest.getFunctionMetaData().getVersion() != (version + 1)) {
                        return false;
                    }
                    return true;
                }
                return false;
            }
        }));

    }

    @Test
    public void deregisterFunction() throws PulsarClientException {
        long version = 5;
        WorkerConfig workerConfig = new WorkerConfig();
        workerConfig.setWorkerId("worker-1");
        FunctionMetaDataManager functionMetaDataManager = spy(
                new FunctionMetaDataManager(workerConfig,
                        mock(SchedulerManager.class),
                        mock(PulsarClient.class)));
        Function.FunctionMetaData m1 = Function.FunctionMetaData.newBuilder()
                .setFunctionConfig(Function.FunctionConfig.newBuilder().setName("func-1")
                        .setNamespace("namespace-1").setTenant("tenant-1")).setVersion(version).build();
        Map<String, Function.FunctionMetaData> functionMetaDataMap = new HashMap<>();
        functionMetaDataMap.put("func-1", m1);
        functionMetaDataManager.functionMetaDataMap.put("tenant-1", new HashMap<>());
        functionMetaDataManager.functionMetaDataMap.get("tenant-1").put("namespace-1", functionMetaDataMap);
        Mockito.doReturn(null).when(functionMetaDataManager).submit(any(Request.ServiceRequest.class));

        functionMetaDataManager.deregisterFunction("tenant-1", "namespace-1", "func-1");

        verify(functionMetaDataManager, times(1)).submit(any(Request.ServiceRequest.class));
        verify(functionMetaDataManager).submit(argThat(new ArgumentMatcher<Request.ServiceRequest>() {
            @Override
            public boolean matches(Object o) {
                if (o instanceof Request.ServiceRequest) {
                    Request.ServiceRequest serviceRequest = (Request.ServiceRequest) o;
                    if (!serviceRequest.getWorkerId().equals(workerConfig.getWorkerId())) return false;
                    if (!serviceRequest.getServiceRequestType().equals(
                            Request.ServiceRequest.ServiceRequestType.DELETE)) {
                        return false;
                    }
                    if (!serviceRequest.getFunctionMetaData().getFunctionConfig().equals(m1.getFunctionConfig())) {
                        return false;
                    }
                    if (serviceRequest.getFunctionMetaData().getVersion() != (version + 1)) {
                        return false;
                    }
                    return true;
                }
                return false;
            }
        }));
    }

    @Test
    public void testProcessRequest() throws PulsarClientException {
        WorkerConfig workerConfig = new WorkerConfig();
        FunctionMetaDataManager functionMetaDataManager = spy(
                new FunctionMetaDataManager(workerConfig,
                        mock(SchedulerManager.class),
                        mock(PulsarClient.class)));

        Mockito.doNothing().when(functionMetaDataManager).processUpdate(any(Request.ServiceRequest.class));
        Mockito.doNothing().when(functionMetaDataManager).proccessDeregister(any(Request.ServiceRequest.class));

        Request.ServiceRequest serviceRequest
                = Request.ServiceRequest.newBuilder().setServiceRequestType(
                        Request.ServiceRequest.ServiceRequestType.UPDATE).build();
        functionMetaDataManager.processRequest(MessageId.earliest, serviceRequest);

        verify(functionMetaDataManager, times(1)).processUpdate
                (any(Request.ServiceRequest.class));
        verify(functionMetaDataManager).processUpdate(serviceRequest);

        serviceRequest
                = Request.ServiceRequest.newBuilder().setServiceRequestType(
                Request.ServiceRequest.ServiceRequestType.INITIALIZE).build();
        functionMetaDataManager.processRequest(MessageId.earliest, serviceRequest);

        serviceRequest
                = Request.ServiceRequest.newBuilder().setServiceRequestType(
                Request.ServiceRequest.ServiceRequestType.DELETE).build();
        functionMetaDataManager.processRequest(MessageId.earliest, serviceRequest);

        verify(functionMetaDataManager, times(1)).proccessDeregister(
                any(Request.ServiceRequest.class));
        verify(functionMetaDataManager).proccessDeregister(serviceRequest);
    }

    @Test
    public void processUpdateTest() throws PulsarClientException {
        long version = 5;
        WorkerConfig workerConfig = new WorkerConfig();
        workerConfig.setWorkerId("worker-1");
        SchedulerManager schedulerManager = mock(SchedulerManager.class);
        FunctionMetaDataManager functionMetaDataManager = spy(
                new FunctionMetaDataManager(workerConfig,
                        schedulerManager,
                        mock(PulsarClient.class)));

        // worker has no record of function
        Function.FunctionMetaData m1 = Function.FunctionMetaData.newBuilder()
                .setFunctionConfig(Function.FunctionConfig.newBuilder().setName("func-1")
                        .setNamespace("namespace-1").setTenant("tenant-1")).setVersion(version).build();

        Request.ServiceRequest serviceRequest = Request.ServiceRequest.newBuilder()
                .setServiceRequestType(Request.ServiceRequest.ServiceRequestType.UPDATE)
                .setFunctionMetaData(m1)
                .setWorkerId("worker-1")
                .build();
        functionMetaDataManager.processUpdate(serviceRequest);
        verify(functionMetaDataManager, times(1))
                .setFunctionMetaData(any(Function.FunctionMetaData.class));
        verify(schedulerManager, times(1)).schedule();
        Assert.assertEquals(m1, functionMetaDataManager.functionMetaDataMap.get(
                "tenant-1").get("namespace-1").get("func-1"));
        Assert.assertEquals(1, functionMetaDataManager.functionMetaDataMap.get(
                "tenant-1").get("namespace-1").size());

        // worker has record of function

        // request is oudated
        schedulerManager = mock(SchedulerManager.class);
        functionMetaDataManager = spy(
                new FunctionMetaDataManager(workerConfig,
                        schedulerManager,
                        mock(PulsarClient.class)));

        Function.FunctionMetaData m3 = Function.FunctionMetaData.newBuilder()
                .setFunctionConfig(Function.FunctionConfig.newBuilder().setName("func-1")
                        .setNamespace("namespace-1").setTenant("tenant-1")).setVersion(version).build();
        functionMetaDataManager.setFunctionMetaData(m3);
        Function.FunctionMetaData outdated = Function.FunctionMetaData.newBuilder()
                .setFunctionConfig(Function.FunctionConfig.newBuilder().setName("func-1")
                        .setNamespace("namespace-1").setTenant("tenant-1")).setVersion(version - 1).build();

        serviceRequest = Request.ServiceRequest.newBuilder()
                .setServiceRequestType(Request.ServiceRequest.ServiceRequestType.UPDATE)
                .setFunctionMetaData(outdated)
                .setWorkerId("worker-1")
                .build();
        functionMetaDataManager.processUpdate(serviceRequest);

        Assert.assertEquals(m3, functionMetaDataManager.getFunctionMetaData(
                "tenant-1", "namespace-1", "func-1"));
        verify(functionMetaDataManager, times(1))
                .setFunctionMetaData(any(Function.FunctionMetaData.class));
        verify(schedulerManager, times(0)).schedule();

        Function.FunctionMetaData outdated2 = Function.FunctionMetaData.newBuilder()
                .setFunctionConfig(Function.FunctionConfig.newBuilder().setName("func-1")
                        .setNamespace("namespace-1").setTenant("tenant-1")).setVersion(version).build();

        serviceRequest = Request.ServiceRequest.newBuilder()
                .setServiceRequestType(Request.ServiceRequest.ServiceRequestType.UPDATE)
                .setFunctionMetaData(outdated2)
                .setWorkerId("worker-2")
                .build();
        functionMetaDataManager.processUpdate(serviceRequest);
        Assert.assertEquals(m3, functionMetaDataManager.getFunctionMetaData(
                "tenant-1", "namespace-1", "func-1"));
        verify(functionMetaDataManager, times(1))
                .setFunctionMetaData(any(Function.FunctionMetaData.class));
        verify(schedulerManager, times(0)).schedule();

        Assert.assertEquals(m1, functionMetaDataManager.functionMetaDataMap.get(
                "tenant-1").get("namespace-1").get("func-1"));
        Assert.assertEquals(1, functionMetaDataManager.functionMetaDataMap.get(
                "tenant-1").get("namespace-1").size());

        // schedule
        schedulerManager = mock(SchedulerManager.class);
        functionMetaDataManager = spy(
                new FunctionMetaDataManager(workerConfig,
                        schedulerManager,
                        mock(PulsarClient.class)));

        Function.FunctionMetaData m4 = Function.FunctionMetaData.newBuilder()
                .setFunctionConfig(Function.FunctionConfig.newBuilder().setName("func-1")
                        .setNamespace("namespace-1").setTenant("tenant-1")).setVersion(version).build();
        functionMetaDataManager.setFunctionMetaData(m4);
        Function.FunctionMetaData m5 = Function.FunctionMetaData.newBuilder()
                .setFunctionConfig(Function.FunctionConfig.newBuilder().setName("func-1")
                        .setNamespace("namespace-1").setTenant("tenant-1")).setVersion(version + 1).build();

        serviceRequest = Request.ServiceRequest.newBuilder()
                .setServiceRequestType(Request.ServiceRequest.ServiceRequestType.UPDATE)
                .setFunctionMetaData(m5)
                .setWorkerId("worker-2")
                .build();
        functionMetaDataManager.processUpdate(serviceRequest);

        verify(functionMetaDataManager, times(2))
                .setFunctionMetaData(any(Function.FunctionMetaData.class));
        verify(schedulerManager, times(1)).schedule();

        Assert.assertEquals(m1.toBuilder().setVersion(version + 1).build(),
                functionMetaDataManager.functionMetaDataMap.get(
                "tenant-1").get("namespace-1").get("func-1"));
        Assert.assertEquals(1, functionMetaDataManager.functionMetaDataMap.get(
                "tenant-1").get("namespace-1").size());
    }

    @Test
    public void processDeregister() throws PulsarClientException {
        long version = 5;
        WorkerConfig workerConfig = new WorkerConfig();
        workerConfig.setWorkerId("worker-1");
        SchedulerManager schedulerManager = mock(SchedulerManager.class);
        FunctionMetaDataManager functionMetaDataManager = spy(
                new FunctionMetaDataManager(workerConfig,
                        schedulerManager,
                        mock(PulsarClient.class)));
        // worker has no record of function
        Function.FunctionMetaData test = Function.FunctionMetaData.newBuilder()
                .setFunctionConfig(Function.FunctionConfig.newBuilder().setName("func-2")
                        .setNamespace("namespace-1").setTenant("tenant-1")).setVersion(version).build();
        functionMetaDataManager.setFunctionMetaData(test);
        Function.FunctionMetaData m1 = Function.FunctionMetaData.newBuilder()
                .setFunctionConfig(Function.FunctionConfig.newBuilder().setName("func-1")
                        .setNamespace("namespace-1").setTenant("tenant-1")).setVersion(version).build();
        Request.ServiceRequest serviceRequest = Request.ServiceRequest.newBuilder()
                .setServiceRequestType(Request.ServiceRequest.ServiceRequestType.UPDATE)
                .setFunctionMetaData(m1)
                .setWorkerId("worker-1")
                .build();
        functionMetaDataManager.proccessDeregister(serviceRequest);

        verify(schedulerManager, times(0)).schedule();
        Assert.assertEquals(test, functionMetaDataManager.functionMetaDataMap.get(
                "tenant-1").get("namespace-1").get("func-2"));
        Assert.assertEquals(1, functionMetaDataManager.functionMetaDataMap.get(
                "tenant-1").get("namespace-1").size());

        // function exists but request outdated
        schedulerManager = mock(SchedulerManager.class);
        functionMetaDataManager = spy(
                new FunctionMetaDataManager(workerConfig,
                        schedulerManager,
                        mock(PulsarClient.class)));
        functionMetaDataManager.setFunctionMetaData(test);
        Function.FunctionMetaData m2 = Function.FunctionMetaData.newBuilder()
                .setFunctionConfig(Function.FunctionConfig.newBuilder().setName("func-1")
                        .setNamespace("namespace-1").setTenant("tenant-1")).setVersion(version).build();
        functionMetaDataManager.setFunctionMetaData(m2);
        serviceRequest = Request.ServiceRequest.newBuilder()
                .setServiceRequestType(Request.ServiceRequest.ServiceRequestType.UPDATE)
                .setFunctionMetaData(m2)
                .setWorkerId("worker-1")
                .build();

        functionMetaDataManager.proccessDeregister(serviceRequest);
        verify(schedulerManager, times(0)).schedule();

        Assert.assertEquals(test, functionMetaDataManager.functionMetaDataMap.get(
                "tenant-1").get("namespace-1").get("func-2"));
        Assert.assertEquals(m2, functionMetaDataManager.functionMetaDataMap.get(
                "tenant-1").get("namespace-1").get("func-1"));
        Assert.assertEquals(2, functionMetaDataManager.functionMetaDataMap.get(
                "tenant-1").get("namespace-1").size());

        // function deleted
        schedulerManager = mock(SchedulerManager.class);
        functionMetaDataManager = spy(
                new FunctionMetaDataManager(workerConfig,
                        schedulerManager,
                        mock(PulsarClient.class)));
        functionMetaDataManager.setFunctionMetaData(test);

        Function.FunctionMetaData m3 = Function.FunctionMetaData.newBuilder()
                .setFunctionConfig(Function.FunctionConfig.newBuilder().setName("func-1")
                        .setNamespace("namespace-1").setTenant("tenant-1")).setVersion(version ).build();
        functionMetaDataManager.setFunctionMetaData(m3);

        Function.FunctionMetaData m4 = Function.FunctionMetaData.newBuilder()
                .setFunctionConfig(Function.FunctionConfig.newBuilder().setName("func-1")
                        .setNamespace("namespace-1").setTenant("tenant-1")).setVersion(version +1).build();
        serviceRequest = Request.ServiceRequest.newBuilder()
                .setServiceRequestType(Request.ServiceRequest.ServiceRequestType.UPDATE)
                .setFunctionMetaData(m4)
                .setWorkerId("worker-1")
                .build();

        functionMetaDataManager.proccessDeregister(serviceRequest);
        verify(schedulerManager, times(1)).schedule();

        Assert.assertEquals(test, functionMetaDataManager.functionMetaDataMap.get(
                "tenant-1").get("namespace-1").get("func-2"));
        Assert.assertEquals(1, functionMetaDataManager.functionMetaDataMap.get(
                "tenant-1").get("namespace-1").size());
    }
}