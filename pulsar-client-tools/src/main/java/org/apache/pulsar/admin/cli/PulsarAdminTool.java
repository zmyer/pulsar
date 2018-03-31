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
package org.apache.pulsar.admin.cli;

import java.io.FileInputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiFunction;

import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminWithFunctions;
import org.apache.pulsar.client.api.AuthenticationFactory;
import org.apache.pulsar.client.impl.conf.ClientConfigurationData;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

public class PulsarAdminTool {
    protected final Map<String, Class> commandMap;
    private final JCommander jcommander;
    protected final ClientConfigurationData config;

    @Parameter(names = { "--admin-url" }, description = "Admin Service URL to which to connect.")
    String serviceUrl = null;

    @Parameter(names = { "--auth-plugin" }, description = "Authentication plugin class name.")
    String authPluginClassName = null;

    @Parameter(names = { "--auth-params" }, description = "Authentication parameters, e.g., \"key1:val1,key2:val2\".")
    String authParams = null;

    @Parameter(names = { "-h", "--help", }, help = true, description = "Show this help.")
    boolean help;

    PulsarAdminTool(Properties properties) throws Exception {
        // fallback to previous-version serviceUrl property to maintain backward-compatibility
        serviceUrl = StringUtils.isNotBlank(properties.getProperty("webServiceUrl"))
                ? properties.getProperty("webServiceUrl") : properties.getProperty("serviceUrl");
        authPluginClassName = properties.getProperty("authPlugin");
        authParams = properties.getProperty("authParams");
        boolean useTls = Boolean.parseBoolean(properties.getProperty("useTls"));
        boolean tlsAllowInsecureConnection = Boolean.parseBoolean(properties.getProperty("tlsAllowInsecureConnection"));
        String tlsTrustCertsFilePath = properties.getProperty("tlsTrustCertsFilePath");

        config = new ClientConfigurationData();
        config.setUseTls(useTls);
        config.setTlsAllowInsecureConnection(tlsAllowInsecureConnection);
        config.setTlsTrustCertsFilePath(tlsTrustCertsFilePath);

        jcommander = new JCommander();
        jcommander.setProgramName("pulsar-admin");
        jcommander.addObject(this);

        commandMap = new HashMap<>();
        commandMap.put("clusters", CmdClusters.class);
        commandMap.put("ns-isolation-policy", CmdNamespaceIsolationPolicy.class);
        commandMap.put("brokers", CmdBrokers.class);
        commandMap.put("broker-stats", CmdBrokerStats.class);
        commandMap.put("properties", CmdProperties.class);
        commandMap.put("namespaces", CmdNamespaces.class);
        commandMap.put("persistent", CmdPersistentTopics.class);
        commandMap.put("non-persistent", CmdNonPersistentTopics.class);
        commandMap.put("resource-quotas", CmdResourceQuotas.class);
        commandMap.put("functions", CmdFunctions.class);
    }

    private void setupCommands(BiFunction<URL, ClientConfigurationData, ? extends PulsarAdmin> adminFactory) {
        try {
            URL url = new URL(serviceUrl);
            config.setAuthentication(AuthenticationFactory.create(authPluginClassName, authParams));
            PulsarAdmin admin = adminFactory.apply(url, config);
            for (Map.Entry<String, Class> c : commandMap.entrySet()) {
                jcommander.addCommand(c.getKey(), c.getValue().getConstructor(PulsarAdmin.class).newInstance(admin));
            }
        } catch (MalformedURLException e) {
            System.err.println("Invalid serviceUrl: '" + serviceUrl + "'");
            System.exit(1);
        } catch (Exception e) {
            System.err.println(e.getClass() + ": " + e.getMessage());
            System.exit(1);
        }
    }

    boolean run(String[] args) {
        return run(args, (url, config) -> {
            try {
                return new PulsarAdminWithFunctions(url, config);
            } catch (Exception ex) {
                System.err.println(ex.getClass() + ": " + ex.getMessage());
                System.exit(1);
                return null;
            }
        });
    }

    boolean run(String[] args, BiFunction<URL, ClientConfigurationData, ? extends PulsarAdmin> adminFactory) {
        if (args.length == 0) {
            setupCommands(adminFactory);
            jcommander.usage();
            return false;
        }

        int cmdPos;
        for (cmdPos=0; cmdPos < args.length; cmdPos++) {
            if (commandMap.containsKey(args[cmdPos])){
                break;
            }
        }

        try {
            jcommander.parse(Arrays.copyOfRange(args, 0, Math.min(cmdPos, args.length)));
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.err.println();
            setupCommands(adminFactory);
            jcommander.usage();
            return false;
        }

        if (help) {
            setupCommands(adminFactory);
            jcommander.usage();
            return false;
        }

        if (cmdPos == args.length) {
            setupCommands(adminFactory);
            jcommander.usage();
            return false;
        } else {
            setupCommands(adminFactory);
            String cmd = args[cmdPos];
            JCommander obj = jcommander.getCommands().get(cmd);
            CmdBase cmdObj = (CmdBase) obj.getObjects().get(0);

            return cmdObj.run(Arrays.copyOfRange(args, cmdPos+1, args.length));
        }
    }

    public static void main(String[] args) throws Exception {
        String configFile = args[0];
        Properties properties = new Properties();

        if (configFile != null) {
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(configFile);
                properties.load(fis);
            } finally {
                if (fis != null)
                    fis.close();
            }
        }

        PulsarAdminTool tool = new PulsarAdminTool(properties);

        int cmdPos;
        for (cmdPos = 1; cmdPos < args.length; cmdPos++) {
            if (tool.commandMap.containsKey(args[cmdPos])) {
                break;
            }
        }

        ++cmdPos;
        boolean isLocalRun = false;
        if (cmdPos < args.length) {
            isLocalRun = "localrun" == args[cmdPos].toLowerCase();
        }

        BiFunction<URL, ClientConfigurationData, ? extends PulsarAdmin> adminFactory;
        if (isLocalRun) {
            // bypass constructing admin client
            adminFactory = (url, config) -> null;
        } else {
            adminFactory = (url, config) -> {
                try {
                    return new PulsarAdminWithFunctions(url, config);
                } catch (Exception ex) {
                    System.err.println(ex.getClass() + ": " + ex.getMessage());
                    System.exit(1);
                    return null;
                }
            };
        }

        if (tool.run(Arrays.copyOfRange(args, 1, args.length), adminFactory)) {
            System.exit(0);
        } else {
            System.exit(1);
        }
    }
}
