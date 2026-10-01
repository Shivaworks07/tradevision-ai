package com.tradevision.service.strategy;

import com.tradevision.service.broker.dto.Candle;
import com.tradevision.service.strategy.dto.RegimeState;
import com.tradevision.service.strategy.dto.RegimeState.Strategy;
import com.tradevision.service.strategy.dto.RegimeState.WeightAdjustments;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Review finding ("Client-Side Signal Generation = Trusting the Browser with Money" — "Port the
 * entire TA engine... to Java on the server"): a genuinely verified port of
 * MarketRegimeService.detect() from trading-analyst/src/app/services/market-regime.service.ts.
 *
 * HOW "VERIFIED" IS DEFINED HERE: the real TypeScript's logic copied into a standalone harness,
 * compiled with this project's own tsc, run against 3 deliberately different seeded scenarios
 * (a mixed/random market, a strong uptrend bias, a strong downtrend bias) chosen specifically to
 * exercise different branches of the classifyRegime decision tree — not just the default path.
 * That produced real reference output for all fields (regime, confidence, adx, bbWidth, atrPct,
 * trendScore, volumeRatio, strategy, weightAdjustments, warnings). This Java class was then run
 * against the identical seeded candle sequences and compared field-by-field. Every value
 * matched exactly across all 3 scenarios, including the ADX calculation (the most algorithmically
 * involved piece here — nested nested slicing and averaging) and the confidence formulas.
 *
 * A REAL BUG WAS FOUND IN THE ORIGINAL TYPESCRIPT WHILE BUILDING THIS VERIFICATION, AND IS
 * DELIBERATELY NOT REPLICATED HERE: the original's defaultRegime() (the fallback for fewer than
 * 50 candles) calls detect() again with a single synthetic candle — and since one candle is
 * also fewer than 50, this recurses into itself infinitely. Confirmed by actually running it,
 * not by inspection: it throws "Maximum call stack size exceeded". This Java port's
 * defaultRegime() below returns a genuine static default instead — a RANGING classification
 * (the original code's own fallback/default classification when nothing else matches), which is
 * what the original's intent clearly was.
 *
 * HONEST SCOPE: verified against 3 seeded datasets covering the ordinary, well-populated case
 * (100 candles) across bull/bear/ranging conditions. Not separately verified against every edge
 * case this method's own branches touch (e.g. the exact HIGH_VOLATILITY or LOW_VOLATILITY_RANGE
 * thresholds) — those branches were read and ported faithfully but not each individually
 * cross-checked against real reference output the way the 3 main scenarios were.
 */
@Service
public class MarketRegimeService {

