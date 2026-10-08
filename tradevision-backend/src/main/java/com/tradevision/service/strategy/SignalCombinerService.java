package com.tradevision.service.strategy;

import com.tradevision.service.ServerSignalEngine;
import com.tradevision.service.broker.dto.Candle;
import com.tradevision.service.strategy.dto.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Combines a base technical-analysis signal with multi-timeframe (MTF) alignment, market
 * regime, Smart Money Concepts bias, order-flow bias, and volume-profile context to produce the
 * final trade call and a human-readable summary of the reasoning behind it.
 *
 * MTF alignment is computed first from up to two higher timeframes (via buildMTFContext), and
 * adjusts confidence based on how strongly those timeframes agree or disagree with the base
 * signal's direction; a strong enough conflict can flip the call to WAIT regardless of the base
 * signal's own confidence. Market regime, SMC, order flow, and volume profile are then each
 * applied as independent confidence adjustments and summary notes, since they capture different,
 * largely orthogonal aspects of market structure (volatility regime, institutional order-block
 * positioning, derivatives-market sentiment, and traded-volume distribution respectively).
 *
 * Confidence-based win-rate learning from historical trade outcomes (per-indicator weight
 * adjustment based on which indicators predicted correctly) is out of scope for this class —
 * mtfWeight is always the fixed default here rather than a learned value, and mlAdjusted is
 * always false. That learning subsystem is a separate concern from this combination logic.
 */
@Service
public class SignalCombinerService {

    private static final Map<String, String> NEXT_HIGHER_TF = Map.of(
        "1m", "15m", "5m", "1h", "15m", "4h", "30m", "4h", "1h", "4h", "4h", "1d", "1d", "1w"
    );

