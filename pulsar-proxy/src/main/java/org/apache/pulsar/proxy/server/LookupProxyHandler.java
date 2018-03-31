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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.pulsar.common.api.Commands;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandLookupTopic;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandLookupTopicResponse.LookupType;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandPartitionedTopicMetadata;
import org.apache.pulsar.common.api.proto.PulsarApi.ServerError;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.policies.data.loadbalancer.ServiceLookupData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.prometheus.client.Counter;
import static org.apache.commons.lang3.StringUtils.isBlank;

public class LookupProxyHandler {
    private final String throttlingErrorMessage = "Too many concurrent lookup and partitionsMetadata requests";
    private final ProxyService service;
    private final ProxyConnection proxyConnection;
    private final boolean connectWithTLS;

    private SocketAddress clientAddress;
    private String brokerServiceURL;

    private static final Counter lookupRequests = Counter
            .build("pulsar_proxy_lookup_requests", "Counter of topic lookup requests").create().register();

    private static final Counter partitionsMetadataRequests = Counter
            .build("pulsar_proxy_partitions_metadata_requests", "Counter of partitions metadata requests").create()
            .register();

    static final Counter rejectedLookupRequests = Counter.build("pulsar_proxy_rejected_lookup_requests",
            "Counter of topic lookup requests rejected due to throttling").create().register();

    static final Counter rejectedPartitionsMetadataRequests = Counter
            .build("pulsar_proxy_rejected_partitions_metadata_requests",
                    "Counter of partitions metadata requests rejected due to throttling")
            .create().register();

    public LookupProxyHandler(ProxyService proxy, ProxyConnection proxyConnection) {
        this.service = proxy;
        this.proxyConnection = proxyConnection;
        this.clientAddress = proxyConnection.clientAddress();
        this.connectWithTLS = proxy.getConfiguration().isTlsEnabledWithBroker();
        this.brokerServiceURL = this.connectWithTLS ? proxy.getConfiguration().getBrokerServiceURLTLS()
                : proxy.getConfiguration().getBrokerServiceURL();
    }

    public void handleLookup(CommandLookupTopic lookup) {
        if (log.isDebugEnabled()) {
            log.debug("Received Lookup from {}", clientAddress);
        }
        long clientRequestId = lookup.getRequestId();
        if (this.service.getLookupRequestSemaphore().tryAcquire()) {
            lookupRequests.inc();
            String topic = lookup.getTopic();
            String serviceUrl;
            if (isBlank(brokerServiceURL)) {
                ServiceLookupData availableBroker = null;
                try {
                    availableBroker = service.getDiscoveryProvider().nextBroker();
                } catch (Exception e) {
                    log.warn("[{}] Failed to get next active broker {}", clientAddress, e.getMessage(), e);
                    proxyConnection.ctx().writeAndFlush(Commands.newLookupErrorResponse(ServerError.ServiceNotReady,
                            e.getMessage(), clientRequestId));
                    return;
                }
                serviceUrl = this.connectWithTLS ? availableBroker.getPulsarServiceUrlTls()
                        : availableBroker.getPulsarServiceUrl();
            } else {
                serviceUrl = this.connectWithTLS ? service.getConfiguration().getBrokerServiceURLTLS()
                        : service.getConfiguration().getBrokerServiceURL();
            }
            performLookup(clientRequestId, topic, serviceUrl, false, 10);
            this.service.getLookupRequestSemaphore().release();
        } else {
            rejectedLookupRequests.inc();
            if (log.isDebugEnabled()) {
                log.debug("Lookup Request ID {} from {} rejected - {}.", clientRequestId, clientAddress,
                        throttlingErrorMessage);
            }
            proxyConnection.ctx().writeAndFlush(Commands.newLookupErrorResponse(ServerError.ServiceNotReady,
                    throttlingErrorMessage, clientRequestId));
        }

    }

    private void performLookup(long clientRequestId, String topic, String brokerServiceUrl, boolean authoritative,
            int numberOfRetries) {
        if (numberOfRetries == 0) {
            proxyConnection.ctx().writeAndFlush(Commands.newLookupErrorResponse(ServerError.ServiceNotReady,
                    "Reached max number of redirections", clientRequestId));
            return;
        }

        URI brokerURI;
        try {
            brokerURI = new URI(brokerServiceUrl);
        } catch (URISyntaxException e) {
            proxyConnection.ctx().writeAndFlush(
                    Commands.newLookupErrorResponse(ServerError.MetadataError, e.getMessage(), clientRequestId));
            return;
        }

        InetSocketAddress addr = InetSocketAddress.createUnresolved(brokerURI.getHost(), brokerURI.getPort());
        if (log.isDebugEnabled()) {
            log.debug("Getting connections to '{}' for Looking up topic '{}' with clientReq Id '{}'", addr, topic,
                    clientRequestId);
        }
        service.getConnectionPool().getConnection(addr).thenAccept(clientCnx -> {
            // Connected to backend broker
            long requestId = service.newRequestId();
            ByteBuf command;
            if (service.getConfiguration().isAuthenticationEnabled()) {
                command = Commands.newLookup(topic, authoritative, proxyConnection.clientAuthRole,
                        proxyConnection.clientAuthData, proxyConnection.clientAuthMethod, requestId);
            } else {
                command = Commands.newLookup(topic, authoritative, requestId);
            }
            clientCnx.newLookup(command, requestId).thenAccept(result -> {
                String brokerUrl = connectWithTLS ? result.brokerUrlTls : result.brokerUrl;
                if (result.redirect) {
                    // Need to try the lookup again on a different broker
                    performLookup(clientRequestId, topic, brokerUrl, result.authoritative, numberOfRetries - 1);
                } else {
                    // Reply the same address for both TLS non-TLS. The reason is that whether we use TLS
                    // and broker is independent of whether the client itself uses TLS, but we need to force the
                    // client
                    // to use the appropriate target broker (and port) when it will connect back.
                    proxyConnection.ctx().writeAndFlush(Commands.newLookupResponse(brokerUrl, brokerUrl, true,
                            LookupType.Connect, clientRequestId, true /* this is coming from proxy */));
                }
            }).exceptionally(ex -> {
                log.warn("[{}] Failed to lookup topic {}: {}", clientAddress, topic, ex.getMessage());
                proxyConnection.ctx().writeAndFlush(
                        Commands.newLookupErrorResponse(ServerError.ServiceNotReady, ex.getMessage(), clientRequestId));
                return null;
            });
        }).exceptionally(ex -> {
            // Failed to connect to backend broker
            proxyConnection.ctx().writeAndFlush(
                    Commands.newLookupErrorResponse(ServerError.ServiceNotReady, ex.getMessage(), clientRequestId));
            return null;
        });
    }

