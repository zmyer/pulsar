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
package org.apache.pulsar.websocket.service;

import java.util.Properties;
import java.util.Set;

import org.apache.pulsar.broker.authorization.PulsarAuthorizationProvider;
import org.apache.pulsar.common.configuration.FieldContext;
import org.apache.pulsar.common.configuration.PulsarConfiguration;

import com.google.common.collect.Sets;

public class WebSocketProxyConfiguration implements PulsarConfiguration {

    // Number of threads used by Proxy server
    public static final int PROXY_SERVER_EXECUTOR_THREADS = 2 * Runtime.getRuntime().availableProcessors();
    // Number of threads used by Websocket service
    public static final int WEBSOCKET_SERVICE_THREADS = 20;
    // Number of threads used by Global ZK
    public static final int GLOBAL_ZK_THREADS = 8;

    // Name of the cluster to which this broker belongs to
    @FieldContext(required = true)
    private String clusterName;

    // Pulsar cluster url to connect to broker (optional if globalZookeeperServers present)
    private String serviceUrl;
    private String serviceUrlTls;
    private String brokerServiceUrl;
    private String brokerServiceUrlTls;
    
    // Path for the file used to determine the rotation status for the broker
    // when responding to service discovery health checks
    private String statusFilePath;

    // Global Zookeeper quorum connection string
    private String globalZookeeperServers;
    // Zookeeper session timeout in milliseconds
    private long zooKeeperSessionTimeoutMillis = 30000;

    // Port to use to server HTTP request
    private int webServicePort = 8080;
    // Port to use to server HTTPS request
    private int webServicePortTls = 8443;
    // Hostname or IP address the service binds on, default is 0.0.0.0.
    private String bindAddress;
    // --- Authentication ---
    // Enable authentication
    private boolean authenticationEnabled;
    // Autentication provider name list, which is a list of class names
    private Set<String> authenticationProviders = Sets.newTreeSet();
    // Enforce authorization
    private boolean authorizationEnabled;
    // Authorization provider fully qualified class-name
    private String authorizationProvider = PulsarAuthorizationProvider.class.getName();

    // Role names that are treated as "super-user", meaning they will be able to
    // do all admin operations and publish/consume from all topics
    private Set<String> superUserRoles = Sets.newTreeSet();

    // Allow wildcard matching in authorization
    // (wildcard matching only applicable if wildcard-char:
    // * presents at first or last position eg: *.pulsar.service, pulsar.service.*)
    private boolean authorizationAllowWildcardsMatching = false;

    // Authentication settings of the proxy itself. Used to connect to brokers
    private String brokerClientAuthenticationPlugin;
    private String brokerClientAuthenticationParameters;
    // Path for the trusted TLS certificate file for outgoing connection to a server (broker)
    private String brokerClientTrustCertsFilePath = "";

    // Number of IO threads in Pulsar Client used in WebSocket proxy
    private int numIoThreads = Runtime.getRuntime().availableProcessors();
    // Number of connections per Broker in Pulsar Client used in WebSocket proxy
    private int connectionsPerBroker = Runtime.getRuntime().availableProcessors();

    // When this parameter is not empty, unauthenticated users perform as anonymousUserRole
    private String anonymousUserRole = null;

    /***** --- TLS --- ****/
    // Enable TLS
    private boolean tlsEnabled = false;
    // Path for the TLS certificate file
    private String tlsCertificateFilePath;
    // Path for the TLS private key file
    private String tlsKeyFilePath;
    // Path for the trusted TLS certificate file
    private String tlsTrustCertsFilePath = "";
    // Accept untrusted TLS certificate from client
    private boolean tlsAllowInsecureConnection = false;
    // Specify whether Client certificates are required for TLS
    // Reject the Connection if the Client Certificate is not trusted.
    private boolean tlsRequireTrustedClientCertOnConnect = false;
    
    private Properties properties = new Properties();

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getServiceUrl() {
        return serviceUrl;
    }

