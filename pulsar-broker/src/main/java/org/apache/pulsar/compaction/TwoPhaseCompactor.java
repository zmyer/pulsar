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
package org.apache.pulsar.compaction;

import com.google.common.collect.ImmutableMap;
import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerHandle;

import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.common.api.Commands;
import org.apache.pulsar.common.api.proto.PulsarApi.MessageMetadata;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.RawReader;
import org.apache.pulsar.client.api.RawMessage;
import org.apache.pulsar.client.impl.RawBatchConverter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compaction will go through the topic in two passes. The first pass
 * selects latest offset for each key in the topic. Then the second pass
 * writes these values to a ledger.
 *
 * <p>The two passes are required to avoid holding the payloads of each of
 * the latest values in memory, as the payload can be many orders of
 * magnitude larger than a message id.
*/
public class TwoPhaseCompactor extends Compactor {
    private static final Logger log = LoggerFactory.getLogger(TwoPhaseCompactor.class);
    private static final int MAX_OUTSTANDING = 500;
    private static final String COMPACTED_TOPIC_LEDGER_PROPERTY = "CompactedTopicLedger";

    public TwoPhaseCompactor(ServiceConfiguration conf,
                             PulsarClient pulsar,
                             BookKeeper bk,
                             ScheduledExecutorService scheduler) {
        super(conf, pulsar, bk, scheduler);
    }

    @Override
    protected CompletableFuture<Long> doCompaction(RawReader reader, BookKeeper bk) {
        return phaseOne(reader).thenCompose(
                (r) -> phaseTwo(reader, r.from, r.to, r.latestForKey, bk));
    }

    private CompletableFuture<PhaseOneResult> phaseOne(RawReader reader) {
        Map<String,MessageId> latestForKey = new HashMap<>();
        CompletableFuture<PhaseOneResult> loopPromise = new CompletableFuture<>();

        reader.getLastMessageIdAsync().whenComplete(
                (lastMessageId, exception) -> {
                    if (exception != null) {
                        loopPromise.completeExceptionally(exception);
                    } else {
                        log.info("Commencing phase one of compaction for {}, reading to {}",
                                 reader.getTopic(), lastMessageId);
                        phaseOneLoop(reader, Optional.empty(), lastMessageId, latestForKey, loopPromise);
                    }
                });
        return loopPromise;
    }

    private void phaseOneLoop(RawReader reader,
                              Optional<MessageId> firstMessageId,
                              MessageId lastMessageId,
                              Map<String,MessageId> latestForKey,
                              CompletableFuture<PhaseOneResult> loopPromise) {
        if (loopPromise.isDone()) {
            return;
        }
        CompletableFuture<RawMessage> future = reader.readNextAsync();
        scheduleTimeout(future);
        future.whenCompleteAsync(
                (m, exception) -> {
                    try {
                        if (exception != null) {
                            loopPromise.completeExceptionally(exception);
                            return;
                        }
                        MessageId id = m.getMessageId();
                        if (RawBatchConverter.isBatch(m)) {
                            try {
                                RawBatchConverter.extractIdsAndKeys(m)
                                    .forEach(e -> latestForKey.put(e.getRight(), e.getLeft()));
                            } catch (IOException ioe) {
                                log.info("Error decoding batch for message {}. Whole batch will be included in output",
                                         id, ioe);
                            }
                        } else {
                            String key = extractKey(m);
                            latestForKey.put(key, id);
                        }

                        if (id.compareTo(lastMessageId) == 0) {
                            loopPromise.complete(new PhaseOneResult(firstMessageId.orElse(id),
                                                                    id, latestForKey));
                        } else {
                            phaseOneLoop(reader,
                                         Optional.of(firstMessageId.orElse(id)),
                                         lastMessageId,
                                         latestForKey, loopPromise);
                        }
                    } finally {
                        m.close();
                    }
                }, scheduler);
    }

    private void scheduleTimeout(CompletableFuture<RawMessage> future) {
        Future<?> timeout = scheduler.schedule(() -> {
                future.completeExceptionally(new TimeoutException("Timeout"));
            }, 10, TimeUnit.SECONDS);
        future.whenComplete((res, exception) -> {
                timeout.cancel(true);
            });
    }

    private CompletableFuture<Long> phaseTwo(RawReader reader, MessageId from, MessageId to,
                                             Map<String,MessageId> latestForKey, BookKeeper bk) {

        return createLedger(bk).thenCompose((ledger) -> {
                log.info("Commencing phase two of compaction for {}, from {} to {}, compacting {} keys to ledger {}",
                         reader.getTopic(), from, to, latestForKey.size(), ledger.getId());
                return phaseTwoSeekThenLoop(reader, from, to, latestForKey, bk, ledger);
            });
    }

    private CompletableFuture<Long> phaseTwoSeekThenLoop(RawReader reader, MessageId from, MessageId to,
                                                         Map<String, MessageId> latestForKey,
                                                         BookKeeper bk, LedgerHandle ledger) {
        CompletableFuture<Long> promise = new CompletableFuture<>();

        reader.seekAsync(from).thenCompose((v) -> {
                Semaphore outstanding = new Semaphore(MAX_OUTSTANDING);
                CompletableFuture<Void> loopPromise = new CompletableFuture<Void>();
                phaseTwoLoop(reader, to, latestForKey, ledger, outstanding, loopPromise);
                return loopPromise;
            }).thenCompose((v) -> closeLedger(ledger))
            .thenCompose((v) -> reader.acknowledgeCumulativeAsync(
                                 to, ImmutableMap.of(COMPACTED_TOPIC_LEDGER_PROPERTY, ledger.getId())))
            .whenComplete((res, exception) -> {
                    if (exception != null) {
                        deleteLedger(bk, ledger)
                            .whenComplete((res2, exception2) -> {
                                    if (exception2 != null) {
                                        log.warn("Cleanup of ledger {} for failed", ledger, exception2);
                                    }
                                    // complete with original exception
                                    promise.completeExceptionally(exception);
                                });
                    } else {
                        promise.complete(ledger.getId());
                    }
                });
        return promise;
    }

