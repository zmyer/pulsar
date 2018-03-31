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

package org.apache.pulsar.functions.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.pulsar.functions.proto.Function.FunctionConfig;

import java.io.File;
import java.io.IOException;

public class FunctionConfigUtils {

    public static String convertYamlToJson(File file) throws IOException {

        ObjectMapper yamlReader = new ObjectMapper(new YAMLFactory());
        Object obj = yamlReader.readValue(file, Object.class);

        ObjectMapper jsonWriter = new ObjectMapper();
        return jsonWriter.writeValueAsString(obj);
    }

    public static FunctionConfig.Builder loadConfig(File file) throws IOException {
        String json = convertYamlToJson(file);
        FunctionConfig.Builder functionConfigBuilder = FunctionConfig.newBuilder();
        Utils.mergeJson(json, functionConfigBuilder);
        return functionConfigBuilder;
    }

    public static String getFullyQualifiedName(FunctionConfig functionConfig) {
        return getFullyQualifiedName(functionConfig.getTenant(), functionConfig.getNamespace(), functionConfig.getName());
    }

    public static String getFullyQualifiedName(String tenant, String namespace, String functionName) {
        return String.format("%s/%s/%s", tenant, namespace, functionName);
    }

    public static String extractTenantFromFQN(String fullyQualifiedName) {
        return fullyQualifiedName.split("/")[0];
    }

    public static String extractNamespaceFromFQN(String fullyQualifiedName) {
        return fullyQualifiedName.split("/")[1];
    }

    public static String extractFunctionNameFromFQN(String fullyQualifiedName) {
        return fullyQualifiedName.split("/")[2];
    }

    public static boolean areAllRequiredFieldsPresent(FunctionConfig functionConfig) {
        if (functionConfig.getTenant() == null || functionConfig.getNamespace() == null
                || functionConfig.getName() == null || functionConfig.getClassName() == null
                || (functionConfig.getInputsCount() <= 0 && functionConfig.getCustomSerdeInputsCount() <= 0)
                || functionConfig.getParallelism() <= 0) {
            return false;
        } else {
            return true;
        }
    }

    public static String getDownloadFileName(FunctionConfig functionConfig) {
        String[] hierarchy = functionConfig.getClassName().split("\\.");
        String fileName;
        if (hierarchy.length <= 0) {
            fileName = functionConfig.getClassName();
        } else if (hierarchy.length == 1) {
            fileName =  hierarchy[0];
        } else {
            fileName = hierarchy[hierarchy.length - 2];
        }
        switch (functionConfig.getRuntime()) {
            case JAVA:
                return fileName + ".jar";
            case PYTHON:
                return fileName + ".py";
            default:
                throw new RuntimeException("Unknown runtime " + functionConfig.getRuntime());
        }
    }
}
