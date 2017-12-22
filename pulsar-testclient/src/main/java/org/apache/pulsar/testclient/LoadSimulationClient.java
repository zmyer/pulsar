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
package org.apache.pulsar.testclient;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.ClientConfiguration;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerConfiguration;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerConfiguration;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.util.concurrent.RateLimiter;

import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * LoadSimulationClient is used to simulate client load by maintaining producers and consumers for topics. Instances of
 * this class are controlled across a network via LoadSimulationController.
 */
public class LoadSimulationClient {
    private final static Logger log = LoggerFactory.getLogger(LoadSimulationClient.class);

    // Values for command encodings.
    public static final byte CHANGE_COMMAND = 0;
    public static final byte STOP_COMMAND = 1;
    public static final byte TRADE_COMMAND = 2;
    public static final byte CHANGE_GROUP_COMMAND = 3;
    public static final byte STOP_GROUP_COMMAND = 4;
    public static final byte FIND_COMMAND = 5;

    private final ExecutorService executor;
    // Map from a message size to a cached byte[] of that size.
    private final Map<Integer, byte[]> payloadCache;

    // Map from a full topic name to the TradeUnit created for that topic.
    private final Map<String, TradeUnit> topicsToTradeUnits;

    // Pulsar admin to create namespaces with.
    private final PulsarAdmin admin;

    // Pulsar client to create producers and consumers with.
    private final PulsarClient client;

    private final ProducerConfiguration producerConf;
    private final ConsumerConfiguration consumerConf;
    private final ClientConfiguration clientConf;
    private final int port;

    // A TradeUnit is a Consumer and Producer pair. The rate of message
    // consumption as well as size may be changed at
    // any time, and the TradeUnit may also be stopped.
    private static class TradeUnit {
        Future<Consumer> consumerFuture;
        final AtomicBoolean stop;
        final RateLimiter rateLimiter;

        // Creating a byte[] for every message is stressful for a client
        // machine, so in order to ensure that any
        // message size may be sent/changed while reducing object creation, the
        // byte[] is wrapped in an AtomicReference.
        final AtomicReference<byte[]> payload;
        final ProducerConfiguration producerConf;
        final PulsarClient client;
        final String topic;
        final Map<Integer, byte[]> payloadCache;

        public TradeUnit(final TradeConfiguration tradeConf, final PulsarClient client,
                final ProducerConfiguration producerConf, final ConsumerConfiguration consumerConf,
                final Map<Integer, byte[]> payloadCache) throws Exception {
            consumerFuture = client.subscribeAsync(tradeConf.topic, "Subscriber-" + tradeConf.topic, consumerConf);
            this.payload = new AtomicReference<>();
            this.producerConf = producerConf;
            this.payloadCache = payloadCache;
            this.client = client;
            topic = tradeConf.topic;

            // Add a byte[] of the appropriate size if it is not already present
            // in the cache.
            this.payload.set(payloadCache.computeIfAbsent(tradeConf.size, byte[]::new));
            rateLimiter = RateLimiter.create(tradeConf.rate);
            stop = new AtomicBoolean(false);
        }

        // Change the message rate/size according to the given configuration.
        public void change(final TradeConfiguration tradeConf) {
            rateLimiter.setRate(tradeConf.rate);
            this.payload.set(payloadCache.computeIfAbsent(tradeConf.size, byte[]::new));
        }

        // Attempt to create a Producer indefinitely. Useful for ensuring
        // messages continue to be sent after broker
        // restarts occur.
        private Producer getNewProducer() throws Exception {
            while (true) {
                try {
                    return client.createProducer(topic, producerConf);
                } catch (Exception e) {
                    Thread.sleep(10000);
                }
            }
        }

        private class MutableBoolean {
            public volatile boolean value = true;
        }

