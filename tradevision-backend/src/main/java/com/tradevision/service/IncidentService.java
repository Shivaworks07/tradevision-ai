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
 * Review finding (P1 #19/#20 — "Emergency alerts are still missing" / "Need a durable incident
 * model"): before this, a CRITICAL failure (protection failed, emergency flatten failed, order
 * state unknown) only ever produced an audit log line and a halted profile — durable, but nobody
 * was actually told, and there was no queryable "what's currently wrong" view. This does both:
 * a durable TradingIncident record (so the position dashboard can show "🔴 1 CRITICAL" instead of
 * someone reading an audit log), and a best-effort email alert to the account holder.
 *
 * Honest scope: email only. SMS/Telegram/Slack/PagerDuty (all mentioned in the review) would each
 * need their own integration and credentials this codebase doesn't have configured — email reuses
 * the Brevo wiring already built for OTP delivery, which is why it's the one implemented here.
 */
@Service
@RequiredArgsConstructor
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
    /**
     * Review finding, same context as TradingIncident.notificationStatus's own field javadoc:
     * the initial attempt (in raise()) plus this many scheduled retries (via
     * IncidentRetryService) before a CRITICAL incident's own delivery is marked
     * DELIVERY_EXHAUSTED.
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
            // Review finding, same context as TradingIncident.notificationStatus's own field
            // javadoc: WARNING severity never pages at all (matches this method's own
            // pre-existing 🔴/🟠 distinction) -- PENDING would be misleading here, since nothing
            // will ever attempt delivery for it, and IncidentRetryService's own scheduled pass
            // only ever looks at PENDING/RETRYING records, never NOT_APPLICABLE ones.
            incident.setNotificationStatus("NOT_APPLICABLE");
            incidentRepo.save(incident);
            return;
        }
        incident = incidentRepo.save(incident);
        attemptDelivery(incident);
    }

    /**
     * Review finding ("Critical alerting is still best-effort" -- external review, thirty-sixth
     * pass, P1, full context in TradingIncident.notificationStatus's own field javadoc): the
     * actual delivery attempt, extracted so both the initial raise() call and
     * IncidentRetryService's own scheduled retry pass share the exact same logic -- a retry is
     * not a different code path that could itself have different bugs, it's the same attempt
     * run again. Package-private (not private) specifically so IncidentRetryService can call it.
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
                // Review finding ("Alerting hooks"): webhook attempted independently of email — a
                // missing/blank email address must not also suppress webhook delivery, and a failed
                // webhook must not suppress email. Each channel is genuinely independent.
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
            // Review finding, same context as TradingIncident.notificationStatus's own field
            // javadoc: the review's own explicit "eventually escalate" -- logged at the loudest
            // level this application has, with a distinct, greppable marker, since a genuine
            // second delivery-guaranteed channel to escalate INTO doesn't exist here without
            // itself being exactly as failure-prone as the ones that already failed.
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
