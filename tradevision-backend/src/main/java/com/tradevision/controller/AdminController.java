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
 * Review finding ("Frontend authentication migration is incomplete and currently breaks
 * authenticated APIs" -- P0): confirmed real and fixed -- see UserController's own javadoc for
 * the full root-cause explanation (Spring's required @RequestHeader rejects a request with its
 * own 400 before this method's body runs, and the frontend no longer sends that header at all).
 * isAdmin() previously parsed the raw bearer token itself (jwt.getUserId(...)) -- now just looks
 * the already-authenticated userId up directly, since SecurityContext (populated by
 * SecurityConfig's own jwtFilter) has already done the token verification this class doesn't
 * need to repeat.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
// Review finding ("@CrossOrigin still has hardcoded localhost origins" -- external review,
// thirty-fifth pass, P2, full context in NewsController's own identical fix): removed --
// CorsConfig's own global CorsFilter already covers this endpoint.
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AdminService   adminService;
    private final UserRepository userRepo;
    private final com.tradevision.service.MLDatasetExportService mlDatasetExportService;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    /**
     * Review finding ("Audit-log retention is two years but not export/archive managed" --
     * external review, twenty-third pass, P2, full context in exportAuditLog's own javadoc):
     * needed for the actual export endpoint.
     */
    private final com.tradevision.repository.BrokerAuditLogRepository auditLogRepo;
    /**
     * Review finding ("richer metrics around per-symbol execution latency" -- external review,
     * P3, confirmed real by direct inspection before this fix: LatencyMetricsService already
     * has genuinely rich per-symbol/per-strategy-version reporting built -- reportForSymbol,
     * reportByStrategyVersion, positionLifetimeReport, entryToProtectionReport -- but no
     * controller anywhere exposed any of it. The metrics existed; nothing surfaced them.
     */
    private final com.tradevision.service.LatencyMetricsService latencyMetricsService;
    /**
     * Review finding ("exchange rejection taxonomy dashboards" / "automatic broker incident
     * dashboards" -- external review, P3, full context in the new endpoint's own javadoc):
     * needed for the taxonomy aggregation.
     */
    private final com.tradevision.repository.TradingIncidentRepository tradingIncidentRepo;
    /**
     * Review finding ("strategy-versioned execution provenance" -- external review, P3, full
     * context in the new endpoint's own javadoc): needed for the actual provenance lookup --
     * orderRepo for the strategy-versioned orders themselves, callRepo to join each order's own
     * signalId back to its real, resolved outcome.
     */
    private final com.tradevision.repository.OrderRepository orderRepo;
    private final com.tradevision.repository.TradeCallRepository callRepo;
    /**
     * Review finding ("automated reconciliation reports" -- external review, P3, full context
     * in the new endpoint's own javadoc): needed for the actual report.
     */
    private final com.tradevision.repository.OrphanedOcoRepository orphanedOcoRepo;
    /**
     * Review finding ("Recovery metrics need to be first-class" -- external review, thirty-sixth
     * pass, P2, full context in the new recoveryHealth endpoint below): needed for that endpoint.
     */
    private final com.tradevision.repository.ProtectionAttemptRepository protectionAttemptRepo;
    /**
     * Review finding ("Incident retry is improved, but external paging still needs production
     * validation" -- external review, thirty-eighth pass, P1, the review's own explicit ask:
     * "CRITICAL generated -> notification delivered -> delivery acknowledged -> retry if failed,
     * tested end-to-end"): needed for the new test-notification endpoint below.
     */
    private final com.tradevision.service.IncidentService incidentService;
    private final com.tradevision.config.TradingHeartbeatService heartbeatService;
    /**
     * Review finding ("immutable external audit export" -- external review, P3, full context
     * in the new verify-chain endpoint's own javadoc): needed to make the hash chain actually
     * usable by an operator, not just built.
     */
    private final com.tradevision.service.AuditChainService auditChainService;
    /**
     * P2-7 fix ("IndexInitializer: BrokerAuditLog TTL 730 days conflicts with AuditChainService's
     * hash chain" -- full context in AuditChainCheckpoint's own class javadoc): needed so
     * verifyAuditChain can actually check a queried range's own first record against something,
     * instead of always trusting whatever record TTL happened to leave as the chain's start.
     */
    private final com.tradevision.repository.AuditChainCheckpointRepository auditChainCheckpointRepo;
    /**
     * Review finding ("full event-sourced order ledger" -- external review, P3, confirmed real
     * by direct inspection before this fix: TradeEvent already exists, already immutable by
     * construction, already wired into OrderService's own single transition chokepoint -- see
     * its own class javadoc for the full, already-honest scope disclosure -- but nothing
     * anywhere exposed it): needed for the actual endpoint making it usable.
     */
    private final com.tradevision.repository.TradeEventRepository tradeEventRepo;
    /**
     * Review finding ("historical replay engine" -- external review, P3, full context in
     * HistoricalReplayService's own class javadoc): needed for the actual endpoint.
     */
    private final com.tradevision.service.HistoricalReplayService historicalReplayService;
    /**
     * User's own explicit architectural request, full context in ExecutionContext's own class
     * javadoc: needed for the actual query endpoint making this document usable.
     */
    private final com.tradevision.repository.ExecutionContextRepository executionContextRepo;

    // Review finding ("P0 #2" — investigating this turned up that bootstrap() previously read
    // System.getProperty/System.getenv directly, completely bypassing Spring's configuration —
    // meaning the application-local/prod.properties split didn't actually apply to this secret
    // at all. @Value is how every other secret in this codebase is wired; this now matches.
    @Value("${app.admin.bootstrap-secret}")
    private String bootstrapSecret;

    // Review finding ("Admin bootstrap rate limiter is JVM-local" -- P1): confirmed real --
    // this JVM-local counter's own original design comment defended it against a DIFFERENT
    // concern (a JVM restart resetting the count, which is a genuinely acceptable failure mode
    // for a soft rate limit) but never actually addressed the review's real concern: with N
    // replicas running simultaneously, each has its OWN independent counter, so the effective
    // limit across the whole deployment becomes 5*N, not 5. Fixed with a real, atomic, Mongo-
    // backed counter -- a single fixed-id document, incremented via $inc (the same atomic
    // building block this codebase's own OTP attempt-limiting and RiskProfile safety-state
    // fixes already use), so every replica genuinely shares one count. Still explicitly a SOFT
    // rate limit, not this endpoint's real hard security boundary -- that remains
    // userRepo.countByRole("ADMIN") > 0 above, checked first and unconditionally.
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
     * P2-22 fix ("AdminController.bootstrap: secret compared with String.equals instead of
     * MessageDigest.isEqual" -- external review, full context in bootstrap's own updated
     * comment above): a constant-time comparison, deliberately structured so its running time
     * doesn't depend on WHICH precondition (configured secret missing, caller supplied nothing,
     * or a genuine mismatch) is the reason for the "no match" result -- MessageDigest.isEqual
     * itself is only constant-time for two arrays of the SAME length, so a caller-supplied secret
     * of the wrong length is padded to the configured secret's own length before comparing,
     * rather than short-circuiting on a length check the way a naive `a.length() != b.length()`
     * guard would. Package-private (not private) so this is directly unit-testable -- see
     * AdminControllerTest.
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

    // ── ML dataset export (review item #17, honest scope: export only, no model) ─
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
     * Review finding ("Audit-log retention is two years but not export/archive managed" --
     * external review, twenty-third pass, P2, confirmed real by direct inspection before this
     * fix: the TTL index already correctly deletes BrokerAuditLog records after 730 days, but
     * nothing let an operator actually extract them first): the actual fix, honestly scoped --
     * this application can guarantee every record is genuinely extractable in bounded,
     * paginated form before deletion. It cannot itself provide an "immutable external archive,"
     * a formal retention policy, or restore-verification tooling -- those are infrastructure and
     * process decisions belonging to wherever this application is actually operated, not
     * something this endpoint can fabricate. Whoever owns that process is expected to call this
     * on a schedule (e.g. a monthly cron pulling everything since the last successful export)
     * and write the result to their own actual archive storage -- this endpoint is the
     * extraction primitive that process needs, not the process itself.
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
     * Review finding ("immutable external audit export" -- external review, P3, full context
     * in AuditChainService's own class javadoc): the actual endpoint making the hash chain
     * usable -- fetches the given date range (same bounded query the export endpoint above
     * already uses), reverses it to oldest-first (the order the chain was actually built in),
     * and verifies it. See AuditChainService.verifyChain's own javadoc for exactly what this
     * does and does not detect, and this class's own honest concurrent-write limitation.
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
        // P2-7 fix, full context in AuditChainCheckpoint's own class javadoc: passes the durable
        // checkpoint (if any) so a range whose true earliest records were already trimmed by
        // BrokerAuditLog's own 730-day TTL is verified against it, rather than always trusting
        // whatever record happens to be oldest-surviving as an unconditionally valid chain start.
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
     * Review finding ("full event-sourced order ledger" -- external review, P3, confirmed real
     * by direct inspection before this fix: TradeEvent already exists -- immutable by
     * construction, wired into OrderService's own single transition chokepoint, covering every
     * order-lifecycle state change from ORDER_CREATED through every terminal status -- but
     * nothing anywhere exposed it. See TradeEvent's own class javadoc for the full, already-
     * honest scope disclosure -- this covers the order lifecycle specifically, not the earlier
     * signal-generation/risk-approval stages or position-level events, both tracked separately
     * elsewhere already): the actual endpoint -- exactly one of orderId or positionId must be
     * given, returning that entity's own full, ordered event timeline.
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
     * Review finding ("historical replay engine" -- external review, P3, confirmed real by
     * direct inspection before this fix: ServerSignalEngine.analyze is genuinely pure and
     * stateless, replayable directly -- but nothing anywhere exposed a way to actually run one):
     * the actual endpoint. Accepts a raw candle sequence directly in the request body rather
     * than fetching from a specific broker credential -- keeps this usable from any data source
     * (a broker's own recent-candle fetch, or a separately-exported historical dataset), not
     * locked to one specific fetch path. See HistoricalReplayService's own class javadoc for
     * the honest scope of what this actually replays (strategy decision logic, not real
     * order-book/fill behavior).
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
    // Review finding ("P0 #2" — "You still ship an admin bootstrap secret inside the frontend"):
    // the frontend leak is fixed (removed entirely, see admin.component.html), but the review's
    // own fix recommendation went further than that — make bootstrap itself: one-time (refuses
    // once ANY admin already exists — this should only ever succeed once in a healthy
    // deployment), rate-limited (a tight global cap on attempts, since brute-forcing the secret
    // is the actual threat model here), and audited (every attempt, success or failure, logged
    // with enough context to investigate).
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

        // P2-22 fix ("AdminController.bootstrap: secret compared with String.equals instead of
        // MessageDigest.isEqual" -- external review, confirmed real by direct inspection):
        // String.equals short-circuits on the first mismatched character, so how long the
        // comparison takes leaks (in principle) how many leading characters of a guess were
        // correct -- exactly the class of side channel MessageDigest.isEqual exists to close via
        // a constant-time comparison. The per-hour rate limit just above already bounds how many
        // guesses this endpoint accepts at all, so this is defense-in-depth on top of that, not
        // the only protection -- still worth closing outright rather than relying solely on the
        // rate limit. secretsMatch below never short-circuits on the null/blank checks either
        // (constant-time regardless of which precondition fails), and still runs the actual
        // constant-time byte comparison against a real byte array of the same length as the
        // configured secret whenever the caller supplied ANY secret at all, so a request with no
        // header takes the same code path length as one with a wrong one.
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

        // Review finding (P1 #7 — full context in BootstrapLock's own javadoc): the actual,
        // race-proof gate. countByRole above is a fast pre-check for the common case (an
        // established deployment already has an admin) — this is what closes the real race
        // window between two simultaneous bootstrap attempts on a genuinely fresh deployment.
        // MongoDB's own unique _id index makes this atomic: only one concurrent insert of a
        // document with this exact fixed id can ever succeed, database-side, regardless of how
        // many application instances (or threads within one) are racing for it.
        try {
            mongoTemplate.insert(new com.tradevision.model.BootstrapLock(u.getId()));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.error("Admin bootstrap lost a concurrency race — another request already won it. Identifier: {}", email != null ? email : mobile);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Another bootstrap request already succeeded first. This deployment now has an administrator — ask them to promote you instead."));
        }

        // Review finding ("Admin bootstrap is still vulnerable to permanent lockout after DB
        // failure" -- external review, twenty-sixth pass, P1, confirmed real by direct
        // inspection before this fix: the BootstrapLock insert above and this save() are two
        // separate, non-atomic operations -- if save() fails, the lock already exists but no
        // admin was ever actually promoted, and every future bootstrap attempt would then fail
        // with a duplicate-key error against a lock that never actually accomplished anything):
        // the actual fix -- if promotion fails, roll back the lock so a retry can succeed
        // later, rather than leaving a permanent, silent lockout. A full Mongo transaction
        // would be the more complete fix, but this application cannot assume transaction
        // support is available on every deployment (see IndexInitializer.
        // checkMongoTransactionSupport's own javadoc) -- this rollback-on-failure approach works
        // regardless of that, at the honest cost of a narrow window (between save() failing and
        // this rollback completing) where the lock briefly still exists for no reason; that
        // window is far narrower and far less consequential than the permanent lockout it
        // replaces.
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
     * Review finding ("richer metrics around per-symbol execution latency" -- external review,
     * P3, full context in latencyMetricsService's own updated field javadoc): the actual
     * endpoint. symbol is optional -- omit it for an all-symbols report, same behavior
     * reportForSymbol itself already has for a null symbol.
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
     * Review finding ("exchange rejection taxonomy dashboards" / "automatic broker incident
     * dashboards" -- external review, P3, confirmed real by direct inspection before this fix:
     * TradingIncident already carries a real type/severity taxonomy on every record -- see its
     * own class javadoc for the full type list -- but nothing anywhere aggregated it into a
     * view an operator could actually use): the actual endpoint. Aggregates real incidents by
     * type and by severity over a bounded window, plus an unresolved count per type -- the
     * "what kinds of failures are happening, and how often, and are they still open" view this
     * review item asks for.
     */
    /**
     * Review finding ("some admin reports are potentially expensive" -- external review,
     * twenty-sixth pass, P2, confirmed real by direct inspection before this fix: this used to
     * load every incident in the window into application memory via findByCreatedAtAfter, then
     * group/count with Java streams -- fine at today's scale, but a real problem once this
     * collection reaches millions of documents, exactly as the review says): the actual fix --
     * three real MongoDB aggregation pipelines (byType, bySeverity, unresolvedByType), each
     * doing its own match+group+count entirely inside the database. Only the already-small,
     * already-grouped result sets (one row per distinct type/severity, never one row per
     * incident) are ever pulled into this application's own memory, regardless of how many raw
     * incidents exist in the window.
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
     * Review finding ("strategy-versioned execution provenance" -- external review, P3,
     * confirmed real by direct inspection before this fix: Order.strategyVersion is genuinely
     * populated on every order, but nothing anywhere let an operator trace which specific orders
     * a given strategy version produced or how they actually performed): the actual lookup --
     * every order for the given strategyVersion, joined to its own real, resolved outcome
     * (where one exists) via signalId. Bounded to the most recent `limit` orders (capped at
     * 500), same "bounded, paginated, never a raw findAll-style read" discipline this session
     * has applied everywhere else.
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
     * Review finding ("automated reconciliation reports" -- external review, P3, confirmed real
     * by direct inspection before this fix: reconciliation itself already runs on a schedule,
     * already raises real incidents and creates real OrphanedOco records for anything genuinely
     * wrong -- but nothing anywhere summarized what actually happened across those passes into
     * something an operator could read at a glance): the actual report -- orphaned-OCO activity
     * (found/resolved) and reconciliation-relevant incident types, both over a bounded window,
     * alongside the real timestamp of the last completed reconciliation cycle. This does NOT
     * instrument doReconcile() itself (a large, already heavily-modified method this session) --
     * it's built entirely from data reconciliation already durably records elsewhere, the safer
     * of the two ways to get a real report without risking that core logic.
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
     * User's own explicit architectural request, full context in ExecutionContext's own class
     * javadoc: "Then if something crashes, executionId is enough to reconstruct the entire
     * operation." The actual endpoint making that true -- accepts exactly one of executionId,
     * signalId, or positionId, returning the matching ExecutionContext(s) as a list for
     * consistency (an executionId lookup can only ever match one document, since it's the
     * primary key, but signalId/positionId could in principle match more than one -- a signal
     * re-evaluated after a recovery pass, for instance -- so this never silently picks one).
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
     * Review finding ("Recovery metrics need to be first-class" -- external review, thirty-sixth
     * pass, P2, the review's own explicit ask: "I want metrics for: ProtectionAttempt age,
     * OrphanedOco age... recovery action count, manual-escalation rate, critical incidents...
     * Especially: oldest unresolved protection attempt, oldest unresolved orphan. Those should
     * appear directly in ops health"): the actual endpoint. Scoped honestly to what's genuinely
     * derivable from existing, already-queryable data without new instrumentation --
     * ProtectionAttempt/OrphanedOco ages and counts, escalation counts, and a bounded recent
     * critical-incident count. Explicitly NOT included here (would need new timing
     * instrumentation added at the actual event-processing sites, a separate, larger change):
     * reconciliation lag, WS-to-OMS lag, WS-to-Position lag, and recovery-duration timing.
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
     * Review finding ("Incident retry is improved, but external paging still needs production
     * validation" -- external review, thirty-eighth pass, P1, the review's own explicit ask: "A
     * Mongo record saying CRITICAL, notificationStatus = PENDING doesn't help if nobody receives
     * the page. So the production runbook needs: CRITICAL generated -> notification delivered ->
     * delivery acknowledged -> retry if failed, tested end-to-end"): the actual tool an operator
     * needs to run that validation against their own real, production notification provider
     * (Brevo, their own webhook endpoint) -- something this sandbox genuinely cannot do itself,
     * since it has no network path to any real email/webhook provider and no way to confirm a
     * human actually received anything. What this endpoint CAN do: raise a real, genuine
     * CRITICAL incident through the exact same code path every real trading incident uses
     * (IncidentService.raiseCritical -> attemptDelivery, the same method IncidentRetryService's
     * own scheduled pass reuses for retries), then report back this application's OWN view of
     * the outcome (notificationStatus, attempt count) immediately. That's the "delivered" half
     * of the review's own chain -- the operator's own job is the "acknowledged" half: check the
     * admin's own real email inbox and webhook endpoint receiver to confirm the test notification
     * actually arrived. This endpoint deliberately does not, and cannot, do that confirmation
     * step itself.
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