        public void start() throws Exception {
            Producer producer = getNewProducer();
            final Consumer consumer = consumerFuture.get();
            while (!stop.get()) {
                final MutableBoolean wellnessFlag = new MutableBoolean();
                final Function<Throwable, ? extends MessageId> exceptionHandler = e -> {
                    // Unset the well flag in the case of an exception so we can
                    // try to get a new Producer.
                    wellnessFlag.value = false;
                    return null;
                };
                while (!stop.get() && wellnessFlag.value) {
                    producer.sendAsync(payload.get()).exceptionally(exceptionHandler);
                    rateLimiter.acquire();
                }
                producer.closeAsync();
                if (!stop.get()) {
                    // The Producer failed due to an exception: attempt to get
                    // another producer.
                    producer = getNewProducer();
                } else {
                    // We are finished: close the consumer.
                    consumer.closeAsync();
                }
            }
        }
    }

    // JCommander arguments for starting a LoadSimulationClient.
    private static class MainArguments {
        @Parameter(names = { "-h", "--help" }, description = "Help message", help = true)
        boolean help;

        @Parameter(names = { "--port" }, description = "Port to listen on for controller", required = true)
        public int port;

        @Parameter(names = { "--service-url" }, description = "Pulsar Service URL", required = true)
        public String serviceURL;
    }

    // Configuration class for initializing or modifying TradeUnits.
    private static class TradeConfiguration {
        public byte command;
        public String topic;
        public double rate;
        public int size;
        public String tenant;
        public String group;

        public TradeConfiguration() {
            command = -1;
            rate = 100;
            size = 1024;
        }
    }

    // Handle input sent from a controller.
    private void handle(final Socket socket) throws Exception {
        final DataInputStream inputStream = new DataInputStream(socket.getInputStream());
        int command;
        while ((command = inputStream.read()) != -1) {
            handle((byte) command, inputStream, new DataOutputStream(socket.getOutputStream()));
        }
    }

    // Decode TradeConfiguration fields common for topic creation and
    // modification.
    private void decodeProducerOptions(final TradeConfiguration tradeConf, final DataInputStream inputStream)
            throws Exception {
        tradeConf.topic = inputStream.readUTF();
        tradeConf.size = inputStream.readInt();
        tradeConf.rate = inputStream.readDouble();
    }

    // Decode TradeConfiguration fields common for group commands.
    private void decodeGroupOptions(final TradeConfiguration tradeConf, final DataInputStream inputStream)
            throws Exception {
        tradeConf.tenant = inputStream.readUTF();
        tradeConf.group = inputStream.readUTF();
    }

