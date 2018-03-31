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

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogWriter;
import org.HdrHistogram.Recorder;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.CompressionType;
import org.apache.pulsar.client.api.CryptoKeyReader;
import org.apache.pulsar.client.api.EncryptionKeyInfo;
import org.apache.pulsar.client.api.MessageRoutingMode;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.testclient.utils.PaddingDecimalFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.RateLimiter;

import io.netty.util.concurrent.DefaultThreadFactory;

public class PerformanceProducer {

    private static final ExecutorService executor = Executors
            .newCachedThreadPool(new DefaultThreadFactory("pulsar-perf-producer-exec"));

    private static final LongAdder messagesSent = new LongAdder();
    private static final LongAdder bytesSent = new LongAdder();

    private static Recorder recorder = new Recorder(TimeUnit.SECONDS.toMillis(120000), 5);
    private static Recorder cumulativeRecorder = new Recorder(TimeUnit.SECONDS.toMillis(120000), 5);

    static class Arguments {

        @Parameter(names = { "-h", "--help" }, description = "Help message", help = true)
        boolean help;

        @Parameter(names = { "--conf-file" }, description = "Configuration file")
        public String confFile;

        @Parameter(description = "persistent://prop/cluster/ns/my-topic", required = true)
        public List<String> topics;

        @Parameter(names = { "-r", "--rate" }, description = "Publish rate msg/s across topics")
        public int msgRate = 100;

        @Parameter(names = { "-s", "--size" }, description = "Message size")
        public int msgSize = 1024;

        @Parameter(names = { "-t", "--num-topic" }, description = "Number of topics")
        public int numTopics = 1;

        @Parameter(names = { "-n", "--num-producers" }, description = "Number of producers (per topic)")
        public int numProducers = 1;

        @Parameter(names = { "-u", "--service-url" }, description = "Pulsar Service URL")
        public String serviceURL;

        @Parameter(names = { "--auth_plugin" }, description = "Authentication plugin class name")
        public String authPluginClassName;

        @Parameter(names = {
                "--auth_params" }, description = "Authentication parameters, e.g., \"key1:val1,key2:val2\"")
        public String authParams;

        @Parameter(names = { "-o", "--max-outstanding" }, description = "Max number of outstanding messages")
        public int maxOutstanding = 1000;

        @Parameter(names = { "-c",
                "--max-connections" }, description = "Max number of TCP connections to a single broker")
        public int maxConnections = 100;

        @Parameter(names = { "-m",
                "--num-messages" }, description = "Number of messages to publish in total. If 0, it will keep publishing")
        public long numMessages = 0;

        @Parameter(names = { "-i",
                "--stats-interval-seconds" }, description = "Statistics Interval Seconds. If 0, statistics will be disabled")
        public long statsIntervalSeconds = 0;

        @Parameter(names = { "-z", "--compression" }, description = "Compress messages payload")
        public CompressionType compression = CompressionType.NONE;

        @Parameter(names = { "-f", "--payload-file" }, description = "Use payload from a file instead of empty buffer")
        public String payloadFilename = null;
        @Parameter(names = { "-b",
                "--batch-time-window" }, description = "Batch messages in 'x' ms window (Default: 1ms)")
        public long batchTime = 1;

        @Parameter(names = { "-time",
                "--test-duration" }, description = "Test duration in secs. If 0, it will keep publishing")
        public long testTime = 0;

        @Parameter(names = {
                "--use-tls" }, description = "Use TLS encryption on the connection")
        public boolean useTls;

        @Parameter(names = {
                "--trust-cert-file" }, description = "Path for the trusted TLS certificate file")
        public String tlsTrustCertsFilePath = "";

        @Parameter(names = { "-k", "--encryption-key-name" }, description = "The public key name to encrypt payload")
        public String encKeyName = null;

        @Parameter(names = { "-v",
                "--encryption-key-value-file" }, description = "The file which contains the public key to encrypt payload")
        public String encKeyFile = null;

    }

