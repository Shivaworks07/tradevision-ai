package com.tradevision.service.strategy.dto;

import java.util.List;

/**
 * Review finding ("Client-Side Signal Generation = Trusting the Browser with Money" / "Move this
 * to Angular to Java"): a verified port — see VolumeProfileService's own javadoc for exactly how
 * "verified" is defined here and what that does and doesn't cover.
 *
 * Review finding ("double still exists throughout strategy calculations" -- external review,
 * twenty-sixth pass, P2): investigated directly -- same reasoning as SMCAnalysis's own updated
 * class javadoc. Confirmed by reading VolumeProfileService directly: buildTradingLevels sorts
 * its own already-built TradingLevel list by re-reading a.price()/b.price() from them
 * (Math.abs(a.price() - price)), and nearest() computes Math.abs(l - price) across an
 * already-built list -- these poc/vah/val/price fields are re-read for further internal
 * arithmetic within the same verified algorithm, not just written once as a final output.
 * Converting would mean rewriting that arithmetic throughout an already-verified, exact-match
 * port, with no compiler available to catch a mistake. These are volume-profile analysis
 * indicators feeding a composite bias, not direct order-sizing inputs -- left as double
 * deliberately, matching the review's own indicator carve-out.
 */
public record VolumeProfile(
    double poc, double vah, double val,
    List<Double> hvns, List<Double> lvns,
    List<VolumeBucket> buckets,
    double currentPrice,
    String priceLocation,  // ABOVE_VAH, INSIDE_VA, BELOW_VAL
    String bias,           // BULLISH, BEARISH, NEUTRAL
    NearestNode nearestHVN,
    NearestNode nearestLVN,
    String interpretation,
    List<TradingLevel> tradingLevels
) {
    public record VolumeBucket(double priceLevel, double volume, double buyVolume, double sellVolume,
                                double percent, boolean isHVN, boolean isLVN, boolean isPOC, boolean isVAH, boolean isVAL) {}
    public record NearestNode(double price, double distance, String direction) {} // direction: ABOVE, BELOW
    public record TradingLevel(double price, String label, String strength) {} // strength: STRONG, MEDIUM, WEAK
}
