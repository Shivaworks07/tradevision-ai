package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The backend-side persistence for per-symbol adaptive weight learning from win/loss trading
 * history — the server-side counterpart to the frontend's own localStorage-based ML memory, so
 * ServerSignalEngine's own signal scoring can adapt per symbol the same way the frontend does.
 *
 * Deliberately narrower than the frontend's own MLMemory: the frontend tracks 12 weight fields,
 * but its own learning loop only ever actually adjusts 4 of them (rsi, macd, patterns, volume)
 * -- the other 8 sit at a fixed default there too. This mirrors exactly what the frontend's
 * learning loop actually does, not every field it merely declares.
 */
@Data
@NoArgsConstructor
@Document(collection = "ml_weights")
public class MLWeights {
    @Id
    /** Set to "{market}:{symbol}" (e.g. "CRYPTO:BTCUSDT") -- matches the frontend's own mlStore
     *  key shape, and doubles as this document's natural primary key, so no separate
     *  unique-indexed "key" field is needed alongside it. */
    private String id;

    private String symbol;
    private int totalCalls = 0;
    private int wins = 0;
    private int losses = 0;
    private double winRate = 50.0;

    // The 4 weights the frontend's own learning loop actually adjusts. Same defaults as
    // the frontend's own defaultMLMemory (1.0 for all four).
    private double rsiWeight = 1.0;
    private double macdWeight = 1.0;
    private double patternsWeight = 1.0;
    private double volumeWeight = 1.0;

    private java.time.LocalDateTime lastUpdated;
}
