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
package org.apache.pulsar.broker.namespace;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.bookkeeper.mledger.util.SafeRun.safeRun;
import static org.apache.pulsar.broker.cache.LocalZooKeeperCacheService.LOCAL_POLICIES_ROOT;
import static org.apache.pulsar.broker.web.PulsarWebResource.joinPath;
import static org.apache.pulsar.common.naming.NamespaceBundleFactory.getBundlesData;
import static org.apache.pulsar.zookeeper.ZooKeeperCache.cacheTimeOutInSec;

import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.admin.AdminResource;
import org.apache.pulsar.broker.loadbalance.LoadManager;
import org.apache.pulsar.broker.loadbalance.ResourceUnit;
import org.apache.pulsar.broker.lookup.LookupResult;
import org.apache.pulsar.broker.service.BrokerServiceException.ServiceUnitNotReadyException;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.common.lookup.data.LookupData;
import org.apache.pulsar.common.naming.DestinationName;
import org.apache.pulsar.common.naming.NamespaceBundle;
import org.apache.pulsar.common.naming.NamespaceBundleFactory;
import org.apache.pulsar.common.naming.NamespaceBundles;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.ServiceUnitId;
import org.apache.pulsar.common.policies.NamespaceIsolationPolicy;
import org.apache.pulsar.common.policies.data.BrokerAssignment;
import org.apache.pulsar.common.policies.data.BundlesData;
import org.apache.pulsar.common.policies.data.LocalPolicies;
import org.apache.pulsar.common.policies.data.NamespaceOwnershipStatus;
import org.apache.pulsar.common.policies.impl.NamespaceIsolationPolicies;
import org.apache.pulsar.common.util.Codec;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.apache.pulsar.policies.data.loadbalancer.ServiceLookupData;
import org.apache.zookeeper.AsyncCallback.StatCallback;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.hash.Hashing;

/**
 * The <code>NamespaceService</code> provides resource ownership lookup as well as resource ownership claiming services
 * for the <code>PulsarService</code>.
 * <p/>
 * The <code>PulsarService</code> relies on this service for resource ownership operations.
 * <p/>
 * The focus of this phase is to bring up the system and be able to iterate and improve the services effectively.
 * <p/>
 *
 * @see org.apache.pulsar.broker.PulsarService
 */
public class NamespaceService {

    public enum AddressType {
        BROKER_URL, LOOKUP_URL
    }

    private static final Logger LOG = LoggerFactory.getLogger(NamespaceService.class);

    private final ServiceConfiguration config;

    private final AtomicReference<LoadManager> loadManager;

    private final PulsarService pulsar;

    private final OwnershipCache ownershipCache;

    private final NamespaceBundleFactory bundleFactory;

    private int uncountedNamespaces;

    private final String host;

    public static final String SLA_NAMESPACE_PROPERTY = "sla-monitor";
    public static final Pattern HEARTBEAT_NAMESPACE_PATTERN = Pattern.compile("pulsar/[^/]+/([^:]+:\\d+)");
    public static final Pattern SLA_NAMESPACE_PATTERN = Pattern.compile(SLA_NAMESPACE_PROPERTY + "/[^/]+/([^:]+:\\d+)");
    public static final String HEARTBEAT_NAMESPACE_FMT = "pulsar/%s/%s:%s";
    public static final String SLA_NAMESPACE_FMT = SLA_NAMESPACE_PROPERTY + "/%s/%s:%s";

    /**
     * Default constructor.
     *
     * @throws PulsarServerException
     */
    public NamespaceService(PulsarService pulsar) {
        this.pulsar = pulsar;
        host = pulsar.getAdvertisedAddress();
        this.config = pulsar.getConfiguration();
        this.loadManager = pulsar.getLoadManager();
        ServiceUnitZkUtils.initZK(pulsar.getLocalZkCache().getZooKeeper(), pulsar.getBrokerServiceUrl());
        this.bundleFactory = new NamespaceBundleFactory(pulsar, Hashing.crc32());
        this.ownershipCache = new OwnershipCache(pulsar, bundleFactory);
    }

