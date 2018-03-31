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
package org.apache.pulsar.client.admin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.apache.pulsar.client.admin.PulsarAdminException.ConflictException;
import org.apache.pulsar.client.admin.PulsarAdminException.NotAllowedException;
import org.apache.pulsar.client.admin.PulsarAdminException.NotAuthorizedException;
import org.apache.pulsar.client.admin.PulsarAdminException.NotFoundException;
import org.apache.pulsar.client.admin.PulsarAdminException.PreconditionFailedException;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.common.partition.PartitionedTopicMetadata;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.PartitionedTopicStats;
import org.apache.pulsar.common.policies.data.PersistentTopicInternalStats;
import org.apache.pulsar.common.policies.data.PersistentTopicStats;
import org.omg.CosNaming.NamingContextPackage.NotFound;

import com.google.gson.JsonObject;

public interface PersistentTopics {

    /**
     * Get the list of topics under a namespace.
     * <p>
     * Response example:
     *
     * <pre>
     * <code>["topic://my-property/use/my-namespace/topic-1",
     *  "topic://my-property/use/my-namespace/topic-2"]</code>
     * </pre>
     *
     * @param namespace
     *            Namespace name
     * @return a list of topics
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Namespace does not exist
     * @throws PulsarAdminException
     *             Unexpected error
     */
    List<String> getList(String namespace) throws PulsarAdminException;

    /**
     * Get the list of partitioned topics under a namespace.
     * <p>
     * Response example:
     *
     * <pre>
     * <code>["persistent://my-property/use/my-namespace/topic-1",
     *  "persistent://my-property/use/my-namespace/topic-2"]</code>
     * </pre>
     *
     * @param namespace
     *            Namespace name
     * @return a list of partitioned topics
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Namespace does not exist
     * @throws PulsarAdminException
     *             Unexpected error
     */
    List<String> getPartitionedTopicList(String namespace) throws PulsarAdminException;

    /**
     * Get permissions on a topic.
     * <p>
     * Retrieve the effective permissions for a topic. These permissions are defined by the permissions set at the
     * namespace level combined (union) with any eventual specific permission set on the topic.
     * <p>
     * Response Example:
     *
     * <pre>
     * <code>{
     *   "role-1" : [ "produce" ],
     *   "role-2" : [ "consume" ]
     * }</code>
     * </pre>
     *
     * @param topic
     *            Topic url
     * @return a map of topics an their permissions set
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Namespace does not exist
     * @throws PulsarAdminException
     *             Unexpected error
     */
    Map<String, Set<AuthAction>> getPermissions(String topic) throws PulsarAdminException;

    /**
     * Grant permission on a topic.
     * <p>
     * Grant a new permission to a client role on a single topic.
     * <p>
     * Request parameter example:
     *
     * <pre>
     * <code>["produce", "consume"]</code>
     * </pre>
     *
     * @param topic
     *            Topic url
     * @param role
     *            Client role to which grant permission
     * @param actions
     *            Auth actions (produce and consume)
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Namespace does not exist
     * @throws ConflictException
     *             Concurrent modification
     * @throws PulsarAdminException
     *             Unexpected error
     */
    void grantPermission(String topic, String role, Set<AuthAction> actions) throws PulsarAdminException;

    /**
     * Revoke permissions on a topic.
     * <p>
     * Revoke permissions to a client role on a single topic. If the permission was not set at the topic level, but
     * rather at the namespace level, this operation will return an error (HTTP status code 412).
     *
     * @param topic
     *            Topic url
     * @param role
     *            Client role to which remove permission
     * @throws UniformInterfaceException
     *             if the operations was not successful
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFound
     *             Namespace does not exist
     * @throws PreconditionFailedException
     *             Permissions are not set at the topic level
     * @throws PulsarAdminException
     *             Unexpected error
     */
    void revokePermissions(String topic, String role) throws PulsarAdminException;

    /**
     * Create a partitioned topic.
     * <p>
     * Create a partitioned topic. It needs to be called before creating a producer for a partitioned topic.
     * <p>
     *
     * @param topic
     *            Topic name
     * @param numPartitions
     *            Number of partitions to create of the topic
     * @throws PulsarAdminException
     */
    void createPartitionedTopic(String topic, int numPartitions) throws PulsarAdminException;

