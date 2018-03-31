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

import static org.mockito.Mockito.spy;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.naming.AuthenticationException;

import org.apache.bookkeeper.test.PortManager;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.authentication.AuthenticationDataSource;
import org.apache.pulsar.broker.authentication.AuthenticationProvider;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.AuthenticationDataProvider;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ProducerConsumerBase;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.PropertyAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class ProxyRolesEnforcementTest extends ProducerConsumerBase {
    private static final Logger log = LoggerFactory.getLogger(ProxyRolesEnforcementTest.class);

    public static class BasicAuthenticationData implements AuthenticationDataProvider {
        private String authParam;

        public BasicAuthenticationData(String authParam) {
            this.authParam = authParam;
        }

        public boolean hasDataFromCommand() {
            return true;
        }

        public String getCommandData() {
            return authParam;
        }

        public boolean hasDataForHttp() {
            return true;
        }

        @Override
        public Set<Entry<String, String>> getHttpHeaders() {
            Map<String, String> headers = new HashMap<>();
            headers.put("BasicAuthentication", authParam);
            return headers.entrySet();
        }
    }

    public static class BasicAuthentication implements Authentication {

        private String authParam;

        @Override
        public void close() throws IOException {
            // noop
        }

        @Override
        public String getAuthMethodName() {
            return "BasicAuthentication";
        }

        @Override
        public AuthenticationDataProvider getAuthData() throws PulsarClientException {
            try {
                return new BasicAuthenticationData(authParam);
            } catch (Exception e) {
                throw new PulsarClientException(e);
            }
        }

        @Override
        public void configure(Map<String, String> authParams) {
            this.authParam = authParams.get("authParam");
        }

        @Override
        public void start() throws PulsarClientException {
            // noop
        }
    }

    public static class BasicAuthenticationProvider implements AuthenticationProvider {

        @Override
        public void close() throws IOException {
        }

        @Override
        public void initialize(ServiceConfiguration config) throws IOException {
        }

        @Override
        public String getAuthMethodName() {
            return "BasicAuthentication";
        }

        @Override
        public String authenticate(AuthenticationDataSource authData) throws AuthenticationException {
            if (authData.hasDataFromCommand()) {
                return authData.getCommandData();
            } else if (authData.hasDataFromHttp()) {
                return authData.getHttpHeader("BasicAuthentication");
            }

            return null;
        }
    }

    private int webServicePort;
    private int servicePort;

    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        webServicePort = PortManager.nextFreePort();
        servicePort = PortManager.nextFreePort();
        conf.setAuthenticationEnabled(true);
        conf.setAuthorizationEnabled(true);
        conf.setTlsEnabled(false);
        conf.setBrokerClientAuthenticationPlugin(BasicAuthentication.class.getName());
        conf.setBrokerClientAuthenticationParameters("authParam:broker");

        Set<String> superUserRoles = new HashSet<String>();
        superUserRoles.add("admin");
        conf.setSuperUserRoles(superUserRoles);

        Set<String> providers = new HashSet<String>();
        providers.add(BasicAuthenticationProvider.class.getName());
        conf.setAuthenticationProviders(providers);

        conf.setClusterName("use");
        Set<String> proxyRoles = new HashSet<String>();
        proxyRoles.add("proxy");
        conf.setProxyRoles(proxyRoles);

        super.init();
    }

    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    @Test
    void testIncorrectRoles() throws Exception {
        log.info("-- Starting {} test --", methodName);

        // Step 1: Create Admin Client
        createAdminClient();
        final String proxyServiceUrl = "pulsar://localhost:" + servicePort;
        // create a client which connects to proxy and pass authData
        String namespaceName = "my-property/use/my-ns";
        String topicName = "persistent://my-property/use/my-ns/my-topic1";
        String subscriptionName = "my-subscriber-name";
        String clientAuthParams = "authParam:client";
        String proxyAuthParams = "authParam:proxy";

        admin.properties().createProperty("my-property",
                new PropertyAdmin(Lists.newArrayList("appid1", "appid2"), Sets.newHashSet("use")));
        admin.namespaces().createNamespace(namespaceName);

        admin.namespaces().grantPermissionOnNamespace(namespaceName, "proxy",
                Sets.newHashSet(AuthAction.consume, AuthAction.produce));
        admin.namespaces().grantPermissionOnNamespace(namespaceName, "client",
                Sets.newHashSet(AuthAction.consume, AuthAction.produce));

        // Step 2: Try to use proxy Client as a normal Client - expect exception
        PulsarClient proxyClient = createPulsarClient("pulsar://localhost:" + BROKER_PORT, proxyAuthParams);
        boolean exceptionOccured = false;
        try {
            proxyClient.newConsumer().topic(topicName).subscriptionName(subscriptionName).subscribe();
        } catch (Exception ex) {
            exceptionOccured = true;
        }
        Assert.assertTrue(exceptionOccured);

        // Step 3: Run Pulsar Proxy and pass proxy params as client params - expect exception
        ProxyConfiguration proxyConfig = new ProxyConfiguration();
        proxyConfig.setAuthenticationEnabled(true);

        proxyConfig.setServicePort(servicePort);
        proxyConfig.setWebServicePort(webServicePort);
        proxyConfig.setBrokerServiceURL("pulsar://localhost:" + BROKER_PORT);

        proxyConfig.setBrokerClientAuthenticationPlugin(BasicAuthentication.class.getName());
        proxyConfig.setBrokerClientAuthenticationParameters(proxyAuthParams);

        Set<String> providers = new HashSet<>();
        providers.add(BasicAuthenticationProvider.class.getName());
        proxyConfig.setAuthenticationProviders(providers);
        ProxyService proxyService = new ProxyService(proxyConfig);

        proxyService.start();
        proxyClient = createPulsarClient(proxyServiceUrl, proxyAuthParams);
        exceptionOccured = false;
        try {
            proxyClient.newConsumer().topic(topicName).subscriptionName(subscriptionName).subscribe();
        } catch (Exception ex) {
            exceptionOccured = true;
        }

        Assert.assertTrue(exceptionOccured);

        // Step 4: Pass correct client params
        proxyClient = createPulsarClient(proxyServiceUrl, clientAuthParams);
        proxyClient.newConsumer().topic(topicName).subscriptionName(subscriptionName).subscribe();
        proxyClient.close();
        proxyService.close();
    }

    private void createAdminClient() throws PulsarClientException {
        String adminAuthParams = "authParam:admin";
        org.apache.pulsar.client.api.ClientConfiguration clientConf = new org.apache.pulsar.client.api.ClientConfiguration();
        clientConf.setAuthentication(BasicAuthentication.class.getName(), adminAuthParams);

        admin = spy(new PulsarAdmin(brokerUrl, clientConf));
    }

    private PulsarClient createPulsarClient(String proxyServiceUrl, String authParams) throws PulsarClientException {
        return PulsarClient.builder().serviceUrl(proxyServiceUrl).authentication(BasicAuthentication.class.getName(),
                authParams).build();
    }
}
