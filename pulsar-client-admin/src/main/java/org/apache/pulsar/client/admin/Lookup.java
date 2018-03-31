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
package org.apache.pulsar.client.admin;

/**
 * This is an interface class to allow using command line tool to quickly lookup the broker serving the topic.
 */
public interface Lookup {

    /**
     * Lookup a topic
     *
     * @param topic
     * @return the broker URL that serves the topic
     */
    public String lookupTopic(String topic) throws PulsarAdminException;

    /**
     * Get a bundle range of a topic
     *
     * @param topic
     * @return
     * @throws PulsarAdminException
     */
    public String getBundleRange(String topic) throws PulsarAdminException;
}
