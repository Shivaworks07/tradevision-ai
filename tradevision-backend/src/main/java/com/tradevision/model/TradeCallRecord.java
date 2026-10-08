package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import java.time.LocalDateTime;
import java.math.BigDecimal;

/**
 * Core trade call record.
 * Features and outcome are embedded for clean separation.
 */
@Data @NoArgsConstructor
@Document(collection = "trade_calls")
@CompoundIndexes({
    @CompoundIndex(name = "user_symbol",  def = "{'userId':1,'symbol':1}"),
    @CompoundIndex(name = "user_result",  def = "{'userId':1,'outcome.result':1}"),
    @CompoundIndex(name = "user_regime",  def = "{'userId':1,'features.regime':1}")
})
public class TradeCallRecord {
    @Id private String id;

    // ── Identity ──────────────────────────────────────────────
    @Indexed private String userId;
    /**
     * Set once an EXECUTION_STALE incident has been raised for this signal sitting at APPROVED
     * past the timeout, so the hourly scheduled check (AutoTradeRecoveryService) doesn't raise
     * a duplicate incident for the same still-stale signal on every subsequent run.
     */
    private boolean staleApprovedIncidentRaised = false;
    private String symbol;
    private String market;          // STOCK, CRYPTO, FOREX
    private String timeframe;
    /**
     * Which StrategyPlan (see its own class javadoc) this signal was scanned for and should be
     * evaluated/executed under -- null for a signal not associated with any plan (a manually
     * submitted, non-autonomous signal from before multi-plan support existed, or a signal
     * whose credential has no plans at all yet). Threaded through to Position/Order at creation
     * time so a position can always be traced back to the exact plan that opened it.
     */
    private String planId;
    /**
     * The plan's own version (see StrategyPlan.version) at the exact moment this signal was
     * generated -- the value AutoTradeService's own final claim
     * (StrategyPlanService.claimPlanExecution) verifies still matches the plan's current
     * version before allowing execution, so a plan edited or disabled after this signal was
     * generated cannot execute under stale authorization. Null for a signal with no associated
     * plan at all, same as planId itself.
     */
    private Long planVersion;

    // ── Signal ────────────────────────────────────────────────
    private String direction;       // LONG, SHORT, WAIT
    private String signal;          // STRONG BUY, BUY, NEUTRAL, SELL, STRONG SELL
    private int    confidence;

    // ── Trade Levels ─────────────────────────────────────────
    // These price/ratio fields are BigDecimal so storage and analytics (where P&L math
    // actually happens) avoid floating-point representation error. TradeCallRequest (the
    // wire-format input DTO) still uses double at the JSON boundary and converts via
    // BigDecimal.valueOf(...) at construction -- a normal, safe type-conversion point.
    // Defaults to BigDecimal.ZERO, matching a primitive double's implicit 0.0 default, so
    // nothing that relies on an implicit zero sees null instead.
    private BigDecimal entryPrice = BigDecimal.ZERO;
    private BigDecimal stopLoss = BigDecimal.ZERO;
    private BigDecimal target1 = BigDecimal.ZERO;
    private BigDecimal target2 = BigDecimal.ZERO;
    private BigDecimal target3 = BigDecimal.ZERO;
    private BigDecimal atr = BigDecimal.ZERO;
    private BigDecimal rrRatio = BigDecimal.ZERO;
    private String risk;            // LOW, MEDIUM, HIGH

    // ── Summary ──────────────────────────────────────────────
    private String summary;

    // ── Feature Set (ML training input) ──────────────────────
    private TradeFeatures features = new TradeFeatures();

    // ── Outcome (set after resolution) ───────────────────────
    private TradeOutcome outcome = new TradeOutcome();

    // ── ML Prediction (filled by Phase 3 Python API) ─────────
    /** Null until Python ML service is deployed. Slot ready now. */
    private MLPrediction mlPrediction;

    // ── Timestamps ───────────────────────────────────────────
    private LocalDateTime calledAt  = LocalDateTime.now();

    // Durable outbox status for this signal's own evaluation, so a crash between saving the
    // signal and an async evaluation task actually running doesn't leave it silently
    // unevaluated: PENDING (saved, not yet evaluated) -> EVALUATING (a worker has picked it up)
    // -> EVALUATED (evaluation against every applicable risk profile completed, whether or not
    // that resulted in an actual trade -- "no trade" is a complete outcome, not a failure).
    // AutoTradeRecoveryService periodically re-dispatches anything stuck in PENDING or
    // EVALUATING for too long.
    @Indexed private String autoTradeEvalStatus = "PENDING";

    // When the current evaluation attempt actually started, set atomically at the moment the
    // PENDING->EVALUATING claim succeeds. The recovery worker's staleness check measures
    // against this, not calledAt (when the signal was created) -- otherwise a signal that sat
    // queued for a while before being claimed could be mistaken for stuck shortly after a
    // worker legitimately picked it up, letting a second worker claim and evaluate it
    // concurrently with the first.
    private LocalDateTime autoTradeEvalStartedAt;

    // A lease on this signal's evaluation: set at claim time, checked at reclaim time, so a
    // worker that is simply slow but still alive isn't mistaken for stuck and have its signal
    // reclaimed on a fixed elapsed-time basis alone. evaluationOwner identifies which worker
    // instance currently holds the lease (informational/diagnostic -- the lease expiry, not
    // the owner identity, is what actually gates reclaiming).
    private String evaluationOwner;
    private LocalDateTime evaluationLeaseUntil;

    // autoTradeEvalStatus becomes "EVALUATED" only when evaluation actually completed; if the
    // evaluation loop was interrupted by an exception it becomes "EVALUATION_FAILED" instead,
    // so a transient infrastructure failure produces a status the recovery worker's own
    // PENDING/EVALUATING query can distinguish from a genuine completion and revisit.

    // The signal's own decision lifecycle (see SignalStatus) -- a separate axis from
    // autoTradeEvalStatus above, which tracks whether dispatch/evaluation has been durably
    // claimed and processed (a crash-recovery concept), not what decision was reached.
    private SignalStatus signalStatus = SignalStatus.GENERATED;

    // Kept as a plain field for other logic that reads it, but with no TTL index attached --
    // this collection also serves as ML training history, so records are never auto-deleted.
    // If storage growth becomes a concern, the answer is a deliberate archive/cold-storage job,
    // not a TTL that would also destroy training data.
    private LocalDateTime expiresAt = LocalDateTime.now().plusDays(90);
}
