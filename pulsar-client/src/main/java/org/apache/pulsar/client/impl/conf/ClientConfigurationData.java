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
package org.apache.pulsar.client.impl.conf;

import java.io.Serializable;

import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.impl.auth.AuthenticationDisabled;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

/**
 * This is a simple holder of the client configuration values.
 */
@Data
public class ClientConfigurationData implements Serializable, Cloneable {
    private static final long serialVersionUID = 1L;

    private String serviceUrl;

    @JsonIgnore
    private Authentication authentication = new AuthenticationDisabled();
    private long operationTimeoutMs = 30000;
    private long statsIntervalSeconds = 60;

    private int numIoThreads = 1;
    private int numListenerThreads = 1;
    private int connectionsPerBroker = 1;

    private boolean useTcpNoDelay = true;

    private boolean useTls = false;
    private String tlsTrustCertsFilePath = "";
    private boolean tlsAllowInsecureConnection = false;
    private boolean tlsHostnameVerificationEnable = false;
    private int concurrentLookupRequest = 50000;
    private int maxNumberOfRejectedRequestPerConnection = 50;

    public ClientConfigurationData clone() {
        try {
            return (ClientConfigurationData) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Failed to clone ClientConfigurationData");
        }
    }
}
