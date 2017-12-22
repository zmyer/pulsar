<!--

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

-->

You can use Pulsar's [admin API](../../admin-api/overview) to create and manage partitioned topics.

In all of the instructions and commands below, the topic name structure is:

{% include topic.html p="property" c="cluster" n="namespace" t="topic" %}

### Create

Partitioned topics in Pulsar must be explicitly created. When creating a new partitioned topic you need to provide a name for the topic as well as the desired number of partitions.

{% include admonition.html type="info" title="Global partitioned topics" content="
If you'd like to create a global partitioned topic, you need to create a partitioned topic using the instructions here and specify `global` as the cluster in the topic name.
" %}

#### pulsar-admin

You can create partitioned topics using the [`create-partitioned-topic`](../../reference/CliTools#pulsar-admin-persistent-create-partitioned-topic) command and specifying the topic name as an argument and the number of partitions using the `-p` or `--partitions` flag. Here's an example:

```shell
$ bin/pulsar-admin persistent create-partitioned-topic \
  persistent://my-property/my-cluster-my-namespace/my-topic \
  --partitions 4
```

#### REST API

{% endpoint PUT /admin/persistent/:property/:cluster/:namespace/:destination/partitions %}

[More info](../../reference/RestApi#/admin/persistent/:property/:cluster/:namespace/:destination/partitions)

#### Java

```java
String topicName = "persistent://my-property/my-cluster-my-namespace/my-topic";
int numPartitions = 4;
admin.persistentTopics().createPartitionedTopic(topicName, numPartitions);
```

### Get metadata

Partitioned topics have metadata associated with them that you can fetch as a JSON object. The following metadata fields are currently available:

Field | Meaning
:-----|:-------
`partitions` | The number of partitions into which the topic is divided

#### pulsar-admin

You can see the number of partitions in a partitioned topic using the [`get-partitioned-topic-metadata`](../../reference/CliTools#pulsar-admin-persistent-get-partitioned-topic) subcommand. Here's an example:

```shell
$ pulsar-admin persistent get-partitioned-topic-metadata \
  persistent://my-property/my-cluster-my-namespace/my-topic
{
  "partitions": 4
}
```

#### REST API

{% endpoint GET /admin/persistent/:property/:cluster:/:namespace/:destination/partitions %}

[More info](../../reference/RestApi#/admin/persistent/:property/:cluster/:namespace/:destination/partitions)

#### Java

```java
String topicName = "persistent://my-property/my-cluster-my-namespace/my-topic";
admin.persistentTopics().getPartitionedTopicMetadata(topicName);
```

### Update

You can update the number of partitions on an existing partitioned topic *if* the topic is non-global. To update, the new number of partitions must be greater than the existing number.

Decrementing the number of partitions would deleting the topic, which is not supported in Pulsar.

Already created partitioned producers and consumers can’t see newly created partitions and it requires to recreate them at application so, newly created producers and consumers can connect to newly added partitions as well. Therefore, it can violate partition ordering at producers until all producers are restarted at application.

#### pulsar-admin

Partitioned topics can be updated using the [`update-partitioned-topic`](../../reference/CliTools#pulsar-admin-persistent-update-partitioned-topic) command.

```shell
$ pulsar-admin persistent update-partitioned-topic \
  persistent://my-property/my-cluster-my-namespace/my-topic \
  --partitions 8
```

#### REST API

{% endpoint POST /admin/persistent/:property/:cluster/:namespace/:destination/partitions %}

[More info](../../reference/RestApi#/admin/persistent/:property/:cluster/:namespace/:destination/partitions)

#### Java

```java
admin.persistentTopics().updatePartitionedTopic(persistentTopic, numPartitions);
```

### Delete

#### pulsar-admin

Partitioned topics can be deleted using the [`delete-partitioned-topic`](../../reference/CliTools#pulsar-admin-persistent-delete-partitioned-topic) command, specifying the topic by name:

```shell
$ bin/pulsar-admin persistent delete-partitioned-topic \
  persistent://my-property/my-cluster-my-namespace/my-topic
```

#### REST API

{% endpoint DELETE /admin/persistent/:property/:cluster/:namespace/:destination/partitions %}

[More info](../../reference/RestApi#/admin/persistent/:property/:cluster/:namespace/:destination/partitions)

#### Java

```java
admin.persistentTopics().delete(persistentTopic);
```

### List

It provides a list of persistent topics existing under a given namespace.  

#### pulsar-admin

```shell
$ pulsar-admin persistent list prop-1/cluster-1/namespace
persistent://property/cluster/namespace/topic
persistent://property/cluster/namespace/topic
```

#### REST API

{% endpoint GET /admin/persistent/:property/:cluster/:namespace %}

[More info](../../reference/RestApi#/admin/persistent/:property/:cluster/:namespace)

#### Java

```java
admin.persistentTopics().getList(namespace);
```

### Stats

It shows current statistics of a given partitioned topic. Here's an example payload:

```json
{
  "msgRateIn": 4641.528542257553,
  "msgThroughputIn": 44663039.74947473,
  "msgRateOut": 0,
  "msgThroughputOut": 0,
  "averageMsgSize": 1232439.816728665,
  "storageSize": 135532389160,
  "publishers": [
    {
      "msgRateIn": 57.855383881403576,
      "msgThroughputIn": 558994.7078932219,
      "averageMsgSize": 613135,
      "producerId": 0,
      "producerName": null,
      "address": null,
      "connectedSince": null
    }
  ],
  "subscriptions": {
    "my-topic_subscription": {
      "msgRateOut": 0,
      "msgThroughputOut": 0,
      "msgBacklog": 116632,
      "type": null,
      "msgRateExpired": 36.98245516804671,
      "consumers": []
    }
  },
  "replication": {}
}
```

The following stats are available:

{% include stats.html id="partitioned_topics" %}

#### pulsar-admin

[`partitioned-stats`](../../reference/CliTools#pulsar-admin-persistent-partitioned-stats)

```shell
$ pulsar-admin persistent partitioned-stats \
  persistent://test-property/cl1/ns1/tp1 \
  --per-partition        
```

#### REST API

{% endpoint GET /admin/persistent/:property/:cluster/:namespace/:destination/partitioned-stats %}

[More info](../../reference/RestApi#/admin/persistent/:property/:cluster/:namespace/:destination/partitioned-stats)

#### Java

```java
admin.persistentTopics().getStats(persistentTopic);
```

### Internal stats

It shows detailed statistics of a topic.

{% include stats.html id="topics" %}

```json
{
  "entriesAddedCounter": 20449518,
  "numberOfEntries": 3233,
  "totalSize": 331482,
  "currentLedgerEntries": 3233,
  "currentLedgerSize": 331482,
  "lastLedgerCreatedTimestamp": "2016-06-29 03:00:23.825",
  "lastLedgerCreationFailureTimestamp": null,
  "waitingCursorsCount": 1,
  "pendingAddEntriesCount": 0,
  "lastConfirmedEntry": "324711539:3232",
  "state": "LedgerOpened",
  "ledgers": [
    {
      "ledgerId": 324711539,
      "entries": 0,
      "size": 0
    }
  ],
  "cursors": {
    "my-subscription": {
      "markDeletePosition": "324711539:3133",
      "readPosition": "324711539:3233",
      "waitingReadOp": true,
      "pendingReadOps": 0,
      "messagesConsumedCounter": 20449501,
      "cursorLedger": 324702104,
      "cursorLedgerLastEntry": 21,
      "individuallyDeletedMessages": "[(324711539:3134‥324711539:3136], (324711539:3137‥324711539:3140], ]",
      "lastLedgerSwitchTimestamp": "2016-06-29 01:30:19.313",
      "state": "Open"
    }
  }
}
```

#### pulsar-admin

```shell
$ pulsar-admin persistent stats-internal \
  persistent://test-property/cl1/ns1/tp1
```

#### REST API

{% endpoint GET /admin/persistent/:property/:cluster/:namespace/:destination/internalStats %}

[More info](../../reference/RestApi#/admin/persistent/:property/:cluster/:namespace/:destination/internalStats)

#### Java

```java
admin.persistentTopics().getInternalStats(persistentTopic);
```
