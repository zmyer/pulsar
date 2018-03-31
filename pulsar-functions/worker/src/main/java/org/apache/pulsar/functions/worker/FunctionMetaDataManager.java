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

import com.google.common.annotations.VisibleForTesting;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.functions.proto.Function;
import org.apache.pulsar.functions.proto.Function.FunctionMetaData;
import org.apache.pulsar.functions.proto.Request;
import org.apache.pulsar.functions.worker.request.RequestResult;
import org.apache.pulsar.functions.worker.request.ServiceRequestInfo;
import org.apache.pulsar.functions.worker.request.ServiceRequestManager;
import org.apache.pulsar.functions.worker.request.ServiceRequestUtils;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class maintains a global state of all function metadata and is responsible for serving function metadata
 */
@Slf4j
public class FunctionMetaDataManager implements AutoCloseable {
    // Represents the global state
    // tenant -> namespace -> (function name, FunctionRuntimeInfo)
    @VisibleForTesting
    final Map<String, Map<String, Map<String, FunctionMetaData>>> functionMetaDataMap = new ConcurrentHashMap<>();

    // A map in which the key is the service request id and value is the service request
    private final Map<String, ServiceRequestInfo> pendingServiceRequests = new ConcurrentHashMap<>();

    private final ServiceRequestManager serviceRequestManager;
    private final SchedulerManager schedulerManager;
    private final WorkerConfig workerConfig;
    private final PulsarClient pulsarClient;

    private FunctionMetaDataTopicTailer functionMetaDataTopicTailer;

    @Setter
    @Getter
    boolean isInitializePhase = false;

    public FunctionMetaDataManager(WorkerConfig workerConfig,
                                   SchedulerManager schedulerManager,
                                   PulsarClient pulsarClient) throws PulsarClientException {
        this.workerConfig = workerConfig;
        this.pulsarClient = pulsarClient;
        this.serviceRequestManager = getServiceRequestManager(
                this.pulsarClient, this.workerConfig.getFunctionMetadataTopic());
        this.schedulerManager = schedulerManager;
    }

    /**
     * Public methods. Please use these methods if references FunctionMetaManager from an external class
     */