    /**
     * Create a partitioned topic asynchronously.
     * <p>
     * Create a partitioned topic asynchronously. It needs to be called before creating a producer for a partitioned
     * topic.
     * <p>
     *
     * @param topic
     *            Topic name
     * @param numPartitions
     *            Number of partitions to create of the topic
     * @return a future that can be used to track when the partitioned topic is created
     */
    CompletableFuture<Void> createPartitionedTopicAsync(String topic, int numPartitions);

    /**
     * Update number of partitions of a non-global partitioned topic.
     * <p>
     * It requires partitioned-topic to be already exist and number of new partitions must be greater than existing
     * number of partitions. Decrementing number of partitions requires deletion of topic which is not supported.
     * <p>
     *
     * @param topic
     *            Topic name
     * @param numPartitions
     *            Number of new partitions of already exist partitioned-topic
     *
     * @return a future that can be used to track when the partitioned topic is updated
     */
    void updatePartitionedTopic(String topic, int numPartitions) throws PulsarAdminException;

    /**
     * Update number of partitions of a non-global partitioned topic asynchronously.
     * <p>
     * It requires partitioned-topic to be already exist and number of new partitions must be greater than existing
     * number of partitions. Decrementing number of partitions requires deletion of topic which is not supported.
     * <p>
     *
     * @param topic
     *            Topic name
     * @param numPartitions
     *            Number of new partitions of already exist partitioned-topic
     *
     * @return a future that can be used to track when the partitioned topic is updated
     */
    CompletableFuture<Void> updatePartitionedTopicAsync(String topic, int numPartitions);

    /**
     * Get metadata of a partitioned topic.
     * <p>
     * Get metadata of a partitioned topic.
     * <p>
     *
     * @param topic
     *            Topic name
     * @return Partitioned topic metadata
     * @throws PulsarAdminException
     */
    PartitionedTopicMetadata getPartitionedTopicMetadata(String topic) throws PulsarAdminException;

    /**
     * Get metadata of a partitioned topic asynchronously.
     * <p>
     * Get metadata of a partitioned topic asynchronously.
     * <p>
     *
     * @param topic
     *            Topic name
     * @return a future that can be used to track when the partitioned topic metadata is returned
     */
    CompletableFuture<PartitionedTopicMetadata> getPartitionedTopicMetadataAsync(String topic);

    /**
     * Delete a partitioned topic.
     * <p>
     * It will also delete all the partitions of the topic if it exists.
     * <p>
     *
     * @param topic
     *            Topic name
     * @throws PulsarAdminException
     */
    void deletePartitionedTopic(String topic) throws PulsarAdminException;

    /**
     * Delete a partitioned topic asynchronously.
     * <p>
     * It will also delete all the partitions of the topic if it exists.
     * <p>
     *
     * @param topic
     *            Topic name
     * @return a future that can be used to track when the partitioned topic is deleted
     */
    CompletableFuture<Void> deletePartitionedTopicAsync(String topic);

    /**
     * Delete a topic.
     * <p>
     * Delete a topic. The topic cannot be deleted if there's any active subscription or producer connected to the it.
     * <p>
     *
     * @param topic
     *            Topic name
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Topic does not exist
     * @throws PreconditionFailedException
     *             Topic has active subscriptions or producers
     * @throws PulsarAdminException
     *             Unexpected error
     */
    void delete(String topic) throws PulsarAdminException;

    /**
     * Delete a topic asynchronously.
     * <p>
     * Delete a topic asynchronously. The topic cannot be deleted if there's any active subscription or producer
     * connected to the it.
     * <p>
     *
     * @param topic
     *            topic name
     *
     * @return a future that can be used to track when the topic is deleted
     */
    CompletableFuture<Void> deleteAsync(String topic);

    /**
     * Unload a topic.
     * <p>
     *
     * @param topic
     *            topic name
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             topic does not exist
     * @throws PulsarAdminException
     *             Unexpected error
     */
    void unload(String topic) throws PulsarAdminException;

    /**
     * Unload a topic asynchronously.
     * <p>
     *
     * @param topic
     *            topic name
     *
     * @return a future that can be used to track when the topic is unloaded
     */
    CompletableFuture<Void> unloadAsync(String topic);

    /**
     * Terminate the topic and prevent any more messages being published on it.
     * <p>
     * This
     *
     * @param topic
     *            topic name
     * @return the message id of the last message that was published in the topic
     */
    CompletableFuture<MessageId> terminateTopicAsync(String topic);

