---
title: Pulsar adaptor for Apache Kafka
tags: [apache, kafka, wrapper]
---

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

Pulsar provides an easy option for applications that are currently written using the
[Apache Kafka](http://kafka.apache.org) Java client API.



## Using the Pulsar Kafka compatibility wrapper

In an existing application, change the regular Kafka client dependency and replace it with
the Pulsar Kafka wrapper:


Remove:

```xml
<dependency>
  <groupId>org.apache.kafka</groupId>
  <artifactId>kakfa-clients</artifactId>
  <version>0.10.2.1</version>
</dependency>
```

Include dependency for Pulsar Kafka wrapper:

```xml
<dependency>
  <groupId>org.apache.pulsar</groupId>
  <artifactId>pulsar-client-kafka</artifactId>
  <version>{{ site.current_version }}</version>
</dependency>
```

With the new dependency, the existing code should work without any changes. The only
thing that needs to be adjusted is the configuration, to make sure to point the
producers and consumers to Pulsar service rather than Kafka and to use a particular
Pulsar topic.

## Producer example

```java
// Topic needs to be a regular Pulsar topic
String topic = "persistent://sample/standalone/ns/my-topic";

Properties props = new Properties();
// Point to a Pulsar service
props.put("bootstrap.servers", "pulsar://localhost:6650");

props.put("key.serializer", IntegerSerializer.class.getName());
props.put("value.serializer", StringSerializer.class.getName());

Producer<Integer, String> producer = new KafkaProducer<>(props);

for (int i = 0; i < 10; i++) {
    producer.send(new ProducerRecord<Integer, String>(topic, i, "hello-" + i));
    log.info("Message {} sent successfully", i);
}

producer.close();
```

## Consumer example

```java
String topic = "persistent://sample/standalone/ns/my-topic";

Properties props = new Properties();
// Point to a Pulsar service
props.put("bootstrap.servers", "pulsar://localhost:6650");
props.put("group.id", "my-subscription-name");
props.put("enable.auto.commit", "false");
props.put("key.deserializer", IntegerDeserializer.class.getName());
props.put("value.deserializer", StringDeserializer.class.getName());

Consumer<Integer, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Arrays.asList(topic));

while (true) {
    ConsumerRecords<Integer, String> records = consumer.poll(100);
    records.forEach(record -> {
        log.info("Received record: {}", record);
    });

    // Commit last offset
    consumer.commitSync();
}
```

## Complete Examples

You can find the complete producer and consumer examples
[here]({{ site.pulsar_repo }}/pulsar-client-kafka-compat/pulsar-client-kafka-tests/src/test/java/org/apache/pulsar/client/kafka/compat/examples).

## Compatibility matrix

Currently the Pulsar Kafka wrapper supports most of the operations offered by the Kafka API.

#### Producer

APIs:

| Producer Method                                                               | Supported | Notes                                                                    |
|:------------------------------------------------------------------------------|:----------|:-------------------------------------------------------------------------|
| `Future<RecordMetadata> send(ProducerRecord<K, V> record)`                    | Yes       | Currently no support for explicitly set the partition id when publishing |
| `Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback)` | Yes       |                                                                          |
| `void flush()`                                                                | Yes       |                                                                          |
| `List<PartitionInfo> partitionsFor(String topic)`                             | No        |                                                                          |
| `Map<MetricName, ? extends Metric> metrics()`                                 | No        |                                                                          |
| `void close()`                                                                | Yes       |                                                                          |
| `void close(long timeout, TimeUnit unit)`                                     | Yes       |                                                                          |

Properties:

| Config property                         | Supported | Notes                                                                         |
|:----------------------------------------|:----------|:------------------------------------------------------------------------------|
| `acks`                                  | Ignored   | Durability and quorum writes are configured at the namespace level            |
| `batch.size`                            | Ignored   |                                                                               |
| `block.on.buffer.full`                  | Yes       | If true it will block producer, otherwise give error                          |
| `bootstrap.servers`                     | Yes       | Needs to point to a single Pulsar service URL                                 |
| `buffer.memory`                         | Ignored   |                                                                               |
| `client.id`                             | Ignored   |                                                                               |
| `compression.type`                      | Yes       | Allows `gzip` and `lz4`. No `snappy`.                                         |
| `connections.max.idle.ms`               | Ignored   |                                                                               |
| `interceptor.classes`                   | Ignored   |                                                                               |
| `key.serializer`                        | Yes       |                                                                               |
| `linger.ms`                             | Yes       | Controls the group commit time when batching messages                         |
| `max.block.ms`                          | Ignored   |                                                                               |
| `max.in.flight.requests.per.connection` | Ignored   | In Pulsar ordering is maintained even with multiple requests in flight        |
| `max.request.size`                      | Ignored   |                                                                               |
| `metric.reporters`                      | Ignored   |                                                                               |
| `metrics.num.samples`                   | Ignored   |                                                                               |
| `metrics.sample.window.ms`              | Ignored   |                                                                               |
| `partitioner.class`                     | Ignored   |                                                                               |
| `receive.buffer.bytes`                  | Ignored   |                                                                               |
| `reconnect.backoff.ms`                  | Ignored   |                                                                               |
| `request.timeout.ms`                    | Ignored   |                                                                               |
| `retries`                               | Ignored   | Pulsar client retries with exponential backoff until the send timeout expires |
| `send.buffer.bytes`                     | Ignored   |                                                                               |
| `timeout.ms`                            | Ignored   |                                                                               |
| `value.serializer`                      | Yes       |                                                                               |


#### Consumer

APIs:

| Consumer Method                                                                                         | Supported | Notes |
|:--------------------------------------------------------------------------------------------------------|:----------|:------|
| `Set<TopicPartition> assignment()`                                                                      | No        |       |
| `Set<String> subscription()`                                                                            | Yes       |       |
| `void subscribe(Collection<String> topics)`                                                             | Yes       |       |
| `void subscribe(Collection<String> topics, ConsumerRebalanceListener callback)`                         | No        |       |
| `void assign(Collection<TopicPartition> partitions)`                                                    | No        |       |
| `void subscribe(Pattern pattern, ConsumerRebalanceListener callback)`                                   | No        |       |
| `void unsubscribe()`                                                                                    | Yes       |       |
| `ConsumerRecords<K, V> poll(long timeoutMillis)`                                                        | Yes       |       |
| `void commitSync()`                                                                                     | Yes       |       |
| `void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets)`                                       | Yes       |       |
| `void commitAsync()`                                                                                    | Yes       |       |
| `void commitAsync(OffsetCommitCallback callback)`                                                       | Yes       |       |
| `void commitAsync(Map<TopicPartition, OffsetAndMetadata> offsets, OffsetCommitCallback callback)`       | Yes       |       |
| `void seek(TopicPartition partition, long offset)`                                                      | Yes        |       |
| `void seekToBeginning(Collection<TopicPartition> partitions)`                                           | Yes        |       |
| `void seekToEnd(Collection<TopicPartition> partitions)`                                                 | Yes        |       |
| `long position(TopicPartition partition)`                                                               | Yes       |       |
| `OffsetAndMetadata committed(TopicPartition partition)`                                                 | Yes       |       |
| `Map<MetricName, ? extends Metric> metrics()`                                                           | No        |       |
| `List<PartitionInfo> partitionsFor(String topic)`                                                       | No        |       |
| `Map<String, List<PartitionInfo>> listTopics()`                                                         | No        |       |
| `Set<TopicPartition> paused()`                                                                          | No        |       |
| `void pause(Collection<TopicPartition> partitions)`                                                     | No        |       |
| `void resume(Collection<TopicPartition> partitions)`                                                    | No        |       |
| `Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(Map<TopicPartition, Long> timestampsToSearch)` | No        |       |
| `Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions)`                     | No        |       |
| `Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions)`                           | No        |       |
| `void close()`                                                                                          | Yes       |       |
| `void close(long timeout, TimeUnit unit)`                                                               | Yes       |       |
| `void wakeup()`                                                                                         | No        |       |

Properties:

| Config property                 | Supported | Notes                                                 |
|:--------------------------------|:----------|:------------------------------------------------------|
| `group.id`                      | Yes       | Maps to a Pulsar subscription name                    |
| `max.poll.records`              | Ignored   |                                                       |
| `max.poll.interval.ms`          | Ignored   | Messages are "pushed" from broker                     |
| `session.timeout.ms`            | Ignored   |                                                       |
| `heartbeat.interval.ms`         | Ignored   |                                                       |
| `bootstrap.servers`             | Yes       | Needs to point to a single Pulsar service URL         |
| `enable.auto.commit`            | Yes       |                                                       |
| `auto.commit.interval.ms`       | Ignored   | With auto-commit, acks are sent immediately to broker |
| `partition.assignment.strategy` | Ignored   |                                                       |
| `auto.offset.reset`             | Ignored   |                                                       |
| `fetch.min.bytes`               | Ignored   |                                                       |
| `fetch.max.bytes`               | Ignored   |                                                       |
| `fetch.max.wait.ms`             | Ignored   |                                                       |
| `metadata.max.age.ms`           | Ignored   |                                                       |
| `max.partition.fetch.bytes`     | Ignored   |                                                       |
| `send.buffer.bytes`             | Ignored   |                                                       |
| `receive.buffer.bytes`          | Ignored   |                                                       |
| `client.id`                     | Ignored   |                                                       |


## Custom Pulsar configurations

You can configure Pulsar authentication provider directly from the Kafka properties.

Properties:

| Config property                        | Default | Notes                                                                                  |
|:---------------------------------------|:--------|:---------------------------------------------------------------------------------------|
| `pulsar.authentication.class`          |         | Configure to auth provider. Eg. `org.apache.pulsar.client.impl.auth.AuthenticationTls` |
| `pulsar.use.tls`                       | `false` | Enable TLS transport encryption                                                        |
| `pulsar.tls.trust.certs.file.path`     |         | Path for the TLS trust certificate store                                               |
| `pulsar.tls.allow.insecure.connection` | `false` | Accept self-signed certificates from brokers                                           |
