package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Review finding (P1 #20 — "Need a durable incident model"): confirmed real — critical failures
 * (protection failed, emergency flatten failed, unknown order state, reconciliation mismatch)
 * were only ever visible as audit log lines mixed in with routine activity. This is a dedicated,
 * queryable record for the failure modes that actually need a human's attention, so the UI can
 * show "🔴 1 CRITICAL" instead of someone having to read through an audit log to notice.
 */
@Data @NoArgsConstructor
@Document(collection = "trading_incidents")
public class TradingIncident {
    @Id private String id;

    @Indexed private String userId;
    @Indexed private String credentialId;
    private String positionId;
    private String orderId;
    private String symbol;

    /** PROTECTION_FAILED, PROTECTION_UNKNOWN, EMERGENCY_FLATTEN_FAILED, ORDER_STATE_UNKNOWN, RECONCILIATION_MISMATCH, BROKER_UNAVAILABLE, RISK_LIMIT_BREACH, WEBSOCKET_DISCONNECTED */
    private String type;
    /** CRITICAL, WARNING */
    private String severity;
    private String message;

    @Indexed private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime acknowledgedAt;
    private LocalDateTime resolvedAt;
    private String resolution;

    /**
     * Review finding ("Critical alerting is still best-effort" -- external review, thirty-sixth
     * pass, P1, confirmed real by direct inspection before this fix: IncidentService's own
     * webhook/email delivery attempts were fire-and-forget -- a failure was logged, but nothing
     * about that failure was ever durably recorded on the incident itself, and nothing ever
     * retried it. If Brevo, the account holder's own webhook endpoint, DNS, or the network were
     * down at the exact moment a CRITICAL incident fired, the incident stayed durably recorded
     * in Mongo -- but the actual page never happened, and nothing else would ever know or try
     * again): the actual fix -- these fields, and IncidentRetryService's own scheduled retry
     * pass, close the review's own explicit required chain: "incident persisted -> notification
     * attempted -> delivery status persisted -> retry -> eventually escalate."
     *
     * PENDING (delivery not yet attempted, or not applicable -- WARNING severity never pages at
     * all), DELIVERED (at least one channel confirmed success), RETRYING (every channel failed
     * so far, but retries remain), DELIVERY_EXHAUSTED (every channel failed across every retry
     * attempt -- the review's own "eventually escalate": logged at CRITICAL level with a
     * distinct, greppable marker precisely because a second notification channel with its own
     * guaranteed delivery doesn't exist to escalate INTO without becoming circular -- something
     * that could itself silently fail the same way).
     */
    private String notificationStatus = "PENDING";
    private int notificationAttempts = 0;
    private LocalDateTime lastNotificationAttemptAt;
}
