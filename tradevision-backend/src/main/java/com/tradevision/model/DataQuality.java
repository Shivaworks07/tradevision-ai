package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * Data quality metadata for each trade call's feature vector.
 * Used to filter low-quality rows from ML training data.
 *
 * Usage:
 *   df = df[df['quality_score'] >= 80]  # exclude noisy rows
 */
@Data @NoArgsConstructor
public class DataQuality {

    /** Data source: BINANCE, NSE, YAHOO_FINANCE, FOREX_API */
    String      dataSource;

    /** Number of indicator fields that were null/zero at capture time */
    int         missingFeatures;

    /** True if any critical indicator produced an invalid value */
    boolean     invalidIndicators;

    /** Names of any missing/invalid fields for debugging */
    List<String> missingFieldNames;

    /**
     * Overall quality score 0-100.
     * 100 = all features present and valid
     * 80+ = usable for ML training
     * < 60 = exclude from training
     */
    int qualityScore;

    /** Candle count available when signal was generated (min 50 needed) */
    int candleCount;

    /** True if enough historical data existed for all indicators */
    boolean sufficientHistory;

    /** Milliseconds taken to compute all features (performance tracking) */
    long computeTimeMs;
}
