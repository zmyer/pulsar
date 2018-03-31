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
package org.apache.bookkeeper.mledger.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedLedgerFactoryConfig;
import org.apache.bookkeeper.test.MockedBookKeeperTestCase;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class EntryCacheManagerTest extends MockedBookKeeperTestCase {

    ManagedLedgerImpl ml1;
    ManagedLedgerImpl ml2;

    @BeforeClass
    void setup() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        ml1 = mock(ManagedLedgerImpl.class);
        when(ml1.getScheduledExecutor()).thenReturn(executor);
        when(ml1.getName()).thenReturn("cache1");

        ml2 = mock(ManagedLedgerImpl.class);
        when(ml2.getScheduledExecutor()).thenReturn(executor);
        when(ml2.getName()).thenReturn("cache2");
    }

    @Test
    void simple() throws Exception {
        ManagedLedgerFactoryConfig config = new ManagedLedgerFactoryConfig();
        config.setMaxCacheSize(10);
        config.setCacheEvictionWatermark(0.8);

        factory = new ManagedLedgerFactoryImpl(bkc, bkc.getZkHandle(), config);

        EntryCacheManager cacheManager = factory.getEntryCacheManager();
        EntryCache cache1 = cacheManager.getEntryCache(ml1);
        EntryCache cache2 = cacheManager.getEntryCache(ml2);

        cache1.insert(EntryImpl.create(1, 1, new byte[4]));
        cache1.insert(EntryImpl.create(1, 0, new byte[3]));

        assertEquals(cache1.getSize(), 7);
        assertEquals(cacheManager.getSize(), 7);

        cacheManager.mlFactoryMBean.refreshStats(1, TimeUnit.SECONDS);
        assertEquals(cacheManager.mlFactoryMBean.getCacheMaxSize(), 10);
        assertEquals(cacheManager.mlFactoryMBean.getCacheUsedSize(), 7);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheMissesRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsThroughput(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getNumberOfCacheEvictions(), 0);

        cache2.insert(EntryImpl.create(2, 0, new byte[1]));
        cache2.insert(EntryImpl.create(2, 1, new byte[1]));
        cache2.insert(EntryImpl.create(2, 2, new byte[1]));

        assertEquals(cache2.getSize(), 3);
        assertEquals(cacheManager.getSize(), 10);

        // Next insert should trigger a cache eviction to force the size to 8
        // The algorithm should evict entries from cache1
        cache2.insert(EntryImpl.create(2, 3, new byte[1]));

        // Wait for eviction to be completed in background
        Thread.sleep(100);
        assertEquals(cacheManager.getSize(), 7);
        assertEquals(cache1.getSize(), 4);
        assertEquals(cache2.getSize(), 3);

        cacheManager.removeEntryCache("cache1");
        assertEquals(cacheManager.getSize(), 3);
        assertEquals(cache2.getSize(), 3);

        // Should remove 2 entries
        cache2.invalidateEntries(new PositionImpl(2, 1));
        assertEquals(cacheManager.getSize(), 1);
        assertEquals(cache2.getSize(), 1);

        cacheManager.mlFactoryMBean.refreshStats(1, TimeUnit.SECONDS);

        assertEquals(cacheManager.mlFactoryMBean.getCacheMaxSize(), 10);
        assertEquals(cacheManager.mlFactoryMBean.getCacheUsedSize(), 1);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheMissesRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsThroughput(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getNumberOfCacheEvictions(), 1);
    }

    @Test
    void doubleInsert() throws Exception {
        ManagedLedgerFactoryConfig config = new ManagedLedgerFactoryConfig();
        config.setMaxCacheSize(10);
        config.setCacheEvictionWatermark(0.8);

        factory = new ManagedLedgerFactoryImpl(bkc, bkc.getZkHandle(), config);

        EntryCacheManager cacheManager = factory.getEntryCacheManager();
        EntryCache cache1 = cacheManager.getEntryCache(ml1);

        assertEquals(cache1.insert(EntryImpl.create(1, 1, new byte[4])), true);
        assertEquals(cache1.insert(EntryImpl.create(1, 0, new byte[3])), true);

        assertEquals(cache1.getSize(), 7);
        assertEquals(cacheManager.getSize(), 7);

        assertEquals(cache1.insert(EntryImpl.create(1, 0, new byte[5])), false);

        assertEquals(cache1.getSize(), 7);
        assertEquals(cacheManager.getSize(), 7);
    }

    @Test
    void cacheDisabled() throws Exception {
        ManagedLedgerFactoryConfig config = new ManagedLedgerFactoryConfig();
        config.setMaxCacheSize(0);
        config.setCacheEvictionWatermark(0.8);

        factory = new ManagedLedgerFactoryImpl(bkc, bkc.getZkHandle(), config);

        EntryCacheManager cacheManager = factory.getEntryCacheManager();
        EntryCache cache1 = cacheManager.getEntryCache(ml1);
        EntryCache cache2 = cacheManager.getEntryCache(ml2);

        assertTrue(cache1 instanceof EntryCacheManager.EntryCacheDisabled);
        assertTrue(cache2 instanceof EntryCacheManager.EntryCacheDisabled);

        cache1.insert(EntryImpl.create(1, 1, new byte[4]));
        cache1.insert(EntryImpl.create(1, 0, new byte[3]));

        assertEquals(cache1.getSize(), 0);
        assertEquals(cacheManager.getSize(), 0);

        cacheManager.mlFactoryMBean.refreshStats(1, TimeUnit.SECONDS);
        assertEquals(cacheManager.mlFactoryMBean.getCacheMaxSize(), 0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheUsedSize(), 0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheMissesRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsThroughput(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getNumberOfCacheEvictions(), 0);

        cache2.insert(EntryImpl.create(2, 0, new byte[1]));
        cache2.insert(EntryImpl.create(2, 1, new byte[1]));
        cache2.insert(EntryImpl.create(2, 2, new byte[1]));

        assertEquals(cache2.getSize(), 0);
        assertEquals(cacheManager.getSize(), 0);
    }

    @Test
    void verifyNoCacheIfNoConsumer() throws Exception {
        ManagedLedgerFactoryConfig config = new ManagedLedgerFactoryConfig();
        config.setMaxCacheSize(7 * 10);
        config.setCacheEvictionWatermark(0.8);

        factory = new ManagedLedgerFactoryImpl(bkc, bkc.getZkHandle(), config);

        EntryCacheManager cacheManager = factory.getEntryCacheManager();
        ManagedLedgerImpl ledger = (ManagedLedgerImpl) factory.open("ledger");
        EntryCache cache1 = ledger.entryCache;

        for (int i = 0; i < 10; i++) {
            ledger.addEntry(("entry-" + i).getBytes());
        }

        assertEquals(cache1.getSize(), 0);
        assertEquals(cacheManager.getSize(), 0);

        cacheManager.mlFactoryMBean.refreshStats(1, TimeUnit.SECONDS);
        assertEquals(cacheManager.mlFactoryMBean.getCacheMaxSize(), 7 * 10);
        assertEquals(cacheManager.mlFactoryMBean.getCacheUsedSize(), 0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheMissesRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsThroughput(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getNumberOfCacheEvictions(), 0);
    }

    @Test
    void verifyHitsMisses() throws Exception {
        ManagedLedgerFactoryConfig config = new ManagedLedgerFactoryConfig();
        config.setMaxCacheSize(7 * 10);
        config.setCacheEvictionWatermark(0.8);

        factory = new ManagedLedgerFactoryImpl(bkc, bkc.getZkHandle(), config);

        EntryCacheManager cacheManager = factory.getEntryCacheManager();
        ManagedLedgerImpl ledger = (ManagedLedgerImpl) factory.open("ledger");

        ManagedCursorImpl c1 = (ManagedCursorImpl) ledger.openCursor("c1");
        ManagedCursorImpl c2 = (ManagedCursorImpl) ledger.openCursor("c2");

        for (int i = 0; i < 10; i++) {
            ledger.addEntry(("entry-" + i).getBytes());
        }

        cacheManager.mlFactoryMBean.refreshStats(1, TimeUnit.SECONDS);
        assertEquals(cacheManager.mlFactoryMBean.getCacheUsedSize(), 70);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheMissesRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsThroughput(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getNumberOfCacheEvictions(), 0);

        List<Entry> entries = c1.readEntries(10);
        assertEquals(entries.size(), 10);
        entries.forEach(e -> e.release());

        cacheManager.mlFactoryMBean.refreshStats(1, TimeUnit.SECONDS);
        assertEquals(cacheManager.mlFactoryMBean.getCacheUsedSize(), 70);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsRate(), 10.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheMissesRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsThroughput(), 70.0);
        assertEquals(cacheManager.mlFactoryMBean.getNumberOfCacheEvictions(), 0);

        ledger.deactivateCursor(c1);

        cacheManager.mlFactoryMBean.refreshStats(1, TimeUnit.SECONDS);
        assertEquals(cacheManager.mlFactoryMBean.getCacheUsedSize(), 70);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheMissesRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsThroughput(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getNumberOfCacheEvictions(), 0);

        entries = c2.readEntries(10);
        assertEquals(entries.size(), 10);

        cacheManager.mlFactoryMBean.refreshStats(1, TimeUnit.SECONDS);
        assertEquals(cacheManager.mlFactoryMBean.getCacheUsedSize(), 70);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsRate(), 10.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheMissesRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsThroughput(), 70.0);
        assertEquals(cacheManager.mlFactoryMBean.getNumberOfCacheEvictions(), 0);

        PositionImpl pos = (PositionImpl) entries.get(entries.size() - 1).getPosition();
        c2.setReadPosition(pos);
        ledger.discardEntriesFromCache(c2, pos);
        entries.forEach(e -> e.release());

        cacheManager.mlFactoryMBean.refreshStats(1, TimeUnit.SECONDS);
        assertEquals(cacheManager.mlFactoryMBean.getCacheUsedSize(), 0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheMissesRate(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getCacheHitsThroughput(), 0.0);
        assertEquals(cacheManager.mlFactoryMBean.getNumberOfCacheEvictions(), 0);
    }
}