    public CompletableFuture<Optional<LookupResult>> getBrokerServiceUrlAsync(DestinationName topic,
            boolean authoritative) {
        return getBundleAsync(topic)
                .thenCompose(bundle -> findBrokerServiceUrl(bundle, authoritative, false /* read-only */));
    }

    public CompletableFuture<NamespaceBundle> getBundleAsync(DestinationName topic) {
        return bundleFactory.getBundlesAsync(topic.getNamespaceObject())
                .thenApply(bundles -> bundles.findBundle(topic));
    }

    public NamespaceBundle getBundle(DestinationName destination) throws Exception {
        return bundleFactory.getBundles(destination.getNamespaceObject()).findBundle(destination);
    }

    public int getBundleCount(NamespaceName namespace) throws Exception {
        return bundleFactory.getBundles(namespace).size();
    }

    private NamespaceBundle getFullBundle(NamespaceName fqnn) throws Exception {
        return bundleFactory.getFullBundle(fqnn);
    }

    /**
     * Return the URL of the broker who's owning a particular service unit.
     *
     * If the service unit is not owned, return an empty optional
     */
	public Optional<URL> getWebServiceUrl(ServiceUnitId suName, boolean authoritative, boolean isRequestHttps, boolean readOnly)
			throws Exception {
        if (suName instanceof DestinationName) {
            DestinationName name = (DestinationName) suName;
            if (LOG.isDebugEnabled()) {
                LOG.debug("Getting web service URL of destination: {} - auth: {}", name, authoritative);
            }
            return this.internalGetWebServiceUrl(getBundle(name), authoritative, isRequestHttps, readOnly).get();
        }

        if (suName instanceof NamespaceName) {
            return this.internalGetWebServiceUrl(getFullBundle((NamespaceName) suName), authoritative, isRequestHttps, readOnly).get();
        }

        if (suName instanceof NamespaceBundle) {
            return this.internalGetWebServiceUrl((NamespaceBundle) suName, authoritative, isRequestHttps, readOnly).get();
        }

        throw new IllegalArgumentException("Unrecognized class of NamespaceBundle: " + suName.getClass().getName());
    }

    private CompletableFuture<Optional<URL>> internalGetWebServiceUrl(NamespaceBundle bundle, boolean authoritative,
            boolean isRequestHttps, boolean readOnly) {

        return findBrokerServiceUrl(bundle, authoritative, readOnly).thenApply(lookupResult -> {
            if (lookupResult.isPresent()) {
                try {
                    LookupData lookupData = lookupResult.get().getLookupData();
                    final String redirectUrl = isRequestHttps ? lookupData.getHttpUrlTls() : lookupData.getHttpUrl();
                    return Optional.of(new URL(redirectUrl));
                } catch (Exception e) {
                    // just log the exception, nothing else to do
                    LOG.warn("internalGetWebServiceUrl [{}]", e.getMessage(), e);
                }
            }
            return Optional.empty();
        });
    }

    /**
     * Register all the bootstrap name spaces including the heartbeat namespace
     *
     * @return
     * @throws PulsarServerException
     */
    public void registerBootstrapNamespaces() throws PulsarServerException {

        // ensure that we own the heartbeat namespace
        if (registerNamespace(getHeartbeatNamespace(host, config), true)) {
            this.uncountedNamespaces++;
            LOG.info("added heartbeat namespace name in local cache: ns={}", getHeartbeatNamespace(host, config));
        }

        // we may not need strict ownership checking for bootstrap names for now
        for (String namespace : config.getBootstrapNamespaces()) {
            if (registerNamespace(namespace, false)) {
                LOG.info("added bootstrap namespace name in local cache: ns={}", namespace);
            }
        }
    }

