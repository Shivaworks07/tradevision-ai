package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A durable claim record that a specific closed candle has already been analyzed by the
 * scanner, so a restart or a second application replica doesn't re-process the exact same
 * candle for the exact same credential/symbol/timeframe and potentially generate a duplicate
 * trade signal. One document per (credentialId, symbol, timeframe, candle-close-time)
 * combination ever analyzed, with a MongoDB unique index on claimKey (created in
 * IndexInitializer) making this a real, cross-instance, cross-restart atomic claim. A TTL
 * index on scannedAt keeps this collection from growing without bound, since a candle from
 * more than a day ago is irrelevant for dedup purposes.
 */
@Data @NoArgsConstructor
@Document(collection = "scanned_candles")
public class ScannedCandle {
    @Id
    private String id;
    /** credentialId + "|" + symbol + "|" + timeframe + "|" + candleCloseTimeMillis -- scoped per
     *  credential+symbol+timeframe, shared across every plan scanning that same combination,
     *  not per-plan. */
    private String claimKey;
    private LocalDateTime scannedAt = LocalDateTime.now();
}
