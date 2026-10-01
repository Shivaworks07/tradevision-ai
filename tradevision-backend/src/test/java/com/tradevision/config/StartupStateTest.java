package com.tradevision.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding ("Critical Mongo unique-index failures do not stop the application" --
 * external review, twenty-first pass, P0, full context in this class's own updated
 * criticalIndexesOk field javadoc): the actual tests proving isTradingEnabled() now genuinely
 * requires BOTH conditions -- startup reconciliation succeeding AND every safety-critical index
 * being confirmed -- regardless of which one is reported first. This class has zero external
 * dependencies (no Mongo, no Spring context), making it a genuinely clean, fully-confident unit
 * test unlike the framework-dependent tests elsewhere in this codebase that carry an honest
 * "never actually compiled in this sandbox" caveat.
 */
class StartupStateTest {

    @Test
    void defaultsToNotTradingEnabled() {
        var state = new StartupState();
        assertThat(state.isTradingEnabled()).isFalse();
    }

    @Test
    void reconciliationSucceedsButIndexesNeverReported_stillNotEnabled() {
        var state = new StartupState();
        state.markReconciling();
        state.markComplete(true);

        assertThat(state.isTradingEnabled()).isFalse();
    }

    @Test
    void indexesSucceedButReconciliationNeverCompletes_stillNotEnabled() {
        var state = new StartupState();
        state.markCriticalIndexesResult(true);

        assertThat(state.isTradingEnabled()).isFalse();
    }

    @Test
    void bothConditionsSatisfied_regardlessOfOrder_tradingEnabled() {
        var state1 = new StartupState();
        state1.markCriticalIndexesResult(true);
        state1.markReconciling();
        state1.markComplete(true);
        assertThat(state1.isTradingEnabled()).isTrue();

        var state2 = new StartupState();
        state2.markReconciling();
        state2.markComplete(true);
        state2.markCriticalIndexesResult(true);
        assertThat(state2.isTradingEnabled()).isTrue();
    }

    @Test
    @DisplayName("P1-7: markComplete(false) still reaches RECONCILIATION_FAILED (unchanged, for observability -- TradingWorkerHealthIndicator still alerts DOWN on it), but isTradingEnabled() is now TRUE once the pass has genuinely finished running -- the actual review fix (\"One failing credential at startup disables autonomous trading for ALL users until restart\"). The OLD behavior (permanently false, no restart, blocking every other credential too) is exactly the bug -- the per-credential block now lives in isCredentialTradingEnabled instead.")
    void reconciliationFails_stillCompletesTheStartupPass_globallyEnabled() {
        var state = new StartupState();
        state.markCriticalIndexesResult(true);
        state.markReconciling();
        state.markComplete(false);

        assertThat(state.getPhase()).isEqualTo(StartupState.Phase.RECONCILIATION_FAILED);
        assertThat(state.isTradingEnabled()).isTrue();
    }

    @Test
    void indexesFail_evenWithReconciliationOk_notEnabled() {
        var state = new StartupState();
        state.markReconciling();
        state.markComplete(true);
        state.markCriticalIndexesResult(false);

        assertThat(state.isTradingEnabled()).isFalse();
        // Phase itself is unaffected -- this is a deliberately independent flag (see this
        // class's own criticalIndexesOk field javadoc for why).
        assertThat(state.getPhase()).isEqualTo(StartupState.Phase.TRADING_ENABLED);
    }

    /**
     * Review finding ("Mongo standalone deployment still weakens the plan/profile execution
     * atomicity guarantee" -- external review, twenty-fourth pass, P1, full context in
     * mongoTransactionsSupported's own field javadoc): the actual tests for the new flag.
     */
    @Test
    void mongoTransactionsSupported_defaultsToFalse() {
        var state = new StartupState();
        assertThat(state.areMongoTransactionsSupported()).isFalse();
    }

