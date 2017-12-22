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

{% include admonition.html type="success" title='Notice' content="
This feature is still in experimental mode and implementation details may change in future release.
" %}

As name suggests, non-persist topic does not persist messages into any durable storage disk unlike persistent topic where messages are durably persisted on multiple disks. 

Therefore, if you are using persistent delivery, messages are persisted to disk/database so that they will survive a broker restart or subscriber failover. While using non-persistent delivery, if you kill a broker or subscriber is disconnected then subscriber will lose all in-transit messages. So, client may see message loss with non-persistent topic.

- In non-persistent topic, as soon as broker receives published message, it immediately delivers this message to all connected subscribers without persisting them into any storage. So, if subscriber gets disconnected with broker then broker will not be able to deliver those in-transit messages and subscribers will never be able to receive those messages again. Broker also drops a message for the consumer, if consumer does not have enough permit to consume message, or consumer TCP channel is not writable. Therefore, consumer receiver queue size (to accommodate enough permits) and TCP-receiver window size (to keep channel writable) should be configured properly to avoid message drop for that consumer.
- Broker only allows configured number of in-flight messages per client connection. So, if producer tries to publish messages higher than this rate, then broker silently drops those new incoming messages without processing and delivering them to the subscribers. However, broker acknowledges with special message-id (`msg-id: -1:-1`) for those dropped messages to signal producer about the message drop.

#### Performance

Non-persistent messaging is usually faster than persistent messaging because broker does not persist messages and immediately sends ack back to producer as soon as that message deliver to all connected subscribers. Therefore, producer sees comparatively low publish latency with non-persistent topic.


#### Client API


A topic name will look like:

```
non-persistent://my-property/us-west/my-namespace/my-topic
```

Producer and consumer can connect to non-persistent topic in a similar way, as persistent topic except topic name must start with `non-persistent`.

Non-persistent topic supports all 3 different subscription-modes: **Exclusive**, **Shared**, **Failover** which are already explained in details at [GettingStarted](../../getting-started/ConceptsAndArchitecture). 


##### Consumer API

```java
PulsarClient client = PulsarClient.create("pulsar://localhost:6650");

Consumer consumer = client.subscribe(
            "non-persistent://sample/standalone/ns1/my-topic",
            "my-subscribtion-name");
```

##### Producer API

```java
PulsarClient client = PulsarClient.create("pulsar://localhost:6650");

Producer producer = client.createProducer(
            "non-persistent://sample/standalone/ns1/my-topic");
```

#### Broker configuration

Sometimes, there would be a need to configure few dedicated brokers in a cluster, to just serve non-persistent topics.

Broker configuration for enabling broker to own only configured type of topics  

```
# It disables broker to load persistent topics
enablePersistentTopics=false
# It enables broker to load non-persistent topics
enableNonPersistentTopics=true
```
