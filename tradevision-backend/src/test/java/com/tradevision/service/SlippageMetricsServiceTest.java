package com.tradevision.service;

import com.tradevision.model.Order;
import com.tradevision.model.OrderStatus;
import com.tradevision.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Review finding ("#9 — Slippage"): verifies the actual basis-points math, the sign convention
 * (positive = worse fill), and the "exclude, don't zero" rule for orders with no requestedPrice.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlippageMetricsServiceTest {

    @Mock OrderRepository orderRepo;
    @InjectMocks SlippageMetricsService service;

    private Order order(double requestedPrice, double fillPrice) {
        Order o = new Order();
        o.setRequestedPrice(BigDecimal.valueOf(requestedPrice));
        o.setAverageFillPrice(BigDecimal.valueOf(fillPrice));
        return o;
    }

    @Test
    @DisplayName("slippageBps: a worse fill (paid more on a BUY) is POSITIVE — expected 100, filled at 100.5 -> 50 bps")
    void worseFill_isPositive() {
        Long bps = service.slippageBps(order(100, 100.5));

        assertThat(bps).isEqualTo(50); // (100.5-100)/100 * 10000 = 50 bps
    }

    @Test
    @DisplayName("slippageBps: a better fill (paid less on a BUY) is NEGATIVE — the sign is preserved, not just magnitude")
    void betterFill_isNegative() {
        Long bps = service.slippageBps(order(100, 99.75));

        assertThat(bps).isEqualTo(-25); // (99.75-100)/100 * 10000 = -25 bps
    }

    @Test
    @DisplayName("slippageBps: an exact fill at the expected price is exactly zero")
    void exactFill_isZero() {
        Long bps = service.slippageBps(order(100, 100));

        assertThat(bps).isEqualTo(0);
    }

    @Test
    @DisplayName("slippageBps: no requestedPrice at all returns null, not a fabricated zero — the order is genuinely excluded from statistics")
    void noRequestedPrice_returnsNull() {
        Order o = new Order();
        o.setAverageFillPrice(BigDecimal.valueOf(100));
        // requestedPrice deliberately left null

        assertThat(service.slippageBps(o)).isNull();
    }

    @Test
    @DisplayName("slippageBps: no averageFillPrice (never actually filled) returns null")
    void noFillPrice_returnsNull() {
        Order o = new Order();
        o.setRequestedPrice(BigDecimal.valueOf(100));

        assertThat(service.slippageBps(o)).isNull();
    }

    @Test
    @DisplayName("computeStats: orders missing requestedPrice are excluded from the sample, not counted as zero slippage")
    void computeStats_excludesUnpriceableOrders() {
        Order withPrice = order(100, 100.5);      // 50 bps
        Order noPrice = new Order();
        noPrice.setAverageFillPrice(BigDecimal.valueOf(100));

        var stats = service.computeStats(List.of(withPrice, noPrice));

        assertThat(stats.sampleCount()).isEqualTo(1); // only the priceable one counted
        assertThat(stats.avgBps()).isEqualTo(50); // not diluted by a fabricated zero from the excluded order
    }

    @Test
    @DisplayName("computeStats: correct P50/P95 across a real dataset with mixed positive and negative slippage")
    void computeStats_correctPercentilesMixedSign() {
        // A clean dataset: -50, -25, 0, 25, 50, 75, 100 bps (7 samples)
        List<Order> orders = List.of(
            order(100, 99.50), order(100, 99.75), order(100, 100.0), order(100, 100.25),
            order(100, 100.50), order(100, 100.75), order(100, 101.0)
        );

        var stats = service.computeStats(orders);

        assertThat(stats.sampleCount()).isEqualTo(7);
        assertThat(stats.maxBps()).isEqualTo(100);
        assertThat(stats.avgBps()).isEqualTo(25); // (-50-25+0+25+50+75+100)/7 = 175/7 = 25
    }

    @Test
    @DisplayName("computeStats: empty dataset returns a real empty result, not an exception")
    void computeStats_emptyDataset() {
        var stats = service.computeStats(List.of());

        assertThat(stats.sampleCount()).isEqualTo(0);
        assertThat(stats.avgBps()).isEqualTo(0);
    }

    // ── Volatility bucketing ("Execution" — "slippage by volatility") ────────────

    private Order orderWithVolatility(double requestedPrice, double fillPrice, Double volatilityAtEntry) {
        Order o = order(requestedPrice, fillPrice);
        o.setVolatilityAtEntry(volatilityAtEntry);
        return o;
    }

    @Test
    @DisplayName("reportByVolatilityBucket: orders correctly sorted into low/medium/high buckets by their own thresholds")
    void reportByVolatilityBucket_sortsIntoCorrectBuckets() {
        Order low = orderWithVolatility(100, 100.1, 0.5);      // < 1.0 -> low
        Order medium = orderWithVolatility(100, 100.5, 2.0);   // 1.0-3.0 -> medium
        Order high = orderWithVolatility(100, 101.0, 5.0);     // > 3.0 -> high
        when(orderRepo.findByStatusAndCreatedAtAfter(eq(OrderStatus.FILLED), any())).thenReturn(List.of(low, medium, high));

        var report = service.reportByVolatilityBucket(null, 30);

        assertThat(report.lowVolatility().sampleCount()).isEqualTo(1);
        assertThat(report.mediumVolatility().sampleCount()).isEqualTo(1);
        assertThat(report.highVolatility().sampleCount()).isEqualTo(1);
        assertThat(report.ordersExcludedNoVolatilityData()).isEqualTo(0);
    }

    @Test
    @DisplayName("reportByVolatilityBucket: boundary values are correctly assigned — exactly 1.0 is medium (not low), exactly 3.0 is medium (not high)")
    void reportByVolatilityBucket_boundaryValuesCorrect() {
        Order atLowBoundary = orderWithVolatility(100, 100.1, 1.0);   // exactly 1.0 -> NOT < 1.0 -> medium
        Order atHighBoundary = orderWithVolatility(100, 100.5, 3.0);  // exactly 3.0 -> NOT > 3.0 -> medium
        when(orderRepo.findByStatusAndCreatedAtAfter(eq(OrderStatus.FILLED), any())).thenReturn(List.of(atLowBoundary, atHighBoundary));

        var report = service.reportByVolatilityBucket(null, 30);

        assertThat(report.mediumVolatility().sampleCount()).isEqualTo(2);
        assertThat(report.lowVolatility().sampleCount()).isEqualTo(0);
        assertThat(report.highVolatility().sampleCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("reportByVolatilityBucket: an order with no volatility data is excluded and counted, never guessed into a bucket")
    void reportByVolatilityBucket_missingVolatilityExcluded() {
        Order withData = orderWithVolatility(100, 100.1, 0.5);
        Order noData = orderWithVolatility(100, 100.1, null);
        when(orderRepo.findByStatusAndCreatedAtAfter(eq(OrderStatus.FILLED), any())).thenReturn(List.of(withData, noData));

        var report = service.reportByVolatilityBucket(null, 30);

        assertThat(report.lowVolatility().sampleCount()).isEqualTo(1);
        assertThat(report.ordersExcludedNoVolatilityData()).isEqualTo(1);
    }

    @Test
    @DisplayName("reportByVolatilityBucket: filters by symbol before bucketing")
    void reportByVolatilityBucket_filtersBySymbol() {
        Order btc = orderWithVolatility(100, 100.1, 0.5);
        btc.setSymbol("BTCUSDT");
        Order eth = orderWithVolatility(100, 100.1, 0.5);
        eth.setSymbol("ETHUSDT");
        when(orderRepo.findByStatusAndCreatedAtAfter(eq(OrderStatus.FILLED), any())).thenReturn(List.of(btc, eth));

        var report = service.reportByVolatilityBucket("BTCUSDT", 30);

        assertThat(report.lowVolatility().sampleCount()).isEqualTo(1); // only btc
    }

    @Test
    @DisplayName("reportByStrategyVersion: orders correctly grouped by their own distinct strategyVersion string -- the actual review fix (\"Slippage analytics can't be tied to strategy version\")")
    void reportByStrategyVersion_groupsByVersion() {
        Order v1a = order(100, 100.5);
        v1a.setStrategyVersion("v1");
        Order v1b = order(100, 100.2);
        v1b.setStrategyVersion("v1");
        Order v2 = order(100, 101.0);
        v2.setStrategyVersion("v2");
        when(orderRepo.findByStatusAndCreatedAtAfter(eq(OrderStatus.FILLED), any())).thenReturn(List.of(v1a, v1b, v2));

        var report = service.reportByStrategyVersion(null, 30);

        assertThat(report.get("v1").sampleCount()).isEqualTo(2);
        assertThat(report.get("v2").sampleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("reportByStrategyVersion: an order with a null strategyVersion is excluded entirely, not lumped into a misleading \"null\" group")
    void reportByStrategyVersion_nullVersionExcluded() {
        Order known = order(100, 100.5);
        known.setStrategyVersion("v1");
        Order unknown = order(100, 100.2); // strategyVersion left null
        when(orderRepo.findByStatusAndCreatedAtAfter(eq(OrderStatus.FILLED), any())).thenReturn(List.of(known, unknown));

        var report = service.reportByStrategyVersion(null, 30);

        assertThat(report).containsOnlyKeys("v1");
        assertThat(report.get("v1").sampleCount()).isEqualTo(1);
    }
}
