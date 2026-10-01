package com.tradevision.service;

import com.tradevision.model.BrokerMode;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.Candle;
import com.tradevision.service.broker.dto.SpreadInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Review finding ("#8 — Market-data quality engine"): verifies the actual checks, in
 * milliseconds throughout — matching the real unit of Candle.time(), a mismatch this class's
 * own comments describe catching and fixing during development, not after.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketDataQualityServiceTest {

    @Mock BrokerAdapter adapter;
    private final MarketDataQualityService service = new MarketDataQualityService();
    private static final long ONE_HOUR_MS = 3600_000L;

    @BeforeEach
    void setup() {
        // Review finding ("Market data" — "order-book depth"): a generous, balanced default so
        // every existing test (which never cared about order-book depth) isn't NPE'd by
        // Mockito's default null return for the unstubbed OrderBookDepth object — same pattern
        // as PositionSafetyServiceTest's own getBalance default stub.
        when(adapter.getOrderBookDepth(any(), any(), anyInt())).thenReturn(new com.tradevision.service.broker.dto.OrderBookDepth(
            List.of(new com.tradevision.service.broker.dto.OrderBookDepth.PriceLevel(BigDecimal.valueOf(100), BigDecimal.valueOf(1000))),
            List.of(new com.tradevision.service.broker.dto.OrderBookDepth.PriceLevel(BigDecimal.valueOf(100.1), BigDecimal.valueOf(1000)))));
    }

    /** Builds `count` clean, evenly-spaced candles ending at "now", one hour apart, gentle price movement. */
    private List<Candle> cleanCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        long nowMs = Instant.now().toEpochMilli();
        double price = 100.0;
        for (int i = count - 1; i >= 0; i--) {
            long t = nowMs - i * ONE_HOUR_MS;
            candles.add(new Candle(t, price, price * 1.005, price * 0.995, price, 1000.0));
        }
        return candles;
    }

    @Test
    @DisplayName("isMarketSafeToTrade: clean, fresh, evenly-spaced candles with a tight spread pass with no issues")
    void cleanData_passesWithNoIssues() {
        when(adapter.getSpread("BTCUSDT", BrokerMode.TESTNET)).thenReturn(new SpreadInfo(BigDecimal.valueOf(100), BigDecimal.valueOf(100.05), 0.05));

        var result = service.isMarketSafeToTrade("BTCUSDT", cleanCandles(30), 3600, adapter, BrokerMode.TESTNET);

        assertThat(result.safe()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("isMarketSafeToTrade: fewer than the minimum required candles fails immediately, without running any other check")
    void insufficientCandles_failsImmediately() {
        var result = service.isMarketSafeToTrade("BTCUSDT", cleanCandles(5), 3600, adapter, BrokerMode.TESTNET);

        assertThat(result.safe()).isFalse();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0)).contains("Insufficient candle history");
    }

    @Test
    @DisplayName("checkFreshness (via isMarketSafeToTrade): the correct millisecond-to-second conversion — a candle 10 hours old on a 1h interval (3h threshold) is correctly flagged stale")
    void staleData_correctlyDetectedWithRealUnits() {
        List<Candle> candles = cleanCandles(30);
        // Replace the last candle with one that's genuinely 10 hours old — well past the
        // 3-hour (3x interval) freshness threshold.
        long staleTimeMs = Instant.now().toEpochMilli() - (10 * ONE_HOUR_MS);
        candles.set(candles.size() - 1, new Candle(staleTimeMs, 100, 100.5, 99.5, 100, 1000));
        when(adapter.getSpread("BTCUSDT", BrokerMode.TESTNET)).thenReturn(new SpreadInfo(BigDecimal.valueOf(100), BigDecimal.valueOf(100.05), 0.05));

        var result = service.isMarketSafeToTrade("BTCUSDT", candles, 3600, adapter, BrokerMode.TESTNET);

        assertThat(result.safe()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.contains("stale"));
    }

    @Test
    @DisplayName("checkMissingCandles (via isMarketSafeToTrade): a real gap (removing a middle candle, leaving a 2-hour hole where 1 hour was expected) is detected using the correct millisecond math")
    void gapInCandles_correctlyDetected() {
        List<Candle> candles = new ArrayList<>(cleanCandles(30));
        candles.remove(15); // removes one candle from the middle, leaving a clean 2x-interval gap between its former neighbors — no ordering violation, just a genuine gap
        when(adapter.getSpread("BTCUSDT", BrokerMode.TESTNET)).thenReturn(new SpreadInfo(BigDecimal.valueOf(100), BigDecimal.valueOf(100.05), 0.05));

        var result = service.isMarketSafeToTrade("BTCUSDT", candles, 3600, adapter, BrokerMode.TESTNET);

        assertThat(result.issues()).anyMatch(i -> i.contains("gap"));
    }

    @Test
    @DisplayName("checkTimestampOrderAndDuplicates (via isMarketSafeToTrade): a duplicate timestamp is detected")
    void duplicateTimestamp_detected() {
        List<Candle> candles = cleanCandles(30);
        Candle dup = candles.get(10);
        candles.set(11, new Candle(dup.time(), dup.open(), dup.high(), dup.low(), dup.close(), dup.volume()));
        when(adapter.getSpread("BTCUSDT", BrokerMode.TESTNET)).thenReturn(new SpreadInfo(BigDecimal.valueOf(100), BigDecimal.valueOf(100.05), 0.05));

        var result = service.isMarketSafeToTrade("BTCUSDT", candles, 3600, adapter, BrokerMode.TESTNET);

        assertThat(result.issues()).anyMatch(i -> i.contains("Duplicate"));
    }

    @Test
    @DisplayName("checkPriceJumps (via isMarketSafeToTrade): a 30% single-candle move is flagged, a normal 0.5% move is not")
    void priceJump_detected() {
        List<Candle> candles = cleanCandles(30);
        Candle prev = candles.get(20);
        candles.set(21, new Candle(prev.time() + ONE_HOUR_MS, prev.close(), prev.close() * 1.31, prev.close() * 0.99, prev.close() * 1.30, 1000));
        when(adapter.getSpread("BTCUSDT", BrokerMode.TESTNET)).thenReturn(new SpreadInfo(BigDecimal.valueOf(100), BigDecimal.valueOf(100.05), 0.05));

        var result = service.isMarketSafeToTrade("BTCUSDT", candles, 3600, adapter, BrokerMode.TESTNET);

        assertThat(result.issues()).anyMatch(i -> i.contains("price jump"));
    }

    @Test
    @DisplayName("checkVolumeAnomaly (via isMarketSafeToTrade): the latest candle's volume at 20x the recent average is flagged")
    void volumeAnomaly_detected() {
        List<Candle> candles = cleanCandles(30);
        Candle last = candles.get(candles.size() - 1);
        candles.set(candles.size() - 1, new Candle(last.time(), last.open(), last.high(), last.low(), last.close(), 20_000.0)); // avg is 1000
        when(adapter.getSpread("BTCUSDT", BrokerMode.TESTNET)).thenReturn(new SpreadInfo(BigDecimal.valueOf(100), BigDecimal.valueOf(100.05), 0.05));

        var result = service.isMarketSafeToTrade("BTCUSDT", candles, 3600, adapter, BrokerMode.TESTNET);

        assertThat(result.issues()).anyMatch(i -> i.contains("Volume anomaly"));
    }

    @Test
    @DisplayName("checkSpread (via isMarketSafeToTrade): a spread wider than the threshold is flagged")
    void wideSpread_detected() {
        when(adapter.getSpread("BTCUSDT", BrokerMode.TESTNET)).thenReturn(new SpreadInfo(BigDecimal.valueOf(100), BigDecimal.valueOf(103), 3.0)); // 3% spread, over the 1% threshold

        var result = service.isMarketSafeToTrade("BTCUSDT", cleanCandles(30), 3600, adapter, BrokerMode.TESTNET);

        assertThat(result.issues()).anyMatch(i -> i.contains("Spread too wide"));
    }

    @Test
    @DisplayName("checkSpread (via isMarketSafeToTrade): a spread query failure is reported as an issue, not silently ignored")
    void spreadQueryFails_reportedAsIssue() {
        when(adapter.getSpread("BTCUSDT", BrokerMode.TESTNET)).thenThrow(new RuntimeException("connection timeout"));

        var result = service.isMarketSafeToTrade("BTCUSDT", cleanCandles(30), 3600, adapter, BrokerMode.TESTNET);

        assertThat(result.safe()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.contains("Could not verify spread"));
    }

    @Test
    @DisplayName("checkClockDrift (via isMarketSafeToTrade): a 10-second drift is flagged as abnormal")
    void largeClockDrift_detected() {
        when(adapter.getSpread("BTCUSDT", BrokerMode.TESTNET)).thenReturn(new SpreadInfo(BigDecimal.valueOf(100), BigDecimal.valueOf(100.05), 0.05));
        when(adapter.getClockDriftMs()).thenReturn(10_000L);

        var result = service.isMarketSafeToTrade("BTCUSDT", cleanCandles(30), 3600, adapter, BrokerMode.TESTNET);

        assertThat(result.safe()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.contains("clock drift"));
    }

    @Test
    @DisplayName("checkClockDrift (via isMarketSafeToTrade): a negative drift (local clock ahead) is also detected — the check uses absolute value, not just a one-directional threshold")
    void negativeClockDrift_alsoDetected() {
        when(adapter.getSpread("BTCUSDT", BrokerMode.TESTNET)).thenReturn(new SpreadInfo(BigDecimal.valueOf(100), BigDecimal.valueOf(100.05), 0.05));
        when(adapter.getClockDriftMs()).thenReturn(-8_000L);

        var result = service.isMarketSafeToTrade("BTCUSDT", cleanCandles(30), 3600, adapter, BrokerMode.TESTNET);

        assertThat(result.issues()).anyMatch(i -> i.contains("clock drift"));
    }

    @Test
    @DisplayName("checkClockDrift (via isMarketSafeToTrade): a small, normal drift (2 seconds, well under Binance's own recvWindow default) is NOT flagged")
    void smallClockDrift_notFlagged() {
        when(adapter.getSpread("BTCUSDT", BrokerMode.TESTNET)).thenReturn(new SpreadInfo(BigDecimal.valueOf(100), BigDecimal.valueOf(100.05), 0.05));
        when(adapter.getClockDriftMs()).thenReturn(2_000L);

        var result = service.isMarketSafeToTrade("BTCUSDT", cleanCandles(30), 3600, adapter, BrokerMode.TESTNET);

        assertThat(result.safe()).isTrue();
        assertThat(result.issues()).noneMatch(i -> i.contains("clock drift"));
    }

    @Test
    @DisplayName("checkDepthImbalance (via isMarketSafeToTrade): a heavily one-sided order book (50x more bid than ask volume) is flagged")
    void heavyDepthImbalance_detected() {
        when(adapter.getSpread("BTCUSDT", BrokerMode.TESTNET)).thenReturn(new SpreadInfo(BigDecimal.valueOf(100), BigDecimal.valueOf(100.05), 0.05));
        when(adapter.getOrderBookDepth(eq("BTCUSDT"), eq(BrokerMode.TESTNET), anyInt())).thenReturn(new com.tradevision.service.broker.dto.OrderBookDepth(
            List.of(new com.tradevision.service.broker.dto.OrderBookDepth.PriceLevel(BigDecimal.valueOf(100), BigDecimal.valueOf(5000))),
            List.of(new com.tradevision.service.broker.dto.OrderBookDepth.PriceLevel(BigDecimal.valueOf(100.1), BigDecimal.valueOf(100)))));

        var result = service.isMarketSafeToTrade("BTCUSDT", cleanCandles(30), 3600, adapter, BrokerMode.TESTNET);

        assertThat(result.safe()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.contains("depth imbalance"));
    }

    @Test
    @DisplayName("checkDepthImbalance (via isMarketSafeToTrade): an empty order book (no bids at all) is flagged as no liquidity, not silently passed")
    void emptyOrderBook_flaggedAsNoLiquidity() {
        when(adapter.getSpread("BTCUSDT", BrokerMode.TESTNET)).thenReturn(new SpreadInfo(BigDecimal.valueOf(100), BigDecimal.valueOf(100.05), 0.05));
        when(adapter.getOrderBookDepth(eq("BTCUSDT"), eq(BrokerMode.TESTNET), anyInt())).thenReturn(new com.tradevision.service.broker.dto.OrderBookDepth(
            List.of(), List.of(new com.tradevision.service.broker.dto.OrderBookDepth.PriceLevel(BigDecimal.valueOf(100.1), BigDecimal.valueOf(100)))));

        var result = service.isMarketSafeToTrade("BTCUSDT", cleanCandles(30), 3600, adapter, BrokerMode.TESTNET);

        assertThat(result.safe()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.contains("no bids or no asks"));
    }

    @Test
    @DisplayName("checkDepthImbalance (via isMarketSafeToTrade): a balanced order book (roughly equal bid/ask volume) is NOT flagged")
    void balancedOrderBook_notFlagged() {
        when(adapter.getSpread("BTCUSDT", BrokerMode.TESTNET)).thenReturn(new SpreadInfo(BigDecimal.valueOf(100), BigDecimal.valueOf(100.05), 0.05));
        when(adapter.getOrderBookDepth(eq("BTCUSDT"), eq(BrokerMode.TESTNET), anyInt())).thenReturn(new com.tradevision.service.broker.dto.OrderBookDepth(
            List.of(new com.tradevision.service.broker.dto.OrderBookDepth.PriceLevel(BigDecimal.valueOf(100), BigDecimal.valueOf(1200))),
            List.of(new com.tradevision.service.broker.dto.OrderBookDepth.PriceLevel(BigDecimal.valueOf(100.1), BigDecimal.valueOf(1000)))));

        var result = service.isMarketSafeToTrade("BTCUSDT", cleanCandles(30), 3600, adapter, BrokerMode.TESTNET);

        assertThat(result.safe()).isTrue();
        assertThat(result.issues()).noneMatch(i -> i.contains("depth imbalance"));
    }
}
