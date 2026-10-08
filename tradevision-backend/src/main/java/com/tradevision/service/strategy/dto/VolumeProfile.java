package com.tradevision.service.strategy.dto;

import java.util.List;

/**
 * Volume profile analysis: point of control (POC), value area high/low (VAH/VAL), high/low
 * volume nodes, and the per-bucket volume distribution, together with where current price sits
 * relative to these levels and the resulting bias. See VolumeProfileService for how the profile
 * is built.
 *
 * Price fields here are intentionally {@code double} rather than BigDecimal: they are
 * volume-profile analysis indicators feeding a composite bias, not direct order-sizing inputs,
 * and are re-read for further internal arithmetic within the same algorithm (buildTradingLevels
 * sorting by distance from price, nearest() computing Math.abs(l - price), etc.) rather than
 * written once as a final output.
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
