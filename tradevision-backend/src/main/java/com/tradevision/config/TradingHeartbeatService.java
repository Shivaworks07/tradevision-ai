package com.tradevision.config;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Review finding ("#10 — External Watchdog" — "Trading worker heartbeat → external monitor →
 * no heartbeat 60 sec → ALERT. Because if TradeVision itself crashes: TradeVision monitoring
 * also disappears"): the review's own point is exactly right — an internal-only watchdog can't
 * detect its own process dying. What CAN be built from inside this application is the heartbeat
 * itself and a real external-facing signal (see TradingWorkerHealthIndicator) that genuine
 * external monitoring — a cron job, UptimeRobot, a Kubernetes liveness probe, PagerDuty's own
 * HTTP check — can poll and alert on. This class is the heartbeat; it is not the watchdog. The
 * watchdog has to live outside this process, by the review's own correct reasoning, and stand-
 * ing up actual external monitoring infrastructure is a deployment decision, not code this
 * session can respectably make unilaterally.
 */
@Component
public class TradingHeartbeatService {

    private final AtomicReference<Instant> lastScanCompletedAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastReconciliationCompletedAt = new AtomicReference<>();

    public void recordScanCompleted() {
        lastScanCompletedAt.set(Instant.now());
    }

    public void recordReconciliationCompleted() {
        lastReconciliationCompletedAt.set(Instant.now());
    }

    public Instant getLastScanCompletedAt() {
        return lastScanCompletedAt.get();
    }

    public Instant getLastReconciliationCompletedAt() {
        return lastReconciliationCompletedAt.get();
    }
}