    /**
     * Tried to registers a namespace to this instance
     *
     * @param namespace
     * @param ensureOwned
     * @return
     * @throws PulsarServerException
     * @throws Exception
     */
    private boolean registerNamespace(String namespace, boolean ensureOwned) throws PulsarServerException {

        String myUrl = pulsar.getBrokerServiceUrl();

        try {
            NamespaceName nsname = NamespaceName.get(namespace);

            String otherUrl = null;
            NamespaceBundle nsFullBundle = null;

            // all pre-registered namespace is assumed to have bundles disabled
            nsFullBundle = bundleFactory.getFullBundle(nsname);
            // v2 namespace will always use full bundle object
            otherUrl = ownershipCache.tryAcquiringOwnership(nsFullBundle).get().getNativeUrl();

            if (myUrl.equals(otherUrl)) {
                if (nsFullBundle != null) {
                    // preload heartbeat namespace
                    pulsar.loadNamespaceDestinations(nsFullBundle);
                }
                return true;
            }

            String msg = String.format("namespace already owned by other broker : ns=%s expected=%s actual=%s",
                    namespace, myUrl, otherUrl);

            // ignore if not be owned for now
            if (!ensureOwned) {
                LOG.info(msg);
                return false;
            }

            // should not happen
            throw new IllegalStateException(msg);
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            throw new PulsarServerException(e);
        }
    }

