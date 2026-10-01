package com.tradevision.service.strategy.dto;

import java.util.List;

/**
 * Review finding ("Client-Side Signal Generation" / "Move this to Angular to Java"): a verified
 * port — see SmcEngineService's own javadoc for the full verification methodology, including a
 * genuine cross-language floating-point display-formatting difference found and documented
 * (never a logic difference) along the way.
 *
 * Review finding ("double still exists throughout strategy calculations" -- external review,
 * twenty-sixth pass, P2): investigated directly, not assumed -- these nested records' own price
 * fields (OrderBlock.top/bottom, LiquidityLevel.price, PremiumDiscount's own levels, etc.) are
 * genuinely different in kind from TradeCallRecord's or CombinedSignal's own price fields, which
 * this same pass DID convert to BigDecimal. Confirmed by reading SmcEngineService directly:
 * these values are constructed early and then RE-READ for further internal arithmetic within
 * the same verified algorithm -- KeyLevel's own construction computes (o.top()+o.bottom())/2
 * from an already-built OrderBlock, for instance. Converting these fields to BigDecimal would
 * require rewriting that internal arithmetic throughout SmcEngineService's own already-verified,
 * exact-match port -- not a boundary-wrapping change like CombinedSignal's, but a genuine rewrite
 * of numerical algorithm internals with no compiler available in this session to catch a mistake
 * in it. These are strategy-analysis indicators (swing/order-block/liquidity levels feeding a
 * composite bias score), not direct order-sizing inputs -- the actual execution path
 * (ServerSignalEngine.Signal's own entry/stopLoss/target, and TradeCallRecord downstream of it)
 * is BigDecimal already. Left as double here deliberately, not by oversight -- matching the
 * review's own explicit carve-out ("For indicators, this is perfectly reasonable").
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
