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
package org.apache.pulsar;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.pulsar.common.configuration.PulsarConfigurationLoader.create;
import static org.apache.pulsar.common.configuration.PulsarConfigurationLoader.isComplete;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.ea.agentloader.AgentLoader;
import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.FileInputStream;
import java.net.MalformedURLException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.bookkeeper.proto.BookieServer;
import org.apache.bookkeeper.replication.AutoRecoveryMain;
import org.apache.bookkeeper.stats.StatsProvider;
import org.apache.bookkeeper.util.ReflectionUtils;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.ServiceConfigurationUtils;
import org.apache.pulsar.functions.worker.WorkerConfig;
import org.apache.pulsar.functions.worker.WorkerService;
import org.aspectj.weaver.loadtime.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

public class PulsarBrokerStarter {

    private static ServiceConfiguration loadConfig(String configFile) throws Exception {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        ServiceConfiguration config = create((new FileInputStream(configFile)), ServiceConfiguration.class);
        // it validates provided configuration is completed
        isComplete(config);
        return config;
    }

    @VisibleForTesting
    private static class StarterArguments {
        @Parameter(names = {"-c", "--broker-conf"}, description = "Configuration file for Broker")
        private String brokerConfigFile = Paths.get("").toAbsolutePath().normalize().toString() + "/conf/broker.conf";

        @Parameter(names = {"-rb", "--run-bookie"}, description = "Run Bookie together with Broker")
        private boolean runBookie = false;

        @Parameter(names = {"-ra", "--run-bookie-autorecovery"}, description = "Run Bookie Autorecovery together with broker")
        private boolean runBookieAutoRecovery = false;

        @Parameter(names = {"-bc", "--bookie-conf"}, description = "Configuration file for Bookie")
        private String bookieConfigFile = Paths.get("").toAbsolutePath().normalize().toString() + "/conf/bookkeeper.conf";

        @Parameter(names = {"-rfw", "--run-functions-worker"}, description = "Run functions worker with Broker")
        private boolean runFunctionsWorker = false;

        @Parameter(names = {"-fwc", "--functions-worker-conf"}, description = "Configuration file for Functions Worker")
        private String fnWorkerConfigFile = Paths.get("").toAbsolutePath().normalize().toString() + "/conf/functions_worker.yml";

        @Parameter(names = {"-h", "--help"}, description = "Show this help message")
        private boolean help = false;
    }

    private static ServerConfiguration readBookieConfFile(String bookieConfigFile) throws IllegalArgumentException {
        ServerConfiguration bookieConf = new ServerConfiguration();
        try {
            bookieConf.loadConf(new File(bookieConfigFile).toURI().toURL());
            bookieConf.validate();
            log.info("Using bookie configuration file {}", bookieConfigFile);
        } catch (MalformedURLException e) {
            log.error("Could not open configuration file: {}", bookieConfigFile, e);
            throw new IllegalArgumentException("Could not open configuration file");
        } catch (ConfigurationException e) {
            log.error("Malformed configuration file: {}", bookieConfigFile, e);
            throw new IllegalArgumentException("Malformed configuration file");
        }
        return bookieConf;
    }

    private static boolean argsContains(String[] args, String arg) {
        return Arrays.asList(args).contains(arg);
    }

    private static class BrokerStarter {
        private final ServiceConfiguration brokerConfig;
        private final PulsarService pulsarService;
        private final BookieServer bookieServer;
        private final AutoRecoveryMain autoRecoveryMain;
        private final StatsProvider bookieStatsProvider;
        private final ServerConfiguration bookieConfig;
        private final WorkerService functionsWorkerService;

