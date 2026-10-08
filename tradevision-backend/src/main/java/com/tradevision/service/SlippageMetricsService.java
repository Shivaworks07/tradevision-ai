package com.tradevision.service;

import com.tradevision.model.Order;
import com.tradevision.model.OrderStatus;
import com.tradevision.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures execution slippage — the gap between a signal's expected price and what an order
 * actually filled at — using Order's own requestedPrice (repurposed as the expected/signal price
 * — see Order's own field comment) and averageFillPrice.
 *
 * Slippage is expressed in basis points (1 bp = 0.01%), positive meaning the fill was WORSE than
 * expected (paid more for a BUY) and negative meaning it was better — the sign matters and is
 * preserved, not just the magnitude, so "average slippage" can't hide a consistent directional
 * bias behind cancellation from a few good and bad fills.
 *
 * Scope:
 * - By strategy version: reportByStrategyVersion below, keyed by Order.strategyVersion
 *   (OrderService.create's own STRATEGY_VERSION constant). Strategy versioning itself is
 *   deliberately simple: a manually-bumped constant, not an automated build-hash system — see
 *   that constant's own javadoc for why.
 * - By volatility: reportByVolatilityBucket below, using Order.volatilityAtEntry (ATR as % of
 *   price at signal time, stamped via OrderService.recordVolatility — see that method's own
 *   javadoc).
 * - By symbol: reportForSymbol.
 * - Only entry orders are measured — the same scope boundary as everywhere else OMS is wired.
 * - An order with a null requestedPrice (e.g. one created before this field was populated) is
 *   excluded from the statistics entirely, not treated as zero slippage.
 */
@Service
@RequiredArgsConstructor
public class SlippageMetricsService {

    private final OrderRepository orderRepo;

    // Fixed thresholds for ATR-as-percent-of-price on hourly candles — a simple heuristic
    // bucketing, not a statistically-derived one. LOW/MEDIUM/HIGH match typical BTC/ETH ranges on
    // this timeframe; a genuinely different symbol universe (much higher-volatility altcoins, a
    // different timeframe) would need different thresholds.
    private static final double LOW_VOLATILITY_THRESHOLD = 1.0;
    private static final double HIGH_VOLATILITY_THRESHOLD = 3.0;

    public record SlippageStats(double avgBps, long p50Bps, long p95Bps, long p99Bps, long maxBps, int sampleCount) {
        static SlippageStats empty() {
            return new SlippageStats(0, 0, 0, 0, 0, 0);
        }
    }

    public record VolatilityBucketedReport(SlippageStats lowVolatility, SlippageStats mediumVolatility,
                                            SlippageStats highVolatility, int ordersExcludedNoVolatilityData) {}

    public SlippageStats reportForSymbol(String symbol, int lookbackDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(lookbackDays);
        // Pushes the cutoff into the database query itself, rather than fetching every FILLED
        // order ever placed and filtering after.
        List<Order> orders = orderRepo.findByStatusAndCreatedAtAfter(OrderStatus.FILLED, cutoff).stream()
            .filter(o -> symbol == null || symbol.equalsIgnoreCase(o.getSymbol()))
            .toList();
        return computeStats(orders);
    }

    /**
     * Buckets orders by volatilityAtEntry before computing slippage stats separately for each
     * bucket — this is what actually answers "does slippage get worse in volatile conditions",
     * not just an overall average that could hide that relationship entirely.
     */
    public VolatilityBucketedReport reportByVolatilityBucket(String symbol, int lookbackDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(lookbackDays);
        // Cutoff pushed into the query itself, same as reportForSymbol above.
        List<Order> orders = orderRepo.findByStatusAndCreatedAtAfter(OrderStatus.FILLED, cutoff).stream()
            .filter(o -> symbol == null || symbol.equalsIgnoreCase(o.getSymbol()))
            .toList();

        List<Order> low = new ArrayList<>(), medium = new ArrayList<>(), high = new ArrayList<>();
        int excluded = 0;
        for (Order o : orders) {
            if (o.getVolatilityAtEntry() == null) { excluded++; continue; }
            double v = o.getVolatilityAtEntry();
            if (v < LOW_VOLATILITY_THRESHOLD) low.add(o);
            else if (v > HIGH_VOLATILITY_THRESHOLD) high.add(o);
            else medium.add(o);
        }
        return new VolatilityBucketedReport(computeStats(low), computeStats(medium), computeStats(high), excluded);
    }

    /**
     * Groups slippage stats by strategy version. Unlike volatility (a continuous value bucketed
     * into 3 fixed ranges), strategy version is a discrete, open set of string values — grouped
     * into a map keyed by whatever versions actually appear in the data, rather than forced into
     * a fixed shape that would break the moment a second strategy version exists.
     */
    public java.util.Map<String, SlippageStats> reportByStrategyVersion(String symbol, int lookbackDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(lookbackDays);
        // Cutoff pushed into the query itself, same as reportForSymbol above.
        List<Order> orders = orderRepo.findByStatusAndCreatedAtAfter(OrderStatus.FILLED, cutoff).stream()
            .filter(o -> symbol == null || symbol.equalsIgnoreCase(o.getSymbol()))
            .filter(o -> o.getStrategyVersion() != null) // an order with no known version is excluded, not lumped into a misleading "null" bucket
            .toList();
        return orders.stream()
            .collect(java.util.stream.Collectors.groupingBy(Order::getStrategyVersion))
            .entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey, e -> computeStats(e.getValue())));
    }

    SlippageStats computeStats(List<Order> orders) {
        List<Long> slippagesBps = new ArrayList<>();
        for (Order o : orders) {
            Long bps = slippageBps(o);
            if (bps != null) slippagesBps.add(bps);
        }
        if (slippagesBps.isEmpty()) return SlippageStats.empty();
        slippagesBps.sort(Long::compareTo);
        double avg = slippagesBps.stream().mapToLong(Long::longValue).average().orElse(0);
        int n = slippagesBps.size();
        return new SlippageStats(avg, percentile(slippagesBps, 50), percentile(slippagesBps, 95),
            percentile(slippagesBps, 99), slippagesBps.get(n - 1), n);
    }

    /** Positive = fill worse than expected (paid more on a BUY), negative = better. Null if not computable. */
    Long slippageBps(Order o) {
        if (o.getRequestedPrice() == null || o.getRequestedPrice().signum() <= 0 || o.getAverageFillPrice() == null) return null;
        BigDecimal deltaRatio = o.getAverageFillPrice().subtract(o.getRequestedPrice())
            .divide(o.getRequestedPrice(), 8, RoundingMode.HALF_UP);
        // basis points = ratio * 10,000
        return deltaRatio.multiply(BigDecimal.valueOf(10_000)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private long percentile(List<Long> sortedAscending, int p) {
        int n = sortedAscending.size();
        // Same floating-point boundary fix as LatencyMetricsService's own percentile method —
        // see that class's comment for the full reasoning.
        int index = (int) Math.ceil(p / 100.0 * n - 1e-9) - 1;
        index = Math.max(0, Math.min(index, n - 1));
        return sortedAscending.get(index);
    }
}
