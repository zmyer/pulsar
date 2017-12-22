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

import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.admin.PulsarAdminException.ConnectException;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

public abstract class CmdBase {
    protected final JCommander jcommander;
    protected final PulsarAdmin admin;

    @Parameter(names = { "-h", "--help" }, help = true, hidden = true)
    private boolean help;

    public CmdBase(String cmdName, PulsarAdmin admin) {
        this.admin = admin;
        jcommander = new JCommander();
        jcommander.setProgramName("pulsar-admin " + cmdName);
    }

    public boolean run(String[] args) {
        try {
            jcommander.parse(args);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.err.println();
            jcommander.usage();
            return false;
        }

        String cmd = jcommander.getParsedCommand();
        if (cmd == null) {
            jcommander.usage();
            return false;
        } else {
            JCommander obj = jcommander.getCommands().get(cmd);
            CliCommand cmdObj = (CliCommand) obj.getObjects().get(0);

            try {
                cmdObj.run();
                return true;
            } catch (ParameterException e) {
                System.err.println(e.getMessage());
                System.err.println();
                jcommander.usage();
                return false;
            } catch (ConnectException e) {
                System.err.println(e.getMessage());
                System.err.println();
                System.err.println("Error connecting to: " + admin.getServiceUrl());
                return false;
            } catch (PulsarAdminException e) {
                System.err.println(e.getHttpError());
                System.err.println();
                System.err.println("Reason: " + e.getMessage());
                return false;
            } catch (Exception e) {
                System.err.println("Got exception: " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        }
    }
}
