package com.tradevision.controller;

import com.tradevision.dto.ApiResponse;
import com.tradevision.model.User;
import com.tradevision.repository.UserRepository;
import com.tradevision.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Administrative operations: dashboard metrics, user/role management, ML dataset and audit-log
 * export, order/execution tracing, operational health reports, and first-admin bootstrap.
 *
 * Admin identity checks look the authenticated userId (from SecurityContext, populated by
 * SecurityConfig's jwtFilter) up directly via isAdmin() rather than re-parsing a token.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
// CORS is handled centrally by CorsConfig's global CorsFilter; no per-controller
// @CrossOrigin is needed here.
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AdminService   adminService;
    private final UserRepository userRepo;
    private final com.tradevision.service.MLDatasetExportService mlDatasetExportService;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    // Backs the audit-log export endpoint.
    private final com.tradevision.repository.BrokerAuditLogRepository auditLogRepo;
    // Provides per-symbol and per-strategy-version execution latency reporting.
    private final com.tradevision.service.LatencyMetricsService latencyMetricsService;
    // Backs the incident taxonomy aggregation (counts by type/severity).
    private final com.tradevision.repository.TradingIncidentRepository tradingIncidentRepo;
    // orderRepo backs the strategy-versioned execution provenance lookup; callRepo joins each
    // order's signalId back to its resolved outcome.
    private final com.tradevision.repository.OrderRepository orderRepo;
    private final com.tradevision.repository.TradeCallRepository callRepo;
    // Backs the automated reconciliation report.
    private final com.tradevision.repository.OrphanedOcoRepository orphanedOcoRepo;
    // Backs the recovery-health endpoint's protection-attempt metrics.
    private final com.tradevision.repository.ProtectionAttemptRepository protectionAttemptRepo;
    // Drives the test-notification endpoint, which exercises the same incident/delivery code
    // path used for real trading incidents.
    private final com.tradevision.service.IncidentService incidentService;
    private final com.tradevision.config.TradingHeartbeatService heartbeatService;
    // Verifies the broker audit log's hash chain for tamper detection.
    private final com.tradevision.service.AuditChainService auditChainService;
    // Durable checkpoint used so chain verification has a trusted starting point even after
    // older records have aged out under BrokerAuditLog's TTL.
    private final com.tradevision.repository.AuditChainCheckpointRepository auditChainCheckpointRepo;
    // Backs the order-ledger endpoint, exposing each order's/position's full lifecycle event
    // timeline.
    private final com.tradevision.repository.TradeEventRepository tradeEventRepo;
    // Replays strategy decision logic over a given candle sequence.
    private final com.tradevision.service.HistoricalReplayService historicalReplayService;
    // Backs the execution-context lookup endpoint.
    private final com.tradevision.repository.ExecutionContextRepository executionContextRepo;

    // Wired through Spring configuration like every other secret in this codebase, so it
    // respects the normal application-local/prod.properties override chain.
    @Value("${app.admin.bootstrap-secret}")
    private String bootstrapSecret;

    // Soft, deployment-wide cap on bootstrap attempts, backed by an atomic Mongo counter so
    // every replica shares one count rather than each enforcing its own independent limit. Not
    // the real security boundary for this endpoint -- that's userRepo.countByRole("ADMIN") > 0
    // below, which is checked first and unconditionally.
    private static final int MAX_BOOTSTRAP_ATTEMPTS_PER_HOUR = 5;

    /** Atomically increments the shared bootstrap-attempt counter and returns the new count,
     *  resetting the window first if the previous one is over an hour old. A single, fixed-id
     *  document (there is only ever one bootstrap rate-limit window across the whole
     *  deployment) -- $inc and the window-reset $set both apply as genuinely atomic,
     *  per-document MongoDB operations, so two replicas racing to increment can't both read a
     *  stale pre-increment value and both think they were, say, attempt #3. */
    private int incrementBootstrapAttempts() {
        var query = new org.springframework.data.mongodb.core.query.Query(
            org.springframework.data.mongodb.core.query.Criteria.where("id").is("bootstrap-rate-limit"));
        var doc = mongoTemplate.findOne(query, org.bson.Document.class, "bootstrap_rate_limit");
        java.time.Instant windowStart = doc != null && doc.get("windowStart") != null
            ? ((java.util.Date) doc.get("windowStart")).toInstant() : null;
        if (windowStart == null || java.time.Instant.now().isAfter(windowStart.plusSeconds(3600))) {
            mongoTemplate.upsert(query,
                new org.springframework.data.mongodb.core.query.Update().set("windowStart", java.util.Date.from(java.time.Instant.now())).set("attemptCount", 0),
                "bootstrap_rate_limit");
        }
        var result = mongoTemplate.findAndModify(query,
            new org.springframework.data.mongodb.core.query.Update().inc("attemptCount", 1),
            org.springframework.data.mongodb.core.FindAndModifyOptions.options().upsert(true).returnNew(true),
            org.bson.Document.class, "bootstrap_rate_limit");
        return result != null && result.getInteger("attemptCount") != null ? result.getInteger("attemptCount") : 1;
    }

    /**
     * Constant-time comparison of the bootstrap secret, structured so its running time doesn't
     * depend on which precondition (missing configured secret, no secret supplied, or a genuine
     * mismatch) produced the "no match" result. MessageDigest.isEqual is only constant-time for
     * two arrays of the same length, so a caller-supplied secret of the wrong length is padded
     * to the configured secret's length before comparing, rather than short-circuiting on a
     * length check first. Package-private so it is directly unit-testable.
     */
    static boolean secretsMatch(String configured, String supplied) {
        if (configured == null || configured.isBlank()) return false;
        byte[] configuredBytes = configured.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] suppliedBytes = supplied != null
            ? supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            : new byte[0];
        byte[] suppliedPadded = java.util.Arrays.copyOf(suppliedBytes, configuredBytes.length);
        boolean lengthMatches = suppliedBytes.length == configuredBytes.length;
        boolean bytesMatch = java.security.MessageDigest.isEqual(configuredBytes, suppliedPadded);
        return lengthMatches && bytesMatch;
    }

    // ── Admin guard ───────────────────────────────────────────
    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.error("Admin access required."));
    }

    private boolean isAdmin(String userId) {
        if (userId == null) return false;
        return userRepo.findById(userId)
            .map(u -> "ADMIN".equals(u.getRole()))
            .orElse(false);
    }

    // ── Dashboard ─────────────────────────────────────────────
    @GetMapping("/dashboard")
    public ResponseEntity<?> dashboard(
            @AuthenticationPrincipal String userId) {
        if (!isAdmin(userId)) return forbidden();
        return ResponseEntity.ok(adminService.getDashboard());
    }

    // ── User management ───────────────────────────────────────
    @GetMapping("/users")
    public ResponseEntity<?> users(
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!isAdmin(userId)) return forbidden();
        var users = userRepo.findAll(
            org.springframework.data.domain.PageRequest.of(page, size,
                org.springframework.data.domain.Sort.by("createdAt").descending()));
        return ResponseEntity.ok(ApiResponse.ok("Users", users.map(com.tradevision.dto.AdminUserDto::from)));
    }

    @PatchMapping("/users/{id}/role")
    public ResponseEntity<?> setRole(
            @AuthenticationPrincipal String userId,
            @PathVariable String id,
            @RequestParam String role) {
        if (!isAdmin(userId)) return forbidden();
        if (!role.equals("ADMIN") && !role.equals("USER"))
            return ResponseEntity.badRequest().body(ApiResponse.error("Role must be ADMIN or USER"));
        return userRepo.findById(id).map(u -> {
            u.setRole(role);
            userRepo.save(u);
            return ResponseEntity.ok((Object)ApiResponse.ok("Role updated to " + role));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── ML dataset export: raw labeled training data only, no model training here ─
    @GetMapping("/ml-dataset/export")
    public ResponseEntity<?> exportMlDataset(
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue = "2000") int limit) {
        if (!isAdmin(userId)) return forbidden();
        var rows = mlDatasetExportService.exportResolved(limit);
        return ResponseEntity.ok(ApiResponse.ok(
            rows.size() + " resolved, labeled trade calls exported. This is raw training data — no model has been trained on it.",
            rows));
    }

    /**
     * Exports broker audit log entries for a date range in bounded, paginated form, so every
     * record can be extracted before BrokerAuditLog's 730-day TTL deletes it. This is the
     * extraction primitive; archiving the result to durable external storage on a schedule is
     * left to whoever operates the deployment.
     */
    @GetMapping("/audit-log/export")
    public ResponseEntity<?> exportAuditLog(
            @AuthenticationPrincipal String userId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "500") int size) {
        if (!isAdmin(userId)) return forbidden();
        java.time.LocalDateTime fromDate, toDate;
        try {
            fromDate = java.time.LocalDate.parse(from).atStartOfDay();
            toDate = java.time.LocalDate.parse(to).atTime(23, 59, 59);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("from/to must be ISO dates (YYYY-MM-DD)."));
        }
        if (toDate.isBefore(fromDate)) return ResponseEntity.badRequest().body(ApiResponse.error("to must not be before from."));
        var pageResult = auditLogRepo.findByTimestampBetweenOrderByTimestampDesc(fromDate, toDate,
            org.springframework.data.domain.PageRequest.of(page, Math.min(Math.max(size, 1), 2000)));
        return ResponseEntity.ok(ApiResponse.ok(
            pageResult.getNumberOfElements() + " of " + pageResult.getTotalElements() + " total audit log entries in range "
                + "(page " + page + " of " + pageResult.getTotalPages() + "). BrokerAuditLog is designed to be safe to hand to an "
                + "auditor as-is (see its own class javadoc) -- these entries are the raw records, unmodified.",
            pageResult.getContent()));
    }

    /**
     * Verifies the audit log's hash chain over a date range: fetches the range (same bounded
     * query as the export endpoint), reverses it to oldest-first (the order the chain was built
     * in), and checks it for tampering or gaps. See AuditChainService.verifyChain for exactly
     * what this does and does not detect.
     */
    @GetMapping("/audit-log/verify-chain")
    public ResponseEntity<?> verifyAuditChain(
            @AuthenticationPrincipal String userId,
            @RequestParam String from,
            @RequestParam String to) {
        if (!isAdmin(userId)) return forbidden();
        java.time.LocalDateTime fromDate, toDate;
        try {
            fromDate = java.time.LocalDate.parse(from).atStartOfDay();
            toDate = java.time.LocalDate.parse(to).atTime(23, 59, 59);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("from/to must be ISO dates (YYYY-MM-DD)."));
        }
        if (toDate.isBefore(fromDate)) return ResponseEntity.badRequest().body(ApiResponse.error("to must not be before from."));
        var pageResult = auditLogRepo.findByTimestampBetweenOrderByTimestampDesc(fromDate, toDate,
            org.springframework.data.domain.PageRequest.of(0, 2000));
        var oldestFirst = new java.util.ArrayList<>(pageResult.getContent());
        java.util.Collections.reverse(oldestFirst);
        // Passes the durable checkpoint (if any) so a range whose true earliest records were
        // already trimmed by the TTL is verified against it, rather than trusting whatever
        // record happens to be oldest-surviving as an unconditionally valid chain start.
        var checkpoint = auditChainCheckpointRepo.findById(com.tradevision.model.AuditChainCheckpoint.SINGLETON_ID).orElse(null);
        var result = auditChainService.verifyChain(oldestFirst, checkpoint);

        return ResponseEntity.ok(ApiResponse.ok(
            result.valid()
                ? "Chain verified VALID across " + result.recordsChecked() + " record(s) in range -- no tampering detected."
                : "Chain BROKEN at or before record " + result.firstBrokenRecordId() + " (checked " + result.recordsChecked()
                    + " of " + oldestFirst.size() + " record(s) before the break was found). This means that record's own "
                    + "content no longer matches its stored hash, or a record between it and the previous one is missing --"
                    + " manual investigation required.",
            result));
    }

    /**
     * Returns the full, ordered lifecycle event timeline for one order or position (exactly one
     * of orderId/positionId must be given). Covers the order lifecycle from creation through its
     * terminal status; earlier signal-generation/risk-approval stages and position-level events
     * are tracked separately.
     */
    @GetMapping("/order-ledger")
    public ResponseEntity<?> orderLedger(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String positionId) {
        if (!isAdmin(userId)) return forbidden();
        boolean hasOrderId = orderId != null && !orderId.isBlank();
        boolean hasPositionId = positionId != null && !positionId.isBlank();
        if (hasOrderId == hasPositionId) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Provide exactly one of orderId or positionId, not both or neither."));
        }
        var events = hasOrderId
            ? tradeEventRepo.findByOrderIdOrderByOccurredAtAsc(orderId)
            : tradeEventRepo.findByPositionIdOrderByOccurredAtAsc(positionId);
        return ResponseEntity.ok(ApiResponse.ok(
            events.size() + " event(s) in this " + (hasOrderId ? "order's" : "position's") + " own ordered lifecycle timeline.",
            events));
    }

    /**
     * Replays the strategy's signal-decision logic over a given candle sequence, supplied
     * directly in the request body rather than fetched from a specific broker credential so any
     * data source (a live fetch or a separately exported historical dataset) can be used. This
     * replays decision logic only, not real order-book depth, slippage, or fill behavior.
     */
    public record ReplayCandleInput(long time, double open, double high, double low, double close, double volume) {}
    public record ReplayRequest(java.util.List<ReplayCandleInput> candles, int windowSize) {}

    @PostMapping("/historical-replay")
    public ResponseEntity<?> historicalReplay(@AuthenticationPrincipal String userId, @RequestBody ReplayRequest req) {
        if (!isAdmin(userId)) return forbidden();
        int windowSize = req.windowSize() > 0 ? req.windowSize() : 100;
        var candles = req.candles().stream()
            .map(c -> new com.tradevision.service.broker.dto.Candle(c.time(), c.open(), c.high(), c.low(), c.close(), c.volume()))
            .toList();
        var results = historicalReplayService.replay(candles, windowSize);
        return ResponseEntity.ok(ApiResponse.ok(
            results.size() + " signal(s) would have fired across " + candles.size() + " candle(s) with a window size of "
                + windowSize + ". This replays strategy DECISION logic only -- not real order-book depth, slippage, or fill "
                + "behavior (see HistoricalReplayService's own class javadoc for the full, honestly-stated scope).",
            results));
    }

    // ── Promote first admin via secret header ────────────────
    // One-time (refuses once any admin already exists -- this should only ever succeed once in
    // a healthy deployment), rate-limited (brute-forcing the secret is the real threat model
    // here), and audited (every attempt, success or failure, is logged with enough context to
    // investigate).
    @PostMapping("/bootstrap")
    public ResponseEntity<?> bootstrap(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String mobile,
            @RequestHeader(value = "X-Bootstrap-Secret", required = false) String secret) {

        if (userRepo.countByRole("ADMIN") > 0) {
            log.warn("Admin bootstrap attempt refused — an admin already exists. Requested identifier: {}",
                email != null ? email : mobile);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Bootstrap is disabled — this deployment already has an administrator. Ask an existing admin to promote you instead."));
        }

        int attempts = incrementBootstrapAttempts();
        if (attempts > MAX_BOOTSTRAP_ATTEMPTS_PER_HOUR) {
            log.error("Admin bootstrap rate limit exceeded ({} attempts this hour, shared across every replica) — refusing further attempts. "
                + "This may indicate an active attempt to guess ADMIN_BOOTSTRAP_SECRET.", attempts);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.error("Too many bootstrap attempts — try again later."));
        }

        // Uses a constant-time comparison rather than String.equals, which short-circuits on the
        // first mismatched character and so leaks, via timing, how many leading characters of a
        // guess were correct. The per-hour rate limit above already bounds how many guesses this
        // endpoint accepts at all; this closes the timing side channel as defense in depth.
        if (!secretsMatch(bootstrapSecret, secret)) {
            log.warn("Admin bootstrap attempt with an incorrect secret. Requested identifier: {}", email != null ? email : mobile);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Invalid bootstrap secret."));
        }

        // Accept email or mobile
        java.util.Optional<com.tradevision.model.User> userOpt = email != null
            ? userRepo.findByEmail(email.trim().toLowerCase())
            : userRepo.findByMobile(mobile);
        if (userOpt.isEmpty()) {
            log.warn("Admin bootstrap: correct secret, but no user found for identifier {}", email != null ? email : mobile);
            return ResponseEntity.notFound().build();
        }
        com.tradevision.model.User u = userOpt.get();

        // Race-proof gate: countByRole above is a fast pre-check for the common case (an
        // established deployment already has an admin), but this insert is what closes the real
        // race window between two simultaneous bootstrap attempts on a genuinely fresh
        // deployment. MongoDB's unique _id index makes this atomic -- only one concurrent insert
        // of a document with this exact fixed id can ever succeed, database-side, regardless of
        // how many application instances or threads are racing for it.
        try {
            mongoTemplate.insert(new com.tradevision.model.BootstrapLock(u.getId()));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.error("Admin bootstrap lost a concurrency race — another request already won it. Identifier: {}", email != null ? email : mobile);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Another bootstrap request already succeeded first. This deployment now has an administrator — ask them to promote you instead."));
        }

        // The BootstrapLock insert above and this save() are two separate, non-atomic
        // operations, so if the save fails, the lock must be rolled back here -- otherwise it
        // would sit there permanently, failing every future bootstrap attempt with a duplicate
        // key error even though no admin was ever actually promoted. A full Mongo transaction
        // would be the cleaner fix, but this application cannot assume transaction support is
        // available on every deployment, so rollback-on-failure is used instead, at the cost of
        // a narrow window where the lock briefly exists for no reason.
        try {
            u.setRole("ADMIN");
            userRepo.save(u);
        } catch (Exception e) {
            log.error("Admin bootstrap: promotion failed after the lock was already acquired for user {} — rolling back the lock so a retry can succeed. Real error: {}",
                u.getId(), e.getMessage());
            try {
                mongoTemplate.remove(new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is("admin-bootstrap")),
                    com.tradevision.model.BootstrapLock.class);
            } catch (Exception rollbackFailure) {
                log.error("Admin bootstrap: rollback of the lock ALSO failed after promotion itself failed — this deployment may now be "
                    + "permanently locked out of bootstrap until the 'admin-bootstrap' BootstrapLock document is manually removed. "
                    + "Real rollback error: {}", rollbackFailure.getMessage());
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Bootstrap promotion failed due to a database error. The lock has been rolled back — please retry."));
        }
        String id = email != null ? email : mobile;
        log.warn("Admin bootstrap SUCCEEDED — user {} promoted to ADMIN. This should have been the only time this ever happens.", id);
        return ResponseEntity.ok(ApiResponse.ok("User " + id + " promoted to ADMIN."));
    }

    /**
     * Reports execution latency (order-entry stages, position lifetime, entry-to-protection
     * timing, optionally broken out by strategy version). symbol is optional -- omit it for an
     * all-symbols report.
     */
    @GetMapping("/latency-report")
    public ResponseEntity<?> latencyReport(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "30") int lookbackDays,
            @RequestParam(defaultValue = "false") boolean byStrategyVersion) {
        if (!isAdmin(userId)) return forbidden();
        if (byStrategyVersion) {
            return ResponseEntity.ok(ApiResponse.ok("Execution latency by strategy version" + (symbol != null ? " for " + symbol : "")
                + " over the last " + lookbackDays + " day(s).",
                latencyMetricsService.reportByStrategyVersion(symbol, lookbackDays)));
        }
        var entryReport = latencyMetricsService.reportForSymbol(symbol, lookbackDays);
        var positionLifetime = latencyMetricsService.positionLifetimeReport(symbol, lookbackDays);
        var entryToProtection = latencyMetricsService.entryToProtectionReport(symbol, lookbackDays);
        return ResponseEntity.ok(ApiResponse.ok("Execution latency report" + (symbol != null ? " for " + symbol : " (all symbols)")
            + " over the last " + lookbackDays + " day(s).",
            java.util.Map.of(
                "entryStages", entryReport,
                "positionLifetime", positionLifetime,
                "entryToFirstProtection", entryToProtection)));
    }

    /**
     * Aggregates trading incidents by type and by severity over a bounded window, plus an
     * unresolved count per type -- the "what kinds of failures are happening, how often, and
     * are they still open" operator view. Uses three MongoDB aggregation pipelines (byType,
     * bySeverity, unresolvedByType) that match+group+count entirely inside the database, so
     * only the small, already-grouped result sets (one row per distinct type/severity) are ever
     * pulled into application memory, regardless of how many raw incidents exist in the window.
     */
    record GroupCount(String id, long count) {}

    @GetMapping("/incident-taxonomy")
    public ResponseEntity<?> incidentTaxonomy(
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue = "30") int lookbackDays) {
        if (!isAdmin(userId)) return forbidden();
        var cutoff = java.time.LocalDateTime.now().minusDays(lookbackDays);
        var afterCutoff = org.springframework.data.mongodb.core.aggregation.Aggregation.match(
            org.springframework.data.mongodb.core.query.Criteria.where("createdAt").gte(cutoff));

        long totalIncidents = mongoTemplate.aggregate(
            org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(afterCutoff),
            "trading_incidents", org.bson.Document.class).getMappedResults().size();

        var byTypeAgg = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
            afterCutoff,
            org.springframework.data.mongodb.core.aggregation.Aggregation.group("type").count().as("count"));
        var byType = toMap(mongoTemplate.aggregate(byTypeAgg, "trading_incidents", GroupCount.class).getMappedResults());

        var bySeverityAgg = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
            afterCutoff,
            org.springframework.data.mongodb.core.aggregation.Aggregation.group("severity").count().as("count"));
        var bySeverity = toMap(mongoTemplate.aggregate(bySeverityAgg, "trading_incidents", GroupCount.class).getMappedResults());

        var unresolvedByTypeAgg = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
            afterCutoff,
            org.springframework.data.mongodb.core.aggregation.Aggregation.match(
                org.springframework.data.mongodb.core.query.Criteria.where("resolvedAt").is(null)),
            org.springframework.data.mongodb.core.aggregation.Aggregation.group("type").count().as("count"));
        var unresolvedByType = toMap(mongoTemplate.aggregate(unresolvedByTypeAgg, "trading_incidents", GroupCount.class).getMappedResults());

        return ResponseEntity.ok(ApiResponse.ok(
            totalIncidents + " incident(s) over the last " + lookbackDays + " day(s), grouped by type and severity "
                + "(aggregated in the database, not loaded into memory).",
            java.util.Map.of(
                "totalIncidents", totalIncidents,
                "byType", byType,
                "bySeverity", bySeverity,
                "unresolvedByType", unresolvedByType)));
    }

    private static java.util.Map<String, Long> toMap(java.util.List<GroupCount> rows) {
        var map = new java.util.LinkedHashMap<String, Long>();
        for (var row : rows) {
            if (row.id() != null) map.put(row.id(), row.count());
        }
        return map;
    }

    /**
     * Traces every order produced by a given strategy version, joined to its resolved outcome
     * (where one exists) via signalId, so an operator can see how that version actually
     * performed. Bounded to the most recent `limit` orders (capped at 500).
     */
    @GetMapping("/execution-provenance")
    public ResponseEntity<?> executionProvenance(
            @AuthenticationPrincipal String userId,
            @RequestParam String strategyVersion,
            @RequestParam(defaultValue = "100") int limit) {
        if (!isAdmin(userId)) return forbidden();
        var orders = orderRepo.findByStrategyVersionOrderByCreatedAtDesc(strategyVersion,
            org.springframework.data.domain.PageRequest.of(0, Math.min(Math.max(limit, 1), 500)));

        var rows = orders.stream().map(o -> {
            String outcomeResult = null;
            Double pnlPct = null;
            if (o.getSignalId() != null) {
                var call = callRepo.findById(o.getSignalId()).orElse(null);
                if (call != null && call.getOutcome() != null) {
                    outcomeResult = call.getOutcome().getResult();
                    pnlPct = call.getOutcome().getPnlPct();
                }
            }
            return java.util.Map.of(
                "orderId", o.getId() != null ? o.getId() : "",
                "symbol", o.getSymbol() != null ? o.getSymbol() : "",
                "status", o.getStatus() != null ? o.getStatus().name() : "",
                "signalId", o.getSignalId() != null ? o.getSignalId() : "",
                "outcomeResult", outcomeResult != null ? outcomeResult : "",
                "pnlPct", pnlPct != null ? pnlPct : 0.0,
                "createdAt", o.getCreatedAt() != null ? o.getCreatedAt().toString() : "");
        }).toList();

        return ResponseEntity.ok(ApiResponse.ok(
            rows.size() + " order(s) traced to strategy version '" + strategyVersion + "'.", rows));
    }

    /**
     * Summarizes recent reconciliation activity for an operator at a glance: orphaned-OCO
     * activity (found/resolved) and reconciliation-relevant incident types over a bounded
     * window, plus the timestamp of the last completed reconciliation cycle. Built entirely from
     * data reconciliation already durably records elsewhere, rather than instrumenting the
     * reconciliation pass itself.
     */
    @GetMapping("/reconciliation-report")
    public ResponseEntity<?> reconciliationReport(
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue = "7") int lookbackDays) {
        if (!isAdmin(userId)) return forbidden();
        var cutoff = java.time.LocalDateTime.now().minusDays(lookbackDays);
        var orphans = orphanedOcoRepo.findByCreatedAtAfter(cutoff);
        long orphansResolved = orphans.stream().filter(com.tradevision.model.OrphanedOco::isResolved).count();
        long orphansStillOpen = orphans.size() - orphansResolved;

        var reconciliationIncidentTypes = java.util.Set.of(
            "RECONCILIATION_MISMATCH", "ORDER_STATE_UNKNOWN", "OCO_PLACED_BUT_NOT_RECORDED",
            "PROTECTION_ATTEMPT_STUCK_WITH_REAL_OCO", "OCO_STATE_UNKNOWN_AFTER_PLACEMENT_ERROR");
        var incidents = tradingIncidentRepo.findByCreatedAtAfter(cutoff).stream()
            .filter(i -> reconciliationIncidentTypes.contains(i.getType()))
            .toList();
        long incidentsStillUnresolved = incidents.stream().filter(i -> i.getResolvedAt() == null).count();

        return ResponseEntity.ok(ApiResponse.ok(
            "Reconciliation activity over the last " + lookbackDays + " day(s).",
            java.util.Map.of(
                "lastReconciliationCompletedAt", String.valueOf(heartbeatService.getLastReconciliationCompletedAt()),
                "orphanedOco", java.util.Map.of("found", orphans.size(), "resolved", orphansResolved, "stillOpen", orphansStillOpen),
                "reconciliationIncidents", java.util.Map.of("total", incidents.size(), "stillUnresolved", incidentsStillUnresolved,
                    "byType", incidents.stream().collect(java.util.stream.Collectors.groupingBy(
                        com.tradevision.model.TradingIncident::getType, java.util.stream.Collectors.counting()))))));
    }

    /**
     * Looks up execution context by exactly one of executionId, signalId, or positionId, so a
     * crashed operation can be reconstructed from its recorded context. Always returns a list,
     * since an executionId lookup can only ever match one document (it's the primary key), but
     * signalId/positionId could in principle match more than one -- a signal re-evaluated after
     * a recovery pass, for instance.
     */
    @GetMapping("/execution-context")
    public ResponseEntity<?> executionContext(
            @AuthenticationPrincipal String userId,
            @RequestParam(required = false) String executionId,
            @RequestParam(required = false) String signalId,
            @RequestParam(required = false) String positionId) {
        if (!isAdmin(userId)) return forbidden();
        int provided = (executionId != null && !executionId.isBlank() ? 1 : 0)
            + (signalId != null && !signalId.isBlank() ? 1 : 0)
            + (positionId != null && !positionId.isBlank() ? 1 : 0);
        if (provided != 1) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Provide exactly one of executionId, signalId, or positionId."));
        }

        java.util.List<com.tradevision.model.ExecutionContext> results;
        if (executionId != null && !executionId.isBlank()) {
            results = executionContextRepo.findById(executionId).map(java.util.List::of).orElse(java.util.List.of());
        } else if (signalId != null && !signalId.isBlank()) {
            results = executionContextRepo.findBySignalIdOrderByCreatedAtDesc(signalId);
        } else {
            results = executionContextRepo.findByPositionId(positionId);
        }

        return ResponseEntity.ok(ApiResponse.ok(
            results.size() + " execution context(s) found.", results));
    }

    /**
     * Snapshot of recovery health: ProtectionAttempt/OrphanedOco ages and counts, manual
     * escalation rate, and a bounded recent critical-incident count, derived entirely from
     * existing queryable data. Does not include reconciliation lag or other latency metrics
     * that would need new timing instrumentation at the event-processing sites.
     */
    @GetMapping("/recovery-health")
    public ResponseEntity<?> recoveryHealth(@AuthenticationPrincipal String userId) {
        if (!isAdmin(userId)) return forbidden();

        var stuckProtectionAttempts = protectionAttemptRepo.findByStatusOrderByCreatedAtAsc("SUBMITTING");
        var unresolvedOrphans = orphanedOcoRepo.findByResolvedFalseOrderByCreatedAtAsc();
        var recentIncidents = tradingIncidentRepo.findByCreatedAtAfter(java.time.LocalDateTime.now().minusDays(1));

        long oldestProtectionAttemptAgeSeconds = stuckProtectionAttempts.isEmpty() ? 0
            : java.time.Duration.between(stuckProtectionAttempts.get(0).getCreatedAt(), java.time.LocalDateTime.now()).toSeconds();
        long oldestOrphanAgeSeconds = unresolvedOrphans.isEmpty() ? 0
            : java.time.Duration.between(unresolvedOrphans.get(0).getCreatedAt(), java.time.LocalDateTime.now()).toSeconds();
        long escalatedOrphanCount = unresolvedOrphans.stream().filter(com.tradevision.model.OrphanedOco::isEscalated).count();
        long criticalIncidentsLast24h = recentIncidents.stream().filter(i -> "CRITICAL".equals(i.getSeverity())).count();
        long unresolvedCriticalIncidentsLast24h = recentIncidents.stream()
            .filter(i -> "CRITICAL".equals(i.getSeverity()) && i.getResolvedAt() == null).count();
        long deliveryExhaustedIncidentsLast24h = recentIncidents.stream()
            .filter(i -> "DELIVERY_EXHAUSTED".equals(i.getNotificationStatus())).count();

        return ResponseEntity.ok(ApiResponse.ok("Recovery health snapshot.", java.util.Map.of(
            "protectionAttempts", java.util.Map.of(
                "unresolvedCount", stuckProtectionAttempts.size(),
                "oldestUnresolvedAgeSeconds", oldestProtectionAttemptAgeSeconds),
            "orphanedOcos", java.util.Map.of(
                "unresolvedCount", unresolvedOrphans.size(),
                "oldestUnresolvedAgeSeconds", oldestOrphanAgeSeconds,
                "escalatedCount", escalatedOrphanCount,
                "manualEscalationRate", unresolvedOrphans.isEmpty() ? 0.0 : (double) escalatedOrphanCount / unresolvedOrphans.size()),
            "criticalIncidentsLast24h", java.util.Map.of(
                "total", criticalIncidentsLast24h,
                "unresolved", unresolvedCriticalIncidentsLast24h,
                "notificationDeliveryExhausted", deliveryExhaustedIncidentsLast24h)
        )));
    }

    /**
     * Raises a real CRITICAL incident through the same code path every real trading incident
     * uses (IncidentService.raiseCritical -> attemptDelivery), so an operator can validate
     * end-to-end delivery against the deployment's real notification provider. Reports back this
     * application's own view of the delivery outcome (status, attempt count) immediately;
     * confirming the notification actually arrived in an inbox or webhook receiver is the
     * operator's own follow-up step.
     */
    @PostMapping("/test-notification")
    public ResponseEntity<?> testNotification(@AuthenticationPrincipal String userId) {
        if (!isAdmin(userId)) return forbidden();

        var beforeCall = java.time.LocalDateTime.now().minusSeconds(1); // small buffer for clock granularity
        incidentService.raiseCritical(userId, null, null, null, null, "TEST_NOTIFICATION",
            "This is a test notification, manually triggered from the admin dashboard to validate end-to-end delivery "
                + "(email and webhook) against this deployment's own real, production notification provider. If you did not "
                + "receive this via email or your configured webhook within a few minutes, check: the account's own email "
                + "address and webhook URL are correctly configured, the notification provider (Brevo) account is active and "
                + "not rate-limited, and this application's own logs for the specific delivery error.");

        // Report this application's own view of the outcome immediately -- the actual human
        // confirmation (did the email/webhook genuinely arrive) is the operator's own next step,
        // not something this endpoint can verify on its own.
        var recent = tradingIncidentRepo.findByCreatedAtAfter(beforeCall).stream()
            .filter(i -> "TEST_NOTIFICATION".equals(i.getType()) && userId.equals(i.getUserId()))
            .max(java.util.Comparator.comparing(com.tradevision.model.TradingIncident::getCreatedAt));
        if (recent.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.ok("Test notification incident raised, but could not be immediately re-queried to "
                + "confirm delivery status -- check the incidents list directly.", null));
        }
        var incident = recent.get();
        return ResponseEntity.ok(ApiResponse.ok("Test notification incident raised. This application's own delivery attempt result: "
            + incident.getNotificationStatus() + " (attempt " + incident.getNotificationAttempts() + "). Now check your own email "
            + "inbox and configured webhook endpoint directly to confirm it actually arrived -- this status only reflects whether "
            + "this application's own send call reported success, not whether a human received it.",
            java.util.Map.of("notificationStatus", incident.getNotificationStatus(), "notificationAttempts", incident.getNotificationAttempts())));
    }
}
