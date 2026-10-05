package com.tradevision.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
// Spring Boot 4 follow-up: Status moved to org.springframework.boot.health.contributor (see
// TradingWorkerHealthIndicator's own updated import comment for full context).
import org.springframework.boot.health.contributor.Status;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Review finding ("#10 — External Watchdog"): verifies the actual DOWN/UP decision — the real
 * value of this whole mechanism, since an external monitor is only as good as the signal it's
 * polling.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingWorkerHealthIndicatorTest {

    @Mock TradingHeartbeatService heartbeatService;
    @Mock StartupState startupState;
    @Mock IndexInitializer indexInitializer;
    @InjectMocks TradingWorkerHealthIndicator indicator;

    /**
     * Review finding ("Startup health reports UP even when startup reconciliation FAILED" --
     * external review, twenty-second pass, P1, full context in the indicator's own updated
     * health() javadoc): this test used to stub phase=RECONCILING and assert Status.UP -- that
     * assertion was itself testing the exact bug this fix closes. Split into two, now-distinct
     * tests: STARTING genuinely still reports UP (nothing has gone wrong, reconciliation simply
     * hasn't started yet), while RECONCILING now correctly reports OUT_OF_SERVICE below.
     */
    @Test
    @DisplayName("health: STARTING phase (before reconciliation has even begun) reports UP — a normal startup window is never a false alarm")
    void startingPhase_reportsUp() {
        when(startupState.isTradingEnabled()).thenReturn(false);
        when(startupState.getPhase()).thenReturn(StartupState.Phase.STARTING);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("health: RECONCILING reports OUT_OF_SERVICE, not UP -- the actual review fix: this in-between state is genuinely different from a normal startup window and from real worker health, and OUT_OF_SERVICE's own documented meaning (\"still published, but not accessible\") matches it")
    void reconcilingPhase_reportsOutOfService() {
        when(startupState.isTradingEnabled()).thenReturn(false);
        when(startupState.getPhase()).thenReturn(StartupState.Phase.RECONCILING);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    @DisplayName("health: RECONCILIATION_FAILED reports DOWN, not UP -- the review's own named danger: an external monitor must be able to learn this deployment needs attention")
    void reconciliationFailedPhase_reportsDown() {
        when(startupState.isTradingEnabled()).thenReturn(false);
        when(startupState.getPhase()).thenReturn(StartupState.Phase.RECONCILIATION_FAILED);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("health: TRADING_ENABLED phase but a safety-critical index failed -- reports DOWN, not UP, since isTradingEnabled() is still false for a genuinely different reason than reconciliation itself failing")
    void tradingEnabledPhaseButIndexesFailed_reportsDown() {
        when(startupState.isTradingEnabled()).thenReturn(false);
        when(startupState.getPhase()).thenReturn(StartupState.Phase.TRADING_ENABLED);
        when(startupState.areCriticalIndexesOk()).thenReturn(false);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("health: both heartbeats recent, well past the startup grace period — UP, the genuinely healthy case")
    void bothHeartbeatsRecent_reportsUp() {
        when(startupState.isTradingEnabled()).thenReturn(true);
        when(startupState.getTradingEnabledAt()).thenReturn(Instant.now().minusSeconds(600)); // well past the grace period
        when(heartbeatService.getLastScanCompletedAt()).thenReturn(Instant.now().minusSeconds(30));
        when(heartbeatService.getLastReconciliationCompletedAt()).thenReturn(Instant.now().minusSeconds(30));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    /**
     * Review finding ("Performance indexes are treated as non-fatal" -- external review,
     * twenty-third pass, P2, full context in IndexInitializer's own updated
     * failedPerformanceIndexes field javadoc): the actual test proving this is surfaced as
     * visible, non-gating health detail.
     */
    @Test
    @DisplayName("health: a failed performance index is surfaced as a visible detail, but does NOT change the overall UP status -- non-gating, exactly as the review's own framing says is correct")
    void failedPerformanceIndex_surfacedAsDetailButStillUp() {
        when(startupState.isTradingEnabled()).thenReturn(true);
        when(startupState.getTradingEnabledAt()).thenReturn(Instant.now().minusSeconds(600));
        when(heartbeatService.getLastScanCompletedAt()).thenReturn(Instant.now().minusSeconds(30));
        when(heartbeatService.getLastReconciliationCompletedAt()).thenReturn(Instant.now().minusSeconds(30));
        when(indexInitializer.getFailedPerformanceIndexes()).thenReturn(List.of("Position.(symbol, status) [compound]"));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("failedPerformanceIndexes");
    }

    /**
     * Review finding ("Performance/TTL index failures remain non-fatal" -- external review,
     * twenty-fourth pass, P2, full context in this indicator's own updated healthy-path
     * comment): the actual test proving the grace-period escalation.
     */
    @Test
    @DisplayName("health: a performance index still failed well past the grace period reports DOWN, not UP")
    void failedPerformanceIndex_pastGracePeriod_reportsDown() {
        when(startupState.isTradingEnabled()).thenReturn(true);
        when(startupState.getTradingEnabledAt()).thenReturn(Instant.now().minusSeconds(600));
        when(heartbeatService.getLastScanCompletedAt()).thenReturn(Instant.now().minusSeconds(30));
        when(heartbeatService.getLastReconciliationCompletedAt()).thenReturn(Instant.now().minusSeconds(30));
        when(indexInitializer.getFailedPerformanceIndexes()).thenReturn(List.of("Position.(symbol, status) [compound]"));
        when(indexInitializer.getIndexCheckCompletedAt()).thenReturn(Instant.now().minus(java.time.Duration.ofMinutes(20)));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("failedPerformanceIndexes");
    }

    @Test
    @DisplayName("health: a performance index failed but STILL WITHIN the grace period reports UP -- a transient startup-time issue must not immediately page anyone")
    void failedPerformanceIndex_withinGracePeriod_reportsUp() {
        when(startupState.isTradingEnabled()).thenReturn(true);
        when(startupState.getTradingEnabledAt()).thenReturn(Instant.now().minusSeconds(600));
        when(heartbeatService.getLastScanCompletedAt()).thenReturn(Instant.now().minusSeconds(30));
        when(heartbeatService.getLastReconciliationCompletedAt()).thenReturn(Instant.now().minusSeconds(30));
        when(indexInitializer.getFailedPerformanceIndexes()).thenReturn(List.of("Position.(symbol, status) [compound]"));
        when(indexInitializer.getIndexCheckCompletedAt()).thenReturn(Instant.now().minus(java.time.Duration.ofMinutes(2)));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("health: reconciliation heartbeat is stale but the scanner's is recent -- DOWN, not UP -- the actual review fix (\"Reconciliation watchdog incorrectly reports healthy if either worker is alive\"): reconciliation is the real safety backstop, and a dead reconciliation worker is not an acceptable failure mode just because the scanner happens to still be running")
    void reconciliationDeadScannerAlive_reportsDown() {
        when(startupState.isTradingEnabled()).thenReturn(true);
        when(startupState.getTradingEnabledAt()).thenReturn(Instant.now().minusSeconds(600));
        when(heartbeatService.getLastScanCompletedAt()).thenReturn(Instant.now().minusSeconds(30)); // recent
        when(heartbeatService.getLastReconciliationCompletedAt()).thenReturn(Instant.now().minusSeconds(400)); // stale

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("health: the scanner's heartbeat is stale but reconciliation's is recent -- DOWN, not UP -- the symmetric case of the same fix")
    void scannerDeadReconciliationAlive_reportsDown() {
        when(startupState.isTradingEnabled()).thenReturn(true);
        when(startupState.getTradingEnabledAt()).thenReturn(Instant.now().minusSeconds(600));
        when(heartbeatService.getLastScanCompletedAt()).thenReturn(Instant.now().minusSeconds(400)); // stale
        when(heartbeatService.getLastReconciliationCompletedAt()).thenReturn(Instant.now().minusSeconds(30)); // recent

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("health: within the startup grace period after trading became enabled, only ONE heartbeat has fired yet -- still UP, not a false alarm on a normal startup")
    void withinStartupGracePeriod_onlyOneHeartbeatFired_stillUp() {
        when(startupState.isTradingEnabled()).thenReturn(true);
        when(startupState.getTradingEnabledAt()).thenReturn(Instant.now().minusSeconds(20)); // just enabled, well within the grace period
        when(heartbeatService.getLastScanCompletedAt()).thenReturn(Instant.now().minusSeconds(5));
        when(heartbeatService.getLastReconciliationCompletedAt()).thenReturn(null); // hasn't fired yet -- expected this soon after enabling

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("health: past the startup grace period, only one heartbeat has ever fired -- DOWN, the grace period doesn't extend forever")
    void pastStartupGracePeriod_onlyOneHeartbeatEverFired_reportsDown() {
        when(startupState.isTradingEnabled()).thenReturn(true);
        when(startupState.getTradingEnabledAt()).thenReturn(Instant.now().minusSeconds(600)); // well past the grace period
        when(heartbeatService.getLastScanCompletedAt()).thenReturn(Instant.now().minusSeconds(30));
        when(heartbeatService.getLastReconciliationCompletedAt()).thenReturn(null); // never fired, and it's been 10 minutes

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("health: both heartbeats stale (over 5 minutes old) — DOWN, the exact case an external monitor should alert on")
    void bothHeartbeatsStale_reportsDown() {
        when(startupState.isTradingEnabled()).thenReturn(true);
        when(startupState.getTradingEnabledAt()).thenReturn(Instant.now().minusSeconds(600));
        when(heartbeatService.getLastScanCompletedAt()).thenReturn(Instant.now().minusSeconds(400));
        when(heartbeatService.getLastReconciliationCompletedAt()).thenReturn(Instant.now().minusSeconds(400));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("health: neither heartbeat has ever fired (both null, post-startup, past the grace period) — DOWN, not a null-pointer exception")
    void neitherHeartbeatEverFired_reportsDownNotException() {
        when(startupState.isTradingEnabled()).thenReturn(true);
        when(startupState.getTradingEnabledAt()).thenReturn(Instant.now().minusSeconds(600));
        when(heartbeatService.getLastScanCompletedAt()).thenReturn(null);
        when(heartbeatService.getLastReconciliationCompletedAt()).thenReturn(null);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("health: both heartbeats exactly at the boundary (just under 5 minutes) are still considered recent")
    void heartbeatsJustUnderThreshold_stillUp() {
        when(startupState.isTradingEnabled()).thenReturn(true);
        when(startupState.getTradingEnabledAt()).thenReturn(Instant.now().minusSeconds(600));
        when(heartbeatService.getLastScanCompletedAt()).thenReturn(Instant.now().minusSeconds(295)); // 4m55s, under the 5m threshold
        when(heartbeatService.getLastReconciliationCompletedAt()).thenReturn(Instant.now().minusSeconds(295));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }
}
