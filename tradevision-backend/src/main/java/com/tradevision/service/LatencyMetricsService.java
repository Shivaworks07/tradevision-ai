package com.tradevision.service;

import com.tradevision.model.Order;
import com.tradevision.model.Position;
import com.tradevision.repository.OrderRepository;
import com.tradevision.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes execution latency statistics across the stages of the order lifecycle:
 * signal to risk acceptance, risk acceptance to submit, submit to broker ack, broker ack
 * to first fill, and first fill to protection placed. Order already carries every
 * timestamp this needs, so the per-stage durations fall out directly from its fields.
 *
 * SCOPE:
 * - Breakdown by broker is not supported (Order has no broker field directly; it's only
 *   reachable via credentialId, which this service doesn't join against).
 * - Breakdown by strategy version is supported via reportByStrategyVersion below.
 * - Breakdown by symbol is supported directly; breakdown by time of day is left to callers,
 *   who can derive it from createdAt rather than have it pre-aggregated here.
 * - Only orders that reached both timestamps of a given stage contribute a sample for that
 *   stage — an order still in SUBMITTING with no brokerAckAt yet simply contributes no
 *   submitToAck sample, rather than counting as zero or being dropped from every stage.
 * - This covers the entry-order path only; OCO/exit-side latency isn't tracked on Order.
 */
@Service
@RequiredArgsConstructor
public class LatencyMetricsService {

    private final OrderRepository orderRepo;
    private final PositionRepository positionRepo;

    public record LatencyStats(double avgMs, long p50Ms, long p95Ms, long p99Ms, long maxMs, int sampleCount) {
        static LatencyStats empty() {
            return new LatencyStats(0, 0, 0, 0, 0, 0);
        }
    }

    public record ExecutionLatencyReport(
        LatencyStats signalToRisk,
        LatencyStats riskToSubmit,
        LatencyStats submitToAck,
        LatencyStats ackToFirstFill,
        LatencyStats fillToProtection,
        int totalOrdersConsidered
    ) {}

    /** Computes the full report over FILLED orders created within the given lookback window. */
    public ExecutionLatencyReport reportForSymbol(String symbol, int lookbackDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(lookbackDays);
        // Apply the lookback cutoff at the query level so we only ever pull the orders this
        // report actually needs, rather than fetching every FILLED order in the database.
        List<Order> orders = orderRepo.findByStatusAndCreatedAtAfter(com.tradevision.model.OrderStatus.FILLED, cutoff).stream()
            .filter(o -> symbol == null || symbol.equalsIgnoreCase(o.getSymbol()))
            .toList();
        return buildReport(orders);
    }

    /**
     * Same report as {@link #reportForSymbol}, grouped by strategy version so latency can be
     * compared across distinct strategy iterations. Orders with no strategy version recorded
     * are excluded, since they can't be attributed to a group.
     */
    public java.util.Map<String, ExecutionLatencyReport> reportByStrategyVersion(String symbol, int lookbackDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(lookbackDays);
        // Cutoff applied at the query level, same as reportForSymbol above.
        List<Order> orders = orderRepo.findByStatusAndCreatedAtAfter(com.tradevision.model.OrderStatus.FILLED, cutoff).stream()
            .filter(o -> symbol == null || symbol.equalsIgnoreCase(o.getSymbol()))
            .filter(o -> o.getStrategyVersion() != null)
            .toList();
        return orders.stream()
            .collect(java.util.stream.Collectors.groupingBy(Order::getStrategyVersion))
            .entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey, e -> buildReport(e.getValue())));
    }

    ExecutionLatencyReport buildReport(List<Order> orders) {
        return new ExecutionLatencyReport(
            computeStats(orders, Order::getCreatedAt, Order::getRiskAcceptedAt),
            computeStats(orders, Order::getRiskAcceptedAt, Order::getSubmitStartedAt),
            computeStats(orders, Order::getSubmitStartedAt, Order::getBrokerAckAt),
            computeStats(orders, Order::getBrokerAckAt, Order::getFirstFillAt),
            computeStats(orders, Order::getFirstFillAt, Order::getProtectionPlacedAt),
            orders.size()
        );
    }

    /**
     * A deliberately coarse exit-side metric: how long a position stays open, end to end
     * (openedAt to closedAt), rather than a granular per-stage breakdown like Order gets on
     * entry. This is the exit-side latency that's cleanly measurable without further OMS
     * integration on the exit path; it does not separately break out OCO acknowledgment,
     * cancel latency, or emergency-flatten latency, since those would need additional
     * timestamp fields threaded through the position monitor/safety exit paths.
     *
     * Entry-to-first-protection latency is tracked separately — see entryToProtectionReport
     * below.
     */
    public LatencyStats positionLifetimeReport(String symbol, int lookbackDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(lookbackDays);
        // Cutoff applied at the query level so this only loads positions closed within the
        // requested window, rather than every closed position ever recorded.
        List<Position> closed = positionRepo.findByStatusAndClosedAtAfter("CLOSED", cutoff).stream()
            .filter(p -> symbol == null || symbol.equalsIgnoreCase(p.getSymbol()))
            .toList();
        List<Long> durationsMs = new ArrayList<>();
        for (Position p : closed) {
            if (p.getOpenedAt() == null || p.getClosedAt() == null) continue; // never fabricate a duration from a missing timestamp
            long ms = Duration.between(p.getOpenedAt(), p.getClosedAt()).toMillis();
            if (ms >= 0) durationsMs.add(ms);
        }
        return computeStatsFromDurations(durationsMs);
    }

    /**
     * Measures entry (Position.openedAt) to first protection placed (Position.ocoPlacedAt),
     * covering both OMS-tracked and non-OMS-tracked positions alike, unlike the entry-order-only
     * reportForSymbol above. Unlike positionLifetimeReport, this isn't scoped to CLOSED
     * positions only — a still-open position that already has protection placed is a complete
     * sample for this metric even though the position itself hasn't closed yet.
     */
    public LatencyStats entryToProtectionReport(String symbol, int lookbackDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(lookbackDays);
        // Bounded at the database level to the lookback window, rather than loading every
        // position ever recorded.
        List<Position> candidates = positionRepo.findByOpenedAtAfter(cutoff).stream()
            .filter(p -> symbol == null || symbol.equalsIgnoreCase(p.getSymbol()))
            .filter(p -> p.getOcoPlacedAt() != null) // never fabricate — only positions that genuinely got protection contribute
            .toList();
        List<Long> durationsMs = new ArrayList<>();
        for (Position p : candidates) {
            long ms = Duration.between(p.getOpenedAt(), p.getOcoPlacedAt()).toMillis();
            if (ms >= 0) durationsMs.add(ms);
        }
        return computeStatsFromDurations(durationsMs);
    }

    private LatencyStats computeStats(List<Order> orders, java.util.function.Function<Order, LocalDateTime> start,
                                       java.util.function.Function<Order, LocalDateTime> end) {
        List<Long> durationsMs = new ArrayList<>();
        for (Order o : orders) {
            LocalDateTime s = start.apply(o);
            LocalDateTime e = end.apply(o);
            if (s == null || e == null) continue; // this order never reached this stage — not a zero, just not counted
            long ms = Duration.between(s, e).toMillis();
            if (ms >= 0) durationsMs.add(ms); // a negative duration would mean corrupted/out-of-order timestamps — excluded, not fabricated as zero
        }
        return computeStatsFromDurations(durationsMs);
    }

    LatencyStats computeStatsFromDurations(List<Long> durationsMs) {
        if (durationsMs.isEmpty()) return LatencyStats.empty();
        List<Long> sorted = new ArrayList<>(durationsMs);
        sorted.sort(Long::compareTo);
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        return new LatencyStats(avg, percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99),
            sorted.get(sorted.size() - 1), sorted.size());
    }

    private long percentile(List<Long> sortedAscending, int p) {
        int n = sortedAscending.size();
        // p/100.0*n computed via floating-point division-then-multiplication can land a hair
        // above an exact integer boundary (e.g. 95.00000000000001 instead of 95.0) due to how
        // IEEE 754 represents fractions like 0.95, which would make Math.ceil round up to the
        // wrong index. Subtracting a tiny epsilon before ceiling absorbs that class of error
        // without meaningfully affecting any genuinely non-boundary percentile.
        int index = (int) Math.ceil(p / 100.0 * n - 1e-9) - 1;
        index = Math.max(0, Math.min(index, n - 1));
        return sortedAscending.get(index);
    }
}
