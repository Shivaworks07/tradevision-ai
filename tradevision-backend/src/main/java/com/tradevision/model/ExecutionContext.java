package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A single traceability record spanning one signal's entire execution lifecycle. This is a
 * TRACEABILITY layer, not a replacement for the underlying atomic-claim/reservation mechanisms
 * (ExposureReservationRecord, PositionSlotReservationRecord, FlattenAttempt, ProtectionAttempt,
 * RiskProfileService.ExecutionClaim) — those remain the real correctness guarantees.
 * ExecutionContext is created once at the start of a signal's evaluation and durably records
 * each stage's own already-existing id as that stage completes, giving an operator (or an
 * automated recovery pass) a single place to query by executionId alone to see the entire
 * lifecycle's status, then cross-reference into whichever specific record needs closer
 * inspection — rather than having to manually correlate scattered records by
 * credentialId/symbol/timestamp.
 *
 * Every write to this document is deliberately best-effort (see ExecutionContextService's own
 * class javadoc) — a failure to record a stage transition here must never block or fail the real
 * trading flow it's merely observing.
 */
@Data @NoArgsConstructor
@Document(collection = "execution_contexts")
public class ExecutionContext {
    @Id
    private String executionId;

    @Indexed
    private String signalId;
    @Indexed
    private String credentialId;
    private String userId;
    private String planId;

    // Filled in progressively as each real stage actually completes -- null means "this
    // execution never reached that stage" (a genuine, meaningful fact on its own: an
    // executionId with a slotReservationId but no exposureReservationId tells you exactly
    // where a crash or a rejection happened, without needing to guess).
    private String slotReservationId;
    private String planSlotReservationId;
    private String exposureReservationId;
    private String executionClaimId;
    private String clientOrderId;
    private String positionId;
    private String protectionAttemptId;
    private String ocoOrderListId;

    /**
     * STARTED, RISK_APPROVED, SLOTS_RESERVED, EXPOSURE_RESERVED, CLAIMED, ORDER_SUBMITTED,
     * FILLED, PROTECTED, CLOSED, REJECTED, FAILED. A plain String, not a Java enum -- this
     * document is written from many different call sites across the evaluation lifecycle (see
     * ExecutionContextService's own methods), and a String survives a future stage being added
     * without needing every writer recompiled against a shared enum type.
     */
    private String status = "STARTED";
    private String terminalReason;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
}
