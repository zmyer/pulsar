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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.bookkeeper.mledger.AsyncCallbacks;
import org.apache.bookkeeper.mledger.AsyncCallbacks.AddEntryCallback;
import org.apache.bookkeeper.mledger.AsyncCallbacks.MarkDeleteCallback;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.ManagedCursor;
import org.apache.bookkeeper.mledger.ManagedLedger;
import org.apache.bookkeeper.mledger.ManagedLedgerConfig;
import org.apache.bookkeeper.mledger.ManagedLedgerException;
import org.apache.bookkeeper.mledger.ManagedLedgerFactory;
import org.apache.bookkeeper.mledger.ManagedLedgerFactoryConfig;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.test.MockedBookKeeperTestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import com.google.common.base.Charsets;
import com.google.common.collect.Iterables;

public class NonDurableCursorTest extends MockedBookKeeperTestCase {

    private static final Charset Encoding = Charsets.UTF_8;

    @Test(timeOut = 20000)
    void readFromEmptyLedger() throws Exception {
        ManagedLedger ledger = factory.open("my_test_ledger");

        ManagedCursor c1 = ledger.newNonDurableCursor(PositionImpl.earliest);
        List<Entry> entries = c1.readEntries(10);
        assertEquals(entries.size(), 0);
        entries.forEach(e -> e.release());

        ledger.addEntry("test".getBytes(Encoding));
        entries = c1.readEntries(10);
        assertEquals(entries.size(), 1);
        entries.forEach(e -> e.release());

        entries = c1.readEntries(10);
        assertEquals(entries.size(), 0);
        entries.forEach(e -> e.release());

        // Test string representation
        assertEquals(c1.toString(), "NonDurableCursorImpl{ledger=my_test_ledger, ackPos=3:-1, readPos=3:1}");
    }

    @Test(timeOut = 20000)
    void testZNodeBypassed() throws Exception {
        ManagedLedger ledger = factory.open("my_test_ledger");

        ManagedCursor c1 = ledger.newNonDurableCursor(PositionImpl.earliest);
        assertTrue(Iterables.isEmpty(ledger.getCursors()));

        c1.close();
        ledger.close();

        // Re-open
        ManagedLedger ledger2 = factory.open("my_test_ledger");
        assertTrue(Iterables.isEmpty(ledger2.getCursors()));
    }

    @Test(timeOut = 20000)
    void readTwice() throws Exception {
        ManagedLedger ledger = factory.open("my_test_ledger",
                new ManagedLedgerConfig().setRetentionTime(1, TimeUnit.HOURS).setRetentionSizeInMB(1));

        ManagedCursor c1 = ledger.newNonDurableCursor(PositionImpl.latest);
        ManagedCursor c2 = ledger.newNonDurableCursor(PositionImpl.latest);

        ledger.addEntry("entry-1".getBytes(Encoding));
        ledger.addEntry("entry-2".getBytes(Encoding));

        List<Entry> entries = c1.readEntries(2);
        assertEquals(entries.size(), 2);
        entries.forEach(e -> e.release());

        entries = c1.readEntries(2);
        assertEquals(entries.size(), 0);
        entries.forEach(e -> e.release());

        entries = c2.readEntries(2);
        assertEquals(entries.size(), 2);
        entries.forEach(e -> e.release());

        entries = c2.readEntries(2);
        assertEquals(entries.size(), 0);
        entries.forEach(e -> e.release());
    }

