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
package org.apache.pulsar.broker.service.schema;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.pulsar.broker.auth.MockedPulsarServiceBaseTest;
import org.apache.pulsar.common.schema.SchemaData;
import org.apache.pulsar.common.schema.SchemaType;
import org.apache.pulsar.common.schema.SchemaVersion;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class SchemaServiceTest extends MockedPulsarServiceBaseTest {

    private static Clock MockClock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault());

    private String schemaId1 = "1/2/3/4";
    private String userId = "user";

    private SchemaData schema1 = SchemaData.builder()
        .user(userId)
        .type(SchemaType.PROTOBUF)
        .timestamp(MockClock.millis())
        .isDeleted(false)
        .data("message { required int64 a = 1};".getBytes())
        .build();

    private SchemaData schema2 = SchemaData.builder()
        .user(userId)
        .type(SchemaType.PROTOBUF)
        .timestamp(MockClock.millis())
        .isDeleted(false)
        .data("message { required int64 b = 1};".getBytes())
        .build();

    private SchemaData schema3 = SchemaData.builder()
        .user(userId)
        .type(SchemaType.PROTOBUF)
        .timestamp(MockClock.millis())
        .isDeleted(false)
        .data("message { required int64 c = 1};".getBytes())
        .build();

    private SchemaRegistryServiceImpl schemaRegistryService;

    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        super.internalSetup();
        BookkeeperSchemaStorage storage = new BookkeeperSchemaStorage(pulsar);
        storage.init();
        storage.start();
        schemaRegistryService = new SchemaRegistryServiceImpl(storage, MockClock);
    }

    @AfterMethod
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
        schemaRegistryService.close();
    }

    @Test
    public void writeReadBackDeleteSchemaEntry() throws Exception {
        putSchema(schemaId1, schema1, version(0));

        SchemaData latest = getLatestSchema(schemaId1, version(0));
        assertEquals(schema1, latest);

        deleteSchema(schemaId1, version(1));

        SchemaData latest2 = getLatestSchema(schemaId1, version(1));

        assertTrue(latest2.isDeleted());
    }

    @Test
    public void getReturnsTheLastWrittenEntry() throws Exception {
        putSchema(schemaId1, schema1, version(0));
        putSchema(schemaId1, schema2, version(1));

        SchemaData latest = getLatestSchema(schemaId1, version(1));
        assertEquals(schema2, latest);

    }

    @Test
    public void getByVersionReturnsTheCorrectEntry() throws Exception {
        putSchema(schemaId1, schema1, version(0));
        putSchema(schemaId1, schema2, version(1));

        SchemaData version0 = getSchema(schemaId1, version(0));
        assertEquals(schema1, version0);
    }

    @Test
    public void getByVersionReturnsTheCorrectEntry2() throws Exception {
        putSchema(schemaId1, schema1, version(0));
        putSchema(schemaId1, schema2, version(1));

        SchemaData version1 = getSchema(schemaId1, version(1));
        assertEquals(schema2, version1);
    }

    @Test
    public void getByVersionReturnsTheCorrectEntry3() throws Exception {
        putSchema(schemaId1, schema1, version(0));

        SchemaData version1 = getSchema(schemaId1, version(0));
        assertEquals(schema1, version1);
    }

    @Test
    public void addLotsOfEntriesThenDelete() throws Exception {
        SchemaData randomSchema1 = randomSchema();
        SchemaData randomSchema2 = randomSchema();
        SchemaData randomSchema3 = randomSchema();
        SchemaData randomSchema4 = randomSchema();
        SchemaData randomSchema5 = randomSchema();
        SchemaData randomSchema6 = randomSchema();
        SchemaData randomSchema7 = randomSchema();

        putSchema(schemaId1, randomSchema1, version(0));
        putSchema(schemaId1, randomSchema2, version(1));
        putSchema(schemaId1, randomSchema3, version(2));
        putSchema(schemaId1, randomSchema4, version(3));
        putSchema(schemaId1, randomSchema5, version(4));
        putSchema(schemaId1, randomSchema6, version(5));
        putSchema(schemaId1, randomSchema7, version(6));

        SchemaData version0 = getSchema(schemaId1, version(0));
        assertEquals(randomSchema1, version0);

        SchemaData version1 = getSchema(schemaId1, version(1));
        assertEquals(randomSchema2, version1);

        SchemaData version2 = getSchema(schemaId1, version(2));
        assertEquals(randomSchema3, version2);

        SchemaData version3 = getSchema(schemaId1, version(3));
        assertEquals(randomSchema4, version3);

        SchemaData version4 = getSchema(schemaId1, version(4));
        assertEquals(randomSchema5, version4);

        SchemaData version5 = getSchema(schemaId1, version(5));
        assertEquals(randomSchema6, version5);

        SchemaData version6 = getSchema(schemaId1, version(6));
        assertEquals(randomSchema7, version6);

        deleteSchema(schemaId1, version(7));

        SchemaData version7 = getSchema(schemaId1, version(7));
        assertTrue(version7.isDeleted());

    }

    @Test
    public void writeSchemasToDifferentIds() throws Exception {
        SchemaData schemaWithDifferentId = schema3;

        putSchema(schemaId1, schema1, version(0));
        String schemaId2 = "id2";
        putSchema(schemaId2, schemaWithDifferentId, version(0));

        SchemaData withFirstId = getLatestSchema(schemaId1, version(0));
        SchemaData withDifferentId = getLatestSchema(schemaId2, version(0));

        assertEquals(schema1, withFirstId);
        assertEquals(schema3, withDifferentId);
    }

    @Test
    public void dontReAddExistingSchemaAtRoot() throws Exception {
        putSchema(schemaId1, schema1, version(0));
        putSchema(schemaId1, schema1, version(0));
        putSchema(schemaId1, schema1, version(0));
    }

    @Test
    public void dontReAddExistingSchemaInMiddle() throws Exception {
        putSchema(schemaId1, randomSchema(), version(0));
        putSchema(schemaId1, schema2, version(1));
        putSchema(schemaId1, randomSchema(), version(2));
        putSchema(schemaId1, randomSchema(), version(3));
        putSchema(schemaId1, randomSchema(), version(4));
        putSchema(schemaId1, randomSchema(), version(5));
        putSchema(schemaId1, schema2, version(1));
    }

    private void putSchema(String schemaId, SchemaData schema, SchemaVersion expectedVersion) throws Exception {
        CompletableFuture<SchemaVersion> put = schemaRegistryService.putSchemaIfAbsent(schemaId, schema);
        SchemaVersion newVersion = put.get();
        assertEquals(expectedVersion, newVersion);
    }

    private SchemaData getLatestSchema(String schemaId, SchemaVersion expectedVersion) throws Exception {
        SchemaRegistry.SchemaAndMetadata schemaAndVersion = schemaRegistryService.getSchema(schemaId).get();
        assertEquals(expectedVersion, schemaAndVersion.version);
        assertEquals(schemaId, schemaAndVersion.id);
        return schemaAndVersion.schema;
    }

    private SchemaData getSchema(String schemaId, SchemaVersion version) throws Exception {
        SchemaRegistry.SchemaAndMetadata schemaAndVersion = schemaRegistryService.getSchema(schemaId, version).get();
        assertEquals(version, schemaAndVersion.version);
        assertEquals(schemaId, schemaAndVersion.id);
        return schemaAndVersion.schema;
    }

    private void deleteSchema(String schemaId, SchemaVersion expectedVersion) throws Exception {
        SchemaVersion version = schemaRegistryService.deleteSchema(schemaId, userId).get();
        assertEquals(expectedVersion, version);
    }

    private SchemaData randomSchema() {
        UUID randomString = UUID.randomUUID();
        return SchemaData.builder()
            .user(userId)
            .type(SchemaType.PROTOBUF)
            .timestamp(MockClock.millis())
            .isDeleted(false)
            .data(randomString.toString().getBytes())
            .build();
    }

    private SchemaVersion version(long version) {
        return new LongSchemaVersion(version);
    }
}
