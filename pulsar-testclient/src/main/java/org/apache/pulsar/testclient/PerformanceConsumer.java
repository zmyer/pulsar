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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.apache.pulsar.client.api.ClientConfiguration;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerConfiguration;
import org.apache.pulsar.client.api.CryptoKeyReader;
import org.apache.pulsar.client.api.EncryptionKeyInfo;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageListener;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.common.naming.DestinationName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.RateLimiter;

public class PerformanceConsumer {
    private static final LongAdder messagesReceived = new LongAdder();
    private static final LongAdder bytesReceived = new LongAdder();
    private static final DecimalFormat dec = new DecimalFormat("0.000");

    private static Recorder recorder = new Recorder(TimeUnit.DAYS.toMillis(10), 5);
    private static Recorder cumulativeRecorder = new Recorder(TimeUnit.DAYS.toMillis(10), 5);


    static class Arguments {

        @Parameter(names = { "-h", "--help" }, description = "Help message", help = true)
        boolean help;

        @Parameter(names = { "--conf-file" }, description = "Configuration file")
        public String confFile;

        @Parameter(description = "persistent://prop/cluster/ns/my-topic", required = true)
        public List<String> topic;

        @Parameter(names = { "-t", "--num-topics" }, description = "Number of topics")
        public int numDestinations = 1;

        @Parameter(names = { "-n", "--num-consumers" }, description = "Number of consumers (per topic)")
        public int numConsumers = 1;

        @Parameter(names = { "-s", "--subscriber-name" }, description = "Subscriber name prefix")
        public String subscriberName = "sub";
        
        @Parameter(names = { "-st", "--subscription-type" }, description = "Subscriber name prefix")
        public SubscriptionType subscriptionType = SubscriptionType.Exclusive;

        @Parameter(names = { "-r", "--rate" }, description = "Simulate a slow message consumer (rate in msg/s)")
        public double rate = 0;

        @Parameter(names = { "-q", "--receiver-queue-size" }, description = "Size of the receiver queue")
        public int receiverQueueSize = 1000;

        @Parameter(names = { "-c",
                "--max-connections" }, description = "Max number of TCP connections to a single broker")
        public int maxConnections = 100;

        @Parameter(names = { "-i",
                "--stats-interval-seconds" }, description = "Statistics Interval Seconds. If 0, statistics will be disabled")
        public long statsIntervalSeconds = 0;

        @Parameter(names = { "-u", "--service-url" }, description = "Pulsar Service URL")
        public String serviceURL;

        @Parameter(names = { "--auth_plugin" }, description = "Authentication plugin class name")
        public String authPluginClassName;

        @Parameter(names = {
                "--auth_params" }, description = "Authentication parameters, e.g., \"key1:val1,key2:val2\"")
        public String authParams;

        @Parameter(names = {
                "--use-tls" }, description = "Use TLS encryption on the connection")
        public boolean useTls;

        @Parameter(names = {
                "--trust-cert-file" }, description = "Path for the trusted TLS certificate file")
        public String tlsTrustCertsFilePath = "";

        @Parameter(names = { "-k", "--encryption-key-name" }, description = "The private key name to decrypt payload")
        public String encKeyName = null;

        @Parameter(names = { "-v",
                "--encryption-key-value-file" }, description = "The file which contains the private key to decrypt payload")
        public String encKeyFile = null;
    }

