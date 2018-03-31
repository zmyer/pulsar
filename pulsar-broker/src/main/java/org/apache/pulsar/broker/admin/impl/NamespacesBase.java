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
package org.apache.pulsar.broker.admin.impl;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.pulsar.broker.cache.ConfigurationCacheService.POLICIES;
import static org.apache.pulsar.broker.cache.LocalZooKeeperCacheService.LOCAL_POLICIES_ROOT;

import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;

import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.admin.AdminResource;
import org.apache.pulsar.broker.service.BrokerServiceException.SubscriptionBusyException;
import org.apache.pulsar.broker.service.Subscription;
import org.apache.pulsar.broker.service.Topic;
import org.apache.pulsar.broker.service.persistent.PersistentReplicator;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.broker.web.RestException;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.naming.NamespaceBundle;
import org.apache.pulsar.common.naming.NamespaceBundleFactory;
import org.apache.pulsar.common.naming.NamespaceBundles;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.BacklogQuota;
import org.apache.pulsar.common.policies.data.BacklogQuota.BacklogQuotaType;
import org.apache.pulsar.common.policies.data.BundlesData;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.DispatchRate;
import org.apache.pulsar.common.policies.data.PersistencePolicies;
import org.apache.pulsar.common.policies.data.Policies;
import org.apache.pulsar.common.policies.data.RetentionPolicies;
import org.apache.pulsar.common.policies.data.SubscriptionAuthMode;
import org.apache.pulsar.common.util.FutureUtil;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;

public abstract class NamespacesBase extends AdminResource {

    private static final long MAX_BUNDLES = ((long) 1) << 32;

    protected List<String> internalGetPropertyNamespaces(String property) {
        validateAdminAccessOnProperty(property);

        try {
            return getListOfNamespaces(property);
        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to get namespace list for propery: {} - Does not exist", clientAppId(), property);
            throw new RestException(Status.NOT_FOUND, "Property does not exist");
        } catch (Exception e) {
            log.error("[{}] Failed to get namespaces list: {}", clientAppId(), e);
            throw new RestException(e);
        }
    }

    protected void internalCreateNamespace(Policies policies) {
        validatePoliciesReadOnlyAccess();
        validateAdminAccessOnProperty(namespaceName.getProperty());

        validatePolicies(namespaceName, policies);

        try {
            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));

