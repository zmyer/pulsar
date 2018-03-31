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
package org.apache.bookkeeper.mledger;

import com.google.common.annotations.Beta;
import io.netty.buffer.ByteBuf;
import org.apache.bookkeeper.mledger.AsyncCallbacks.AddEntryCallback;
import org.apache.bookkeeper.mledger.AsyncCallbacks.CloseCallback;
import org.apache.bookkeeper.mledger.AsyncCallbacks.DeleteCursorCallback;
import org.apache.bookkeeper.mledger.AsyncCallbacks.DeleteLedgerCallback;
import org.apache.bookkeeper.mledger.AsyncCallbacks.OpenCursorCallback;
import org.apache.bookkeeper.mledger.AsyncCallbacks.TerminateCallback;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandSubscribe.InitialPosition;

/**
 * A ManagedLedger it's a superset of a BookKeeper ledger concept.
 *
 * <p/>It mimics the concept of an appender log that:
 *
 * <ul>
 * <li>has a unique name (chosen by clients) by which it can be created/opened/deleted</li>
 * <li>is always writable: if a writer process crashes, a new writer can re-open the ManagedLedger and continue writing
 * into it</li>
 * <li>has multiple persisted consumers (see {@link ManagedCursor}), each of them with an associated position</li>
 * <li>when all the consumers have processed all the entries contained in a Bookkeeper ledger, the ledger is
 * deleted</li>
 * </ul>
 *
 * <p/>Caveats:
 * <ul>
 * <li>A single ManagedLedger can only be open once at any time. Implementation can protect double access from the same
 * VM, but accesses from different machines to the same ManagedLedger need to be avoided through an external source of
 * coordination.</li>
 * </ul>
 */
@Beta
public interface ManagedLedger {

    /**
     * @return the unique name of this ManagedLedger
     */
    String getName();

    /**
     * Append a new entry to the end of a managed ledger.
     *
     * @param data
     *            data entry to be persisted
     * @return the Position at which the entry has been inserted
     * @throws ManagedLedgerException
     */
    Position addEntry(byte[] data) throws InterruptedException, ManagedLedgerException;

    /**
     * Append a new entry asynchronously.
     *
     * @see #addEntry(byte[])
     * @param data
     *            data entry to be persisted
     *
     * @param callback
     *            callback object
     * @param ctx
     *            opaque context
     */
    void asyncAddEntry(byte[] data, AddEntryCallback callback, Object ctx);

    /**
     * Append a new entry to the end of a managed ledger.
     *
     * @param data
     *            data entry to be persisted
     * @param offset
     *            offset in the data array
     * @param length
     *            number of bytes
     * @return the Position at which the entry has been inserted
     * @throws ManagedLedgerException
     */
    Position addEntry(byte[] data, int offset, int length) throws InterruptedException, ManagedLedgerException;

    /**
     * Append a new entry asynchronously.
     *
     * @see #addEntry(byte[])
     * @param data
     *            data entry to be persisted
     * @param offset
     *            offset in the data array
     * @param length
     *            number of bytes
     * @param callback
     *            callback object
     * @param ctx
     *            opaque context
     */
    void asyncAddEntry(byte[] data, int offset, int length, AddEntryCallback callback, Object ctx);

    /**
     * Append a new entry asynchronously.
     *
     * @see #addEntry(byte[])
     * @param buffer
     *            buffer with the data entry
     * @param callback
     *            callback object
     * @param ctx
     *            opaque context
     */
    void asyncAddEntry(ByteBuf buffer, AddEntryCallback callback, Object ctx);

    /**
     * Open a ManagedCursor in this ManagedLedger.
     *
     * <p/>If the cursors doesn't exist, a new one will be created and its position will be at the end of the
     * ManagedLedger.
     *
     * @param name
     *            the name associated with the ManagedCursor
     * @param initializeOnLatest
     *            the flag tell the method wthether it should intialize the cursor at latest position or not.
     * @return the ManagedCursor
     * @throws ManagedLedgerException
     */
    ManagedCursor openCursor(String name) throws InterruptedException, ManagedLedgerException;

    /**
     * Open a ManagedCursor in this ManagedLedger.
     * <p>
     * If the cursors doesn't exist, a new one will be created and its position will be at the end of the ManagedLedger.
     *
     * @param name
     *            the name associated with the ManagedCursor
     * @param initialPosition
     *            the cursor will be set at lastest position or not when first created
     *            default is <b>true</b>
     * @return the ManagedCursor
     * @throws ManagedLedgerException
     */
    public ManagedCursor openCursor(String name, InitialPosition initialPosition) throws InterruptedException, ManagedLedgerException;

    /**
     * Creates a new cursor whose metadata is not backed by durable storage. A caller can treat the non-durable cursor
     * exactly like a normal cursor, with the only difference in that after restart it will not remember which entries
     * were deleted. Also it does not prevent data from being deleted.
     *
     * <p/>The cursor is anonymous and can be positioned on an arbitrary position.
     *
     * <p/>This method is not-blocking.
     *
     * @param startCursorPosition
     *            the position where the cursor should be initialized, or null to start from the current latest entry.
     *            When starting on a particular cursor position, the first entry to be returned will be the entry next
     *            to the specified position
     * @return the new NonDurableCursor
     */
    ManagedCursor newNonDurableCursor(Position startCursorPosition) throws ManagedLedgerException;