    public CombinedSignal combine(
        ServerSignalEngine.Signal base, String baseSummary, String tf,
        List<Candle> higherTF1, List<Candle> higherTF2,
        SMCAnalysis smc, OrderFlowAnalysis orderFlow, VolumeProfile volumeProfile, RegimeState regime
    ) {
        if ((higherTF1 == null || higherTF1.isEmpty()) && (higherTF2 == null || higherTF2.isEmpty())) {
            return unmodified(base, baseSummary);
        }

        List<MTFContext> mtfContexts = new ArrayList<>();
        String htf1Tf = NEXT_HIGHER_TF.getOrDefault(tf, "4h");
        String htf2Tf = NEXT_HIGHER_TF.getOrDefault(htf1Tf, "4h");
        if (higherTF1 != null && higherTF1.size() >= 30) mtfContexts.add(buildMTFContext(higherTF1, htf1Tf));
        if (higherTF2 != null && higherTF2.size() >= 30) mtfContexts.add(buildMTFContext(higherTF2, htf2Tf));

        if (mtfContexts.isEmpty()) return unmodified(base, baseSummary);

        String baseDir = base.direction();
        double alignScore = 0;
        StringBuilder alignDesc = new StringBuilder();

        for (MTFContext ctx : mtfContexts) {
            boolean ctxBull = ctx.score() > 15;
            boolean ctxBear = ctx.score() < -15;
            if (baseDir.equals("LONG") && ctxBull) { alignScore += 25; alignDesc.append(ctx.tf()).append(" aligned bull \u00B7 "); }
            else if (baseDir.equals("SHORT") && ctxBear) { alignScore += 25; alignDesc.append(ctx.tf()).append(" aligned bear \u00B7 "); }
            else if (baseDir.equals("LONG") && ctxBear) { alignScore -= 20; alignDesc.append(ctx.tf()).append(" AGAINST (bear) \u26A0\uFE0F \u00B7 "); }
            else if (baseDir.equals("SHORT") && ctxBull) { alignScore -= 20; alignDesc.append(ctx.tf()).append(" AGAINST (bull) \u26A0\uFE0F \u00B7 "); }
            else { alignDesc.append(ctx.tf()).append(" neutral \u00B7 "); }
        }

        double newConf = base.confidence();
        // Fixed default weight for MTF alignment's influence on confidence; a learned,
        // per-indicator weight (adjusted from historical win-rate) is out of scope here.
        double mtfWeight = 1.5;

        if (alignScore > 0) newConf = Math.min(96, base.confidence() + Math.round(alignScore * 0.4 * mtfWeight));
        else if (alignScore < 0) newConf = Math.max(30, base.confidence() + Math.round(alignScore * 0.5 * mtfWeight));

        String trimmedAlignDesc = alignDesc.length() >= 3 ? alignDesc.substring(0, alignDesc.length() - 3) : alignDesc.toString();
        String mtfAlignment = alignScore > 30 ? "\u2705 All timeframes aligned (" + trimmedAlignDesc + ")"
            : alignScore > 0 ? "\uD83D\uDFE1 Partial alignment (" + trimmedAlignDesc + ")"
            : alignScore < -10 ? "\uD83D\uDD34 MTF conflict \u2014 lower confidence (" + trimmedAlignDesc + ")"
            : "\u26AA MTF neutral (" + trimmedAlignDesc + ")";

        String newDirection = base.direction();
        String newSignal = base.signalLabel();
        if (alignScore <= -30 && newConf < 48) {
            newDirection = "WAIT";
            newSignal = "NEUTRAL";
            newConf = Math.max(35, newConf);
        }

        // Win-rate-based ML confidence adjustment is out of scope for this combination logic.
        boolean mlAdjusted = false;

        StringBuilder regimeNote = new StringBuilder();
        if (regime != null) {
            if (regime.regime().contains("STRONG_BULL") && newDirection.equals("LONG")) { newConf = Math.min(96, newConf + 8); regimeNote.append(" ").append(regime.emoji()).append(" ").append(regime.label()).append("."); }
            if (regime.regime().contains("STRONG_BEAR") && newDirection.equals("SHORT")) { newConf = Math.min(96, newConf + 8); regimeNote.append(" ").append(regime.emoji()).append(" ").append(regime.label()).append("."); }
            if (regime.regime().equals("HIGH_VOLATILITY")) { newConf = Math.max(30, newConf - 10); regimeNote.append(" \u26A1 HIGH VOLATILITY \u2014 reduce size."); }
            if (regime.regime().equals("LOW_VOLATILITY_RANGE")) { newConf = Math.max(30, newConf - 15); regimeNote.append(" \uD83D\uDE34 Low vol range \u2014 do not trade."); }
            if (regime.regime().equals("RANGING") && (newSignal.equals("STRONG BUY") || newSignal.equals("STRONG SELL"))) {
                newSignal = newDirection.equals("LONG") ? "BUY" : "SELL";
                newConf = Math.max(30, newConf - 12);
                regimeNote.append(" \u2194\uFE0F Ranging market \u2014 signal downgraded.");
            }
            if (regime.regime().equals("BREAKOUT_IMMINENT")) regimeNote.append(" \uD83D\uDCA5 BB squeeze \u2014 breakout imminent.");
            if (regime.regime().startsWith("POST_BREAKOUT")) { newConf = Math.min(96, newConf + 6); regimeNote.append(" \uD83C\uDFAF Post-breakout momentum."); }
        }

        StringBuilder smcNote = new StringBuilder();
        if (smc != null) {
            boolean smcBull = smc.bias().equals("BULLISH");
            boolean smcBear = smc.bias().equals("BEARISH");
            int smcStr = smc.biasStrength();
            if (smcBull && newDirection.equals("LONG")) { newConf = Math.min(96, newConf + Math.round(smcStr * 0.12)); smcNote.append(" SMC: ").append(smc.bias()).append(" (").append(smcStr).append("%)."); }
            if (smcBear && newDirection.equals("SHORT")) { newConf = Math.min(96, newConf + Math.round(smcStr * 0.12)); smcNote.append(" SMC: ").append(smc.bias()).append(" (").append(smcStr).append("%)."); }
            if (smcBull && newDirection.equals("SHORT")) { newConf = Math.max(30, newConf - 10); smcNote.append(" \u26A0\uFE0F SMC bullish vs SHORT."); }
            if (smcBear && newDirection.equals("LONG")) { newConf = Math.max(30, newConf - 10); smcNote.append(" \u26A0\uFE0F SMC bearish vs LONG."); }
            if (smc.entrySetup() != null && !smc.bias().equals("NEUTRAL")) smcNote.append(" ").append(smc.entrySetup());
        }

        StringBuilder ofNote = new StringBuilder();
        if (orderFlow != null) {
            double ofScore = orderFlow.score();
            if (ofScore >= 30 && newDirection.equals("LONG")) { newConf = Math.min(96, newConf + 8); ofNote.append(" OF: ").append(orderFlow.overallBias()).append(" (+").append((int) ofScore).append(")."); }
            if (ofScore <= -30 && newDirection.equals("SHORT")) { newConf = Math.min(96, newConf + 8); ofNote.append(" OF: ").append(orderFlow.overallBias()).append(" (").append((int) ofScore).append(")."); }
            if (ofScore >= 30 && newDirection.equals("SHORT")) { newConf = Math.max(30, newConf - 12); ofNote.append(" \u26A0\uFE0F Order flow bullish vs SHORT."); }
            if (ofScore <= -30 && newDirection.equals("LONG")) { newConf = Math.max(30, newConf - 12); ofNote.append(" \u26A0\uFE0F Order flow bearish vs LONG."); }
        }

        StringBuilder vpNote = new StringBuilder();
        if (volumeProfile != null) {
            if (volumeProfile.priceLocation().equals("ABOVE_VAH") && newDirection.equals("LONG")) { newConf = Math.min(96, newConf + 6); vpNote.append(" VP: Above VAH \u2014 bullish."); }
            if (volumeProfile.priceLocation().equals("BELOW_VAL") && newDirection.equals("SHORT")) { newConf = Math.min(96, newConf + 6); vpNote.append(" VP: Below VAL \u2014 bearish."); }
            if (volumeProfile.priceLocation().equals("ABOVE_VAH") && newDirection.equals("SHORT")) newConf = Math.max(30, newConf - 6);
            if (volumeProfile.priceLocation().equals("BELOW_VAL") && newDirection.equals("LONG")) newConf = Math.max(30, newConf - 6);
        }

        newConf = Math.max(30, Math.min(96, Math.round(newConf)));

        String summary = baseSummary + " MTF: " + mtfAlignment + "." + regimeNote + smcNote + ofNote + vpNote;

        return new CombinedSignal(newDirection, newSignal, newConf,
            java.math.BigDecimal.valueOf(base.entry()), java.math.BigDecimal.valueOf(base.stopLoss()),
            java.math.BigDecimal.valueOf(base.target1()), java.math.BigDecimal.valueOf(base.target2()),
            java.math.BigDecimal.valueOf(base.target3()),
            regime != null ? regime.regime() : null, regime != null ? regime.label() : null,
            regime != null ? regime.emoji() : null, regime != null ? regime.color() : null,
            regime != null ? (double) regime.confidence() : null, regime != null ? regime.warnings() : null,
            mtfContexts, mtfAlignment,
            smc != null ? smc.bias() : null, smc != null ? smc.entrySetup() : null, smc != null ? smc.biasStrength() : null,
            orderFlow != null ? orderFlow.overallBias() : null, orderFlow != null ? orderFlow.score() : null, orderFlow != null ? orderFlow.reasons() : null,
            volumeProfile != null ? volumeProfile.priceLocation() : null, volumeProfile != null ? volumeProfile.poc() : null,
            volumeProfile != null ? volumeProfile.vah() : null, volumeProfile != null ? volumeProfile.val() : null,
            mlAdjusted, summary
        );
    }

