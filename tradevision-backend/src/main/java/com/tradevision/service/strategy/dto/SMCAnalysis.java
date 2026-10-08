package com.tradevision.service.strategy.dto;

import java.util.List;

/**
 * Smart Money Concepts analysis: swing structure, order blocks, fair value gaps, liquidity
 * levels, structure breaks, premium/discount zoning, and a resulting directional bias. See
 * SmcEngineService for how each component is computed.
 *
 * Price fields throughout these nested records are intentionally {@code double} rather than
 * BigDecimal. They are strategy-analysis indicators feeding a composite bias score, not
 * order-sizing inputs — the actual execution path (ServerSignalEngine.Signal's entry/stopLoss/
 * target, and TradeCallRecord downstream of it) uses BigDecimal. Several of these fields are
 * also re-read for further internal arithmetic within the same algorithm (e.g. KeyLevel
 * computing (o.top()+o.bottom())/2 from an already-built OrderBlock), so keeping them as double
 * avoids mixing numeric types across a single computation.
 */
public record SMCAnalysis(
    List<SwingPoint> swingHighs, List<SwingPoint> swingLows,
    List<OrderBlock> orderBlocks, List<FairValueGap> fairValueGaps,
    List<LiquidityLevel> liquidityLevels, List<StructureBreak> structureBreaks,
    PremiumDiscount premiumDiscount,
    String trend, // BULL_TREND, BEAR_TREND, RANGING
    String bias,  // BULLISH, BEARISH, NEUTRAL
    int biasStrength, // 0-100
    List<KeyLevel> keyLevels,
    String summary,
    String entrySetup // null if no specific setup found
) {
    public record SwingPoint(int index, double price, String type, double time) {} // type: HIGH, LOW
    public record OrderBlock(double top, double bottom, String direction, double time, int strength, boolean mitigated, String description) {} // direction: BULL, BEAR
    public record FairValueGap(double top, double bottom, String direction, double time, double size, boolean filled, String description) {}
    public record LiquidityLevel(double price, String type, double time, boolean swept, String description) {} // type: EQL_HIGHS, EQL_LOWS
    public record StructureBreak(String type, String direction, double price, double time, String significance, String description) {} // type: BOS, CHOCH
    public record PremiumDiscount(double equilibrium, double premium, double discount, String currentZone, double fibLevel) {} // currentZone: PREMIUM, DISCOUNT, EQUILIBRIUM
    public record KeyLevel(double price, String label, String type) {} // type: support, resistance, imbalance, liquidity
}
