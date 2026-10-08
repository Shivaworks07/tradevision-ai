package com.tradevision.service;

import com.tradevision.config.ShutdownState;
import com.tradevision.config.StartupState;
import com.tradevision.config.TradingHeartbeatService;
import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.BrokerType;
import com.tradevision.model.RiskProfile;
import com.tradevision.repository.BrokerCredentialRepository;
import com.tradevision.repository.RiskProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

/**
 * Verifies OpsStatusService's aggregation of operational state, including the "credential with
 * no risk profile yet" case, rather than silently defaulting or skipping it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpsStatusServiceTest {

    @Mock BrokerCredentialRepository credentialRepo;
    @Mock RiskProfileRepository riskProfileRepo;
    @Mock TradingHeartbeatService heartbeatService;
    @Mock StartupState startupState;
    @Mock ShutdownState shutdownState;
    @Mock ExchangeHealthService exchangeHealthService;
    @Mock BrokerCredentialService credentialService;

    @InjectMocks OpsStatusService service;

    @BeforeEach
    void setup() {
        when(startupState.getPhase()).thenReturn(StartupState.Phase.TRADING_ENABLED);
        when(startupState.isTradingEnabled()).thenReturn(true);
        when(shutdownState.isShuttingDown()).thenReturn(false);
        when(credentialService.decrypt(any(), anyBoolean())).thenReturn("fake-api-key");
    }

    private BrokerCredential credential(String id, BrokerMode mode) {
        BrokerCredential c = new BrokerCredential();
        c.setId(id);
        c.setBroker(BrokerType.BINANCE);
        c.setMode(mode);
        return c;
    }

    @Test
    @DisplayName("getStatus: aggregates startup phase, tradingEnabled, and shuttingDown correctly")
    void getStatus_aggregatesGlobalState() {
        when(credentialRepo.findByUserIdAndActiveTrue("user1")).thenReturn(List.of());

        var status = service.getStatus("user1");

        assertThat(status.startupPhase()).isEqualTo("TRADING_ENABLED");
        assertThat(status.tradingEnabled()).isTrue();
        assertThat(status.shuttingDown()).isFalse();
    }

    @Test
    @DisplayName("getStatus: includes real heartbeat timestamps from TradingHeartbeatService")
    void getStatus_includesRealHeartbeats() {
        Instant scanTime = Instant.now().minusSeconds(30);
        Instant reconcileTime = Instant.now().minusSeconds(45);
        when(credentialRepo.findByUserIdAndActiveTrue("user1")).thenReturn(List.of());
        when(heartbeatService.getLastScanCompletedAt()).thenReturn(scanTime);
        when(heartbeatService.getLastReconciliationCompletedAt()).thenReturn(reconcileTime);

        var status = service.getStatus("user1");

        assertThat(status.lastScanCompletedAt()).isEqualTo(scanTime);
        assertThat(status.lastReconciliationCompletedAt()).isEqualTo(reconcileTime);
    }

    @Test
    @DisplayName("getStatus: a credential WITH a risk profile reports its real halt flags, LIVE authorization, and consecutive-loss count")
    void getStatus_credentialWithProfile_reportsRealState() {
        BrokerCredential cred = credential("cred1", BrokerMode.LIVE);
        when(credentialRepo.findByUserIdAndActiveTrue("user1")).thenReturn(List.of(cred));

        RiskProfile profile = new RiskProfile();
        profile.setTradingHalted(true);
        profile.setHaltReason("daily loss limit reached");
        profile.setAutoTradeHalted(true);
        profile.setAutoTradeHaltReason("5 consecutive losses");
        profile.setLiveAutoTradeAuthorized(true);
        profile.setConsecutiveAutoTradeLosses(5);
        when(riskProfileRepo.findByUserIdAndCredentialId("user1", "cred1")).thenReturn(Optional.of(profile));

        var status = service.getStatus("user1");

        assertThat(status.credentials()).hasSize(1);
        var c = status.credentials().get(0);
        assertThat(c.credentialId()).isEqualTo("cred1");
        assertThat(c.mode()).isEqualTo("LIVE");
        assertThat(c.tradingHalted()).isTrue();
        assertThat(c.haltReason()).isEqualTo("daily loss limit reached");
        assertThat(c.autoTradeHalted()).isTrue();
        assertThat(c.autoTradeHaltReason()).isEqualTo("5 consecutive losses");
        assertThat(c.liveAutoTradeAuthorized()).isTrue();
        assertThat(c.consecutiveAutoTradeLosses()).isEqualTo(5);
        assertThat(c.hasRiskProfile()).isTrue();
    }

    @Test
    @DisplayName("getStatus: a credential with NO risk profile yet reports hasRiskProfile=false honestly, not silently defaulted or skipped")
    void getStatus_credentialWithoutProfile_reportsHonestly() {
        BrokerCredential cred = credential("cred2", BrokerMode.TESTNET);
        when(credentialRepo.findByUserIdAndActiveTrue("user1")).thenReturn(List.of(cred));
        when(riskProfileRepo.findByUserIdAndCredentialId("user1", "cred2")).thenReturn(Optional.empty());

        var status = service.getStatus("user1");

        assertThat(status.credentials()).hasSize(1); // NOT skipped
        var c = status.credentials().get(0);
        assertThat(c.credentialId()).isEqualTo("cred2");
        assertThat(c.hasRiskProfile()).isFalse();
        assertThat(c.tradingHalted()).isFalse(); // honest default, not fabricated risk
        assertThat(c.liveAutoTradeAuthorized()).isFalse();
    }

    @Test
    @DisplayName("getStatus: multiple credentials for the same user are all reported, each independently")
    void getStatus_multipleCredentials_allReported() {
        BrokerCredential cred1 = credential("cred1", BrokerMode.TESTNET);
        BrokerCredential cred2 = credential("cred2", BrokerMode.LIVE);
        when(credentialRepo.findByUserIdAndActiveTrue("user1")).thenReturn(List.of(cred1, cred2));
        when(riskProfileRepo.findByUserIdAndCredentialId(any(), any())).thenReturn(Optional.empty());

        var status = service.getStatus("user1");

        assertThat(status.credentials()).hasSize(2);
        var ids = status.credentials().stream().map(OpsStatusService.CredentialOpsStatus::credentialId).toList();
        assertThat(ids).containsExactlyInAnyOrder("cred1", "cred2");
    }

    // ── Composite broker health ───────────────────────────────────────────────────────

    @Test
    @DisplayName("getStatus: brokerHealth is populated for every credential, even one with no risk profile — broker connectivity is a fact about the credential, not about auto-trade configuration")
    void getStatus_populatesBrokerHealthForEveryCredential() {
        BrokerCredential cred = credential("cred1", BrokerMode.LIVE);
        when(credentialRepo.findByUserIdAndActiveTrue("user1")).thenReturn(List.of(cred));
        when(riskProfileRepo.findByUserIdAndCredentialId("user1", "cred1")).thenReturn(Optional.empty());
        var composite = new ExchangeHealthService.CompositeHealthStatus(true, List.of(), null, null, null);
        when(exchangeHealthService.checkComposite("fake-api-key", "cred1")).thenReturn(composite);

        var status = service.getStatus("user1");

        assertThat(status.credentials().get(0).brokerHealth()).isSameAs(composite);
    }

    @Test
    @DisplayName("getStatus: a decryption or lookup failure for broker health is reported as an honest 'couldn't determine', never silently omitted or defaulted to healthy")
    void getStatus_brokerHealthLookupFails_reportsHonestly() {
        BrokerCredential cred = credential("cred1", BrokerMode.LIVE);
        when(credentialRepo.findByUserIdAndActiveTrue("user1")).thenReturn(List.of(cred));
        when(riskProfileRepo.findByUserIdAndCredentialId("user1", "cred1")).thenReturn(Optional.empty());
        when(credentialService.decrypt(any(), anyBoolean())).thenThrow(new RuntimeException("decryption failed"));

        var status = service.getStatus("user1");

        var brokerHealth = status.credentials().get(0).brokerHealth();
        assertThat(brokerHealth.healthy()).isFalse();
        assertThat(brokerHealth.reasons().get(0)).contains("Could not determine broker health");
    }
}
