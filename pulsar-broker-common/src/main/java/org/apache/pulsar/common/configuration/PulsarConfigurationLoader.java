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
package org.apache.pulsar.common.configuration;

import org.apache.pulsar.broker.ServiceConfiguration;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.pulsar.common.util.FieldParser.update;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

/**
 * Loads ServiceConfiguration with properties
 * 
 *
 */
public class PulsarConfigurationLoader {

    /**
     * Creates PulsarConfiguration and loads it with populated attribute values loaded from provided property file.
     * 
     * @param configFile
     * @throws IOException
     * @throws IllegalArgumentException
     */
    public static <T extends PulsarConfiguration> T create(String configFile,
            Class<? extends PulsarConfiguration> clazz) throws IOException, IllegalArgumentException {
        checkNotNull(configFile);
        return create(new FileInputStream(configFile), clazz);
    }

    /**
     * Creates PulsarConfiguration and loads it with populated attribute values loaded from provided inputstream
     * property file.
     * 
     * @param inStream
     * @throws IOException
     *             if an error occurred when reading from the input stream.
     * @throws IllegalArgumentException
     *             if the input stream contains incorrect value type
     */
    public static <T extends PulsarConfiguration> T create(InputStream inStream,
            Class<? extends PulsarConfiguration> clazz) throws IOException, IllegalArgumentException {
        try {
            checkNotNull(inStream);
            Properties properties = new Properties();
            properties.load(inStream);
            return (create(properties, clazz));
        } finally {
            if (inStream != null) {
                inStream.close();
            }
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected static <T extends PulsarConfiguration> T create(Properties properties,
            Class<? extends PulsarConfiguration> clazz) throws IOException, IllegalArgumentException {
        checkNotNull(properties);
        T configuration = null;
        try {
            configuration = (T) clazz.newInstance();
            configuration.setProperties(properties);
            update((Map) properties, configuration);
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalArgumentException("Failed to instantiate " + clazz.getName(), e);
        }
        return configuration;
    }

    /**
     * Validates {@link FieldContext} annotation on each field of the class element. If element is annotated required
     * and value of the element is null or number value is not in a provided (min,max) range then consider as incomplete
     * object and throws exception with incomplete parameters
     * 
     * @param object
     * @return
     * @throws IllegalArgumentException
     *             if object is field values are not completed according to {@link FieldContext} constraints.
     * @throws IllegalAccessException
     */
    public static boolean isComplete(Object obj) throws IllegalArgumentException, IllegalAccessException {
        checkNotNull(obj);
        Field[] fields = obj.getClass().getDeclaredFields();
        StringBuilder error = new StringBuilder();
        for (Field field : fields) {
            if (field.isAnnotationPresent(FieldContext.class)) {
                field.setAccessible(true);
                Object value = field.get(obj);
                boolean isRequired = ((FieldContext) field.getAnnotation(FieldContext.class)).required();
                long minValue = ((FieldContext) field.getAnnotation(FieldContext.class)).minValue();
                long maxValue = ((FieldContext) field.getAnnotation(FieldContext.class)).maxValue();
                if (isRequired && value == null)
                    error.append(String.format("Required %s is null,", field.getName()));
                if (value != null && Number.class.isAssignableFrom(value.getClass())) {
                    long fieldVal = ((Number) value).longValue();
                    boolean valid = fieldVal >= minValue && fieldVal <= maxValue;
                    if (!valid) {
                        error.append(String.format("%s value %d doesn't fit in given range (%d, %d),", field.getName(),
                                fieldVal, minValue, maxValue));
                    }
                }
            }
        }
        if (error.length() > 0) {
            throw new IllegalArgumentException(error.substring(0, error.length() - 1));
        }
        return true;
    }

    /**
     * Converts a PulsarConfiguration object to a ServiceConfiguration object.
     *
     * @param conf
     * @param ignoreNonExistMember
     * @return
     * @throws IllegalArgumentException
     *             if conf has the field whose name is not contained in ServiceConfiguration and ignoreNonExistMember is false.
     * @throws RuntimeException
     */
    public static ServiceConfiguration convertFrom(PulsarConfiguration conf, boolean ignoreNonExistMember) throws RuntimeException {
        try {
            final ServiceConfiguration convertedConf = ServiceConfiguration.class.newInstance();
            Field[] confFields = conf.getClass().getDeclaredFields();
            Arrays.stream(confFields).forEach(confField -> {
                try {
                    Field convertedConfField = ServiceConfiguration.class.getDeclaredField(confField.getName());
                    confField.setAccessible(true);
                    convertedConfField.setAccessible(true);
                    convertedConfField.set(convertedConf, confField.get(conf));
                } catch (NoSuchFieldException e) {
                    if (!ignoreNonExistMember) {
                        throw new IllegalArgumentException("Exception caused while converting configuration: " + e.getMessage());
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Exception caused while converting configuration: " + e.getMessage());
                }
            });
            return convertedConf;
        } catch (InstantiationException e) {
            throw new RuntimeException("Exception caused while converting configuration: " + e.getMessage());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Exception caused while converting configuration: " + e.getMessage());
        }
    }

    public static ServiceConfiguration convertFrom(PulsarConfiguration conf) throws RuntimeException {
        return convertFrom(conf, true);
    }

}