    /**
     * Get the list of subscriptions.
     * <p>
     * Get the list of persistent subscriptions for a given topic.
     * <p>
     *
     * @param topic
     *            topic name
     * @return the list of subscriptions
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Topic does not exist
     * @throws PulsarAdminException
     *             Unexpected error
     */
    List<String> getSubscriptions(String topic) throws PulsarAdminException;

    /**
     * Get the list of subscriptions asynchronously.
     * <p>
     * Get the list of persistent subscriptions for a given topic.
     * <p>
     *
     * @param topic
     *            topic name
     * @return a future that can be used to track when the list of subscriptions is returned
     */
    CompletableFuture<List<String>> getSubscriptionsAsync(String topic);

    /**
     * Get the stats for the topic.
     * <p>
     * Response Example:
     *
     * <pre>
     * <code>
     * {
     *   "msgRateIn" : 100.0,                    // Total rate of messages published on the topic. msg/s
     *   "msgThroughputIn" : 10240.0,            // Total throughput of messages published on the topic. byte/s
     *   "msgRateOut" : 100.0,                   // Total rate of messages delivered on the topic. msg/s
     *   "msgThroughputOut" : 10240.0,           // Total throughput of messages delivered on the topic. byte/s
     *   "averageMsgSize" : 1024.0,              // Average size of published messages. bytes
     *   "publishers" : [                        // List of publishes on this topic with their stats
     *      {
     *          "producerId" : 10                // producer id
     *          "address"   : 10.4.1.23:3425     // IP and port for this producer
     *          "connectedSince" : 2014-11-21 23:54:46 // Timestamp of this published connection
     *          "msgRateIn" : 100.0,             // Total rate of messages published by this producer. msg/s
     *          "msgThroughputIn" : 10240.0,     // Total throughput of messages published by this producer. byte/s
     *          "averageMsgSize" : 1024.0,       // Average size of published messages by this producer. bytes
     *      },
     *   ],
     *   "subscriptions" : {                     // Map of subscriptions on this topic
     *     "sub1" : {
     *       "msgRateOut" : 100.0,               // Total rate of messages delivered on this subscription. msg/s
     *       "msgThroughputOut" : 10240.0,       // Total throughput delivered on this subscription. bytes/s
     *       "msgBacklog" : 0,                   // Number of messages in the subscriotion backlog
     *       "type" : Exclusive                  // Whether the subscription is exclusive or shared
     *       "consumers" [                       // List of consumers on this subscription
     *          {
     *              "id" : 5                            // Consumer id
     *              "address" : 10.4.1.23:3425          // IP and port for this consumer
     *              "connectedSince" : 2014-11-21 23:54:46 // Timestamp of this consumer connection
     *              "msgRateOut" : 100.0,               // Total rate of messages delivered to this consumer. msg/s
     *              "msgThroughputOut" : 10240.0,       // Total throughput delivered to this consumer. bytes/s
     *          }
     *       ],
     *   },
     *   "replication" : {                    // Replication statistics
     *     "cluster_1" : {                    // Cluster name in the context of from-cluster or to-cluster
     *       "msgRateIn" : 100.0,             // Total rate of messages received from this remote cluster. msg/s
     *       "msgThroughputIn" : 10240.0,     // Total throughput received from this remote cluster. bytes/s
     *       "msgRateOut" : 100.0,            // Total rate of messages delivered to the replication-subscriber. msg/s
     *       "msgThroughputOut" : 10240.0,    // Total throughput delivered to the replication-subscriber. bytes/s
     *       "replicationBacklog" : 0,        // Number of messages pending to be replicated to this remote cluster
     *       "connected" : true,              // Whether the replication-subscriber is currently connected locally
     *     },
     *     "cluster_2" : {
     *       "msgRateIn" : 100.0,
     *       "msgThroughputIn" : 10240.0,
     *       "msgRateOut" : 100.0,
     *       "msghroughputOut" : 10240.0,
     *       "replicationBacklog" : 0,
     *       "connected" : true,
     *     }
     *   },
     * }
     * </code>
     * </pre>
     *
     * All the rates are computed over a 1 minute window and are relative the last completed 1 minute period.
     *
     * @param topic
     *            topic name
     * @return the topic statistics
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Topic does not exist
     * @throws PulsarAdminException
     *             Unexpected error
     */
    PersistentTopicStats getStats(String topic) throws PulsarAdminException;