    private void phaseTwoLoop(RawReader reader, MessageId to, Map<String, MessageId> latestForKey,
                              LedgerHandle lh, Semaphore outstanding, CompletableFuture<Void> promise) {
        reader.readNextAsync().whenCompleteAsync(
                (m, exception) -> {
                    if (exception != null) {
                        promise.completeExceptionally(exception);
                        return;
                    } else if (promise.isDone()) {
                        return;
                    }
                    MessageId id = m.getMessageId();
                    Optional<RawMessage> messageToAdd = Optional.empty();
                    if (RawBatchConverter.isBatch(m)) {
                        try {
                            messageToAdd = RawBatchConverter.rebatchMessage(
                                    m, (key, subid) -> latestForKey.get(key).equals(subid));
                        } catch (IOException ioe) {
                            log.info("Error decoding batch for message {}. Whole batch will be included in output",
                                     id, ioe);
                            messageToAdd = Optional.of(m);
                        }
                    } else {
                        String key = extractKey(m);
                        if (latestForKey.get(key).equals(id)) {
                            messageToAdd = Optional.of(m);
                        } else {
                            m.close();
                        }
                    }

                    messageToAdd.ifPresent((toAdd) -> {
                            try {
                                outstanding.acquire();
                                CompletableFuture<Void> addFuture = addToCompactedLedger(lh, toAdd)
                                    .whenComplete((res, exception2) -> {
                                            outstanding.release();
                                            if (exception2 != null) {
                                                promise.completeExceptionally(exception2);
                                            }
                                        });
                                if (to.equals(id)) {
                                    addFuture.whenComplete((res, exception2) -> {
                                            if (exception2 == null) {
                                                promise.complete(null);
                                            }
                                        });
                                }
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                promise.completeExceptionally(ie);
                            }
                        });
                    phaseTwoLoop(reader, to, latestForKey, lh, outstanding, promise);
                }, scheduler);
    }

    private CompletableFuture<LedgerHandle> createLedger(BookKeeper bk) {
        CompletableFuture<LedgerHandle> bkf = new CompletableFuture<>();
        bk.asyncCreateLedger(conf.getManagedLedgerDefaultEnsembleSize(),
                             conf.getManagedLedgerDefaultWriteQuorum(),
                             conf.getManagedLedgerDefaultAckQuorum(),
                             Compactor.COMPACTED_TOPIC_LEDGER_DIGEST_TYPE,
                             Compactor.COMPACTED_TOPIC_LEDGER_PASSWORD,
                             (rc, ledger, ctx) -> {
                                 if (rc != BKException.Code.OK) {
                                     bkf.completeExceptionally(BKException.create(rc));
                                 } else {
                                     bkf.complete(ledger);
                                 }
                             }, null, Collections.emptyMap());
        return bkf;
    }

    private CompletableFuture<Void> deleteLedger(BookKeeper bk, LedgerHandle lh) {
        CompletableFuture<Void> bkf = new CompletableFuture<>();
        bk.asyncDeleteLedger(lh.getId(),
                             (rc, ctx) -> {
                                 if (rc != BKException.Code.OK) {
                                     bkf.completeExceptionally(BKException.create(rc));
                                 } else {
                                     bkf.complete(null);
                                 }
                             }, null);
        return bkf;
    }

    private CompletableFuture<Void> closeLedger(LedgerHandle lh) {
        CompletableFuture<Void> bkf = new CompletableFuture<>();
        lh.asyncClose((rc, ledger, ctx) -> {
                if (rc != BKException.Code.OK) {
                    bkf.completeExceptionally(BKException.create(rc));
                } else {
                    bkf.complete(null);
                }
            }, null);
        return bkf;
    }

    private CompletableFuture<Void> addToCompactedLedger(LedgerHandle lh, RawMessage m) {
        CompletableFuture<Void> bkf = new CompletableFuture<>();
        ByteBuf serialized = m.serialize();
        lh.asyncAddEntry(serialized,
                         (rc, ledger, eid, ctx) -> {
                             if (rc != BKException.Code.OK) {
                                 bkf.completeExceptionally(BKException.create(rc));
                             } else {
                                 bkf.complete(null);
                             }
                         }, null);
        serialized.release();
        return bkf;
    }

    private static String extractKey(RawMessage m) {
        ByteBuf headersAndPayload = m.getHeadersAndPayload();
        MessageMetadata msgMetadata = Commands.parseMessageMetadata(headersAndPayload);
        return msgMetadata.getPartitionKey();
    }

    private static class PhaseOneResult {
        final MessageId from;
        final MessageId to;
        final Map<String,MessageId> latestForKey;

        PhaseOneResult(MessageId from, MessageId to, Map<String,MessageId> latestForKey) {
            this.from = from;
            this.to = to;
            this.latestForKey = latestForKey;
        }
    }
}
