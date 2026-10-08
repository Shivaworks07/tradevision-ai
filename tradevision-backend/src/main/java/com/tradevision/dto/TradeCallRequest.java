package com.tradevision.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Request payload for recording a trade call signal, carrying trade levels, indicator values,
 * and strategy/ML metadata. The larger list/map fields (rawCandles, summary, patterns,
 * bullReasons, bearReasons, decisionWeights) carry explicit element-count bounds in addition to
 * Tomcat's raw-byte POST size ceiling, since a request under that byte limit can still contain a
 * very large number of small entries; not every field on this DTO is bounded this way, only the
 * ones that would otherwise let a caller force this backend to process an unbounded collection.
 */
@Data
public class TradeCallRequest {
    // Identity
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9/_-]{1,20}$") private String symbol;
    @Size(max = 30) private String market;
    @Size(max = 20) private String timeframe;
    @Size(max = 10) private String direction;
    @Size(max = 30) private String signal;
    /** Which StrategyPlan this request was generated for -- null for non-autonomous (e.g. frontend-submitted) signals. See TradeCallRecord.planId's own field javadoc. */
    @Size(max = 30) private String planId;
    /** The plan's own version at signal-generation time -- see TradeCallRecord.planVersion's own field javadoc. */
    private Long planVersion;
    private int    confidence;

    // Trade levels
    private double entryPrice;
    private double stopLoss;
    private double target1;
    private double target2;
    private double target3;
    private double atr;
    private double atrPct;
    private double rrRatio;
    private String risk;

    // Explicit finiteness check because @DecimalMin/@Max don't reliably reject NaN -- IEEE 754
    // NaN compares as neither greater nor less than any bound, so a NaN entryPrice would
    // silently bypass NoTradeFilterService's price-deviation check ("deviation > maxAllowed"
    // never evaluates true when deviation is NaN) rather than failing it. Scoped to the fields
    // that actually drive execution math (entry/SL/TP/ATR/R:R/confidence), not every numeric
    // field on this DTO.
    @AssertTrue(message = "Trade levels and confidence must be finite numbers (no NaN/Infinity) and confidence must be 0-100")
    public boolean isNumericallyValid() {
        double[] mustBeFinite = { entryPrice, stopLoss, target1, target2, target3, atr, atrPct, rrRatio };
        for (double v : mustBeFinite) {
            if (Double.isNaN(v) || Double.isInfinite(v)) return false;
        }
        return confidence >= 0 && confidence <= 100;
    }

    // Core indicators
    private double  rsi;
    private String  rsiZone;
    private double  macdHistogram;
    private boolean macdBull;
    private double  adx;
    private String  trendEMA;
    private String  bbSignal;
    private double  stochK;
    private double  stochD;
    private double  williamsR;
    private String  vwapSignal;
    private String  obvSignal;
    private double  volumeRatio;
    private String  volumeSignal;

    // Phase 1 features
    private String  smcBias;
    private int     smcBiasStrength;
    private boolean bosDetected;
    private boolean chochDetected;
    private boolean orderBlockNear;
    private boolean fvgNear;
    private String  pdZone;
    private String  vpLocation;
    private double  vpPoc;
    private double  vpVah;
    private double  vpVal;
    private double  pocDistancePct;
    private String  ofBias;
    private int     ofScore;
    private double  fundingRate;
    private String  oiSignal;
    private String  cvdTrend;

    // Phase 2 features
    private String  regime;
    private double  regimeAdx;
    private double  regimeBbWidth;

    // Sentiment
    private Integer fearGreedValue;
    private String  fearGreedClass;

    // MTF
    private String  mtfAlignment;
    private String  htf1Trend;
    private String  htf2Trend;

    // Patterns
    @Size(max = 50) private List<@Size(max = 100) String> patterns;
    @Size(max = 50) private List<@Size(max = 300) String> bullReasons;
    @Size(max = 50) private List<@Size(max = 300) String> bearReasons;

    // ML
    private Double  mlWinRate;
    private Integer mlSampleSize;
    @Size(max = 2000) private String  summary;

    // Market metadata
    private String  exchange;
    private String  assetClass;
    private String  marketSession;
    private int     dayOfWeek;
    private int     hourOfDay;

    // ML evolution tracking
    private int featureVersion = 2;  // v2 includes sub-classes
    @Size(max = 500) private List<@Size(min = 5, max = 6) List<Double>> rawCandles;
    @Size(max = 100) private Map<String, Double> decisionWeights;

    // Data quality (computed client-side)
    private Integer qualityScore;
    private String  dataSource;
    private Integer missingFeatures;
    private Integer candleCount;

    // Rule engine score breakdown
    private Integer         ruleScore;
    @Size(max = 50) private List<@Size(max = 300) String> ruleReasons;
    @Size(max = 100) private Map<String, Integer> ruleIndicatorScores;
    @Size(max = 100) private Map<String, Double>  ruleIndicatorWeights;
    @Size(max = 100) private Map<String, Double>  ruleAdjustments;
}