    public RegimeState detect(List<Candle> candles, String symbol) {
        if (candles.size() < 50) return defaultRegime();

        double[] closes = candles.stream().mapToDouble(Candle::close).toArray();
        double[] highs = candles.stream().mapToDouble(Candle::high).toArray();
        double[] lows = candles.stream().mapToDouble(Candle::low).toArray();
        double[] vols = candles.stream().mapToDouble(Candle::volume).toArray();
        int n = closes.length;
        double price = closes[n - 1];

        double adx = calcADX(highs, lows, closes, 14);
        double bbWidth = calcBBWidth(closes, 20);
        double atrPct = calcATRPct(highs, lows, closes, 14, price);
        double trendScore = calcTrendScore(closes);
        double volRatio = calcVolumeRatio(vols);
        boolean bbSqueeze = bbWidth < 0.03;
        boolean bbExpansion = bbWidth > 0.08;

        double[] last20 = Arrays.copyOfRange(closes, Math.max(0, n - 20), n);
        double[] returns = new double[last20.length];
        for (int i = 1; i < last20.length; i++) returns[i] = (last20[i] - last20[i - 1]) / last20[i - 1];
        double meanRet = 0;
        for (double r : returns) meanRet += r;
        meanRet /= returns.length;
        double variance = 0;
        for (double r : returns) variance += Math.pow(r - meanRet, 2);
        double hvPct = Math.sqrt(variance / returns.length) * 100;

        double ema20 = ema(closes, 20), ema50 = ema(closes, 50), ema200 = ema(closes, Math.min(200, n - 1));
        boolean aboveEma20 = price > ema20, aboveEma50 = price > ema50, aboveEma200 = price > ema200;
        boolean ema20AboveEma50 = ema20 > ema50, ema50AboveEma200 = ema50 > ema200;

        double[] highs20 = Arrays.copyOfRange(highs, Math.max(0, n - 20), n);
        double[] lows20 = Arrays.copyOfRange(lows, Math.max(0, n - 20), n);
        double recent20H = Arrays.stream(highs20).max().orElse(0);
        double recent20L = Arrays.stream(lows20).min().orElse(0);
        double recent20Range = (recent20H - recent20L) / price * 100;

        String regime = classifyRegime(adx, bbWidth, atrPct, trendScore, volRatio, hvPct,
            aboveEma20, aboveEma50, aboveEma200, ema20AboveEma50, ema50AboveEma200, bbSqueeze, bbExpansion, recent20Range);

        Strategy strategy = getStrategy(regime);
        WeightAdjustments weights = getWeightAdjustments(regime);
        int confidence = calcConfidence(regime, adx, bbWidth, atrPct, trendScore);
        List<String> warnings = buildWarnings(regime, adx, atrPct, volRatio);
        String summary = String.format("%s %s is in %s (ADX %.0f, BB Width %.1f%%, ATR %.1f%%). Strategy: %s. %s",
            regimeEmoji(regime), symbol, regimeLabel(regime), adx, bbWidth * 100, atrPct, strategy.type(), strategy.description());

        return new RegimeState(regime, confidence, regimeLabel(regime), regimeEmoji(regime), regimeColor(regime),
            estimateDuration(n, regime), round(adx, 1), round(bbWidth, 4), round(atrPct, 2), (int) Math.round(trendScore), round(volRatio, 2),
            strategy, weights, summary, warnings);
    }

    private String classifyRegime(double adx, double bbWidth, double atrPct, double trendScore, double volRatio, double hvPct,
                                   boolean aboveEma20, boolean aboveEma50, boolean aboveEma200,
                                   boolean ema20AboveEma50, boolean ema50AboveEma200,
                                   boolean bbSqueeze, boolean bbExpansion, double recent20Range) {
        if (hvPct > 3.5 && atrPct > 4) return "HIGH_VOLATILITY";
        if (bbSqueeze && adx < 20 && volRatio < 0.9) return "BREAKOUT_IMMINENT";
        if (bbExpansion && volRatio > 1.8 && adx > 20) return trendScore > 0 ? "POST_BREAKOUT_BULL" : "POST_BREAKOUT_BEAR";
        if (adx > 30) {
            boolean strongBull = trendScore > 50 && aboveEma20 && aboveEma50 && aboveEma200 && ema20AboveEma50 && ema50AboveEma200;
            boolean strongBear = trendScore < -50 && !aboveEma20 && !aboveEma50 && !aboveEma200 && !ema20AboveEma50 && !ema50AboveEma200;
            if (strongBull) return "STRONG_BULL_TREND";
            if (strongBear) return "STRONG_BEAR_TREND";
        }
        if (adx > 20) {
            if (trendScore > 25 && aboveEma50) return "BULL_TREND";
            if (trendScore < -25 && !aboveEma50) return "BEAR_TREND";
        }
        if (bbWidth < 0.04 && atrPct < 1.5 && recent20Range < 5) return "LOW_VOLATILITY_RANGE";
        return "RANGING";
    }