    /**
     * Get the stats for the topic asynchronously. All the rates are computed over a 1 minute window and are relative
     * the last completed 1 minute period.
     *
     * @param topic
     *            topic name
     *
     * @return a future that can be used to track when the topic statistics are returned
     *
     */
    CompletableFuture<PersistentTopicStats> getStatsAsync(String topic);

    /**
     * Get the internal stats for the topic.
     * <p>
     * Access the internal state of the topic
     *
     * @param topic
     *            topic name
     * @return the topic statistics
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Topic does not exist
     * @throws PulsarAdminException
     *             Unexpected error
     */
    PersistentTopicInternalStats getInternalStats(String topic) throws PulsarAdminException;

    /**
     * Get the internal stats for the topic asynchronously.
     *
     * @param topic
     *            topic Name
     *
     * @return a future that can be used to track when the internal topic statistics are returned
     */
    CompletableFuture<PersistentTopicInternalStats> getInternalStatsAsync(String topic);

    /**
     * Get a JSON representation of the topic metadata stored in ZooKeeper
     *
     * @param topic
     *            topic name
     * @return the topic internal metadata
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Topic does not exist
     * @throws PulsarAdminException
     *             Unexpected error
     */
    JsonObject getInternalInfo(String topic) throws PulsarAdminException;

    /**
     * Get a JSON representation of the topic metadata stored in ZooKeeper
     *
     * @param topic
     *            topic name
     * @return a future to receive the topic internal metadata
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Topic does not exist
     * @throws PulsarAdminException
     *             Unexpected error
     */
    CompletableFuture<JsonObject> getInternalInfoAsync(String topic);

    /**
     * Get the stats for the partitioned topic
     * <p>
     * Response Example:
     *
     * <pre>
     * <code>
     * {
     *   "msgRateIn" : 100.0,                    // Total rate of messages published on the partitioned topic. msg/s
     *   "msgThroughputIn" : 10240.0,            // Total throughput of messages published on the partitioned topic. byte/s
     *   "msgRateOut" : 100.0,                   // Total rate of messages delivered on the partitioned topic. msg/s
     *   "msgThroughputOut" : 10240.0,           // Total throughput of messages delivered on the partitioned topic. byte/s
     *   "averageMsgSize" : 1024.0,              // Average size of published messages. bytes
     *   "publishers" : [                        // List of publishes on this partitioned topic with their stats
     *      {
     *          "msgRateIn" : 100.0,             // Total rate of messages published by this producer. msg/s
     *          "msgThroughputIn" : 10240.0,     // Total throughput of messages published by this producer. byte/s
     *          "averageMsgSize" : 1024.0,       // Average size of published messages by this producer. bytes
     *      },
     *   ],
     *   "subscriptions" : {                     // Map of subscriptions on this topic
     *     "sub1" : {
     *       "msgRateOut" : 100.0,               // Total rate of messages delivered on this subscription. msg/s
     *       "msgThroughputOut" : 10240.0,       // Total throughput delivered on this subscription. bytes/s
     *       "msgBacklog" : 0,                   // Number of messages in the subscriotion backlog
     *       "type" : Exclusive                  // Whether the subscription is exclusive or shared
     *       "consumers" [                       // List of consumers on this subscription
     *          {
     *              "msgRateOut" : 100.0,               // Total rate of messages delivered to this consumer. msg/s
     *              "msgThroughputOut" : 10240.0,       // Total throughput delivered to this consumer. bytes/s
     *          }
     *       ],
     *   },
     *   "replication" : {                    // Replication statistics
     *     "cluster_1" : {                    // Cluster name in the context of from-cluster or to-cluster
     *       "msgRateIn" : 100.0,             // Total rate of messages received from this remote cluster. msg/s
     *       "msgThroughputIn" : 10240.0,     // Total throughput received from this remote cluster. bytes/s
     *       "msgRateOut" : 100.0,            // Total rate of messages delivered to the replication-subscriber. msg/s
     *       "msgThroughputOut" : 10240.0,    // Total throughput delivered to the replication-subscriber. bytes/s
     *       "replicationBacklog" : 0,        // Number of messages pending to be replicated to this remote cluster
     *       "connected" : true,              // Whether the replication-subscriber is currently connected locally
     *     },
     *     "cluster_2" : {
     *       "msgRateIn" : 100.0,
     *       "msgThroughputIn" : 10240.0,
     *       "msgRateOut" : 100.0,
     *       "msghroughputOut" : 10240.0,
     *       "replicationBacklog" : 0,
     *       "connected" : true,
     *     }
     *   },
     * }
     * </code>
     * </pre>
     *
     * All the rates are computed over a 1 minute window and are relative the last completed 1 minute period.
     *
     * @param topic
     *            topic name
     * @param perPartition
     *
     * @return the partitioned topic statistics
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Topic does not exist
     * @throws PulsarAdminException
     *             Unexpected error
     *
     */
    PartitionedTopicStats getPartitionedStats(String topic, boolean perPartition) throws PulsarAdminException;

