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
 * Review finding ("#8 — Market-data quality engine" — "freshness, missing candles, duplicate
 * candles, timestamp order, spread, price jump, volume anomaly, REST/WS disagreement... Then
 * every strategy asks: isMarketSafeToTrade(symbol)? If NO -> no trade"): this is that gate.
 *
 * HONEST SCOPE:
 * - REST/WS disagreement is NOT implemented — checked before building this, and confirmed this
 *   codebase has no market-data WebSocket feed at all (BinanceUserDataStreamService is a
 *   USER-data stream — order fills and account events — not a klines/ticker market-data
 *   stream). There is nothing to disagree WITH; this check has no second data source to compare
 *   against, and building one is real further scope, not something to fake here.
 * - Spread checking IS implemented — checked before assuming otherwise, and found
 *   BrokerAdapter.getSpread already exists and works. Not every check in the review's list
 *   turned out to be unbuildable.
 * - "Price jump" and "volume anomaly" thresholds below are simple, disclosed heuristics (a
 *   fixed percentage/multiple), not a statistical or learned model — stated plainly so a wide
 *   but genuine market move isn't mistaken for a validated "this is definitely bad data" signal.
 */
@Service
public class MarketDataQualityService {

    private static final double MAX_SINGLE_CANDLE_JUMP_PERCENT = 20.0;
    private static final double VOLUME_ANOMALY_MULTIPLE = 10.0;
    private static final double MAX_ACCEPTABLE_SPREAD_PERCENT = 1.0;
    // Review finding ("Market data" — "exchange clock drift"): 5 seconds is a deliberately
    // generous threshold — Binance's own recvWindow default is 5000ms, and this adapter already
    // corrects signed-request timestamps using this same offset (review item #25), so a drift
    // under this threshold is already being handled correctly. This check exists to catch a
    // GENUINELY abnormal drift (a real system clock problem), not to second-guess normal,
    // already-compensated-for network/clock variance.
    private static final long MAX_ACCEPTABLE_CLOCK_DRIFT_MS = 5000;
    // Review finding ("Market data" — "order-book depth, bid/ask depth imbalance"): a simple,
    // disclosed heuristic (a fixed multiple), not a statistical model — a genuinely thin or
    // one-sided book at the moment of the check, same "don't overclaim precision" rule as the
    // price-jump/volume-anomaly heuristics elsewhere in this class.
    private static final double MAX_ACCEPTABLE_DEPTH_IMBALANCE_MULTIPLE = 20.0;
    private static final int DEPTH_LEVELS_TO_CHECK = 20;
    private static final int MIN_CANDLES_REQUIRED = 20;

    public record QualityResult(boolean safe, List<String> issues) {
        public static QualityResult ok() {
            return new QualityResult(true, List.of());
        }
    }

    /**
     * The review's own named entry point. Runs every check this class actually supports (see
     * this class's own javadoc for what's deliberately not included) and returns a single
     * safe/unsafe verdict with the specific reasons — never just a bare boolean, so a caller (or
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
        // Review finding (same unit-mismatch bug caught and fixed in checkFreshness — Candle.time()
        // is milliseconds, not seconds): comparing the raw millisecond gap against
        // expectedIntervalSeconds directly would have made this check essentially useless — a
        // real interval gap in milliseconds is ~1000x the seconds-based threshold, so it would
        // have (almost) always tripped, or (with a big enough expectedIntervalSeconds) sometimes
        // never tripped, either way not measuring what it claims to. Gap converted to seconds
        // before comparing, matching the unit checkFreshness now uses.
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
        // Review finding (caught while writing this method, not after — verified against the
        // actual construction site rather than assumed): Candle.time() is MILLISECONDS, not
        // seconds. BinanceBrokerAdapter.getRecentCandles constructs it directly from Binance's
        // kline openTime field (k.get(0).asLong()) with zero conversion, and Binance's own kline
        // API always returns millisecond epoch timestamps. Comparing this against
        // Instant.now().getEpochSecond() (seconds) would have produced a result roughly 1000x
        // wrong on every single call — using toEpochMilli() throughout instead avoids the unit
        // mismatch entirely rather than requiring a conversion to get right.
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
