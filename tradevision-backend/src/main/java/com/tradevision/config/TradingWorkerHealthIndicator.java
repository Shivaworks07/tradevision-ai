package com.tradevision.config;

import lombok.RequiredArgsConstructor;
// Spring Boot 4 follow-up (full context in pom.xml's own dated parent-version comment): Health/
// HealthIndicator/Status moved out of org.springframework.boot.actuate.health entirely, into a
// new spring-boot-health module (transitively pulled in by spring-boot-starter-actuator, already
// a dependency here -- no new dependency needed) -- confirmed directly against Spring Boot
// 4.1.1's own published javadoc for this exact package, not assumed.
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Review finding ("#10 — External Watchdog"): the actual external-facing signal — a plain
 * Spring Boot Actuator HealthIndicator, surfaced automatically at GET /actuator/health under the
 * key "tradingWorker" (Spring Boot's own naming convention: a bean named XHealthIndicator maps
 * to key X). Any real external monitor can poll this exact endpoint and alert on a non-UP
 * status — that's the whole mechanism, deliberately using Spring's own standard extension point
 * rather than inventing a bespoke one.
 *
 * DOWN if either the scanner or reconciliation has NOT completed a cycle within the threshold —
 * both are @Scheduled at a 60s fixedDelay, so a 5-minute threshold gives real buffer for
 * transient slowness (a slow Binance response, GC pause, momentary DB latency) while still
 * catching a genuinely stuck or dead worker, not a worker that's simply running a touch slow.
 *
 * UPDATE ("Reconciliation watchdog incorrectly reports healthy if either worker is alive" -- P1):
 * confirmed real and fixed. Requiring only ONE of the two heartbeats to be recent meant a
 * completely dead reconciliation worker — the actual safety backstop for OCO state, fills,
 * position state, late fills, protection failures, drawdown, and ledger consistency — could sit
 * dead indefinitely while the scanner alone kept reporting the whole system UP. A dead
 * reconciliation worker is not equivalent to a healthy trading system, and this indicator now
 * requires BOTH to be recent, not either. The startup-window concern that originally motivated
 * the OR logic is real but is now solved properly instead of worked around: STARTUP_GRACE_PERIOD
 * below, anchored to StartupState's own tradingEnabledAt timestamp (not just "trading is
 * enabled," but WHEN it became enabled), covers the genuine brief window where one heartbeat
 * legitimately hasn't fired yet without treating a worker that's actually been dead for minutes
 * as merely "still starting up."
 */
@Component("tradingWorkerHealthIndicator")
@RequiredArgsConstructor
public class TradingWorkerHealthIndicator implements HealthIndicator {

    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    /**
     * Review finding ("Performance/TTL index failures remain non-fatal" -- external review,
     * twenty-fourth pass, P2, full context in this method's own updated healthy-path comment):
     * the "short startup grace period" the review itself asks for -- long enough that a
     * transient issue right at application startup doesn't immediately page anyone, short
     * enough that a genuinely persistent problem doesn't stay silent for long.
     */
    private static final Duration PERFORMANCE_INDEX_GRACE_PERIOD = Duration.ofMinutes(15);
    // Review finding's own fix (full context above): both @Scheduled tasks' own initialDelay
    // values (45s scanner, 30s reconciliation) are measured from application startup, not from
    // when trading becomes enabled — so even after isTradingEnabled() flips true, there is a
    // real, if narrow (well under a minute in practice), window where one hasn't fired yet.
    // Set generously above that worst case, not tuned to the exact number, since the real
    // safety property this needs is "long enough to never false-alarm on a normal startup,"
    // not "as short as possible."
    private static final Duration STARTUP_GRACE_PERIOD = Duration.ofMinutes(2);

    private final TradingHeartbeatService heartbeatService;
    private final StartupState startupState;
    /**
     * Review finding ("Performance indexes are treated as non-fatal" -- external review,
     * twenty-third pass, P2, full context in IndexInitializer's own updated
     * failedPerformanceIndexes field javadoc): needed to surface these failures as visible
     * health detail -- non-gating (this does NOT affect the overall Status returned below), just
     * no longer invisible to an operator who isn't actively watching logs.
     */
    private final IndexInitializer indexInitializer;

