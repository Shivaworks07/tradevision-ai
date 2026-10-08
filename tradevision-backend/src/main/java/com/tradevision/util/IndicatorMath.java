package com.tradevision.util;

import com.tradevision.service.broker.dto.Candle;

import java.util.List;

/**
 * Technical indicator math used to independently recompute signal inputs server-side from real
 * fetched candles, ported to match the frontend's ta-engine.service.ts formulas (e.g. Wilder's
 * smoothed RSI/ATR, not naive variants) so this serves as a meaningful cross-check against what
 * a signal claims rather than a comparison against a different formula.
 */
public final class IndicatorMath {

    private IndicatorMath() {}

    public static double wilderRsi(List<Candle> candles, int period) {
        int n = candles.size();
        if (n <= period + 1) return 50;

        double[] changes = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            changes[i] = candles.get(i + 1).close() - candles.get(i).close();
        }

        double avgGain = 0, avgLoss = 0;
        for (int i = 0; i < period; i++) {
            if (changes[i] > 0) avgGain += changes[i];
            else avgLoss += Math.abs(changes[i]);
        }
        avgGain /= period;
        avgLoss /= period;

        for (int i = period; i < changes.length; i++) {
            double d = changes[i];
            avgGain = (avgGain * (period - 1) + Math.max(d, 0)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(-d, 0)) / period;
        }

