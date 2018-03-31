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

import static org.apache.pulsar.broker.service.BrokerService.BROKER_SERVICE_CONFIGURATION_PATH;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response.Status;

import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.util.ZkUtils;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.admin.AdminResource;
import org.apache.pulsar.broker.loadbalance.LoadManager;
import org.apache.pulsar.broker.service.BrokerService;
import org.apache.pulsar.broker.web.RestException;
import org.apache.pulsar.common.conf.InternalConfigurationData;
import org.apache.pulsar.common.policies.data.NamespaceOwnershipStatus;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.apache.pulsar.zookeeper.ZooKeeperDataCache;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;


public class BrokersBase extends AdminResource {
    private static final Logger LOG = LoggerFactory.getLogger(BrokersBase.class);
    private int serviceConfigZkVersion = -1;
    
    @GET
    @Path("/{cluster}")
    @ApiOperation(value = "Get the list of active brokers (web service addresses) in the cluster.", response = String.class, responseContainer = "Set")
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission"),
            @ApiResponse(code = 404, message = "Cluster doesn't exist") })
    public Set<String> getActiveBrokers(@PathParam("cluster") String cluster) throws Exception {
        validateSuperUserAccess();
        validateClusterOwnership(cluster);

        try {
            // Add Native brokers
            return pulsar().getLocalZkCache().getChildren(LoadManager.LOADBALANCE_BROKERS_ROOT);
        } catch (Exception e) {
            LOG.error(String.format("[%s] Failed to get active broker list: cluster=%s", clientAppId(), cluster), e);
            throw new RestException(e);
        }
    }

    @GET
    @Path("/{cluster}/{broker}/ownedNamespaces")
    @ApiOperation(value = "Get the list of namespaces served by the specific broker", response = NamespaceOwnershipStatus.class, responseContainer = "Map")
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission"),
            @ApiResponse(code = 404, message = "Cluster doesn't exist") })
    public Map<String, NamespaceOwnershipStatus> getOwnedNamespaes(@PathParam("cluster") String cluster,
            @PathParam("broker") String broker) throws Exception {
        validateSuperUserAccess();
        validateClusterOwnership(cluster);
        validateBrokerName(broker);

        try {
            // now we validated that this is the broker specified in the request
            return pulsar().getNamespaceService().getOwnedNameSpacesStatus();
        } catch (Exception e) {
            LOG.error("[{}] Failed to get the namespace ownership status. cluster={}, broker={}", clientAppId(),
                    cluster, broker);
            throw new RestException(e);
        }
    }
    
    @POST
    @Path("/configuration/{configName}/{configValue}")
    @ApiOperation(value = "Update dynamic serviceconfiguration into zk only. This operation requires Pulsar super-user privileges.")
    @ApiResponses(value = { @ApiResponse(code = 204, message = "Service configuration updated successfully"),
            @ApiResponse(code = 403, message = "You don't have admin permission to update service-configuration"),
            @ApiResponse(code = 404, message = "Configuration not found"),
            @ApiResponse(code = 412, message = "Configuration can't be updated dynamically") })
    public void updateDynamicConfiguration(@PathParam("configName") String configName, @PathParam("configValue") String configValue) throws Exception{
        validateSuperUserAccess();
        updateDynamicConfigurationOnZk(configName, configValue);
    }

    @GET
    @Path("/configuration/values")
    @ApiOperation(value = "Get value of all dynamic configurations' value overridden on local config")
    @ApiResponses(value = { @ApiResponse(code = 404, message = "Configuration not found") })
    public Map<String, String> getAllDynamicConfigurations() throws Exception {
        ZooKeeperDataCache<Map<String, String>> dynamicConfigurationCache = pulsar().getBrokerService()
                .getDynamicConfigurationCache();
        Map<String, String> configurationMap = null;
        try {
            configurationMap = dynamicConfigurationCache.get(BROKER_SERVICE_CONFIGURATION_PATH)
                    .orElseThrow(() -> new RestException(Status.NOT_FOUND, "Couldn't find configuration in zk"));
        } catch (RestException e) {
            LOG.error("[{}] couldn't find any configuration in zk {}", clientAppId(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            LOG.error("[{}] Failed to retrieve configuration from zk {}", clientAppId(), e.getMessage(), e);
            throw new RestException(e);
        }
        return configurationMap;
    }

    @GET
    @Path("/configuration")
    @ApiOperation(value = "Get all updatable dynamic configurations's name")
    public List<String> getDynamicConfigurationName() {
        return BrokerService.getDynamicConfiguration();
    }
    
    /**
     * if {@link ServiceConfiguration}-field is allowed to be modified dynamically, update configuration-map into zk, so
     * all other brokers get the watch and can see the change and take appropriate action on the change.
     * 
     * @param configName
     *            : configuration key
     * @param configValue
     *            : configuration value
     */
    private synchronized void updateDynamicConfigurationOnZk(String configName, String configValue) {
        try {
            if (!BrokerService.validateDynamicConfiguration(configName, configValue)) {
                throw new RestException(Status.PRECONDITION_FAILED, " Invalid dynamic-config value");
            }
            if (BrokerService.isDynamicConfiguration(configName)) {
                ZooKeeperDataCache<Map<String, String>> dynamicConfigurationCache = pulsar().getBrokerService()
                        .getDynamicConfigurationCache();
                Map<String, String> configurationMap = dynamicConfigurationCache.get(BROKER_SERVICE_CONFIGURATION_PATH)
                        .orElse(null);
                if (configurationMap != null) {
                    configurationMap.put(configName, configValue);
                    byte[] content = ObjectMapperFactory.getThreadLocal().writeValueAsBytes(configurationMap);
                    dynamicConfigurationCache.invalidate(BROKER_SERVICE_CONFIGURATION_PATH);
                    serviceConfigZkVersion = localZk()
                            .setData(BROKER_SERVICE_CONFIGURATION_PATH, content, serviceConfigZkVersion).getVersion();
                } else {
                    configurationMap = Maps.newHashMap();
                    configurationMap.put(configName, configValue);
                    byte[] content = ObjectMapperFactory.getThreadLocal().writeValueAsBytes(configurationMap);
                    ZkUtils.createFullPathOptimistic(localZk(), BROKER_SERVICE_CONFIGURATION_PATH, content,
                            ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                }
                LOG.info("[{}] Updated Service configuration {}/{}", clientAppId(), configName, configValue);
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("[{}] Can't update non-dynamic configuration {}/{}", clientAppId(), configName,
                            configValue);
                }
                throw new RestException(Status.PRECONDITION_FAILED, " Can't update non-dynamic configuration");
            }
        } catch (RestException re) {
            throw re;
        } catch (Exception ie) {
            LOG.error("[{}] Failed to update configuration {}/{}, {}", clientAppId(), configName, configValue,
                    ie.getMessage(), ie);
            throw new RestException(ie);
        }
    }

    @GET
    @Path("/internal-configuration")
    @ApiOperation(value = "Get the internal configuration data", response = InternalConfigurationData.class)
    public InternalConfigurationData getInternalConfigurationData() {
        ClientConfiguration conf = new ClientConfiguration();
        return new InternalConfigurationData(
            pulsar().getConfiguration().getZookeeperServers(),
            pulsar().getConfiguration().getGlobalZookeeperServers(),
            conf.getZkLedgersRootPath());
    }

}
