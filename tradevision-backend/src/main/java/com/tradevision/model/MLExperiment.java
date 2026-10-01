package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * ML Experiment tracking — never overwrite models.
 * Each training run creates a new experiment record.
 * Phase 3 (Python FastAPI) populates this.
 */
@Data @NoArgsConstructor
@Document(collection = "ml_experiments")
public class MLExperiment {
    @Id private String id;

    @Indexed private String userId;

    // ── Experiment Identity ───────────────────────────────────
    String  experimentName;    // e.g. "xgboost_v1_btc_4h"
    String  modelType;         // XGBOOST, LIGHTGBM, LSTM, ENSEMBLE
    int     modelVersion;
    boolean isDeployed;        // is this the active model?

    // ── Training Config ───────────────────────────────────────
    int     trainingDataSize;  // number of calls used
    int     featureVersion;    // which feature schema version
    String  trainingPeriod;    // "2024-01-01 to 2024-12-31"

    // ── Dataset Snapshot Tracking ─────────────────────────────
    /**
     * UUID of the exported dataset file used for training.
     * Generated at export time. Links model ↔ exact data snapshot.
     */
    String  datasetId;

    /**
     * SHA-256 hash of the dataset CSV/JSON at export time.
     * Lets you verify dataset hasn't changed since training.
     * Example: "a3f5b2c1d4e6..."
     */
    String  datasetHash;

    /** Row count in the dataset (sanity check) */
    int     datasetRows;

    /** Feature version range in dataset (may span v1 + v2 calls) */
    String  featureVersionRange;  // e.g. "v1-v2"
    Map<String, Object> hyperparameters;

    // ── Performance Metrics ───────────────────────────────────
    double  accuracy;
    double  precision;
    double  recall;
    double  f1Score;
    double  rocAuc;
    double  logLoss;

    // ── Backtest on Test Set ──────────────────────────────────
    double  winRate;
    double  profitFactor;
    double  sharpeRatio;
    double  maxDrawdown;
    double  expectancy;

    // ── Feature Importance (top 10) ───────────────────────────
    Map<String, Double> featureImportance;

    // ── Comparison ────────────────────────────────────────────
    double  improvementOverBaseline; // % improvement over rule engine
    String  notes;

    LocalDateTime trainedAt = LocalDateTime.now();
}
