package com.tradevision.service;

import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Tracks broker/exchange connectivity health, keyed per-credential by a hash of the API key
 * (the value actually available at every call site, without threading a credentialId through
 * every adapter method signature) so that one credential's broken key or regional network issue
 * doesn't make the whole app look unhealthy for every other credential.
 *
 * <p>{@link #check} judges REST call outcomes only; WebSocket health, request-weight budget, and
 * per-symbol rejection tracking below are each recorded and queryable separately and never fold
 * into {@code check()}'s own healthy/unhealthy verdict, since each is its own independent axis.
 *
 * <p>All of this state is durable, MongoDB-backed storage rather than purely in-memory, since
 * every metric here only feeds the health dashboard and never gates a trading decision -- so the
 * added latency of a Mongo round trip per record only affects dashboard freshness. Storing every
 * individual call outcome (a literal sliding window of recent calls) would mean either an array
 * push-and-trim on every broker call, which is contention-prone under concurrent writers to the
 * same document, or many separate per-window documents, which is expensive to query -- neither
 * worth it for a dashboard number. Instead this uses a fixed-window aggregate-counter pattern
 * (the same one used by {@code DistributedRateLimitService}): one atomic {@code $inc} per call,
 * with the window resetting on a time boundary rather than a call count. This means the signal is
 * "calls within the last N minutes" rather than "the last N calls" -- for a health check whose
 * purpose is "is the exchange broken right now," a recent time window is arguably the more
 * honest signal, since a fixed call count can span anywhere from minutes to milliseconds
 * depending on trading volume.
 */
@Service
@lombok.RequiredArgsConstructor
public class ExchangeHealthService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExchangeHealthService.class);
    private static final double MAX_ERROR_RATE = 0.5;
    private static final long MAX_AVG_LATENCY_MS = 3000;
    private static final long WINDOW_SECONDS = 300; // 5-minute rolling window for aggregate health counters -- see class javadoc
    private static final int MIN_SAMPLE_SIZE = 5; // below this, report healthy: not enough data yet to judge

    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    public record HealthStatus(boolean healthy, String reason, double errorRate, double avgLatencyMs, int sampleSize) {}

    /** apiKey is hashed before use as a Mongo document id — never stored or logged in plaintext, even here. */
    private String keyFor(String apiKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "unkeyed"; // falls back to a shared bucket only if hashing itself somehow fails
        }
    }

    /**
     * {@link #record} is called after every broker REST call ({@code BinanceBrokerAdapter} calls
     * it on every public and private endpoint call), so it must stay off the hot path: it only
     * touches an in-memory, per-key {@code ConcurrentHashMap} of pending deltas (a handful of
     * atomic increments, no I/O), and a periodic {@code @Scheduled} {@link
     * #flushPendingHealthDeltas} drains those deltas into MongoDB using the same
     * reset-then-increment logic, just far less often. This means a broker call under load pays
     * zero Mongo latency for health bookkeeping, and {@link #check}'s dashboard verdict is at
     * most one flush interval stale, which is immaterial against the 5-minute window this signal
     * aggregates over.
     */
    private static final long FLUSH_INTERVAL_MS = 15_000; // dashboard-only signal; staleness up to this interval is immaterial against the 5-minute window
    private final java.util.concurrent.ConcurrentHashMap<String, PendingHealthDelta> pendingHealthDeltas = new java.util.concurrent.ConcurrentHashMap<>();

    private record PendingHealthDelta(java.util.concurrent.atomic.AtomicInteger successCount,
                                       java.util.concurrent.atomic.AtomicInteger failureCount,
                                       java.util.concurrent.atomic.AtomicLong totalLatencyMs) {
        static PendingHealthDelta empty() {
            return new PendingHealthDelta(new java.util.concurrent.atomic.AtomicInteger(),
                new java.util.concurrent.atomic.AtomicInteger(), new java.util.concurrent.atomic.AtomicLong());
        }
    }

    public void record(String apiKey, boolean success, long latencyMs) {
        var delta = pendingHealthDeltas.computeIfAbsent(keyFor(apiKey), k -> PendingHealthDelta.empty());
        if (success) delta.successCount().incrementAndGet(); else delta.failureCount().incrementAndGet();
        delta.totalLatencyMs().addAndGet(latencyMs);
    }

    /**
     * Drains every key's accumulated in-memory delta into MongoDB. {@code getAndSet(0)} on each
     * of the three independent atomic fields is not one compound atomic snapshot, but that's
     * fine here: a {@link #record} call that lands concurrently with a drain either gets captured
     * by this flush (if its increment happens before the {@code getAndSet}) or the next one (if
     * after) -- no delta is ever lost or double-counted, which is all a dashboard aggregate
     * needs. Also invoked directly on shutdown (see the {@code @PreDestroy} method below) so the
     * last, sub-interval sliver of data isn't dropped on restart.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = FLUSH_INTERVAL_MS, scheduler = "maintenanceScheduler")
    void flushPendingHealthDeltas() {
        for (var entry : pendingHealthDeltas.entrySet()) {
            String key = entry.getKey();
            var delta = entry.getValue();
            int successDelta = delta.successCount().getAndSet(0);
            int failureDelta = delta.failureCount().getAndSet(0);
            long latencyDelta = delta.totalLatencyMs().getAndSet(0);
            if (successDelta == 0 && failureDelta == 0) continue; // nothing accumulated for this key this cycle

            var query = new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(key));
            try {
                var existing = mongoTemplate.findOne(query, org.bson.Document.class, "exchange_health_rest");
                Instant windowStart = existing != null && existing.get("windowStart") != null
                    ? ((java.util.Date) existing.get("windowStart")).toInstant() : null;
                if (windowStart == null || Instant.now().isAfter(windowStart.plusSeconds(WINDOW_SECONDS))) {
                    mongoTemplate.upsert(query,
                        new org.springframework.data.mongodb.core.query.Update()
                            .set("windowStart", java.util.Date.from(Instant.now()))
                            .set("successCount", 0).set("failureCount", 0).set("totalLatencyMs", 0L),
                        "exchange_health_rest");
                }
                var update = new org.springframework.data.mongodb.core.query.Update()
                    .inc("successCount", successDelta)
                    .inc("failureCount", failureDelta)
                    .inc("totalLatencyMs", latencyDelta);
                mongoTemplate.upsert(query, update, "exchange_health_rest");
            } catch (Exception e) {
                // Dashboard-only metric: a transient Mongo failure here must never propagate and
                // disrupt anything else on this shared scheduler pool. The delta is already gone
                // (getAndSet already reset it to 0 above), so this interval's data is lost -- an
                // acceptable trade-off for a health signal, not worth adding retry complexity for.
                log.warn("Could not flush exchange-health delta for key {} to MongoDB (dashboard-only, non-fatal): {}", key, e.getMessage());
            }
        }
    }

    @jakarta.annotation.PreDestroy
    void flushOnShutdown() {
        flushPendingHealthDeltas();
    }

    public HealthStatus check(String apiKey) {
        var query = new org.springframework.data.mongodb.core.query.Query(
            org.springframework.data.mongodb.core.query.Criteria.where("_id").is(keyFor(apiKey)));
        var doc = mongoTemplate.findOne(query, org.bson.Document.class, "exchange_health_rest");
        int successCount = doc != null && doc.getInteger("successCount") != null ? doc.getInteger("successCount") : 0;
        int failureCount = doc != null && doc.getInteger("failureCount") != null ? doc.getInteger("failureCount") : 0;
        long totalLatencyMs = doc != null && doc.get("totalLatencyMs") != null ? ((Number) doc.get("totalLatencyMs")).longValue() : 0L;
        int sampleSize = successCount + failureCount;

        if (sampleSize < MIN_SAMPLE_SIZE) {
            return new HealthStatus(true, null, 0, 0, sampleSize);
        }
        double errorRate = (double) failureCount / sampleSize;
        double avgLatency = (double) totalLatencyMs / sampleSize;

        if (errorRate > MAX_ERROR_RATE) {
            return new HealthStatus(false, "Broker call error rate " + Math.round(errorRate * 100)
                + "% over the last " + sampleSize + " calls exceeds the " + Math.round(MAX_ERROR_RATE * 100) + "% threshold",
                errorRate, avgLatency, sampleSize);
        }
        if (avgLatency > MAX_AVG_LATENCY_MS) {
            return new HealthStatus(false, "Average broker call latency " + Math.round(avgLatency) + "ms exceeds "
                + MAX_AVG_LATENCY_MS + "ms threshold", errorRate, avgLatency, sampleSize);
        }
        return new HealthStatus(true, null, errorRate, avgLatency, sampleSize);
    }

    // ── WebSocket health ──────────────────────────────────────────────────────────────────
    // Mongo-backed storage, same as the REST section above, but a single small document per
    // credential (state, detail, two timestamps) rather than a sliding window, so this is a
    // plain read-then-write rather than the aggregate-counter pattern used elsewhere.

    public enum WsState { CONNECTED, DISCONNECTED, ERROR }
    // How recently a message must have arrived for a CONNECTED stream to still count as
    // genuinely healthy — Binance's own user-data stream sends at least a keepalive/ping well
    // within this window under normal operation; a connection that's open but silent this long
    // is a real, distinct concern from one that's cleanly closed.
    private static final java.time.Duration WS_MESSAGE_STALE_AFTER = java.time.Duration.ofMinutes(5);

    public void recordWsConnected(String credentialId) {
        upsertWsDoc(credentialId, new org.springframework.data.mongodb.core.query.Update()
            .set("state", WsState.CONNECTED.name()).set("detail", null).set("stateAt", java.util.Date.from(Instant.now())));
    }

    public void recordWsDisconnected(String credentialId, String reason) {
        upsertWsDoc(credentialId, new org.springframework.data.mongodb.core.query.Update()
            .set("state", WsState.DISCONNECTED.name()).set("detail", reason).set("stateAt", java.util.Date.from(Instant.now()))
            .unset("lastMessageAt"));
    }

    public void recordWsError(String credentialId, String error) {
        upsertWsDoc(credentialId, new org.springframework.data.mongodb.core.query.Update()
            .set("state", WsState.ERROR.name()).set("detail", error).set("stateAt", java.util.Date.from(Instant.now())));
    }

    public void recordWsMessageReceived(String credentialId) {
        upsertWsDoc(credentialId, new org.springframework.data.mongodb.core.query.Update()
            .set("lastMessageAt", java.util.Date.from(Instant.now())));
    }

    private void upsertWsDoc(String credentialId, org.springframework.data.mongodb.core.query.Update update) {
        mongoTemplate.upsert(
            new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(credentialId)),
            update, "exchange_health_ws");
    }

    public record WsHealthStatus(boolean healthy, String reason, String state, Instant lastMessageAt) {}

    public WsHealthStatus checkWs(String credentialId) {
        var doc = mongoTemplate.findOne(
            new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(credentialId)),
            org.bson.Document.class, "exchange_health_ws");
        if (doc == null || doc.getString("state") == null) {
            return new WsHealthStatus(false, "No WebSocket connection has ever been recorded for this credential.", "UNKNOWN", null);
        }
        String state = doc.getString("state");
        String detail = doc.getString("detail");
        if (!WsState.CONNECTED.name().equals(state)) {
            return new WsHealthStatus(false, state + (detail != null ? ": " + detail : ""), state, null);
        }
        Instant lastMessage = doc.get("lastMessageAt") != null ? ((java.util.Date) doc.get("lastMessageAt")).toInstant() : null;
        if (lastMessage == null || lastMessage.isBefore(Instant.now().minus(WS_MESSAGE_STALE_AFTER))) {
            return new WsHealthStatus(false, "Connected, but no message received in over " + WS_MESSAGE_STALE_AFTER.toMinutes()
                + " minutes — the connection may be silently dead.", "CONNECTED", lastMessage);
        }
        return new WsHealthStatus(true, null, "CONNECTED", lastMessage);
    }

    // ── Request-weight budget ───────────────────────────────────────────────────────────────

    // X-MBX-USED-WEIGHT-1M is the header Binance returns on every REST response for the default
    // 1-minute spot weight limit. This weight is tracked per-IP, not per API key -- weight
    // accumulates per IP address and is shared across all connections from that address -- so it
    // is tracked globally here rather than per-credential: multiple credentials calling from this
    // same server genuinely share one budget, and a per-key abstraction would misrepresent that
    // as separate budgets. Whichever credential's call most recently reported a value is the
    // current, true, shared state, since Binance reports the same cumulative IP-wide number
    // regardless of which key made the call.
    //
    // The real, current REQUEST_WEIGHT limit for a standard spot account is 1200 per minute (not
    // to be confused with the separate "raw requests per 5 minutes" rate-limit category).
    private static final int DEFAULT_SPOT_WEIGHT_LIMIT_PER_MINUTE = 1200;
    // Stored as a single Mongo document, last-write-wins -- the correct semantic here regardless
    // of storage (see the comment above on why this is tracked globally, not per-credential), so
    // this is the simplest possible representation: one document, one field, upsert-set on write.

    public void recordUsedWeight(int usedWeight) {
        mongoTemplate.upsert(
            new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is("global")),
            new org.springframework.data.mongodb.core.query.Update().set("usedWeight", usedWeight),
            "exchange_health_weight");
    }

    public record RequestBudgetStatus(int usedWeight, int limit, double usedFraction, boolean healthy) {}

    public RequestBudgetStatus checkRequestBudget() {
        var doc = mongoTemplate.findOne(
            new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is("global")),
            org.bson.Document.class, "exchange_health_weight");
        int usedWeight = doc != null && doc.getInteger("usedWeight") != null ? doc.getInteger("usedWeight") : 0;
        double fraction = (double) usedWeight / DEFAULT_SPOT_WEIGHT_LIMIT_PER_MINUTE;
        // 90% threshold, same "how close to the actual limit" spirit as this class's own
        // MAX_ERROR_RATE/MAX_AVG_LATENCY_MS thresholds above.
        return new RequestBudgetStatus(usedWeight, DEFAULT_SPOT_WEIGHT_LIMIT_PER_MINUTE, fraction, fraction < 0.90);
    }

    // ── Per-symbol rejection rate ───────────────────────────────────────────────────────────
    // Same fixed-window aggregate-counter pattern as the REST section above.

    public void recordOrderOutcome(String symbol, boolean accepted) {
        if (symbol == null) return;
        var query = new org.springframework.data.mongodb.core.query.Query(
            org.springframework.data.mongodb.core.query.Criteria.where("_id").is(symbol.toUpperCase()));
        var existing = mongoTemplate.findOne(query, org.bson.Document.class, "exchange_health_symbol");
        Instant windowStart = existing != null && existing.get("windowStart") != null
            ? ((java.util.Date) existing.get("windowStart")).toInstant() : null;
        if (windowStart == null || Instant.now().isAfter(windowStart.plusSeconds(WINDOW_SECONDS))) {
            mongoTemplate.upsert(query,
                new org.springframework.data.mongodb.core.query.Update()
                    .set("windowStart", java.util.Date.from(Instant.now()))
                    .set("acceptedCount", 0).set("rejectedCount", 0),
                "exchange_health_symbol");
        }
        mongoTemplate.upsert(query,
            new org.springframework.data.mongodb.core.query.Update().inc(accepted ? "acceptedCount" : "rejectedCount", 1),
            "exchange_health_symbol");
    }

    public record SymbolRejectionStatus(String symbol, double rejectionRate, int sampleSize) {}

    public SymbolRejectionStatus checkSymbolRejectionRate(String symbol) {
        if (symbol == null) return new SymbolRejectionStatus(null, 0, 0);
        var doc = mongoTemplate.findOne(
            new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(symbol.toUpperCase())),
            org.bson.Document.class, "exchange_health_symbol");
        int accepted = doc != null && doc.getInteger("acceptedCount") != null ? doc.getInteger("acceptedCount") : 0;
        int rejected = doc != null && doc.getInteger("rejectedCount") != null ? doc.getInteger("rejectedCount") : 0;
        int sampleSize = accepted + rejected;
        if (sampleSize == 0) return new SymbolRejectionStatus(symbol.toUpperCase(), 0, 0);
        return new SymbolRejectionStatus(symbol.toUpperCase(), (double) rejected / sampleSize, sampleSize);
    }

    // ── Unified composite status ────────────────────────────────────────────────────────────

    /**
     * Folds REST connectivity, WebSocket connectivity, and request-weight budget into a single
     * healthy/unhealthy verdict, listing every contributing reason rather than just the first one
     * found. Per-symbol rejection rate ({@link #checkSymbolRejectionRate}) is deliberately not
     * included here: it is an inherently per-symbol, multi-valued metric rather than a single
     * credential-level or global fact like the other three, so a caller who wants that detail
     * calls {@code checkSymbolRejectionRate} directly for the symbol they care about.
     */
    public record CompositeHealthStatus(boolean healthy, List<String> reasons,
                                         HealthStatus rest, WsHealthStatus webSocket, RequestBudgetStatus requestBudget) {}

    public CompositeHealthStatus checkComposite(String apiKey, String credentialId) {
        HealthStatus restStatus = check(apiKey);
        WsHealthStatus wsStatus = checkWs(credentialId);
        RequestBudgetStatus budgetStatus = checkRequestBudget();

        List<String> reasons = new java.util.ArrayList<>();
        if (!restStatus.healthy() && restStatus.reason() != null) reasons.add("REST: " + restStatus.reason());
        if (!wsStatus.healthy() && wsStatus.reason() != null) reasons.add("WebSocket: " + wsStatus.reason());
        if (!budgetStatus.healthy()) {
            reasons.add("Request budget: " + budgetStatus.usedWeight() + "/" + budgetStatus.limit()
                + " (" + Math.round(budgetStatus.usedFraction() * 100) + "%) — approaching the per-IP limit");
        }
        return new CompositeHealthStatus(reasons.isEmpty(), reasons, restStatus, wsStatus, budgetStatus);
    }
}