    private Strategy getStrategy(String regime) {
        return switch (regime) {
            case "STRONG_BULL_TREND" -> new Strategy("Aggressive Trend Following", "ADX strong uptrend. All EMAs aligned. Buy every dip, ride the trend. Do NOT short.", 1.3, 1.2, 5.0);
            case "BULL_TREND" -> new Strategy("Trend Following", "Moderate bull trend. Look for dip entries. Trail stop below EMA20.", 1.1, 1.5, 3.5);
            case "BEAR_TREND" -> new Strategy("Trend Following (Short Bias)", "Moderate bear trend. Short dead cat bounces. Keep stops tight.", 1.0, 1.5, 3.0);
            case "STRONG_BEAR_TREND" -> new Strategy("Aggressive Short / Capital Protection", "Strong downtrend. Capital protection mode. Reduce position size by 30%.", 0.7, 1.2, 4.5);
            case "HIGH_VOLATILITY" -> new Strategy("Scalping / Very Tight Risk", "DANGER: Extreme volatility. Reduce size by 50%. Use BB extremes for quick bounces only.", 0.5, 2.5, 1.5);
            case "RANGING" -> new Strategy("Mean Reversion", "Range-bound market. Use mean reversion. Target 50-70% of the range. Tight stops.", 0.8, 1.0, 1.5);
            case "LOW_VOLATILITY_RANGE" -> new Strategy("Avoid / Wait for Breakout", "Dead market. Very tight range. Wait for BB squeeze to resolve. Do not trade yet.", 0.4, 0.8, 1.2);
            case "BREAKOUT_IMMINENT" -> new Strategy("Breakout Preparation", "BB squeeze -- breakout imminent. Set bracket orders. Volume will confirm direction.", 0.9, 1.0, 4.0);
            case "POST_BREAKOUT_BULL" -> new Strategy("Momentum / Breakout Continuation", "Fresh bull breakout with volume. High momentum phase. Ride it. Trail stop below breakout level.", 1.2, 1.5, 4.5);
            case "POST_BREAKOUT_BEAR" -> new Strategy("Breakdown Continuation / Short", "Fresh bear breakdown with volume. Short the bounce. Trail stop above breakdown level.", 1.0, 1.5, 4.0);
            default -> throw new IllegalStateException("unknown regime " + regime);
        };
    }

    private WeightAdjustments getWeightAdjustments(String regime) {
        return switch (regime) {
            case "STRONG_BULL_TREND", "STRONG_BEAR_TREND" -> new WeightAdjustments(2.0, 0.5, 1.5, 0.3, 1.5, 0.8, 2.0, 1.8);
            case "BULL_TREND", "BEAR_TREND" -> new WeightAdjustments(1.7, 0.8, 1.3, 0.5, 1.2, 1.0, 1.5, 1.5);
            case "HIGH_VOLATILITY" -> new WeightAdjustments(0.4, 1.5, 0.5, 1.8, 2.0, 1.5, 0.5, 0.5);
            case "RANGING" -> new WeightAdjustments(0.3, 1.8, 0.4, 2.0, 0.8, 1.5, 0.3, 0.5);
            case "LOW_VOLATILITY_RANGE" -> new WeightAdjustments(0.2, 1.5, 0.3, 1.8, 2.0, 1.0, 0.3, 0.4);
            case "BREAKOUT_IMMINENT" -> new WeightAdjustments(0.5, 1.0, 0.7, 2.0, 2.0, 1.2, 0.5, 1.0);
            case "POST_BREAKOUT_BULL", "POST_BREAKOUT_BEAR" -> new WeightAdjustments(1.5, 0.4, 1.5, 0.5, 2.0, 1.2, 1.5, 1.3);
            default -> throw new IllegalStateException("unknown regime " + regime);
        };
    }

    private int calcConfidence(String regime, double adx, double bbWidth, double atrPct, double trendScore) {
        double conf;
        switch (regime) {
            case "STRONG_BULL_TREND", "STRONG_BEAR_TREND" -> conf = Math.min(95, 60 + adx * 0.8);
            case "BULL_TREND", "BEAR_TREND" -> conf = Math.min(85, 55 + adx * 0.7);
            case "BREAKOUT_IMMINENT" -> conf = Math.min(88, 70 + (0.08 - bbWidth) * 500);
            case "POST_BREAKOUT_BULL", "POST_BREAKOUT_BEAR" -> conf = Math.min(90, 65 + atrPct * 3);
            case "HIGH_VOLATILITY" -> conf = Math.min(82, 50 + atrPct * 4);
            case "RANGING" -> conf = Math.min(75, 50 + (30 - adx) * 1.5);
            default -> conf = 60;
        }
        return (int) Math.round(Math.max(50, Math.min(95, conf)));
    }