    /**
     * Main internal method to lookup and setup ownership of service unit to a broker
     *
     * @param bundle
     * @param authoritative
     * @param readOnly
     * @return
     * @throws PulsarServerException
     */
    private CompletableFuture<Optional<LookupResult>> findBrokerServiceUrl(NamespaceBundle bundle, boolean authoritative,
            boolean readOnly) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("findBrokerServiceUrl: {} - read-only: {}", bundle, readOnly);
        }

        CompletableFuture<Optional<LookupResult>> future = new CompletableFuture<>();

        // First check if we or someone else already owns the bundle
        ownershipCache.getOwnerAsync(bundle).thenAccept(nsData -> {
            if (!nsData.isPresent()) {
                // No one owns this bundle

                if (readOnly) {
                    // Do not attempt to acquire ownership
                    future.complete(Optional.empty());
                } else {
                    // Now, no one owns the namespace yet. Hence, we will try to dynamically assign it
                    pulsar.getExecutor().execute(() -> {
                        searchForCandidateBroker(bundle, future, authoritative);
                    });
                }
            } else if (nsData.get().isDisabled()) {
                future.completeExceptionally(
                        new IllegalStateException(String.format("Namespace bundle %s is being unloaded", bundle)));
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Namespace bundle {} already owned by {} ", bundle, nsData);
                }
                future.complete(Optional.of(new LookupResult(nsData.get())));
            }
        }).exceptionally(exception -> {
            LOG.warn("Failed to check owner for bundle {}: {}", bundle, exception.getMessage(), exception);
            future.completeExceptionally(exception);
            return null;
        });

        return future;
    }

    private void searchForCandidateBroker(NamespaceBundle bundle,
            CompletableFuture<Optional<LookupResult>> lookupFuture, boolean authoritative) {
        String candidateBroker = null;
        try {
            // check if this is Heartbeat or SLAMonitor namespace
            candidateBroker = checkHeartbeatNamespace(bundle);
            if (candidateBroker == null) {
                String broker = getSLAMonitorBrokerName(bundle);
                // checking if the broker is up and running
                if (broker != null && isBrokerActive(broker)) {
                    candidateBroker = broker;
                }
            }

            if (candidateBroker == null) {
                if (!this.loadManager.get().isCentralized() || pulsar.getLeaderElectionService().isLeader()) {
                    Optional<String> availableBroker = getLeastLoadedFromLoadManager(bundle);
                    if (!availableBroker.isPresent()) {
                        lookupFuture.complete(Optional.empty());
                        return;
                    }
                    candidateBroker = availableBroker.get();
                } else {
                    if (authoritative) {
                        // leader broker already assigned the current broker as owner
                        candidateBroker = pulsar.getWebServiceAddress();
                    } else {
                        // forward to leader broker to make assignment
                        candidateBroker = pulsar.getLeaderElectionService().getCurrentLeader().getServiceUrl();
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Error when searching for candidate broker to acquire {}: {}", bundle, e.getMessage(), e);
            lookupFuture.completeExceptionally(e);
            return;
        }

        try {
            checkNotNull(candidateBroker);

            if (pulsar.getWebServiceAddress().equals(candidateBroker)) {
                // Load manager decided that the local broker should try to become the owner
                ownershipCache.tryAcquiringOwnership(bundle).thenAccept(ownerInfo -> {
                    if (ownerInfo.isDisabled()) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Namespace bundle {} is currently being unloaded", bundle);
                        }
                        lookupFuture.completeExceptionally(new IllegalStateException(
                                String.format("Namespace bundle %s is currently being unloaded", bundle)));
                    } else {
                        // Found owner for the namespace bundle

                        // Schedule the task to pre-load destinations
                        pulsar.loadNamespaceDestinations(bundle);

                        lookupFuture.complete(Optional.of(new LookupResult(ownerInfo)));
                    }
                }).exceptionally(exception -> {
                    LOG.warn("Failed to acquire ownership for namespace bundle {}: ", bundle, exception.getMessage(),
                            exception);
                    lookupFuture.completeExceptionally(new PulsarServerException(
                            "Failed to acquire ownership for namespace bundle " + bundle, exception));
                    return null;
                });

            } else {
                // Load managed decider some other broker should try to acquire ownership

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Redirecting to broker {} to acquire ownership of bundle {}", candidateBroker, bundle);
                }

                // Now setting the redirect url
                createLookupResult(candidateBroker)
                        .thenAccept(lookupResult -> lookupFuture.complete(Optional.of(lookupResult)))
                        .exceptionally(ex -> {
                            lookupFuture.completeExceptionally(ex);
                            return null;
                        });

            }
        } catch (Exception e) {
            LOG.warn("Error in trying to acquire namespace bundle ownership for {}: {}", bundle, e.getMessage(), e);
            lookupFuture.completeExceptionally(e);
        }
    }

    protected CompletableFuture<LookupResult> createLookupResult(String candidateBroker) throws Exception {

        CompletableFuture<LookupResult> lookupFuture = new CompletableFuture<>();
        try {
            checkArgument(StringUtils.isNotBlank(candidateBroker), "Lookup broker can't be null " + candidateBroker);
            URI uri = new URI(candidateBroker);
            String path = String.format("%s/%s:%s", LoadManager.LOADBALANCE_BROKERS_ROOT, uri.getHost(),
                    uri.getPort());
            pulsar.getLocalZkCache().getDataAsync(path, pulsar.getLoadManager().get().getLoadReportDeserializer()).thenAccept(reportData -> {
                if (reportData.isPresent()) {
                    ServiceLookupData lookupData = reportData.get();
                    lookupFuture.complete(new LookupResult(lookupData.getWebServiceUrl(),
                            lookupData.getWebServiceUrlTls(), lookupData.getPulsarServiceUrl(),
                            lookupData.getPulsarServiceUrlTls()));
                } else {
                    lookupFuture.completeExceptionally(new KeeperException.NoNodeException(path));
                }
            }).exceptionally(ex -> {
                lookupFuture.completeExceptionally(ex);
                return null;
            });
        } catch (Exception e) {
            lookupFuture.completeExceptionally(e);
        }
        return lookupFuture;
    }

    private boolean isBrokerActive(String candidateBroker) throws KeeperException, InterruptedException {
        Set<String> activeNativeBrokers = pulsar.getLocalZkCache().getChildren(LoadManager.LOADBALANCE_BROKERS_ROOT);

        for (String brokerHostPort : activeNativeBrokers) {
            if (candidateBroker.equals("http://" + brokerHostPort)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Broker {} found for SLA Monitoring Namespace", brokerHostPort);
                }
                return true;
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Broker not found for SLA Monitoring Namespace {}",
                    candidateBroker + ":" + config.getWebServicePort());
        }
        return false;
    }

    /**
     * Helper function to encapsulate the logic to invoke between old and new load manager
     *
     * @return
     * @throws Exception
     */
    private Optional<String> getLeastLoadedFromLoadManager(ServiceUnitId serviceUnit) throws Exception {
        ResourceUnit leastLoadedBroker = loadManager.get().getLeastLoaded(serviceUnit);
        if (leastLoadedBroker != null) {
            String lookupAddress = leastLoadedBroker.getResourceId();
            if (LOG.isDebugEnabled()) {
                LOG.debug("{} : redirecting to the least loaded broker, lookup address={}",
                        pulsar.getWebServiceAddress(), lookupAddress);
            }
            return Optional.of(lookupAddress);
        } else {
            LOG.warn("No broker is available for {}", serviceUnit);
            return Optional.empty();
        }
    }

    public void unloadNamespace(NamespaceName ns) throws Exception {
        NamespaceBundle nsFullBundle = getFullBundle(ns);
        unloadNamespaceBundle(nsFullBundle);
    }

    public void unloadNamespaceBundle(NamespaceBundle bundle) throws Exception {
        checkNotNull(ownershipCache.getOwnedBundle(bundle)).handleUnloadRequest(pulsar);
    }

    public Map<String, NamespaceOwnershipStatus> getOwnedNameSpacesStatus() throws Exception {
        NamespaceIsolationPolicies nsIsolationPolicies = this.getLocalNamespaceIsolationPolicies();
        Map<String, NamespaceOwnershipStatus> ownedNsStatus = new HashMap<String, NamespaceOwnershipStatus>();
        for (OwnedBundle nsObj : this.ownershipCache.getOwnedBundles().values()) {
            NamespaceOwnershipStatus nsStatus = this.getNamespaceOwnershipStatus(nsObj,
                    nsIsolationPolicies.getPolicyByNamespace(nsObj.getNamespaceBundle().getNamespaceObject()));
            ownedNsStatus.put(nsObj.getNamespaceBundle().toString(), nsStatus);
        }

        return ownedNsStatus;
    }

    private NamespaceOwnershipStatus getNamespaceOwnershipStatus(OwnedBundle nsObj,
            NamespaceIsolationPolicy nsIsolationPolicy) {
        NamespaceOwnershipStatus nsOwnedStatus = new NamespaceOwnershipStatus(BrokerAssignment.shared, false,
                nsObj.isActive());
        if (nsIsolationPolicy == null) {
            // no matching policy found, this namespace must be an uncontrolled one and using shared broker
            return nsOwnedStatus;
        }
        // found corresponding policy, set the status to controlled
        nsOwnedStatus.is_controlled = true;
        if (nsIsolationPolicy.isPrimaryBroker(pulsar.getAdvertisedAddress())) {
            nsOwnedStatus.broker_assignment = BrokerAssignment.primary;
        } else if (nsIsolationPolicy.isSecondaryBroker(pulsar.getAdvertisedAddress())) {
            nsOwnedStatus.broker_assignment = BrokerAssignment.secondary;
        }

        return nsOwnedStatus;
    }

    private NamespaceIsolationPolicies getLocalNamespaceIsolationPolicies() throws Exception {
        String localCluster = pulsar.getConfiguration().getClusterName();
        return pulsar.getConfigurationCache().namespaceIsolationPoliciesCache()
                .get(AdminResource.path("clusters", localCluster, "namespaceIsolationPolicies")).orElseGet(() -> {
                    // the namespace isolation policies are empty/undefined = an empty object
                    return new NamespaceIsolationPolicies();
                });
    }

    public boolean isNamespaceBundleDisabled(NamespaceBundle bundle) throws Exception {
        try {
            // Does ZooKeeper says that the namespace is disabled?
            CompletableFuture<Optional<NamespaceEphemeralData>> nsDataFuture = ownershipCache.getOwnerAsync(bundle);
            if (nsDataFuture != null) {
                Optional<NamespaceEphemeralData> nsData = nsDataFuture.getNow(null);
                if (nsData != null && nsData.isPresent()) {
                    return nsData.get().isDisabled();
                } else {
                    return false;
                }
            } else {
                // if namespace is not owned, it is not considered disabled
                return false;
            }
        } catch (Exception e) {
            LOG.warn("Exception in getting ownership info for service unit {}: {}", bundle, e.getMessage(), e);
        }

        return false;
    }

    /**
     * 1. split the given bundle into two bundles 2. assign ownership of both the bundles to current broker 3. update
     * policies with newly created bundles into LocalZK 4. disable original bundle and refresh the cache
     *
     * @param bundle
     * @return
     * @throws Exception
     */
    public CompletableFuture<Void> splitAndOwnBundle(NamespaceBundle bundle, final boolean unload) throws Exception {

        final CompletableFuture<Void> unloadFuture = new CompletableFuture<>();

        final Pair<NamespaceBundles, List<NamespaceBundle>> splittedBundles = bundleFactory.splitBundles(bundle,
                2 /* by default split into 2 */);
        if (splittedBundles != null) {
            checkNotNull(splittedBundles.getLeft());
            checkNotNull(splittedBundles.getRight());
            checkArgument(splittedBundles.getRight().size() == 2, "bundle has to be split in two bundles");
            NamespaceName nsname = bundle.getNamespaceObject();
            try {
                // take ownership of newly split bundles
                for (NamespaceBundle sBundle : splittedBundles.getRight()) {
                    checkNotNull(ownershipCache.tryAcquiringOwnership(sBundle));
                }
                updateNamespaceBundles(nsname, splittedBundles.getLeft(),
                        (rc, path, zkCtx, stat) -> pulsar.getOrderedExecutor().submit(safeRun(() -> {
                            if (rc == KeeperException.Code.OK.intValue()) {
                                try {
                                    // disable old bundle in memory
                                    getOwnershipCache().updateBundleState(bundle, false);
                                    // invalidate cache as zookeeper has new split
                                    // namespace bundle
                                    bundleFactory.invalidateBundleCache(nsname);
                                    // update bundled_topic cache for load-report-generation
                                    pulsar.getBrokerService().refreshTopicToStatsMaps(bundle);
                                    loadManager.get().setLoadReportForceUpdateFlag();
                                    unloadFuture.complete(null);
                                } catch (Exception e) {
                                    String msg1 = format(
                                            "failed to disable bundle %s under namespace [%s] with error %s",
                                            nsname.toString(), bundle.toString(), e.getMessage());
                                    LOG.warn(msg1, e);
                                    unloadFuture.completeExceptionally(new ServiceUnitNotReadyException(msg1));
                                }
                            } else {
                                String msg2 = format("failed to update namespace [%s] policies due to %s",
                                        nsname.toString(),
                                        KeeperException.create(KeeperException.Code.get(rc)).getMessage());
                                LOG.warn(msg2);
                                unloadFuture.completeExceptionally(new ServiceUnitNotReadyException(msg2));
                            }
                        })));
            } catch (Exception e) {
                String msg = format("failed to aquire ownership of split bundle for namespace [%s], %s",
                        nsname.toString(), e.getMessage());
                LOG.warn(msg, e);
                unloadFuture.completeExceptionally(new ServiceUnitNotReadyException(msg));
            }

        } else {
            String msg = format("bundle %s not found under namespace", bundle.toString());
            unloadFuture.completeExceptionally(new ServiceUnitNotReadyException(msg));
        }
        
        return unloadFuture.thenApply(res -> {
            if (!unload) {
                return null;
            }
            // unload new split bundles
            splittedBundles.getRight().forEach(splitBundle -> {
                try {
                    unloadNamespaceBundle(splitBundle);
                } catch (Exception e) {
                    LOG.warn("Failed to unload split bundle {}", splitBundle, e);
                    throw new RuntimeException("Failed to unload split bundle " + splitBundle, e);
                }
            });
            return null;
        });
    }

    /**
     * update new bundle-range to LocalZk (create a new node if not present)
     *
     * @param nsname
     * @param nsBundles
     * @param callback
     * @throws Exception
     */
    private void updateNamespaceBundles(NamespaceName nsname, NamespaceBundles nsBundles, StatCallback callback)
            throws Exception {
        checkNotNull(nsname);
        checkNotNull(nsBundles);
        String path = joinPath(LOCAL_POLICIES_ROOT, nsname.toString());
        Optional<LocalPolicies> policies = pulsar.getLocalZkCacheService().policiesCache().get(path);

        if (!policies.isPresent()) {
            // if policies is not present into localZk then create new policies
            this.pulsar.getLocalZkCacheService().createPolicies(path, false).get(cacheTimeOutInSec, SECONDS);
            policies = this.pulsar.getLocalZkCacheService().policiesCache().get(path);
        }

        policies.get().bundles = getBundlesData(nsBundles);
        this.pulsar.getLocalZkCache().getZooKeeper().setData(path,
                ObjectMapperFactory.getThreadLocal().writeValueAsBytes(policies.get()), -1, callback, null);
        // invalidate namespace's local-policies
        this.pulsar.getLocalZkCacheService().policiesCache().invalidate(path);
    }

    public OwnershipCache getOwnershipCache() {
        return ownershipCache;
    }

    public int getTotalServiceUnitsLoaded() {
        return ownershipCache.getOwnedBundles().size() - this.uncountedNamespaces;
    }

    public Set<NamespaceBundle> getOwnedServiceUnits() {
        return ownershipCache.getOwnedBundles().values().stream().map(OwnedBundle::getNamespaceBundle)
                .collect(Collectors.toSet());
    }

    public boolean isServiceUnitOwned(ServiceUnitId suName) throws Exception {
        if (suName instanceof DestinationName) {
            return isDestinationOwned((DestinationName) suName);
        }

        if (suName instanceof NamespaceName) {
            return isNamespaceOwned((NamespaceName) suName);
        }

        if (suName instanceof NamespaceBundle) {
            return ownershipCache.isNamespaceBundleOwned((NamespaceBundle) suName);
        }

        throw new IllegalArgumentException("Invalid class of NamespaceBundle: " + suName.getClass().getName());
    }

    public boolean isServiceUnitActive(DestinationName fqdn) {
        try {
            return ownershipCache.getOwnedBundle(getBundle(fqdn)).isActive();
        } catch (Exception e) {
            LOG.warn("Unable to find OwnedBundle for fqdn - [{}]", fqdn.toString());
            return false;
        }
    }

    private boolean isNamespaceOwned(NamespaceName fqnn) throws Exception {
        return ownershipCache.getOwnedBundle(getFullBundle(fqnn)) != null;
    }

    private CompletableFuture<Boolean> isDestinationOwnedAsync(DestinationName topic) {
        return getBundleAsync(topic).thenApply(bundle -> ownershipCache.isNamespaceBundleOwned(bundle));
    }

    private boolean isDestinationOwned(DestinationName fqdn) throws Exception {
        return ownershipCache.getOwnedBundle(getBundle(fqdn)) != null;
    }

    public void removeOwnedServiceUnit(NamespaceName nsName) throws Exception {
        ownershipCache.removeOwnership(getFullBundle(nsName)).get(cacheTimeOutInSec, SECONDS);
        bundleFactory.invalidateBundleCache(nsName);
    }

    public void removeOwnedServiceUnit(NamespaceBundle nsBundle) throws Exception {
        ownershipCache.removeOwnership(nsBundle).get(cacheTimeOutInSec, SECONDS);
        bundleFactory.invalidateBundleCache(nsBundle.getNamespaceObject());
    }

    public void removeOwnedServiceUnits(NamespaceName nsName, BundlesData bundleData) throws Exception {
        ownershipCache.removeOwnership(bundleFactory.getBundles(nsName, bundleData)).get(cacheTimeOutInSec, SECONDS);
        bundleFactory.invalidateBundleCache(nsName);
    }

    public NamespaceBundleFactory getNamespaceBundleFactory() {
        return bundleFactory;
    }

    public ServiceUnitId getServiceUnitId(DestinationName destinationName) throws Exception {
        return getBundle(destinationName);
    }

    public List<String> getListOfDestinations(String property, String cluster, String namespace) throws Exception {
        List<String> destinations = Lists.newArrayList();

        // For every topic there will be a managed ledger created.
        try {
            String path = String.format("/managed-ledgers/%s/%s/%s/persistent", property, cluster, namespace);
            LOG.debug("Getting children from managed-ledgers now: {}", path);
            for (String destination : pulsar.getLocalZkCacheService().managedLedgerListCache().get(path)) {
                destinations.add(String.format("persistent://%s/%s/%s/%s", property, cluster, namespace,
                        Codec.decode(destination)));
            }
        } catch (KeeperException.NoNodeException e) {
            // NoNode means there are no persistent topics for this namespace
        }

        destinations.sort(null);
        return destinations;
    }

    public Optional<NamespaceEphemeralData> getOwner(NamespaceBundle bundle) throws Exception {
        // if there is no znode for the service unit, it is not owned by any broker
        return getOwnerAsync(bundle).get(cacheTimeOutInSec, SECONDS);
    }

    public CompletableFuture<Optional<NamespaceEphemeralData>> getOwnerAsync(NamespaceBundle bundle) {
        return ownershipCache.getOwnerAsync(bundle);
    }

    public void unloadSLANamespace() throws Exception {
        PulsarAdmin adminClient = null;
        String namespaceName = getSLAMonitorNamespace(host, config);

        LOG.info("Checking owner for SLA namespace {}", namespaceName);

        NamespaceBundle nsFullBundle = getFullBundle(NamespaceName.get(namespaceName));
        if (!getOwner(nsFullBundle).isPresent()) {
            // No one owns the namespace so no point trying to unload it
            // Next lookup will assign the bundle to this broker.
            return;
        }

        LOG.info("Trying to unload SLA namespace {}", namespaceName);
        adminClient = pulsar.getAdminClient();
        adminClient.namespaces().unload(namespaceName);
        LOG.info("Namespace {} unloaded successfully", namespaceName);
    }

    public static String getHeartbeatNamespace(String host, ServiceConfiguration config) {
        return String.format(HEARTBEAT_NAMESPACE_FMT, config.getClusterName(), host, config.getWebServicePort());
    }

    public static String getSLAMonitorNamespace(String host, ServiceConfiguration config) {
        return String.format(SLA_NAMESPACE_FMT, config.getClusterName(), host, config.getWebServicePort());
    }

    public static String checkHeartbeatNamespace(ServiceUnitId ns) {
        Matcher m = HEARTBEAT_NAMESPACE_PATTERN.matcher(ns.getNamespaceObject().toString());
        if (m.matches()) {
            LOG.debug("SLAMonitoring namespace matched the lookup namespace {}", ns.getNamespaceObject().toString());
            return String.format("http://%s", m.group(1));
        } else {
            return null;
        }
    }

    public static String getSLAMonitorBrokerName(ServiceUnitId ns) {
        Matcher m = SLA_NAMESPACE_PATTERN.matcher(ns.getNamespaceObject().toString());
        if (m.matches()) {
            return String.format("http://%s", m.group(1));
        } else {
            return null;
        }
    }

    public boolean registerSLANamespace() throws PulsarServerException {
        boolean isNameSpaceRegistered = registerNamespace(getSLAMonitorNamespace(host, config), false);
        if (isNameSpaceRegistered) {
            this.uncountedNamespaces++;

            if (LOG.isDebugEnabled()) {
                LOG.debug("Added SLA Monitoring namespace name in local cache: ns={}",
                        getSLAMonitorNamespace(host, config));
            }
        } else if (LOG.isDebugEnabled()) {
            LOG.debug("SLA Monitoring not owned by the broker: ns={}", getSLAMonitorNamespace(host, config));
        }
        return isNameSpaceRegistered;
    }
}
