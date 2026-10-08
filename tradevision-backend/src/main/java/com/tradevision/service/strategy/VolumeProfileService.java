package com.tradevision.service.strategy;

import com.tradevision.service.broker.dto.Candle;
import com.tradevision.service.strategy.dto.VolumeProfile;
import com.tradevision.service.strategy.dto.VolumeProfile.NearestNode;
import com.tradevision.service.strategy.dto.VolumeProfile.TradingLevel;
import com.tradevision.service.strategy.dto.VolumeProfile.VolumeBucket;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a volume profile from a candle series: distributes each candle's volume across price
 * buckets proportionally to how much of the candle's range falls in each bucket, then derives
 * the point of control (POC), value area high/low (VAH/VAL, the range containing 70% of volume
 * centered on the POC), and high/low volume nodes (local maxima/minima in the bucket volumes)
 * used to identify likely support/resistance and thin, fast-moving price areas.
 *
 * Needs at least 20 candles and a non-degenerate price range and total volume to produce a real
 * profile; otherwise falls back to {@link #emptyProfile}.
 */
@Service
public class VolumeProfileService {

    public VolumeProfile analyze(List<Candle> candles, int numBuckets) {
        if (candles.size() < 20) return emptyProfile(candles);

        double price = candles.get(candles.size() - 1).close();
        double high = candles.stream().mapToDouble(Candle::high).max().orElse(0);
        double low = candles.stream().mapToDouble(Candle::low).min().orElse(0);
        double range = high - low;
        if (range <= 0) return emptyProfile(candles);

        double bucketSize = range / numBuckets;
        double[] priceLevels = new double[numBuckets];
        double[] volume = new double[numBuckets];
        double[] buyVolume = new double[numBuckets];
        double[] sellVolume = new double[numBuckets];
        for (int i = 0; i < numBuckets; i++) priceLevels[i] = low + i * bucketSize + bucketSize / 2;

        double totalVolume = 0;
        for (Candle c : candles) {
            double cRange = (c.high() - c.low()) != 0 ? c.high() - c.low() : bucketSize;
            boolean isBull = c.close() >= c.open();
            for (int i = 0; i < numBuckets; i++) {
                double bucketLow = low + i * bucketSize;
                double bucketHigh = bucketLow + bucketSize;
                double overlap = Math.min(c.high(), bucketHigh) - Math.max(c.low(), bucketLow);
                if (overlap > 0) {
                    double volShare = (overlap / cRange) * c.volume();
                    volume[i] += volShare;
                    if (isBull) buyVolume[i] += volShare; else sellVolume[i] += volShare;
                    totalVolume += volShare;
                }
            }
        }
        if (totalVolume == 0) return emptyProfile(candles);

        double[] percent = new double[numBuckets];
        for (int i = 0; i < numBuckets; i++) percent[i] = round2((volume[i] / totalVolume) * 100.0);

        int pocIdx = 0;
        for (int i = 1; i < numBuckets; i++) if (volume[i] > volume[pocIdx]) pocIdx = i;

        // calculateValueArea — 70% of total volume centered on the POC bucket.
        double target = totalVolume * 0.70;
        double included = volume[pocIdx];
        int upper = pocIdx, lower = pocIdx;
        while (included < target && (upper < numBuckets - 1 || lower > 0)) {
            double addUp = upper < numBuckets - 1 ? volume[upper + 1] : 0;
            double addDown = lower > 0 ? volume[lower - 1] : 0;
            if (addUp >= addDown && upper < numBuckets - 1) { upper++; included += volume[upper]; }
            else if (lower > 0) { lower--; included += volume[lower]; }
            else break;
        }
        double vah = priceLevels[upper];
        double val = priceLevels[lower];

        boolean[] isVAH = new boolean[numBuckets], isVAL = new boolean[numBuckets];
        for (int i = 0; i < numBuckets; i++) {
            if (Math.abs(priceLevels[i] - vah) < bucketSize) isVAH[i] = true;
            if (Math.abs(priceLevels[i] - val) < bucketSize) isVAL[i] = true;
        }

        double avgVol = totalVolume / numBuckets;
        boolean[] isHVN = new boolean[numBuckets], isLVN = new boolean[numBuckets];
        List<Double> hvns = new ArrayList<>(), lvns = new ArrayList<>();
        for (int i = 1; i < numBuckets - 1; i++) {
            boolean isLocalMax = volume[i] > volume[i - 1] && volume[i] > volume[i + 1] && volume[i] > avgVol * 1.5;
            boolean isLocalMin = volume[i] < volume[i - 1] && volume[i] < volume[i + 1] && volume[i] < avgVol * 0.4;
            if (isLocalMax) { isHVN[i] = true; hvns.add(priceLevels[i]); }
            if (isLocalMin) { isLVN[i] = true; lvns.add(priceLevels[i]); }
        }

        String priceLocation = price > vah ? "ABOVE_VAH" : price < val ? "BELOW_VAL" : "INSIDE_VA";

        NearestNode nearestHVN = nearest(hvns, price);
        NearestNode nearestLVN = nearest(lvns, price);

        String bias = (price > priceLevels[pocIdx] && priceLocation.equals("ABOVE_VAH")) ? "BULLISH"
            : (price < priceLevels[pocIdx] && priceLocation.equals("BELOW_VAL")) ? "BEARISH" : "NEUTRAL";

        List<TradingLevel> tradingLevels = buildTradingLevels(priceLevels[pocIdx], vah, val, hvns, price);
        String interpretation = buildInterpretation(price, priceLevels[pocIdx], vah, val, priceLocation, nearestHVN, nearestLVN);

        List<VolumeBucket> buckets = new ArrayList<>();
        for (int i = 0; i < Math.min(numBuckets, 50); i++) {
            buckets.add(new VolumeBucket(priceLevels[i], volume[i], buyVolume[i], sellVolume[i], percent[i],
                isHVN[i], isLVN[i], i == pocIdx, isVAH[i], isVAL[i]));
        }

        return new VolumeProfile(
            round4(priceLevels[pocIdx]), round4(vah), round4(val),
            hvns.stream().limit(5).map(VolumeProfileService::round4).toList(),
            lvns.stream().limit(5).map(VolumeProfileService::round4).toList(),
            buckets, round4(price), priceLocation, bias, nearestHVN, nearestLVN, interpretation, tradingLevels
        );
    }

    private NearestNode nearest(List<Double> levels, double price) {
        if (levels.isEmpty()) return null;
        Double best = null;
        double bestDist = Double.MAX_VALUE;
        for (double l : levels) {
            double dist = Math.abs(l - price) / price * 100;
            if (dist < bestDist) { bestDist = dist; best = l; }
        }
        return new NearestNode(best, bestDist, best >= price ? "ABOVE" : "BELOW");
    }

    private List<TradingLevel> buildTradingLevels(double poc, double vah, double val, List<Double> hvns, double price) {
        List<TradingLevel> levels = new ArrayList<>(List.of(
            new TradingLevel(poc, "POC", "STRONG"),
            new TradingLevel(vah, "VAH", "STRONG"),
            new TradingLevel(val, "VAL", "STRONG")
        ));
        for (double h : hvns) {
            double dist = Math.abs(h - price) / price * 100;
            if (dist < 5) levels.add(new TradingLevel(h, "HVN", "MEDIUM"));
        }
        levels.sort((a, b) -> Double.compare(Math.abs(a.price() - price), Math.abs(b.price() - price)));
        return levels;
    }

    private String buildInterpretation(double price, double poc, double vah, double val, String location, NearestNode hvn, NearestNode lvn) {
        List<String> parts = new ArrayList<>();
        if (location.equals("ABOVE_VAH"))
            parts.add(String.format("Price ABOVE value area (%.2f). Strong bullish -- institutions accepted higher prices. VAH becomes support.", vah));
        else if (location.equals("BELOW_VAL"))
            parts.add(String.format("Price BELOW value area (%.2f). Bearish -- institutions rejected lower prices. VAL becomes resistance.", val));
        else
            parts.add(String.format("Price INSIDE value area (%.2f-%.2f). Balanced market -- range-bound between VAL and VAH.", val, vah));
        parts.add(String.format("POC at %.2f -- highest traded price, acts as magnet.", poc));
        if (hvn != null) parts.add(String.format("Nearest HVN %s at %.2f (%.1f%% away).", hvn.direction().equals("ABOVE") ? "resistance" : "support", hvn.price(), hvn.distance()));
        if (lvn != null) parts.add(String.format("Nearest LVN at %.2f -- thin area, price moves quickly through here.", lvn.price()));
        return String.join(" ", parts);
    }

    private VolumeProfile emptyProfile(List<Candle> candles) {
        double p = candles.isEmpty() ? 0 : candles.get(candles.size() - 1).close();
        return new VolumeProfile(p, p, p, List.of(), List.of(), List.of(), p, "INSIDE_VA", "NEUTRAL",
            null, null, "Need 20+ candles for volume profile.", List.of());
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
