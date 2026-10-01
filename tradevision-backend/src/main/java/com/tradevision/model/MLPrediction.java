package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * ML model prediction slot.
 * Populated by Python FastAPI (Phase 3).
 * Rule engine always runs first; ML fills this later.
 */
@Data @NoArgsConstructor
public class MLPrediction {
    /** Model identifier: "xgboost_v1", "lgbm_v2", "ensemble_v1" */
    String  modelName;
    /** Model schema version to handle breaking changes */
    int     modelVersion;

    // Class probabilities (sum to 1.0)
    double  buyProbability;
    double  sellProbability;
    double  waitProbability;

    // Model's recommended action
    String  mlSignal;          // BUY, SELL, WAIT
    double  mlConfidence;      // max(buy, sell, wait)

    // Feature importance for this specific prediction (SHAP values)
    java.util.Map<String, Double> shapValues;

    // Metadata
    LocalDateTime predictedAt;
    boolean       isCalibrated; // true if model confidence has been calibrated
}
