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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Verifies the percentile math and the rule that a stage without a timestamp is excluded from
 * that stage's stats rather than counted as zero latency.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LatencyMetricsServiceTest {

    @Mock OrderRepository orderRepo;
    @Mock com.tradevision.repository.PositionRepository positionRepo;
    @InjectMocks LatencyMetricsService service;

    @Test
    @DisplayName("computeStatsFromDurations: known dataset produces correct avg/P50/P95/P99/max — real math, not a placeholder")
    void computeStats_correctPercentiles() {
        // 1..100 ms, a clean dataset with well-known percentile answers.
        List<Long> durations = new java.util.ArrayList<>();
        for (long i = 1; i <= 100; i++) durations.add(i);

        var stats = service.computeStatsFromDurations(durations);

        assertThat(stats.sampleCount()).isEqualTo(100);
        assertThat(stats.avgMs()).isEqualTo(50.5); // (1+...+100)/100
        assertThat(stats.maxMs()).isEqualTo(100);
        assertThat(stats.p50Ms()).isEqualTo(50);   // ceil(0.50*100)-1 = 49 (0-indexed) -> value 50
        assertThat(stats.p95Ms()).isEqualTo(95);   // ceil(0.95*100)-1 = 94 -> value 95
        assertThat(stats.p99Ms()).isEqualTo(99);   // ceil(0.99*100)-1 = 98 -> value 99
    }

    @Test
    @DisplayName("computeStatsFromDurations: empty dataset returns a real empty result, not an exception or a fabricated zero")
    void computeStats_emptyDataset() {
        var stats = service.computeStatsFromDurations(List.of());

        assertThat(stats.sampleCount()).isEqualTo(0);
        assertThat(stats.avgMs()).isEqualTo(0);
        assertThat(stats.maxMs()).isEqualTo(0);
    }

    @Test
    @DisplayName("computeStatsFromDurations: a single sample — every percentile equals that one value")
    void computeStats_singleSample() {
        var stats = service.computeStatsFromDurations(List.of(42L));

        assertThat(stats.sampleCount()).isEqualTo(1);
        assertThat(stats.p50Ms()).isEqualTo(42);
        assertThat(stats.p95Ms()).isEqualTo(42);
        assertThat(stats.p99Ms()).isEqualTo(42);
        assertThat(stats.maxMs()).isEqualTo(42);
    }

    private Order orderWithTimestamps(String symbol, LocalDateTime created, LocalDateTime riskAccepted,
                                       LocalDateTime submitStarted, LocalDateTime brokerAck, LocalDateTime firstFill,
                                       LocalDateTime protectionPlaced) {
        Order o = new Order();
        o.setSymbol(symbol);
        o.setStatus(OrderStatus.FILLED);
        o.setCreatedAt(created);
        o.setRiskAcceptedAt(riskAccepted);
        o.setSubmitStartedAt(submitStarted);
        o.setBrokerAckAt(brokerAck);
        o.setFirstFillAt(firstFill);
        o.setProtectionPlacedAt(protectionPlaced);
        return o;
    }

    @Test
    @DisplayName("buildReport: an order missing a later timestamp (e.g. protection never placed) contributes to earlier stages but not to that one — never counted as a zero-latency stage")
    void buildReport_missingTimestamp_excludedNotZero() {
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
        Order complete = orderWithTimestamps("BTCUSDT", t0, t0.plusSeconds(1), t0.plusSeconds(2), t0.plusSeconds(3), t0.plusSeconds(4), t0.plusSeconds(5));
        Order noProtection = orderWithTimestamps("BTCUSDT", t0, t0.plusSeconds(1), t0.plusSeconds(2), t0.plusSeconds(3), t0.plusSeconds(4), null);

        var report = service.buildReport(List.of(complete, noProtection));

        assertThat(report.signalToRisk().sampleCount()).isEqualTo(2);       // both orders have createdAt+riskAcceptedAt
        assertThat(report.fillToProtection().sampleCount()).isEqualTo(1);   // only the complete one has protectionPlacedAt
        assertThat(report.totalOrdersConsidered()).isEqualTo(2);
    }

    @Test
    @DisplayName("buildReport: correct millisecond durations computed for a real, fully-timestamped order")
    void buildReport_correctDurations() {
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
        Order o = orderWithTimestamps("BTCUSDT", t0,
            t0.plusNanos(50_000_000),   // signal->risk: 50ms
            t0.plusNanos(150_000_000),  // risk->submit: 100ms
            t0.plusNanos(300_000_000),  // submit->ack: 150ms
            t0.plusNanos(500_000_000),  // ack->fill: 200ms
            t0.plusNanos(700_000_000)); // fill->protection: 200ms

        var report = service.buildReport(List.of(o));

        assertThat(report.signalToRisk().avgMs()).isEqualTo(50);
        assertThat(report.riskToSubmit().avgMs()).isEqualTo(100);
        assertThat(report.submitToAck().avgMs()).isEqualTo(150);
        assertThat(report.ackToFirstFill().avgMs()).isEqualTo(200);
        assertThat(report.fillToProtection().avgMs()).isEqualTo(200);
    }

    @Test
    @DisplayName("reportForSymbol: filters by symbol and lookback window before computing")
    void reportForSymbol_filtersCorrectly() {
        LocalDateTime t0 = LocalDateTime.now();
        Order btcRecent = orderWithTimestamps("BTCUSDT", t0.minusHours(1), t0.minusHours(1).plusSeconds(1), null, null, null, null);
        Order ethRecent = orderWithTimestamps("ETHUSDT", t0.minusHours(1), t0.minusHours(1).plusSeconds(1), null, null, null, null);
        Order btcOld = orderWithTimestamps("BTCUSDT", t0.minusDays(30), t0.minusDays(30).plusSeconds(1), null, null, null, null);
        when(orderRepo.findByStatusAndCreatedAtAfter(eq(OrderStatus.FILLED), any())).thenReturn(List.of(btcRecent, ethRecent));

        var report = service.reportForSymbol("BTCUSDT", 7);

        assertThat(report.totalOrdersConsidered()).isEqualTo(1); // only btcRecent: right symbol, within lookback
    }

    // ── Position lifetime ("Execution" — "exit latency") ────────────────────────

    private com.tradevision.model.Position closedPosition(String symbol, LocalDateTime openedAt, LocalDateTime closedAt) {
        var p = new com.tradevision.model.Position();
        p.setSymbol(symbol);
        p.setStatus("CLOSED");
        p.setOpenedAt(openedAt);
        p.setClosedAt(closedAt);
        return p;
    }

    // ── entryToProtectionReport ("Execution latency still entry-focused") ────────────────────

    @Test
    @DisplayName("entryToProtectionReport: correct duration computed for a position with real protection placed")
    void entryToProtectionReport_correctDuration() {
        LocalDateTime opened = LocalDateTime.now().minusMinutes(5);
        var p = new com.tradevision.model.Position();
        p.setSymbol("BTCUSDT");
        p.setOpenedAt(opened);
        p.setOcoOrderListId("oco-1");
        p.recordOcoPlaced(); // stamps "now" — roughly 5 minutes after opened
        when(positionRepo.findByOpenedAtAfter(any())).thenReturn(List.of(p));

        var stats = service.entryToProtectionReport("BTCUSDT", 7);

        assertThat(stats.sampleCount()).isEqualTo(1);
        assertThat(stats.avgMs()).isGreaterThan(0);
    }

    @Test
    @DisplayName("entryToProtectionReport: a position with NO protection placed yet is excluded, never fabricated as zero latency")
    void entryToProtectionReport_noProtectionYet_excluded() {
        var p = new com.tradevision.model.Position();
        p.setSymbol("BTCUSDT");
        p.setOpenedAt(LocalDateTime.now().minusMinutes(5));
        // ocoPlacedAt stays null — protection was never (yet) placed.
        when(positionRepo.findByOpenedAtAfter(any())).thenReturn(List.of(p));

        var stats = service.entryToProtectionReport("BTCUSDT", 7);

        assertThat(stats.sampleCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("entryToProtectionReport: includes OPEN positions, not just CLOSED ones — unlike positionLifetimeReport, an open position with protection already placed is still a complete, real sample for this specific metric")
    void entryToProtectionReport_includesOpenPositions() {
        var p = new com.tradevision.model.Position();
        p.setSymbol("BTCUSDT");
        p.setStatus("OPEN");
        p.setOpenedAt(LocalDateTime.now().minusMinutes(5));
        p.setOcoOrderListId("oco-1");
        p.recordOcoPlaced();
        when(positionRepo.findByOpenedAtAfter(any())).thenReturn(List.of(p));

        var stats = service.entryToProtectionReport("BTCUSDT", 7);

        assertThat(stats.sampleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Position.recordOcoPlaced: guards against overwriting a real first value — a resize/recovery re-placement later doesn't corrupt the entry-to-FIRST-protection measurement")
    void recordOcoPlaced_doesNotOverwriteFirstValue() {
        var p = new com.tradevision.model.Position();
        p.recordOcoPlaced();
        var firstValue = p.getOcoPlacedAt();

        p.recordOcoPlaced(); // simulates a later resize/recovery re-placement

        assertThat(p.getOcoPlacedAt()).isEqualTo(firstValue); // unchanged — still the FIRST placement's timestamp
    }

    @Test
    void positionLifetimeReport_correctDuration() {
        LocalDateTime opened = LocalDateTime.now().minusHours(2);
        LocalDateTime closed = opened.plusMinutes(90); // 1.5 hours -> 5,400,000 ms
        when(positionRepo.findByStatusAndClosedAtAfter(eq("CLOSED"), any())).thenReturn(List.of(closedPosition("BTCUSDT", opened, closed)));

        var stats = service.positionLifetimeReport("BTCUSDT", 7);

        assertThat(stats.sampleCount()).isEqualTo(1);
        assertThat(stats.avgMs()).isEqualTo(90 * 60 * 1000);
    }

    @Test
    @DisplayName("positionLifetimeReport: filters by symbol and lookback window before computing")
    void positionLifetimeReport_filtersCorrectly() {
        LocalDateTime now = LocalDateTime.now();
        var btcRecent = closedPosition("BTCUSDT", now.minusHours(2), now.minusHours(1));
        var ethRecent = closedPosition("ETHUSDT", now.minusHours(2), now.minusHours(1));
        var btcOld = closedPosition("BTCUSDT", now.minusDays(30), now.minusDays(30).plusHours(1));
        when(positionRepo.findByStatusAndClosedAtAfter(eq("CLOSED"), any())).thenReturn(List.of(btcRecent, ethRecent));

        var stats = service.positionLifetimeReport("BTCUSDT", 7);

        assertThat(stats.sampleCount()).isEqualTo(1); // only btcRecent: right symbol, within lookback
    }

    @Test
    @DisplayName("positionLifetimeReport: a closed position missing openedAt or closedAt is excluded, never fabricated as zero duration")
    void positionLifetimeReport_missingTimestampExcluded() {
        var complete = closedPosition("BTCUSDT", LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));
        var incomplete = closedPosition("BTCUSDT", null, LocalDateTime.now().minusHours(1)); // openedAt missing
        when(positionRepo.findByStatusAndClosedAtAfter(eq("CLOSED"), any())).thenReturn(List.of(complete, incomplete));

        var stats = service.positionLifetimeReport("BTCUSDT", 7);

        assertThat(stats.sampleCount()).isEqualTo(1); // only the complete one contributes
    }

    @Test
    @DisplayName("positionLifetimeReport: no closed positions at all returns a real empty result, not an exception")
    void positionLifetimeReport_emptyDataset() {
        when(positionRepo.findByStatusAndClosedAtAfter(eq("CLOSED"), any())).thenReturn(List.of());

        var stats = service.positionLifetimeReport("BTCUSDT", 7);

        assertThat(stats.sampleCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("reportByStrategyVersion: orders are grouped by their own distinct strategyVersion string, each with its own full ExecutionLatencyReport")
    void reportByStrategyVersion_groupsByVersion() {
        LocalDateTime now = LocalDateTime.now();
        Order v1 = orderWithTimestamps("BTCUSDT", now, now.plusSeconds(1), now.plusSeconds(2), now.plusSeconds(3), now.plusSeconds(4), now.plusSeconds(5));
        v1.setStrategyVersion("v1");
        Order v2 = orderWithTimestamps("BTCUSDT", now, now.plusSeconds(1), now.plusSeconds(2), now.plusSeconds(3), now.plusSeconds(4), now.plusSeconds(5));
        v2.setStrategyVersion("v2");
        when(orderRepo.findByStatusAndCreatedAtAfter(eq(OrderStatus.FILLED), any())).thenReturn(List.of(v1, v2));

        var report = service.reportByStrategyVersion(null, 30);

        assertThat(report.get("v1").totalOrdersConsidered()).isEqualTo(1);
        assertThat(report.get("v2").totalOrdersConsidered()).isEqualTo(1);
    }
}
