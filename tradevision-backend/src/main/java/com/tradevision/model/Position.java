package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A real open/closed position — NOT inferred from counting order rows (that was review item #5).
 * Risk-engine concurrency checks and realized-P&L tracking both read from this collection now.
 * Long-only / spot-only, matching the rest of this adapter's scope.
 */
// P0-5 fix (same real bug and same fix shape as Order's own identical @CompoundIndex --
// see that class's own javadoc for the full explanation): entryOrderId is a Binance-issued
// order id, unique only per (account, symbol) in the real world, not globally -- the old
// single-field unique index below could reject a genuine second position (different
// credential or different symbol) that happened to share an order-id number with an existing
// one, silently dropping a real fill's Position record. Scoped the same way.
//
// P3-11 fix ("The new order-ID index will block trading on a symbol after one rejected order"
// -- external review, second pass, re-audit): same real bug as Order's own identical index --
// sparse=true on a COMPOUND index only excludes a document missing ALL of the indexed fields, not
// just entryOrderId, and credentialId/symbol are always set. Every Position with entryOrderId
// still null (unverified entry, or created before the broker order id was recorded) collided on
// the same {credentialId, symbol, null} key. Fixed the same way -- a partial index that only
// includes a document once entryOrderId genuinely holds a string. See Order.java's own updated
// comment for the full explanation, and IndexInitializer's own updated comment for the required
// migration on existing deployments.
@CompoundIndex(name = "credential_symbol_entryOrderId_unique",
    def = "{'credentialId': 1, 'symbol': 1, 'entryOrderId': 1}", unique = true,
    partialFilter = "{'entryOrderId': {'$type': 'string'}}")
@Data @NoArgsConstructor
@Document(collection = "positions")
public class Position {
    @Id private String id;

    /**
     * Review finding (P1 #12 -- "Order state written by whole-document save() with no
     * optimistic locking"): Spring Data MongoDB's standard optimistic-locking field, same
     * reasoning as Order.version's own field javadoc. This class's own documented pattern (see
     * this class's top-level javadoc and PositionMonitorService's own atomic-close comments) is
     * that the highest-stakes writes already go through a targeted, atomic $set (which this
     * field does not interact with — an update() bypasses Spring Data's save()-based
     * optimistic-lock check entirely, by design, the same way it does for Order), while a
     * genuine handful of remaining plain positionRepo.save(position) calls exist for lower-risk,
     * less contended fields. This field is the backstop for exactly those remaining save()
     * calls: two concurrent processes each holding their own stale copy of the same Position and
     * both calling save() will now have the SECOND one fail loudly with
     * OptimisticLockingFailureException instead of silently winning with stale data, rather than
     * relying solely on this codebase's own discipline about which fields are safe to
     * plain-save.
     */
    @Version private Long version;

    @Indexed private String userId;
    @Indexed private String credentialId;
    private BrokerType broker;
    private BrokerMode mode;

    private String symbol;
    private BigDecimal quantity;          // filled quantity, updated on partial fills; ZERO once the position reaches any terminal status
    /** Review finding ("NAKED_FLATTENED retains the old quantity" — the same gap applies to
     *  every terminal status, not just that one): preserves what the position's quantity WAS at
     *  the moment it closed, since `quantity` itself is zeroed on close so downstream code that
     *  filters/sums by "quantity > 0" can't misinterpret a closed position as still open. */
    private BigDecimal closedQuantity;
    /**
     * Review finding ("zero-total-balance after a previous partial" -- external review,
     * thirty-first pass, confirmed real by direct inspection before this fix: when an emergency
     * flatten's own balance check finds free+locked already at zero -- meaning THIS operation's
     * own sell never actually executed at all, because there was nothing left to sell -- the
     * remaining internal quantity used to be written straight into closedQuantity, as if THIS
     * flatten had confirmed-sold it. It hadn't: the coins being gone is an assumption based on a
     * zero balance observation, not a confirmed execution this specific sell attempt can take
     * credit for. The review's own point: "Whether this represents a genuinely missing balance
     * or an already-liquidated/manual position is ambiguous... this should not silently
     * manufacture a sale"): the actual amount this application genuinely cannot account for as a
     * confirmed sale by ITS OWN flatten attempt(s) -- set alongside closedQuantity (which after
     * this fix reflects only what a real, confirmed exchange fill actually sold), not instead of
     * it, so the two together always sum to the position's own original quantity without ever
     * asserting more certainty about the unaccounted remainder than the evidence actually
     * supports.
     */
    private BigDecimal unverifiedClosedQuantity;
    // Review finding ("OCO quantity can be smaller than the actual position because of
    // base-asset fees" -- P0): confirmed real by direct inspection of BinanceBrokerAdapter's own
    // placeExitOco -- it rounds DOWN to the symbol's own step size before placing the OCO
    // (Binance rejects a quantity that doesn't exactly match a step-size multiple), which can
    // genuinely differ from quantity (this position's own real, full size after entry fees).
    // Example the review itself names: quantity=10.999, step=1 -> the OCO only ever protects
    // 10, leaving 0.999 with NO stop-loss or take-profit at all. Never tracked or surfaced
    // anywhere before this field -- the application had no way to even ask "is this position
    // FULLY protected?", only "does it have an OCO at all?" Set from OcoOrderResult's own
    // actualProtectedQuantity whenever an OCO is successfully placed (entry, resize, late-fill,
    // remainder -- every real placement site in this codebase). Null means either no OCO has
    // been placed yet, or this position predates this field.
    private BigDecimal protectedQuantity;
    private BigDecimal avgEntryPrice;

    /**
     * Review finding ("Exposure reservation rollback can steal another trade's reservation" /
     * "Generic exposure release() has the same ownership problem" -- external review, twenty-
     * sixth pass, P0, full context in ExposureReservationRecord's own class javadoc): this
     * position's own exact exposure reservation id, set at entry time from
     * ExposureReserveResult.reservationId(). Closing this position releases EXACTLY this
     * reservation (via ExposureReservationService.release(String)) rather than recomputing an
     * amount from current quantity*avgEntryPrice, which the review's own P0-2 finding showed can
     * legitimately differ from what was actually reserved at entry time. Null for positions
     * opened before this field existed, or where no exposure cap was configured at entry time --
     * callers must fall back to the narrower, amount-based release(credentialId, symbol, amount)
     * overload in that case, same as before this fix.
     */
    private String exposureReservationId;

    /**
     * Review finding ("Position slot reservations still don't have ownership IDs" -- external
     * review, twenty-eighth pass, P0, full context in PositionSlotReservationRecord's own class
     * javadoc): this position's own exact slot reservation id, set at entry time. Closing this
     * position releases EXACTLY this reservation (via PositionSlotReservationService.release
     * (String reservationId)) rather than a bare key-based decrement with no ownership check.
     * Null for positions opened before this field existed -- callers must fall back to the
     * narrower releaseByKey(credentialId) overload in that case, same as before this fix.
     */
    private String slotReservationId;
    /** The plan-level tier's own reservation id, when this position's own signal was
     *  attributed to a StrategyPlan with its own maxConcurrentTrades cap -- see
     *  slotReservationId's own field javadoc for the same reasoning, applied to the second tier. */
    private String planSlotReservationId;

    // Review finding ("Entry/position uniqueness is weaker than order uniqueness" -- P1):
    // confirmed real -- Order.clientOrderId/brokerOrderId already have a unique index (P0 #14
    // from earlier this session), but nothing stopped a recovery bug from creating two Position
    // documents for the SAME underlying broker entry order. Unique + sparse, same reasoning as
    // Order's own identical fields: sparse so historical/edge-case Position documents with no
    // entryOrderId recorded at all (null) don't collide with each other under a unique
    // constraint -- only a genuine duplicate NON-NULL entryOrderId is rejected. Actual index
    // creation happens in IndexInitializer (this codebase's own established, deliberate
    // explicit-index-creation design -- the annotation alone does nothing by itself, same gap
    // already caught for Order's own fields).
    // P0-5 fix: no longer unique on its own -- see this class's own @CompoundIndex above.
    // Kept as a plain index for lookup performance.
    @Indexed
    private String entryOrderId;          // brokerOrderId of the entry order
    private String entryClientOrderId;    // idempotency key used on entry
    private String ocoOrderListId;        // protective SL/TP order list, if placed
    private String signalId;
    /** Which StrategyPlan opened this position -- null for a position with no associated plan (pre-multi-plan, or manually triggered). See TradeCallRecord.planId's own field javadoc. */
    private String planId;
    private String triggerSource;         // MANUAL or SIGNAL

    /**
     * Review finding ("Position created before ledger is guaranteed" — "Broker fill happened ->
     * ledger write fails -> Position still created -> no ledger history. That is the opposite of
     * ledger-authoritative design"): confirmed real — fillLedgerService.recordFills() is called
     * before positionRepo.save() on every path, but never throws by its own design (an
     * additive, non-fatal side-channel — see FillLedgerService's own javadoc), so this Position
     * is always created regardless of whether the ledger write actually succeeded.
     *
     * The review's own suggested fix — halt or require RECONCILIATION_REQUIRED on a ledger
     * failure — is NOT what this flag does. Blocking real money flow over a ledger hiccup is
     * exactly the failure mode the ledger's own "additive, non-fatal" design was built to avoid,
     * and the review's own words on this point acknowledge the current design is consistent with
     * the ledger's disclosed, honest scope: "Priority: Becomes P0 the moment you make the ledger
     * authoritative. Until then it's consistent with 'ledger is observability.'" The ledger isn't
     * authoritative yet (see PositionLedgerService's own javadoc). So instead: this flag makes
     * the gap VISIBLE rather than silent — set when a real fill happened but the ledger ended up
     * with fewer records than expected, so an operator or the ops status endpoint can surface
     * "this position's ledger history may be incomplete" without changing execution behavior at
     * all. When the ledger genuinely becomes authoritative later, this is exactly the signal that
     * pass would need to promote into a real halt/RECONCILIATION_REQUIRED — the review's fix,
     * correctly sequenced after the prerequisite it names, not skipped.
     */
    private boolean ledgerRecordingIncomplete;

    /**
     * OPEN, FLATTENING, CLOSED, CLOSED_UNVERIFIED_PNL, NAKED_FLATTENED (entry filled, protection
     * failed, emergency-closed).
     *
     * Review finding ("Position still has no FLATTENING state" -- external review, second pass,
     * confirmed real by direct inspection before any fix was attempted): the distributed
     * flatten lock this codebase already has (see DistributedLockService) is inherently
     * ephemeral -- it does not survive a process crash, JVM restart, or container replacement.
     * A persistent, database-durable marker does: if this application dies mid-flatten (after
     * FLATTENING is set atomically, before the real sell's own outcome is recorded), a restart
     * can find this exact position via a real, targeted query
     * (PositionMonitorService.recoverStuckFlattening's own javadoc has the full recovery
     * design) and resolve it by asking the exchange directly what actually happened, rather
     * than the crash silently leaving the position in a state no code path was watching for.
     * FLATTENING is set atomically (WHERE status="OPEN") at the very start of
     * PositionSafetyService.attemptFlatten's own first attempt -- the same query condition
     * doubling as an extra, database-level guard against a second concurrent flatten attempt
     * on top of the distributed lock, not a replacement for it.
     */
    private String status = "OPEN";

    /**
     * P2-8 fix ("PositionSafetyService.attemptFlatten: flatten clientOrderId deterministic per
     * positionId:attempt; a later flatten episode on the same position reuses it" -- external
     * review, confirmed real by direct inspection: this position CAN legitimately return to
     * OPEN after a flatten attempt (PositionMonitorService.recoverStuckFlattening's own
     * "STUCK_FLATTENING_RECOVERED_PARTIAL" path sets status back to OPEN with the remaining
     * quantity when a partial fill is confirmed but not fully resolved), and a subsequent
     * protection failure or trigger can then start a genuinely NEW flatten episode on this same
     * position id. Before this fix, PositionSafetyService.attemptFlatten's own clientOrderId
     * was generated purely from positionId + attempt (0 or 1) -- with no way to distinguish that
     * new episode's attempt 0 from the FIRST episode's own attempt 0, producing the exact same
     * clientOrderId. OrderService.create's own unique-clientOrderId constraint then rejects the
     * second episode's OMS Order insert as a duplicate key, so the real exchange sell still goes
     * out (attemptFlatten's own established "OMS setup is non-fatal" behavior lets it proceed
     * regardless), but with NO OMS Order record at all for that second episode -- leaving
     * recovery to fall back to the weaker FlattenAttempt/balance heuristics for an episode that
     * should have had a full, unambiguous OMS record.
     *
     * The fix: this counter increments exactly once per flatten EPISODE (every fresh
     * PositionSafetyService.attemptFlatten(attempt=0) call, i.e. every top-level emergencyFlatten
     * or exitPosition invocation -- NOT every retry attempt, since attempt=1 is still the SAME
     * episode continuing), persisted atomically together with the OPEN-&gt;FLATTENING transition
     * so it survives a crash exactly like status itself does. The clientOrderId basis becomes
     * positionId + flattenEpisode + attempt, so two separate episodes on the same position can
     * never collide, while a crash-and-resume WITHIN one episode (attempt 0 then a retry at
     * attempt 1) still reproduces the exact same, already-durable episode number.
     * PositionMonitorService.recoverStuckFlattening's own last-resort blind re-derivation (used
     * only when neither the OMS Order nor the FlattenAttempt record survived) reads this field
     * directly off the stuck position it already has in hand -- no guessing required, since the
     * position's own currently-stamped episode is exactly the one attemptFlatten used.
     */
    private int flattenEpisode = 0;

    private BigDecimal exitPrice;
    private BigDecimal realizedPnlQuote;
    private String closeReason;           // TAKE_PROFIT, STOP_LOSS, EMERGENCY_FLATTEN, MANUAL_CLOSE

    /** Review item #12: real commission from Binance's fill data, in quote-asset terms when the
     *  fee was actually paid in the quote asset. Null means "fee unknown/not netted" — e.g. paid
     *  in BNB via the fee-discount program, which this doesn't convert (documented gap, not a
     *  silently wrong number). realizedPnlQuote is net of these when both are known. */
    private BigDecimal entryFeeQuote;
    private BigDecimal exitFeeQuote;

    /**
     * Review finding ("Fee accounting has no feeStatus (COMPLETE/PARTIAL/UNKNOWN); net P&L can
     * look exact when fees are actually partially unknown" -- P1): confirmed real and fixed.
     * realizedPnlQuote nets whatever fees ARE known, but displayed on its own gives no signal
     * that a fee might be silently missing (e.g. paid in BNB, per this class's own entryFeeQuote
     * javadoc) -- this is that signal, computed rather than stored so it can never go stale
     * against the two fields it actually reads (a stored, separately-updated status field would
     * risk exactly that -- forgotten at one of the many call sites across this codebase that set
     * these fees).
     *
     * COMPLETE: both fees are known (or the position hasn't exited yet, so exitFeeQuote isn't
     *   applicable -- entryFeeQuote known is as complete as an open position can be).
     * PARTIAL: the position has exited, and exactly one of the two fees is known.
     * UNKNOWN: neither fee is known.
     */
    public enum FeeStatus { COMPLETE, PARTIAL, UNKNOWN }

    public FeeStatus getFeeStatus() {
        boolean entryKnown = entryFeeQuote != null;
        // Review finding's own correction, caught before this shipped: "DUST_REMAINING" is a
        // closeReason on this class (see PositionMonitorService's own dust-handling), not a
        // status value -- the actual, complete set of status strings this codebase ever sets is
        // CLOSED, CLOSED_UNVERIFIED_PNL, NAKED_FLATTENED, and OPEN (confirmed directly against
        // every position.setStatus(...) call site, not assumed).
        boolean hasExited = "CLOSED".equals(status) || "NAKED_FLATTENED".equals(status) || "CLOSED_UNVERIFIED_PNL".equals(status);
        boolean exitKnown = exitFeeQuote != null;
        if (!hasExited) {
            return entryKnown ? FeeStatus.COMPLETE : FeeStatus.UNKNOWN;
        }
        if (entryKnown && exitKnown) return FeeStatus.COMPLETE;
        if (!entryKnown && !exitKnown) return FeeStatus.UNKNOWN;
        return FeeStatus.PARTIAL;
    }

    /**
     * Review finding ("#15" — "partial entry correction has a dangerous fallback"): set true
     * when a quantity correction had to proceed without being able to independently verify the
     * average entry price (a fills lookup failed) — the quantity is real (confirmed via order
     * status), but avgEntryPrice may still be stale. Downstream P&L/risk/ML numbers for this
     * position should be treated as provisional until this is cleared by a successful
     * reconciliation or manual review. Distinguishes "I tried to reconcile" from "I did."
     */
    private boolean avgEntryPriceUnverified = false;

    private LocalDateTime openedAt = LocalDateTime.now();
    private LocalDateTime closedAt;
    /**
     * Review finding ("Execution latency still entry-focused" — "OCO submit/ack, exit fill,
     * cancel request/confirm, emergency flatten timeline are not a complete lifecycle analytics
     * model"): confirmed real — Order (the OMS entity) only tracks the entry path; OCO/exit
     * remain outside OMS in this pass (see OrderService's own disclosed scope). This is the
     * bounded, additive piece of that gap this pass closes: entry-to-first-protection latency,
     * at the Position level so it works for both OMS-tracked and non-OMS-tracked flows alike.
     * Set once, via recordOcoPlaced() below — a resize or recovery re-placement later in this
     * position's life does NOT overwrite it, so this always measures the FIRST protection, not
     * whichever placement happened most recently.
     *
     * HONEST SCOPE: this is entry-to-first-protection only. Exit fill, cancel request/confirm,
     * and emergency-flatten timing remain unmeasured — building those properly needs the OMS
     * migration for OCO/exit paths (declined, unchanged, for the same reasons stated throughout
     * this session: replacing safety-critical, already-hardened logic without a compiler or real
     * broker to verify the replacement against).
     */
    private LocalDateTime ocoPlacedAt;

    /** See ocoPlacedAt's own javadoc for why this guards against overwriting a real first value. */
    public void recordOcoPlaced() {
        if (this.ocoPlacedAt == null) this.ocoPlacedAt = LocalDateTime.now();
    }
}
