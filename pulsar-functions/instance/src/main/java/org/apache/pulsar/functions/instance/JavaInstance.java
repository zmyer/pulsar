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
package org.apache.pulsar.functions.instance;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.functions.api.Function;
import org.apache.pulsar.functions.proto.InstanceCommunication;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the Java Instance. This is started by the runtimeSpawner using the JavaInstanceClient
 * program if invoking via a process based invocation or using JavaInstance using a thread
 * based invocation.
 */
@Slf4j
public class JavaInstance implements AutoCloseable {

    @Getter(AccessLevel.PACKAGE)
    private final ContextImpl context;
    private Function function;
    private java.util.function.Function javaUtilFunction;

    public JavaInstance(InstanceConfig config, Object userClassObject,
                 ClassLoader clsLoader,
                 PulsarClient pulsarClient,
                 Map<String, Consumer> inputConsumers) {
        // TODO: cache logger instances by functions?
        Logger instanceLog = LoggerFactory.getLogger("function-" + config.getFunctionConfig().getName());

        this.context = new ContextImpl(config, instanceLog, pulsarClient, clsLoader, inputConsumers);

        // create the functions
        if (userClassObject instanceof Function) {
            this.function = (Function) userClassObject;
        } else {
            this.javaUtilFunction = (java.util.function.Function) userClassObject;
        }
    }

    public JavaExecutionResult handleMessage(MessageId messageId, String topicName, Object input) {
        context.setCurrentMessageContext(messageId, topicName);
        JavaExecutionResult executionResult = new JavaExecutionResult();
        try {
            Object output;
            if (function != null) {
                output = function.process(input, context);
            } else {
                output = javaUtilFunction.apply(input);
            }
            executionResult.setResult(output);
        } catch (Exception ex) {
            executionResult.setUserException(ex);
        }
        return executionResult;
    }

    @Override
    public void close() {
    }

    public InstanceCommunication.MetricsData getAndResetMetrics() {
        return context.getAndResetMetrics();
    }
}