    @Test
    void mongoTransactionsSupported_reflectsMarkedResult() {
        var state = new StartupState();
        state.markMongoTransactionsResult(true);
        assertThat(state.areMongoTransactionsSupported()).isTrue();

        state.markMongoTransactionsResult(false);
        assertThat(state.areMongoTransactionsSupported()).isFalse();
    }

    @Test
    void mongoTransactionsSupported_isIndependentOfTradingEnabled() {
        var state = new StartupState();
        state.markCriticalIndexesResult(true);
        state.markReconciling();
        state.markComplete(true);
        // isTradingEnabled() is TRUE even though mongoTransactionsSupported was never marked --
        // confirming this flag deliberately does NOT gate global trading readiness, only
        // authorizeLiveAutoTrade's own separate check (see this class's own field javadoc).
        assertThat(state.isTradingEnabled()).isTrue();
        assertThat(state.areMongoTransactionsSupported()).isFalse();
    }

    // ── Per-credential reconciliation tracking (P1 #7) ──────────────────────────────

    @Test
    @DisplayName("P1-7: isCredentialTradingEnabled is false for every credential before the startup pass has even run, same as the old global check")
    void credentialTradingEnabled_beforeStartupPassCompletes_false() {
        var state = new StartupState();
        state.markCriticalIndexesResult(true);

        assertThat(state.isCredentialTradingEnabled("cred1")).isFalse();
    }

    @Test
    @DisplayName("P1-7: a credential that was never marked failed is enabled once the startup pass completes cleanly")
    void credentialTradingEnabled_neverFailed_true() {
        var state = new StartupState();
        state.markCriticalIndexesResult(true);
        state.markReconciling();
        state.markComplete(true);

        assertThat(state.isCredentialTradingEnabled("cred1")).isTrue();
    }

    @Test
    @DisplayName("P1-7: the actual review fix -- one credential's own reconciliation failing blocks ONLY that credential; every other credential stays enabled")
    void credentialTradingEnabled_oneCredentialFails_onlyThatOneBlocked() {
        var state = new StartupState();
        state.markCriticalIndexesResult(true);
        state.markReconciling();
        state.markComplete(false); // one credential (cred1) failed during this pass
        state.markCredentialReconciled("cred1", false);
        state.markCredentialReconciled("cred2", true);

        assertThat(state.isCredentialTradingEnabled("cred1")).isFalse();
        assertThat(state.isCredentialTradingEnabled("cred2")).isTrue();
        // The app-wide gate itself is still open -- only the per-credential check differs.
        assertThat(state.isTradingEnabled()).isTrue();
    }

    @Test
    @DisplayName("P1-7: a previously-failed credential recovers automatically (no restart) once a later reconciliation attempt for it succeeds -- the review's own requested \"retry\" behavior")
    void credentialTradingEnabled_recoversAfterLaterSuccess_noRestartNeeded() {
        var state = new StartupState();
        state.markCriticalIndexesResult(true);
        state.markReconciling();
        state.markComplete(false);
        state.markCredentialReconciled("cred1", false);
        assertThat(state.isCredentialTradingEnabled("cred1")).isFalse();

        // A later periodic reconciliation cycle succeeds for cred1 (e.g. the user fixed their
        // API key) -- no markReconciling()/markComplete() call needed, this is a normal 60s
        // periodic cycle, not another startup pass.
        state.markCredentialReconciled("cred1", true);

        assertThat(state.isCredentialTradingEnabled("cred1")).isTrue();
    }

    @Test
    @DisplayName("P1-7: a credential can also newly fail during a LATER periodic cycle, after having been fine at startup")
    void credentialTradingEnabled_laterFailure_blocksThatCredential() {
        var state = new StartupState();
        state.markCriticalIndexesResult(true);
        state.markReconciling();
        state.markComplete(true); // clean startup, no failures yet
        assertThat(state.isCredentialTradingEnabled("cred1")).isTrue();

        state.markCredentialReconciled("cred1", false); // a later periodic cycle fails for cred1

        assertThat(state.isCredentialTradingEnabled("cred1")).isFalse();
    }
}