    @Test(timeOut = 20000)
    void readWithCacheDisabled() throws Exception {
        ManagedLedgerFactoryConfig config = new ManagedLedgerFactoryConfig();
        config.setMaxCacheSize(0);
        factory = new ManagedLedgerFactoryImpl(bkc, bkc.getZkHandle(), config);
        ManagedLedger ledger = factory.open("my_test_ledger", new ManagedLedgerConfig().setMaxEntriesPerLedger(1)
                .setRetentionTime(1, TimeUnit.HOURS).setRetentionSizeInMB(1));

        ManagedCursor c1 = ledger.newNonDurableCursor(PositionImpl.latest);
        ManagedCursor c2 = ledger.newNonDurableCursor(PositionImpl.latest);

        ledger.addEntry("entry-1".getBytes(Encoding));
        ledger.addEntry("entry-2".getBytes(Encoding));

        List<Entry> entries = c1.readEntries(2);
        assertEquals(entries.size(), 2);
        assertEquals(new String(entries.get(0).getData(), Encoding), "entry-1");
        assertEquals(new String(entries.get(1).getData(), Encoding), "entry-2");
        entries.forEach(e -> e.release());

        entries = c1.readEntries(2);
        assertEquals(entries.size(), 0);
        entries.forEach(e -> e.release());

        entries = c2.readEntries(2);
        assertEquals(entries.size(), 2);
        entries.forEach(e -> e.release());

        entries = c2.readEntries(2);
        assertEquals(entries.size(), 0);
        entries.forEach(e -> e.release());
    }

    @Test(timeOut = 20000)
    void readFromClosedLedger() throws Exception {
        ManagedLedger ledger = factory.open("my_test_ledger", new ManagedLedgerConfig().setMaxEntriesPerLedger(1)
                .setRetentionTime(1, TimeUnit.HOURS).setRetentionSizeInMB(1));

        ManagedCursor c1 = ledger.newNonDurableCursor(PositionImpl.latest);

        ledger.close();

        try {
            c1.readEntries(2);
            fail("ledger is closed, should fail");
        } catch (ManagedLedgerException e) {
            // ok
        }
    }

    @Test(timeOut = 20000)
    void testNumberOfEntries() throws Exception {
        ManagedLedger ledger = factory.open("my_test_ledger", new ManagedLedgerConfig().setMaxEntriesPerLedger(2)
                .setRetentionTime(1, TimeUnit.HOURS).setRetentionSizeInMB(1));

        ManagedCursor c1 = ledger.newNonDurableCursor(PositionImpl.latest);
        ledger.addEntry("dummy-entry-1".getBytes(Encoding));
        ManagedCursor c2 = ledger.newNonDurableCursor(PositionImpl.latest);
        ledger.addEntry("dummy-entry-2".getBytes(Encoding));
        ManagedCursor c3 = ledger.newNonDurableCursor(PositionImpl.latest);
        ledger.addEntry("dummy-entry-3".getBytes(Encoding));
        ManagedCursor c4 = ledger.newNonDurableCursor(PositionImpl.latest);
        ledger.addEntry("dummy-entry-4".getBytes(Encoding));
        ManagedCursor c5 = ledger.newNonDurableCursor(PositionImpl.latest);

        assertEquals(c1.getNumberOfEntries(), 4);
        assertEquals(c1.hasMoreEntries(), true);

        assertEquals(c2.getNumberOfEntries(), 3);
        assertEquals(c2.hasMoreEntries(), true);

        assertEquals(c3.getNumberOfEntries(), 2);
        assertEquals(c3.hasMoreEntries(), true);

        assertEquals(c4.getNumberOfEntries(), 1);
        assertEquals(c4.hasMoreEntries(), true);

        assertEquals(c5.getNumberOfEntries(), 0);
        assertEquals(c5.hasMoreEntries(), false);

        List<Entry> entries = c1.readEntries(2);
        assertEquals(entries.size(), 2);
        c1.markDelete(entries.get(1).getPosition());
        assertEquals(c1.getNumberOfEntries(), 2);
        entries.forEach(e -> e.release());
    }