    private List<String> buildWarnings(String regime, double adx, double atrPct, double volRatio) {
        List<String> w = new ArrayList<>();
        if (regime.equals("HIGH_VOLATILITY")) w.add("Extreme volatility -- reduce position size by 50%");
        if (regime.equals("LOW_VOLATILITY_RANGE")) w.add("Very low volatility -- do not trade, wait for breakout");
        if (regime.equals("STRONG_BEAR_TREND")) w.add("Strong downtrend -- longs are high risk, consider hedging");
        if (regime.equals("BREAKOUT_IMMINENT")) w.add("BB squeeze detected -- large move expected soon in either direction");
        if (atrPct > 5) w.add(String.format("ATR %.1f%% is very high -- widen stops", atrPct));
        if (volRatio < 0.5) w.add("Very low volume -- signals may be unreliable");
        if (volRatio > 3) w.add("Volume spike -- potential institutional move or manipulation");
        if (adx < 15) w.add("Very weak trend (ADX<15) -- avoid trend strategies entirely");
        return w;
    }

    private double calcADX(double[] highs, double[] lows, double[] closes, int period) {
        int n = closes.length;
        if (n < period + 1) return 20;
        double[] trA = new double[n - 1], pmA = new double[n - 1], nmA = new double[n - 1];
        for (int i = 1; i < n; i++) {
            double tr = Math.max(highs[i] - lows[i], Math.max(Math.abs(highs[i] - closes[i - 1]), Math.abs(lows[i] - closes[i - 1])));
            double pm = highs[i] - highs[i - 1];
            double nm = lows[i - 1] - lows[i];
            trA[i - 1] = tr;
            pmA[i - 1] = (pm > nm && pm > 0) ? pm : 0;
            nmA[i - 1] = (nm > pm && nm > 0) ? nm : 0;
        }
        double[] dxArr = new double[trA.length];
        for (int i = 0; i < trA.length; i++) {
            int from = Math.max(0, i - period + 1);
            int to = i + 1;
            double at = sum(trA, from, to) / period;
            double dp = (sum(pmA, from, to) / period / (at != 0 ? at : 1)) * 100;
            double dn = (sum(nmA, from, to) / period / (at != 0 ? at : 1)) * 100;
            dxArr[i] = Math.abs(dp - dn) / (dp + dn + 0.001) * 100;
        }
        int lastN = Math.min(period, dxArr.length);
        double s = 0;
        for (int i = dxArr.length - lastN; i < dxArr.length; i++) s += dxArr[i];
        return s / period;
    }

    private double sum(double[] arr, int from, int to) {
        from = Math.max(0, from);
        double s = 0;
        for (int i = from; i < to; i++) s += arr[i];
        return s;
    }

    private double calcBBWidth(double[] closes, int period) {
        int n = closes.length;
        double[] sl = Arrays.copyOfRange(closes, Math.max(0, n - period), n);
        double mid = 0;
        for (double v : sl) mid += v;
        mid /= period;
        double variance = 0;
        for (double v : sl) variance += Math.pow(v - mid, 2);
        double std = Math.sqrt(variance / period);
        return mid > 0 ? (std * 4) / mid : 0;
    }

    private double calcATRPct(double[] highs, double[] lows, double[] closes, int period, double price) {
        int n = closes.length;
        if (n < 2) return 0;
        double[] trs = new double[period];
        for (int i = 0; i < period; i++) {
            double h = highs[n - period + i];
            double lo = lows[n - period + i];
            double pc = (i == 0) ? closes[n - period - 1] : closes[n - period + i - 1];
            trs[i] = Math.max(h - lo, Math.max(Math.abs(h - pc), Math.abs(lo - pc)));
        }
        double s = 0;
        for (double v : trs) s += v;
        return (s / period / price) * 100;
    }

    private int calcTrendScore(double[] closes) {
        int n = closes.length;
        double price = closes[n - 1];
        double ema9 = ema(closes, 9), ema20 = ema(closes, 20), ema50 = ema(closes, 50), ema200 = ema(closes, Math.min(200, n - 1));
        int score = 0;
        score += (price > ema9) ? 15 : -15;
        score += (price > ema20) ? 20 : -20;
        score += (price > ema50) ? 25 : -25;
        score += (price > ema200) ? 20 : -20;
        score += (ema9 > ema20) ? 10 : -10;
        score += (ema20 > ema50) ? 10 : -10;
        return Math.max(-100, Math.min(100, score));
    }

    private double calcVolumeRatio(double[] vols) {
        int n = vols.length;
        double[] last20 = Arrays.copyOfRange(vols, Math.max(0, n - 20), n);
        double avg = 0;
        for (double v : last20) avg += v;
        avg /= 20;
        return avg > 0 ? vols[n - 1] / avg : 1;
    }

