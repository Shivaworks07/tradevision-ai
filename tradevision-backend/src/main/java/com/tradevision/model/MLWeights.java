package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Review finding ("Strategy engine is not the complete strategy actually represented by the
 * frontend" -- P1, full context in MLWeightService's own javadoc): the backend-side persistence
 * for the one honestly-disclosed gap ServerSignalEngine's own header comment named -- "ML weight
 * adjustment (the frontend's per-symbol adaptive weight learning from win/loss history, stored
 * in browser localStorage) is NOT ported... Porting the adaptive learning loop is separate, real
 * work, not attempted here." This is that separate work, now attempted.
 *
 * Deliberately narrower than the frontend's own MLMemory: the frontend tracks 12 weight fields
 * in its own defaultMLMemory, but its own updateMLFromOutcome only ever actually ADJUSTS 4 of
 * them (rsi, macd, patterns, volume) -- the other 8 (trend, bb, stoch, adx, williamsR, obv,
 * divergence, mtfAlignment) sit at their fixed default forever in the frontend too. A faithful
 * port matches what the frontend's own learning loop actually does, not what it merely declares
 * a field for -- inventing adjustment logic for the other 8 would not be porting, it would be
 * a different, new algorithm.
 */
@Data
@NoArgsConstructor
@Document(collection = "ml_weights")
public class MLWeights {
    @Id
    /** Set to "{market}:{symbol}" (e.g. "CRYPTO:BTCUSDT") -- matches the frontend's own mlStore
     *  key shape exactly, and doubles as this document's own natural primary key, so no separate
     *  unique-indexed "key" field is needed alongside it. */
    private String id;

    private String symbol;
    private int totalCalls = 0;
    private int wins = 0;
    private int losses = 0;
    private double winRate = 50.0;

    // The 4 weights the frontend's own updateMLFromOutcome actually adjusts. Same defaults as
    // the frontend's own defaultMLMemory (1.0 for all four).
    private double rsiWeight = 1.0;
    private double macdWeight = 1.0;
    private double patternsWeight = 1.0;
    private double volumeWeight = 1.0;

    private java.time.LocalDateTime lastUpdated;
}