        BrokerStarter(String[] args) throws Exception{
            StarterArguments starterArguments = new StarterArguments();
            JCommander jcommander = new JCommander(starterArguments);
            jcommander.setProgramName("PulsarBrokerStarter");

            // parse args by JCommander
            jcommander.parse(args);
            if (starterArguments.help) {
                jcommander.usage();
                System.exit(-1);
            }

            // init broker config
            if (isBlank(starterArguments.brokerConfigFile)) {
                jcommander.usage();
                throw new IllegalArgumentException("Need to specify a configuration file for broker");
            } else {
                brokerConfig = loadConfig(starterArguments.brokerConfigFile);
            }

            // init functions worker
            if (starterArguments.runFunctionsWorker || brokerConfig.isFunctionsWorkerEnabled()) {
                WorkerConfig workerConfig;
                if (isBlank(starterArguments.fnWorkerConfigFile)) {
                    workerConfig = new WorkerConfig();
                } else {
                    workerConfig = WorkerConfig.load(starterArguments.fnWorkerConfigFile);
                }
                // worker talks to local broker
                workerConfig.setPulsarServiceUrl("pulsar://127.0.0.1:" + brokerConfig.getBrokerServicePort());
                workerConfig.setPulsarWebServiceUrl("http://127.0.0.1:" + brokerConfig.getWebServicePort());
                String hostname = ServiceConfigurationUtils.getDefaultOrConfiguredAddress(
                    brokerConfig.getAdvertisedAddress());
                workerConfig.setWorkerHostname(hostname);
                workerConfig.setWorkerId(
                    "c-" + brokerConfig.getClusterName()
                        + "-fw-" + hostname
                        + "-" + workerConfig.getWorkerPort());
                functionsWorkerService = new WorkerService(workerConfig);
            } else {
                functionsWorkerService = null;
            }

            // init pulsar service
            pulsarService = new PulsarService(brokerConfig, Optional.ofNullable(functionsWorkerService));

            // if no argument to run bookie in cmd line, read from pulsar config
            if (!argsContains(args, "-rb") && !argsContains(args, "--run-bookie")) {
                checkState(starterArguments.runBookie == false,
                    "runBookie should be false if has no argument specified");
                starterArguments.runBookie = brokerConfig.isEnableRunBookieTogether();
            }
            if (!argsContains(args, "-ra") && !argsContains(args, "--run-bookie-autorecovery")) {
                checkState(starterArguments.runBookieAutoRecovery == false,
                    "runBookieAutoRecovery should be false if has no argument specified");
                starterArguments.runBookieAutoRecovery = brokerConfig.isEnableRunBookieAutoRecoveryTogether();
            }

            if ((starterArguments.runBookie || starterArguments.runBookieAutoRecovery)
                && isBlank(starterArguments.bookieConfigFile)) {
                jcommander.usage();
                throw new IllegalArgumentException("No configuration file for Bookie");
            }

            // init stats provider
            if (starterArguments.runBookie || starterArguments.runBookieAutoRecovery) {
                checkState(isNotBlank(starterArguments.bookieConfigFile),
                    "No configuration file for Bookie");
                bookieConfig = readBookieConfFile(starterArguments.bookieConfigFile);
                Class<? extends StatsProvider> statsProviderClass = bookieConfig.getStatsProviderClass();
                bookieStatsProvider = ReflectionUtils.newInstance(statsProviderClass);
            } else {
                bookieConfig = null;
                bookieStatsProvider = null;
            }

            // init bookie server
            if (starterArguments.runBookie) {
                checkNotNull(bookieConfig, "No ServerConfiguration for Bookie");
                checkNotNull(bookieStatsProvider, "No Stats Provider for Bookie");
                bookieServer = new BookieServer(bookieConfig, bookieStatsProvider.getStatsLogger(""));
            } else {
                bookieServer = null;
            }

            // init bookie AutorecoveryMain
            if (starterArguments.runBookieAutoRecovery) {
                checkNotNull(bookieConfig, "No ServerConfiguration for Bookie Autorecovery");
                autoRecoveryMain = new AutoRecoveryMain(bookieConfig);
            } else {
                autoRecoveryMain = null;
            }
        }

        public void start() throws Exception {
            if (bookieStatsProvider != null) {
                bookieStatsProvider.start(bookieConfig);
                log.info("started bookieStatsProvider.");
            }
            if (bookieServer != null) {
                bookieServer.start();
                log.info("started bookieServer.");
            }
            if (autoRecoveryMain != null) {
                autoRecoveryMain.start();
                log.info("started bookie autoRecoveryMain.");
            }

            pulsarService.start();
            log.info("PulsarService started.");

            // after broker is started, start the functions worker
            if (null != functionsWorkerService) {
                try {
                    functionsWorkerService.start();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }

        public void join() throws InterruptedException {
            pulsarService.waitUntilClosed();

            if (bookieServer != null) {
                bookieServer.join();
            }
            if (autoRecoveryMain != null) {
                autoRecoveryMain.join();
            }
        }

        public void shutdown() {
            if (null != functionsWorkerService) {
                functionsWorkerService.stop();
                log.info("Shut down functions worker service successfully.");
            }

            pulsarService.getShutdownService().run();
            log.info("Shut down broker service successfully.");

            if (bookieStatsProvider != null) {
                bookieStatsProvider.stop();
                log.info("Shut down bookieStatsProvider successfully.");
            }
            if (bookieServer != null) {
                bookieServer.shutdown();
                log.info("Shut down bookieServer successfully.");
            }
            if (autoRecoveryMain != null) {
                autoRecoveryMain.shutdown();
                log.info("Shut down autoRecoveryMain successfully.");
            }
        }
    }


    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
            log.error("Uncaught exception in thread {}: {}", thread.getName(), exception.getMessage(), exception);
        });

        // load aspectj-weaver agent for instrumentation
        AgentLoader.loadAgentClass(Agent.class.getName(), null);

        BrokerStarter starter = new BrokerStarter(args);
        Runtime.getRuntime().addShutdownHook(
            new Thread(() -> {
                starter.shutdown();
            })
        );

        try {
            starter.start();
        } catch (Exception e) {
            log.error("Failed to start pulsar service.", e);
            Runtime.getRuntime().halt(1);
        }

        starter.join();
    }

    private static final Logger log = LoggerFactory.getLogger(PulsarBrokerStarter.class);
}
