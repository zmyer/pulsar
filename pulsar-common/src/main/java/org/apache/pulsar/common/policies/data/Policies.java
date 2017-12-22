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
package org.apache.pulsar.common.policies.data;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class Policies {

    public final AuthPolicies auth_policies = new AuthPolicies();
    public List<String> replication_clusters = Lists.newArrayList();
    public BundlesData bundles = defaultBundle();
    public Map<BacklogQuota.BacklogQuotaType, BacklogQuota> backlog_quota_map = Maps.newHashMap();
    public Map<String, DispatchRate> clusterDispatchRate = Maps.newHashMap();
    public PersistencePolicies persistence = null;

    // If set, it will override the broker settings for enabling deduplication
    public Boolean deduplicationEnabled = null;

    public Map<String, Integer> latency_stats_sample_rate = Maps.newHashMap();
    public int message_ttl_in_seconds = 0;
    public RetentionPolicies retention_policies = null;
    public boolean deleted = false;

    public static final String FIRST_BOUNDARY = "0x00000000";
    public static final String LAST_BOUNDARY = "0xffffffff";

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Policies) {
            Policies other = (Policies) obj;
            return Objects.equals(auth_policies, other.auth_policies)
                    && Objects.equals(replication_clusters, other.replication_clusters)
                    && Objects.equals(backlog_quota_map, other.backlog_quota_map)
                    && Objects.equals(clusterDispatchRate, other.clusterDispatchRate)
                    && Objects.equals(deduplicationEnabled, other.deduplicationEnabled)
                    && Objects.equals(persistence, other.persistence) && Objects.equals(bundles, other.bundles)
                    && Objects.equals(latency_stats_sample_rate, other.latency_stats_sample_rate)
                    && message_ttl_in_seconds == other.message_ttl_in_seconds
                    && Objects.equals(retention_policies, other.retention_policies);
        }

        return false;
    }

    public static BundlesData defaultBundle() {
        BundlesData bundle = new BundlesData(1);
        List<String> boundaries = Lists.newArrayList();
        boundaries.add(FIRST_BOUNDARY);
        boundaries.add(LAST_BOUNDARY);
        bundle.setBoundaries(boundaries);
        return bundle;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("auth_policies", auth_policies)
                .add("replication_clusters", replication_clusters).add("bundles", bundles)
                .add("backlog_quota_map", backlog_quota_map).add("persistence", persistence)
                .add("deduplicationEnabled", deduplicationEnabled)
                .add("clusterDispatchRate", clusterDispatchRate)
                .add("latency_stats_sample_rate", latency_stats_sample_rate)
                .add("message_ttl_in_seconds", message_ttl_in_seconds).add("retention_policies", retention_policies)
                .add("deleted", deleted).toString();
    }
}