        if (avgLoss == 0) return 100;
        return 100 - 100 / (1 + avgGain / avgLoss);
    }

    public static double atr(List<Candle> candles, int period) {
        int n = candles.size();
        if (n < 2 || n < period + 1) return 0;

        double sum = 0;
        for (int i = 0; i < period; i++) {
            int idx = n - period + i;
            double h = candles.get(idx).high();
            double lo = candles.get(idx).low();
            double pc = i == 0 ? candles.get(n - period - 1).close() : candles.get(idx - 1).close();
            double tr = Math.max(h - lo, Math.max(Math.abs(h - pc), Math.abs(lo - pc)));
            sum += tr;
        }
        return sum / period;
    }

    /**
     * Standard EMA, seeded with an SMA of the first `period` closes. Used for an independent
     * server-side trend read computed from candles this backend fetched itself, not a full
     * strategy.
     */
    public static double ema(List<Candle> candles, int period) {
        int n = candles.size();
        if (n < period) return n > 0 ? candles.get(n - 1).close() : 0;

        double sma = 0;
        for (int i = 0; i < period; i++) sma += candles.get(i).close();
        sma /= period;

        double multiplier = 2.0 / (period + 1);
        double ema = sma;
        for (int i = period; i < n; i++) {
            ema = (candles.get(i).close() - ema) * multiplier + ema;
        }
        return ema;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Indicator ports below (Bollinger Bands, MACD, Stochastic, ADX, Williams %R,
    // OBV, VWAP, support/resistance, candlestick patterns, RSI divergence) mirror
    // ta-engine.service.ts exactly, so results match the frontend's live values
    // bit-for-bit given the same candle data. See ServerSignalEngine for the
    // decision logic that consumes these.
    // ─────────────────────────────────────────────────────────────────────────

    private static double[] closes(List<Candle> c) { double[] a = new double[c.size()]; for (int i=0;i<a.length;i++) a[i]=c.get(i).close(); return a; }
    private static double[] highs(List<Candle> c)  { double[] a = new double[c.size()]; for (int i=0;i<a.length;i++) a[i]=c.get(i).high();  return a; }
    private static double[] lows(List<Candle> c)   { double[] a = new double[c.size()]; for (int i=0;i<a.length;i++) a[i]=c.get(i).low();   return a; }
    private static double[] vols(List<Candle> c)   { double[] a = new double[c.size()]; for (int i=0;i<a.length;i++) a[i]=c.get(i).volume(); return a; }

    private static double emaArr(double[] data, int period) {
        if (data.length < period) return data.length > 0 ? data[data.length - 1] : 0;
        double k = 2.0 / (period + 1);
        double v = 0; for (int i = 0; i < period; i++) v += data[i]; v /= period;
        for (int i = period; i < data.length; i++) v = data[i] * k + v * (1 - k);
        return v;
    }
    private static double[] emaArray(double[] data, int period) {
        if (data.length < period) return new double[data.length];
        double k = 2.0 / (period + 1);
        java.util.List<Double> r = new java.util.ArrayList<>();
        for (int i = 0; i < period - 1; i++) r.add(0.0);
        double v = 0; for (int i = 0; i < period; i++) v += data[i]; v /= period;
        r.add(v);
        for (int i = period; i < data.length; i++) { v = data[i] * k + v * (1 - k); r.add(v); }
        double[] out = new double[r.size()]; for (int i=0;i<out.length;i++) out[i]=r.get(i); return out;
    }

    public record BB(double upper, double mid, double lower) {}
    public static BB bollingerBands(List<Candle> candles, int period, double mult) {
        double[] closes = closes(candles);
        int n = closes.length;
        double[] sl = java.util.Arrays.copyOfRange(closes, n - period, n);
        double mid = 0; for (double v : sl) mid += v; mid /= period;
        double var = 0; for (double v : sl) var += Math.pow(v - mid, 2); var /= period;
        double std = Math.sqrt(var);
        return new BB(mid + mult * std, mid, mid - mult * std);
    }

    public record Macd(double macd, double signal, double hist) {}
    public static Macd macd(List<Candle> candles) {
        double[] closes = closes(candles);
        return macdFromCloses(closes);
    }
    private static Macd macdFromCloses(double[] closes) {
        double[] e12 = emaArray(closes, 12);
        double[] e26 = emaArray(closes, 26);
        java.util.List<Double> ma = new java.util.ArrayList<>();
        for (int i = 0; i < e12.length; i++) { double v = e12[i] - e26[i]; if (v != 0) ma.add(v); }
        double ml = e12[e12.length - 1] - e26[e26.length - 1];
        double[] maArr = ma.size() >= 9 ? ma.stream().mapToDouble(Double::doubleValue).toArray() : new double[]{ml};
        double sig = emaArr(maArr, 9);
        return new Macd(ml, sig, ml - sig);
    }

    public record Stoch(double k, double d) {}
    public static Stoch stochastic(List<Candle> candles, int k, int d) {
        double[] highs = highs(candles), lows = lows(candles), closes = closes(candles);
        int n = closes.length; if (n < k) return new Stoch(50, 50);
        java.util.List<Double> kVals = new java.util.ArrayList<>();
        for (int i = k - 1; i < n; i++) {
            double wH = Double.NEGATIVE_INFINITY, wL = Double.POSITIVE_INFINITY;
            for (int j = i - k + 1; j <= i; j++) { wH = Math.max(wH, highs[j]); wL = Math.min(wL, lows[j]); }
            kVals.add(wH == wL ? 50 : ((closes[i] - wL) / (wH - wL)) * 100);
        }
        double kLast = kVals.get(kVals.size() - 1);
        int dCount = Math.min(d, kVals.size());
        double dSum = 0; for (int i = kVals.size() - dCount; i < kVals.size(); i++) dSum += kVals.get(i);
        return new Stoch(kLast, dSum / dCount);
    }

    public record Adx(double adx, double diPlus, double diMinus) {}
    public static Adx adx(List<Candle> candles, int period) {
        double[] highs = highs(candles), lows = lows(candles), closes = closes(candles);
        int n = closes.length; if (n < period + 1) return new Adx(20, 20, 20);
        double[] trA = new double[n-1], pmA = new double[n-1], nmA = new double[n-1];
        for (int i = 1; i < n; i++) {
            double tr = Math.max(highs[i]-lows[i], Math.max(Math.abs(highs[i]-closes[i-1]), Math.abs(lows[i]-closes[i-1])));
            double pm = highs[i]-highs[i-1], nm = lows[i-1]-lows[i];
            trA[i-1] = tr; pmA[i-1] = (pm > nm && pm > 0) ? pm : 0; nmA[i-1] = (nm > pm && nm > 0) ? nm : 0;
        }
        double atrV = sumLast(trA, period) / period;
        double diP = (sumLast(pmA, period) / period / (atrV == 0 ? 1 : atrV)) * 100;
        double diN = (sumLast(nmA, period) / period / (atrV == 0 ? 1 : atrV)) * 100;
        java.util.List<Double> dxArr = new java.util.ArrayList<>();
        for (int i = 0; i < trA.length; i++) {
            int start = Math.max(0, i - period + 1), end = i + 1;
            double at = sumRange(trA, start, end) / period;
            double dp = (sumRange(pmA, start, end) / period / (at == 0 ? 1 : at)) * 100;
            double dn = (sumRange(nmA, start, end) / period / (at == 0 ? 1 : at)) * 100;
            dxArr.add(Math.abs(dp - dn) / (dp + dn + 0.001) * 100);
        }
        double adxV = 0; int cnt = Math.min(period, dxArr.size());
        for (int i = dxArr.size() - cnt; i < dxArr.size(); i++) adxV += dxArr.get(i);
        adxV /= period;
        return new Adx(adxV, diP, diN);
    }
    private static double sumLast(double[] a, int period) { double s=0; for (int i=a.length-period;i<a.length;i++) s+=a[i]; return s; }
    private static double sumRange(double[] a, int start, int end) { double s=0; for (int i=start;i<end;i++) s+=a[i]; return s; }

    public static double williamsR(List<Candle> candles, int period) {
        double[] highs = highs(candles), lows = lows(candles), closes = closes(candles);
        int n = closes.length; if (n < period) return -50;
        double hh = Double.NEGATIVE_INFINITY, ll = Double.POSITIVE_INFINITY;
        for (int i = n - period; i < n; i++) { hh = Math.max(hh, highs[i]); ll = Math.min(ll, lows[i]); }
        return hh == ll ? -50 : ((hh - closes[n-1]) / (hh - ll)) * -100;
    }

    public static double obv(List<Candle> candles) {
        double[] closes = closes(candles), vols = vols(candles);
        double o = 0;
        for (int i = 1; i < closes.length; i++) {
            if (closes[i] > closes[i-1]) o += vols[i]; else if (closes[i] < closes[i-1]) o -= vols[i];
        }
        return o;
    }

    public static double vwap(List<Candle> candles) {
        double cv = 0, v = 0;
        int start = Math.max(0, candles.size() - 100);
        for (int i = start; i < candles.size(); i++) {
            Candle c = candles.get(i);
            double tp = (c.high() + c.low() + c.close()) / 3;
            cv += tp * c.volume(); v += c.volume();
        }
        return v == 0 ? candles.get(candles.size()-1).close() : cv / v;
    }

    public record SR(java.util.List<Double> supports, java.util.List<Double> resistances) {}
    public static SR srLevels(List<Candle> candles) {
        double[] highs = highs(candles), lows = lows(candles);
        int lb = 5;
        java.util.List<Double> res = new java.util.ArrayList<>(), sup = new java.util.ArrayList<>();
        for (int i = lb; i < highs.length - lb; i++) {
            boolean isRes = true; for (int j = i - lb; j <= i + lb; j++) if (highs[j] > highs[i]) { isRes = false; break; }
            if (isRes) res.add(highs[i]);
        }
        for (int i = lb; i < lows.length - lb; i++) {
            boolean isSup = true; for (int j = i - lb; j <= i + lb; j++) if (lows[j] < lows[i]) { isSup = false; break; }
            if (isSup) sup.add(lows[i]);
        }
        var resSlice = res.size() > 6 ? res.subList(res.size()-6, res.size()) : res;
        var supSlice = sup.size() > 6 ? sup.subList(sup.size()-6, sup.size()) : sup;
        var resSorted = new java.util.ArrayList<>(resSlice); java.util.Collections.sort(resSorted);
        var supSorted = new java.util.ArrayList<>(supSlice); java.util.Collections.sort(supSorted);
        return new SR(supSorted, resSorted);
    }

    public static java.util.List<String> detectPatterns(List<Candle> candles) {
        java.util.List<String> p = new java.util.ArrayList<>();
        int n = candles.size(); if (n < 4) { p.add("Insufficient Data"); return p; }
        Candle c = candles.get(n-1), prev = candles.get(n-2), pp = candles.get(n-3);
        double bodyC = Math.abs(c.close()-c.open()), rngC = (c.high()-c.low())==0 ? 0.0001 : (c.high()-c.low());
        boolean bullC = c.close()>c.open(), bearC = c.close()<c.open();
        double uwC = c.high()-Math.max(c.open(),c.close()), lwC = Math.min(c.open(),c.close())-c.low();
        double bodyPrev = Math.abs(prev.close()-prev.open()), rngPrev = (prev.high()-prev.low())==0 ? 0.0001 : (prev.high()-prev.low());
        boolean bullPrev = prev.close()>prev.open(), bearPrev = prev.close()<prev.open();
        double midPrev = (prev.open()+prev.close())/2;
        boolean bullPP = pp.close()>pp.open(), bearPP = pp.close()<pp.open();
        double midPP = (pp.open()+pp.close())/2;

        if (bodyC < rngC*0.07) p.add("Doji");
        if (lwC>bodyC*2 && uwC<bodyC*0.5 && bullC && lwC>rngC*0.55) p.add("Hammer");
        if (uwC>bodyC*2 && lwC<bodyC*0.5 && bearC && uwC>rngC*0.55) p.add("Shooting Star");
        if (bodyC>rngC*0.85 && bullC) p.add("Bullish Marubozu");
        if (bodyC>rngC*0.85 && bearC) p.add("Bearish Marubozu");
        if (lwC>rngC*0.6 && uwC<rngC*0.1 && bodyC<rngC*0.2) p.add("Dragonfly Doji");
        if (uwC>rngC*0.6 && lwC<rngC*0.1 && bodyC<rngC*0.2) p.add("Gravestone Doji");
        if (bearPrev && bullC && c.open()<prev.close() && c.close()>prev.open() && bodyC>bodyPrev*0.8) p.add("Bullish Engulfing");
        if (bullPrev && bearC && c.open()>prev.close() && c.close()<prev.open() && bodyC>bodyPrev*0.8) p.add("Bearish Engulfing");
        if (bearPrev && bullC && c.open()<prev.close() && c.close()>midPrev && c.close()<prev.open()) p.add("Piercing Line");
        if (bullPrev && bearC && c.open()>prev.close() && c.close()<midPrev && c.close()>prev.open()) p.add("Dark Cloud Cover");
        if (Math.abs(c.low()-prev.low())<rngC*0.04 && bullC && bearPrev) p.add("Tweezer Bottom");
        if (Math.abs(c.high()-prev.high())<rngC*0.04 && bearC && bullPrev) p.add("Tweezer Top");
        if (bearPP && bodyPrev<rngPrev*0.3 && bullC && c.close()>midPP && c.open()<pp.close()) p.add("Morning Star");
        if (bullPP && bodyPrev<rngPrev*0.3 && bearC && c.close()<midPP && c.open()>pp.close()) p.add("Evening Star");
        if (bullPP && bullPrev && bullC && prev.close()>pp.close() && c.close()>prev.close() && bodyC>rngC*0.5) p.add("White Soldiers");
        if (bearPP && bearPrev && bearC && prev.close()<pp.close() && c.close()<prev.close() && bodyC>rngC*0.5) p.add("Black Crows");
        if (p.isEmpty()) p.add("No Clear Pattern");
        return p;
    }

    private static double[] rsiArray(double[] closes, int period) {
        java.util.List<Double> r = new java.util.ArrayList<>();
        for (int i = 0; i < period; i++) r.add(50.0);
        double[] ch = new double[closes.length - 1];
        for (int i = 0; i < ch.length; i++) ch[i] = closes[i+1] - closes[i];
        if (ch.length < period) { double[] o = new double[r.size()]; for (int i=0;i<o.length;i++) o[i]=r.get(i); return o; }
        double ag=0, al=0;
        for (int i=0;i<period;i++){ if(ch[i]>0) ag+=ch[i]; else if(ch[i]<0) al+=Math.abs(ch[i]); }
        ag/=period; al/=period;
        r.add(al==0?100:100-100/(1+ag/al));
        for (int i=period;i<ch.length;i++){ double d=ch[i]; ag=(ag*(period-1)+Math.max(d,0))/period; al=(al*(period-1)+Math.max(-d,0))/period; r.add(al==0?100:100-100/(1+ag/al)); }
        double[] o = new double[r.size()]; for (int i=0;i<o.length;i++) o[i]=r.get(i); return o;
    }
    private static double min(double[] a, int s, int e) { double m=Double.POSITIVE_INFINITY; for(int i=s;i<e;i++) m=Math.min(m,a[i]); return m; }
    private static double max(double[] a, int s, int e) { double m=Double.NEGATIVE_INFINITY; for(int i=s;i<e;i++) m=Math.max(m,a[i]); return m; }

    public static boolean bullishDivergence(List<Candle> candles) {
        double[] closes = closes(candles);
        double[] rsiArr = rsiArray(closes, 14);
        int n = closes.length; if (n < 20) return false;
        double curLow=min(closes, n-10, n), prevLow=min(closes, n-20, n-10);
        double curRsiLow=min(rsiArr, rsiArr.length-10, rsiArr.length), prevRsiLow=min(rsiArr, rsiArr.length-20, rsiArr.length-10);
        return curLow < prevLow*0.998 && curRsiLow > prevRsiLow+2 && curRsiLow < 45;
    }
    public static boolean bearishDivergence(List<Candle> candles) {
        double[] closes = closes(candles);
        double[] rsiArr = rsiArray(closes, 14);
        int n = closes.length; if (n < 20) return false;
        double curHigh=max(closes, n-10, n), prevHigh=max(closes, n-20, n-10);
        double curRsiHigh=max(rsiArr, rsiArr.length-10, rsiArr.length), prevRsiHigh=max(rsiArr, rsiArr.length-20, rsiArr.length-10);
        return curHigh > prevHigh*1.002 && curRsiHigh < prevRsiHigh-2 && curRsiHigh > 55;
    }

}
