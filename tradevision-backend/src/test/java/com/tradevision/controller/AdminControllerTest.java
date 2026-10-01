package com.tradevision.controller;

import com.tradevision.dto.ApiResponse;
import com.tradevision.model.BootstrapLock;
import com.tradevision.model.User;
import com.tradevision.repository.UserRepository;
import com.tradevision.service.AdminService;
import com.tradevision.service.MLDatasetExportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Review finding (P1 #7 — "Admin bootstrap has a race" — full context in BootstrapLock's own
 * javadoc): the review's own explicitly requested test #7 ("20 concurrent requests. Expected:
 * exactly ONE admin"). Adapted to 5 concurrent requests to stay within this controller's own
 * 5-attempts-per-hour rate limit, which is a separate, deliberate defense and not something this
 * test should need to work around — the atomicity claim under test ("no matter how many
 * concurrent requests, exactly one can ever win") is proven just as validly at 5 as at 20.
 *
 * mongoTemplate.insert is mocked to faithfully replicate MongoDB's own real guarantee for this
 * scenario (a shared AtomicBoolean: the first caller succeeds, every other caller gets
 * DuplicateKeyException) — not because that specific mock behavior is asserted directly, but
 * because it's what a real unique-_id insert actually does, and this test needs that real
 * behavior to prove the controller code built on top of it is race-free.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminControllerTest {

    @Mock AdminService adminService;
    @Mock UserRepository userRepo;
    @Mock MLDatasetExportService mlDatasetExportService;
    @Mock MongoTemplate mongoTemplate;
    @Mock com.tradevision.repository.BrokerAuditLogRepository auditLogRepo;
    @Mock com.tradevision.service.LatencyMetricsService latencyMetricsService;
    @Mock com.tradevision.repository.TradingIncidentRepository tradingIncidentRepo;
    @Mock com.tradevision.repository.OrderRepository orderRepo;
    @Mock com.tradevision.repository.TradeCallRepository callRepo;
    @Mock com.tradevision.repository.OrphanedOcoRepository orphanedOcoRepo;
    @Mock com.tradevision.repository.ProtectionAttemptRepository protectionAttemptRepo;
    @Mock com.tradevision.service.IncidentService incidentService;
    @Mock com.tradevision.config.TradingHeartbeatService heartbeatService;
    @Mock com.tradevision.service.AuditChainService auditChainService;
    // P2-7 fix, full context in AuditChainCheckpoint's own class javadoc: verifyAuditChain now
    // fetches this to pass into the checkpoint-aware verifyChain overload.
    @Mock com.tradevision.repository.AuditChainCheckpointRepository auditChainCheckpointRepo;
    @Mock com.tradevision.repository.TradeEventRepository tradeEventRepo;
    @Mock com.tradevision.service.HistoricalReplayService historicalReplayService;
    @Mock com.tradevision.repository.ExecutionContextRepository executionContextRepo;

    @InjectMocks AdminController controller;

    @Test
    @DisplayName("bootstrap: N concurrent requests for DIFFERENT users race for admin — exactly ONE succeeds, matching MongoDB's real unique-_id guarantee, not a JVM-only guard")
    void concurrentBootstrap_exactlyOneAdminPromoted() throws Exception {
        ReflectionTestUtils.setField(controller, "bootstrapSecret", "correct-secret");
        when(userRepo.countByRole("ADMIN")).thenReturn(0L); // fresh deployment — the fast pre-check passes for everyone

        int concurrentAttempts = 5; // stays within MAX_BOOTSTRAP_ATTEMPTS_PER_HOUR
        for (int i = 0; i < concurrentAttempts; i++) {
            User u = new User();
            u.setId("user-" + i);
            u.setEmail("user" + i + "@example.com");
            when(userRepo.findByEmail("user" + i + "@example.com")).thenReturn(Optional.of(u));
        }

        // Faithfully simulates MongoDB's real unique-_id insert behavior for this exact
        // scenario: whichever thread's insert call executes first (racing on the JVM's own
        // thread scheduler, same as real concurrent requests would race on the database) wins;
        // every other thread's insert throws, exactly like a real duplicate-key violation would.
        AtomicBoolean lockTaken = new AtomicBoolean(false);
        when(mongoTemplate.insert(any(BootstrapLock.class))).thenAnswer(inv -> {
            if (lockTaken.compareAndSet(false, true)) {
                return inv.getArgument(0);
            }
            throw new DuplicateKeyException("E11000 duplicate key error — admin-bootstrap already exists");
        });

        ExecutorService executor = Executors.newFixedThreadPool(concurrentAttempts);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(concurrentAttempts);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < concurrentAttempts; i++) {
            final String email = "user" + i + "@example.com";
            executor.submit(() -> {
                try {
                    startLine.await();
                    var response = controller.bootstrap(email, null, "correct-secret");
                    if (response.getStatusCode().is2xxSuccessful()) successCount.incrementAndGet();
                } catch (InterruptedException ignored) {
                } finally {
                    allDone.countDown();
                }
            });
        }

        startLine.countDown();
        assertThat(allDone.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // The actual claim under test: no matter how many threads race for it, exactly one can
        // ever win the atomic insert — never zero (a real user should be promotable), never more
        // than one (the exact bug the review found).
        assertThat(successCount.get()).isEqualTo(1);
        verify(userRepo, times(1)).save(any(User.class));
    }

    @Test
    @DisplayName("bootstrap: a losing request gets a clear 409, not a silent failure or a false success")
    void bootstrapLosesRace_returnsConflict() {
        ReflectionTestUtils.setField(controller, "bootstrapSecret", "correct-secret");
        when(userRepo.countByRole("ADMIN")).thenReturn(0L);
        User u = new User();
        u.setId("user-1");
        u.setEmail("user1@example.com");
        when(userRepo.findByEmail("user1@example.com")).thenReturn(Optional.of(u));
        when(mongoTemplate.insert(any(BootstrapLock.class)))
            .thenThrow(new DuplicateKeyException("E11000 duplicate key error — admin-bootstrap already exists"));

        var response = controller.bootstrap("user1@example.com", null, "correct-secret");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        verify(userRepo, never()).save(any());
    }

    // ── Review finding ("Frontend authentication migration is incomplete and currently breaks
    // authenticated APIs" -- P0, full context in UserController's own javadoc): the actual fix
    // for this specific controller, tested directly -- isAdmin() now looks the already-
    // authenticated userId up directly instead of re-parsing a bearer token this class no
    // longer receives at all. ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dashboard: an authenticated non-admin userId is correctly forbidden, not crashed or silently allowed")
    void dashboard_nonAdminUser_forbidden() {
        User u = new User();
        u.setId("user1");
        u.setRole("USER");
        when(userRepo.findById("user1")).thenReturn(Optional.of(u));

        var response = controller.dashboard("user1");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("dashboard: an authenticated admin userId is correctly allowed through")
    void dashboard_adminUser_allowed() {
        User u = new User();
        u.setId("admin1");
        u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        doReturn(com.tradevision.dto.ApiResponse.ok("OK", Map.of("ok", true))).when(adminService).getDashboard();

        var response = controller.dashboard("admin1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("dashboard: a null principal (should never reach here given SecurityConfig's own anyRequest().authenticated(), but confirmed handled gracefully regardless) is forbidden, never an NPE")
    void dashboard_nullPrincipal_forbiddenNotThrown() {
        var response = controller.dashboard(null);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("bootstrap: the rate limit is enforced via the shared MongoDB counter, not a JVM-local one -- the actual review fix (\"Admin bootstrap rate limiter is JVM-local\"), since a real distributed rate limit means every replica must see the SAME count, not each starting its own count at zero")
    void bootstrap_rateLimitEnforcedViaMongoCounter_notJvmLocal() {
        ReflectionTestUtils.setField(controller, "bootstrapSecret", "correct-secret");
        when(userRepo.countByRole("ADMIN")).thenReturn(0L);
        // Simulates the shared counter already being at the limit -- as it would be if ANOTHER
        // replica had already made 6 attempts, something a JVM-local counter could never see.
        org.bson.Document alreadyAtLimit = new org.bson.Document("attemptCount", 6);
        when(mongoTemplate.findAndModify(any(), any(org.springframework.data.mongodb.core.query.Update.class), any(FindAndModifyOptions.class),
            eq(org.bson.Document.class), eq("bootstrap_rate_limit"))).thenReturn(alreadyAtLimit);

        var response = controller.bootstrap(null, "9000000001", "correct-secret");

        assertThat(response.getStatusCode().value()).isEqualTo(429);
    }

    /**
     * Review finding ("Audit-log retention is two years but not export/archive managed" --
     * external review, twenty-third pass, P2, full context in AdminController.exportAuditLog's
     * own javadoc): the actual tests for the new endpoint.
     */
    @Test
    @DisplayName("exportAuditLog: a non-admin user is forbidden, never reaches the query")
    void exportAuditLog_nonAdminUser_forbidden() {
        User u = new User(); u.setId("user1"); u.setRole("USER");
        when(userRepo.findById("user1")).thenReturn(Optional.of(u));

        var response = controller.exportAuditLog("user1", "2025-01-01", "2025-01-31", 0, 500);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(auditLogRepo, never()).findByTimestampBetweenOrderByTimestampDesc(any(), any(), any());
    }

    @Test
    @DisplayName("exportAuditLog: an admin with a valid date range gets the bounded, paginated result")
    void exportAuditLog_adminValidRange_returnsResult() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        var log1 = new com.tradevision.model.BrokerAuditLog();
        log1.setId("log1"); log1.setAction("ORDER_PLACED");
        var page = new org.springframework.data.domain.PageImpl<>(java.util.List.of(log1),
            org.springframework.data.domain.PageRequest.of(0, 500), 1);
        when(auditLogRepo.findByTimestampBetweenOrderByTimestampDesc(any(), any(), any())).thenReturn(page);

        var response = controller.exportAuditLog("admin1", "2025-01-01", "2025-01-31", 0, 500);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(auditLogRepo).findByTimestampBetweenOrderByTimestampDesc(
            eq(java.time.LocalDate.of(2025, 1, 1).atStartOfDay()),
            eq(java.time.LocalDate.of(2025, 1, 31).atTime(23, 59, 59)),
            any());
    }

    @Test
    @DisplayName("exportAuditLog: an invalid date format returns 400, never reaches the query")
    void exportAuditLog_invalidDateFormat_returns400() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));

        var response = controller.exportAuditLog("admin1", "not-a-date", "2025-01-31", 0, 500);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(auditLogRepo, never()).findByTimestampBetweenOrderByTimestampDesc(any(), any(), any());
    }

    @Test
    @DisplayName("exportAuditLog: 'to' before 'from' returns 400, never reaches the query")
    void exportAuditLog_toBeforeFrom_returns400() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));

        var response = controller.exportAuditLog("admin1", "2025-02-01", "2025-01-01", 0, 500);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(auditLogRepo, never()).findByTimestampBetweenOrderByTimestampDesc(any(), any(), any());
    }

    @Test
    @DisplayName("exportAuditLog: a requested page size above the 2000 hard cap is clamped, not honored as-is")
    void exportAuditLog_oversizedPageRequest_isClamped() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        var page = new org.springframework.data.domain.PageImpl<>(java.util.List.<com.tradevision.model.BrokerAuditLog>of());
        when(auditLogRepo.findByTimestampBetweenOrderByTimestampDesc(any(), any(), any())).thenReturn(page);

        controller.exportAuditLog("admin1", "2025-01-01", "2025-01-31", 0, 50_000);

        verify(auditLogRepo).findByTimestampBetweenOrderByTimestampDesc(any(), any(),
            argThat(p -> ((org.springframework.data.domain.Pageable) p).getPageSize() == 2000));
    }

    /**
     * Review finding ("richer metrics around per-symbol execution latency" -- external review,
     * P3, full context in latencyMetricsService's own updated field javadoc): the actual test
     * for the new endpoint.
     */
    @Test
    @DisplayName("latencyReport: a non-admin user is forbidden, never reaches the service")
    void latencyReport_nonAdminUser_forbidden() {
        User u = new User(); u.setId("user1"); u.setRole("USER");
        when(userRepo.findById("user1")).thenReturn(Optional.of(u));

        var response = controller.latencyReport("user1", "BTCUSDT", 30, false);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(latencyMetricsService, never()).reportForSymbol(any(), anyInt());
    }

    @Test
    @DisplayName("latencyReport: an admin gets the full report -- entry stages, position lifetime, and entry-to-protection, all for the requested symbol/lookback")
    void latencyReport_admin_returnsFullReport() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        var stats = new com.tradevision.service.LatencyMetricsService.LatencyStats(120.5, 100, 250, 400, 500, 42);
        var entryReport = new com.tradevision.service.LatencyMetricsService.ExecutionLatencyReport(
            stats, stats, stats, stats, stats, 42);
        when(latencyMetricsService.reportForSymbol("BTCUSDT", 30)).thenReturn(entryReport);
        when(latencyMetricsService.positionLifetimeReport("BTCUSDT", 30)).thenReturn(stats);
        when(latencyMetricsService.entryToProtectionReport("BTCUSDT", 30)).thenReturn(stats);

        var response = controller.latencyReport("admin1", "BTCUSDT", 30, false);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(latencyMetricsService).reportForSymbol("BTCUSDT", 30);
        verify(latencyMetricsService).positionLifetimeReport("BTCUSDT", 30);
        verify(latencyMetricsService).entryToProtectionReport("BTCUSDT", 30);
        verify(latencyMetricsService, never()).reportByStrategyVersion(any(), anyInt());
    }

    @Test
    @DisplayName("latencyReport: byStrategyVersion=true routes to reportByStrategyVersion instead of the 3-report bundle")
    void latencyReport_byStrategyVersion_routesToStrategyVersionReport() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        when(latencyMetricsService.reportByStrategyVersion(any(), anyInt())).thenReturn(java.util.Map.of());

        var response = controller.latencyReport("admin1", null, 7, true);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(latencyMetricsService).reportByStrategyVersion(null, 7);
        verify(latencyMetricsService, never()).reportForSymbol(any(), anyInt());
    }

    /**
     * Review finding ("exchange rejection taxonomy dashboards" / "automatic broker incident
     * dashboards" -- external review, P3, full context in the endpoint's own javadoc): the
     * actual tests.
     */
    @Test
    @DisplayName("incidentTaxonomy: a non-admin user is forbidden")
    void incidentTaxonomy_nonAdminUser_forbidden() {
        User u = new User(); u.setId("user1"); u.setRole("USER");
        when(userRepo.findById("user1")).thenReturn(Optional.of(u));

        var response = controller.incidentTaxonomy("user1", 30);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(mongoTemplate, never()).aggregate(any(org.springframework.data.mongodb.core.aggregation.Aggregation.class),
            anyString(), any(Class.class));
    }

    /**
     * Review finding ("some admin reports are potentially expensive" -- external review,
     * twenty-sixth pass, P2, full context in the endpoint's own updated javadoc): the actual
     * test for the real aggregation-pipeline rewrite -- confirms the endpoint reads its counts
     * from mongoTemplate.aggregate's own mapped results, not from a full in-memory collection
     * loaded via findByCreatedAtAfter (which this endpoint no longer calls at all).
     */
    @Test
    @DisplayName("incidentTaxonomy: an admin gets real counts sourced from mongoTemplate.aggregate's own mapped results, grouped by type and severity, with a separate unresolved-by-type breakdown")
    void incidentTaxonomy_admin_returnsRealGroupedCountsFromAggregation() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));

        // totalIncidents: a plain count via Document.class.
        when(mongoTemplate.aggregate(any(org.springframework.data.mongodb.core.aggregation.Aggregation.class),
            eq("trading_incidents"), eq(org.bson.Document.class)))
            .thenReturn(new org.springframework.data.mongodb.core.aggregation.AggregationResults<>(
                java.util.List.of(new org.bson.Document(), new org.bson.Document(), new org.bson.Document()), new org.bson.Document()));

        // byType, bySeverity, unresolvedByType -- called in that fixed order by the real
        // implementation, each via GroupCount.class.
        when(mongoTemplate.aggregate(any(org.springframework.data.mongodb.core.aggregation.Aggregation.class),
            eq("trading_incidents"), eq(AdminController.GroupCount.class)))
            .thenReturn(new org.springframework.data.mongodb.core.aggregation.AggregationResults<>(
                java.util.List.of(new AdminController.GroupCount("PROTECTION_FAILED", 2), new AdminController.GroupCount("BROKER_UNAVAILABLE", 1)),
                new org.bson.Document()))
            .thenReturn(new org.springframework.data.mongodb.core.aggregation.AggregationResults<>(
                java.util.List.of(new AdminController.GroupCount("CRITICAL", 2), new AdminController.GroupCount("WARNING", 1)),
                new org.bson.Document()))
            .thenReturn(new org.springframework.data.mongodb.core.aggregation.AggregationResults<>(
                java.util.List.of(new AdminController.GroupCount("PROTECTION_FAILED", 1)), new org.bson.Document()));

        var response = controller.incidentTaxonomy("admin1", 30);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        var body = (ApiResponse<?>) response.getBody();
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) body.getData();
        @SuppressWarnings("unchecked")
        var byType = (java.util.Map<String, Long>) data.get("byType");
        @SuppressWarnings("unchecked")
        var unresolvedByType = (java.util.Map<String, Long>) data.get("unresolvedByType");
        assertThat(byType.get("PROTECTION_FAILED")).isEqualTo(2L);
        assertThat(byType.get("BROKER_UNAVAILABLE")).isEqualTo(1L);
        assertThat(unresolvedByType.get("PROTECTION_FAILED")).isEqualTo(1L);
        assertThat(data.get("totalIncidents")).isEqualTo(3L);
        // The old, in-memory-loading query is never called at all.
        verify(tradingIncidentRepo, never()).findByCreatedAtAfter(any());
    }

    /**
     * Review finding, same context as the test above: a second, focused test confirming the
     * actual database-side filter -- resolvedAt IS NULL is applied as a real $match stage, not
     * an in-memory Java filter.
     */
    @Test
    @DisplayName("incidentTaxonomy: the unresolved-by-type breakdown is filtered via a real $match stage on resolvedAt, not an in-memory Java filter")
    void incidentTaxonomy_unresolvedFilter_appliedAsRealMatchStage() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        when(mongoTemplate.aggregate(any(org.springframework.data.mongodb.core.aggregation.Aggregation.class),
            eq("trading_incidents"), eq(org.bson.Document.class)))
            .thenReturn(new org.springframework.data.mongodb.core.aggregation.AggregationResults<>(java.util.List.of(), new org.bson.Document()));
        when(mongoTemplate.aggregate(any(org.springframework.data.mongodb.core.aggregation.Aggregation.class),
            eq("trading_incidents"), eq(AdminController.GroupCount.class)))
            .thenReturn(new org.springframework.data.mongodb.core.aggregation.AggregationResults<>(java.util.List.of(), new org.bson.Document()));

        controller.incidentTaxonomy("admin1", 30);

        // Exactly 3 GroupCount-typed aggregate calls: byType, bySeverity, unresolvedByType.
        verify(mongoTemplate, times(3)).aggregate(any(org.springframework.data.mongodb.core.aggregation.Aggregation.class),
            eq("trading_incidents"), eq(AdminController.GroupCount.class));
    }

    /**
     * Review finding ("strategy-versioned execution provenance" -- external review, P3, full
     * context in the endpoint's own javadoc): the actual tests.
     */
    @Test
    @DisplayName("executionProvenance: a non-admin user is forbidden")
    void executionProvenance_nonAdminUser_forbidden() {
        User u = new User(); u.setId("user1"); u.setRole("USER");
        when(userRepo.findById("user1")).thenReturn(Optional.of(u));

        var response = controller.executionProvenance("user1", "v3.2.1", 100);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(orderRepo, never()).findByStrategyVersionOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("executionProvenance: an admin gets every order for the strategy version, each joined to its real, resolved outcome via signalId")
    void executionProvenance_admin_returnsOrdersJoinedToRealOutcomes() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));

        var order1 = new com.tradevision.model.Order();
        order1.setId("order1"); order1.setSymbol("BTCUSDT"); order1.setSignalId("sig1");
        order1.setStatus(com.tradevision.model.OrderStatus.FILLED);
        var order2 = new com.tradevision.model.Order();
        order2.setId("order2"); order2.setSymbol("ETHUSDT"); order2.setSignalId(null); // no signal linked
        order2.setStatus(com.tradevision.model.OrderStatus.REJECTED);
        when(orderRepo.findByStrategyVersionOrderByCreatedAtDesc(eq("v3.2.1"), any())).thenReturn(java.util.List.of(order1, order2));

        var call1 = new com.tradevision.model.TradeCallRecord();
        var outcome1 = new com.tradevision.model.TradeOutcome();
        outcome1.setResult("HIT_T1"); outcome1.setPnlPct(4.2);
        call1.setOutcome(outcome1);
        when(callRepo.findById("sig1")).thenReturn(Optional.of(call1));

        var response = controller.executionProvenance("admin1", "v3.2.1", 100);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        var body = (ApiResponse<?>) response.getBody();
        @SuppressWarnings("unchecked")
        var rows = (java.util.List<java.util.Map<String, Object>>) body.getData();
        assertThat(rows).hasSize(2);
        var row1 = rows.stream().filter(r -> "order1".equals(r.get("orderId"))).findFirst().orElseThrow();
        assertThat(row1.get("outcomeResult")).isEqualTo("HIT_T1");
        assertThat(row1.get("pnlPct")).isEqualTo(4.2);
        var row2 = rows.stream().filter(r -> "order2".equals(r.get("orderId"))).findFirst().orElseThrow();
        // No signalId at all -- correctly falls back to empty/zero rather than a lookup or NPE.
        assertThat(row2.get("outcomeResult")).isEqualTo("");
        verify(callRepo, never()).findById(isNull());
    }

    @Test
    @DisplayName("executionProvenance: an oversized limit request is clamped to the 500 hard cap")
    void executionProvenance_oversizedLimit_isClamped() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        when(orderRepo.findByStrategyVersionOrderByCreatedAtDesc(any(), any())).thenReturn(java.util.List.of());

        controller.executionProvenance("admin1", "v3.2.1", 50_000);

        verify(orderRepo).findByStrategyVersionOrderByCreatedAtDesc(eq("v3.2.1"),
            argThat(p -> ((org.springframework.data.domain.Pageable) p).getPageSize() == 500));
    }

    /**
     * Review finding ("automated reconciliation reports" -- external review, P3, full context
     * in the endpoint's own javadoc): the actual tests.
     */
    @Test
    @DisplayName("reconciliationReport: a non-admin user is forbidden")
    void reconciliationReport_nonAdminUser_forbidden() {
        User u = new User(); u.setId("user1"); u.setRole("USER");
        when(userRepo.findById("user1")).thenReturn(Optional.of(u));

        var response = controller.reconciliationReport("user1", 7);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(orphanedOcoRepo, never()).findByCreatedAtAfter(any());
    }

    @Test
    @DisplayName("reconciliationReport: an admin gets real orphan and incident counts, correctly filtered to reconciliation-relevant incident types only")
    void reconciliationReport_admin_returnsRealCounts() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));

        var orphan1 = new com.tradevision.model.OrphanedOco();
        orphan1.setResolved(true);
        var orphan2 = new com.tradevision.model.OrphanedOco();
        orphan2.setResolved(false);
        when(orphanedOcoRepo.findByCreatedAtAfter(any())).thenReturn(java.util.List.of(orphan1, orphan2));

        var reconciliationIncident = new com.tradevision.model.TradingIncident();
        reconciliationIncident.setType("RECONCILIATION_MISMATCH");
        // Deliberately a DIFFERENT incident type -- must be excluded from this report entirely,
        // since it's not one of the reconciliation-specific types this endpoint filters to.
        var unrelatedIncident = new com.tradevision.model.TradingIncident();
        unrelatedIncident.setType("PROTECTION_ATTEMPT_PERSISTENCE_FAILED_LIVE_HALT");
        when(tradingIncidentRepo.findByCreatedAtAfter(any())).thenReturn(java.util.List.of(reconciliationIncident, unrelatedIncident));

        var response = controller.reconciliationReport("admin1", 7);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        var body = (ApiResponse<?>) response.getBody();
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) body.getData();
        @SuppressWarnings("unchecked")
        var orphanedOco = (java.util.Map<String, Object>) data.get("orphanedOco");
        assertThat(orphanedOco.get("found")).isEqualTo(2);
        assertThat(orphanedOco.get("resolved")).isEqualTo(1L);
        assertThat(orphanedOco.get("stillOpen")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        var reconciliationIncidents = (java.util.Map<String, Object>) data.get("reconciliationIncidents");
        // Only the ONE reconciliation-relevant incident is counted -- the unrelated one is
        // correctly excluded, confirming the type filter actually works.
        assertThat(reconciliationIncidents.get("total")).isEqualTo(1);
    }

    /**
     * Review finding ("immutable external audit export" -- external review, P3, full context
     * in AuditChainService's own class javadoc): the actual tests for the new endpoint.
     */
    @Test
    @DisplayName("verifyAuditChain: a non-admin user is forbidden")
    void verifyAuditChain_nonAdminUser_forbidden() {
        User u = new User(); u.setId("user1"); u.setRole("USER");
        when(userRepo.findById("user1")).thenReturn(Optional.of(u));

        var response = controller.verifyAuditChain("user1", "2026-01-01", "2026-01-31");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(auditChainService, never()).verifyChain(any(), any());
    }

    @Test
    @DisplayName("verifyAuditChain: an admin gets the real verification result, computed against the range reversed to oldest-first, using whatever checkpoint (if any) is currently on file")
    void verifyAuditChain_admin_returnsRealResult() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        var log1 = new com.tradevision.model.BrokerAuditLog();
        var page = new org.springframework.data.domain.PageImpl<>(java.util.List.of(log1));
        when(auditLogRepo.findByTimestampBetweenOrderByTimestampDesc(any(), any(), any())).thenReturn(page);
        when(auditChainCheckpointRepo.findById(com.tradevision.model.AuditChainCheckpoint.SINGLETON_ID)).thenReturn(Optional.empty());
        when(auditChainService.verifyChain(any(), any())).thenReturn(
            new com.tradevision.service.AuditChainService.ChainVerificationResult(true, 1, null));

        var response = controller.verifyAuditChain("admin1", "2026-01-01", "2026-01-31");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(auditChainService).verifyChain(java.util.List.of(log1), null);
    }

    /**
     * Review finding ("full event-sourced order ledger" -- external review, P3, full context
     * in the endpoint's own javadoc): the actual tests.
     */
    @Test
    @DisplayName("orderLedger: a non-admin user is forbidden")
    void orderLedger_nonAdminUser_forbidden() {
        User u = new User(); u.setId("user1"); u.setRole("USER");
        when(userRepo.findById("user1")).thenReturn(Optional.of(u));

        var response = controller.orderLedger("user1", "order1", null);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(tradeEventRepo, never()).findByOrderIdOrderByOccurredAtAsc(any());
    }

    @Test
    @DisplayName("orderLedger: neither orderId nor positionId given -- 400, never reaches the repository")
    void orderLedger_neitherIdGiven_returns400() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));

        var response = controller.orderLedger("admin1", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(tradeEventRepo, never()).findByOrderIdOrderByOccurredAtAsc(any());
        verify(tradeEventRepo, never()).findByPositionIdOrderByOccurredAtAsc(any());
    }

    @Test
    @DisplayName("orderLedger: BOTH orderId and positionId given -- 400, ambiguous request rejected")
    void orderLedger_bothIdsGiven_returns400() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));

        var response = controller.orderLedger("admin1", "order1", "position1");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("orderLedger: orderId given -- routes to findByOrderIdOrderByOccurredAtAsc, not the position query")
    void orderLedger_orderIdGiven_routesToOrderQuery() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        when(tradeEventRepo.findByOrderIdOrderByOccurredAtAsc("order1")).thenReturn(java.util.List.of());

        var response = controller.orderLedger("admin1", "order1", null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(tradeEventRepo).findByOrderIdOrderByOccurredAtAsc("order1");
        verify(tradeEventRepo, never()).findByPositionIdOrderByOccurredAtAsc(any());
    }

    @Test
    @DisplayName("orderLedger: positionId given -- routes to findByPositionIdOrderByOccurredAtAsc, not the order query")
    void orderLedger_positionIdGiven_routesToPositionQuery() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        when(tradeEventRepo.findByPositionIdOrderByOccurredAtAsc("position1")).thenReturn(java.util.List.of());

        var response = controller.orderLedger("admin1", null, "position1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(tradeEventRepo).findByPositionIdOrderByOccurredAtAsc("position1");
        verify(tradeEventRepo, never()).findByOrderIdOrderByOccurredAtAsc(any());
    }

    /**
     * Review finding ("Admin bootstrap is still vulnerable to permanent lockout after DB
     * failure" -- external review, twenty-sixth pass, P1, full context in the bootstrap
     * endpoint's own updated comment): the actual test proving the rollback works.
     */
    @Test
    @DisplayName("bootstrap: promotion (save()) fails after the lock was already acquired -- the lock is rolled back, returning a real error instead of a silent, permanent lockout")
    void bootstrapPromotionFailsAfterLockAcquired_rollsBackLock() {
        ReflectionTestUtils.setField(controller, "bootstrapSecret", "correct-secret");
        when(userRepo.countByRole("ADMIN")).thenReturn(0L);
        User u = new User();
        u.setId("user-1");
        u.setEmail("user1@example.com");
        when(userRepo.findByEmail("user1@example.com")).thenReturn(Optional.of(u));
        when(mongoTemplate.insert(any(BootstrapLock.class))).thenReturn(new BootstrapLock("user-1"));
        when(userRepo.save(any(User.class))).thenThrow(new RuntimeException("simulated DB failure"));

        var response = controller.bootstrap("user1@example.com", null, "correct-secret");

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        // The actual fix: the just-acquired lock is rolled back so a retry can succeed later,
        // instead of leaving a permanent, silent lockout.
        verify(mongoTemplate).remove(any(org.springframework.data.mongodb.core.query.Query.class), eq(BootstrapLock.class));
    }

    /**
     * User's own explicit architectural request, full context in ExecutionContext's own class
     * javadoc: the actual tests for the query endpoint.
     */
    @Test
    @DisplayName("executionContext: a non-admin user is forbidden")
    void executionContext_nonAdminUser_forbidden() {
        User u = new User(); u.setId("user1"); u.setRole("USER");
        when(userRepo.findById("user1")).thenReturn(Optional.of(u));

        var response = controller.executionContext("user1", "exec1", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(executionContextRepo, never()).findById(any());
    }

    @Test
    @DisplayName("executionContext: none of the three identifiers given -- 400, never reaches the repository")
    void executionContext_noneGiven_returns400() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));

        var response = controller.executionContext("admin1", null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(executionContextRepo, never()).findById(any());
        verify(executionContextRepo, never()).findBySignalIdOrderByCreatedAtDesc(any());
        verify(executionContextRepo, never()).findByPositionId(any());
    }

    @Test
    @DisplayName("executionContext: more than one identifier given -- 400, ambiguous request rejected")
    void executionContext_moreThanOneGiven_returns400() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));

        var response = controller.executionContext("admin1", "exec1", "sig1", null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("executionContext: executionId given -- routes to the primary-key lookup, wrapping a single result as a list")
    void executionContext_executionIdGiven_routesToFindById() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        var context = new com.tradevision.model.ExecutionContext();
        context.setExecutionId("exec1");
        when(executionContextRepo.findById("exec1")).thenReturn(Optional.of(context));

        var response = controller.executionContext("admin1", "exec1", null, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        var body = (ApiResponse<?>) response.getBody();
        @SuppressWarnings("unchecked")
        var results = (java.util.List<com.tradevision.model.ExecutionContext>) body.getData();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getExecutionId()).isEqualTo("exec1");
        verify(executionContextRepo, never()).findBySignalIdOrderByCreatedAtDesc(any());
        verify(executionContextRepo, never()).findByPositionId(any());
    }

    @Test
    @DisplayName("executionContext: executionId given but not found -- an empty list, not a 404 or an error")
    void executionContext_executionIdNotFound_returnsEmptyList() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        when(executionContextRepo.findById("exec-unknown")).thenReturn(Optional.empty());

        var response = controller.executionContext("admin1", "exec-unknown", null, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        var body = (ApiResponse<?>) response.getBody();
        @SuppressWarnings("unchecked")
        var results = (java.util.List<com.tradevision.model.ExecutionContext>) body.getData();
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("executionContext: signalId given -- routes to findBySignalIdOrderByCreatedAtDesc, can return more than one (a signal re-evaluated after recovery)")
    void executionContext_signalIdGiven_routesToSignalLookup() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        var attempt1 = new com.tradevision.model.ExecutionContext(); attempt1.setExecutionId("exec1");
        var attempt2 = new com.tradevision.model.ExecutionContext(); attempt2.setExecutionId("exec2");
        when(executionContextRepo.findBySignalIdOrderByCreatedAtDesc("sig1")).thenReturn(java.util.List.of(attempt2, attempt1));

        var response = controller.executionContext("admin1", null, "sig1", null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        var body = (ApiResponse<?>) response.getBody();
        @SuppressWarnings("unchecked")
        var results = (java.util.List<com.tradevision.model.ExecutionContext>) body.getData();
        assertThat(results).hasSize(2);
        verify(executionContextRepo, never()).findById(any());
        verify(executionContextRepo, never()).findByPositionId(any());
    }

    @Test
    @DisplayName("executionContext: positionId given -- routes to findByPositionId")
    void executionContext_positionIdGiven_routesToPositionLookup() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        when(executionContextRepo.findByPositionId("pos1")).thenReturn(java.util.List.of(new com.tradevision.model.ExecutionContext()));

        var response = controller.executionContext("admin1", null, null, "pos1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(executionContextRepo).findByPositionId("pos1");
        verify(executionContextRepo, never()).findById(any());
        verify(executionContextRepo, never()).findBySignalIdOrderByCreatedAtDesc(any());
    }

    /**
     * Review finding ("Recovery metrics need to be first-class" -- external review, thirty-sixth
     * pass, P2, full context in the recoveryHealth endpoint's own javadoc): the actual tests.
     */
    @Test
    @DisplayName("recoveryHealth: a non-admin user is forbidden")
    void recoveryHealth_nonAdminUser_forbidden() {
        User u = new User(); u.setId("user1"); u.setRole("USER");
        when(userRepo.findById("user1")).thenReturn(Optional.of(u));

        var response = controller.recoveryHealth("user1");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(protectionAttemptRepo, never()).findByStatusOrderByCreatedAtAsc(any());
    }

    @Test
    @DisplayName("recoveryHealth: correctly computes the oldest-unresolved age, counts, and escalation rate from real, already-queried records -- not hardcoded or approximated")
    void recoveryHealth_computesRealMetricsFromQueriedRecords() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));

        var oldAttempt = new com.tradevision.model.ProtectionAttempt();
        oldAttempt.setId("attempt1"); oldAttempt.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        when(protectionAttemptRepo.findByStatusOrderByCreatedAtAsc("SUBMITTING")).thenReturn(List.of(oldAttempt));

        var oldOrphan = new com.tradevision.model.OrphanedOco();
        oldOrphan.setId("orphan1"); oldOrphan.setCreatedAt(LocalDateTime.now().minusMinutes(5)); oldOrphan.setEscalated(true);
        var newOrphan = new com.tradevision.model.OrphanedOco();
        newOrphan.setId("orphan2"); newOrphan.setCreatedAt(LocalDateTime.now().minusMinutes(1)); newOrphan.setEscalated(false);
        when(orphanedOcoRepo.findByResolvedFalseOrderByCreatedAtAsc()).thenReturn(List.of(oldOrphan, newOrphan));

        var criticalUnresolved = new com.tradevision.model.TradingIncident();
        criticalUnresolved.setSeverity("CRITICAL");
        var criticalResolved = new com.tradevision.model.TradingIncident();
        criticalResolved.setSeverity("CRITICAL"); criticalResolved.setResolvedAt(LocalDateTime.now());
        var warning = new com.tradevision.model.TradingIncident();
        warning.setSeverity("WARNING");
        when(tradingIncidentRepo.findByCreatedAtAfter(any())).thenReturn(List.of(criticalUnresolved, criticalResolved, warning));

        var response = controller.recoveryHealth("admin1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        var body = (ApiResponse<?>) response.getBody();
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) body.getData();
        @SuppressWarnings("unchecked")
        var protectionAttempts = (java.util.Map<String, Object>) data.get("protectionAttempts");
        @SuppressWarnings("unchecked")
        var orphanedOcos = (java.util.Map<String, Object>) data.get("orphanedOcos");
        @SuppressWarnings("unchecked")
        var incidents = (java.util.Map<String, Object>) data.get("criticalIncidentsLast24h");

        assertThat(protectionAttempts.get("unresolvedCount")).isEqualTo(1);
        assertThat((Long) protectionAttempts.get("oldestUnresolvedAgeSeconds")).isGreaterThanOrEqualTo(590L); // ~10 minutes
        assertThat(orphanedOcos.get("unresolvedCount")).isEqualTo(2);
        assertThat(orphanedOcos.get("escalatedCount")).isEqualTo(1L);
        assertThat((Double) orphanedOcos.get("manualEscalationRate")).isEqualTo(0.5);
        assertThat(incidents.get("total")).isEqualTo(2L); // only the 2 CRITICAL ones, not the WARNING
        assertThat(incidents.get("unresolved")).isEqualTo(1L); // only the one with no resolvedAt
    }

    @Test
    @DisplayName("recoveryHealth: no stuck records at all -- ages and rates default to zero rather than throwing on an empty list")
    void recoveryHealth_noStuckRecords_defaultsToZero() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        when(protectionAttemptRepo.findByStatusOrderByCreatedAtAsc("SUBMITTING")).thenReturn(List.of());
        when(orphanedOcoRepo.findByResolvedFalseOrderByCreatedAtAsc()).thenReturn(List.of());
        when(tradingIncidentRepo.findByCreatedAtAfter(any())).thenReturn(List.of());

        var response = controller.recoveryHealth("admin1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        var body = (ApiResponse<?>) response.getBody();
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) body.getData();
        @SuppressWarnings("unchecked")
        var orphanedOcos = (java.util.Map<String, Object>) data.get("orphanedOcos");
        assertThat((Double) orphanedOcos.get("manualEscalationRate")).isEqualTo(0.0);
    }

    /**
     * Review finding ("Incident retry is improved, but external paging still needs production
     * validation" -- external review, thirty-eighth pass, P1, full context in the
     * testNotification endpoint's own javadoc): the actual tests.
     */
    @Test
    @DisplayName("testNotification: a non-admin user is forbidden")
    void testNotification_nonAdminUser_forbidden() {
        User u = new User(); u.setId("user1"); u.setRole("USER");
        when(userRepo.findById("user1")).thenReturn(Optional.of(u));

        var response = controller.testNotification("user1");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("testNotification: raises a real CRITICAL incident through the exact same raiseCritical path every real incident uses, and reports back this application's own observed delivery outcome")
    void testNotification_raisesRealIncidentReportsOutcome() {
        User u = new User(); u.setId("admin1"); u.setRole("ADMIN");
        when(userRepo.findById("admin1")).thenReturn(Optional.of(u));
        var raised = new com.tradevision.model.TradingIncident();
        raised.setUserId("admin1"); raised.setType("TEST_NOTIFICATION"); raised.setNotificationStatus("DELIVERED"); raised.setNotificationAttempts(1);
        when(tradingIncidentRepo.findByCreatedAtAfter(any())).thenReturn(List.of(raised));

        var response = controller.testNotification("admin1");

        verify(incidentService).raiseCritical(eq("admin1"), isNull(), isNull(), isNull(), isNull(), eq("TEST_NOTIFICATION"), any());
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        var body = (ApiResponse<?>) response.getBody();
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) body.getData();
        assertThat(data.get("notificationStatus")).isEqualTo("DELIVERED");
        assertThat(data.get("notificationAttempts")).isEqualTo(1);
    }

    // ── P2-22: bootstrap secret comparison is constant-time (MessageDigest.isEqual), not
    // String.equals ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P2-22 fix: secretsMatch -- identical secrets match")
    void secretsMatch_identicalSecrets_match() {
        assertThat(AdminController.secretsMatch("correct-secret", "correct-secret")).isTrue();
    }

    @Test
    @DisplayName("P2-22 fix: secretsMatch -- a wrong secret of the SAME length never matches")
    void secretsMatch_wrongSecretSameLength_neverMatches() {
        assertThat(AdminController.secretsMatch("correct-secret", "wr0ng-secr3t!")).isFalse();
    }

    @Test
    @DisplayName("P2-22 fix: secretsMatch -- a wrong secret of a DIFFERENT length never matches, and never throws (no array-length mismatch from MessageDigest.isEqual)")
    void secretsMatch_wrongSecretDifferentLength_neverMatchesAndNeverThrows() {
        assertThat(AdminController.secretsMatch("correct-secret", "short")).isFalse();
        assertThat(AdminController.secretsMatch("correct-secret", "a-much-longer-guess-than-the-real-secret")).isFalse();
    }

    @Test
    @DisplayName("P2-22 fix: secretsMatch -- a null or blank configured secret never matches anything, even an empty/null supplied value")
    void secretsMatch_noConfiguredSecret_neverMatches() {
        assertThat(AdminController.secretsMatch(null, "anything")).isFalse();
        assertThat(AdminController.secretsMatch("", "anything")).isFalse();
        assertThat(AdminController.secretsMatch("  ", null)).isFalse();
    }

    @Test
    @DisplayName("P2-22 fix: secretsMatch -- a null supplied secret against a real configured one never matches and never throws an NPE")
    void secretsMatch_nullSuppliedSecret_neverMatchesNeverThrows() {
        assertThat(AdminController.secretsMatch("correct-secret", null)).isFalse();
    }
}