    @Test(timeOut = 20000)
    void testNumberOfEntriesInBacklog() throws Exception {
        ManagedLedger ledger = factory.open("my_test_ledger", new ManagedLedgerConfig().setMaxEntriesPerLedger(2)
                .setRetentionTime(1, TimeUnit.HOURS).setRetentionSizeInMB(1));

        ManagedCursor c1 = ledger.newNonDurableCursor(PositionImpl.latest);
        Position p1 = ledger.addEntry("dummy-entry-1".getBytes(Encoding));
        ManagedCursor c2 = ledger.newNonDurableCursor(PositionImpl.latest);
        ledger.addEntry("dummy-entry-2".getBytes(Encoding));
        ManagedCursor c3 = ledger.newNonDurableCursor(PositionImpl.latest);
        Position p3 = ledger.addEntry("dummy-entry-3".getBytes(Encoding));
        ManagedCursor c4 = ledger.newNonDurableCursor(PositionImpl.latest);
        Position p4 = ledger.addEntry("dummy-entry-4".getBytes(Encoding));
        ManagedCursor c5 = ledger.newNonDurableCursor(PositionImpl.latest);

        assertEquals(c1.getNumberOfEntriesInBacklog(), 4);
        assertEquals(c2.getNumberOfEntriesInBacklog(), 3);
        assertEquals(c3.getNumberOfEntriesInBacklog(), 2);
        assertEquals(c4.getNumberOfEntriesInBacklog(), 1);
        assertEquals(c5.getNumberOfEntriesInBacklog(), 0);

        List<Entry> entries = c1.readEntries(2);
        assertEquals(entries.size(), 2);
        entries.forEach(e -> e.release());

        assertEquals(c1.getNumberOfEntries(), 2);
        assertEquals(c1.getNumberOfEntriesInBacklog(), 4);

        c1.markDelete(p1);
        assertEquals(c1.getNumberOfEntries(), 2);
        assertEquals(c1.getNumberOfEntriesInBacklog(), 3);

        c1.delete(p3);

        assertEquals(c1.getNumberOfEntries(), 1);
        assertEquals(c1.getNumberOfEntriesInBacklog(), 2);

        c1.markDelete(p4);
        assertEquals(c1.getNumberOfEntries(), 0);
        assertEquals(c1.getNumberOfEntriesInBacklog(), 0);
    }

    @Test(timeOut = 20000)
    void markDeleteWithErrors() throws Exception {
        ManagedLedger ledger = factory.open("my_test_ledger");
        ManagedCursor cursor = ledger.openCursor("c1");
        ledger.addEntry("dummy-entry-1".getBytes(Encoding));
        List<Entry> entries = cursor.readEntries(100);

        stopBookKeeper();
        assertEquals(entries.size(), 1);

        try {
            cursor.markDelete(entries.get(0).getPosition());
            fail("call should have failed");
        } catch (ManagedLedgerException e) {
            // ok
        }

        entries.forEach(e -> e.release());
    }

    @Test(timeOut = 20000)
    void markDeleteAcrossLedgers() throws Exception {
        ManagedLedger ml1 = factory.open("my_test_ledger");
        ManagedCursor mc1 = ml1.openCursor("c1");

        // open ledger id 3 for ml1
        // markDeletePosition for mc1 is 3:-1
        // readPosition is 3:0

        ml1.close();
        mc1.close();

        // force removal of this ledger from the cache
        factory.close(ml1);

        ManagedLedger ml2 = factory.open("my_test_ledger");
        ManagedCursor mc2 = ml2.openCursor("c1");

        // open ledger id 5 for ml2
        // this entry is written at 5:0
        Position pos = ml2.addEntry("dummy-entry-1".getBytes(Encoding));

        List<Entry> entries = mc2.readEntries(1);
        assertEquals(entries.size(), 1);
        assertEquals(new String(entries.get(0).getData(), Encoding), "dummy-entry-1");
        entries.forEach(e -> e.release());

        mc2.delete(pos);

        // verify if the markDeletePosition moves from 3:-1 to 5:0
        assertEquals(mc2.getMarkDeletedPosition(), pos);
        assertEquals(mc2.getMarkDeletedPosition().getNext(), mc2.getReadPosition());
    }

    @Test(timeOut = 20000)
    void testResetCursor() throws Exception {
        ManagedLedger ledger = factory.open("my_test_move_cursor_ledger",
                new ManagedLedgerConfig().setMaxEntriesPerLedger(10));
        ManagedCursor cursor = ledger.newNonDurableCursor(PositionImpl.latest);
        ledger.addEntry("dummy-entry-1".getBytes(Encoding));
        ledger.addEntry("dummy-entry-2".getBytes(Encoding));
        ledger.addEntry("dummy-entry-3".getBytes(Encoding));
        PositionImpl lastPosition = (PositionImpl) ledger.addEntry("dummy-entry-4".getBytes(Encoding));
        final AtomicBoolean moveStatus = new AtomicBoolean(false);
        PositionImpl resetPosition = new PositionImpl(lastPosition.getLedgerId(), lastPosition.getEntryId() - 2);
        try {
            cursor.resetCursor(resetPosition);
            moveStatus.set(true);
        } catch (Exception e) {
            log.warn("error in reset cursor", e.getCause());
        }

        assertTrue(moveStatus.get());
        assertTrue(cursor.getReadPosition().equals(resetPosition));
        cursor.close();
        ledger.close();
    }

