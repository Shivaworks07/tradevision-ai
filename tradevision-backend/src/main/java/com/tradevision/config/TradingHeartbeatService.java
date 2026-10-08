package com.tradevision.config;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records when the autonomous scanner and position reconciliation loops last completed a cycle.
 * An internal-only process can never detect its own crash, so this class only produces the
 * heartbeat timestamps; TradingWorkerHealthIndicator turns them into an external-facing signal
 * that real external monitoring (a cron job, a Kubernetes liveness probe, an uptime check) can
 * poll and alert on if either loop stops updating.
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