    /**
     * Get the stats for the partitioned topic asynchronously
     *
     * @param topic
     *            topic Name
     * @param perPartition
     *            flag to get stats per partition
     * @return a future that can be used to track when the partitioned topic statistics are returned
     */
    CompletableFuture<PartitionedTopicStats> getPartitionedStatsAsync(String topic, boolean perPartition);

    /**
     * Delete a subscription.
     * <p>
     * Delete a persistent subscription from a topic. There should not be any active consumers on the subscription.
     * <p>
     *
     * @param topic
     *            topic name
     * @param subName
     *            Subscription name
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Topic or subscription does not exist
     * @throws PreconditionFailedException
     *             Subscription has active consumers
     * @throws PulsarAdminException
     *             Unexpected error
     */
    void deleteSubscription(String topic, String subName) throws PulsarAdminException;

    /**
     * Delete a subscription asynchronously.
     * <p>
     * Delete a persistent subscription from a topic. There should not be any active consumers on the subscription.
     * <p>
     *
     * @param topic
     *            topic name
     * @param subName
     *            Subscription name
     *
     * @return a future that can be used to track when the subscription is deleted
     */
    CompletableFuture<Void> deleteSubscriptionAsync(String topic, String subName);

    /**
     * Skip all messages on a topic subscription.
     * <p>
     * Completely clears the backlog on the subscription.
     *
     * @param topic
     *            topic name
     * @param subName
     *            Subscription name
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Topic or subscription does not exist
     * @throws PulsarAdminException
     *             Unexpected error
     */
    void skipAllMessages(String topic, String subName) throws PulsarAdminException;

    /**
     * Skip all messages on a topic subscription asynchronously.
     * <p>
     * Completely clears the backlog on the subscription.
     *
     * @param topic
     *            topic name
     * @param subName
     *            Subscription name
     *
     * @return a future that can be used to track when all the messages are skipped
     */
    CompletableFuture<Void> skipAllMessagesAsync(String topic, String subName);

    /**
     * Skip messages on a topic subscription.
     *
     * @param topic
     *            topic name
     * @param subName
     *            Subscription name
     * @param numMessages
     *            Number of messages
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Topic or subscription does not exist
     * @throws PulsarAdminException
     *             Unexpected error
     */
    void skipMessages(String topic, String subName, long numMessages) throws PulsarAdminException;

    /**
     * Skip messages on a topic subscription asynchronously.
     *
     * @param topic
     *            topic name
     * @param subName
     *            Subscription name
     * @param numMessages
     *            Number of messages
     *
     * @return a future that can be used to track when the number of messages are skipped
     */
    CompletableFuture<Void> skipMessagesAsync(String topic, String subName, long numMessages);

    /**
     * Expire all messages older than given N (expireTimeInSeconds) seconds for a given subscription
     *
     * @param topic
     *            topic name
     * @param subName
     *            Subscription name
     * @param expireTimeInSeconds
     *            Expire messages older than time in seconds
     * @throws PulsarAdminException
     *             Unexpected error
     */
    public void expireMessages(String topic, String subscriptionName, long expireTimeInSeconds)
            throws PulsarAdminException;

    /**
     * Expire all messages older than given N (expireTimeInSeconds) seconds for a given subscription asynchronously
     *
     * @param topic
     *            topic name
     * @param subName
     *            Subscription name
     * @param expireTimeInSeconds
     *            Expire messages older than time in seconds
     * @return
     */
    public CompletableFuture<Void> expireMessagesAsync(String topic, String subscriptionName,
            long expireTimeInSeconds);

