package com.tradevision.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Review finding ("TradeCall request has almost no validation"): the fields flagged as
 * "particularly concerning" (rawCandles, summary, patterns, bullReasons, bearReasons,
 * decisionWeights) are now bounded — an attacker sending a request with millions of list/map
 * entries gets a 400 instead of this backend trying to process and store all of it. Tomcat's
 * default max POST size (2MB) already provided a raw-byte ceiling before this; these add a
 * ceiling on element COUNT, since a 2MB body can still contain a very large number of small
 * entries. Not every one of the 100+ fields on this DTO has bounds — that would be its own
 * large diff — but every field the review specifically called out as dangerous does.
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

    // Review finding (P1 #10 — "TradeCallRequest numeric validation is still weak"): confirmed a
    // real, consequential gap, not just a hygiene concern — NaN comparisons in Java are always
    // false (NaN > x, NaN < x both evaluate false), meaning a NaN entryPrice would silently
    // bypass NoTradeFilterService's price-deviation safety check entirely rather than failing
    // it, since "deviation > maxAllowed" never evaluates true when deviation is NaN. @DecimalMin/
    // @Max don't reliably catch this (IEEE 754 NaN doesn't compare as greater or less than any
    // bound), so this needs an explicit finite check. Scoped to the fields that actually drive
    // execution math (entry/SL/TP/ATR/R:R/confidence) — not all 100+ fields on this DTO, which
    // would be its own much larger diff; these are the ones a bad value can actually bypass a
    // safety gate through.
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
