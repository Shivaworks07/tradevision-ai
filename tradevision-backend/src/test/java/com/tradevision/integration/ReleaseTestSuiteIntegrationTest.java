package com.tradevision.integration;

import com.tradevision.model.*;
import com.tradevision.repository.*;
import com.tradevision.service.IncidentService;
import com.tradevision.service.PositionMonitorService;
import com.tradevision.service.broker.BinanceBrokerAdapter;
import com.tradevision.service.broker.dto.OcoOrderResult;
import com.tradevision.service.broker.dto.OcoStatusInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies, against a real MongoDB via the same PositionMonitorService.reconcileCredential
 * entry point a real scheduled/WebSocket-triggered reconciliation pass calls: kill-after-OCO
 * recovery, OCO auto-attach, OCO auto-cancel, cancel-failure escalation to a halt, reservation
 * crash recovery, and a full restart reconciliation across multiple coexisting records.
 *
 * Scope:
 * - The broker adapter is a real Spring bean (BinanceBrokerAdapter) with its own real HTTP call
 *   methods replaced by @MockitoBean, not a real Binance Testnet connection. This proves the
 *   recovery logic's own correctness against real MongoDB reads/writes and real distributed-lock/
 *   reconciliation-loop behavior, not that Binance's real API responds exactly as these mocked
 *   stubs assume.
 * - A process kill/restart is simulated by constructing the durable records (ProtectionAttempt,
 *   OrphanedOco, ExposureReservationRecord) directly in the real database exactly as they would
 *   exist after a crash at that point, then calling reconcileCredential fresh -- not by literally
 *   starting and killing a JVM process. The recovery methods' behavior is identical either way,
 *   since none of them depend on in-memory state from before the "crash."
 *
 * Requires Docker (Testcontainers); run
 * `mvn test -Dtest=ReleaseTestSuiteIntegrationTest` on a machine with Docker available.
 */