    @Test(timeOut = 20000)
    void testasyncResetCursor() throws Exception {
        ManagedLedger ledger = factory.open("my_test_move_cursor_ledger",
                new ManagedLedgerConfig().setMaxEntriesPerLedger(10));
        ManagedCursor cursor = ledger.newNonDurableCursor(PositionImpl.latest);
        ledger.addEntry("dummy-entry-1".getBytes(Encoding));
        ledger.addEntry("dummy-entry-2".getBytes(Encoding));
        ledger.addEntry("dummy-entry-3".getBytes(Encoding));
        PositionImpl lastPosition = (PositionImpl) ledger.addEntry("dummy-entry-4".getBytes(Encoding));
        final AtomicBoolean moveStatus = new AtomicBoolean(false);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        PositionImpl resetPosition = new PositionImpl(lastPosition.getLedgerId(), lastPosition.getEntryId() - 2);

        cursor.asyncResetCursor(resetPosition, new AsyncCallbacks.ResetCursorCallback() {
            @Override
            public void resetComplete(Object ctx) {
                moveStatus.set(true);
                countDownLatch.countDown();
            }

            @Override
            public void resetFailed(ManagedLedgerException exception, Object ctx) {
                moveStatus.set(false);
                countDownLatch.countDown();
            }
        });
        countDownLatch.await();
        assertTrue(moveStatus.get());
        assertTrue(cursor.getReadPosition().equals(resetPosition));
        cursor.close();
        ledger.close();
    }

    @Test(timeOut = 20000)
    void rewind() throws Exception {
        ManagedLedger ledger = factory.open("my_test_ledger", new ManagedLedgerConfig().setMaxEntriesPerLedger(2)
                .setRetentionTime(1, TimeUnit.HOURS).setRetentionSizeInMB(1));
        ManagedCursor c1 = ledger.newNonDurableCursor(PositionImpl.earliest);
        Position p1 = ledger.addEntry("dummy-entry-1".getBytes(Encoding));
        Position p2 = ledger.addEntry("dummy-entry-2".getBytes(Encoding));
        Position p3 = ledger.addEntry("dummy-entry-3".getBytes(Encoding));
        Position p4 = ledger.addEntry("dummy-entry-4".getBytes(Encoding));

        log.debug("p1: {}", p1);
        log.debug("p2: {}", p2);
        log.debug("p3: {}", p3);
        log.debug("p4: {}", p4);

        assertEquals(c1.getNumberOfEntries(), 4);
        assertEquals(c1.getNumberOfEntriesInBacklog(), 4);
        c1.markDelete(p1);
        assertEquals(c1.getNumberOfEntries(), 3);
        assertEquals(c1.getNumberOfEntriesInBacklog(), 3);
        List<Entry> entries = c1.readEntries(10);
        assertEquals(entries.size(), 3);
        entries.forEach(e -> e.release());

        assertEquals(c1.getNumberOfEntries(), 0);
        assertEquals(c1.getNumberOfEntriesInBacklog(), 3);
        c1.rewind();
        assertEquals(c1.getNumberOfEntries(), 3);
        assertEquals(c1.getNumberOfEntriesInBacklog(), 3);
        c1.markDelete(p2);
        assertEquals(c1.getNumberOfEntries(), 2);
        assertEquals(c1.getNumberOfEntriesInBacklog(), 2);

        entries = c1.readEntries(10);
        assertEquals(entries.size(), 2);
        entries.forEach(e -> e.release());

        assertEquals(c1.getNumberOfEntries(), 0);
        assertEquals(c1.getNumberOfEntriesInBacklog(), 2);
        c1.rewind();
        assertEquals(c1.getNumberOfEntries(), 2);
        c1.markDelete(p4);
        assertEquals(c1.getNumberOfEntries(), 0);
        assertEquals(c1.getNumberOfEntriesInBacklog(), 0);
        c1.rewind();
        assertEquals(c1.getNumberOfEntries(), 0);
        ledger.addEntry("dummy-entry-5".getBytes(Encoding));
        assertEquals(c1.getNumberOfEntries(), 1);
        ledger.addEntry("dummy-entry-6".getBytes(Encoding));
        assertEquals(c1.getNumberOfEntries(), 2);
    }

