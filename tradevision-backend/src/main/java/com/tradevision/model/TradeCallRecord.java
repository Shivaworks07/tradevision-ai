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
     * Review finding ("Evaluation recovery now excludes APPROVED, but APPROVED can become
     * permanently abandoned" -- external review, twenty-sixth pass, P1, full context in
     * AutoTradeRecoveryService.detectStaleApprovedSignals's own javadoc): set once an
     * EXECUTION_STALE incident has been raised for this signal sitting at APPROVED past the
     * timeout, so the hourly scheduled check doesn't raise a duplicate incident for the same
     * still-stale signal on every subsequent run.
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
     * Review finding ("Strategy Plan disable vs execution is still technically non-atomic" --
     * external review, fourteenth pass, P1, full context in StrategyPlan.version's own field
     * javadoc): the plan's own version at the exact moment this signal was generated -- the
     * value AutoTradeService's own final claim (StrategyPlanService.claimPlanExecution) verifies
     * still matches the plan's current version before allowing execution. Null for a signal with
     * no associated plan at all, same as planId itself.
     */
    private Long planVersion;

    // ── Signal ────────────────────────────────────────────────
    private String direction;       // LONG, SHORT, WAIT
    private String signal;          // STRONG BUY, BUY, NEUTRAL, SELL, STRONG SELL
    private int    confidence;

    // ── Trade Levels ─────────────────────────────────────────
    // Review finding (P1 #7 — "Financial values still mix double and BigDecimal", re-flagged
    // across multiple review passes, now actually converted here): the earlier comment on this
    // exact spot explained why this was deferred (cross-cutting scope across
    // TradeCallRequest/ML export/frontend). Scoped here to what's genuinely safe without a
    // compiler to verify a full cross-boundary conversion: this model's own 7 fields are now
    // BigDecimal, and every real accessor usage across the backend (confirmed via a precise
    // search for .getEntryPrice()/.setEntryPrice()/etc., not a broad field-name substring match
    // that would have caught unrelated classes' own same-named fields) has been updated to
    // match. TradeCallRequest (the wire-format input DTO) deliberately still uses double at the
    // JSON boundary -- that's a normal, safe type-conversion point (BigDecimal.valueOf(...) at
    // construction), not the "inconsistency" the earlier comment was concerned about; the actual
    // concern (these values being double throughout STORAGE and ANALYTICS, where P&L math
    // actually happens) is what this fix closes.
    // Defaults to BigDecimal.ZERO, deliberately matching the old primitive double's own
    // implicit 0.0 default -- a BigDecimal field with no explicit default would be null
    // wherever the old code relied on that implicit zero, a real regression risk this default
    // avoids.
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

    // Review finding (P1 #5 — "Auto-trading is not durable"): confirmed real — the old flow was
    // save-then-fire-an-@Async-task, with no durable record of whether that task actually ran.
    // A crash in the narrow window after save but before the async task executes meant the
    // signal existed in Mongo but was never evaluated, and nothing ever revisited it. This field
    // is the outbox: PENDING (saved, not yet evaluated) -> EVALUATING (a worker has picked it
    // up) -> EVALUATED (evaluation against every applicable risk profile completed, whether or
    // not that resulted in an actual trade — "no trade" is a complete outcome, not a failure).
    // AutoTradeRecoveryService periodically re-dispatches anything stuck in PENDING or
    // EVALUATING for too long — the "Mongo-backed outbox/worker" the review itself said would be
    // enough for this stage, not a message broker.
    @Indexed private String autoTradeEvalStatus = "PENDING";

    // Review finding (P1 — "Auto-trade recovery has a duplicate-evaluation edge"): confirmed
    // real, and a real bug in the recovery worker's original design — its staleness check for
    // an EVALUATING signal compared against calledAt (when the signal was CREATED), not when the
    // current evaluation attempt actually STARTED. A signal that sat queued for 8 minutes before
    // being claimed, then legitimately evaluating for only 2 more, would already be >10 minutes
    // past calledAt — the recovery worker would incorrectly consider it stuck and reset it to
    // PENDING, letting a second worker claim and evaluate the SAME signal concurrently with the
    // first, still-alive one. Set atomically at the exact moment the PENDING->EVALUATING claim
    // succeeds — the recovery worker's staleness check now measures against THIS, not calledAt.
    private LocalDateTime autoTradeEvalStartedAt;

    // Review finding ("Auto-trade recovery still needs a lease"): confirmed sound reasoning —
    // a fixed elapsed-time reclaim (autoTradeEvalStartedAt + 10 minutes) can steal a signal from
    // a worker that's simply slow but genuinely still alive and working on it. A real lease —
    // set at claim time, checked at reclaim time — only reclaims once the lease has actually
    // expired, not merely "a while has passed". evaluationOwner identifies which worker instance
    // currently holds the lease (informational/diagnostic — the lease EXPIRY, not the owner
    // identity, is what actually gates reclaiming).
    private String evaluationOwner;
    private LocalDateTime evaluationLeaseUntil;

    // Review finding ("Auto-trade recovery still has a durability problem" — "I'd distinguish
    // EVALUATED from EVALUATION_FAILED, and only mark EVALUATED after all intended work
    // completed"): confirmed real — autoTradeEvalStatus used to unconditionally become
    // "EVALUATED" in a finally block regardless of whether an exception interrupted the work,
    // meaning a transient infrastructure failure could produce a signal that LOOKS complete but
    // never actually finished, and the recovery worker would never pick it up (its own query
    // only looks for PENDING/EVALUATING, not "EVALUATED but actually failed"). autoTradeEvalStatus
    // itself is unchanged (still a plain String, matching this file's existing convention) — the
    // new value is "EVALUATION_FAILED", set instead of "EVALUATED" specifically when the
    // evaluation loop was interrupted by an exception, so it's visibly distinct from a genuine
    // completion rather than indistinguishable from one.

    // Review finding ("#3 — Signal engine" — full context in SignalStatus's own javadoc): a
    // SEPARATE lifecycle from autoTradeEvalStatus above — that field tracks "has this signal's
    // dispatch been durably claimed and processed" (a narrower, crash-recovery concept, #5's own
    // work). This tracks the review's own requested GENERATED -> VALIDATING -> RISK_REJECTED ->
    // APPROVED -> ORDER_PENDING -> EXECUTED lifecycle — a different axis, not a replacement.
    private SignalStatus signalStatus = SignalStatus.GENERATED;

    // Review item #16 (fixed): this used to auto-delete every record after 90 days via a Mongo
    // TTL index — directly contradictory with using this same collection as ML training history.
    // expiresAt is kept as a plain field (still useful for other logic that reads it) but the
    // @Indexed(expireAfterSeconds=...) TTL behavior is removed: nothing in this collection
    // auto-deletes anymore. If storage growth becomes a real concern later, the fix is a
    // deliberate archive/cold-storage job, not a silent TTL that also destroys training data.
    private LocalDateTime expiresAt = LocalDateTime.now().plusDays(90);
}