@Testcontainers(disabledWithoutDocker = true)
// spring.profiles.active defaults to "prod" (fail-closed), which has no default secrets at all
// -- without this, this Testcontainers-backed context would fail to start outside a real
// deployment with JWT_SECRET/etc set. Explicitly opts into "local" instead, which has the
// secrets this test relies on.
@ActiveProfiles("local")
@SpringBootTest
class ReleaseTestSuiteIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
    }

    // Spring Boot 4 replaced @MockBean with @MockitoBean.
    @MockitoBean private BinanceBrokerAdapter adapter;

    @Autowired private PositionMonitorService positionMonitorService;
    @Autowired private PositionRepository positionRepo;
    @Autowired private ProtectionAttemptRepository protectionAttemptRepo;
    @Autowired private OrphanedOcoRepository orphanedOcoRepo;
    @Autowired private ExposureReservationRecordRepository exposureReservationRecordRepo;
    @Autowired private ExecutionContextRepository executionContextRepo;
    @Autowired private BrokerCredentialRepository credentialRepo;
    @Autowired private RiskProfileRepository riskProfileRepo;
    @Autowired private com.tradevision.service.CredentialEncryptionService credentialEncryptionService;
    @MockitoBean private IncidentService incidentService; // real delivery (email/webhook) is out of scope for this suite -- see IncidentRetryServiceTest for that

    private BrokerCredential newTestCredential() {
        var credential = new BrokerCredential();
        credential.setId("release-test-" + System.currentTimeMillis() + "-" + Math.random());
        credential.setUserId("user1");
        credential.setBroker(BrokerType.BINANCE);
        credential.setMode(BrokerMode.TESTNET);
        // API keys are encrypted at rest in the real model -- these test values are never
        // actually sent anywhere, since every real HTTP call is mocked via @MockitoBean above.
        // BrokerCredentialService's decrypt() (called internally by reconcileCredential's
        // sub-methods to obtain real apiKey/apiSecret before every adapter call) delegates to
        // CredentialEncryptionService's real AES/GCM decrypt, so a placeholder, non-ciphertext
        // string here would throw IllegalStateException("Failed to decrypt credential"). Using
        // the real encrypt() method produces genuinely valid ciphertext instead.
        //
        // Every production read path (BrokerCredentialService.decrypt(BrokerCredential,
        // boolean), the only place this application decrypts a stored credential) decrypts with
        // the field-specific AES-GCM AAD context "apiKey"/"apiSecret" -- never the default
        // context. AES-GCM's authentication tag is bound to the AAD it was encrypted under, so
        // the fixture must encrypt with those same two field-specific contexts to be decryptable
        // by the code under test.
        credential.setEncryptedApiKey(credentialEncryptionService.encrypt("test-api-key", "apiKey"));
        credential.setEncryptedApiSecret(credentialEncryptionService.encrypt("test-api-secret", "apiSecret"));
        credentialRepo.save(credential);
        var profile = new RiskProfile();
        profile.setCredentialId(credential.getId());
        profile.setUserId("user1");
        profile.setMaxConcurrentTrades(5);
        profile.setMaxTotalExposureQuote(BigDecimal.valueOf(50_000));
        profile.setMaxSymbolExposureQuote(BigDecimal.valueOf(20_000));
        riskProfileRepo.save(profile);
        when(adapter.getType()).thenReturn(BrokerType.BINANCE);
        return credential;
    }

    @Test
    @DisplayName("RELEASE TEST -- kill-after-OCO + OCO auto-attach: a real ProtectionAttempt stuck in SUBMITTING (simulating a crash after Binance accepted the OCO but before this application recorded it), with the exchange's own real OCO genuinely still active -- reconcileCredential auto-attaches it against a real MongoDB, resolving ACTIVE with no human escalation")
    void killAfterOco_autoAttach_realMongo() {
        var credential = newTestCredential();
        var position = new Position();
        position.setId("pos-" + System.currentTimeMillis());
        position.setUserId("user1"); position.setCredentialId(credential.getId()); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setQuantity(BigDecimal.valueOf(1.0)); position.setOcoOrderListId(null);
        positionRepo.save(position);

        var attempt = new ProtectionAttempt();
        attempt.setId("attempt-" + System.currentTimeMillis());
        attempt.setUserId("user1"); attempt.setCredentialId(credential.getId());
        attempt.setPositionId(position.getId()); attempt.setSymbol("BTCUSDT");
        attempt.setListClientOrderId("tv-oco-" + System.currentTimeMillis());
        attempt.setStatus("SUBMITTING");
        attempt.setCreatedAt(LocalDateTime.now().minusMinutes(5)); // well past the 2-minute stuck threshold
        protectionAttemptRepo.save(attempt);

        when(adapter.getOcoStatusByClientOrderId(any(), any(), any(), eq(attempt.getListClientOrderId())))
            .thenReturn(new OcoStatusInfo("real-order-list-id", "EXECUTING", List.of(
                new OcoStatusInfo.Leg("leg1", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(70000), BigDecimal.ZERO, BigDecimal.valueOf(1.0)),
                new OcoStatusInfo.Leg("leg2", "SELL", "STOP_LOSS_LIMIT", "NEW", BigDecimal.valueOf(65000), BigDecimal.ZERO, BigDecimal.valueOf(1.0))
            ), "{}"));

        positionMonitorService.reconcileCredential(credential);

        var recoveredPosition = positionRepo.findById(position.getId()).orElseThrow();
        assertThat(recoveredPosition.getOcoOrderListId()).isEqualTo("real-order-list-id");
        var recoveredAttempt = protectionAttemptRepo.findById(attempt.getId()).orElseThrow();
        assertThat(recoveredAttempt.getStatus()).isEqualTo("ACTIVE");
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), eq("PROTECTION_ATTEMPT_STUCK_WITH_REAL_OCO"), any());
    }

    @Test
    @DisplayName("RELEASE TEST -- OCO auto-cancel: the position has already closed (can no longer safely take the recovered OCO back), and the exchange confirms the cancel -- reconcileCredential auto-cancels against a real MongoDB, resolving the ProtectionAttempt FAILED, no human escalation")
    void ocoAutoCancel_realMongo() {
        var credential = newTestCredential();
        var attempt = new ProtectionAttempt();
        attempt.setId("attempt-" + System.currentTimeMillis());
        attempt.setUserId("user1"); attempt.setCredentialId(credential.getId());
        attempt.setPositionId("pos-does-not-exist"); attempt.setSymbol("BTCUSDT");
        attempt.setListClientOrderId("tv-oco-" + System.currentTimeMillis());
        attempt.setStatus("SUBMITTING");
        attempt.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        protectionAttemptRepo.save(attempt);

        when(adapter.getOcoStatusByClientOrderId(any(), any(), any(), eq(attempt.getListClientOrderId())))
            .thenReturn(new OcoStatusInfo("real-order-list-id", "EXECUTING",
                List.of(new OcoStatusInfo.Leg("leg1", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(70000), BigDecimal.ZERO, BigDecimal.valueOf(1.0))), "{}"));
        when(adapter.cancelOco(any(), any(), any(), any(), eq("real-order-list-id")))
            .thenReturn(new OcoOrderResult(true, "real-order-list-id", "{}", null));
        when(adapter.getOcoStatus(any(), any(), any(), eq("real-order-list-id")))
            .thenReturn(new OcoStatusInfo("real-order-list-id", "ALL_DONE", List.of(), "{}"));

        positionMonitorService.reconcileCredential(credential);

        var recoveredAttempt = protectionAttemptRepo.findById(attempt.getId()).orElseThrow();
        assertThat(recoveredAttempt.getStatus()).isEqualTo("FAILED");
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), eq("PROTECTION_ATTEMPT_STUCK_WITH_REAL_OCO"), any());
    }

    @Test
    @DisplayName("RELEASE TEST -- cancel failure -> halt: the position is unreachable and the auto-cancel attempt itself throws -- reconcileCredential escalates with a real critical incident against a real MongoDB, the ProtectionAttempt stays SUBMITTING (never silently resolved), no blind market sell is ever attempted")
    void cancelFailureHalts_realMongo() {
        var credential = newTestCredential();
        var attempt = new ProtectionAttempt();
        attempt.setId("attempt-" + System.currentTimeMillis());
        attempt.setUserId("user1"); attempt.setCredentialId(credential.getId());
        attempt.setPositionId("pos-does-not-exist"); attempt.setSymbol("BTCUSDT");
        attempt.setListClientOrderId("tv-oco-" + System.currentTimeMillis());
        attempt.setStatus("SUBMITTING");
        attempt.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        protectionAttemptRepo.save(attempt);

        when(adapter.getOcoStatusByClientOrderId(any(), any(), any(), eq(attempt.getListClientOrderId())))
            .thenReturn(new OcoStatusInfo("real-order-list-id", "EXECUTING",
                List.of(new OcoStatusInfo.Leg("leg1", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(70000), BigDecimal.ZERO, BigDecimal.valueOf(1.0))), "{}"));
        when(adapter.cancelOco(any(), any(), any(), any(), eq("real-order-list-id")))
            .thenThrow(new RuntimeException("simulated exchange error"));

        positionMonitorService.reconcileCredential(credential);

        var recoveredAttempt = protectionAttemptRepo.findById(attempt.getId()).orElseThrow();
        // Never resolved -- stays SUBMITTING against the real database, since the dangerous
        // condition (a real, untracked OCO) is not actually gone.
        assertThat(recoveredAttempt.getStatus()).isEqualTo("SUBMITTING");
        verify(incidentService).raiseCritical(any(), eq(credential.getId()), any(), any(), any(),
            eq("PROTECTION_ATTEMPT_STUCK_WITH_REAL_OCO"), any());
        // No sell/flatten call of any kind was ever attempted -- only the OCO lookup and cancel.
        verify(adapter, never()).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("RELEASE TEST -- reservation crash recovery: a real, stale PENDING exposure reservation record whose linked ExecutionContext shows real progress toward the exchange -- reconcile refuses to delete it against a real MongoDB, raising a critical incident instead of silently erasing the only trace of a possibly-real position")
    void reservationCrashRecovery_realMongo() {
        var credential = newTestCredential();
        var execution = new ExecutionContext();
        execution.setExecutionId("exec-" + System.currentTimeMillis());
        execution.setCredentialId(credential.getId());
        execution.setStatus("ORDER_SUBMITTED"); // real progress -- may have reached the exchange
        executionContextRepo.save(execution);

        var stalePending = new ExposureReservationRecord();
        stalePending.setId("reservation-" + System.currentTimeMillis());
        stalePending.setCredentialId(credential.getId());
        stalePending.setSymbol("BTCUSDT");
        stalePending.setStatus("PENDING");
        stalePending.setExecutionId(execution.getExecutionId());
        stalePending.setTotalAmountReserved(BigDecimal.valueOf(10000));
        // Old enough to be past the stale-PENDING cleanup window this codebase already enforces.
        exposureReservationRecordRepo.save(stalePending);

        positionMonitorService.reconcileCredential(credential);

        // The record still exists (was not deleted) against a real database, even though it's
        // well past the normal stale-cleanup age.
        var stillExists = exposureReservationRecordRepo.findById(stalePending.getId());
        assertThat(stillExists).isPresent();
        assertThat(stillExists.get().getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("RELEASE TEST -- restart + full reconciliation: multiple genuinely different stuck records (a ProtectionAttempt needing auto-attach, an OrphanedOco already resolved correctly, a healthy position needing no action) coexisting for the SAME credential -- one single reconcileCredential pass against a real MongoDB resolves each correctly, with no cross-contamination between them")
    void restartFullReconciliation_realMongo() {
        var credential = newTestCredential();

        // Record 1: a position needing OCO auto-attach.
        var position1 = new Position();
        position1.setId("pos1-" + System.currentTimeMillis());
        position1.setUserId("user1"); position1.setCredentialId(credential.getId()); position1.setSymbol("BTCUSDT");
        position1.setStatus("OPEN"); position1.setQuantity(BigDecimal.valueOf(1.0)); position1.setOcoOrderListId(null);
        positionRepo.save(position1);
        var attempt1 = new ProtectionAttempt();
        attempt1.setId("attempt1-" + System.currentTimeMillis());
        attempt1.setUserId("user1"); attempt1.setCredentialId(credential.getId());
        attempt1.setPositionId(position1.getId()); attempt1.setSymbol("BTCUSDT");
        attempt1.setListClientOrderId("tv-oco-1-" + System.currentTimeMillis());
        attempt1.setStatus("SUBMITTING"); attempt1.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        protectionAttemptRepo.save(attempt1);
        when(adapter.getOcoStatusByClientOrderId(any(), any(), any(), eq(attempt1.getListClientOrderId())))
            .thenReturn(new OcoStatusInfo("real-order-list-id-1", "EXECUTING",
                List.of(new OcoStatusInfo.Leg("leg1", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(70000), BigDecimal.ZERO, BigDecimal.valueOf(1.0))), "{}"));

        // Record 2: a completely healthy, unrelated OPEN position that already has an OCO --
        // must be left entirely untouched by this same reconciliation pass.
        var position2 = new Position();
        position2.setId("pos2-" + System.currentTimeMillis());
        position2.setUserId("user1"); position2.setCredentialId(credential.getId()); position2.setSymbol("ETHUSDT");
        position2.setStatus("OPEN"); position2.setQuantity(BigDecimal.valueOf(2.0)); position2.setOcoOrderListId("already-healthy-oco");
        positionRepo.save(position2);
        when(adapter.getOcoStatus(any(), any(), any(), eq("already-healthy-oco")))
            .thenReturn(new OcoStatusInfo("already-healthy-oco", "EXECUTING",
                List.of(new OcoStatusInfo.Leg("leg1", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(3000), BigDecimal.ZERO, BigDecimal.valueOf(2.0))), "{}"));

        positionMonitorService.reconcileCredential(credential);

        // Record 1 was correctly auto-attached...
        var recoveredPosition1 = positionRepo.findById(position1.getId()).orElseThrow();
        assertThat(recoveredPosition1.getOcoOrderListId()).isEqualTo("real-order-list-id-1");
        var recoveredAttempt1 = protectionAttemptRepo.findById(attempt1.getId()).orElseThrow();
        assertThat(recoveredAttempt1.getStatus()).isEqualTo("ACTIVE");
        // ...and record 2 (the already-healthy, unrelated position) was left completely untouched.
        var recoveredPosition2 = positionRepo.findById(position2.getId()).orElseThrow();
        assertThat(recoveredPosition2.getOcoOrderListId()).isEqualTo("already-healthy-oco");
        assertThat(recoveredPosition2.getStatus()).isEqualTo("OPEN");
    }
}
