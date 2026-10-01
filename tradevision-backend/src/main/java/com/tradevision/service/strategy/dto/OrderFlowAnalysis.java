package com.tradevision.service.strategy.dto;

import java.util.List;

/**
 * Review finding ("Client-Side Signal Generation" / "Move this to Angular to Java"): a verified
 * port — see OrderFlowService's own javadoc for the full verification methodology and its honest
 * scope (computation logic verified, live HTTP fetching NOT verified — Binance is network-blocked
 * from this sandbox).
 *
 * Review finding ("double still exists throughout strategy calculations" -- external review,
 * twenty-sixth pass, P2): investigated directly -- same reasoning as SMCAnalysis's and
 * VolumeProfile's own updated class javadocs. Confirmed by reading OrderFlowService directly:
 * calculateBias(funding, oi, cvd) re-reads fields off the already-built FundingData/
 * OpenInterestData/CVDData records to compute the overall bias score, and buildSummary does the
 * same for the summary text -- these values are re-read for further internal computation within
 * the same verified algorithm, not written once as a final output. Also, unlike a raw price,
 * most of these fields (fundingRate, oiChange, cvd) are themselves rates/deltas/percentages
 * rather than money amounts a real order would be sized from -- genuinely closer to the
 * review's own "indicator" carve-out than to its "prices, quantities, SL, TP" concern. Left as
 * double deliberately.
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
