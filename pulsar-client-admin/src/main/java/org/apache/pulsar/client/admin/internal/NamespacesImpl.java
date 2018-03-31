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
package org.apache.pulsar.client.admin.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;

import org.apache.pulsar.client.admin.Namespaces;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.BacklogQuota;
import org.apache.pulsar.common.policies.data.BacklogQuota.BacklogQuotaType;
import org.apache.pulsar.common.policies.data.BundlesData;
import org.apache.pulsar.common.policies.data.DispatchRate;
import org.apache.pulsar.common.policies.data.ErrorData;
import org.apache.pulsar.common.policies.data.PersistencePolicies;
import org.apache.pulsar.common.policies.data.Policies;
import org.apache.pulsar.common.policies.data.RetentionPolicies;
import org.apache.pulsar.common.policies.data.SubscriptionAuthMode;

public class NamespacesImpl extends BaseResource implements Namespaces {

    private final WebTarget adminNamespaces;
    private final WebTarget adminV2Namespaces;

    public NamespacesImpl(WebTarget web, Authentication auth) {
        super(auth);
        adminNamespaces = web.path("/admin/namespaces");
        adminV2Namespaces = web.path("/admin/v2/namespaces");
    }

    @Override
    public List<String> getNamespaces(String property) throws PulsarAdminException {
        try {
            WebTarget path = adminV2Namespaces.path(property);
            return request(path).get(new GenericType<List<String>>() {
            });
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public List<String> getNamespaces(String property, String cluster) throws PulsarAdminException {
        try {
            WebTarget path = adminNamespaces.path(property).path(cluster);
            return request(path).get(new GenericType<List<String>>() {
            });
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public List<String> getTopics(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "destinations");
            return request(path).get(new GenericType<List<String>>() {
            });
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public Policies getPolicies(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns);
            return request(path).get(Policies.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void createNamespace(String namespace, int numBundles) throws PulsarAdminException {
        createNamespace(namespace, new BundlesData(numBundles));
    }

    @Override
    public void createNamespace(String namespace, BundlesData bundlesData) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns);
            request(path).put(Entity.entity(bundlesData, MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void createNamespace(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns);
            request(path).put(Entity.entity("", MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void deleteNamespace(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns);
            request(path).delete(ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void deleteNamespaceBundle(String namespace, String bundleRange) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, bundleRange);
            request(path).delete(ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public Map<String, Set<AuthAction>> getPermissions(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "permissions");
            return request(path).get(new GenericType<Map<String, Set<AuthAction>>>() {});
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void grantPermissionOnNamespace(String namespace, String role, Set<AuthAction> actions)
            throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "permissions", role);
            request(path).post(Entity.entity(actions, MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void revokePermissionsOnNamespace(String namespace, String role) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "permissions", role);
            request(path).delete(ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public List<String> getNamespaceReplicationClusters(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "replication");
            return request(path).get(new GenericType<List<String>>() {});
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void setNamespaceReplicationClusters(String namespace, List<String> clusterIds) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "replication");
            request(path).post(Entity.entity(clusterIds, MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public int getNamespaceMessageTTL(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "messageTTL");
            return request(path).get(new GenericType<Integer>() {});
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void setNamespaceMessageTTL(String namespace, int ttlInSeconds) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "messageTTL");
            request(path).post(Entity.entity(ttlInSeconds, MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void setNamespaceAntiAffinityGroup(String namespace, String namespaceAntiAffinityGroup)
            throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "antiAffinity");
            request(path).post(Entity.entity(namespaceAntiAffinityGroup, MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public String getNamespaceAntiAffinityGroup(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "antiAffinity");
            return request(path).get(new GenericType<String>() {});
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public List<String> getAntiAffinityNamespaces(String property, String cluster, String namespaceAntiAffinityGroup)
            throws PulsarAdminException {
        try {
            WebTarget path = adminNamespaces.path(cluster).path("antiAffinity").path(namespaceAntiAffinityGroup);
            return request(path.queryParam("property", property)).get(new GenericType<List<String>>() {});
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void deleteNamespaceAntiAffinityGroup(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "antiAffinity");
            request(path).delete(ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void setDeduplicationStatus(String namespace, boolean enableDeduplication) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "deduplication");
            request(path).post(Entity.entity(enableDeduplication, MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public Map<BacklogQuotaType, BacklogQuota> getBacklogQuotaMap(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "backlogQuotaMap");
            return request(path).get(new GenericType<Map<BacklogQuotaType, BacklogQuota>>() {
            });
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void setBacklogQuota(String namespace, BacklogQuota backlogQuota) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "backlogQuota");
            request(path).post(Entity.entity(backlogQuota, MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void removeBacklogQuota(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "backlogQuota");
            request(path.queryParam("backlogQuotaType", BacklogQuotaType.destination_storage.toString()))
                    .delete(ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void setPersistence(String namespace, PersistencePolicies persistence) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "persistence");
            request(path).post(Entity.entity(persistence, MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public PersistencePolicies getPersistence(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "persistence");
            return request(path).get(PersistencePolicies.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void setRetention(String namespace, RetentionPolicies retention) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "retention");
            request(path).post(Entity.entity(retention, MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }

    }

    @Override
    public RetentionPolicies getRetention(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "retention");
            return request(path).get(RetentionPolicies.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void unload(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "unload");
            request(path).put(Entity.entity("", MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public String getReplicationConfigVersion(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "configversion");
            return request(path).get(String.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void unloadNamespaceBundle(String namespace, String bundle) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, bundle, "unload");
            request(path).put(Entity.entity("", MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void splitNamespaceBundle(String namespace, String bundle, boolean unloadSplitBundles)
            throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, bundle, "split");
            request(path.queryParam("unload", Boolean.toString(unloadSplitBundles)))
                    .put(Entity.entity("", MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void setDispatchRate(String namespace, DispatchRate dispatchRate) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "dispatchRate");
            request(path).post(Entity.entity(dispatchRate, MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public DispatchRate getDispatchRate(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "dispatchRate");
            return request(path).get(DispatchRate.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void clearNamespaceBacklog(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "clearBacklog");
            request(path).post(Entity.entity("", MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void clearNamespaceBacklogForSubscription(String namespace, String subscription)
            throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "clearBacklog", subscription);
            request(path).post(Entity.entity("", MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void clearNamespaceBundleBacklog(String namespace, String bundle) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, bundle, "clearBacklog");
            request(path).post(Entity.entity("", MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void clearNamespaceBundleBacklogForSubscription(String namespace, String bundle, String subscription)
            throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, bundle, "clearBacklog", subscription);
            request(path).post(Entity.entity("", MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void unsubscribeNamespace(String namespace, String subscription) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "unsubscribe", subscription);
            request(path).post(Entity.entity("", MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void unsubscribeNamespaceBundle(String namespace, String bundle, String subscription)
            throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, bundle, "unsubscribe", subscription);
            request(path).post(Entity.entity("", MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void setSubscriptionAuthMode(String namespace, SubscriptionAuthMode subscriptionAuthMode) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "subscriptionAuthMode");
            request(path).post(Entity.entity(subscriptionAuthMode, MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void setEncryptionRequiredStatus(String namespace, boolean encryptionRequired) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "encryptionRequired");
            request(path).post(Entity.entity(encryptionRequired, MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public int getMaxProducersPerTopic(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "maxProducersPerTopic");
            return request(path).get(Integer.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void setMaxProducersPerTopic(String namespace, int maxProducersPerTopic) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "maxProducersPerTopic");
            request(path).post(Entity.entity(maxProducersPerTopic, MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public int getMaxConsumersPerTopic(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "maxConsumersPerTopic");
            return request(path).get(Integer.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void setMaxConsumersPerTopic(String namespace, int maxConsumersPerTopic) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "maxConsumersPerTopic");
            request(path).post(Entity.entity(maxConsumersPerTopic, MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public int getMaxConsumersPerSubscription(String namespace) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "maxConsumersPerSubscription");
            return request(path).get(Integer.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    @Override
    public void setMaxConsumersPerSubscription(String namespace, int maxConsumersPerSubscription) throws PulsarAdminException {
        try {
            NamespaceName ns = NamespaceName.get(namespace);
            WebTarget path = namespacePath(ns, "maxConsumersPerSubscription");
            request(path).post(Entity.entity(maxConsumersPerSubscription, MediaType.APPLICATION_JSON), ErrorData.class);
        } catch (Exception e) {
            throw getApiException(e);
        }
    }

    private WebTarget namespacePath(NamespaceName namespace, String... parts) {
        final WebTarget base = namespace.isV2() ? adminV2Namespaces : adminNamespaces;
        WebTarget namespacePath = base.path(namespace.toString());
        namespacePath = WebTargets.addParts(namespacePath, parts);
        return namespacePath;
    }
}
