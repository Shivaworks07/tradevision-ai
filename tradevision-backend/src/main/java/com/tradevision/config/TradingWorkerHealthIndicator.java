package com.tradevision.config;

import lombok.RequiredArgsConstructor;
// Health/HealthIndicator/Status live in the spring-boot-health module (transitively pulled in
// by spring-boot-starter-actuator) rather than org.springframework.boot.actuate.health.
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Spring Boot Actuator HealthIndicator surfaced automatically at GET /actuator/health under the
 * key "tradingWorker" (Spring Boot's naming convention: a bean named XHealthIndicator maps to
 * key X). Any external monitor can poll this endpoint and alert on a non-UP status.
 *
 * Reports DOWN if either the scanner or reconciliation has not completed a cycle within
 * STALE_THRESHOLD. Both are @Scheduled at a 60s fixedDelay, so a 5-minute threshold gives real
 * buffer for transient slowness (a slow Binance response, GC pause, momentary DB latency) while
 * still catching a genuinely stuck or dead worker.
 *
 * Both heartbeats, not just one, must be recent for a healthy report: a dead reconciliation
 * worker — the safety backstop for OCO state, fills, position state, late fills, protection
 * failures, drawdown, and ledger consistency — is not equivalent to a healthy trading system
 * just because the scanner is still running. STARTUP_GRACE_PERIOD below, anchored to
 * StartupState's tradingEnabledAt timestamp (when trading became enabled, not just that it is),
 * covers the brief window right after startup where one heartbeat legitimately hasn't fired yet,
 * without treating a worker that has actually been dead for minutes as merely still starting up.
 */
@Component("tradingWorkerHealthIndicator")
@RequiredArgsConstructor
public class TradingWorkerHealthIndicator implements HealthIndicator {

    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);
    /**
     * How long a failed performance/TTL index is tolerated before it escalates from a visible
     * detail into an actual DOWN status: long enough that a transient issue right at application
     * startup doesn't immediately page anyone, short enough that a genuinely persistent problem
     * doesn't stay silent for long.
     */
    private static final Duration PERFORMANCE_INDEX_GRACE_PERIOD = Duration.ofMinutes(15);
    // Both @Scheduled tasks' initialDelay values (45s scanner, 30s reconciliation) are measured
    // from application startup, not from when trading becomes enabled — so even after
    // isTradingEnabled() flips true, there is a real, narrow window where one hasn't fired yet.
    // Set generously above that worst case, since the property that matters is "long enough to
    // never false-alarm on a normal startup," not "as short as possible."
    private static final Duration STARTUP_GRACE_PERIOD = Duration.ofMinutes(2);

    private final TradingHeartbeatService heartbeatService;
    private final StartupState startupState;
    /** Surfaces failed performance/TTL indexes as visible health detail (non-gating on its own). */
    private final IndexInitializer indexInitializer;

    @Override
    public Health health() {
        // Before startup reconciliation has confirmed real broker state, neither loop is
        // expected to have produced a heartbeat yet — reporting DOWN during a normal, brief
        // startup window would be a false alarm, not a real signal worth alerting an operator on.
        // Each startup phase maps to a distinct Spring Actuator Status rather than collapsing
        // every pre-TRADING_ENABLED phase into UP, since RECONCILIATION_FAILED is a genuinely bad
        // state that an external monitor needs to learn about, not a normal startup transient.
        // Status has exactly four built-in values (UP, DOWN, OUT_OF_SERVICE, UNKNOWN).
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
            // OUT_OF_SERVICE's documented meaning ("still published, but not accessible") fits
            // this in-between state — genuinely still starting up, not a failure, but not ready
            // to be trusted as normal worker health either — more precisely than UP would.
            return Health.status(Status.OUT_OF_SERVICE)
                .withDetail("reason", "Startup reconciliation against the real broker state is currently in progress.")
                .withDetail("startupPhase", phase.name())
                .build();
        }
        if (phase == StartupState.Phase.TRADING_ENABLED && !startupState.areCriticalIndexesOk()) {
            // A different failure mode from RECONCILIATION_FAILED above: reconciliation itself
            // succeeded, but a safety-critical unique index failed to be confirmed (see
            // IndexInitializer's own logs for which one). Also blocks isTradingEnabled(), and
            // gets its own distinct message rather than falling through to "not yet complete".
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
        // Startup grace period anchored to the actual moment trading became enabled.
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
            // A failed performance index is non-gating only for a short grace period after the
            // index check ran near application startup — a transient issue right at that moment
            // shouldn't immediately page anyone — but one still missing well past that grace
            // period escalates to a real operational problem worth alerting on, rather than
            // remaining a detail field an operator could go on not reading indefinitely.
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