    public static void main(String[] args) throws Exception {
        final Arguments arguments = new Arguments();
        JCommander jc = new JCommander(arguments);
        jc.setProgramName("pulsar-perf-consumer");

        try {
            jc.parse(args);
        } catch (ParameterException e) {
            System.out.println(e.getMessage());
            jc.usage();
            System.exit(-1);
        }

        if (arguments.help) {
            jc.usage();
            System.exit(-1);
        }

        if (arguments.topic.size() != 1) {
            System.out.println("Only one destination name is allowed");
            jc.usage();
            System.exit(-1);
        }

        if (arguments.confFile != null) {
            Properties prop = new Properties(System.getProperties());
            prop.load(new FileInputStream(arguments.confFile));

            if (arguments.serviceURL == null) {
                arguments.serviceURL = prop.getProperty("brokerServiceUrl");
            }

            if (arguments.serviceURL == null) {
                arguments.serviceURL = prop.getProperty("webServiceUrl");
            }

            // fallback to previous-version serviceUrl property to maintain backward-compatibility
            if (arguments.serviceURL == null) {
                arguments.serviceURL = prop.getProperty("serviceUrl", "http://localhost:8080/");
            }

            if (arguments.authPluginClassName == null) {
                arguments.authPluginClassName = prop.getProperty("authPlugin", null);
            }

            if (arguments.authParams == null) {
                arguments.authParams = prop.getProperty("authParams", null);
            }

            if (arguments.useTls == false) {
                arguments.useTls = Boolean.parseBoolean(prop.getProperty("useTls"));
            }

            if (isBlank(arguments.tlsTrustCertsFilePath)) {
                arguments.tlsTrustCertsFilePath = prop.getProperty("tlsTrustCertsFilePath", "");
            }
        }

        // Dump config variables
        ObjectMapper m = new ObjectMapper();
        ObjectWriter w = m.writerWithDefaultPrettyPrinter();
        log.info("Starting Pulsar performance consumer with config: {}", w.writeValueAsString(arguments));

        final DestinationName prefixDestinationName = DestinationName.get(arguments.topic.get(0));

        final RateLimiter limiter = arguments.rate > 0 ? RateLimiter.create(arguments.rate) : null;

        MessageListener listener = new MessageListener() {
            public void received(Consumer consumer, Message msg) {
                messagesReceived.increment();
                bytesReceived.add(msg.getData().length);

                if (limiter != null) {
                    limiter.acquire();
                }

                long latencyMillis = System.currentTimeMillis() - msg.getPublishTime();
                recorder.recordValue(latencyMillis);
                cumulativeRecorder.recordValue(latencyMillis);

                consumer.acknowledgeAsync(msg);
            }
        };

        ClientConfiguration clientConf = new ClientConfiguration();
        clientConf.setConnectionsPerBroker(arguments.maxConnections);
        clientConf.setStatsInterval(arguments.statsIntervalSeconds, TimeUnit.SECONDS);
        clientConf.setIoThreads(Runtime.getRuntime().availableProcessors());
        if (isNotBlank(arguments.authPluginClassName)) {
            clientConf.setAuthentication(arguments.authPluginClassName, arguments.authParams);
        }
        clientConf.setUseTls(arguments.useTls);
        clientConf.setTlsTrustCertsFilePath(arguments.tlsTrustCertsFilePath);
        PulsarClient pulsarClient = new PulsarClientImpl(arguments.serviceURL, clientConf);

        class EncKeyReader implements CryptoKeyReader {

            EncryptionKeyInfo keyInfo = new EncryptionKeyInfo();

            EncKeyReader(byte[] value) {
                keyInfo.setKey(value);
            }

            @Override
            public EncryptionKeyInfo getPublicKey(String keyName, Map<String, String> keyMeta) {
                return null;
            }

            @Override
            public EncryptionKeyInfo getPrivateKey(String keyName, Map<String, String> keyMeta) {
                if (keyName.equals(arguments.encKeyName)) {
                    return keyInfo;
                }
                return null;
            }
        }
        List<Future<Consumer>> futures = Lists.newArrayList();
        ConsumerConfiguration consumerConfig = new ConsumerConfiguration();
        consumerConfig.setMessageListener(listener);
        consumerConfig.setReceiverQueueSize(arguments.receiverQueueSize);
        consumerConfig.setSubscriptionType(arguments.subscriptionType);

        if (arguments.encKeyName != null) {
            byte[] pKey = Files.readAllBytes(Paths.get(arguments.encKeyFile));
            EncKeyReader keyReader = new EncKeyReader(pKey);
            consumerConfig.setCryptoKeyReader(keyReader);
        }

        for (int i = 0; i < arguments.numDestinations; i++) {
            final DestinationName destinationName = (arguments.numDestinations == 1) ? prefixDestinationName
                    : DestinationName.get(String.format("%s-%d", prefixDestinationName, i));
            log.info("Adding {} consumers on destination {}", arguments.numConsumers, destinationName);

            for (int j = 0; j < arguments.numConsumers; j++) {
                String subscriberName;
                if (arguments.numConsumers > 1) {
                    subscriberName = String.format("%s-%d", arguments.subscriberName, j);
                } else {
                    subscriberName = arguments.subscriberName;
                }

                futures.add(pulsarClient.subscribeAsync(destinationName.toString(), subscriberName, consumerConfig));
            }
        }

        for (Future<Consumer> future : futures) {
            future.get();
        }

        log.info("Start receiving from {} consumers on {} destinations", arguments.numConsumers,
                arguments.numDestinations);

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                printAggregatedStats();
            }
        });


        long oldTime = System.nanoTime();

        Histogram reportHistogram = null;


        while (true) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                break;
            }

            long now = System.nanoTime();
            double elapsed = (now - oldTime) / 1e9;
            double rate = messagesReceived.sumThenReset() / elapsed;
            double throughput = bytesReceived.sumThenReset() / elapsed * 8 / 1024 / 1024;

            log.info("Throughput received: {}  msg/s -- {} Mbit/s", dec.format(rate), dec.format(throughput));

            reportHistogram = recorder.getIntervalHistogram(reportHistogram);

            log.info(
                    "Throughput received: {}  msg/s -- {} Mbit/s --- Latency: mean: {} ms - med: {} - 95pct: {} - 99pct: {} - 99.9pct: {} - 99.99pct: {} - Max: {}",
                    dec.format(rate), dec.format(throughput),
                    dec.format(reportHistogram.getMean()),
                    dec.format(reportHistogram.getValueAtPercentile(50)),
                    dec.format(reportHistogram.getValueAtPercentile(95)),
                    dec.format(reportHistogram.getValueAtPercentile(99)),
                    dec.format(reportHistogram.getValueAtPercentile(99.9)),
                    dec.format(reportHistogram.getValueAtPercentile(99.99)),
                    dec.format(reportHistogram.getMaxValue()));

            reportHistogram.reset();
            oldTime = now;
        }

        pulsarClient.close();
    }
    private static void printAggregatedStats() {
        Histogram reportHistogram = cumulativeRecorder.getIntervalHistogram();

        log.info(
                "Aggregated latency stats --- Latency: mean: {} ms - med: {} - 95pct: {} - 99pct: {} - 99.9pct: {} - 99.99pct: {} - 99.999pct: {} - Max: {}",
                dec.format(reportHistogram.getMean() / 1000.0),
                dec.format(reportHistogram.getValueAtPercentile(50)),
                dec.format(reportHistogram.getValueAtPercentile(95)),
                dec.format(reportHistogram.getValueAtPercentile(99)),
                dec.format(reportHistogram.getValueAtPercentile(99.9)),
                dec.format(reportHistogram.getValueAtPercentile(99.99)),
                dec.format(reportHistogram.getValueAtPercentile(99.999)),
                dec.format(reportHistogram.getMaxValue()));
    }

    private static final Logger log = LoggerFactory.getLogger(PerformanceConsumer.class);
}
