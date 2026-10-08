package com.tradevision.service.strategy.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The final trade call produced by SignalCombinerService: the base signal direction/confidence
 * folded together with multi-timeframe alignment, market regime, SMC bias, order-flow bias, and
 * volume-profile context, plus a human-readable summary explaining the combined reasoning.
 *
 * entry/stopLoss/target1/target2/target3 are BigDecimal at this DTO boundary because they are
 * the actual prices/SL/TP a real order could be sized from, even though the internal
 * computation they come from (ServerSignalEngine) works in double throughout for its own
 * indicator math (EMA/RSI/MACD), which has different precision needs than an order price.
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
