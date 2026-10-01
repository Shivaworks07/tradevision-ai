package com.tradevision.model;

import com.tradevision.model.features.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

/**
 * All indicator/feature values captured at signal time.
 * This is the ML training input (X vector).
 *
 * Structure:
 *   TradeFeatures (root — stored flat in MongoDB for ML-friendly export)
 *     ├── TechnicalFeatures   technical
 *     ├── SMCFeatures         smc
 *     ├── VolumeProfileFeatures vp
 *     ├── OrderFlowFeatures   orderFlow
 *     ├── SentimentFeatures   sentiment
 *     └── MarketContextFeatures context
 *
 * Flat fields are kept for backward compatibility with existing queries.
 * Sub-class objects are the canonical write path going forward.
 */
@Data @NoArgsConstructor
public class TradeFeatures {

    // ── Sub-class breakdown (v20+) ────────────────────────────
    TechnicalFeatures     technical;
    SMCFeatures           smc;
    VolumeProfileFeatures vp;
    OrderFlowFeatures     orderFlow;
    SentimentFeatures     sentiment;
    MarketContextFeatures context;

    // ── Flat fields (kept for backward compat + ML CSV export) ─
    // Technical
    double  rsi;
    String  rsiZone;
    double  macdHistogram;
    boolean macdBull;
    double  adx;
    double  atrPct;
    String  trendEMA;
    String  bbSignal;
    double  stochK;
    double  stochD;
    double  williamsR;
    String  vwapSignal;
    String  obvSignal;
    double  volumeRatio;
    String  volumeSignal;

    // SMC
    String  smcBias;
    int     smcBiasStrength;
    boolean bosDetected;
    boolean chochDetected;
    boolean orderBlockNear;
    boolean fvgNear;
    String  pdZone;

    // Volume Profile
    String  vpLocation;
    double  vpPoc;
    double  vpVah;
    double  vpVal;
    double  pocDistancePct;

    // Order Flow
    String  ofBias;
    int     ofScore;
    double  fundingRate;
    String  oiSignal;
    String  cvdTrend;

    // Regime
    String  regime;
    double  regimeAdx;
    double  regimeBbWidth;

    // Sentiment
    Integer fearGreedValue;
    String  fearGreedClass;

    // MTF
    String  mtfAlignment;
    String  htf1Trend;
    String  htf2Trend;

    // Patterns
    List<String> patterns;
    List<String> bullReasons;
    List<String> bearReasons;

    // ML state
    Double  mlWinRate;
    Integer mlSampleSize;

    // Market metadata
    String  exchange;
    String  assetClass;
    String  marketSession;
    int     dayOfWeek;
    int     hourOfDay;

    // ── Versioning ────────────────────────────────────────────
    /** v1=flat only, v2=flat+sub-classes. Increment on schema changes. */
    int featureVersion = 2;

    // ── Sequence data (for LSTM/Transformer) ─────────────────
    /** Last 50 OHLCV: [[time,open,high,low,close,volume], ...] */
    List<List<Double>> rawCandles;

    // ── Rule engine decision ──────────────────────────────────
    /** Per-indicator weights that drove the score. Compare vs XGBoost later. */
    Map<String, Double> decisionWeights;

    // ── Data Quality ─────────────────────────────────────────
    DataQuality quality;

    // ── Rule Engine Score ─────────────────────────────────────
    RuleEngineScore ruleEngineScore;
}
