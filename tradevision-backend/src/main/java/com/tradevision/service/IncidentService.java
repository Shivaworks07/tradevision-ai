package com.tradevision.service;

import com.tradevision.model.TradingIncident;
import com.tradevision.model.User;
import com.tradevision.repository.TradingIncidentRepository;
import com.tradevision.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Records a durable {@code TradingIncident} for a CRITICAL or WARNING event (a failed protection
 * check, a failed emergency flatten, an unknown order state), so the position dashboard can show
 * a live "currently wrong" view instead of someone reading an audit log, and best-effort notifies
 * the account holder through each configured channel (webhook, email).
 *
 * <p>Scope: email and webhook only. SMS/Telegram/Slack/PagerDuty would each need their own
 * integration and credentials this codebase doesn't have configured; email reuses the Brevo
 * wiring already built for OTP delivery.
 */
@Service
@RequiredArgsConstructor
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
    /**
     * The initial attempt (in {@link #raise}) plus this many scheduled retries (via
     * {@code IncidentRetryService}) before a CRITICAL incident's delivery is marked
     * {@code DELIVERY_EXHAUSTED}.
     */
    static final int MAX_NOTIFICATION_ATTEMPTS = 5;

    private final TradingIncidentRepository incidentRepo;
    private final UserRepository userRepo;
    private final EmailService emailService;
    private final WebhookAlertService webhookAlertService;

    public void raiseCritical(String userId, String credentialId, String positionId, String orderId,
                               String symbol, String type, String message) {
        raise(userId, credentialId, positionId, orderId, symbol, type, "CRITICAL", message);
    }

    public void raiseWarning(String userId, String credentialId, String positionId, String orderId,
                              String symbol, String type, String message) {
        raise(userId, credentialId, positionId, orderId, symbol, type, "WARNING", message);
    }

    private void raise(String userId, String credentialId, String positionId, String orderId,
                        String symbol, String type, String severity, String message) {
        TradingIncident incident = new TradingIncident();
        incident.setUserId(userId);
        incident.setCredentialId(credentialId);
        incident.setPositionId(positionId);
        incident.setOrderId(orderId);
        incident.setSymbol(symbol);
        incident.setType(type);
        incident.setSeverity(severity);
        incident.setMessage(message);
        if (!"CRITICAL".equals(severity)) {
            // WARNING severity never pages at all. NOT_APPLICABLE (not PENDING) is set here since
            // nothing will ever attempt delivery for it, and IncidentRetryService's scheduled
            // pass only looks at PENDING/RETRYING records, never NOT_APPLICABLE ones.
            incident.setNotificationStatus("NOT_APPLICABLE");
            incidentRepo.save(incident);
            return;
        }
        incident = incidentRepo.save(incident);
        attemptDelivery(incident);
    }

    /**
     * Attempts delivery of a CRITICAL incident through every configured channel. Extracted into
     * its own method so both the initial {@link #raise} call and {@code IncidentRetryService}'s
     * scheduled retry pass share the exact same logic -- a retry is the same attempt run again,
     * not a separate code path. Package-private (not private) specifically so
     * {@code IncidentRetryService} can call it.
     */
    void attemptDelivery(TradingIncident incident) {
        incident.setNotificationAttempts(incident.getNotificationAttempts() + 1);
        incident.setLastNotificationAttemptAt(java.time.LocalDateTime.now());
        boolean anyChannelSucceeded = false;
        try {
            User user = userRepo.findById(incident.getUserId()).orElse(null);
            if (user == null) {
                log.warn("Cannot alert user {} for incident type {} — user not found.", incident.getUserId(), incident.getType());
            } else {
                // Webhook and email are attempted independently: a missing/blank email address
                // must not suppress webhook delivery, and a failed webhook must not suppress
                // email. Each channel is independent.
                if (user.getAlertWebhookUrl() != null && !user.getAlertWebhookUrl().isBlank()) {
                    boolean webhookSent = webhookAlertService.send(user.getAlertWebhookUrl(), incident.getType(), incident.getSeverity(),
                        incident.getSymbol(), incident.getMessage());
                    if (webhookSent) anyChannelSucceeded = true;
                    else log.warn("Webhook alert delivery failed for incident {} type {} on user {} (attempt {}).",
                        incident.getId(), incident.getType(), incident.getUserId(), incident.getNotificationAttempts());
                }
                if (user.getEmail() == null || user.getEmail().isBlank()) {
                    log.warn("Cannot email-alert user {} for incident type {} — no email on file.", incident.getUserId(), incident.getType());
                } else {
                    boolean sent = emailService.sendAlert(user.getEmail(),
                        "🔴 TradeVision Alert: " + incident.getType().replace("_", " ") + (incident.getSymbol() != null ? " (" + incident.getSymbol() + ")" : ""),
                        "A critical trading incident needs your attention:\n\n" + incident.getMessage()
                            + "\n\nSymbol: " + (incident.getSymbol() != null ? incident.getSymbol() : "n/a")
                            + "\nCredential: " + (incident.getCredentialId() != null ? incident.getCredentialId() : "n/a")
                            + "\n\nLog into TradeVision AI and check the Positions page for details.");
                    if (sent) anyChannelSucceeded = true;
                    else log.warn("Alert email delivery failed for incident {} type {} on user {} (attempt {}).",
                        incident.getId(), incident.getType(), incident.getUserId(), incident.getNotificationAttempts());
                }
            }
        } catch (Exception e) {
            // An alert failing to send must never mask or interrupt the actual safety action
            // that triggered it — the incident record above already succeeded regardless.
            log.error("Unexpected error sending incident alert for incident {} user {}: {}", incident.getId(), incident.getUserId(), e.getMessage());
        }

        if (anyChannelSucceeded) {
            incident.setNotificationStatus("DELIVERED");
        } else if (incident.getNotificationAttempts() >= MAX_NOTIFICATION_ATTEMPTS) {
            // Logged at the loudest level this application has, with a distinct, greppable
            // marker, since there's no second delivery-guaranteed channel to escalate into
            // without it being exactly as failure-prone as the ones that already failed.
            incident.setNotificationStatus("DELIVERY_EXHAUSTED");
            log.error("NOTIFICATION_DELIVERY_EXHAUSTED: incident {} (type {}, severity {}, user {}) could not be delivered through any "
                + "notification channel after {} attempts. This CRITICAL incident remains durably recorded and visible on the dashboard, "
                + "but the account holder has NOT been actively notified. Manual verification of notification infrastructure (email "
                + "provider, webhook endpoint, DNS, network) is required.",
                incident.getId(), incident.getType(), incident.getSeverity(), incident.getUserId(), incident.getNotificationAttempts());
        } else {
            incident.setNotificationStatus("RETRYING");
        }
        try {
            incidentRepo.save(incident);
        } catch (Exception e) {
            log.warn("Could not persist delivery status for incident {} (non-fatal -- the incident record itself already exists; only "
                + "this specific status update failed): {}", incident.getId(), e.getMessage());
        }
    }
}
