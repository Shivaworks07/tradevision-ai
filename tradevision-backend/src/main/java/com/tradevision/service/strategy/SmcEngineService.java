package com.tradevision.service.strategy;

import com.tradevision.service.broker.dto.Candle;
import com.tradevision.service.strategy.dto.SMCAnalysis;
import com.tradevision.service.strategy.dto.SMCAnalysis.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Smart Money Concepts (SMC) pattern detection engine: identifies swing points, order blocks,
 * fair value gaps, liquidity levels, and structure breaks (BOS/CHoCH) from raw candle data, then
 * combines them into a premium/discount zoning, a directional bias score, and a textual entry
 * setup description. This is the heaviest pattern-detection piece of the strategy stack.
 *
 * Needs at least 50 candles to produce a meaningful analysis; below that it falls back to
 * {@link #emptyAnalysis()}, a plain neutral result rather than attempting detection on
 * insufficient data.
 *
 * Note on floating-point display: percentage values formatted with String.format("%.2f", ...)
 * round the shortest round-trip decimal representation of the underlying double using half-up
 * rounding. This only affects the last displayed digit of a label text at exact rounding
 * boundaries (e.g. a gap size of 0.175%) — the underlying numeric fields themselves are
 * unaffected, so no decision logic is impacted by this formatting behavior.
 */
@Service
public class SmcEngineService {

    public SMCAnalysis analyze(List<Candle> candles, String symbol) {
        if (candles.size() < 50) return emptyAnalysis();

        List<SwingPoint> swingHighs = findSwingPoints(candles, "HIGH", 5);
        List<SwingPoint> swingLows = findSwingPoints(candles, "LOW", 5);
        List<StructureBreak> structureBreaks = detectStructureBreaks(candles, swingHighs, swingLows);
        List<OrderBlock> orderBlocks = detectOrderBlocks(candles);
        List<FairValueGap> fvgs = detectFairValueGaps(candles);
        List<LiquidityLevel> liquidityLevels = detectLiquidityLevels(candles, swingHighs, swingLows);
        PremiumDiscount premiumDiscount = calculatePremiumDiscount(candles, swingHighs, swingLows);

        BiasResult biasResult = determineBias(structureBreaks, orderBlocks);
        List<KeyLevel> keyLevels = buildKeyLevels(orderBlocks, fvgs, liquidityLevels, candles.get(candles.size() - 1).close());
        String entrySetup = findEntrySetup(candles, orderBlocks, fvgs, biasResult.bias(), premiumDiscount);
        String summary = buildSummary(biasResult.bias(), biasResult.biasStrength(), structureBreaks, orderBlocks, fvgs, premiumDiscount, entrySetup);

        List<OrderBlock> unmitigatedObs = orderBlocks.stream().filter(o -> !o.mitigated()).toList();
        List<FairValueGap> unfilledFvgs = fvgs.stream().filter(f -> !f.filled()).toList();

        return new SMCAnalysis(
            lastN(swingHighs, 5), lastN(swingLows, 5),
            lastN(unmitigatedObs, 4), lastN(unfilledFvgs, 4),
            lastN(liquidityLevels, 5), lastN(structureBreaks, 4),
            premiumDiscount, biasResult.trend(), biasResult.bias(), biasResult.biasStrength(),
            keyLevels.subList(0, Math.min(8, keyLevels.size())),
            summary, entrySetup
        );
    }

    private <T> List<T> lastN(List<T> list, int n) {
        return list.subList(Math.max(0, list.size() - n), list.size());
    }

    private List<SwingPoint> findSwingPoints(List<Candle> candles, String type, int lookback) {
        List<SwingPoint> points = new ArrayList<>();
        int n = candles.size();
        for (int i = lookback; i < n - lookback; i++) {
            if (type.equals("HIGH")) {
                boolean isSwingHigh = true;
                for (int j = i - lookback; j < i && isSwingHigh; j++) if (candles.get(j).high() > candles.get(i).high()) isSwingHigh = false;
                if (isSwingHigh) for (int j = i + 1; j <= i + lookback && isSwingHigh; j++) if (candles.get(j).high() > candles.get(i).high()) isSwingHigh = false;
                if (isSwingHigh) points.add(new SwingPoint(i, candles.get(i).high(), "HIGH", candles.get(i).time()));
            } else {
                boolean isSwingLow = true;
                for (int j = i - lookback; j < i && isSwingLow; j++) if (candles.get(j).low() < candles.get(i).low()) isSwingLow = false;
                if (isSwingLow) for (int j = i + 1; j <= i + lookback && isSwingLow; j++) if (candles.get(j).low() < candles.get(i).low()) isSwingLow = false;
                if (isSwingLow) points.add(new SwingPoint(i, candles.get(i).low(), "LOW", candles.get(i).time()));
            }
        }
        return points;
    }

    private List<StructureBreak> detectStructureBreaks(List<Candle> candles, List<SwingPoint> highs, List<SwingPoint> lows) {
        List<StructureBreak> breaks = new ArrayList<>();
        for (int i = 1; i < highs.size(); i++) {
            double prevHigh = highs.get(i - 1).price();
            double currHigh = highs.get(i).price();
            boolean isHigherHigh = currHigh > prevHigh;
            int breakIdx = -1;
            for (int idx = 0; idx < candles.size(); idx++) {
                if (idx > highs.get(i).index() && candles.get(idx).close() > currHigh) { breakIdx = idx; break; }
            }
            if (breakIdx > 0) {
                SwingPoint prevLow = null;
                for (SwingPoint l : lows) if (l.index() < highs.get(i).index()) prevLow = l;
                boolean wasUptrend = prevLow != null && highs.get(i).price() > prevLow.price();
                breaks.add(new StructureBreak(wasUptrend ? "BOS" : "CHOCH", "BULL", currHigh, candles.get(breakIdx).time(),
                    isHigherHigh ? "MAJOR" : "MINOR",
                    wasUptrend ? String.format("BOS: Bullish continuation -- broke %.2f", currHigh) : String.format("CHoCH: Bullish reversal -- broke above %.2f, structure changed", currHigh)));
            }
        }
        for (int i = 1; i < lows.size(); i++) {
            double prevLow = lows.get(i - 1).price();
            double currLow = lows.get(i).price();
            boolean isLowerLow = currLow < prevLow;
            int breakIdx = -1;
            for (int idx = 0; idx < candles.size(); idx++) {
                if (idx > lows.get(i).index() && candles.get(idx).close() < currLow) { breakIdx = idx; break; }
            }
            if (breakIdx > 0) {
                SwingPoint prevHigh = null;
                for (SwingPoint h : highs) if (h.index() < lows.get(i).index()) prevHigh = h;
                boolean wasDowntrend = prevHigh != null && lows.get(i).price() < prevHigh.price();
                breaks.add(new StructureBreak(wasDowntrend ? "BOS" : "CHOCH", "BEAR", currLow, candles.get(breakIdx).time(),
                    isLowerLow ? "MAJOR" : "MINOR",
                    wasDowntrend ? String.format("BOS: Bearish continuation -- broke %.2f", currLow) : String.format("CHoCH: Bearish reversal -- broke below %.2f, structure changed", currLow)));
            }
        }
        breaks.sort(Comparator.comparingDouble(StructureBreak::time));
        return breaks;
    }

    private List<OrderBlock> detectOrderBlocks(List<Candle> candles) {
        List<OrderBlock> obs = new ArrayList<>();
        int n = candles.size();
        for (int i = 2; i < n - 3; i++) {
            Candle c = candles.get(i);
            double range = (c.high() - c.low()) != 0 ? c.high() - c.low() : 0.0001;
            List<Candle> nextThree = candles.subList(i + 1, Math.min(i + 4, n));
            if (c.close() < c.open()) {
                boolean impulseUp = nextThree.stream().anyMatch(nn -> (nn.close() - nn.open()) / range > 0.8 && nn.close() > c.high());
                if (impulseUp) {
                    boolean mitigated = candles.subList(Math.min(i + 4, n), n).stream().anyMatch(cc -> cc.low() <= c.low());
                    obs.add(new OrderBlock(c.open(), c.close(), "BULL", c.time(), 3, mitigated,
                        String.format("Bull OB at %.2f-%.2f (%s)", c.close(), c.open(), mitigated ? "mitigated" : "unmitigated")));
                }
            }
            if (c.close() > c.open()) {
                boolean impulseDown = nextThree.stream().anyMatch(nn -> (nn.open() - nn.close()) / range > 0.8 && nn.close() < c.low());
                if (impulseDown) {
                    boolean mitigated = candles.subList(Math.min(i + 4, n), n).stream().anyMatch(cc -> cc.high() >= c.high());
                    obs.add(new OrderBlock(c.close(), c.open(), "BEAR", c.time(), 3, mitigated,
                        String.format("Bear OB at %.2f-%.2f (%s)", c.open(), c.close(), mitigated ? "mitigated" : "unmitigated")));
                }
            }
        }
        List<OrderBlock> unmitigated = obs.stream().filter(ob -> !ob.mitigated()).toList();
        return lastN(unmitigated, 6);
    }

    private List<FairValueGap> detectFairValueGaps(List<Candle> candles) {
        List<FairValueGap> fvgs = new ArrayList<>();
        int n = candles.size();
        for (int i = 1; i < n - 1; i++) {
            Candle c1 = candles.get(i - 1), c2 = candles.get(i), c3 = candles.get(i + 1);
            if (c3.low() > c1.high()) {
                double gapTop = c3.low(), gapBottom = c1.high();
                double gapSize = (gapTop - gapBottom) / gapBottom * 100;
                if (gapSize > 0.1) {
                    boolean filled = candles.subList(Math.min(i + 2, n), n).stream().anyMatch(cc -> cc.low() <= gapBottom);
                    fvgs.add(new FairValueGap(gapTop, gapBottom, "BULL", c2.time(), round(gapSize, 3), filled,
                        String.format("Bull FVG %.2f-%.2f (%.2f%% gap, %s)", gapBottom, gapTop, gapSize, filled ? "filled" : "unfilled")));
                }
            }
            if (c1.low() > c3.high()) {
                double gapTop = c1.low(), gapBottom = c3.high();
                double gapSize = (gapTop - gapBottom) / gapBottom * 100;
                if (gapSize > 0.1) {
                    boolean filled = candles.subList(Math.min(i + 2, n), n).stream().anyMatch(cc -> cc.high() >= gapTop);
                    fvgs.add(new FairValueGap(gapTop, gapBottom, "BEAR", c2.time(), round(gapSize, 3), filled,
                        String.format("Bear FVG %.2f-%.2f (%.2f%% gap, %s)", gapBottom, gapTop, gapSize, filled ? "filled" : "unfilled")));
                }
            }
        }
        List<FairValueGap> unfilled = fvgs.stream().filter(f -> !f.filled()).toList();
        return lastN(unfilled, 6);
    }

    private List<LiquidityLevel> detectLiquidityLevels(List<Candle> candles, List<SwingPoint> highs, List<SwingPoint> lows) {
        List<LiquidityLevel> levels = new ArrayList<>();
        double tolerance = 0.003;
        for (int i = 0; i < highs.size() - 1; i++) {
            for (int j = i + 1; j < highs.size(); j++) {
                double diff = Math.abs(highs.get(i).price() - highs.get(j).price()) / highs.get(i).price();
                if (diff < tolerance) {
                    double jPrice = highs.get(j).price();
                    int jIndex = highs.get(j).index();
                    boolean swept = candles.subList(jIndex, candles.size()).stream().anyMatch(c -> c.high() > jPrice * 1.001);
                    levels.add(new LiquidityLevel((highs.get(i).price() + jPrice) / 2, "EQL_HIGHS", highs.get(j).time(), swept,
                        String.format("Equal Highs ~%.2f (Buy Side Liquidity%s)", jPrice, swept ? " -- swept" : "")));
                    break;
                }
            }
        }
        for (int i = 0; i < lows.size() - 1; i++) {
            for (int j = i + 1; j < lows.size(); j++) {
                double diff = Math.abs(lows.get(i).price() - lows.get(j).price()) / lows.get(i).price();
                if (diff < tolerance) {
                    double jPrice = lows.get(j).price();
                    int jIndex = lows.get(j).index();
                    boolean swept = candles.subList(jIndex, candles.size()).stream().anyMatch(c -> c.low() < jPrice * 0.999);
                    levels.add(new LiquidityLevel((lows.get(i).price() + jPrice) / 2, "EQL_LOWS", lows.get(j).time(), swept,
                        String.format("Equal Lows ~%.2f (Sell Side Liquidity%s)", jPrice, swept ? " -- swept" : "")));
                    break;
                }
            }
        }
        List<LiquidityLevel> unswept = levels.stream().filter(l -> !l.swept()).toList();
        return lastN(unswept, 6);
    }

    private PremiumDiscount calculatePremiumDiscount(List<Candle> candles, List<SwingPoint> highs, List<SwingPoint> lows) {
        if (highs.isEmpty() || lows.isEmpty()) return null;
        double recentHigh = lastN(highs, 3).stream().mapToDouble(SwingPoint::price).max().orElse(0);
        double recentLow = lastN(lows, 3).stream().mapToDouble(SwingPoint::price).min().orElse(0);
        double range = recentHigh - recentLow;
        if (range <= 0) return null;
        double equilibrium = recentLow + range * 0.5;
        double price = candles.get(candles.size() - 1).close();
        double fibLevel = (price - recentLow) / range;
        return new PremiumDiscount(round(equilibrium, 4), round(recentHigh, 4), round(recentLow, 4),
            fibLevel > 0.55 ? "PREMIUM" : fibLevel < 0.45 ? "DISCOUNT" : "EQUILIBRIUM", round(fibLevel, 3));
    }

    private record BiasResult(String trend, String bias, int biasStrength) {}

    private BiasResult determineBias(List<StructureBreak> breaks, List<OrderBlock> obs) {
        List<StructureBreak> recentBreaks = lastN(breaks, 4);
        long bullBreaks = recentBreaks.stream().filter(b -> b.direction().equals("BULL")).count();
        long bearBreaks = recentBreaks.stream().filter(b -> b.direction().equals("BEAR")).count();
        long bullOBs = obs.stream().filter(o -> o.direction().equals("BULL") && !o.mitigated()).count();
        long bearOBs = obs.stream().filter(o -> o.direction().equals("BEAR") && !o.mitigated()).count();
        double bullScore = bullBreaks * 25 + bullOBs * 15;
        double bearScore = bearBreaks * 25 + bearOBs * 15;
        List<StructureBreak> recentChoch = lastN(recentBreaks, 2).stream().filter(b -> b.type().equals("CHOCH")).toList();
        for (StructureBreak c : recentChoch) { if (c.direction().equals("BULL")) bullScore += 20; else bearScore += 20; }
        double total = (bullScore + bearScore) != 0 ? (bullScore + bearScore) : 1;
        String bias = bullScore > bearScore * 1.3 ? "BULLISH" : bearScore > bullScore * 1.3 ? "BEARISH" : "NEUTRAL";
        int biasStrength = (int) Math.round(Math.max(bullScore, bearScore) / total * 100);
        String trend = bullScore > bearScore * 1.5 ? "BULL_TREND" : bearScore > bullScore * 1.5 ? "BEAR_TREND" : "RANGING";
        return new BiasResult(trend, bias, biasStrength);
    }

    private String findEntrySetup(List<Candle> candles, List<OrderBlock> obs, List<FairValueGap> fvgs, String bias, PremiumDiscount pd) {
        double price = candles.get(candles.size() - 1).close();
        if (bias.equals("BULLISH")) {
            OrderBlock nearBullOB = obs.stream().filter(ob -> ob.direction().equals("BULL") && !ob.mitigated()
                && price >= ob.bottom() * 0.995 && price <= ob.top() * 1.01).findFirst().orElse(null);
            boolean inDiscount = pd != null && pd.currentZone().equals("DISCOUNT");
            FairValueGap bullFVGAbove = fvgs.stream().filter(f -> f.direction().equals("BULL") && f.bottom() > price).findFirst().orElse(null);
            if (nearBullOB != null && inDiscount)
                return String.format("HIGH PROBABILITY LONG: Price at Bull OB (%.2f-%.2f) in DISCOUNT zone%s. Enter on bullish confirmation candle.",
                    nearBullOB.bottom(), nearBullOB.top(), bullFVGAbove != null ? ". FVG target: " + String.format("%.2f", bullFVGAbove.top()) : "");
            if (nearBullOB != null)
                return String.format("Potential LONG: At Bull OB (%.2f-%.2f). Wait for mitigation + bullish reaction.", nearBullOB.bottom(), nearBullOB.top());
            if (inDiscount && bullFVGAbove != null)
                return String.format("Potential LONG: In discount zone. Bull FVG above at %.2f-%.2f is a magnet target.", bullFVGAbove.bottom(), bullFVGAbove.top());
        }
        if (bias.equals("BEARISH")) {
            OrderBlock nearBearOB = obs.stream().filter(ob -> ob.direction().equals("BEAR") && !ob.mitigated()
                && price <= ob.top() * 1.005 && price >= ob.bottom() * 0.99).findFirst().orElse(null);
            boolean inPremium = pd != null && pd.currentZone().equals("PREMIUM");
            FairValueGap bearFVGBelow = fvgs.stream().filter(f -> f.direction().equals("BEAR") && f.top() < price).findFirst().orElse(null);
            if (nearBearOB != null && inPremium)
                return String.format("HIGH PROBABILITY SHORT: Price at Bear OB (%.2f-%.2f) in PREMIUM zone%s. Enter on bearish confirmation candle.",
                    nearBearOB.bottom(), nearBearOB.top(), bearFVGBelow != null ? ". FVG target: " + String.format("%.2f", bearFVGBelow.bottom()) : "");
            if (nearBearOB != null)
                return String.format("Potential SHORT: At Bear OB (%.2f-%.2f). Wait for rejection.", nearBearOB.bottom(), nearBearOB.top());
        }
        return null;
    }

    private List<KeyLevel> buildKeyLevels(List<OrderBlock> obs, List<FairValueGap> fvgs, List<LiquidityLevel> liq, double price) {
        List<KeyLevel> levels = new ArrayList<>();
        for (OrderBlock o : obs) if (!o.mitigated()) levels.add(new KeyLevel((o.top() + o.bottom()) / 2, o.direction() + " OB", o.direction().equals("BULL") ? "support" : "resistance"));
        for (FairValueGap f : fvgs) if (!f.filled()) levels.add(new KeyLevel((f.top() + f.bottom()) / 2, String.format("FVG (%.2f%%)", f.size()), "imbalance"));
        for (LiquidityLevel l : liq) if (!l.swept()) levels.add(new KeyLevel(l.price(), String.format("%s ~%.2f", l.type().equals("EQL_HIGHS") ? "BSL" : "SSL", l.price()), "liquidity"));
        levels.sort(Comparator.comparingDouble(l -> Math.abs(l.price() - price)));
        return levels;
    }

    private String buildSummary(String bias, int strength, List<StructureBreak> breaks, List<OrderBlock> obs, List<FairValueGap> fvgs, PremiumDiscount pd, String setup) {
        StructureBreak lastBreak = breaks.isEmpty() ? null : breaks.get(breaks.size() - 1);
        long unmitigatedOBs = obs.stream().filter(o -> !o.mitigated()).count();
        long unfilledFVGs = fvgs.stream().filter(f -> !f.filled()).count();
        String pdStr = pd != null ? String.format(" Price in %s zone (%.0f%% of range).", pd.currentZone(), pd.fibLevel() * 100) : "";
        String setupStr = setup != null ? " " + setup : " Wait for price to reach key OB or FVG for entry.";
        String bosStr = lastBreak != null ? String.format(" Last structure event: %s %s at %.2f.", lastBreak.type(), lastBreak.direction(), lastBreak.price()) : "";
        return String.format("SMC %s bias (%d%% strength). %d active Order Blocks, %d unfilled FVGs.%s%s%s", bias, strength, unmitigatedOBs, unfilledFVGs, pdStr, bosStr, setupStr);
    }

    private SMCAnalysis emptyAnalysis() {
        return new SMCAnalysis(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            null, "RANGING", "NEUTRAL", 0, List.of(), "Need 50+ candles for SMC analysis.", null);
    }

    private double round(double v, int decimals) { double f = Math.pow(10, decimals); return Math.round(v * f) / f; }
}
