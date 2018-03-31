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
package org.apache.pulsar.broker.authorization;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.pulsar.broker.cache.ConfigurationCacheService.POLICIES;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.authentication.AuthenticationDataSource;
import org.apache.pulsar.broker.cache.ConfigurationCacheService;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.Policies;
import static org.apache.pulsar.common.util.ObjectMapperFactory.getThreadLocal;
import org.apache.pulsar.zookeeper.ZooKeeperCache;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default authorization provider that stores authorization policies under local-zookeeper.
 *
 */
public class PulsarAuthorizationProvider implements AuthorizationProvider {
    private static final Logger log = LoggerFactory.getLogger(PulsarAuthorizationProvider.class);

    public ServiceConfiguration conf;
    public ConfigurationCacheService configCache;
    private static final String POLICY_ROOT = "/admin/policies/";
    private static final String POLICIES_READONLY_FLAG_PATH = "/admin/flags/policies-readonly";

    public PulsarAuthorizationProvider() {
    }

    public PulsarAuthorizationProvider(ServiceConfiguration conf, ConfigurationCacheService configCache)
            throws IOException {
        initialize(conf, configCache);
    }

    @Override
    public void initialize(ServiceConfiguration conf, ConfigurationCacheService configCache) throws IOException {
        checkNotNull(conf, "ServiceConfiguration can't be null");
        checkNotNull(configCache, "ConfigurationCacheService can't be null");
        this.conf = conf;
        this.configCache = configCache;

    }

    /**
     * Check if the specified role has permission to send messages to the specified fully qualified topic name.
     *
     * @param topicName
     *            the fully qualified topic name associated with the topic.
     * @param role
     *            the app id used to send messages to the topic.
     */
    @Override
    public CompletableFuture<Boolean> canProduceAsync(TopicName topicName, String role,
            AuthenticationDataSource authenticationData) {
        return checkAuthorization(topicName, role, AuthAction.produce);
    }

