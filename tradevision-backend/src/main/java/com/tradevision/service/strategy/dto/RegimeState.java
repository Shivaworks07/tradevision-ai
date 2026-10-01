package com.tradevision.service.strategy.dto;

import java.util.List;

/**
 * Review finding ("Client-Side Signal Generation" / "Move this to Angular to Java"): a verified
 * port — see MarketRegimeService's own javadoc for the full verification methodology, including
 * a real bug found in the original TypeScript along the way (infinite recursion on the
 * insufficient-data fallback path).
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