    private CombinedSignal unmodified(ServerSignalEngine.Signal base, String baseSummary) {
        return new CombinedSignal(base.direction(), base.signalLabel(), base.confidence(),
            java.math.BigDecimal.valueOf(base.entry()), java.math.BigDecimal.valueOf(base.stopLoss()),
            java.math.BigDecimal.valueOf(base.target1()), java.math.BigDecimal.valueOf(base.target2()),
            java.math.BigDecimal.valueOf(base.target3()),
            null, null, null, null, null, null, List.of(), null,
            null, null, null, null, null, null, null, null, null, null,
            false, baseSummary);
    }

    /**
     * Derives a higher-timeframe trend/momentum reading from a candle series: EMA20/50/200
     * positioning, Wilder RSI, and full MACD histogram, combined into a single alignment score
     * and coarse trend label used by the MTF alignment logic above.
     */
    private MTFContext buildMTFContext(List<Candle> candles, String tf) {
        double[] closes = candles.stream().mapToDouble(Candle::close).toArray();
        int n = closes.length;
        double price = closes[n - 1];
        double ema20 = ema(closes, 20);
        double ema50 = ema(closes, 50);
        double ema200 = ema(closes, Math.min(200, n - 1));
        double rsi = wilderRsi(closes, 14);
        double macdHist = macdFull(closes);
        boolean aboveEma50 = price > ema50;
        boolean aboveEma200 = price > ema200;
        boolean macdBull = macdHist > 0;

        double score = 0;
        if (price > ema50 && ema20 > ema50) score += 30;
        else if (price > ema50) score += 15;
        else if (price < ema50 && ema20 < ema50) score -= 30;
        else if (price < ema50) score -= 15;
        if (price > ema200) score += 20; else score -= 20;
        if (rsi < 35) score += 20; else if (rsi > 65) score -= 20;
        else if (rsi < 50) score += 8; else score -= 8;
        if (macdBull) score += 15; else score -= 15;

        String trend = score > 50 ? "STRONG_UP" : score > 20 ? "UP" : score < -50 ? "STRONG_DOWN" : score < -20 ? "DOWN" : "SIDEWAYS";
        return new MTFContext(tf, trend, rsi, macdBull, aboveEma50, aboveEma200, score);
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

    private double[] emaArray(double[] data, int period) {
        if (data.length < period) return new double[data.length];
        double k = 2.0 / (period + 1);
        List<Double> r = new ArrayList<>();
        for (int i = 0; i < period - 1; i++) r.add(0.0);
        double v = 0;
        for (int i = 0; i < period; i++) v += data[i];
        v /= period;
        r.add(v);
        for (int i = period; i < data.length; i++) { v = data[i] * k + v * (1 - k); r.add(v); }
        double[] out = new double[r.size()];
        for (int i = 0; i < r.size(); i++) out[i] = r.get(i);
        return out;
    }

    private double wilderRsi(double[] closes, int period) {
        int n = closes.length;
        if (n <= period + 1) return 50;
        double[] ch = new double[n - 1];
        for (int i = 0; i < n - 1; i++) ch[i] = closes[i + 1] - closes[i];
        double ag = 0, al = 0;
        for (int i = 0; i < period; i++) { if (ch[i] > 0) ag += ch[i]; if (ch[i] < 0) al += Math.abs(ch[i]); }
        ag /= period; al /= period;
        for (int i = period; i < ch.length; i++) {
            double d = ch[i];
            ag = (ag * (period - 1) + (d > 0 ? d : 0)) / period;
            al = (al * (period - 1) + (d < 0 ? Math.abs(d) : 0)) / period;
        }
        if (al == 0) return 100;
        return 100 - 100 / (1 + ag / al);
    }

    private double macdFull(double[] closes) {
        double[] e12 = emaArray(closes, 12);
        double[] e26 = emaArray(closes, 26);
        int len = Math.min(e12.length, e26.length);
        List<Double> maList = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            double diff = e12[i] - e26[i];
            if (diff != 0) maList.add(diff);
        }
        double ml = e12[e12.length - 1] - e26[e26.length - 1];
        double[] maArr = maList.isEmpty() ? new double[]{ml} : maList.stream().mapToDouble(Double::doubleValue).toArray();
        double sig = ema(maList.size() >= 9 ? maArr : new double[]{ml}, 9);
        return ml - sig;
    }
}
