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

import com.google.common.collect.Lists;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;
import org.apache.pulsar.common.lookup.data.LookupData;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.partition.PartitionedTopicMetadata;
import org.apache.pulsar.common.util.FutureUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.EventLoopGroup;

class HttpLookupService implements LookupService {

    private final HttpClient httpClient;
    private final boolean useTls;
    private static final String BasePath = "lookup/v2/destination/";

    public HttpLookupService(ClientConfigurationData conf, EventLoopGroup eventLoopGroup)
            throws PulsarClientException {
        this.httpClient = new HttpClient(conf.getServiceUrl(), conf.getAuthentication(),
                eventLoopGroup, conf.isTlsAllowInsecureConnection(), conf.getTlsTrustCertsFilePath());
        this.useTls = conf.isUseTls();
    }

    /**
     * Calls http-lookup api to find broker-service address which can serve a given topic.
     *
     * @param topicName topic-name
     * @return broker-socket-address that serves given topic
     */
    @SuppressWarnings("deprecation")
    public CompletableFuture<Pair<InetSocketAddress, InetSocketAddress>> getBroker(TopicName topicName) {
        return httpClient.get(BasePath + topicName.getLookupName(), LookupData.class).thenCompose(lookupData -> {
            // Convert LookupData into as SocketAddress, handling exceptions
        	URI uri = null;
            try {
                if (useTls) {
                    uri = new URI(lookupData.getBrokerUrlTls());
                } else {
                    String serviceUrl = lookupData.getBrokerUrl();
                    if (serviceUrl == null) {
                        serviceUrl = lookupData.getNativeUrl();
                    }
                    uri = new URI(serviceUrl);
                }

                InetSocketAddress brokerAddress = InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort());
                return CompletableFuture.completedFuture(Pair.of(brokerAddress, brokerAddress));
            } catch (Exception e) {
                // Failed to parse url
            	log.warn("[{}] Lookup Failed due to invalid url {}, {}", topicName, uri, e.getMessage());
                return FutureUtil.failedFuture(e);
            }
        });
    }

    public CompletableFuture<PartitionedTopicMetadata> getPartitionedTopicMetadata(TopicName topicName) {
    	return httpClient.get(String.format("admin/%s/partitions", topicName.getLookupName()),
                PartitionedTopicMetadata.class);
    }

    public String getServiceUrl() {
    	return httpClient.url.toString();
    }

    @Override
    public CompletableFuture<List<String>> getTopicsUnderNamespace(NamespaceName namespace) {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        httpClient
            .get(String.format("admin/namespaces/%s/destinations", namespace), String[].class)
            .thenAccept(topics -> {
                List<String> result = Lists.newArrayList();
                // do not keep partition part of topic name
                Arrays.asList(topics).forEach(topic -> {
                    String filtered = TopicName.get(topic).getPartitionedTopicName();
                    if (!result.contains(filtered)) {
                        result.add(filtered);
                    }
                });
                future.complete(result);})
            .exceptionally(ex -> {
                log.warn("Failed to getTopicsUnderNamespace namespace: {}.", namespace, ex.getMessage());
                future.completeExceptionally(ex);
                return null;
            });
        return future;
    }

    @Override
    public void close() throws Exception {
        httpClient.close();
    }

    private static final Logger log = LoggerFactory.getLogger(HttpLookupService.class);
}
