package com.tradevision.dto;

/**
 * One labeled training example — a signal's feature snapshot plus its actual resolved outcome.
 * This is infrastructure for eventually training a model; it is not itself a model, a
 * prediction, or a probability. It only exists for calls whose outcome has actually resolved
 * (not PENDING) — an unresolved call has no label and is useless for supervised training, so
 * it's excluded rather than filled with a guess.
 */
public record MLDatasetRow(
    String callId, String symbol, String market, String timeframe,
    String direction, int confidence, double entryPrice, double stopLoss,
    double target1, double rrRatio, double atr,
    Double rsi, Boolean macdBull, String trendEMA, String regime, String smcBias,
    Integer dataQualityScore, Integer candleCount,
    // Label — what actually happened. This is the target variable for training.
    String outcomeResult, Double pnlPct, Double pnlR, Long durationMinutes
) {}