            zkCreateOptimistic(path(POLICIES, namespaceName.toString()), jsonMapper().writeValueAsBytes(policies));
            log.info("[{}] Created namespace {}", clientAppId(), namespaceName);
        } catch (KeeperException.NodeExistsException e) {
            log.warn("[{}] Failed to create namespace {} - already exists", clientAppId(), namespaceName);
            throw new RestException(Status.CONFLICT, "Namespace already exists");
        } catch (Exception e) {
            log.error("[{}] Failed to create namespace {}", clientAppId(), namespaceName, e);
            throw new RestException(e);
        }
    }

    @SuppressWarnings("deprecation")
    protected void internalDeleteNamespace(boolean authoritative) {
        validateAdminAccessOnProperty(namespaceName.getProperty());
        validatePoliciesReadOnlyAccess();

        // ensure that non-global namespace is directed to the correct cluster
        if (!namespaceName.isGlobal()) {
            validateClusterOwnership(namespaceName.getCluster());
        }

        Entry<Policies, Stat> policiesNode = null;
        Policies policies = null;

        // ensure the local cluster is the only cluster for the global namespace configuration
        try {
            policiesNode = policiesCache().getWithStat(path(POLICIES, namespaceName.toString())).orElseThrow(
                    () -> new RestException(Status.NOT_FOUND, "Namespace " + namespaceName + " does not exist."));

            policies = policiesNode.getKey();
            if (namespaceName.isGlobal()) {
                if (policies.replication_clusters.size() > 1) {
                    // There are still more than one clusters configured for the global namespace
                    throw new RestException(Status.PRECONDITION_FAILED, "Cannot delete the global namespace "
                            + namespaceName + ". There are still more than one replication clusters configured.");
                }
                if (policies.replication_clusters.size() == 1
                        && !policies.replication_clusters.contains(config().getClusterName())) {
                    // the only replication cluster is other cluster, redirect
                    String replCluster = policies.replication_clusters.get(0);
                    ClusterData replClusterData = clustersCache().get(AdminResource.path("clusters", replCluster))
                            .orElseThrow(() -> new RestException(Status.NOT_FOUND,
                                    "Cluster " + replCluster + " does not exist"));
                    URL replClusterUrl;
                    if (!config().isTlsEnabled() || !isRequestHttps()) {
                        replClusterUrl = new URL(replClusterData.getServiceUrl());
                    } else if (StringUtils.isNotBlank(replClusterData.getServiceUrlTls())) {
                        replClusterUrl = new URL(replClusterData.getServiceUrlTls());
                    } else {
                        throw new RestException(Status.PRECONDITION_FAILED,
                                "The replication cluster does not provide TLS encrypted service");
                    }
                    URI redirect = UriBuilder.fromUri(uri.getRequestUri()).host(replClusterUrl.getHost())
                            .port(replClusterUrl.getPort()).replaceQueryParam("authoritative", false).build();
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] Redirecting the rest call to {}: cluster={}", clientAppId(), redirect,
                                replCluster);
                    }
                    throw new WebApplicationException(Response.temporaryRedirect(redirect).build());
                }
            }
        } catch (WebApplicationException wae) {
            throw wae;
        } catch (Exception e) {
            throw new RestException(e);
        }

        boolean isEmpty;
        try {
            isEmpty = pulsar().getNamespaceService().getListOfTopics(namespaceName).isEmpty();
        } catch (Exception e) {
            throw new RestException(e);
        }

        if (!isEmpty) {
            log.debug("Found topics on namespace {}", namespaceName);
            throw new RestException(Status.CONFLICT, "Cannot delete non empty namespace");
        }

        // set the policies to deleted so that somebody else cannot acquire this namespace
        try {
            policies.deleted = true;
            globalZk().setData(path(POLICIES, namespaceName.toString()), jsonMapper().writeValueAsBytes(policies),
                    policiesNode.getValue().getVersion());
            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));
        } catch (Exception e) {
            log.error("[{}] Failed to delete namespace on global ZK {}", clientAppId(), namespaceName, e);
            throw new RestException(e);
        }

        // remove from owned namespace map and ephemeral node from ZK
        try {
            NamespaceBundles bundles = pulsar().getNamespaceService().getNamespaceBundleFactory()
                    .getBundles(namespaceName);
            for (NamespaceBundle bundle : bundles.getBundles()) {
                // check if the bundle is owned by any broker, if not then we do not need to delete the bundle
                if (pulsar().getNamespaceService().getOwner(bundle).isPresent()) {
                    pulsar().getAdminClient().namespaces().deleteNamespaceBundle(namespaceName.toString(),
                            bundle.getBundleRange());
                }
            }

            // we have successfully removed all the ownership for the namespace, the policies znode can be deleted now
            final String globalZkPolicyPath = path(POLICIES, namespaceName.toString());
            final String lcaolZkPolicyPath = joinPath(LOCAL_POLICIES_ROOT, namespaceName.toString());
            globalZk().delete(globalZkPolicyPath, -1);
            localZk().delete(lcaolZkPolicyPath, -1);
            policiesCache().invalidate(globalZkPolicyPath);
            localCacheService().policiesCache().invalidate(lcaolZkPolicyPath);
        } catch (PulsarAdminException cae) {
            throw new RestException(cae);
        } catch (Exception e) {
            log.error("[{}] Failed to remove owned namespace {}", clientAppId(), namespaceName, e);
            // avoid throwing exception in case of the second failure
        }

    }

    @SuppressWarnings("deprecation")
    protected void internalDeleteNamespaceBundle(String bundleRange, boolean authoritative) {
        validateAdminAccessOnProperty(namespaceName.getProperty());
        validatePoliciesReadOnlyAccess();

        // ensure that non-global namespace is directed to the correct cluster
        if (!namespaceName.isGlobal()) {
            validateClusterOwnership(namespaceName.getCluster());
        }

        Policies policies = getNamespacePolicies(namespaceName);
        // ensure the local cluster is the only cluster for the global namespace configuration
        try {
            if (namespaceName.isGlobal()) {
                if (policies.replication_clusters.size() > 1) {
                    // There are still more than one clusters configured for the global namespace
                    throw new RestException(Status.PRECONDITION_FAILED, "Cannot delete the global namespace "
                            + namespaceName + ". There are still more than one replication clusters configured.");
                }
                if (policies.replication_clusters.size() == 1
                        && !policies.replication_clusters.contains(config().getClusterName())) {
                    // the only replication cluster is other cluster, redirect
                    String replCluster = policies.replication_clusters.get(0);
                    ClusterData replClusterData = clustersCache().get(AdminResource.path("clusters", replCluster))
                            .orElseThrow(() -> new RestException(Status.NOT_FOUND,
                                    "Cluser " + replCluster + " does not exist"));
                    URL replClusterUrl;
                    if (!config().isTlsEnabled() || !isRequestHttps()) {
                        replClusterUrl = new URL(replClusterData.getServiceUrl());
                    } else if (StringUtils.isNotBlank(replClusterData.getServiceUrlTls())) {
                        replClusterUrl = new URL(replClusterData.getServiceUrlTls());
                    } else {
                        throw new RestException(Status.PRECONDITION_FAILED,
                                "The replication cluster does not provide TLS encrypted service");
                    }
                    URI redirect = UriBuilder.fromUri(uri.getRequestUri()).host(replClusterUrl.getHost())
                            .port(replClusterUrl.getPort()).replaceQueryParam("authoritative", false).build();
                    log.debug("[{}] Redirecting the rest call to {}: cluster={}", clientAppId(), redirect, replCluster);
                    throw new WebApplicationException(Response.temporaryRedirect(redirect).build());
                }
            }
        } catch (WebApplicationException wae) {
            throw wae;
        } catch (Exception e) {
            throw new RestException(e);
        }

        NamespaceBundle bundle = validateNamespaceBundleOwnership(namespaceName, policies.bundles, bundleRange,
                authoritative, true);
        try {
            List<String> topics = pulsar().getNamespaceService().getListOfTopics(namespaceName);
            for (String topic : topics) {
                NamespaceBundle topicBundle = (NamespaceBundle) pulsar().getNamespaceService()
                        .getBundle(TopicName.get(topic));
                if (bundle.equals(topicBundle)) {
                    throw new RestException(Status.CONFLICT, "Cannot delete non empty bundle");
                }
            }

            // remove from owned namespace map and ephemeral node from ZK
            pulsar().getNamespaceService().removeOwnedServiceUnit(bundle);
        } catch (WebApplicationException wae) {
            throw wae;
        } catch (Exception e) {
            log.error("[{}] Failed to remove namespace bundle {}/{}", clientAppId(), namespaceName.toString(),
                    bundleRange, e);
            throw new RestException(e);
        }
    }

    protected void internalGrantPermissionOnNamespace(String role, Set<AuthAction> actions) {
        validateAdminAccessOnProperty(namespaceName.getProperty());

        try {
            pulsar().getBrokerService().getAuthorizationService()
                    .grantPermissionAsync(namespaceName, actions, role, null/*additional auth-data json*/)
                    .get();
        } catch (InterruptedException e) {
            log.error("[{}] Failed to get permissions for namespace {}", clientAppId(), namespaceName, e);
            throw new RestException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException) {
                log.warn("[{}] Failed to set permissions for namespace {}: does not exist", clientAppId(),
                        namespaceName);
                throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
            } else if (e.getCause() instanceof IllegalStateException) {
                log.warn("[{}] Failed to set permissions for namespace {}: concurrent modification",
                        clientAppId(), namespaceName);
                throw new RestException(Status.CONFLICT, "Concurrent modification");
            } else {
                log.error("[{}] Failed to get permissions for namespace {}", clientAppId(), namespaceName, e);
                throw new RestException(e);
            }
        }
    }

    protected void internalRevokePermissionsOnNamespace(String role) {
        validateAdminAccessOnProperty(namespaceName.getProperty());
        validatePoliciesReadOnlyAccess();

        try {
            Stat nodeStat = new Stat();
            byte[] content = globalZk().getData(path(POLICIES, namespaceName.toString()), null, nodeStat);
            Policies policies = jsonMapper().readValue(content, Policies.class);
            policies.auth_policies.namespace_auth.remove(role);

            // Write back the new policies into zookeeper
            globalZk().setData(path(POLICIES, namespaceName.toString()), jsonMapper().writeValueAsBytes(policies),
                    nodeStat.getVersion());

            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));
            log.info("[{}] Successfully revoked access for role {} - namespace {}", clientAppId(), role, namespaceName);
        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to revoke permissions for namespace {}: does not exist", clientAppId(),
                    namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn("[{}] Failed to revoke permissions on namespace {}: concurrent modification", clientAppId(),
                    namespaceName);
            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (Exception e) {
            log.error("[{}] Failed to revoke permissions on namespace {}", clientAppId(), namespaceName, e);
            throw new RestException(e);
        }
    }

    protected List<String> internalGetNamespaceReplicationClusters() {
        if (!namespaceName.isGlobal()) {
            throw new RestException(Status.PRECONDITION_FAILED,
                    "Cannot get the replication clusters for a non-global namespace");
        }

        Policies policies = getNamespacePolicies(namespaceName);
        return policies.replication_clusters;
    }

    protected void internalSetNamespaceReplicationClusters(List<String> clusterIds) {
        validateAdminAccessOnProperty(namespaceName.getProperty());
        validatePoliciesReadOnlyAccess();

        Set<String> replicationClusterSet = Sets.newHashSet(clusterIds);
        if (!namespaceName.isGlobal()) {
            throw new RestException(Status.PRECONDITION_FAILED, "Cannot set replication on a non-global namespace");
        }

        if (replicationClusterSet.contains("global")) {
            throw new RestException(Status.PRECONDITION_FAILED,
                    "Cannot specify global in the list of replication clusters");
        }

        Set<String> clusters = clusters();
        for (String clusterId : replicationClusterSet) {
            if (!clusters.contains(clusterId)) {
                throw new RestException(Status.FORBIDDEN, "Invalid cluster id: " + clusterId);
            }
            validatePeerClusterConflict(clusterId, replicationClusterSet);
        }

        for (String clusterId : replicationClusterSet) {
            validateClusterForProperty(namespaceName.getProperty(), clusterId);
        }

        Entry<Policies, Stat> policiesNode = null;

        try {
            // Force to read the data s.t. the watch to the cache content is setup.
            policiesNode = policiesCache().getWithStat(path(POLICIES, namespaceName.toString())).orElseThrow(
                    () -> new RestException(Status.NOT_FOUND, "Namespace " + namespaceName + " does not exist"));
            policiesNode.getKey().replication_clusters = clusterIds;

            // Write back the new policies into zookeeper
            globalZk().setData(path(POLICIES, namespaceName.toString()),
                    jsonMapper().writeValueAsBytes(policiesNode.getKey()), policiesNode.getValue().getVersion());
            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));

            log.info("[{}] Successfully updated the replication clusters on namespace {}", clientAppId(),
                    namespaceName);
        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to update the replication clusters for namespace {}: does not exist", clientAppId(),
                    namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn(
                    "[{}] Failed to update the replication clusters on namespace {} expected policy node version={} : concurrent modification",
                    clientAppId(), namespaceName, policiesNode.getValue().getVersion());

            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (Exception e) {
            log.error("[{}] Failed to update the replication clusters on namespace {}", clientAppId(), namespaceName,
                    e);
            throw new RestException(e);
        }
    }

    protected void internalSetNamespaceMessageTTL(int messageTTL) {
        validateAdminAccessOnProperty(namespaceName.getProperty());
        validatePoliciesReadOnlyAccess();

        if (messageTTL < 0) {
            throw new RestException(Status.PRECONDITION_FAILED, "Invalid value for message TTL");
        }

        Entry<Policies, Stat> policiesNode = null;

        try {
            // Force to read the data s.t. the watch to the cache content is setup.
            policiesNode = policiesCache().getWithStat(path(POLICIES, namespaceName.toString())).orElseThrow(
                    () -> new RestException(Status.NOT_FOUND, "Namespace " + namespaceName + " does not exist"));
            policiesNode.getKey().message_ttl_in_seconds = messageTTL;

            // Write back the new policies into zookeeper
            globalZk().setData(path(POLICIES, namespaceName.toString()),
                    jsonMapper().writeValueAsBytes(policiesNode.getKey()), policiesNode.getValue().getVersion());
            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));

            log.info("[{}] Successfully updated the message TTL on namespace {}", clientAppId(), namespaceName);
        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to update the message TTL for namespace {}: does not exist", clientAppId(),
                    namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn(
                    "[{}] Failed to update the message TTL on namespace {} expected policy node version={} : concurrent modification",
                    clientAppId(), namespaceName, policiesNode.getValue().getVersion());

            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (Exception e) {
            log.error("[{}] Failed to update the message TTL on namespace {}", clientAppId(), namespaceName, e);
            throw new RestException(e);
        }
    }

    protected void internalModifyDeduplication(boolean enableDeduplication) {
        validateAdminAccessOnProperty(namespaceName.getProperty());
        validatePoliciesReadOnlyAccess();

        Entry<Policies, Stat> policiesNode = null;

        try {
            // Force to read the data s.t. the watch to the cache content is setup.
            policiesNode = policiesCache().getWithStat(path(POLICIES, namespaceName.toString())).orElseThrow(
                    () -> new RestException(Status.NOT_FOUND, "Namespace " + namespaceName + " does not exist"));
            policiesNode.getKey().deduplicationEnabled = enableDeduplication;

            // Write back the new policies into zookeeper
            globalZk().setData(path(POLICIES, namespaceName.toString()),
                    jsonMapper().writeValueAsBytes(policiesNode.getKey()), policiesNode.getValue().getVersion());
            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));

            log.info("[{}] Successfully {} on namespace {}", clientAppId(),
                    enableDeduplication ? "enabled" : "disabled", namespaceName);
        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to modify deplication status for namespace {}: does not exist", clientAppId(),
                    namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn(
                    "[{}] Failed to modify deplication status on namespace {} expected policy node version={} : concurrent modification",
                    clientAppId(), namespaceName, policiesNode.getValue().getVersion());

            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (Exception e) {
            log.error("[{}] Failed to modify deplication status on namespace {}", clientAppId(), namespaceName, e);
            throw new RestException(e);
        }
    }

    @SuppressWarnings("deprecation")
    protected void internalUnloadNamespace() {
        log.info("[{}] Unloading namespace {}", clientAppId());

        validateSuperUserAccess();

        if (namespaceName.isGlobal()) {
            // check cluster ownership for a given global namespace: redirect if peer-cluster owns it
            validateGlobalNamespaceOwnership(namespaceName);
        } else {
            validateClusterOwnership(namespaceName.getCluster());
            validateClusterForProperty(namespaceName.getProperty(), namespaceName.getCluster());
        }

        Policies policies = getNamespacePolicies(namespaceName);

        List<String> boundaries = policies.bundles.getBoundaries();
        for (int i = 0; i < boundaries.size() - 1; i++) {
            String bundle = String.format("%s_%s", boundaries.get(i), boundaries.get(i + 1));
            try {
                pulsar().getAdminClient().namespaces().unloadNamespaceBundle(namespaceName.toString(), bundle);
            } catch (PulsarServerException | PulsarAdminException e) {
                log.error(String.format("[%s] Failed to unload namespace %s", clientAppId(), namespaceName), e);
                throw new RestException(e);
            }
        }

        log.info("[{}] Successfully unloaded all the bundles in namespace {}/{}/{}", clientAppId(), namespaceName);
    }

    @SuppressWarnings("deprecation")
    public void internalUnloadNamespaceBundle(String bundleRange, boolean authoritative) {
        log.info("[{}] Unloading namespace bundle {}/{}", clientAppId(), namespaceName, bundleRange);

        validateSuperUserAccess();
        Policies policies = getNamespacePolicies(namespaceName);

        if (namespaceName.isGlobal()) {
            // check cluster ownership for a given global namespace: redirect if peer-cluster owns it
            validateGlobalNamespaceOwnership(namespaceName);
        } else {
            validateClusterOwnership(namespaceName.getCluster());
            validateClusterForProperty(namespaceName.getProperty(), namespaceName.getCluster());
        }

        validatePoliciesReadOnlyAccess();

        if (!isBundleOwnedByAnyBroker(namespaceName, policies.bundles, bundleRange)) {
            log.info("[{}] Namespace bundle is not owned by any broker {}/{}", clientAppId(), namespaceName,
                    bundleRange);
            return;
        }

        NamespaceBundle nsBundle = validateNamespaceBundleOwnership(namespaceName, policies.bundles, bundleRange,
                authoritative, true);
        try {
            pulsar().getNamespaceService().unloadNamespaceBundle(nsBundle);
            log.info("[{}] Successfully unloaded namespace bundle {}", clientAppId(), nsBundle.toString());
        } catch (Exception e) {
            log.error("[{}] Failed to unload namespace bundle {}/{}", clientAppId(), namespaceName, bundleRange, e);
            throw new RestException(e);
        }
    }

    @SuppressWarnings("deprecation")
    protected void internalSplitNamespaceBundle(String bundleRange, boolean authoritative, boolean unload) {
        log.info("[{}] Split namespace bundle {}/{}", clientAppId(), namespaceName, bundleRange);

        validateSuperUserAccess();
        Policies policies = getNamespacePolicies(namespaceName);

        if (namespaceName.isGlobal()) {
            // check cluster ownership for a given global namespace: redirect if peer-cluster owns it
            validateGlobalNamespaceOwnership(namespaceName);
        } else {
            validateClusterOwnership(namespaceName.getCluster());
            validateClusterForProperty(namespaceName.getProperty(), namespaceName.getCluster());
        }

        validatePoliciesReadOnlyAccess();
        NamespaceBundle nsBundle = validateNamespaceBundleOwnership(namespaceName, policies.bundles, bundleRange,
                authoritative, true);

        try {
            pulsar().getNamespaceService().splitAndOwnBundle(nsBundle, unload).get();
            log.info("[{}] Successfully split namespace bundle {}", clientAppId(), nsBundle.toString());
        } catch (IllegalArgumentException e) {
            log.error("[{}] Failed to split namespace bundle {}/{} due to {}", clientAppId(), namespaceName,
                    bundleRange, e.getMessage());
            throw new RestException(Status.PRECONDITION_FAILED, "Split bundle failed due to invalid request");
        } catch (Exception e) {
            log.error("[{}] Failed to split namespace bundle {}/{}", clientAppId(), namespaceName, bundleRange, e);
            throw new RestException(e);
        }
    }

    protected void internalSetDispatchRate(DispatchRate dispatchRate) {
        log.info("[{}] Set namespace dispatch-rate {}/{}", clientAppId(), namespaceName, dispatchRate);
        validateSuperUserAccess();

        Entry<Policies, Stat> policiesNode = null;

        try {
            final String path = path(POLICIES, namespaceName.toString());
            // Force to read the data s.t. the watch to the cache content is setup.
            policiesNode = policiesCache().getWithStat(path).orElseThrow(
                    () -> new RestException(Status.NOT_FOUND, "Namespace " + namespaceName + " does not exist"));
            policiesNode.getKey().clusterDispatchRate.put(pulsar().getConfiguration().getClusterName(), dispatchRate);

            // Write back the new policies into zookeeper
            globalZk().setData(path, jsonMapper().writeValueAsBytes(policiesNode.getKey()),
                    policiesNode.getValue().getVersion());
            policiesCache().invalidate(path);

            log.info("[{}] Successfully updated the dispatchRate for cluster on namespace {}", clientAppId(),
                    namespaceName);
        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to update the dispatchRate for cluster on namespace {}: does not exist",
                    clientAppId(), namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn(
                    "[{}] Failed to update the dispatchRate for cluster on namespace {} expected policy node version={} : concurrent modification",
                    clientAppId(), namespaceName, policiesNode.getValue().getVersion());

            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (Exception e) {
            log.error("[{}] Failed to update the dispatchRate for cluster on namespace {}", clientAppId(),
                    namespaceName, e);
            throw new RestException(e);
        }
    }

    protected DispatchRate internalGetDispatchRate() {
        validateAdminAccessOnProperty(namespaceName.getProperty());

        Policies policies = getNamespacePolicies(namespaceName);
        DispatchRate dispatchRate = policies.clusterDispatchRate.get(pulsar().getConfiguration().getClusterName());
        if (dispatchRate != null) {
            return dispatchRate;
        } else {
            throw new RestException(Status.NOT_FOUND,
                    "Dispatch-rate is not configured for cluster " + pulsar().getConfiguration().getClusterName());
        }
    }

    protected void internalSetBacklogQuota(BacklogQuotaType backlogQuotaType, BacklogQuota backlogQuota) {
        validateAdminAccessOnProperty(namespaceName.getProperty());
        validatePoliciesReadOnlyAccess();

        if (backlogQuotaType == null) {
            backlogQuotaType = BacklogQuotaType.destination_storage;
        }

        try {
            Stat nodeStat = new Stat();
            final String path = path(POLICIES, namespaceName.toString());
            byte[] content = globalZk().getData(path, null, nodeStat);
            Policies policies = jsonMapper().readValue(content, Policies.class);
            RetentionPolicies r = policies.retention_policies;
            if (r != null) {
                Policies p = new Policies();
                p.backlog_quota_map.put(backlogQuotaType, backlogQuota);
                if (!checkQuotas(p, r)) {
                    log.warn(
                            "[{}] Failed to update backlog configuration for namespace {}: conflicts with retention quota",
                            clientAppId(), namespaceName);
                    throw new RestException(Status.PRECONDITION_FAILED,
                            "Backlog Quota exceeds configured retention quota for namespace. Please increase retention quota and retry");
                }
            }
            policies.backlog_quota_map.put(backlogQuotaType, backlogQuota);
            globalZk().setData(path, jsonMapper().writeValueAsBytes(policies), nodeStat.getVersion());
            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));
            log.info("[{}] Successfully updated backlog quota map: namespace={}, map={}", clientAppId(), namespaceName,
                    jsonMapper().writeValueAsString(policies.backlog_quota_map));

        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to update backlog quota map for namespace {}: does not exist", clientAppId(),
                    namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn("[{}] Failed to update backlog quota map for namespace {}: concurrent modification", clientAppId(),
                    namespaceName);
            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (RestException pfe) {
            throw pfe;
        } catch (Exception e) {
            log.error("[{}] Failed to update backlog quota map for namespace {}", clientAppId(), namespaceName, e);
            throw new RestException(e);
        }
    }

    protected void internalRemoveBacklogQuota(BacklogQuotaType backlogQuotaType) {
        validateAdminAccessOnProperty(namespaceName.getProperty());
        validatePoliciesReadOnlyAccess();

        if (backlogQuotaType == null) {
            backlogQuotaType = BacklogQuotaType.destination_storage;
        }

        try {
            Stat nodeStat = new Stat();
            final String path = path(POLICIES, namespaceName.toString());
            byte[] content = globalZk().getData(path, null, nodeStat);
            Policies policies = jsonMapper().readValue(content, Policies.class);
            policies.backlog_quota_map.remove(backlogQuotaType);
            globalZk().setData(path, jsonMapper().writeValueAsBytes(policies), nodeStat.getVersion());
            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));
            log.info("[{}] Successfully removed backlog namespace={}, quota={}", clientAppId(), namespaceName,
                    backlogQuotaType);

        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to update backlog quota map for namespace {}: does not exist", clientAppId(),
                    namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn("[{}] Failed to update backlog quota map for namespace {}: concurrent modification", clientAppId(),
                    namespaceName);
            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (Exception e) {
            log.error("[{}] Failed to update backlog quota map for namespace {}", clientAppId(), namespaceName, e);
            throw new RestException(e);
        }
    }

    protected void internalSetRetention(RetentionPolicies retention) {
        validatePoliciesReadOnlyAccess();

        try {
            Stat nodeStat = new Stat();
            final String path = path(POLICIES, namespaceName.toString());
            byte[] content = globalZk().getData(path, null, nodeStat);
            Policies policies = jsonMapper().readValue(content, Policies.class);
            if (!checkQuotas(policies, retention)) {
                log.warn("[{}] Failed to update retention configuration for namespace {}: conflicts with backlog quota",
                        clientAppId(), namespaceName);
                throw new RestException(Status.PRECONDITION_FAILED,
                        "Retention Quota must exceed configured backlog quota for namespace.");
            }
            policies.retention_policies = retention;
            globalZk().setData(path, jsonMapper().writeValueAsBytes(policies), nodeStat.getVersion());
            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));
            log.info("[{}] Successfully updated retention configuration: namespace={}, map={}", clientAppId(),
                    namespaceName, jsonMapper().writeValueAsString(policies.retention_policies));

        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to update retention configuration for namespace {}: does not exist", clientAppId(),
                    namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn("[{}] Failed to update retention configuration for namespace {}: concurrent modification",
                    clientAppId(), namespaceName);
            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (RestException pfe) {
            throw pfe;
        } catch (Exception e) {
            log.error("[{}] Failed to update retention configuration for namespace {}", clientAppId(), namespaceName,
                    e);
            throw new RestException(e);
        }
    }

    protected void internalSetPersistence(PersistencePolicies persistence) {
        validatePoliciesReadOnlyAccess();
        validatePersistencePolicies(persistence);

        try {
            Stat nodeStat = new Stat();
            final String path = path(POLICIES, namespaceName.toString());
            byte[] content = globalZk().getData(path, null, nodeStat);
            Policies policies = jsonMapper().readValue(content, Policies.class);
            policies.persistence = persistence;
            globalZk().setData(path, jsonMapper().writeValueAsBytes(policies), nodeStat.getVersion());
            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));
            log.info("[{}] Successfully updated persistence configuration: namespace={}, map={}", clientAppId(),
                    namespaceName, jsonMapper().writeValueAsString(policies.persistence));

        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to update persistence configuration for namespace {}: does not exist", clientAppId(),
                    namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn("[{}] Failed to update persistence configuration for namespace {}: concurrent modification",
                    clientAppId(), namespaceName);
            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (Exception e) {
            log.error("[{}] Failed to update persistence configuration for namespace {}", clientAppId(), namespaceName,
                    e);
            throw new RestException(e);
        }
    }

    protected PersistencePolicies internalGetPersistence() {
        validateAdminAccessOnProperty(namespaceName.getProperty());

        Policies policies = getNamespacePolicies(namespaceName);
        if (policies.persistence == null) {
            return new PersistencePolicies(config().getManagedLedgerDefaultEnsembleSize(),
                    config().getManagedLedgerDefaultWriteQuorum(), config().getManagedLedgerDefaultAckQuorum(), 0.0d);
        } else {
            return policies.persistence;
        }
    }

    protected void internalClearNamespaceBacklog(boolean authoritative) {
        validateAdminAccessOnProperty(namespaceName.getProperty());

        try {
            NamespaceBundles bundles = pulsar().getNamespaceService().getNamespaceBundleFactory()
                    .getBundles(namespaceName);
            Exception exception = null;
            for (NamespaceBundle nsBundle : bundles.getBundles()) {
                try {
                    // check if the bundle is owned by any broker, if not then there is no backlog on this bundle to
                    // clear
                    if (pulsar().getNamespaceService().getOwner(nsBundle).isPresent()) {
                        // TODO: make this admin call asynchronous
                        pulsar().getAdminClient().namespaces().clearNamespaceBundleBacklog(namespaceName.toString(),
                                nsBundle.getBundleRange());
                    }
                } catch (Exception e) {
                    if (exception == null) {
                        exception = e;
                    }
                }
            }
            if (exception != null) {
                if (exception instanceof PulsarAdminException) {
                    throw new RestException((PulsarAdminException) exception);
                } else {
                    throw new RestException(exception.getCause());
                }
            }
        } catch (WebApplicationException wae) {
            throw wae;
        } catch (Exception e) {
            throw new RestException(e);
        }
        log.info("[{}] Successfully cleared backlog on all the bundles for namespace {}", clientAppId(), namespaceName);
    }

    @SuppressWarnings("deprecation")
    protected void internalClearNamespaceBundleBacklog(String bundleRange, boolean authoritative) {
        validateAdminAccessOnProperty(namespaceName.getProperty());

        Policies policies = getNamespacePolicies(namespaceName);

        if (namespaceName.isGlobal()) {
            // check cluster ownership for a given global namespace: redirect if peer-cluster owns it
            validateGlobalNamespaceOwnership(namespaceName);
        } else {
            validateClusterOwnership(namespaceName.getCluster());
            validateClusterForProperty(namespaceName.getProperty(), namespaceName.getCluster());
        }

        validateNamespaceBundleOwnership(namespaceName, policies.bundles, bundleRange, authoritative, true);

        clearBacklog(namespaceName, bundleRange, null);
        log.info("[{}] Successfully cleared backlog on namespace bundle {}/{}", clientAppId(), namespaceName,
                bundleRange);
    }

    protected void internalClearNamespaceBacklogForSubscription(String subscription, boolean authoritative) {
        validateAdminAccessOnProperty(namespaceName.getProperty());

        try {
            NamespaceBundles bundles = pulsar().getNamespaceService().getNamespaceBundleFactory()
                    .getBundles(namespaceName);
            Exception exception = null;
            for (NamespaceBundle nsBundle : bundles.getBundles()) {
                try {
                    // check if the bundle is owned by any broker, if not then there is no backlog on this bundle to
                    // clear
                    if (pulsar().getNamespaceService().getOwner(nsBundle).isPresent()) {
                        // TODO: make this admin call asynchronous
                        pulsar().getAdminClient().namespaces().clearNamespaceBundleBacklogForSubscription(
                                namespaceName.toString(), nsBundle.getBundleRange(), subscription);
                    }
                } catch (Exception e) {
                    if (exception == null) {
                        exception = e;
                    }
                }
            }
            if (exception != null) {
                if (exception instanceof PulsarAdminException) {
                    throw new RestException((PulsarAdminException) exception);
                } else {
                    throw new RestException(exception.getCause());
                }
            }
        } catch (WebApplicationException wae) {
            throw wae;
        } catch (Exception e) {
            throw new RestException(e);
        }
        log.info("[{}] Successfully cleared backlog for subscription {} on all the bundles for namespace {}",
                clientAppId(), subscription, namespaceName);
    }

    @SuppressWarnings("deprecation")
    protected void internalClearNamespaceBundleBacklogForSubscription(String subscription, String bundleRange,
            boolean authoritative) {
        validateAdminAccessOnProperty(namespaceName.getProperty());

        Policies policies = getNamespacePolicies(namespaceName);

        if (namespaceName.isGlobal()) {
            // check cluster ownership for a given global namespace: redirect if peer-cluster owns it
            validateGlobalNamespaceOwnership(namespaceName);
        } else {
            validateClusterOwnership(namespaceName.getCluster());
            validateClusterForProperty(namespaceName.getProperty(), namespaceName.getCluster());
        }

        validateNamespaceBundleOwnership(namespaceName, policies.bundles, bundleRange, authoritative, true);

        clearBacklog(namespaceName, bundleRange, subscription);
        log.info("[{}] Successfully cleared backlog for subscription {} on namespace bundle {}/{}", clientAppId(),
                subscription, namespaceName, bundleRange);
    }

    protected void internalUnsubscribeNamespace(String subscription, boolean authoritative) {
        validateAdminAccessOnProperty(namespaceName.getProperty());

        try {
            NamespaceBundles bundles = pulsar().getNamespaceService().getNamespaceBundleFactory()
                    .getBundles(namespaceName);
            Exception exception = null;
            for (NamespaceBundle nsBundle : bundles.getBundles()) {
                try {
                    // check if the bundle is owned by any broker, if not then there are no subscriptions
                    if (pulsar().getNamespaceService().getOwner(nsBundle).isPresent()) {
                        // TODO: make this admin call asynchronous
                        pulsar().getAdminClient().namespaces().unsubscribeNamespaceBundle(namespaceName.toString(),
                                nsBundle.getBundleRange(), subscription);
                    }
                } catch (Exception e) {
                    if (exception == null) {
                        exception = e;
                    }
                }
            }
            if (exception != null) {
                if (exception instanceof PulsarAdminException) {
                    throw new RestException((PulsarAdminException) exception);
                } else {
                    throw new RestException(exception.getCause());
                }
            }
        } catch (WebApplicationException wae) {
            throw wae;
        } catch (Exception e) {
            throw new RestException(e);
        }
        log.info("[{}] Successfully unsubscribed {} on all the bundles for namespace {}", clientAppId(), subscription,
                namespaceName);
    }

    @SuppressWarnings("deprecation")
    protected void internalUnsubscribeNamespaceBundle(String subscription, String bundleRange, boolean authoritative) {
        validateAdminAccessOnProperty(namespaceName.getProperty());

        Policies policies = getNamespacePolicies(namespaceName);

        if (namespaceName.isGlobal()) {
            // check cluster ownership for a given global namespace: redirect if peer-cluster owns it
            validateGlobalNamespaceOwnership(namespaceName);
        } else {
            validateClusterOwnership(namespaceName.getCluster());
            validateClusterForProperty(namespaceName.getProperty(), namespaceName.getCluster());
        }

        validateNamespaceBundleOwnership(namespaceName, policies.bundles, bundleRange, authoritative, true);

        unsubscribe(namespaceName, bundleRange, subscription);
        log.info("[{}] Successfully unsubscribed {} on namespace bundle {}/{}", clientAppId(), subscription,
                namespaceName, bundleRange);
    }

    protected void internalSetSubscriptionAuthMode(SubscriptionAuthMode subscriptionAuthMode) {
        validateAdminAccessOnProperty(namespaceName.getProperty());
        validatePoliciesReadOnlyAccess();

        if (subscriptionAuthMode == null) {
            subscriptionAuthMode = SubscriptionAuthMode.None;
        }

        try {
            Stat nodeStat = new Stat();
            final String path = path(POLICIES, namespaceName.toString());
            byte[] content = globalZk().getData(path, null, nodeStat);
            Policies policies = jsonMapper().readValue(content, Policies.class);
            policies.subscription_auth_mode = subscriptionAuthMode;
            globalZk().setData(path, jsonMapper().writeValueAsBytes(policies), nodeStat.getVersion());
            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));
            log.info("[{}] Successfully updated subscription auth mode: namespace={}, map={}", clientAppId(),
                    namespaceName, jsonMapper().writeValueAsString(policies.backlog_quota_map));

        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to update subscription auth mode for namespace {}: does not exist", clientAppId(),
                    namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn("[{}] Failed to update subscription auth mode for namespace {}/{}/{}: concurrent modification",
                    clientAppId(), namespaceName);
            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (RestException pfe) {
            throw pfe;
        } catch (Exception e) {
            log.error("[{}] Failed to update subscription auth mode for namespace {}/{}/{}", clientAppId(),
                    namespaceName, e);
            throw new RestException(e);
        }
    }

    protected void internalModifyEncryptionRequired(boolean encryptionRequired) {
        validateAdminAccessOnProperty(namespaceName.getProperty());
        validatePoliciesReadOnlyAccess();

        Entry<Policies, Stat> policiesNode = null;

        try {
            // Force to read the data s.t. the watch to the cache content is setup.
            policiesNode = policiesCache().getWithStat(path(POLICIES, namespaceName.toString())).orElseThrow(
                    () -> new RestException(Status.NOT_FOUND, "Namespace " + namespaceName + " does not exist"));
            policiesNode.getKey().encryption_required = encryptionRequired;

            // Write back the new policies into zookeeper
            globalZk().setData(path(POLICIES, namespaceName.toString()),
                    jsonMapper().writeValueAsBytes(policiesNode.getKey()), policiesNode.getValue().getVersion());
            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));

            log.info("[{}] Successfully {} on namespace {}", clientAppId(), encryptionRequired ? "true" : "false",
                    namespaceName);
        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to modify encryption required status for namespace {}: does not exist", clientAppId(),
                    namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn(
                    "[{}] Failed to modify encryption required status on namespace {} expected policy node version={} : concurrent modification",
                    clientAppId(), namespaceName, policiesNode.getValue().getVersion());

            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (Exception e) {
            log.error("[{}] Failed to modify encryption required status on namespace {}", clientAppId(), namespaceName,
                    e);
            throw new RestException(e);
        }
    }

    private void validatePersistencePolicies(PersistencePolicies persistence) {
        try {
            checkNotNull(persistence);
            final ServiceConfiguration config = pulsar().getConfiguration();
            checkArgument(persistence.getBookkeeperEnsemble() <= config.getManagedLedgerMaxEnsembleSize(),
                    "Bookkeeper-Ensemble must be <= %s", config.getManagedLedgerMaxEnsembleSize());
            checkArgument(persistence.getBookkeeperWriteQuorum() <= config.getManagedLedgerMaxWriteQuorum(),
                    "Bookkeeper-WriteQuorum must be <= %s", config.getManagedLedgerMaxWriteQuorum());
            checkArgument(persistence.getBookkeeperAckQuorum() <= config.getManagedLedgerMaxAckQuorum(),
                    "Bookkeeper-AckQuorum must be <= %s", config.getManagedLedgerMaxAckQuorum());
            checkArgument(
                    (persistence.getBookkeeperEnsemble() >= persistence.getBookkeeperWriteQuorum())
                            && (persistence.getBookkeeperWriteQuorum() >= persistence.getBookkeeperAckQuorum()),
                    "Bookkeeper Ensemble (%s) >= WriteQuorum (%s) >= AckQuoru (%s)",
                    persistence.getBookkeeperEnsemble(), persistence.getBookkeeperWriteQuorum(),
                    persistence.getBookkeeperAckQuorum());
        } catch (NullPointerException | IllegalArgumentException e) {
            throw new RestException(Status.PRECONDITION_FAILED, e.getMessage());
        }
    }

    protected RetentionPolicies internalGetRetention() {
        validateAdminAccessOnProperty(namespaceName.getProperty());

        Policies policies = getNamespacePolicies(namespaceName);
        if (policies.retention_policies == null) {
            return new RetentionPolicies(config().getDefaultRetentionTimeInMinutes(),
                    config().getDefaultRetentionSizeInMB());
        } else {
            return policies.retention_policies;
        }
    }

    private boolean checkQuotas(Policies policies, RetentionPolicies retention) {
        Map<BacklogQuota.BacklogQuotaType, BacklogQuota> backlog_quota_map = policies.backlog_quota_map;
        if (backlog_quota_map.isEmpty() || retention.getRetentionSizeInMB() == 0 || retention.getRetentionSizeInMB() == -1) {
            return true;
        }
        BacklogQuota quota = backlog_quota_map.get(BacklogQuotaType.destination_storage);
        if (quota == null) {
            quota = pulsar().getBrokerService().getBacklogQuotaManager().getDefaultQuota();
        }
        if (quota.getLimit() >= ((long) retention.getRetentionSizeInMB() * 1024 * 1024)) {
            return false;
        }
        return true;
    }

    private void clearBacklog(NamespaceName nsName, String bundleRange, String subscription) {
        try {
            List<Topic> topicList = pulsar().getBrokerService().getAllTopicsFromNamespaceBundle(nsName.toString(),
                    nsName.toString() + "/" + bundleRange);

            List<CompletableFuture<Void>> futures = Lists.newArrayList();
            if (subscription != null) {
                if (subscription.startsWith(pulsar().getConfiguration().getReplicatorPrefix())) {
                    subscription = PersistentReplicator.getRemoteCluster(subscription);
                }
                for (Topic topic : topicList) {
                    if (topic instanceof PersistentTopic) {
                        futures.add(((PersistentTopic) topic).clearBacklog(subscription));
                    }
                }
            } else {
                for (Topic topic : topicList) {
                    if (topic instanceof PersistentTopic) {
                        futures.add(((PersistentTopic) topic).clearBacklog());
                    }
                }
            }

            FutureUtil.waitForAll(futures).get();
        } catch (Exception e) {
            log.error("[{}] Failed to clear backlog for namespace {}/{}, subscription: {}", clientAppId(),
                    nsName.toString(), bundleRange, subscription, e);
            throw new RestException(e);
        }
    }

    private void unsubscribe(NamespaceName nsName, String bundleRange, String subscription) {
        try {
            List<Topic> topicList = pulsar().getBrokerService().getAllTopicsFromNamespaceBundle(nsName.toString(),
                    nsName.toString() + "/" + bundleRange);
            List<CompletableFuture<Void>> futures = Lists.newArrayList();
            if (subscription.startsWith(pulsar().getConfiguration().getReplicatorPrefix())) {
                throw new RestException(Status.PRECONDITION_FAILED, "Cannot unsubscribe a replication cursor");
            } else {
                for (Topic topic : topicList) {
                    Subscription sub = topic.getSubscription(subscription);
                    if (sub != null) {
                        futures.add(sub.delete());
                    }
                }
            }

            FutureUtil.waitForAll(futures).get();
        } catch (RestException re) {
            throw re;
        } catch (Exception e) {
            log.error("[{}] Failed to unsubscribe {} for namespace {}/{}", clientAppId(), subscription,
                    nsName.toString(), bundleRange, e);
            if (e.getCause() instanceof SubscriptionBusyException) {
                throw new RestException(Status.PRECONDITION_FAILED, "Subscription has active connected consumers");
            }
            throw new RestException(e.getCause());
        }
    }

    /**
     * It validates that peer-clusters can't coexist in replication-clusters
     *
     * @param clusterName:
     *            given cluster whose peer-clusters can't be present into replication-cluster list
     * @param clusters:
     *            replication-cluster list
     */
    private void validatePeerClusterConflict(String clusterName, Set<String> replicationClusters) {
        try {
            ClusterData clusterData = clustersCache().get(path("clusters", clusterName)).orElseThrow(
                    () -> new RestException(Status.PRECONDITION_FAILED, "Invalid replication cluster " + clusterName));
            Set<String> peerClusters = clusterData.getPeerClusterNames();
            if (peerClusters != null && !peerClusters.isEmpty()) {
                SetView<String> conflictPeerClusters = Sets.intersection(peerClusters, replicationClusters);
                if (!conflictPeerClusters.isEmpty()) {
                    log.warn("[{}] {}'s peer cluster can't be part of replication clusters {}", clientAppId(),
                            clusterName, conflictPeerClusters);
                    throw new RestException(Status.CONFLICT,
                            String.format("%s's peer-clusters %s can't be part of replication-clusters %s", clusterName,
                                    conflictPeerClusters, replicationClusters));
                }
            }
        } catch (RestException re) {
            throw re;
        } catch (Exception e) {
            log.warn("[{}] Failed to get cluster-data for {}", clientAppId(), clusterName, e);
        }
    }

    protected BundlesData validateBundlesData(BundlesData initialBundles) {
        SortedSet<String> partitions = new TreeSet<String>();
        for (String partition : initialBundles.getBoundaries()) {
            Long partBoundary = Long.decode(partition);
            partitions.add(String.format("0x%08x", partBoundary));
        }
        if (partitions.size() != initialBundles.getBoundaries().size()) {
            log.debug("Input bundles included repeated partition points. Ignored.");
        }
        try {
            NamespaceBundleFactory.validateFullRange(partitions);
        } catch (IllegalArgumentException iae) {
            throw new RestException(Status.BAD_REQUEST, "Input bundles do not cover the whole hash range. first:"
                    + partitions.first() + ", last:" + partitions.last());
        }
        List<String> bundles = Lists.newArrayList();
        bundles.addAll(partitions);
        return new BundlesData(bundles);
    }

    protected BundlesData getBundles(int numBundles) {
        if (numBundles <= 0 || numBundles > MAX_BUNDLES) {
            throw new RestException(Status.BAD_REQUEST,
                    "Invalid number of bundles. Number of numbles has to be in the range of (0, 2^32].");
        }
        Long maxVal = ((long) 1) << 32;
        Long segSize = maxVal / numBundles;
        List<String> partitions = Lists.newArrayList();
        partitions.add(String.format("0x%08x", 0l));
        Long curPartition = segSize;
        for (int i = 0; i < numBundles; i++) {
            if (i != numBundles - 1) {
                partitions.add(String.format("0x%08x", curPartition));
            } else {
                partitions.add(String.format("0x%08x", maxVal - 1));
            }
            curPartition += segSize;
        }
        return new BundlesData(partitions);
    }

    private void validatePolicies(NamespaceName ns, Policies policies) {
        // Validate cluster names and permissions
        policies.replication_clusters.forEach(cluster -> validateClusterForProperty(ns.getProperty(), cluster));

        if (policies.message_ttl_in_seconds < 0) {
            throw new RestException(Status.PRECONDITION_FAILED, "Invalid value for message TTL");
        }

        if (policies.bundles != null && policies.bundles.getNumBundles() > 0) {
            if (policies.bundles.getBoundaries() == null || policies.bundles.getBoundaries().size() == 0) {
                policies.bundles = getBundles(policies.bundles.getNumBundles());
            } else {
                policies.bundles = validateBundlesData(policies.bundles);
            }
        } else {
            int defaultNumberOfBundles = config().getDefaultNumberOfNamespaceBundles();
            policies.bundles = getBundles(defaultNumberOfBundles);
        }

        if (policies.persistence != null) {
            validatePersistencePolicies(policies.persistence);
        }
    }


    protected int internalGetMaxProducersPerTopic() {
        validateAdminAccessOnProperty(namespaceName.getProperty());
        return getNamespacePolicies(namespaceName).max_producers_per_topic;
    }

    protected void internalSetMaxProducersPerTopic(int maxProducersPerTopic) {
        validateSuperUserAccess();
        validatePoliciesReadOnlyAccess();

        try {
            Stat nodeStat = new Stat();
            final String path = path(POLICIES, namespaceName.toString());
            byte[] content = globalZk().getData(path, null, nodeStat);
            Policies policies = jsonMapper().readValue(content, Policies.class);
            if (maxProducersPerTopic < 0) {
                throw new RestException(Status.PRECONDITION_FAILED,
                        "maxProducersPerTopic must be 0 or more");
            }
            policies.max_producers_per_topic = maxProducersPerTopic;
            globalZk().setData(path, jsonMapper().writeValueAsBytes(policies), nodeStat.getVersion());
            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));
            log.info("[{}] Successfully updated maxProducersPerTopic configuration: namespace={}, value={}", clientAppId(),
                    namespaceName, policies.max_producers_per_topic);

        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to update maxProducersPerTopic configuration for namespace {}: does not exist", clientAppId(),
                    namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn("[{}] Failed to update maxProducersPerTopic configuration for namespace {}: concurrent modification",
                    clientAppId(), namespaceName);
            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (RestException pfe) {
            throw pfe;
        } catch (Exception e) {
            log.error("[{}] Failed to update maxProducersPerTopic configuration for namespace {}", clientAppId(), namespaceName,
                    e);
            throw new RestException(e);
        }
    }

    protected int internalGetMaxConsumersPerTopic() {
        validateAdminAccessOnProperty(namespaceName.getProperty());
        return getNamespacePolicies(namespaceName).max_consumers_per_topic;
    }

    protected void internalSetMaxConsumersPerTopic(int maxConsumersPerTopic) {
        validateSuperUserAccess();
        validatePoliciesReadOnlyAccess();

        try {
            Stat nodeStat = new Stat();
            final String path = path(POLICIES, namespaceName.toString());
            byte[] content = globalZk().getData(path, null, nodeStat);
            Policies policies = jsonMapper().readValue(content, Policies.class);
            if (maxConsumersPerTopic < 0) {
                throw new RestException(Status.PRECONDITION_FAILED,
                        "maxConsumersPerTopic must be 0 or more");
            }
            policies.max_consumers_per_topic = maxConsumersPerTopic;
            globalZk().setData(path, jsonMapper().writeValueAsBytes(policies), nodeStat.getVersion());
            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));
            log.info("[{}] Successfully updated maxConsumersPerTopic configuration: namespace={}, value={}", clientAppId(),
                    namespaceName, policies.max_consumers_per_topic);

        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to update maxConsumersPerTopic configuration for namespace {}: does not exist", clientAppId(),
                    namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn("[{}] Failed to update maxConsumersPerTopic configuration for namespace {}: concurrent modification",
                    clientAppId(), namespaceName);
            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (RestException pfe) {
            throw pfe;
        } catch (Exception e) {
            log.error("[{}] Failed to update maxConsumersPerTopic configuration for namespace {}", clientAppId(), namespaceName,
                    e);
            throw new RestException(e);
        }
    }

    protected int internalGetMaxConsumersPerSubscription() {
        validateAdminAccessOnProperty(namespaceName.getProperty());
        return getNamespacePolicies(namespaceName).max_consumers_per_subscription;
    }

    protected void internalSetMaxConsumersPerSubscription(int maxConsumersPerSubscription) {
        validateSuperUserAccess();
        validatePoliciesReadOnlyAccess();

        try {
            Stat nodeStat = new Stat();
            final String path = path(POLICIES, namespaceName.toString());
            byte[] content = globalZk().getData(path, null, nodeStat);
            Policies policies = jsonMapper().readValue(content, Policies.class);
            if (maxConsumersPerSubscription < 0) {
                throw new RestException(Status.PRECONDITION_FAILED,
                        "maxConsumersPerSubscription must be 0 or more");
            }
            policies.max_consumers_per_subscription = maxConsumersPerSubscription;
            globalZk().setData(path, jsonMapper().writeValueAsBytes(policies), nodeStat.getVersion());
            policiesCache().invalidate(path(POLICIES, namespaceName.toString()));
            log.info("[{}] Successfully updated maxConsumersPerSubscription configuration: namespace={}, value={}", clientAppId(),
                    namespaceName, policies.max_consumers_per_subscription);

        } catch (KeeperException.NoNodeException e) {
            log.warn("[{}] Failed to update maxConsumersPerSubscription configuration for namespace {}: does not exist", clientAppId(),
                    namespaceName);
            throw new RestException(Status.NOT_FOUND, "Namespace does not exist");
        } catch (KeeperException.BadVersionException e) {
            log.warn("[{}] Failed to update maxConsumersPerSubscription configuration for namespace {}: concurrent modification",
                    clientAppId(), namespaceName);
            throw new RestException(Status.CONFLICT, "Concurrent modification");
        } catch (RestException pfe) {
            throw pfe;
        } catch (Exception e) {
            log.error("[{}] Failed to update maxConsumersPerSubscription configuration for namespace {}", clientAppId(), namespaceName,
                    e);
            throw new RestException(e);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(NamespacesBase.class);
}
