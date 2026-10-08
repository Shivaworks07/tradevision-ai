package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A dedicated, queryable record of a trading failure that needs a human's attention —
 * protection failed, emergency flatten failed, unknown order state, reconciliation mismatch,
 * and similar critical conditions — kept separate from routine audit-log activity so the UI
 * can surface outstanding critical incidents directly instead of scanning an audit log for them.
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
     * Tracks whether this incident's own notification (email/webhook) has actually been
     * delivered, since delivery is attempted asynchronously and can itself fail — a durable
     * status here, plus IncidentRetryService's scheduled retry pass, closes the chain of
     * "incident persisted -> notification attempted -> delivery status persisted -> retry ->
     * eventually escalate" rather than treating delivery as fire-and-forget.
     *
     * PENDING (delivery not yet attempted, or not applicable -- WARNING severity never pages at
     * all), DELIVERED (at least one channel confirmed success), RETRYING (every channel failed
     * so far, but retries remain), DELIVERY_EXHAUSTED (every channel failed across every retry
     * attempt -- logged at CRITICAL level with a distinct, greppable marker, since there is no
     * second guaranteed-delivery channel to escalate into without the escalation path itself
     * being able to fail the same way).
     */
    private String notificationStatus = "PENDING";
    private int notificationAttempts = 0;
    private LocalDateTime lastNotificationAttemptAt;
}