    public static void main(String[] args) throws Exception {

        final Arguments arguments = new Arguments();
        JCommander jc = new JCommander(arguments);
        jc.setProgramName("pulsar-perf-producer");

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

        if (arguments.topics.size() != 1) {
            System.out.println("Only one topic name is allowed");
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

        arguments.testTime = TimeUnit.SECONDS.toMillis(arguments.testTime);

        // Dump config variables
        ObjectMapper m = new ObjectMapper();
        ObjectWriter w = m.writerWithDefaultPrettyPrinter();
        log.info("Starting Pulsar perf producer with config: {}", w.writeValueAsString(arguments));

        // Read payload data from file if needed
        byte payloadData[];
        if (arguments.payloadFilename != null) {
            payloadData = Files.readAllBytes(Paths.get(arguments.payloadFilename));
        } else {
            payloadData = new byte[arguments.msgSize];
        }

        // Now processing command line arguments
        String prefixTopicName = arguments.topics.get(0);
        List<Future<Producer<byte[]>>> futures = Lists.newArrayList();

        ClientBuilder clientBuilder = PulsarClient.builder() //
                .serviceUrl(arguments.serviceURL) //
                .connectionsPerBroker(arguments.maxConnections) //
                .ioThreads(Runtime.getRuntime().availableProcessors()) //
                .statsInterval(arguments.statsIntervalSeconds, TimeUnit.SECONDS) //
                .enableTls(arguments.useTls) //
                .tlsTrustCertsFilePath(arguments.tlsTrustCertsFilePath);

        if (isNotBlank(arguments.authPluginClassName)) {
            clientBuilder.authentication(arguments.authPluginClassName, arguments.authParams);
        }

        class EncKeyReader implements CryptoKeyReader {

            EncryptionKeyInfo keyInfo = new EncryptionKeyInfo();

            EncKeyReader(byte[] value) {
                keyInfo.setKey(value);
            }

            @Override
            public EncryptionKeyInfo getPublicKey(String keyName, Map<String, String> keyMeta) {
                if (keyName.equals(arguments.encKeyName)) {
                    return keyInfo;
                }
                return null;
            }

            @Override
            public EncryptionKeyInfo getPrivateKey(String keyName, Map<String, String> keyMeta) {
                return null;
            }
        }
        PulsarClient client = clientBuilder.build();
        ProducerBuilder<byte[]> producerBuilder = client.newProducer() //
                .sendTimeout(0, TimeUnit.SECONDS) //
                .compressionType(arguments.compression) //
                .maxPendingMessages(arguments.maxOutstanding) //
                // enable round robin message routing if it is a partitioned topic
                .messageRoutingMode(MessageRoutingMode.RoundRobinPartition);

        if (arguments.batchTime > 0) {
            producerBuilder.batchingMaxPublishDelay(arguments.batchTime, TimeUnit.MILLISECONDS).enableBatching(true);
        }

        // Block if queue is full else we will start seeing errors in sendAsync
        producerBuilder.blockIfQueueFull(true);

        if (arguments.encKeyName != null) {
            producerBuilder.addEncryptionKey(arguments.encKeyName);
            byte[] pKey = Files.readAllBytes(Paths.get(arguments.encKeyFile));
            EncKeyReader keyReader = new EncKeyReader(pKey);
            producerBuilder.cryptoKeyReader(keyReader);
        }

        for (int i = 0; i < arguments.numTopics; i++) {
            String topic = (arguments.numTopics == 1) ? prefixTopicName : String.format("%s-%d", prefixTopicName, i);
            log.info("Adding {} publishers on topic {}", arguments.numProducers, topic);

            for (int j = 0; j < arguments.numProducers; j++) {
                futures.add(producerBuilder.clone().topic(topic).createAsync());
            }
        }

        final List<Producer<byte[]>> producers = Lists.newArrayListWithCapacity(futures.size());
        for (Future<Producer<byte[]>> future : futures) {
            producers.add(future.get());
        }

        log.info("Created {} producers", producers.size());

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                printAggregatedStats();
            }
        });

        Collections.shuffle(producers);
        AtomicBoolean isDone = new AtomicBoolean();

        executor.submit(() -> {
            try {
                RateLimiter rateLimiter = RateLimiter.create(arguments.msgRate);

                long startTime = System.currentTimeMillis();

                // Send messages on all topics/producers
                long totalSent = 0;
                while (true) {
                    for (Producer<byte[]> producer : producers) {
                        if (arguments.testTime > 0) {
                            if (System.currentTimeMillis() - startTime > arguments.testTime) {
                                log.info("------------------- DONE -----------------------");
                                printAggregatedStats();
                                isDone.set(true);
                                Thread.sleep(5000);
                                System.exit(0);
                            }
                        }

                        if (arguments.numMessages > 0) {
                            if (totalSent++ >= arguments.numMessages) {
                                log.info("------------------- DONE -----------------------");
                                printAggregatedStats();
                                isDone.set(true);
                                Thread.sleep(5000);
                                System.exit(0);
                            }
                        }
                        rateLimiter.acquire();

                        final long sendTime = System.nanoTime();

                        producer.sendAsync(payloadData).thenRun(() -> {
                            messagesSent.increment();
                            bytesSent.add(payloadData.length);

                            long latencyMicros = NANOSECONDS.toMicros(System.nanoTime() - sendTime);
                            recorder.recordValue(latencyMicros);
                            cumulativeRecorder.recordValue(latencyMicros);
                        }).exceptionally(ex -> {
                            log.warn("Write error on message", ex);
                            System.exit(-1);
                            return null;
                        });
                    }
                }
            } catch (Throwable t) {
                log.error("Got error", t);
            }
        });

        // Print report stats
        long oldTime = System.nanoTime();

        Histogram reportHistogram = null;

        String statsFileName = "perf-producer-" + System.currentTimeMillis() + ".hgrm";
        log.info("Dumping latency stats to {}", statsFileName);

        PrintStream histogramLog = new PrintStream(new FileOutputStream(statsFileName), false);
        HistogramLogWriter histogramLogWriter = new HistogramLogWriter(histogramLog);

        // Some log header bits
        histogramLogWriter.outputLogFormatVersion();
        histogramLogWriter.outputLegend();

        while (true) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                break;
            }

            if (isDone.get()) {
                break;
            }

            long now = System.nanoTime();
            double elapsed = (now - oldTime) / 1e9;

            double rate = messagesSent.sumThenReset() / elapsed;
            double throughput = bytesSent.sumThenReset() / elapsed / 1024 / 1024 * 8;

            reportHistogram = recorder.getIntervalHistogram(reportHistogram);

            log.info(
                    "Throughput produced: {}  msg/s --- {} Mbit/s --- Latency: mean: {} ms - med: {} - 95pct: {} - 99pct: {} - 99.9pct: {} - 99.99pct: {} - Max: {}",
                    throughputFormat.format(rate), throughputFormat.format(throughput),
                    dec.format(reportHistogram.getMean() / 1000.0),
                    dec.format(reportHistogram.getValueAtPercentile(50) / 1000.0),
                    dec.format(reportHistogram.getValueAtPercentile(95) / 1000.0),
                    dec.format(reportHistogram.getValueAtPercentile(99) / 1000.0),
                    dec.format(reportHistogram.getValueAtPercentile(99.9) / 1000.0),
                    dec.format(reportHistogram.getValueAtPercentile(99.99) / 1000.0),
                    dec.format(reportHistogram.getMaxValue() / 1000.0));

            histogramLogWriter.outputIntervalHistogram(reportHistogram);
            reportHistogram.reset();

            oldTime = now;
        }

        client.close();
    }

    private static void printAggregatedStats() {
        Histogram reportHistogram = cumulativeRecorder.getIntervalHistogram();

        log.info(
                "Aggregated latency stats --- Latency: mean: {} ms - med: {} - 95pct: {} - 99pct: {} - 99.9pct: {} - 99.99pct: {} - 99.999pct: {} - Max: {}",
                dec.format(reportHistogram.getMean() / 1000.0),
                dec.format(reportHistogram.getValueAtPercentile(50) / 1000.0),
                dec.format(reportHistogram.getValueAtPercentile(95) / 1000.0),
                dec.format(reportHistogram.getValueAtPercentile(99) / 1000.0),
                dec.format(reportHistogram.getValueAtPercentile(99.9) / 1000.0),
                dec.format(reportHistogram.getValueAtPercentile(99.99) / 1000.0),
                dec.format(reportHistogram.getValueAtPercentile(99.999) / 1000.0),
                dec.format(reportHistogram.getMaxValue() / 1000.0));
    }

    static final DecimalFormat throughputFormat = new PaddingDecimalFormat("0.0", 8);
    static final DecimalFormat dec = new PaddingDecimalFormat("0.000", 7);
    private static final Logger log = LoggerFactory.getLogger(PerformanceProducer.class);
}
