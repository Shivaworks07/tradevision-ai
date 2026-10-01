package com.tradevision.service.strategy.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Review finding ("Client-Side Signal Generation = Trusting the Browser with Money" / "Move this
 * to Angular to Java"): the final combined output of SignalCombinerService — see that class's own
 * javadoc for the full verification methodology, including a correction made mid-build after
 * finding this file's FIRST version had silently simplified the real frontend's emoji,
 * punctuation, and had omitted most of the real return object's own fields entirely.
 *
 * Review finding ("double still exists throughout strategy calculations" -- external review,
 * twenty-sixth pass, P2, confirmed real by direct inspection before this fix): entry/stopLoss/
 * target1/target2/target3 are exactly the "prices, SL, TP" the review names specifically.
 * Converted to BigDecimal here at the DTO boundary -- the internal computation this value is
 * built from (ServerSignalEngine's own verified, exact-match algorithm) deliberately stays
 * double throughout, unconverted: rewriting that algorithm's own internal arithmetic to
 * BigDecimal would risk the "exact match" verification it already has, for no real gain, since
 * the algorithm's own precision needs (EMA/RSI/MACD math) are not the same class of concern as a
 * price a real order could be sized from. Confirmed by direct inspection that no caller anywhere
 * in this codebase actually reads these five fields off a CombinedSignal to feed a real
 * calculation (see SignalCombinerService's own two construction sites) -- both real callers
 * (AutonomousScannerService, NoTradeFilterService) explicitly discard them in favor of the
 * original ServerSignalEngine.Signal's own values. Converted anyway, on principle: a future
 * caller reading this DTO's own price fields should get real precision, not an assumption that
 * happens to be safe only because nothing uses them yet.
 */
public record CombinedSignal(
    String direction,  // LONG, SHORT, WAIT
    String signal,      // STRONG BUY, BUY, NEUTRAL, SELL, STRONG SELL
    double confidence,   // 30-96 after all adjustments
    BigDecimal entry, BigDecimal stopLoss, BigDecimal target1, BigDecimal target2, BigDecimal target3,
    String regime, String regimeLabel, String regimeEmoji, String regimeColor, Double regimeConfidence, List<String> regimeWarnings,
    List<MTFContext> mtfContext, String mtfAlignment,
    String smcBias, String smcSetup, Integer smcBiasStrength,
    String ofBias, Double ofScore, List<String> ofReasons,
    String vpLocation, Double vpPoc, Double vpVah, Double vpVal,
    boolean mlAdjusted,
    String summary
) {}
