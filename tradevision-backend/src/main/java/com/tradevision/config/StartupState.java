package com.tradevision.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tracks whether the one-time startup reconciliation pass against the exchange has finished,
 * and gates autonomous trading on it. Phases progress STARTING (default) -> RECONCILING (the
 * startup pass is running) -> TRADING_ENABLED (it completed with zero per-credential failures)
 * or RECONCILIATION_FAILED (at least one credential failed during that pass).
 * AutoTradeService.evaluateSignal refuses to run anything until this reaches TRADING_ENABLED.
 *
 * Scope is deliberately limited to the ONE-TIME startup pass: this is not an ongoing health
 * check re-evaluated on every later periodic reconciliation cycle. A transient failure during a
 * later periodic cycle, long after a clean startup, does not retroactively flip this state back
 * — that would make this a continuous circuit breaker on the whole application rather than a
 * one-time "is it safe to start autonomous trading after this restart" gate.
 */
@Component
public class StartupState {

    private static final Logger log = LoggerFactory.getLogger(StartupState.class);

    public enum Phase { STARTING, RECONCILING, TRADING_ENABLED, RECONCILIATION_FAILED }

    private volatile Phase phase = Phase.STARTING;
    // Timestamp of when trading became enabled, so the health indicator can distinguish a
    // genuinely fresh start (no heartbeat has had time to fire yet) from a worker that died
    // after running normally for a while, instead of just knowing that trading is enabled.
    private volatile java.time.Instant tradingEnabledAt;
    /**
     * Whether every safety-critical Mongo unique index was confirmed present at startup. Kept
     * as a separate, independent flag from `phase` above because IndexInitializer.ensureCriticalIndexes()
     * and the startup reconciliation pass both fire on the same ApplicationReadyEvent with no
     * guaranteed ordering between them; folding this into markComplete()'s phase transition would
     * risk one call overwriting the other's result depending on which ran second. Defaults to
     * false (unconfirmed, the same default-safe posture as `phase` defaulting to STARTING) —
     * isTradingEnabled() below requires both this flag and the phase to be correct, regardless of
     * which is set first.
     */
    private volatile boolean criticalIndexesOk = false;

    public java.time.Instant getTradingEnabledAt() {
        return tradingEnabledAt;
    }

    public Phase getPhase() {
        return phase;
    }

    /**
     * Reports whether the app-wide, one-time startup pass has finished running at all —
     * nothing should evaluate against unverified broker state before this. RECONCILIATION_FAILED
     * counts as "the pass finished" exactly like TRADING_ENABLED does, so a single credential's
     * reconciliation failure does not hold every other credential hostage; TradingWorkerHealthIndicator
     * still reports DOWN on RECONCILIATION_FAILED for observability. Whether a specific
     * credential's own reconciliation succeeded is a separate, per-credential concern answered by
     * isCredentialTradingEnabled below, which is what AutoTradeService.evaluateForProfile gates
     * entries on.
     */
    public boolean isTradingEnabled() {
        boolean startupPassCompleted = phase == Phase.TRADING_ENABLED || phase == Phase.RECONCILIATION_FAILED;
        return startupPassCompleted && criticalIndexesOk;
    }

    // Per-credential reconciliation readiness, tracked independently of the coarse app-wide
    // phase above. A credential lands here the moment its own reconciliation attempt fails
    // (startup or any later periodic cycle) and leaves the moment a reconciliation attempt for
    // that exact credential succeeds again — no restart needed to clear it.
    private final java.util.Set<String> failedReconciliationCredentialIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void markCredentialReconciled(String credentialId, boolean succeeded) {
        if (credentialId == null) return;
        if (succeeded) {
            failedReconciliationCredentialIds.remove(credentialId);
        } else {
            failedReconciliationCredentialIds.add(credentialId);
        }
    }

    /**
     * True only when the app-wide startup pass has completed (isTradingEnabled() above) and this
     * specific credential's own most recent reconciliation attempt, startup or periodic, did not
     * fail. Before the startup pass completes, this is false for every credential regardless,
     * guaranteeing nothing evaluates against unverified broker state right after a cold start;
     * this method only differentiates between credentials once that coarse gate has opened. A
     * credential never attempted (e.g. one added after startup, before its first periodic cycle
     * reaches it) is not in the failed set and so is not blocked by this method.
     */
    public boolean isCredentialTradingEnabled(String credentialId) {
        return isTradingEnabled() && credentialId != null && !failedReconciliationCredentialIds.contains(credentialId);
    }

    /**
     * Lets the health indicator distinguish "reconciliation itself failed" (phase ==
     * RECONCILIATION_FAILED) from "a safety-critical index failed" (this flag false, even with
     * phase == TRADING_ENABLED). Both block isTradingEnabled(), but they are different problems,
     * and collapsing them into one generic "not yet complete" health message would obscure which
     * one an operator needs to investigate.
     */
    public boolean areCriticalIndexesOk() {
        return criticalIndexesOk;
    }

    public void markCriticalIndexesResult(boolean allSucceeded) {
        criticalIndexesOk = allSucceeded;
        if (!allSucceeded) {
            log.error("One or more safety-critical unique indexes failed to be confirmed -- autonomous trading remains DISABLED "
                + "regardless of startup reconciliation's own outcome, until this is resolved (see IndexInitializer's own logs above "
                + "for exactly which index(es) failed) and the application is restarted.");
        }
    }

    /**
     * Whether the Mongo deployment supports multi-document transactions, needed for atomic
     * plan/profile execution. Deliberately not wired into isTradingEnabled() above: unlike
     * criticalIndexesOk, a lack of transaction support should only refuse LIVE authorization
     * specifically (see RiskProfileService.authorizeLiveAutoTrade), not disable TESTNET/PAPER
     * trading, which has no real-money stake in this guarantee. Defaults to false (unconfirmed),
     * the same default-safe posture as the other startup flags in this class.
     */
    private volatile boolean mongoTransactionsSupported = false;

    public boolean areMongoTransactionsSupported() {
        return mongoTransactionsSupported;
    }

    public void markMongoTransactionsResult(boolean supported) {
        mongoTransactionsSupported = supported;
    }

    public void markReconciling() {
        phase = Phase.RECONCILING;
    }

    public void markComplete(boolean allCredentialsSucceeded) {
        if (allCredentialsSucceeded) {
            phase = Phase.TRADING_ENABLED;
            tradingEnabledAt = java.time.Instant.now();
            log.info("Startup reconciliation completed successfully for every active credential — autonomous trading enabled.");
        } else {
            phase = Phase.RECONCILIATION_FAILED;
            log.error("Startup reconciliation FAILED for one or more credentials — autonomous trading remains DISABLED "
                + "until this is resolved and the application is restarted. Check the logs above for which credential(s) failed.");
        }
    }
}