    // Handle a command sent from a controller.
    private void handle(final byte command, final DataInputStream inputStream, final DataOutputStream outputStream)
            throws Exception {
        final TradeConfiguration tradeConf = new TradeConfiguration();
        tradeConf.command = command;
        switch (command) {
        case CHANGE_COMMAND:
            // Change the topic's settings if it exists.
            decodeProducerOptions(tradeConf, inputStream);
            if (topicsToTradeUnits.containsKey(tradeConf.topic)) {
                topicsToTradeUnits.get(tradeConf.topic).change(tradeConf);
            }
            break;
        case STOP_COMMAND:
            // Stop the topic if it exists.
            tradeConf.topic = inputStream.readUTF();
            if (topicsToTradeUnits.containsKey(tradeConf.topic)) {
                topicsToTradeUnits.get(tradeConf.topic).stop.set(true);
            }
            break;
        case TRADE_COMMAND:
            // Create the topic. It is assumed that the topic does not already exist.
            decodeProducerOptions(tradeConf, inputStream);
            final TradeUnit tradeUnit = new TradeUnit(tradeConf, client, producerConf, consumerConf, payloadCache);
            topicsToTradeUnits.put(tradeConf.topic, tradeUnit);
            executor.submit(() -> {
                try {
                    final String topic = tradeConf.topic;
                    final String namespace = topic.substring("persistent://".length(), topic.lastIndexOf('/'));
                    try {
                        admin.namespaces().createNamespace(namespace);
                    } catch (PulsarAdminException.ConflictException e) {
                        // Ignore, already created namespace.
                    }
                    tradeUnit.start();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
            break;
        case CHANGE_GROUP_COMMAND:
            // Change the settings of all topics belonging to a group.
            decodeGroupOptions(tradeConf, inputStream);
            tradeConf.size = inputStream.readInt();
            tradeConf.rate = inputStream.readDouble();
            // See if a topic belongs to this tenant and group using this regex.
            final String groupRegex = ".*://" + tradeConf.tenant + "/.*/" + tradeConf.group + "-.*/.*";
            for (Map.Entry<String, TradeUnit> entry : topicsToTradeUnits.entrySet()) {
                final String destination = entry.getKey();
                final TradeUnit unit = entry.getValue();
                if (destination.matches(groupRegex)) {
                    unit.change(tradeConf);
                }
            }
            break;
        case STOP_GROUP_COMMAND:
            // Stop all topics belonging to a group.
            decodeGroupOptions(tradeConf, inputStream);
            // See if a topic belongs to this tenant and group using this regex.
            final String regex = ".*://" + tradeConf.tenant + "/.*/" + tradeConf.group + "-.*/.*";
            for (Map.Entry<String, TradeUnit> entry : topicsToTradeUnits.entrySet()) {
                final String destination = entry.getKey();
                final TradeUnit unit = entry.getValue();
                if (destination.matches(regex)) {
                    unit.stop.set(true);
                }
            }
            break;
        case FIND_COMMAND:
            // Write a single boolean indicating if the topic was found.
            outputStream.writeBoolean(topicsToTradeUnits.containsKey(inputStream.readUTF()));
            outputStream.flush();
            break;
        default:
            throw new IllegalArgumentException("Unrecognized command code received: " + command);
        }
    }

    // Make listener as lightweight as possible.
    private static final MessageListener ackListener = Consumer::acknowledgeAsync;

    /**
     * Create a LoadSimulationClient with the given JCommander arguments.
     *
     * @param arguments
     *            Arguments to configure this from.
     */
    public LoadSimulationClient(final MainArguments arguments) throws Exception {
        payloadCache = new ConcurrentHashMap<>();
        topicsToTradeUnits = new ConcurrentHashMap<>();

        clientConf = new ClientConfiguration();

        clientConf.setConnectionsPerBroker(4);
        clientConf.setIoThreads(Runtime.getRuntime().availableProcessors());

        // Disable stats on the clients to reduce CPU/memory usage.
        clientConf.setStatsInterval(0, TimeUnit.SECONDS);

        producerConf = new ProducerConfiguration();

        // Disable timeout.
        producerConf.setSendTimeout(0, TimeUnit.SECONDS);

        producerConf.setMessageRoutingMode(ProducerConfiguration.MessageRoutingMode.RoundRobinPartition);

        // Enable batching.
        producerConf.setBatchingMaxPublishDelay(1, TimeUnit.MILLISECONDS);
        producerConf.setBatchingEnabled(true);
        consumerConf = new ConsumerConfiguration();
        consumerConf.setMessageListener(ackListener);
        admin = new PulsarAdmin(new URL(arguments.serviceURL), clientConf);
        client = new PulsarClientImpl(arguments.serviceURL, clientConf);
        port = arguments.port;
        executor = Executors.newCachedThreadPool(new DefaultThreadFactory("test-client"));
    }

    /**
     * Start a client with command line arguments.
     *
     * @param args
     *            Command line arguments to pass in.
     */
    public static void main(String[] args) throws Exception {
        final MainArguments mainArguments = new MainArguments();
        final JCommander jc = new JCommander(mainArguments);
        try {
            jc.parse(args);
        } catch (ParameterException e) {
            jc.usage();
            throw e;
        }
        (new LoadSimulationClient(mainArguments)).run();
    }

    /**
     * Start listening for controller commands to create producers and consumers.
     */
    public void run() throws Exception {
        final ServerSocket serverSocket = new ServerSocket(port);

        while (true) {
            // Technically, two controllers can be connected simultaneously, but
            // non-sequential handling of commands
            // has not been tested or considered and is not recommended.
            log.info("Listening for controller command...");
            final Socket socket = serverSocket.accept();
            log.info("Connected to {}", socket.getInetAddress().getHostName());
            executor.submit(() -> {
                try {
                    handle(socket);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
    }
}
