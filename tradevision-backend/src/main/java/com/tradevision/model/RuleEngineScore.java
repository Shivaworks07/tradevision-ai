package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

/**
 * The rule engine's internal scoring breakdown at signal time.
 *
 * Stored alongside ML features so you can later compare:
 *   Rule Engine score=81 → BUY
 *   XGBoost probability=0.73 → BUY
 *   Ensemble → BUY (agree)
 *
 *   vs
 *
 *   Rule Engine score=75 → BUY
 *   XGBoost probability=0.41 → WAIT  ← disagreement = interesting case
 */
@Data @NoArgsConstructor
public class RuleEngineScore {

    /** Final composite score (0-100) that drove the signal */
    int     totalScore;

    /** Score before any adjustments */
    int     rawScore;

    /** Direction the rule engine produced: LONG, SHORT, WAIT */
    String  direction;

    /** Confidence output (0-100) */
    int     confidence;

    /**
     * Per-indicator contribution to final score.
     * Example: {"emaAlignment": 18, "smcBias": 12, "bosConfirmed": 10, "regime": 15}
     */
    Map<String, Integer> indicatorScores;

    /**
     * Per-indicator weights used (before scoring).
     * These change based on regime and market conditions.
     */
    Map<String, Double> indicatorWeights;

    /** Human-readable reasons that were active */
    List<String> bullReasons;
    List<String> bearReasons;

    /** Adjustments applied: regime multiplier, ML memory adjustment, etc. */
    Map<String, Double> adjustments;
}