    /**
     * Initializes the FunctionMetaDataManager.  Does the following:
     * 1. Restores from snapshot if one exists
     * 2. Sends out initialize marker to FMT and consume messages until the initialize marker is consumed
     */
    public void initialize() {
        log.info("/** Initializing Function Metadata Manager **/");
        try {

            Reader reader = pulsarClient.newReader()
                    .topic(this.workerConfig.getFunctionMetadataTopic())
                    .startMessageId(MessageId.earliest)
                    .create();

            this.functionMetaDataTopicTailer = new FunctionMetaDataTopicTailer(this, reader);
            // read all existing messages
            this.setInitializePhase(true);
            while (reader.hasMessageAvailable()) {
                this.functionMetaDataTopicTailer.processRequest(reader.readNext());
            }
            this.setInitializePhase(false);
            // schedule functions if necessary
            this.schedulerManager.schedule();
            // start function metadata tailer
            this.functionMetaDataTopicTailer.start();

        } catch (Exception e) {
            log.error("Failed to initialize meta data store: ", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the function metadata for a function
     * @param tenant the tenant the function belongs to
     * @param namespace the namespace the function belongs to
     * @param functionName the function name
     * @return FunctionMetaData that contains the function metadata
     */
    public synchronized FunctionMetaData getFunctionMetaData(String tenant, String namespace, String functionName) {
        return this.functionMetaDataMap.get(tenant).get(namespace).get(functionName);
    }

    /**
     * Get a list of all the meta for every function
     * @return list of function metadata
     */
    public synchronized List<FunctionMetaData> getAllFunctionMetaData() {
        List<FunctionMetaData> ret = new LinkedList<>();
        for (Map<String, Map<String, FunctionMetaData>> i : this.functionMetaDataMap.values()) {
            for (Map<String, FunctionMetaData> j : i.values()) {
                ret.addAll(j.values());
            }
        }
        return ret;
    }

    /**
     * List all the functions in a namespace
     * @param tenant the tenant the namespace belongs to
     * @param namespace the namespace
     * @return a list of function names
     */
    public synchronized Collection<String> listFunctions(String tenant, String namespace) {
        List<String> ret = new LinkedList<>();

        if (!this.functionMetaDataMap.containsKey(tenant)) {
            return ret;
        }

        if (!this.functionMetaDataMap.get(tenant).containsKey(namespace)) {
            return ret;
        }
        for (FunctionMetaData functionMetaData : this.functionMetaDataMap.get(tenant).get(namespace).values()) {
            ret.add(functionMetaData.getFunctionConfig().getName());
        }
        return ret;
    }

    /**
     * Check if the function exists
     * @param tenant tenant that the function belongs to
     * @param namespace namespace that the function belongs to
     * @param functionName name of function
     * @return true if function exists and false if it does not
     */
    public synchronized boolean containsFunction(String tenant, String namespace, String functionName) {
        return containsFunctionMetaData(tenant, namespace, functionName);
    }

    /**
     * Sends an update request to the FMT (Function Metadata Topic)
     * @param functionMetaData The function metadata that needs to be updated
     * @return a completable future of when the update has been applied
     */
    public synchronized CompletableFuture<RequestResult> updateFunction(FunctionMetaData functionMetaData) {

        long version = 0;

        String tenant = functionMetaData.getFunctionConfig().getTenant();
        if (!this.functionMetaDataMap.containsKey(tenant)) {
            this.functionMetaDataMap.put(tenant, new ConcurrentHashMap<>());
        }

        Map<String, Map<String, FunctionMetaData>> namespaces = this.functionMetaDataMap.get(tenant);
        String namespace = functionMetaData.getFunctionConfig().getNamespace();
        if (!namespaces.containsKey(namespace)) {
            namespaces.put(namespace, new ConcurrentHashMap<>());
        }

        Map<String, FunctionMetaData> functionMetaDatas = namespaces.get(namespace);
        String functionName = functionMetaData.getFunctionConfig().getName();
        if (functionMetaDatas.containsKey(functionName)) {
            version = functionMetaDatas.get(functionName).getVersion() + 1;
        }

        FunctionMetaData newFunctionMetaData = functionMetaData.toBuilder().setVersion(version).build();

        Request.ServiceRequest updateRequest = ServiceRequestUtils.getUpdateRequest(
                this.workerConfig.getWorkerId(), newFunctionMetaData);

        return submit(updateRequest);
    }


    /**
     * Sends a deregister request to the FMT (Function Metadata Topic) for a function
     * @param tenant the tenant the function that needs to be deregistered belongs to
     * @param namespace the namespace the function that needs to be deregistered belongs to
     * @param functionName the name of the function
     * @return a completable future of when the deregister has been applied
     */
    public synchronized CompletableFuture<RequestResult> deregisterFunction(String tenant, String namespace, String functionName) {
        FunctionMetaData functionMetaData = this.functionMetaDataMap.get(tenant).get(namespace).get(functionName);

        FunctionMetaData newFunctionMetaData = functionMetaData.toBuilder()
                .setVersion(functionMetaData.getVersion() + 1)
                .build();

        Request.ServiceRequest deregisterRequest = ServiceRequestUtils.getDeregisterRequest(
                this.workerConfig.getWorkerId(), newFunctionMetaData);

        return submit(deregisterRequest);
    }

    /**
     * Processes a request received from the FMT (Function Metadata Topic)
     * @param messageId The message id of the request
     * @param serviceRequest The request
     */
    public void processRequest(MessageId messageId, Request.ServiceRequest serviceRequest) {

        // make sure that processing requests don't happen simultaneously
        synchronized (this) {
            switch (serviceRequest.getServiceRequestType()) {
                case UPDATE:
                    this.processUpdate(serviceRequest);
                    break;
                case DELETE:
                    this.proccessDeregister(serviceRequest);
                    break;
                default:
                    log.warn("Received request with unrecognized type: {}", serviceRequest);
            }
        }
    }

    /**
     * Private methods for internal use.  Should not be used outside of this class
     */

    private boolean containsFunctionMetaData(FunctionMetaData functionMetaData) {
        return containsFunctionMetaData(functionMetaData.getFunctionConfig());
    }

    private boolean containsFunctionMetaData(Function.FunctionConfig functionConfig) {
        return containsFunctionMetaData(
                functionConfig.getTenant(), functionConfig.getNamespace(), functionConfig.getName());
    }

    private boolean containsFunctionMetaData(String tenant, String namespace, String functionName) {
        if (this.functionMetaDataMap.containsKey(tenant)) {
            if (this.functionMetaDataMap.get(tenant).containsKey(namespace)) {
                if (this.functionMetaDataMap.get(tenant).get(namespace).containsKey(functionName)) {
                    return true;
                }
            }
        }
        return false;
    }

    @VisibleForTesting
    synchronized void proccessDeregister(Request.ServiceRequest deregisterRequest) {

        FunctionMetaData deregisterRequestFs = deregisterRequest.getFunctionMetaData();
        String functionName = deregisterRequestFs.getFunctionConfig().getName();
        String tenant = deregisterRequestFs.getFunctionConfig().getTenant();
        String namespace = deregisterRequestFs.getFunctionConfig().getNamespace();

        boolean needsScheduling = false;

        log.debug("Process deregister request: {}", deregisterRequest);

        // Check if we still have this function. Maybe already deleted by someone else
        if (this.containsFunctionMetaData(deregisterRequestFs)) {
            // check if request is outdated
            if (!isRequestOutdated(deregisterRequest)) {
                this.functionMetaDataMap.get(tenant).get(namespace).remove(functionName);
                completeRequest(deregisterRequest, true);
                needsScheduling = true;
            } else {
                completeRequest(deregisterRequest, false,
                        "Request ignored because it is out of date. Please try again.");
            }
        } else {
            // already deleted so  just complete request
            completeRequest(deregisterRequest, true);
        }

        if (!this.isInitializePhase() && needsScheduling) {
            this.schedulerManager.schedule();
        }
    }

    @VisibleForTesting
    synchronized void processUpdate(Request.ServiceRequest updateRequest) {

        log.debug("Process update request: {}", updateRequest);

        FunctionMetaData updateRequestFs = updateRequest.getFunctionMetaData();

        boolean needsScheduling = false;

        // Worker doesn't know about the function so far
        if (!this.containsFunctionMetaData(updateRequestFs)) {
            // Since this is the first time worker has seen function, just put it into internal function metadata store
            setFunctionMetaData(updateRequestFs);
            needsScheduling = true;
            completeRequest(updateRequest, true);
        } else {
            // The request is an update to an existing function since this worker already has a record of this function
            // in its function metadata store
            // Check if request is outdated
            if (!isRequestOutdated(updateRequest)) {
                // update the function metadata
                setFunctionMetaData(updateRequestFs);
                needsScheduling = true;
                completeRequest(updateRequest, true);
            } else {
                completeRequest(updateRequest, false,
                        "Request ignored because it is out of date. Please try again.");
            }
        }

        if (!this.isInitializePhase() && needsScheduling) {
            this.schedulerManager.schedule();
        }
    }

    /**
     * Complete requests that this worker has pending
     * @param serviceRequest
     * @param isSuccess
     * @param message
     */
    private void completeRequest(Request.ServiceRequest serviceRequest, boolean isSuccess, String message) {
        ServiceRequestInfo pendingServiceRequestInfo
                = this.pendingServiceRequests.getOrDefault(
                serviceRequest.getRequestId(), null);
        if (pendingServiceRequestInfo != null) {
            RequestResult requestResult = new RequestResult();
            requestResult.setSuccess(isSuccess);
            requestResult.setMessage(message);
            pendingServiceRequestInfo.getRequestResultCompletableFuture().complete(requestResult);
        }
    }

    private void completeRequest(Request.ServiceRequest serviceRequest, boolean isSuccess) {
        completeRequest(serviceRequest, isSuccess, null);
    }


    private boolean isRequestOutdated(Request.ServiceRequest serviceRequest) {
        FunctionMetaData requestFunctionMetaData = serviceRequest.getFunctionMetaData();
        Function.FunctionConfig functionConfig = requestFunctionMetaData.getFunctionConfig();
        FunctionMetaData currentFunctionMetaData = this.functionMetaDataMap.get(functionConfig.getTenant())
                .get(functionConfig.getNamespace()).get(functionConfig.getName());
        return currentFunctionMetaData.getVersion() >= requestFunctionMetaData.getVersion();
    }

    @VisibleForTesting
    void setFunctionMetaData(FunctionMetaData functionMetaData) {
        Function.FunctionConfig functionConfig = functionMetaData.getFunctionConfig();
        if (!this.functionMetaDataMap.containsKey(functionConfig.getTenant())) {
            this.functionMetaDataMap.put(functionConfig.getTenant(), new ConcurrentHashMap<>());
        }

        if (!this.functionMetaDataMap.get(functionConfig.getTenant()).containsKey(functionConfig.getNamespace())) {
            this.functionMetaDataMap.get(functionConfig.getTenant())
                    .put(functionConfig.getNamespace(), new ConcurrentHashMap<>());
        }
        this.functionMetaDataMap.get(functionConfig.getTenant())
                .get(functionConfig.getNamespace()).put(functionConfig.getName(), functionMetaData);
    }

    @VisibleForTesting
    CompletableFuture<RequestResult> submit(Request.ServiceRequest serviceRequest) {
        ServiceRequestInfo serviceRequestInfo = ServiceRequestInfo.of(serviceRequest);
        CompletableFuture<MessageId> messageIdCompletableFuture = this.serviceRequestManager.submitRequest(serviceRequest);

        serviceRequestInfo.setCompletableFutureRequestMessageId(messageIdCompletableFuture);
        CompletableFuture<RequestResult> requestResultCompletableFuture = new CompletableFuture<>();

        serviceRequestInfo.setRequestResultCompletableFuture(requestResultCompletableFuture);

        this.pendingServiceRequests.put(serviceRequestInfo.getServiceRequest().getRequestId(), serviceRequestInfo);

        return requestResultCompletableFuture;
    }

    @Override
    public void close() throws Exception {
        if (this.functionMetaDataTopicTailer != null) {
            this.functionMetaDataTopicTailer.close();
        }
        if (this.serviceRequestManager != null) {
            this.serviceRequestManager.close();
        }
    }

    private ServiceRequestManager getServiceRequestManager(PulsarClient pulsarClient, String functionMetadataTopic) throws PulsarClientException {
        return new ServiceRequestManager(pulsarClient.createProducer(functionMetadataTopic));
    }
}