    @Test(timeOut = 20000)
    void markDeleteSkippingMessage() throws Exception {
        ManagedLedger ledger = factory.open("my_test_ledger", new ManagedLedgerConfig().setMaxEntriesPerLedger(10));
        ManagedCursor cursor = ledger.newNonDurableCursor(PositionImpl.earliest);
        Position p1 = ledger.addEntry("dummy-entry-1".getBytes(Encoding));
        Position p2 = ledger.addEntry("dummy-entry-2".getBytes(Encoding));
        ledger.addEntry("dummy-entry-3".getBytes(Encoding));
        PositionImpl p4 = (PositionImpl) ledger.addEntry("dummy-entry-4".getBytes(Encoding));

        assertEquals(cursor.getNumberOfEntries(), 4);

        cursor.markDelete(p1);
        assertEquals(cursor.hasMoreEntries(), true);
        assertEquals(cursor.getNumberOfEntries(), 3);

        assertEquals(cursor.getReadPosition(), p2);

        List<Entry> entries = cursor.readEntries(1);
        assertEquals(entries.size(), 1);
        assertEquals(new String(entries.get(0).getData(), Encoding), "dummy-entry-2");
        entries.forEach(e -> e.release());

        cursor.markDelete(p4);
        assertEquals(cursor.hasMoreEntries(), false);
        assertEquals(cursor.getNumberOfEntries(), 0);

        assertEquals(cursor.getReadPosition(), new PositionImpl(p4.getLedgerId(), p4.getEntryId() + 1));
    }

    @Test(timeOut = 20000)
    public void asyncMarkDeleteBlocking() throws Exception {
        ManagedLedgerConfig config = new ManagedLedgerConfig();
        config.setMaxEntriesPerLedger(10);
        config.setMetadataMaxEntriesPerLedger(5);
        ManagedLedger ledger = factory.open("my_test_ledger", config);
        final ManagedCursor c1 = ledger.openCursor("c1");
        final AtomicReference<Position> lastPosition = new AtomicReference<Position>();

        final int N = 100;
        final CountDownLatch latch = new CountDownLatch(N);
        for (int i = 0; i < N; i++) {
            ledger.asyncAddEntry("entry".getBytes(Encoding), new AddEntryCallback() {
                @Override
                public void addFailed(ManagedLedgerException exception, Object ctx) {
                }

                @Override
                public void addComplete(Position position, Object ctx) {
                    lastPosition.set(position);
                    c1.asyncMarkDelete(position, new MarkDeleteCallback() {
                        @Override
                        public void markDeleteFailed(ManagedLedgerException exception, Object ctx) {
                        }

                        @Override
                        public void markDeleteComplete(Object ctx) {
                            latch.countDown();
                        }
                    }, null);
                }
            }, null);
        }

        latch.await();

        assertEquals(c1.getNumberOfEntries(), 0);

        // Reopen
        ManagedLedgerFactory factory2 = new ManagedLedgerFactoryImpl(bkc, bkc.getZkHandle());
        ledger = factory2.open("my_test_ledger");
        ManagedCursor c2 = ledger.openCursor("c1");

        assertEquals(c2.getMarkDeletedPosition(), lastPosition.get());
        factory2.shutdown();
    }

    @Test(timeOut = 20000)
    void unorderedMarkDelete() throws Exception {
        ManagedLedger ledger = factory.open("my_test_ledger");
        final ManagedCursor c1 = ledger.openCursor("c1");

        Position p1 = ledger.addEntry("entry-1".getBytes(Encoding));
        Position p2 = ledger.addEntry("entry-2".getBytes(Encoding));

        c1.markDelete(p2);
        try {
            c1.markDelete(p1);
            fail("Should have thrown exception");
        } catch (ManagedLedgerException e) {
            // ok
        }

        assertEquals(c1.getMarkDeletedPosition(), p2);
    }

