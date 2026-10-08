package com.tradevision.service.strategy.dto;

import java.util.List;

/**
 * Classification of the current market regime (trending, ranging, volatile, etc.) along with
 * the confidence, supporting indicator readings, and the strategy/weight adjustments that
 * should apply while this regime holds. See MarketRegimeService for how each field is derived.
 */
public record RegimeState(
    String regime, // one of the 10 RegimeType values — see MarketRegimeService's own REGIME_TYPES
    int confidence, // 0-100
    String label, String emoji, String color,
    int duration, // approximate candles in this regime
    double adx, double bbWidth, double atrPct, int trendScore, double volumeRatio,
    Strategy strategy, WeightAdjustments weightAdjustments,
    String summary, List<String> warnings
) {
    public record Strategy(String type, String description, double riskMultiplier, double slMultiplier, double tpMultiplier) {}
    public record WeightAdjustments(double trend, double rsi, double macd, double bb, double volume, double patterns, double adx, double mtf) {}
}
