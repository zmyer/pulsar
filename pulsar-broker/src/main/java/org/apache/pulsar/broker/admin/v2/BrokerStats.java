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
package org.apache.pulsar.broker.admin.v2;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.pulsar.broker.admin.impl.BrokerStatsBase;
import org.apache.pulsar.broker.loadbalance.ResourceUnit;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.Collection;
import java.util.Map;

@Path("/broker-stats")
@Api(value = "/broker-stats", description = "Stats for broker", tags = "broker-stats")
@Produces(MediaType.APPLICATION_JSON)
public class BrokerStats extends BrokerStatsBase {

    @GET
    @Path("/broker-resource-availability/{property}/{namespace}")
    @ApiOperation(value = "Broker availability report", notes = "This API gives the current broker availability in "
            + "percent, each resource percentage usage is calculated and then"
            + "sum of all of the resource usage percent is called broker-resource-availability"
            + "<br/><br/>THIS API IS ONLY FOR USE BY TESTING FOR CONFIRMING NAMESPACE ALLOCATION ALGORITHM",
            response = ResourceUnit.class, responseContainer = "Map")
    @ApiResponses(value = { @ApiResponse(code = 403, message = "Don't have admin permission"),
            @ApiResponse(code = 409, message = "Load-manager doesn't support operation") })
    public Map<Long, Collection<ResourceUnit>> getBrokerResourceAvailability(@PathParam("property") String property,
        @PathParam("namespace") String namespace) {
        validateNamespaceName(property, namespace);
        return internalBrokerResourceAvailability(namespaceName);
    }
}
