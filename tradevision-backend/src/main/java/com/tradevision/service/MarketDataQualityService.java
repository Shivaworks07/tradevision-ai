package com.tradevision.service;

import com.tradevision.model.BrokerMode;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.Candle;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Gatekeeper that decides whether market data for a symbol is trustworthy enough to trade on,
 * checking candle freshness, missing/duplicate candles, timestamp ordering, spread, price
 * jumps, volume anomalies, exchange clock drift, and order-book depth imbalance. Strategies
 * call isMarketSafeToTrade(symbol) before acting on a signal; a NO means skip the trade.
 *
 * SCOPE:
 * - REST/WS disagreement is not checked — this codebase has no market-data WebSocket feed to
 *   compare against (only a user-data stream for fills and account events), so there is no
 *   second source available for this check.
 * - Spread checking is implemented via BrokerAdapter.getSpread.
 * - The "price jump" and "volume anomaly" checks use simple fixed percentage/multiple
 *   thresholds rather than a statistical or learned model, so a wide but genuine market move
 *   can still trip them — these are heuristics, not a validated bad-data classifier.
 */
@Service
public class MarketDataQualityService {

    private static final double MAX_SINGLE_CANDLE_JUMP_PERCENT = 20.0;
    private static final double VOLUME_ANOMALY_MULTIPLE = 10.0;
    private static final double MAX_ACCEPTABLE_SPREAD_PERCENT = 1.0;
    // 5 seconds is a deliberately generous threshold: Binance's own recvWindow default is
    // 5000ms, and signed requests already correct for this same clock offset, so drift under
    // this threshold is already handled correctly elsewhere. This check exists to catch a
    // genuinely abnormal drift (a real system clock problem), not normal, already-compensated
    // network/clock variance.
    private static final long MAX_ACCEPTABLE_CLOCK_DRIFT_MS = 5000;
    // A simple fixed-multiple heuristic for a thin or one-sided order book at the moment of
    // the check, not a statistical model.
    private static final double MAX_ACCEPTABLE_DEPTH_IMBALANCE_MULTIPLE = 20.0;
    private static final int DEPTH_LEVELS_TO_CHECK = 20;
    private static final int MIN_CANDLES_REQUIRED = 20;

    public record QualityResult(boolean safe, List<String> issues) {
        public static QualityResult ok() {
            return new QualityResult(true, List.of());
        }
    }

    /**
     * Runs every data-quality check this class supports and returns a single safe/unsafe
     * verdict together with the specific reasons, rather than a bare boolean, so a caller (or
     * a human reviewing why a signal was skipped) can see exactly what failed.
     */
    public QualityResult isMarketSafeToTrade(String symbol, List<Candle> candles, long expectedIntervalSeconds,
                                              BrokerAdapter adapter, BrokerMode mode) {
        List<String> issues = new ArrayList<>();

        if (candles == null || candles.size() < MIN_CANDLES_REQUIRED) {
            issues.add("Insufficient candle history: " + (candles == null ? 0 : candles.size()) + " candles, need at least " + MIN_CANDLES_REQUIRED);
            return new QualityResult(false, issues); // nothing else below is meaningful without enough real data
        }

        checkTimestampOrderAndDuplicates(candles, issues);
        checkMissingCandles(candles, expectedIntervalSeconds, issues);
        checkFreshness(candles, expectedIntervalSeconds, issues);
        checkPriceJumps(candles, issues);
        checkVolumeAnomaly(candles, issues);
        checkSpread(symbol, adapter, mode, issues);
        checkClockDrift(adapter, issues);
        checkDepthImbalance(symbol, adapter, mode, issues);

        return new QualityResult(issues.isEmpty(), issues);
    }

    private void checkTimestampOrderAndDuplicates(List<Candle> candles, List<String> issues) {
        for (int i = 1; i < candles.size(); i++) {
            long prev = candles.get(i - 1).time();
            long curr = candles.get(i).time();
            if (curr == prev) {
                issues.add("Duplicate candle timestamp at index " + i + " (time=" + curr + ")");
            } else if (curr < prev) {
                issues.add("Out-of-order candles: index " + i + " (time=" + curr + ") comes before index " + (i - 1) + " (time=" + prev + ")");
            }
        }
    }

    private void checkMissingCandles(List<Candle> candles, long expectedIntervalSeconds, List<String> issues) {
        if (expectedIntervalSeconds <= 0) return; // caller didn't specify a known interval — can't judge gaps
        int gapCount = 0;
        // Candle.time() is in milliseconds, so the expected interval is converted to
        // milliseconds here to compare like units — mixing seconds and milliseconds would make
        // the gap threshold off by a factor of ~1000.
        long expectedIntervalMs = expectedIntervalSeconds * 1000;
        for (int i = 1; i < candles.size(); i++) {
            long gapMs = candles.get(i).time() - candles.get(i - 1).time();
            // Allow up to 1.5x the expected interval before calling it a genuine gap — real
            // exchange candle timestamps aren't always perfectly uniform to the second.
            if (gapMs > expectedIntervalMs * 1.5) gapCount++;
        }
        if (gapCount > 0) issues.add(gapCount + " gap(s) detected in candle history larger than 1.5x the expected " + expectedIntervalSeconds + "s interval");
    }