    @Test(timeOut = 20000)
    void testSingleDelete() throws Exception {
        ManagedLedger ledger = factory.open("my_test_ledger", new ManagedLedgerConfig().setMaxEntriesPerLedger(3)
                .setRetentionTime(1, TimeUnit.HOURS).setRetentionSizeInMB(1));
        ManagedCursor cursor = ledger.newNonDurableCursor(PositionImpl.latest);

        Position p1 = ledger.addEntry("entry1".getBytes());
        Position p2 = ledger.addEntry("entry2".getBytes());
        Position p3 = ledger.addEntry("entry3".getBytes());
        Position p4 = ledger.addEntry("entry4".getBytes());
        Position p5 = ledger.addEntry("entry5".getBytes());
        Position p6 = ledger.addEntry("entry6".getBytes());

        Position p0 = cursor.getMarkDeletedPosition();

        cursor.delete(p4);
        assertEquals(cursor.getMarkDeletedPosition(), p0);

        cursor.delete(p1);
        assertEquals(cursor.getMarkDeletedPosition(), p1);

        cursor.delete(p3);

        // Delete will silently succeed
        cursor.delete(p3);
        assertEquals(cursor.getMarkDeletedPosition(), p1);

        cursor.delete(p2);
        assertEquals(cursor.getMarkDeletedPosition(), p4);

        cursor.delete(p5);
        assertEquals(cursor.getMarkDeletedPosition(), p5);

        cursor.close();
        try {
            cursor.delete(p6);
        } catch (ManagedLedgerException e) {
            // Ok
        }
    }

    @Test(timeOut = 20000)
    void subscribeToEarliestPositionWithImmediateDeletion() throws Exception {
        ManagedLedger ledger = factory.open("my_test_ledger", new ManagedLedgerConfig().setMaxEntriesPerLedger(1));

        /* Position p1 = */ ledger.addEntry("entry-1".getBytes());
        /* Position p2 = */ ledger.addEntry("entry-2".getBytes());
        Position p3 = ledger.addEntry("entry-3".getBytes());

        Thread.sleep(300);
        ManagedCursor c1 = ledger.newNonDurableCursor(PositionImpl.earliest);
        assertEquals(c1.getReadPosition(), p3);
        assertEquals(c1.getMarkDeletedPosition(), new PositionImpl(5, -1));
    }

    @Test // (timeOut = 20000)
    void subscribeToEarliestPositionWithDeferredDeletion() throws Exception {
        ManagedLedger ledger = factory.open("my_test_ledger", new ManagedLedgerConfig().setMaxEntriesPerLedger(1)
                .setRetentionTime(1, TimeUnit.HOURS).setRetentionSizeInMB(1));

        Position p1 = ledger.addEntry("entry-1".getBytes());
        Position p2 = ledger.addEntry("entry-2".getBytes());
        /* Position p3 = */ ledger.addEntry("entry-3".getBytes());
        /* Position p4 = */ ledger.addEntry("entry-4".getBytes());
        /* Position p5 = */ ledger.addEntry("entry-5".getBytes());
        /* Position p6 = */ ledger.addEntry("entry-6".getBytes());

        ManagedCursor c1 = ledger.newNonDurableCursor(PositionImpl.earliest);
        assertEquals(c1.getReadPosition(), p1);
        assertEquals(c1.getMarkDeletedPosition(), new PositionImpl(3, -1));
        assertEquals(c1.getNumberOfEntries(), 6);
        assertEquals(c1.getNumberOfEntriesInBacklog(), 6);

        ManagedCursor c2 = ledger.newNonDurableCursor(p1);
        assertEquals(c2.getReadPosition(), p2);
        assertEquals(c2.getMarkDeletedPosition(), p1);
        assertEquals(c2.getNumberOfEntries(), 5);
        assertEquals(c2.getNumberOfEntriesInBacklog(), 5);
    }

    private static final Logger log = LoggerFactory.getLogger(NonDurableCursorTest.class);
}
