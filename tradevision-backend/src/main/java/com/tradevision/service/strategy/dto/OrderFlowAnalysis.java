package com.tradevision.service.strategy.dto;

import java.util.List;

/**
 * Order-flow analysis derived from funding rate, open interest, and cumulative volume delta
 * (CVD), combined into an overall directional bias and score. See OrderFlowService for how
 * each component is fetched and computed.
 *
 * Fields here are intentionally {@code double} rather than BigDecimal: funding rate, OI change
 * and CVD are rates/deltas/percentages rather than money amounts an order would be sized from,
 * and calculateBias/buildSummary in OrderFlowService re-read them for further internal
 * computation within the same bias calculation rather than treating them as a final output.
 */
public record OrderFlowAnalysis(
    FundingData funding, OpenInterestData openInterest, CVDData cvd,
    String overallBias, // STRONG_BULL, BULL, NEUTRAL, BEAR, STRONG_BEAR
    double score,        // -100 to +100
    List<String> reasons,
    String summary,
    boolean isLoaded
) {
    public record FundingData(String symbol, double fundingRate, double nextFunding, String bias, String interpretation) {}
    public record OpenInterestData(String symbol, double oi, double oiChange1h, double oiChange24h, double priceChange, String signal, String interpretation) {}
    public record CVDData(double cvd, String cvdTrend, double buyVol, double sellVol, String interpretation) {}
}
