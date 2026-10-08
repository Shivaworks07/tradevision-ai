package com.tradevision.service;

import com.tradevision.dto.ApiResponse;
import com.tradevision.model.TradeCallRecord;
import com.tradevision.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository      userRepo;
    private final TradeCallRepository callRepo;
    private final ApiMetricRepository metricRepo;

    /**
     * Full platform dashboard snapshot.
     * Called at most once per minute — results should be cached on frontend.
     */
    public ApiResponse<?> getDashboard() {
        LocalDateTime now    = LocalDateTime.now();
        LocalDateTime h24    = now.minusDays(1);
        LocalDateTime d7     = now.minusDays(7);
        LocalDateTime d30    = now.minusDays(30);

        // ── Users ─────────────────────────────────────────────
        long totalUsers      = userRepo.count();
        long activeToday     = userRepo.countByLastLoginAfter(h24);
        long activeWeek      = userRepo.countByLastLoginAfter(d7);
        long newThisMonth    = userRepo.countByCreatedAtAfter(d30);
        long adminCount      = userRepo.countByRole("ADMIN");

        // ── Signals ───────────────────────────────────────────
        long totalSignals    = callRepo.count();
        long signalsToday    = callRepo.countByCalledAtAfter(h24);
        long signalsWeek     = callRepo.countByCalledAtAfter(d7);
        long totalWins       = callRepo.countByOutcome_ResultStartingWith("HIT_T");
        long totalSL         = callRepo.countByOutcome_ResultStartingWith("HIT_SL");
        long totalResolved   = totalWins + totalSL;
        double overallWR     = totalResolved > 0 ? Math.round(totalWins * 1000.0 / totalResolved) / 10.0 : 0;

        // ── Top symbols ───────────────────────────────────────
        List<TradeCallRecord> recent = callRepo
            .findByCalledAtAfterOrderByCalledAtDesc(d30, PageRequest.of(0, 1000));

        Map<String,Long> symCount = recent.stream()
            .collect(Collectors.groupingBy(
                r -> r.getSymbol() != null ? r.getSymbol() : "UNKNOWN",
                Collectors.counting()
            ));
        List<Map<String,Object>> topSymbols = symCount.entrySet().stream()
            .sorted(Map.Entry.<String,Long>comparingByValue().reversed())
            .limit(10)
            .map(e -> Map.of("symbol", (Object)e.getKey(), "calls", (Object)e.getValue()))
            .collect(Collectors.toList());

        // ── Market breakdown ──────────────────────────────────
        Map<String,Long> byMarket = recent.stream()
            .collect(Collectors.groupingBy(
                r -> r.getMarket() != null ? r.getMarket() : "UNKNOWN",
                Collectors.counting()
            ));

        // ── Signal accuracy by market ─────────────────────────
        Map<String, Map<String,Object>> accuracyByMarket = new HashMap<>();
        for (String market : List.of("CRYPTO","STOCK","FOREX")) {
            List<TradeCallRecord> mktCalls = recent.stream()
                .filter(r -> market.equals(r.getMarket()) && r.getOutcome() != null
                          && r.getOutcome().getResult() != null
                          && !r.getOutcome().getResult().equals("PENDING"))
                .collect(Collectors.toList());
            long mktWins = mktCalls.stream()
                .filter(r -> r.getOutcome().getResult().startsWith("HIT_T")).count();
            double mktWR = mktCalls.size() > 0
                ? Math.round(mktWins * 1000.0 / mktCalls.size()) / 10.0 : 0;
            accuracyByMarket.put(market, Map.of(
                "total", mktCalls.size(), "wins", mktWins, "winRate", mktWR
            ));
        }

        // Strategy-level performance attribution: same win-rate computation as the by-market
        // segment above, grouped by planId instead, over the same 30-day/1000-record bounded
        // window (`recent`). Lets an operator running several StrategyPlans see which one is
        // actually performing. Calls with no planId (manually-entered signals, or ones from
        // before this field existed) are grouped under "MANUAL_OR_UNATTRIBUTED" rather than
        // silently dropped.
        Map<String, Map<String,Object>> accuracyByPlan = recent.stream()
            .filter(r -> r.getOutcome() != null && r.getOutcome().getResult() != null
                      && !r.getOutcome().getResult().equals("PENDING"))
            .collect(Collectors.groupingBy(r -> r.getPlanId() != null ? r.getPlanId() : "MANUAL_OR_UNATTRIBUTED"))
            .entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                List<TradeCallRecord> planCalls = e.getValue();
                long planWins = planCalls.stream().filter(r -> r.getOutcome().getResult().startsWith("HIT_T")).count();
                double planWR = planCalls.size() > 0 ? Math.round(planWins * 1000.0 / planCalls.size()) / 10.0 : 0;
                return Map.of("total", planCalls.size(), "wins", planWins, "winRate", planWR);
            }));

        // ── API metrics ───────────────────────────────────────
        long reqTotal24h   = metricRepo.countByRecordedAtAfter(h24);
        long errTotal24h   = metricRepo.countByErrorTrueAndRecordedAtAfter(h24);
        double errorRate   = reqTotal24h > 0 ? Math.round(errTotal24h * 1000.0 / reqTotal24h) / 10.0 : 0;

        // Average latency from recent sample (last 500 requests)
        List<com.tradevision.model.ApiMetric> metrics = metricRepo.findByRecordedAtAfter(h24);
        double avgLatency = metrics.stream()
            .mapToLong(com.tradevision.model.ApiMetric::getLatencyMs)
            .average().orElse(0);
        double p95Latency = percentile(
            metrics.stream().mapToLong(com.tradevision.model.ApiMetric::getLatencyMs).sorted().toArray(),
            95
        );

        // ── Endpoint breakdown ────────────────────────────────
        Map<String,Long> byEndpoint = metrics.stream()
            .collect(Collectors.groupingBy(
                m -> m.getMethod() + " " + m.getEndpoint(),
                Collectors.counting()
            ));
        List<Map<String,Object>> topEndpoints = byEndpoint.entrySet().stream()
            .sorted(Map.Entry.<String,Long>comparingByValue().reversed())
            .limit(8)
            .map(e -> Map.of("endpoint", (Object)e.getKey(), "calls", (Object)e.getValue()))
            .collect(Collectors.toList());

        // ── Hourly signal volume (last 24h) ───────────────────
        Map<Integer,Long> hourlySignals = recent.stream()
            .filter(r -> r.getCalledAt() != null && r.getCalledAt().isAfter(h24))
            .collect(Collectors.groupingBy(
                r -> r.getCalledAt().getHour(),
                Collectors.counting()
            ));

        // ── Build response ────────────────────────────────────
        Map<String,Object> dashboard = new LinkedHashMap<>();

        dashboard.put("users", Map.of(
            "total",        totalUsers,
            "activeToday",  activeToday,
            "activeWeek",   activeWeek,
            "newThisMonth", newThisMonth,
            "admins",       adminCount
        ));
        dashboard.put("signals", Map.of(
            "total",        totalSignals,
            "today",        signalsToday,
            "thisWeek",     signalsWeek,
            "wins",         totalWins,
            "losses",       totalSL,
            "overallWinRate", overallWR,
            "accuracyByMarket", accuracyByMarket,
            "accuracyByPlan", accuracyByPlan
        ));
        dashboard.put("topSymbols",   topSymbols);
        dashboard.put("byMarket",     byMarket);
        dashboard.put("hourlyVolume", hourlySignals);
        dashboard.put("api", Map.of(
            "requests24h",  reqTotal24h,
            "errors24h",    errTotal24h,
            "errorRatePct", errorRate,
            "avgLatencyMs", Math.round(avgLatency),
            "p95LatencyMs", Math.round(p95Latency),
            "topEndpoints", topEndpoints
        ));
        dashboard.put("generatedAt", now.toString());

        return ApiResponse.ok("Dashboard loaded", dashboard);
    }

    // ── Helpers ───────────────────────────────────────────────
    private double percentile(long[] sorted, int pct) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }
}
