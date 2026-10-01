package com.tradevision.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Review finding (P1 — "Startup reconciliation is good, but startup trading should remain
 * disabled until reconciliation completes"): confirmed real — the startup reconciliation pass
 * ran, logged a warning on any per-credential failure, and continued regardless. Nothing ever
 * stopped autonomous trading from proceeding even if the very first reconciliation against the
 * real exchange state had failed for one or more credentials.
 *
 * The review's own suggested state names, implemented as the minimum that actually matters:
 * STARTING (default) -> RECONCILING (the startup pass is running) -> TRADING_ENABLED (it
 * completed with zero per-credential failures) or RECONCILIATION_FAILED (at least one credential
 * failed during that pass). AutoTradeService.evaluateSignal refuses to run anything until this
 * reaches TRADING_ENABLED.
 *
 * Honest scope: this gates the ONE-TIME startup pass specifically, matching the review's own
 * framing ("startup trading should remain disabled until reconciliation completes") — it is NOT
 * an ongoing health-check that re-evaluates on every later periodic reconciliation cycle. A
 * transient failure during a LATER periodic cycle (long after a clean startup) does not
 * retroactively flip this back — that would be a much more aggressive, different feature
 * (effectively a continuous circuit breaker on the whole application, not "is it safe to start
 * autonomous trading after this restart") than what the review actually described.
 */
@Component
public class StartupState {

    private static final Logger log = LoggerFactory.getLogger(StartupState.class);

    public enum Phase { STARTING, RECONCILING, TRADING_ENABLED, RECONCILIATION_FAILED }

    private volatile Phase phase = Phase.STARTING;
    // Review finding ("Reconciliation watchdog incorrectly reports healthy if either worker is
    // alive" -- P1, full context in TradingWorkerHealthIndicator's own updated javadoc): needed
    // for a real, bounded startup grace period -- the health indicator needs to know WHEN
    // trading became enabled, not just THAT it did, to distinguish "genuinely just started, one
    // heartbeat hasn't fired yet" from "has been running for a while and a worker actually died."
    private volatile java.time.Instant tradingEnabledAt;
    /**
     * Review finding ("Critical Mongo unique-index failures do not stop the application" --
     * external review, twenty-first pass, P0, confirmed real by direct inspection before this
     * fix: IndexInitializer caught every unique-index-creation failure and only logged it --
     * nothing about this ever stopped autonomous trading from starting, even if a safety-
     * critical constraint like Order.clientOrderId's own uniqueness genuinely failed to be
     * enforced due to pre-existing duplicate data): a SEPARATE, independent flag from `phase`
     * above, deliberately -- IndexInitializer.ensureCriticalIndexes() and
     * PositionMonitorService's own startup reconciliation both fire on the same
     * ApplicationReadyEvent with no guaranteed ordering between them. Folding this directly into
     * markComplete()'s own phase transition would risk one of the two calls silently overwriting
     * the other's own result depending on which happened to run second. Defaults to false
     * (unconfirmed, same "default-safe until proven otherwise" posture as `phase` itself
     * defaulting to STARTING) -- isTradingEnabled() below requires BOTH this flag AND the
     * existing phase to be correct, regardless of which one is set first.
     */
    private volatile boolean criticalIndexesOk = false;

    public java.time.Instant getTradingEnabledAt() {
        return tradingEnabledAt;
    }

    public Phase getPhase() {
        return phase;
    }

    /**
     * Review finding (P1 #7 -- "One failing credential at startup disables autonomous trading
     * for ALL users until restart"): confirmed real, and confirmed to be THE gate every caller
     * (AutoTradeService.evaluateSignal, AutonomousScannerService.scan, the health indicator, ops
     * status) actually consulted -- this used to require phase == TRADING_ENABLED exactly, and
     * markComplete(false) (a SINGLE credential's startup reconciliation failing) permanently set
     * phase to RECONCILIATION_FAILED, which this method then reported as globally not-enabled,
     * for every credential of every user, until a manual restart. That conflated two genuinely
     * different questions: "has the one-time startup pass finished running at all" (a real,
     * app-wide readiness gate -- nothing should evaluate against unverified broker state before
     * this) and "did THIS credential's own reconciliation succeed" (a per-credential concern that
     * has no business blocking anyone else). This method now answers only the first question --
     * RECONCILIATION_FAILED counts as "the pass finished" exactly like TRADING_ENABLED does, so a
     * single bad credential no longer holds every other credential hostage. It still means
     * exactly what it always did for observability: TradingWorkerHealthIndicator still reports
     * DOWN on RECONCILIATION_FAILED, unchanged by this fix. The actual per-credential decision
     * now lives in isCredentialTradingEnabled below, which is what AutoTradeService.
     * evaluateForProfile actually gates entries on.
     */
    public boolean isTradingEnabled() {
        boolean startupPassCompleted = phase == Phase.TRADING_ENABLED || phase == Phase.RECONCILIATION_FAILED;
        return startupPassCompleted && criticalIndexesOk;
    }

    // Review finding (P1 #7, full context in isTradingEnabled's own updated javadoc): per-
    // credential readiness, tracked independently of the coarse app-wide phase above. A
    // credential lands here the moment ITS OWN reconciliation attempt fails (startup or any
    // later periodic cycle -- see PositionMonitorService.reconcileCredential's own updated
    // try/catch) and leaves the moment a reconciliation attempt for that exact credential
    // succeeds again -- no restart needed, unlike the old global block.
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
     * The real, per-credential fix for P1 #7: true only when the app-wide startup pass has
     * completed (isTradingEnabled() above) AND this specific credential's own most recent
     * reconciliation attempt (startup or periodic) did not fail. Before the startup pass
     * completes at all, isTradingEnabled() above is false for every credential regardless (the
     * same "nothing evaluates against unverified broker state right after a cold start"
     * guarantee as before this fix) -- this method only starts to differentiate BETWEEN
     * credentials once that coarse gate has already opened. A credential this application has
     * never attempted to reconcile at all (e.g. one added after startup, before its first
     * periodic cycle reaches it) is not in the failed set and so is not blocked by this method --
     * the same posture newly-added credentials already had under the old, purely-global check.
     */
    public boolean isCredentialTradingEnabled(String credentialId) {
        return isTradingEnabled() && credentialId != null && !failedReconciliationCredentialIds.contains(credentialId);
    }

    /**
     * Review finding ("Startup health reports UP even when startup reconciliation FAILED" --
     * external review, twenty-second pass, P1, full context in
     * TradingWorkerHealthIndicator's own updated health() javadoc): needed so that indicator
     * can distinguish "reconciliation itself failed" (phase == RECONCILIATION_FAILED) from "a
     * safety-critical index failed" (this flag false, even with phase == TRADING_ENABLED) --
     * both block isTradingEnabled(), but they're different problems, and collapsing them into
     * one generic "not yet complete" health message would obscure which one an operator
     * actually needs to investigate.
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
     * Review finding ("Mongo standalone deployment still weakens the plan/profile execution
     * atomicity guarantee" -- external review, twenty-fourth pass, P1, full context in
     * IndexInitializer.checkMongoTransactionSupport's own javadoc): deliberately NOT wired into
     * isTradingEnabled() above -- unlike criticalIndexesOk, a lack of Mongo transaction support
     * should only refuse LIVE authorization specifically (see
     * RiskProfileService.authorizeLiveAutoTrade's own updated check), not disable TESTNET/PAPER
     * trading, which has no real-money stake in this specific guarantee at all. Defaults to
     * false (unconfirmed) -- same "default-safe until proven otherwise" posture as the other
     * startup flags in this class.
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
