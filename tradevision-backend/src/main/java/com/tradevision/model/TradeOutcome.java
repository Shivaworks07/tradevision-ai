package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * The outcome of a trade call.
 * Set when result is marked or auto-detected.
 */
@Data @NoArgsConstructor
public class TradeOutcome {
    String        result;       // PENDING, HIT_T1, HIT_T2, HIT_T3, HIT_SL, EXPIRED
    Double        exitPrice;
    Double        pnlPct;       // % gain/loss
    Double        pnlR;         // gain/loss in R multiples
    Integer       holdBars;       // candles held
    Long          durationMinutes; // wall-clock duration (calledAt → resolvedAt)
    String        durationLabel;   // "2h 15m", "3 days" etc.
    LocalDateTime resolvedAt;
}
