package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Review finding ("Scanner deduplication is JVM-local" -- external review, twenty-second pass,
 * P1, confirmed real by direct inspection before this was built: AutonomousScannerService's own
 * lastProcessedCandleTime is a plain ConcurrentHashMap, entirely in-memory -- a restart, or a
 * second replica (this application's own Kubernetes manifest currently runs replicas: 1,
 * genuinely reducing but not eliminating this risk, per the review's own honest framing), could
 * re-process the exact same closed candle for the exact same credential/symbol/timeframe,
 * potentially generating a duplicate trade signal): the durable claim record this fix is built
 * around. One document per (credentialId, symbol, timeframe, candle-close-time) combination this
 * application has ever actually analyzed -- a genuine MongoDB unique index on claimKey (created
 * in IndexInitializer, same explicit-index-creation convention as every other collection in this
 * codebase) is what makes this a real, cross-instance, cross-restart atomic claim, not just
 * another in-memory structure with a different name. A TTL index on scannedAt (also in
 * IndexInitializer) keeps this collection from growing without bound -- a candle from more than a
 * day ago is irrelevant for this dedup purpose.
 */
@Data @NoArgsConstructor
@Document(collection = "scanned_candles")
public class ScannedCandle {
    @Id
    private String id;
    /** credentialId + "|" + symbol + "|" + timeframe + "|" + candleCloseTimeMillis -- deliberately
     *  the SAME scope as the in-memory key this replaces (per credential+symbol+timeframe,
     *  shared across every plan scanning that same combination), not per-plan -- changing that
     *  scope itself would be a separate, different design decision from just making the existing
     *  one durable. */
    private String claimKey;
    private LocalDateTime scannedAt = LocalDateTime.now();
}