    /**
     * Expire all messages older than given N (expireTimeInSeconds) seconds for all subscriptions of the
     * persistent-topic
     *
     * @param topic
     *            topic name
     * @param expireTimeInSeconds
     *            Expire messages older than time in seconds
     * @throws PulsarAdminException
     *             Unexpected error
     */
    public void expireMessagesForAllSubscriptions(String topic, long expireTimeInSeconds)
            throws PulsarAdminException;

    /**
     * Expire all messages older than given N (expireTimeInSeconds) seconds for all subscriptions of the
     * persistent-topic asynchronously
     *
     * @param topic
     *            topic name
     * @param expireTimeInSeconds
     *            Expire messages older than time in seconds
     */
    public CompletableFuture<Void> expireMessagesForAllSubscriptionsAsync(String topic, long expireTimeInSeconds);

    /**
     * Peek messages from a topic subscription
     *
     * @param topic
     *            topic name
     * @param subName
     *            Subscription name
     * @param numMessages
     *            Number of messages
     * @return
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Topic or subscription does not exist
     * @throws PulsarAdminException
     *             Unexpected error
     */
    List<Message<byte[]>> peekMessages(String topic, String subName, int numMessages) throws PulsarAdminException;

    /**
     * Peek messages from a topic subscription asynchronously
     *
     * @param topic
     *            topic name
     * @param subName
     *            Subscription name
     * @param numMessages
     *            Number of messages
     * @return a future that can be used to track when the messages are returned
     */
    CompletableFuture<List<Message<byte[]>>> peekMessagesAsync(String topic, String subName, int numMessages);

    /**
     * Create a new subscription on a topic
     *
     * @param topic
     *            topic name
     * @param subscriptionName
     *            Subscription name
     * @param messageId
     *            The {@link MessageId} on where to initialize the subscription. It could be {@link MessageId#latest},
     *            {@link MessageId#earliest} or a specific message id.
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws ConflictException
     *             Subscription already exists
     * @throws NotAllowedException
     *             Command disallowed for requested resource
     * @throws PulsarAdminException
     *             Unexpected error
     */
    void createSubscription(String topic, String subscriptionName, MessageId messageId)
            throws PulsarAdminException;

    /**
     * Create a new subscription on a topic
     *
     * @param topic
     *            topic name
     * @param subscriptionName
     *            Subscription name
     * @param messageId
     *            The {@link MessageId} on where to initialize the subscription. It could be {@link MessageId#latest},
     *            {@link MessageId#earliest} or a specific message id.
     */
    CompletableFuture<Void> createSubscriptionAsync(String topic, String subscriptionName, MessageId messageId);

    /**
     * Reset cursor position on a topic subscription
     *
     * @param topic
     *            topic name
     * @param subName
     *            Subscription name
     * @param timestamp
     *            reset subscription to position closest to time in ms since epoch
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Topic or subscription does not exist
     * @throws NotAllowedException
     *             Command disallowed for requested resource
     * @throws PulsarAdminException
     *             Unexpected error
     */
    void resetCursor(String topic, String subName, long timestamp) throws PulsarAdminException;

    /**
     * Reset cursor position on a topic subscription
     *
     * @param topic
     *            topic name
     * @param subName
     *            Subscription name
     * @param timestamp
     *            reset subscription to position closest to time in ms since epoch
     */
    CompletableFuture<Void> resetCursorAsync(String topic, String subName, long timestamp);

    /**
     * Reset cursor position on a topic subscription
     *
     * @param topic
     *            topic name
     * @param subName
     *            Subscription name
     * @param messageId
     *            reset subscription to messageId (or previous nearest messageId if given messageId is not valid)
     *
     * @throws NotAuthorizedException
     *             Don't have admin permission
     * @throws NotFoundException
     *             Topic or subscription does not exist
     * @throws NotAllowedException
     *             Command disallowed for requested resource
     * @throws PulsarAdminException
     *             Unexpected error
     */
    void resetCursor(String topic, String subName, MessageId messageId) throws PulsarAdminException;

    /**
     * Reset cursor position on a topic subscription
     *
     * @param topic
     *            topic name
     * @param subName
     *            Subscription name
     * @param MessageId
     *            reset subscription to messageId (or previous nearest messageId if given messageId is not valid)
     */
    CompletableFuture<Void> resetCursorAsync(String topic, String subName, MessageId messageId);
}
