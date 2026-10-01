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
 * Review finding ("#9 — Execution Latency", agreed sequencing 4 -> 6 -> 5 -> 9 -> 7 — "Once those
 * timestamps exist naturally in OMS/Fills/protection, measure... Signal -> Risk -> Order Created
 * -> Submit -> Broker ACK -> First Fill -> Final Fill -> Protection... Then build: average, P50,
 * P95, P99, max, by broker, by symbol, by strategy, by time of day"): this is that calculation
 * — genuinely close to free now, exactly as the review predicted, because Order (#4) already
 * carries every timestamp this needs, and OrderService.recordProtectionPlaced (added alongside
 * this) closes the one gap that didn't already exist.
 *
 * HONEST SCOPE:
 * - "By broker" is NOT implemented — Order doesn't carry a broker field (it's reachable via
 *   credentialId, but this pass doesn't join that in). "By strategy" IS now implemented
 *   (reportByStrategyVersion below) — Order.strategyVersion is genuinely populated now, closing
 *   the gap this comment used to name.
 *   "By symbol" is directly supported; "by time of day" is derivable from createdAt by any
 *   caller of this service, not built as a pre-aggregated bucket here.
 * - Only orders reaching each pair of timestamps contribute to that stage's statistics — an
 *   order stuck in SUBMITTING with no brokerAckAt yet simply doesn't contribute a submitToAck
 *   sample, rather than being counted as zero or excluded from every other stage too.
 * - This measures the entry-order path only, matching everywhere else OMS is wired in this
 *   session — OCO/exit-side latency isn't part of what Order tracks.
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
        // Review finding (unbounded metrics queries -- P2, confirmed real): pushes the cutoff
        // this method already computes into the database query itself, rather than fetching
        // every FILLED order ever placed (across every user) and filtering after.
        List<Order> orders = orderRepo.findByStatusAndCreatedAtAfter(com.tradevision.model.OrderStatus.FILLED, cutoff).stream()
            .filter(o -> symbol == null || symbol.equalsIgnoreCase(o.getSymbol()))
            .toList();
        return buildReport(orders);
    }

    /**
     * Review finding ("Strategy/risk-profile/feature versioning fields exist but are
     * unused/null" -- P1, full context in OrderService.create's own STRATEGY_VERSION comment):
     * confirmed real and fixed -- Order.strategyVersion is now genuinely populated, closing the
     * gap this class's own "HONEST SCOPE" javadoc used to name explicitly ("by strategy" was not
     * implemented). Grouped by whatever distinct version strings actually appear in the data,
     * same reasoning as SlippageMetricsService's own identical addition.
     */
    public java.util.Map<String, ExecutionLatencyReport> reportByStrategyVersion(String symbol, int lookbackDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(lookbackDays);
        // Review finding (unbounded metrics queries -- P2, full context in reportForSymbol's
        // own identical fix above): same fix, same reasoning.
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
     * Review finding ("Execution" — "exit latency, OCO latency, cancel latency, emergency
     * flatten latency"): a genuine, but deliberately COARSE metric — position lifetime
     * (openedAt -> closedAt), not the granular per-stage breakdown Order provides for entries.
     * HONEST SCOPE: this is the one exit-side metric actually cleanly measurable without further
     * OMS integration on the exit path — see OrderService's own javadoc for why OCO/resize/
     * emergency-flatten remain outside OMS in this pass. It does NOT separately break out OCO
     * acknowledgment, cancel latency, or emergency-flatten latency — those would need new
     * timestamp fields threaded through PositionMonitorService/PositionSafetyService's exit
     * paths beyond what this pass adds, the same scope this session has repeatedly declined to
     * touch blind.
     *
     * UPDATE ("Execution latency still entry-focused" review): entry-to-first-protection latency
     * IS now tracked — see entryToProtectionReport below, backed by Position.ocoPlacedAt (that
     * field's own javadoc has the full reasoning for why it's bounded to first-placement only).
     */
    public LatencyStats positionLifetimeReport(String symbol, int lookbackDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(lookbackDays);
        // Review finding (unbounded metrics queries -- P2): same fix as the two orderRepo sites
        // above -- pushes the cutoff into the database query rather than fetching every closed
        // position ever and filtering after.
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
     * Review finding ("Execution latency still entry-focused" — "OCO submit/ack... timeline are
     * not a complete lifecycle analytics model"): the bounded piece of that gap this pass closes
     * — entry (Position.openedAt) to first protection placed (Position.ocoPlacedAt), covering
     * both OMS-tracked and non-OMS-tracked positions alike, unlike LatencyMetricsService's own
     * entry-order-only reportForSymbol above. Not scoped to CLOSED positions only (unlike
     * positionLifetimeReport above) — an open position with protection already placed is a
     * complete, real sample for this specific metric even though the position itself isn't done.
     */
    public LatencyStats entryToProtectionReport(String symbol, int lookbackDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(lookbackDays);
        // Review finding (unbounded metrics queries -- P2): confirmed the most severe of the 4
        // sites in this file -- findAll() with no filter at all, literally every position ever
        // recorded in the entire database, before this fix. Now bounded at the database level.
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
        // Review finding (caught while writing this method's own tests, not after): p/100.0*n
        // computed via floating-point division-then-multiplication can land a hair above an
        // exact integer boundary (e.g. 95.00000000000001 instead of 95.0) due to how IEEE 754
        // represents fractions like 0.95 — Math.ceil would then round UP to the wrong index.
        // Subtracting a tiny epsilon before ceiling absorbs exactly that class of error without
        // meaningfully affecting any genuinely non-boundary percentile.
        int index = (int) Math.ceil(p / 100.0 * n - 1e-9) - 1;
        index = Math.max(0, Math.min(index, n - 1));
        return sortedAscending.get(index);
    }
}