    /**
     * Check if the specified role has permission to receive messages from the specified fully qualified topic
     * name.
     *
     * @param topicName
     *            the fully qualified topic name associated with the topic.
     * @param role
     *            the app id used to receive messages from the topic.
     * @param subscription
     *            the subscription name defined by the client
     */
    @Override
    public CompletableFuture<Boolean> canConsumeAsync(TopicName topicName, String role,
            AuthenticationDataSource authenticationData, String subscription) {
        CompletableFuture<Boolean> permissionFuture = new CompletableFuture<>();
        try {
            configCache.policiesCache().getAsync(POLICY_ROOT + topicName.getNamespace()).thenAccept(policies -> {
                if (!policies.isPresent()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Policies node couldn't be found for topic : {}", topicName);
                    }
                } else {
                    if (isNotBlank(subscription) && !isSuperUser(role)) {
                        switch (policies.get().subscription_auth_mode) {
                        case Prefix:
                            if (!subscription.startsWith(role)) {
                                PulsarServerException ex = new PulsarServerException(String.format(
                                        "Failed to create consumer - The subscription name needs to be prefixed by the authentication role, like %s-xxxx for topic: %s",
                                        role, topicName));
                                permissionFuture.completeExceptionally(ex);
                                return;
                            }
                            break;
                        default:
                            break;
                        }
                    }
                }
                checkAuthorization(topicName, role, AuthAction.consume).thenAccept(isAuthorized -> {
                    permissionFuture.complete(isAuthorized);
                });
            }).exceptionally(ex -> {
                log.warn("Client with Role - {} failed to get permissions for topic - {}. {}", role, topicName,
                        ex.getMessage());
                permissionFuture.completeExceptionally(ex);
                return null;
            });
        } catch (Exception e) {
            log.warn("Client  with Role - {} failed to get permissions for topic - {}. {}", role, topicName,
                    e.getMessage());
            permissionFuture.completeExceptionally(e);
        }
        return permissionFuture;
    }

    /**
     * Check whether the specified role can perform a lookup for the specified topic.
     *
     * For that the caller needs to have producer or consumer permission.
     *
     * @param topicName
     * @param role
     * @return
     * @throws Exception
     */
    @Override
    public CompletableFuture<Boolean> canLookupAsync(TopicName topicName, String role,
            AuthenticationDataSource authenticationData) {
        CompletableFuture<Boolean> finalResult = new CompletableFuture<Boolean>();
        canProduceAsync(topicName, role, authenticationData).whenComplete((produceAuthorized, ex) -> {
            if (ex == null) {
                if (produceAuthorized) {
                    finalResult.complete(produceAuthorized);
                    return;
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "Topic [{}] Role [{}] exception occured while trying to check Produce permissions. {}",
                            topicName.toString(), role, ex.getMessage());
                }
            }
            canConsumeAsync(topicName, role, authenticationData, null).whenComplete((consumeAuthorized, e) -> {
                if (e == null) {
                    if (consumeAuthorized) {
                        finalResult.complete(consumeAuthorized);
                        return;
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "Topic [{}] Role [{}] exception occured while trying to check Consume permissions. {}",
                                topicName.toString(), role, e.getMessage());

                    }
                    finalResult.completeExceptionally(e);
                    return;
                }
                finalResult.complete(false);
            });
        });
        return finalResult;
    }

    @Override
    public CompletableFuture<Void> grantPermissionAsync(TopicName topicName, Set<AuthAction> actions,
            String role, String authDataJson) {
        return grantPermissionAsync(topicName.getNamespaceObject(), actions, role, authDataJson);
    }

    @Override
    public CompletableFuture<Void> grantPermissionAsync(NamespaceName namespaceName, Set<AuthAction> actions,
            String role, String authDataJson) {
        CompletableFuture<Void> result = new CompletableFuture<>();

        try {
            validatePoliciesReadOnlyAccess();
        } catch (Exception e) {
            result.completeExceptionally(e);
        }

        ZooKeeper globalZk = configCache.getZooKeeper();
        final String property = namespaceName.getProperty();
        final String cluster = namespaceName.getCluster();
        final String namespace = namespaceName.getLocalName();
        final String policiesPath = String.format("/%s/%s/%s/%s/%s", "admin", POLICIES, property, cluster, namespace);

        try {
            Stat nodeStat = new Stat();
            byte[] content = globalZk.getData(policiesPath, null, nodeStat);
            Policies policies = getThreadLocal().readValue(content, Policies.class);
            policies.auth_policies.namespace_auth.put(role, actions);

            // Write back the new policies into zookeeper
            globalZk.setData(policiesPath, getThreadLocal().writeValueAsBytes(policies), nodeStat.getVersion());

            configCache.policiesCache().invalidate(policiesPath);

            log.info("[{}] Successfully granted access for role {}: {} - namespace {}/{}/{}", role, role, actions,
                    property, cluster, namespace);
            result.complete(null);
        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to set permissions for namespace {}/{}/{}: does not exist", role, property, cluster,
                    namespace);
            result.completeExceptionally(new IllegalArgumentException("Namespace does not exist" + namespace));
        } catch (KeeperException.BadVersionException e) {
            log.warn("[{}] Failed to set permissions for namespace {}/{}/{}: concurrent modification", role, property,
                    cluster, namespace);
            result.completeExceptionally(new IllegalStateException(
                    "Concurrent modification on zk path: " + policiesPath + ", " + e.getMessage()));
        } catch (Exception e) {
            log.error("[{}] Failed to get permissions for namespace {}/{}/{}", role, property, cluster, namespace, e);
            result.completeExceptionally(
                    new IllegalStateException("Failed to get permissions for namespace " + namespace));
        }

        return result;
    }

    private CompletableFuture<Boolean> checkAuthorization(TopicName topicName, String role, AuthAction action) {
        if (isSuperUser(role)) {
            return CompletableFuture.completedFuture(true);
        } else {
            return checkPermission(topicName, role, action)
                    .thenApply(isPermission -> isPermission && checkCluster(topicName));
        }
    }

    private boolean checkCluster(TopicName topicName) {
        if (topicName.isGlobal() || conf.getClusterName().equals(topicName.getCluster())) {
            return true;
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Topic [{}] does not belong to local cluster [{}]", topicName.toString(),
                        conf.getClusterName());
            }
            return false;
        }
    }

    public CompletableFuture<Boolean> checkPermission(TopicName topicName, String role, AuthAction action) {
        CompletableFuture<Boolean> permissionFuture = new CompletableFuture<>();
        try {
            configCache.policiesCache().getAsync(POLICY_ROOT + topicName.getNamespace()).thenAccept(policies -> {
                if (!policies.isPresent()) {
                    if (log.isDebugEnabled()) {
                        log.debug("Policies node couldn't be found for topic : {}", topicName);
                    }
                } else {
                    Map<String, Set<AuthAction>> namespaceRoles = policies.get().auth_policies.namespace_auth;
                    Set<AuthAction> namespaceActions = namespaceRoles.get(role);
                    if (namespaceActions != null && namespaceActions.contains(action)) {
                        // The role has namespace level permission
                        permissionFuture.complete(true);
                        return;
                    }

                    Map<String, Set<AuthAction>> topicRoles = policies.get().auth_policies.destination_auth
                            .get(topicName.toString());
                    if (topicRoles != null) {
                        // Topic has custom policy
                        Set<AuthAction> topicActions = topicRoles.get(role);
                        if (topicActions != null && topicActions.contains(action)) {
                            // The role has topic level permission
                            permissionFuture.complete(true);
                            return;
                        }
                    }

                    // Using wildcard
                    if (conf.getAuthorizationAllowWildcardsMatching()) {
                        if (checkWildcardPermission(role, action, namespaceRoles)) {
                            // The role has namespace level permission by wildcard match
                            permissionFuture.complete(true);
                            return;
                        }

                        if (topicRoles != null && checkWildcardPermission(role, action, topicRoles)) {
                            // The role has topic level permission by wildcard match
                            permissionFuture.complete(true);
                            return;
                        }
                    }
                }
                permissionFuture.complete(false);
            }).exceptionally(ex -> {
                log.warn("Client  with Role - {} failed to get permissions for topic - {}. {}", role, topicName,
                        ex.getMessage());
                permissionFuture.completeExceptionally(ex);
                return null;
            });
        } catch (Exception e) {
            log.warn("Client  with Role - {} failed to get permissions for topic - {}. {}", role, topicName,
                    e.getMessage());
            permissionFuture.completeExceptionally(e);
        }
        return permissionFuture;
    }

    private boolean checkWildcardPermission(String checkedRole, AuthAction checkedAction,
            Map<String, Set<AuthAction>> permissionMap) {
        for (Map.Entry<String, Set<AuthAction>> permissionData : permissionMap.entrySet()) {
            String permittedRole = permissionData.getKey();
            Set<AuthAction> permittedActions = permissionData.getValue();

            // Prefix match
            if (permittedRole.charAt(permittedRole.length() - 1) == '*'
                    && checkedRole.startsWith(permittedRole.substring(0, permittedRole.length() - 1))
                    && permittedActions.contains(checkedAction)) {
                return true;
            }

            // Suffix match
            if (permittedRole.charAt(0) == '*' && checkedRole.endsWith(permittedRole.substring(1))
                    && permittedActions.contains(checkedAction)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Super user roles are allowed to do anything, used for replication primarily
     *
     * @param role
     *            the app id used to receive messages from the topic.
     */
    public boolean isSuperUser(String role) {
        Set<String> superUserRoles = conf.getSuperUserRoles();
        return role != null && superUserRoles.contains(role) ? true : false;
    }

    @Override
    public void close() throws IOException {
        // No-op
    }

    private void validatePoliciesReadOnlyAccess() {
        boolean arePoliciesReadOnly = true;
        ZooKeeperCache globalZkCache = configCache.cache();

        try {
            arePoliciesReadOnly = globalZkCache.exists(POLICIES_READONLY_FLAG_PATH);
        } catch (Exception e) {
            log.warn("Unable to fetch contents of [{}] from global zookeeper", POLICIES_READONLY_FLAG_PATH, e);
            throw new IllegalStateException("Unable to fetch content from global zk");
        }

        if (arePoliciesReadOnly) {
            if (log.isDebugEnabled()) {
                log.debug("Policies are read-only. Broker cannot do read-write operations");
            }
            throw new IllegalStateException("policies are in readonly mode");
        } else {
            // Make sure the broker is connected to the global zookeeper before writing. If not, throw an exception.
            if (globalZkCache.getZooKeeper().getState() != States.CONNECTED) {
                if (log.isDebugEnabled()) {
                    log.debug("Broker is not connected to the global zookeeper");
                }
                throw new IllegalStateException("not connected woith global zookeeper");
            } else {
                // Do nothing, just log the message.
                log.debug("Broker is allowed to make read-write operations");
            }
        }
    }

}
