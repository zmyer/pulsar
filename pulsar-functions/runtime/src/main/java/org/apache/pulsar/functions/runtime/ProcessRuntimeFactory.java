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

package org.apache.pulsar.functions.runtime;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.functions.instance.InstanceConfig;

import java.nio.file.Paths;

/**
 * Thread based function container factory implementation.
 */
@Slf4j
public class ProcessRuntimeFactory implements RuntimeFactory {

    private String pulsarServiceUrl;
    private String javaInstanceJarFile;
    private String pythonInstanceFile;
    private String logDirectory;

    @VisibleForTesting
    public ProcessRuntimeFactory(String pulsarServiceUrl,
                                 String javaInstanceJarFile,
                                 String pythonInstanceFile,
                                 String logDirectory) {

        this.pulsarServiceUrl = pulsarServiceUrl;
        this.javaInstanceJarFile = javaInstanceJarFile;
        this.pythonInstanceFile = pythonInstanceFile;
        this.logDirectory = logDirectory;

        // if things are not specified, try to figure out by env properties
        if (this.javaInstanceJarFile == null) {
            String envJavaInstanceJarLocation = System.getProperty("pulsar.functions.java.instance.jar");
            if (null != envJavaInstanceJarLocation) {
                log.info("Java instance jar location is not defined,"
                        + " using the location defined in system environment : {}", envJavaInstanceJarLocation);
                this.javaInstanceJarFile = envJavaInstanceJarLocation;
            } else {
                throw new RuntimeException("No JavaInstanceJar specified");
            }
        }

        if (this.pythonInstanceFile == null) {
            String envPythonInstanceLocation = System.getProperty("pulsar.functions.python.instance.file");
            if (null != envPythonInstanceLocation) {
                log.info("Python instance file location is not defined"
                        + " using the location defined in system environment : {}", envPythonInstanceLocation);
                this.pythonInstanceFile = envPythonInstanceLocation;
            } else {
                throw new RuntimeException("No PythonInstanceFile specified");
            }
        }

        if (this.logDirectory == null) {
            String envProcessContainerLogDirectory = System.getProperty("pulsar.functions.process.container.log.dir");
            if (null != envProcessContainerLogDirectory) {
                this.logDirectory = envProcessContainerLogDirectory;
            } else {
                // use a default location
                this.logDirectory = Paths.get("logs").toFile().getAbsolutePath();
            }
        }
    }

    @Override
    public ProcessRuntime createContainer(InstanceConfig instanceConfig, String codeFile) {
        String instanceFile;
        switch (instanceConfig.getFunctionConfig().getRuntime()) {
            case JAVA:
                instanceFile = javaInstanceJarFile;
                break;
            case PYTHON:
                instanceFile = pythonInstanceFile;
                break;
            default:
                throw new RuntimeException("Unsupported Runtime " + instanceConfig.getFunctionConfig().getRuntime());
        }
        return new ProcessRuntime(
            instanceConfig,
            instanceFile,
            logDirectory,
            codeFile,
            pulsarServiceUrl);
    }

    @Override
    public void close() {
    }
}