    public void handlePartitionMetadataResponse(CommandPartitionedTopicMetadata partitionMetadata) {
        partitionsMetadataRequests.inc();
        if (log.isDebugEnabled()) {
            log.debug("[{}] Received PartitionMetadataLookup", clientAddress);
        }
        final long clientRequestId = partitionMetadata.getRequestId();
        if (this.service.getLookupRequestSemaphore().tryAcquire()) {
            handlePartitionMetadataResponse(partitionMetadata, clientRequestId);
            this.service.getLookupRequestSemaphore().release();
        } else {
            rejectedPartitionsMetadataRequests.inc();
            if (log.isDebugEnabled()) {
                log.debug("PartitionMetaData Request ID {} from {} rejected - {}.", clientRequestId, clientAddress,
                        throttlingErrorMessage);
            }
            proxyConnection.ctx().writeAndFlush(Commands.newPartitionMetadataResponse(ServerError.ServiceNotReady,
                    throttlingErrorMessage, clientRequestId));
        }
    }

    private void handlePartitionMetadataResponse(CommandPartitionedTopicMetadata partitionMetadata,
            long clientRequestId) {
        TopicName topicName = TopicName.get(partitionMetadata.getTopic());
        if (isBlank(brokerServiceURL)) {
            service.getDiscoveryProvider().getPartitionedTopicMetadata(service, topicName,
                    proxyConnection.clientAuthRole, proxyConnection.authenticationData).thenAccept(metadata -> {
                        if (log.isDebugEnabled()) {
                            log.debug("[{}] Total number of partitions for topic {} is {}",
                                    proxyConnection.clientAuthRole, topicName, metadata.partitions);
                        }
                        proxyConnection.ctx().writeAndFlush(
                                Commands.newPartitionMetadataResponse(metadata.partitions, clientRequestId));
                    }).exceptionally(ex -> {
                        log.warn("[{}] Failed to get partitioned metadata for topic {} {}", clientAddress, topicName,
                                ex.getMessage(), ex);
                        proxyConnection.ctx().writeAndFlush(Commands.newPartitionMetadataResponse(
                                ServerError.ServiceNotReady, ex.getMessage(), clientRequestId));
                        return null;
                    });
        } else {
            URI brokerURI;
            try {
                brokerURI = new URI(brokerServiceURL);
            } catch (URISyntaxException e) {
                proxyConnection.ctx().writeAndFlush(Commands.newPartitionMetadataResponse(ServerError.MetadataError,
                        e.getMessage(), clientRequestId));
                return;
            }
            InetSocketAddress addr = new InetSocketAddress(brokerURI.getHost(), brokerURI.getPort());

            if (log.isDebugEnabled()) {
                log.debug("Getting connections to '{}' for Looking up topic '{}' with clientReq Id '{}'", addr,
                        topicName.getPartitionedTopicName(), clientRequestId);
            }

            service.getConnectionPool().getConnection(addr).thenAccept(clientCnx -> {
                // Connected to backend broker
                long requestId = service.newRequestId();
                ByteBuf command;
                if (service.getConfiguration().isAuthenticationEnabled()) {
                    command = Commands.newPartitionMetadataRequest(topicName.toString(), requestId,
                            proxyConnection.clientAuthRole, proxyConnection.clientAuthData,
                            proxyConnection.clientAuthMethod);
                } else {
                    command = Commands.newPartitionMetadataRequest(topicName.toString(), requestId);
                }
                clientCnx.newLookup(command, requestId).thenAccept(lookupDataResult -> {
                    proxyConnection.ctx().writeAndFlush(
                            Commands.newPartitionMetadataResponse(lookupDataResult.partitions, clientRequestId));
                }).exceptionally((ex) -> {
                    log.warn("[{}] failed to get Partitioned metadata : {}", topicName.toString(),
                            ex.getCause().getMessage(), ex);
                    proxyConnection.ctx().writeAndFlush(Commands.newLookupErrorResponse(ServerError.ServiceNotReady,
                            ex.getMessage(), clientRequestId));
                    return null;
                });
            }).exceptionally(ex -> {
                // Failed to connect to backend broker
                proxyConnection.ctx().writeAndFlush(Commands.newPartitionMetadataResponse(ServerError.ServiceNotReady,
                        ex.getMessage(), clientRequestId));
                return null;
            });
        }
    }

    private static final Logger log = LoggerFactory.getLogger(LookupProxyHandler.class);
}
