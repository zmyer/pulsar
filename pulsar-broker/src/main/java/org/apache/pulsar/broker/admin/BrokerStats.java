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
package org.apache.pulsar.broker.admin;

import java.io.OutputStream;
import java.util.Collection;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.StreamingOutput;

import org.apache.bookkeeper.mledger.proto.PendingBookieOpsStats;
import org.apache.pulsar.broker.loadbalance.LoadManager;
import org.apache.pulsar.broker.loadbalance.ResourceUnit;
import org.apache.pulsar.broker.loadbalance.impl.SimpleLoadManagerImpl;
import org.apache.pulsar.broker.stats.AllocatorStatsGenerator;
import org.apache.pulsar.broker.stats.BookieClientStatsGenerator;
import org.apache.pulsar.broker.stats.MBeanStatsGenerator;
import org.apache.pulsar.broker.web.RestException;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.stats.AllocatorStats;
import org.apache.pulsar.common.stats.Metrics;
import org.apache.pulsar.policies.data.loadbalancer.LoadManagerReport;
import org.apache.pulsar.policies.data.loadbalancer.LoadReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

@Path("/broker-stats")
@Api(value = "/broker-stats", description = "Stats for broker", tags = "broker-stats")
@Produces(MediaType.APPLICATION_JSON)
public class BrokerStats extends AdminResource {
    private static final Logger log = LoggerFactory.getLogger(BrokerStats.class);

    @GET
    @Path("/metrics")
    @ApiOperation(value = "Gets the metrics for Monitoring", notes = "Requested should be executed by Monitoring agent on each broker to fetch the metrics", response = Metrics.class, responseContainer = "List")
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission") })
    public Collection<Metrics> getMetrics() throws Exception {
        // Ensure super user access only
        validateSuperUserAccess();
        try {
            Collection<Metrics> metrics = pulsar().getMetricsGenerator().generate();
            return metrics;
        } catch (Exception e) {
            log.error("[{}] Failed to generate metrics", clientAppId(), e);
            throw new RestException(e);
        }
    }

    @GET
    @Path("/mbeans")
    @ApiOperation(value = "Get all the mbean details of this broker JVM", response = Metrics.class, responseContainer = "List")
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission") })
    public Collection<Metrics> getMBeans() throws Exception {
        // Ensure super user access only
        validateSuperUserAccess();
        try {
            Collection<Metrics> metrics = MBeanStatsGenerator.generate(pulsar());
            return metrics;
        } catch (Exception e) {
            log.error("[{}] Failed to generate mbean stats", clientAppId(), e);
            throw new RestException(e);
        }
    }

    @GET
    @Path("/destinations")
    @ApiOperation(value = "Get all the destination stats by namesapce", response = OutputStream.class, responseContainer = "OutputStream") // https://github.com/swagger-api/swagger-ui/issues/558
                                                                                                                                           // map
                                                                                                                                           // support
                                                                                                                                           // missing
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission") })
    public StreamingOutput getDestinations2() throws Exception {
        // Ensure super user access only
        validateSuperUserAccess();
        return output -> pulsar().getBrokerService().getDimensionMetrics(statsBuf -> {
            try {
                output.write(statsBuf.array(), statsBuf.arrayOffset(), statsBuf.readableBytes());
            } catch (Exception e) {
                throw new WebApplicationException(e);
            }
        });
    }

    @GET
    @Path("/allocator-stats/{allocator}")
    @ApiOperation(value = "Get the stats for the Netty allocator. Available allocators are 'default' and 'ml-cache'", response = AllocatorStats.class)
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission") })
    public AllocatorStats getAllocatorStats(@PathParam("allocator") String allocatorName) throws Exception {
        // Ensure super user access only
        validateSuperUserAccess();

        try {
            return AllocatorStatsGenerator.generate(allocatorName);
        } catch (IllegalArgumentException e) {
            throw new RestException(Status.NOT_ACCEPTABLE, e.getMessage());
        } catch (Exception e) {
            log.error("[{}] Failed to generate allocator stats", clientAppId(), e);
            throw new RestException(e);
        }
    }

    @GET
    @Path("/bookieops")
    @ApiOperation(value = "Get pending bookie client op stats by namesapce", response = PendingBookieOpsStats.class, // https://github.com/swagger-api/swagger-core/issues/449
                                                                                                                     // nested
                                                                                                                     // containers
                                                                                                                     // are
                                                                                                                     // not
                                                                                                                     // supported
            responseContainer = "Map")
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission") })
    public Map<String, Map<String, PendingBookieOpsStats>> getPendingBookieOpsStats() throws Exception {
        // Ensure super user access only
        validateSuperUserAccess();
        try {
            return BookieClientStatsGenerator.generate(pulsar());
        } catch (Exception e) {
            log.error("[{}] Failed to generate pending bookie ops stats for destinations", clientAppId(), e);
            throw new RestException(e);
        }
    }

    @GET
    @Path("/load-report")
    @ApiOperation(value = "Get Load for this broker", notes = "consists of destinationstats & systemResourceUsage", response = LoadReport.class)
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission") })
    public LoadManagerReport getLoadReport() throws Exception {
        // Ensure super user access only
        validateSuperUserAccess();
        try {
            return (pulsar().getLoadManager().get()).generateLoadReport();
        } catch (Exception e) {
            log.error("[{}] Failed to generate LoadReport for broker, reason [{}]", clientAppId(), e.getMessage(), e);
            throw new RestException(e);
        }
    }

    @GET
    @Path("/broker-resource-availability/{property}/{cluster}/{namespace}")
    @ApiOperation(value = "Broker availability report", notes = "This API gives the current broker availability in percent, each resource percentage usage is calculated and then"
            + "sum of all of the resource usage percent is called broker-resource-availability"
            + "<br/><br/>THIS API IS ONLY FOR USE BY TESTING FOR CONFIRMING NAMESPACE ALLOCATION ALGORITHM", response = ResourceUnit.class, responseContainer = "Map")
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission"),
            @ApiResponse(code = 409, message = "Load-manager doesn't support operation") })
    public Map<Long, Collection<ResourceUnit>> getBrokerResourceAvailability(@PathParam("property") String property,
            @PathParam("cluster") String cluster, @PathParam("namespace") String namespace) throws Exception {
        try {
            NamespaceName ns = NamespaceName.get(property, cluster, namespace);
            LoadManager lm = pulsar().getLoadManager().get();
            if (lm instanceof SimpleLoadManagerImpl) {
                return ((SimpleLoadManagerImpl) lm).getResourceAvailabilityFor(ns).asMap();
            } else {
                throw new RestException(Status.CONFLICT, lm.getClass().getName() + " does not support this operation");
            }
        } catch (Exception e) {
            log.error("Unable to get Resource Availability - [{}]", e);
            throw new RestException(e);
        }
    }
}