    public void setServiceUrl(String serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    public String getServiceUrlTls() {
        return serviceUrlTls;
    }

    public void setServiceUrlTls(String serviceUrlTls) {
        this.serviceUrlTls = serviceUrlTls;
    }

    public String getBrokerServiceUrl() {
        return brokerServiceUrl;
    }

    public void setBrokerServiceUrl(String brokerServiceUrl) {
        this.brokerServiceUrl = brokerServiceUrl;
    }

    public String getBrokerServiceUrlTls() {
        return brokerServiceUrlTls;
    }

    public void setBrokerServiceUrlTls(String brokerServiceUrlTls) {
        this.brokerServiceUrlTls = brokerServiceUrlTls;
    }

    public String getStatusFilePath() {
        return statusFilePath;
    }

    public void setStatusFilePath(String statusFilePath) {
        this.statusFilePath = statusFilePath;
    }

    public String getGlobalZookeeperServers() {
        return globalZookeeperServers;
    }

    public void setGlobalZookeeperServers(String globalZookeeperServers) {
        this.globalZookeeperServers = globalZookeeperServers;
    }

    public long getZooKeeperSessionTimeoutMillis() {
        return zooKeeperSessionTimeoutMillis;
    }

    public void setZooKeeperSessionTimeoutMillis(long zooKeeperSessionTimeoutMillis) {
        this.zooKeeperSessionTimeoutMillis = zooKeeperSessionTimeoutMillis;
    }

    public int getWebServicePort() {
        return webServicePort;
    }

    public void setWebServicePort(int webServicePort) {
        this.webServicePort = webServicePort;
    }

    public int getWebServicePortTls() {
        return webServicePortTls;
    }

    public void setWebServicePortTls(int webServicePortTls) {
        this.webServicePortTls = webServicePortTls;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public boolean isAuthenticationEnabled() {
        return authenticationEnabled;
    }

    public void setAuthenticationEnabled(boolean authenticationEnabled) {
        this.authenticationEnabled = authenticationEnabled;
    }

    public void setAuthenticationProviders(Set<String> providersClassNames) {
        authenticationProviders = providersClassNames;
    }

    public Set<String> getAuthenticationProviders() {
        return authenticationProviders;
    }

    public boolean isAuthorizationEnabled() {
        return authorizationEnabled;
    }

    public void setAuthorizationEnabled(boolean authorizationEnabled) {
        this.authorizationEnabled = authorizationEnabled;
    }

    public String getAuthorizationProvider() {
        return authorizationProvider;
    }

    public void setAuthorizationProvider(String authorizationProvider) {
        this.authorizationProvider = authorizationProvider;
    }

    public boolean getAuthorizationAllowWildcardsMatching() {
        return authorizationAllowWildcardsMatching;
    }

    public void setAuthorizationAllowWildcardsMatching(boolean authorizationAllowWildcardsMatching) {
        this.authorizationAllowWildcardsMatching = authorizationAllowWildcardsMatching;
    }

    public Set<String> getSuperUserRoles() {
        return superUserRoles;
    }

    public void setSuperUserRoles(Set<String> superUserRoles) {
        this.superUserRoles = superUserRoles;
    }

    public String getBrokerClientAuthenticationPlugin() {
        return brokerClientAuthenticationPlugin;
    }

    public void setBrokerClientAuthenticationPlugin(String brokerClientAuthenticationPlugin) {
        this.brokerClientAuthenticationPlugin = brokerClientAuthenticationPlugin;
    }

    public String getBrokerClientTrustCertsFilePath() {
        return brokerClientTrustCertsFilePath;
    }

    public void setBrokerClientTrustCertsFilePath(String brokerClientTrustCertsFilePath) {
        this.brokerClientTrustCertsFilePath = brokerClientTrustCertsFilePath;
    }

    public String getBrokerClientAuthenticationParameters() {
        return brokerClientAuthenticationParameters;
    }

    public void setBrokerClientAuthenticationParameters(String brokerClientAuthenticationParameters) {
        this.brokerClientAuthenticationParameters = brokerClientAuthenticationParameters;
    }

    public int getNumIoThreads() {
        return numIoThreads;
    }

    public void setNumIoThreads(int numIoThreads) {
        this.numIoThreads = numIoThreads;
    }

    public int getConnectionsPerBroker() {
        return connectionsPerBroker;
    }

    public void setConnectionsPerBroker(int connectionsPerBroker) {
        this.connectionsPerBroker = connectionsPerBroker;
    }

    public String getAnonymousUserRole() {
        return anonymousUserRole;
    }

    public void setAnonymousUserRole(String anonymousUserRole) {
        this.anonymousUserRole = anonymousUserRole;
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    public void setTlsEnabled(boolean tlsEnabled) {
        this.tlsEnabled = tlsEnabled;
    }

    public String getTlsCertificateFilePath() {
        return tlsCertificateFilePath;
    }

    public void setTlsCertificateFilePath(String tlsCertificateFilePath) {
        this.tlsCertificateFilePath = tlsCertificateFilePath;
    }

    public String getTlsKeyFilePath() {
        return tlsKeyFilePath;
    }

    public void setTlsKeyFilePath(String tlsKeyFilePath) {
        this.tlsKeyFilePath = tlsKeyFilePath;
    }

    public String getTlsTrustCertsFilePath() {
        return tlsTrustCertsFilePath;
    }

    public void setTlsTrustCertsFilePath(String tlsTrustCertsFilePath) {
        this.tlsTrustCertsFilePath = tlsTrustCertsFilePath;
    }

    public boolean isTlsAllowInsecureConnection() {
        return tlsAllowInsecureConnection;
    }

    public void setTlsAllowInsecureConnection(boolean tlsAllowInsecureConnection) {
        this.tlsAllowInsecureConnection = tlsAllowInsecureConnection;
    }

    public Properties getProperties() {
        return properties;
    }

    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    public boolean getTlsRequireTrustedClientCertOnConnect() {
        return tlsRequireTrustedClientCertOnConnect;
    }

    public void setTlsRequireTrustedClientCertOnConnect(boolean tlsRequireTrustedClientCertOnConnect) {
        this.tlsRequireTrustedClientCertOnConnect = tlsRequireTrustedClientCertOnConnect;
    }
}
