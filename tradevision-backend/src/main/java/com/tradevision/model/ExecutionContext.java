package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * User's own explicit architectural request, given verbatim after this session's own third
 * consecutive review pass finding the same underlying pattern (a reservation/claim with no
 * unifying identity) in a different place each time: "At this point, I would stop creating
 * separate reservation fixes. Build a single concept: ExecutionContext... Then if something
 * crashes, executionId is enough to reconstruct the entire operation."
 *
 * DESIGN DECISION, stated plainly rather than left implicit: this is a TRACEABILITY layer, not a
 * replacement for the reservation/claim mechanisms this session already built and hardened
 * (ExposureReservationRecord, PositionSlotReservationRecord, FlattenAttempt, ProtectionAttempt,
 * RiskProfileService.ExecutionClaim). Those are real, working, already-tested atomic-claim
 * primitives — ripping them out in favor of a single new abstraction, without a compiler to
 * verify the rewrite, would trade proven correctness for a redesign risk with no real safety
 * upside. Instead, ExecutionContext is created once at the very start of a signal's evaluation
 * and durably records EACH stage's own already-existing id as that stage completes — the single
 * place an operator (or a future automated recovery pass) can query by executionId alone to see
 * the entire lifecycle's real status, then cross-reference into whichever specific record needs
 * closer inspection. The underlying atomic-claim guarantees are exactly as strong as they were
 * before this fix; what's new is that they're now traceable as one story instead of scattered
 * facts an operator has to manually correlate by credentialId/symbol/timestamp.
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