    /**
     * Delete a ManagedCursor asynchronously.
     *
     * @see #deleteCursor(String)
     * @param name
     *            the name associated with the ManagedCursor
     * @param callback
     *            callback object
     * @param ctx
     *            opaque context
     */
    void asyncDeleteCursor(String name, DeleteCursorCallback callback, Object ctx);

    /**
     * Remove a ManagedCursor from this ManagedLedger.
     *
     * <p/>If the cursor doesn't exist, the operation will still succeed.
     *
     * @param name
     *            the name associated with the ManagedCursor
     *
     * @throws ManagedLedgerException
     * @throws InterruptedException
     */
    void deleteCursor(String name) throws InterruptedException, ManagedLedgerException;

    /**
     * Open a ManagedCursor asynchronously.
     *
     * @see #openCursor(String)
     * @param name
     *            the name associated with the ManagedCursor
     * @param callback
     *            callback object
     * @param ctx
     *            opaque context
     */
    void asyncOpenCursor(String name, OpenCursorCallback callback, Object ctx);

    /**
     * Open a ManagedCursor asynchronously.
     *
     * @see #openCursor(String)
     * @param name
     *            the name associated with the ManagedCursor
     * @param initialPosition
     *            the cursor will be set at lastest position or not when first created
     *            default is <b>true</b>
     * @param callback
     *            callback object
     * @param ctx
     *            opaque context
     */
    public void asyncOpenCursor(String name, InitialPosition initialPosition, OpenCursorCallback callback, Object ctx);

    /**
     * Get a list of all the cursors reading from this ManagedLedger
     *
     * @return a list of cursors
     */
    Iterable<ManagedCursor> getCursors();

    /**
     * Get a list of all the active cursors reading from this ManagedLedger.
     *
     * @return a list of cursors
     */
    Iterable<ManagedCursor> getActiveCursors();

    /**
     * Get the total number of entries for this managed ledger.
     *
     * <p/>This is defined by the number of entries in all the BookKeeper ledgers that are being maintained by this
     * ManagedLedger.
     *
     * <p/>This method is non-blocking.
     *
     * @return the number of entries
     */
    long getNumberOfEntries();

    /**
     * Get the total number of active entries for this managed ledger.
     *
     * <p/>This is defined by the number of non consumed entries in all the BookKeeper ledgers that are being maintained
     * by this ManagedLedger.
     *
     * <p/>This method is non-blocking.
     *
     * @return the number of entries
     */
    long getNumberOfActiveEntries();

    /**
     * Get the total sizes in bytes of the managed ledger, without accounting for replicas.
     *
     * <p/>This is defined by the sizes of all the BookKeeper ledgers that are being maintained by this ManagedLedger.
     *
     * <p/>This method is non-blocking.
     *
     * @return total size in bytes
     */
    long getTotalSize();

    /**
     * Get estimated total unconsumed or backlog size in bytes for the managed ledger, without accounting for replicas.
     *
     * @return estimated total backlog size
     */
    long getEstimatedBacklogSize();

    /**
     * Activate cursors those caught up backlog-threshold entries and deactivate slow cursors which are creating
     * backlog.
     */
    void checkBackloggedCursors();

    void asyncTerminate(TerminateCallback callback, Object ctx);

    /**
     * Terminate the managed ledger and return the last committed entry.
     *
     * <p/>Once the managed ledger is terminated, it will not accept any more write
     *
     * @return
     * @throws InterruptedException
     * @throws ManagedLedgerException
     */
    Position terminate() throws InterruptedException, ManagedLedgerException;

    /**
     * Close the ManagedLedger.
     *
     * <p/>This will close all the underlying BookKeeper ledgers. All the ManagedCursors associated will be invalidated.
     *
     * @throws ManagedLedgerException
     */
    void close() throws InterruptedException, ManagedLedgerException;

    /**
     * Close the ManagedLedger asynchronously.
     *
     * @see #close()
     * @param callback
     *            callback object
     * @param ctx
     *            opaque context
     */
    void asyncClose(CloseCallback callback, Object ctx);

    /**
     * @return the managed ledger stats MBean
     */
    ManagedLedgerMXBean getStats();

    /**
     * Delete the ManagedLedger.
     *
     * @throws InterruptedException
     * @throws ManagedLedgerException
     */
    void delete() throws InterruptedException, ManagedLedgerException;

    /**
     * Async delete a ledger.
     *
     * @param callback
     * @param ctx
     * @throws InterruptedException
     * @throws ManagedLedgerException
     */
    void asyncDelete(DeleteLedgerCallback callback, Object ctx);

    /**
     * Get the slowest consumer.
     *
     * @return the slowest consumer
     */
    ManagedCursor getSlowestConsumer();

    /**
     * Returns whether the managed ledger was terminated.
     */
    boolean isTerminated();

    /**
     * Returns managed-ledger config.
     */
    ManagedLedgerConfig getConfig();

    /**
     * Updates managed-ledger config.
     *
     * @param config
     */
    void setConfig(ManagedLedgerConfig config);

    /**
     * Gets last confirmed entry of the managed ledger.
     *
     * @return the last confirmed entry id
     */
    Position getLastConfirmedEntry();
}