    @Override
    public Health health() {
        // Before startup reconciliation has confirmed real broker state, neither loop is
        // expected to have produced a heartbeat yet — reporting DOWN during a normal, brief
        // startup window would be a false alarm, not a real signal worth alerting an operator on.
        // Review finding ("Startup health reports UP even when startup reconciliation FAILED"
        // -- external review, twenty-second pass, P1, confirmed real by direct inspection
        // before this fix: this branch used to report UP for EVERY pre-TRADING_ENABLED phase
        // indiscriminately, including RECONCILIATION_FAILED -- a genuinely bad state (startup
        // reconciliation against the real broker failed, autonomous trading is disabled) that
        // would still report healthy to Kubernetes/an external monitor, which could never learn
        // the deployment needs attention): the actual fix -- each phase now maps to a real,
        // distinct Spring Actuator Status, confirmed via Spring Boot's own documentation before
        // choosing this mapping (Status has exactly four built-in values: UP, DOWN,
        // OUT_OF_SERVICE, UNKNOWN -- the review's own suggested "NOT_READY" is not one of them).
        var phase = startupState.getPhase();
        if (phase == StartupState.Phase.RECONCILIATION_FAILED) {
            return Health.down()
                .withDetail("reason", "Startup reconciliation against the real broker state FAILED for one or more credentials -- "
                    + "autonomous trading remains disabled until this is resolved and the application is restarted. This is a real "
                    + "failure an external monitor should alert on, not a normal startup transient.")
                .withDetail("startupPhase", phase.name())
                .build();
        }
        if (phase == StartupState.Phase.RECONCILING) {
            // Closest real Status to the review's own suggested "NOT_READY": genuinely still
            // starting up, not a failure, but not ready to be trusted as normal worker health
            // either -- OUT_OF_SERVICE's own documented meaning ("still published, but not
            // accessible") matches this specific in-between state more precisely than UP does.
            return Health.status(Status.OUT_OF_SERVICE)
                .withDetail("reason", "Startup reconciliation against the real broker state is currently in progress.")
                .withDetail("startupPhase", phase.name())
                .build();
        }
        if (phase == StartupState.Phase.TRADING_ENABLED && !startupState.areCriticalIndexesOk()) {
            // Review finding, same context as the RECONCILIATION_FAILED branch above: a
            // genuinely different failure mode -- reconciliation itself succeeded, but a
            // safety-critical unique index failed to be confirmed (see IndexInitializer's own
            // logs for which one). Also blocks isTradingEnabled(), also worth its own distinct,
            // non-generic health message rather than falling through to "not yet complete".
            return Health.down()
                .withDetail("reason", "Startup reconciliation succeeded, but one or more safety-critical unique indexes could not be "
                    + "confirmed -- autonomous trading remains disabled until this is resolved and the application is restarted. See "
                    + "IndexInitializer's own logs for exactly which index failed.")
                .withDetail("startupPhase", phase.name())
                .build();
        }
        if (!startupState.isTradingEnabled()) {
            return Health.up()
                .withDetail("reason", "Startup reconciliation not yet complete — trading worker heartbeat not expected yet.")
                .withDetail("startupPhase", phase.name())
                .build();
        }

        Instant now = Instant.now();
        // Review finding's own fix (full context above): the real startup grace period,
        // anchored to the actual moment trading became enabled.
        Instant tradingEnabledAt = startupState.getTradingEnabledAt();
        if (tradingEnabledAt != null && Duration.between(tradingEnabledAt, now).compareTo(STARTUP_GRACE_PERIOD) < 0) {
            return Health.up()
                .withDetail("reason", "Within the startup grace period after trading was enabled — one or both worker heartbeats may not have fired yet.")
                .withDetail("tradingEnabledAt", tradingEnabledAt)
                .build();
        }

        Instant lastScan = heartbeatService.getLastScanCompletedAt();
        Instant lastReconcile = heartbeatService.getLastReconciliationCompletedAt();

        boolean scanRecent = lastScan != null && Duration.between(lastScan, now).compareTo(STALE_THRESHOLD) < 0;
        boolean reconcileRecent = lastReconcile != null && Duration.between(lastReconcile, now).compareTo(STALE_THRESHOLD) < 0;
        // Health.Builder.withDetail() throws IllegalArgumentException on a null value, but a
        // heartbeat that has never fired at all (a genuine, expected state right after startup --
        // not itself an error condition) is exactly when these are null. Never fed to withDetail
        // raw below; always through these safe placeholders instead.
        Object lastScanDetail = lastScan != null ? lastScan : "never";
        Object lastReconcileDetail = lastReconcile != null ? lastReconcile : "never";

        if (scanRecent && reconcileRecent) {
            // Review finding ("Performance/TTL index failures remain non-fatal" -- external
            // review, twenty-fourth pass, P2, confirmed real by direct inspection before this
            // fix: a failed performance index was surfaced as a visible health detail, but
            // NEVER changed the overall status -- an operator who wasn't actively reading the
            // detail field would never learn about it at all, indefinitely): the actual fix --
            // still genuinely non-gating for a SHORT startup grace period (the index check ran
            // once, near application startup, and a transient issue right at that moment
            // shouldn't immediately page anyone), but a performance index still missing well
            // past that grace period is now treated as a real operational problem worth
            // alerting on, not silent forever.
            var failedIndexes = indexInitializer.getFailedPerformanceIndexes();
            if (!failedIndexes.isEmpty()) {
                var indexCheckCompletedAt = indexInitializer.getIndexCheckCompletedAt();
                boolean pastGracePeriod = indexCheckCompletedAt != null
                    && Duration.between(indexCheckCompletedAt, now).compareTo(PERFORMANCE_INDEX_GRACE_PERIOD) >= 0;
                if (pastGracePeriod) {
                    return Health.down()
                        .withDetail("reason", "One or more performance/TTL indexes have been missing for longer than the "
                            + PERFORMANCE_INDEX_GRACE_PERIOD.toMinutes() + "-minute startup grace period -- this needs operator "
                            + "attention (see IndexInitializer's own logs for exactly which index(es)). Queries using these shapes "
                            + "still work correctly, just without the missing index's own performance benefit or, for a TTL index, "
                            + "without automatic expiry of old documents.")
                        .withDetail("failedPerformanceIndexes", failedIndexes)
                        .withDetail("indexCheckCompletedAt", indexCheckCompletedAt)
                        .withDetail("lastScanCompletedAt", lastScanDetail)
                        .withDetail("lastReconciliationCompletedAt", lastReconcileDetail)
                        .build();
                }
            }
            var healthBuilder = Health.up()
                .withDetail("lastScanCompletedAt", lastScanDetail)
                .withDetail("lastReconciliationCompletedAt", lastReconcileDetail);
            if (!failedIndexes.isEmpty()) {
                healthBuilder.withDetail("failedPerformanceIndexes", failedIndexes);
            }
            return healthBuilder.build();
        }

        java.util.List<String> stale = new java.util.ArrayList<>();
        if (!scanRecent) stale.add("the autonomous scanner");
        if (!reconcileRecent) stale.add("position reconciliation");
        return Health.down()
            .withDetail("reason", "Stale worker(s): " + String.join(" and ", stale) + " — has not completed a cycle within "
                + STALE_THRESHOLD.toMinutes() + " minutes. The trading worker may be stuck or the process may not be "
                + "processing its scheduled tasks. This is what an external monitor should alert on.")
            .withDetail("scanRecent", scanRecent)
            .withDetail("reconcileRecent", reconcileRecent)
            .withDetail("lastScanCompletedAt", lastScanDetail)
            .withDetail("lastReconciliationCompletedAt", lastReconcileDetail)
            .build();
    }
}