    private void checkFreshness(List<Candle> candles, long expectedIntervalSeconds, List<String> issues) {
        Candle last = candles.get(candles.size() - 1);
        long lastCandleTimeMs = last.time();
        // Candle.time() is milliseconds (Binance's kline openTime field, used as-is), so we
        // work in epoch milliseconds throughout and divide down to seconds only at the end,
        // rather than risk a unit mismatch between seconds and milliseconds.
        long ageSeconds = (Instant.now().toEpochMilli() - lastCandleTimeMs) / 1000;
        long staleThreshold = expectedIntervalSeconds > 0 ? expectedIntervalSeconds * 3 : Duration.ofHours(2).toSeconds();
        if (ageSeconds > staleThreshold) {
            issues.add("Most recent candle is " + ageSeconds + "s old, exceeding the " + staleThreshold + "s freshness threshold — market data may be stale");
        }
    }

    private void checkPriceJumps(List<Candle> candles, List<String> issues) {
        for (int i = 1; i < candles.size(); i++) {
            double prevClose = candles.get(i - 1).close();
            double currClose = candles.get(i).close();
            if (prevClose <= 0) continue;
            double changePercent = Math.abs((currClose - prevClose) / prevClose) * 100.0;
            if (changePercent > MAX_SINGLE_CANDLE_JUMP_PERCENT) {
                issues.add(String.format("Abnormal single-candle price jump at index %d: %.1f%% (prev close %.4f, this close %.4f)",
                    i, changePercent, prevClose, currClose));
            }
        }
    }

    private void checkVolumeAnomaly(List<Candle> candles, List<String> issues) {
        // Average over everything except the most recent candle, so the most recent candle can
        // actually be compared AGAINST that baseline rather than being part of its own baseline.
        List<Candle> history = candles.subList(0, candles.size() - 1);
        double avgVolume = history.stream().mapToDouble(Candle::volume).average().orElse(0);
        double latestVolume = candles.get(candles.size() - 1).volume();
        if (avgVolume <= 0) return; // no meaningful baseline to compare against
        double multiple = latestVolume / avgVolume;
        if (multiple > VOLUME_ANOMALY_MULTIPLE || multiple < (1.0 / VOLUME_ANOMALY_MULTIPLE)) {
            issues.add(String.format("Volume anomaly on the most recent candle: %.1fx the recent average (%.2f vs avg %.2f)",
                multiple, latestVolume, avgVolume));
        }
    }

    private void checkSpread(String symbol, BrokerAdapter adapter, BrokerMode mode, List<String> issues) {
        if (adapter == null) return; // caller didn't provide a live adapter — skip rather than fail closed on a check that can't run
        try {
            var spread = adapter.getSpread(symbol, mode);
            if (spread.spreadPercent() > MAX_ACCEPTABLE_SPREAD_PERCENT) {
                issues.add(String.format("Spread too wide: %.3f%% (bid %s, ask %s) exceeds the %.2f%% threshold",
                    spread.spreadPercent(), spread.bidPrice(), spread.askPrice(), MAX_ACCEPTABLE_SPREAD_PERCENT));
            }
        } catch (Exception e) {
            issues.add("Could not verify spread: " + e.getMessage() + " — treated as a quality issue, not silently skipped");
        }
    }

    private void checkClockDrift(BrokerAdapter adapter, List<String> issues) {
        if (adapter == null) return;
        try {
            long driftMs = adapter.getClockDriftMs();
            if (Math.abs(driftMs) > MAX_ACCEPTABLE_CLOCK_DRIFT_MS) {
                issues.add("Exchange clock drift abnormally large: " + driftMs + "ms (threshold "
                    + MAX_ACCEPTABLE_CLOCK_DRIFT_MS + "ms) — possible local system clock problem");
            }
        } catch (Exception e) {
            issues.add("Could not verify exchange clock drift: " + e.getMessage() + " — treated as a quality issue, not silently skipped");
        }
    }

    private void checkDepthImbalance(String symbol, BrokerAdapter adapter, BrokerMode mode, List<String> issues) {
        if (adapter == null) return;
        try {
            var depth = adapter.getOrderBookDepth(symbol, mode, DEPTH_LEVELS_TO_CHECK);
            if (depth.bids().isEmpty() || depth.asks().isEmpty()) {
                issues.add("Order book has no bids or no asks within the top " + DEPTH_LEVELS_TO_CHECK + " levels — effectively no liquidity");
                return;
            }
            double totalBid = depth.totalBidQuantity().doubleValue();
            double totalAsk = depth.totalAskQuantity().doubleValue();
            if (totalBid <= 0 || totalAsk <= 0) {
                issues.add("Order book depth reports zero total quantity on one side within the top " + DEPTH_LEVELS_TO_CHECK + " levels");
                return;
            }
            double ratio = totalBid / totalAsk;
            if (ratio > MAX_ACCEPTABLE_DEPTH_IMBALANCE_MULTIPLE || ratio < (1.0 / MAX_ACCEPTABLE_DEPTH_IMBALANCE_MULTIPLE)) {
                issues.add(String.format("Order book depth imbalance: bid/ask ratio %.1fx within the top %d levels (bid qty %.4f, ask qty %.4f) — possible thin or one-sided liquidity",
                    ratio, DEPTH_LEVELS_TO_CHECK, totalBid, totalAsk));
            }
        } catch (Exception e) {
            issues.add("Could not verify order book depth: " + e.getMessage() + " — treated as a quality issue, not silently skipped");
        }
    }
}
