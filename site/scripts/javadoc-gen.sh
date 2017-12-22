#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#


ROOT_DIR=$(git rev-parse --show-toplevel)
JDK_COMMON_PKGS=java.lang:java.util:java.util.concurrent:java.nio:java.net:java.io

(
  cd $ROOT_DIR

  # Java client
  javadoc \
    -quiet \
    -windowtitle "Pulsar Client Java API" \
    -doctitle "Pulsar Client Java API" \
    -overview site/scripts/javadoc-client.html \
    -d site/api/client \
    -subpackages org.apache.pulsar.client.api \
    -noqualifier $JDK_COMMON_PKGS \
    -notimestamp \
    -Xdoclint:none \
    `find pulsar-client/src/main/java/org/apache/pulsar/client/api -name *.java`

  # Java admin
  javadoc \
    -quiet \
    -windowtitle "Pulsar Admin Java API" \
    -doctitle "Pulsar Admin Java API" \
    -overview site/scripts/javadoc-admin.html \
    -d site/api/admin \
    -noqualifier $JDK_COMMON_PKGS \
    -notimestamp \
    -Xdoclint:none \
    `find pulsar-client-admin -name *.java | grep -v /internal/` \
    `find pulsar-common/src/main/java/org/apache/pulsar/common/policies -name *.java`

  # Broker
  #javadoc \
  #  -quiet \
  #  -windowtitle "Pulsar Broker Java API" \
  #  -doctitle "Pulsar Broker Java API" \
  #  -overview site/scripts/javadoc-broker.html \
  #  -d site/api/broker \
  #  -noqualifier $JDK_COMMON_PKGS \
  #  -notimestamp \
  #  -Xdoclint:none \
  #  `find pulsar-broker -name *.java`
) || true

# The "|| true" is present here to keep this script from failing due to
# Javadoc errors