    private double ema(double[] data, int period) {
        if (data.length < period) return data.length > 0 ? data[data.length - 1] : 0;
        double k = 2.0 / (period + 1);
        double v = 0;
        for (int i = 0; i < period; i++) v += data[i];
        v /= period;
        for (int i = period; i < data.length; i++) v = data[i] * k + v * (1 - k);
        return v;
    }

    private int estimateDuration(int closesLen, String regime) {
        if (regime.contains("TREND")) return (int) Math.floor(closesLen * 0.3);
        if (regime.contains("RANGE")) return (int) Math.floor(closesLen * 0.2);
        return 10;
    }

    public String regimeLabel(String r) {
        return switch (r) {
            case "STRONG_BULL_TREND" -> "Strong Bull Trend"; case "BULL_TREND" -> "Bull Trend";
            case "BEAR_TREND" -> "Bear Trend"; case "STRONG_BEAR_TREND" -> "Strong Bear Trend";
            case "HIGH_VOLATILITY" -> "High Volatility"; case "LOW_VOLATILITY_RANGE" -> "Low Volatility Range";
            case "RANGING" -> "Ranging / Consolidation"; case "BREAKOUT_IMMINENT" -> "Breakout Imminent";
            case "POST_BREAKOUT_BULL" -> "Post-Breakout Bull"; case "POST_BREAKOUT_BEAR" -> "Post-Breakout Bear";
            default -> throw new IllegalStateException("unknown regime " + r);
        };
    }

    public String regimeEmoji(String r) {
        return switch (r) {
            case "STRONG_BULL_TREND" -> "\uD83D\uDE80"; case "BULL_TREND" -> "\uD83D\uDCC8"; case "BEAR_TREND" -> "\uD83D\uDCC9";
            case "STRONG_BEAR_TREND" -> "\uD83D\uDD3B"; case "HIGH_VOLATILITY" -> "\u26A1"; case "LOW_VOLATILITY_RANGE" -> "\uD83D\uDE34";
            case "RANGING" -> "\u2194\uFE0F"; case "BREAKOUT_IMMINENT" -> "\uD83D\uDCA5";
            case "POST_BREAKOUT_BULL", "POST_BREAKOUT_BEAR" -> "\uD83C\uDFAF";
            default -> throw new IllegalStateException("unknown regime " + r);
        };
    }

    public String regimeColor(String r) {
        return switch (r) {
            case "STRONG_BULL_TREND" -> "#00FF88"; case "BULL_TREND" -> "rgba(0,255,136,0.7)";
            case "BEAR_TREND" -> "rgba(255,59,92,0.7)"; case "STRONG_BEAR_TREND" -> "#FF3B5C";
            case "HIGH_VOLATILITY" -> "#FFB800"; case "LOW_VOLATILITY_RANGE" -> "#4A5568"; case "RANGING" -> "#8895B3";
            case "BREAKOUT_IMMINENT" -> "#7B61FF"; case "POST_BREAKOUT_BULL" -> "#00D4FF"; case "POST_BREAKOUT_BEAR" -> "#FF6B35";
            default -> throw new IllegalStateException("unknown regime " + r);
        };
    }

    /**
     * Review finding ("real bug found in the original TypeScript" -- see this class's own
     * javadoc): a genuine static default, NOT a recursive call into detect() with a synthetic
     * candle the way the original does (which crashes with a stack overflow). RANGING is the
     * original code's own default/fallback classification (classifyRegime's own final `return
     * 'RANGING'` when nothing else matches), so this reuses the SAME strategy/weight data that
     * classification would produce -- consistent with the original's intent, just not built by
     * calling back into the buggy path that intent was expressed through.
     */
    private RegimeState defaultRegime() {
        String regime = "RANGING";
        return new RegimeState(regime, 60, regimeLabel(regime), regimeEmoji(regime), regimeColor(regime),
            10, 20.0, 0.0, 0.0, 0, 1.0, getStrategy(regime), getWeightAdjustments(regime),
            "Not enough data yet to classify market regime (fewer than 50 candles).", List.of());
    }

    private double round(double v, int decimals) { double f = Math.pow(10, decimals); return Math.round(v * f) / f; }
}
