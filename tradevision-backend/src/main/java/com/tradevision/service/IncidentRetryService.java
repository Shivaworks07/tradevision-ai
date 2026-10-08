package com.tradevision.service;

import com.tradevision.config.ShutdownState;
import com.tradevision.model.TradingIncident;
import com.tradevision.repository.TradingIncidentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Retries notification delivery for incidents whose last delivery attempt failed. The full chain
 * is "incident persisted -> notification attempted -> delivery status persisted -> retry ->
 * eventually escalate"; IncidentService's own raise() already does the first three steps, and
 * this service is the fourth, run on a schedule independent of whatever originally raised the
 * incident (which may itself be long finished by the time a retry is due).
 *
 * Kept separate from IncidentService rather than a self-scheduling retry inside it: a scheduled
 * bean is the natural place for "run this periodically," and keeping it separate means
 * IncidentService's own raise() path stays simple and synchronous. This service is purely
 * additive, re-driving the exact same attemptDelivery() logic raise() already uses rather than a
 * second, parallel delivery implementation that could drift from it.
 */
@Service
@RequiredArgsConstructor
public class IncidentRetryService {

    private static final Logger log = LoggerFactory.getLogger(IncidentRetryService.class);
    /**
     * Backoff between retry attempts for the SAME incident -- deliberately well above this
     * service's own scheduling interval, so a single incident isn't retried on every single
     * scheduled pass regardless of how recently it last failed. A genuinely down notification
     * provider (Brevo, DNS, network) rarely recovers within seconds; retrying every few minutes
     * is a reasonable balance between "eventually deliver" and "don't hammer an already-failing
     * dependency."
     */
    private static final Duration RETRY_BACKOFF = Duration.ofMinutes(3);

    private final TradingIncidentRepository incidentRepo;
    private final IncidentService incidentService;
    private final ShutdownState shutdownState;

    @Scheduled(fixedDelay = 120_000, initialDelay = 90_000, scheduler = "maintenanceScheduler")
    public void retryPendingDeliveries() {
        // Same "don't start new scheduled work once shutdown has begun" discipline as
        // PositionMonitorService's own reconciliation loop.
        if (shutdownState.isShuttingDown()) {
            log.info("Shutdown in progress -- skipping this incident-notification-retry cycle.");
            return;
        }
        List<TradingIncident> pending = incidentRepo.findByNotificationStatusIn(List.of("PENDING", "RETRYING"));
        if (pending.isEmpty()) return;

        LocalDateTime backoffCutoff = LocalDateTime.now().minus(RETRY_BACKOFF);
        int retried = 0;
        for (TradingIncident incident : pending) {
            if (shutdownState.isShuttingDown()) {
                log.info("Shutdown began mid-loop in incident-notification-retry -- stopping the rest of this pass.");
                return;
            }
            // The backoff itself: an incident whose last attempt was too recent is left for a
            // later scheduled pass, not retried on every single cycle.
            if (incident.getLastNotificationAttemptAt() != null && incident.getLastNotificationAttemptAt().isAfter(backoffCutoff)) {
                continue;
            }
            try {
                incidentService.attemptDelivery(incident);
                retried++;
            } catch (Exception e) {
                // attemptDelivery itself already catches and logs delivery-channel failures
                // internally -- this catch is only for a genuinely unexpected failure in the
                // retry loop itself (e.g. a database error persisting the updated status),
                // which must never stop the rest of this pass from retrying other incidents.
                log.warn("Unexpected error retrying notification delivery for incident {}: {}", incident.getId(), e.getMessage());
            }
        }
        if (retried > 0) {
            log.info("Incident notification retry pass: attempted redelivery for {} incident(s).", retried);
        }
    }
}
