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
 * A position's own real open/closed lifecycle, tracked directly rather than inferred from
 * counting order rows. Risk-engine concurrency checks and realized-P&L tracking both read from
 * this collection. Long-only / spot-only, matching the rest of this adapter's scope.
 */
// entryOrderId is a broker-issued order id, unique only per (account, symbol) in the real
// world, not globally -- a single-field unique index could reject a genuine second position
// (different credential or different symbol) that happened to share an order-id number with
// an existing one. Scoped the same way as Order's own identical @CompoundIndex -- see that
// class's own javadoc for the full explanation.
//
// This is a partial index, not merely sparse, for the same reason as Order's own identical
// index: sparse=true on a compound index only excludes a document missing ALL of the indexed
// fields, not just entryOrderId, and credentialId/symbol are always set. Every Position with
// entryOrderId still null (unverified entry, or created before the broker order id was
// recorded) would otherwise collide on the same {credentialId, symbol, null} key. See
// Order.java's own comment for the full explanation, and IndexInitializer's own comment for
// the required migration on existing deployments.
@CompoundIndex(name = "credential_symbol_entryOrderId_unique",
    def = "{'credentialId': 1, 'symbol': 1, 'entryOrderId': 1}", unique = true,
    partialFilter = "{'entryOrderId': {'$type': 'string'}}")
@Data @NoArgsConstructor
@Document(collection = "positions")
public class Position {
    @Id private String id;

    /**
     * Spring Data MongoDB's standard optimistic-locking field, same reasoning as Order.version.
     * The highest-stakes writes already go through a targeted, atomic $set (which this field
     * does not interact with — an update() bypasses Spring Data's save()-based optimistic-lock
     * check entirely, by design, the same way it does for Order), while a handful of remaining
     * plain positionRepo.save(position) calls exist for lower-risk, less contended fields. This
     * field is the backstop for exactly those remaining save() calls: two concurrent processes
     * each holding their own stale copy of the same Position and both calling save() will have
     * the second one fail loudly with OptimisticLockingFailureException instead of silently
     * winning with stale data.
     */
    @Version private Long version;

    @Indexed private String userId;
    @Indexed private String credentialId;
    private BrokerType broker;
    private BrokerMode mode;

    private String symbol;
    private BigDecimal quantity;          // filled quantity, updated on partial fills; ZERO once the position reaches any terminal status
    /** Preserves what the position's quantity WAS at the moment it closed, since `quantity`
     *  itself is zeroed on close so downstream code that filters/sums by "quantity > 0" can't
     *  misinterpret a closed position as still open. */
    private BigDecimal closedQuantity;
    /**
     * The amount this application genuinely cannot account for as a confirmed sale by its own
     * flatten attempt(s) -- distinct from closedQuantity (which reflects only what a real,
     * confirmed exchange fill actually sold). When an emergency flatten's own balance check
     * finds free+locked already at zero, that observation alone does not prove this specific
     * sell attempt sold the remaining quantity -- it may simply already be gone (an
     * already-liquidated or manually-closed position). Set alongside closedQuantity, not
     * instead of it, so the two together always sum to the position's own original quantity
     * without asserting more certainty about the unaccounted remainder than the evidence
     * actually supports.
     */
    private BigDecimal unverifiedClosedQuantity;
    // BinanceBrokerAdapter's own placeExitOco rounds DOWN to the symbol's own step size before
    // placing the OCO (the exchange rejects a quantity that doesn't exactly match a step-size
    // multiple), which can genuinely differ from quantity (this position's own real, full size
    // after entry fees) -- e.g. quantity=10.999, step=1 -> the OCO only protects 10, leaving
    // 0.999 with no stop-loss or take-profit at all. This field is what lets the application
    // ask "is this position fully protected?" rather than only "does it have an OCO at all?"
    // Set from OcoOrderResult's own actualProtectedQuantity whenever an OCO is successfully
    // placed (entry, resize, late-fill, remainder -- every real placement site in this
    // codebase). Null means either no OCO has been placed yet, or this position predates this
    // field.
    private BigDecimal protectedQuantity;
    private BigDecimal avgEntryPrice;

    /**
     * This position's own exact exposure reservation id (see ExposureReservationRecord), set
     * at entry time from ExposureReserveResult.reservationId(). Closing this position releases
     * exactly this reservation (via ExposureReservationService.release(String)) rather than
     * recomputing an amount from current quantity*avgEntryPrice, which can legitimately differ
     * from what was actually reserved at entry time. Null for positions opened before this
     * field existed, or where no exposure cap was configured at entry time -- callers fall back
     * to the narrower, amount-based release(credentialId, symbol, amount) overload in that case.
     */
    private String exposureReservationId;

    /**
     * This position's own exact slot reservation id (see PositionSlotReservationRecord), set
     * at entry time. Closing this position releases exactly this reservation (via
     * PositionSlotReservationService.release(String reservationId)) rather than a bare
     * key-based decrement with no ownership check. Null for positions opened before this field
     * existed -- callers fall back to the narrower releaseByKey(credentialId) overload in that
     * case.
     */
    private String slotReservationId;
    /** The plan-level tier's own reservation id, when this position's own signal was
     *  attributed to a StrategyPlan with its own maxConcurrentTrades cap -- see
     *  slotReservationId's own field javadoc for the same reasoning, applied to the second tier. */
    private String planSlotReservationId;

    // Guards against a recovery bug creating two Position documents for the same underlying
    // broker entry order. sparse, same reasoning as Order's own identical fields: historical/
    // edge-case Position documents with no entryOrderId recorded at all (null) don't collide
    // with each other under a unique constraint -- only a genuine duplicate non-null
    // entryOrderId is rejected. Actual index creation happens in IndexInitializer (the
    // annotation alone does nothing by itself). No longer unique on its own -- see this
    // class's own @CompoundIndex above for the real, scoped uniqueness constraint; this index
    // is kept plain for lookup performance.
    @Indexed
    private String entryOrderId;          // brokerOrderId of the entry order
    private String entryClientOrderId;    // idempotency key used on entry
    private String ocoOrderListId;        // protective SL/TP order list, if placed
    private String signalId;
    /** Which StrategyPlan opened this position -- null for a position with no associated plan (pre-multi-plan, or manually triggered). See TradeCallRecord.planId's own field javadoc. */
    private String planId;
    private String triggerSource;         // MANUAL or SIGNAL

    /**
     * fillLedgerService.recordFills() is called before positionRepo.save() on every path, but
     * never throws by design (an additive, non-fatal side-channel — see FillLedgerService's
     * own javadoc), so this Position is always created regardless of whether the ledger write
     * actually succeeded. Blocking real money flow over a ledger hiccup would defeat the
     * ledger's own "additive, non-fatal" design, and the ledger isn't authoritative yet (see
     * PositionLedgerService's own javadoc) — so instead, this flag makes the gap visible rather
     * than silent: set when a real fill happened but the ledger ended up with fewer records
     * than expected, so an operator or the ops status endpoint can surface "this position's
     * ledger history may be incomplete" without changing execution behavior. If the ledger
     * becomes authoritative later, this is the signal that would be promoted into a real
     * halt/RECONCILIATION_REQUIRED.
     */
    private boolean ledgerRecordingIncomplete;

    /**
     * OPEN, FLATTENING, CLOSED, CLOSED_UNVERIFIED_PNL, NAKED_FLATTENED (entry filled, protection
     * failed, emergency-closed).
     *
     * FLATTENING is a persistent, database-durable marker, distinct from the distributed
     * flatten lock (see DistributedLockService), which is inherently ephemeral and does not
     * survive a process crash, JVM restart, or container replacement. If this application dies
     * mid-flatten (after FLATTENING is set atomically, before the real sell's outcome is
     * recorded), a restart can find this exact position via a targeted query
     * (PositionMonitorService.recoverStuckFlattening's own javadoc has the full recovery
     * design) and resolve it by asking the exchange directly what happened, rather than the
     * crash leaving the position in a state no code path is watching for. FLATTENING is set
     * atomically (WHERE status="OPEN") at the very start of
     * PositionSafetyService.attemptFlatten's own first attempt -- the same query condition
     * doubling as an extra, database-level guard against a second concurrent flatten attempt,
     * on top of the distributed lock, not a replacement for it.
     */
    private String status = "OPEN";

    /**
     * Increments exactly once per flatten episode (every fresh
     * PositionSafetyService.attemptFlatten(attempt=0) call, i.e. every top-level
     * emergencyFlatten or exitPosition invocation -- not every retry attempt, since attempt=1
     * is still the same episode continuing), persisted atomically together with the
     * OPEN-&gt;FLATTENING transition so it survives a crash exactly like status itself does.
     *
     * This position can legitimately return to OPEN after a flatten attempt
     * (PositionMonitorService.recoverStuckFlattening's own "STUCK_FLATTENING_RECOVERED_PARTIAL"
     * path sets status back to OPEN with the remaining quantity when a partial fill is
     * confirmed but not fully resolved), and a subsequent protection failure or trigger can
     * then start a genuinely new flatten episode on this same position id. The clientOrderId
     * basis for a flatten attempt is positionId + flattenEpisode + attempt, so two separate
     * episodes on the same position can never collide on the same clientOrderId, while a
     * crash-and-resume within one episode (attempt 0 then a retry at attempt 1) still
     * reproduces the exact same, already-durable episode number.
     * PositionMonitorService.recoverStuckFlattening's own last-resort blind re-derivation (used
     * only when neither the OMS Order nor the FlattenAttempt record survived) reads this field
     * directly off the stuck position it already has in hand -- no guessing required, since the
     * position's own currently-stamped episode is exactly the one attemptFlatten used.
     */
    private int flattenEpisode = 0;

    private BigDecimal exitPrice;
    private BigDecimal realizedPnlQuote;
    private String closeReason;           // TAKE_PROFIT, STOP_LOSS, EMERGENCY_FLATTEN, MANUAL_CLOSE

    /** Real commission from the broker's fill data, in quote-asset terms when the fee was
     *  actually paid in the quote asset. Null means "fee unknown/not netted" — e.g. paid in BNB
     *  via the fee-discount program, which this doesn't convert (a documented gap, not a
     *  silently wrong number). realizedPnlQuote is net of these when both are known. */
    private BigDecimal entryFeeQuote;
    private BigDecimal exitFeeQuote;

    /**
     * realizedPnlQuote nets whatever fees are known, but displayed on its own gives no signal
     * that a fee might be silently missing (e.g. paid in BNB, per this class's own
     * entryFeeQuote javadoc) -- this is that signal, computed rather than stored so it can
     * never go stale against the two fields it actually reads (a stored, separately-updated
     * status field would risk being forgotten at one of the many call sites across this
     * codebase that set these fees).
     *
     * COMPLETE: both fees are known (or the position hasn't exited yet, so exitFeeQuote isn't
     *   applicable -- entryFeeQuote known is as complete as an open position can be).
     * PARTIAL: the position has exited, and exactly one of the two fees is known.
     * UNKNOWN: neither fee is known.
     */
    public enum FeeStatus { COMPLETE, PARTIAL, UNKNOWN }

    public FeeStatus getFeeStatus() {
        boolean entryKnown = entryFeeQuote != null;
        // "DUST_REMAINING" is a closeReason on this class (see PositionMonitorService's own
        // dust-handling), not a status value -- the complete set of status strings this
        // codebase ever sets is CLOSED, CLOSED_UNVERIFIED_PNL, NAKED_FLATTENED, and OPEN.
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
     * Set true when a quantity correction had to proceed without being able to independently
     * verify the average entry price (a fills lookup failed) — the quantity is real (confirmed
     * via order status), but avgEntryPrice may still be stale. Downstream P&L/risk/ML numbers
     * for this position should be treated as provisional until this is cleared by a successful
     * reconciliation or manual review. Distinguishes "reconciliation was attempted" from
     * "reconciliation succeeded."
     */
    private boolean avgEntryPriceUnverified = false;

    private LocalDateTime openedAt = LocalDateTime.now();
    private LocalDateTime closedAt;
    /**
     * Entry-to-first-protection latency, tracked at the Position level (rather than on Order,
     * the OMS entity, which only tracks the entry path -- OCO/exit remain outside OMS) so it
     * works for both OMS-tracked and non-OMS-tracked flows alike. Set once, via
     * recordOcoPlaced() below — a resize or recovery re-placement later in this position's life
     * does not overwrite it, so this always measures the first protection, not whichever
     * placement happened most recently.
     *
     * SCOPE: this is entry-to-first-protection only. Exit fill, cancel request/confirm, and
     * emergency-flatten timing remain unmeasured, since those would need the OCO/exit paths to
     * be migrated into OMS as well.
     */
    private LocalDateTime ocoPlacedAt;

    /** See ocoPlacedAt's own javadoc for why this guards against overwriting the first value. */
    public void recordOcoPlaced() {
        if (this.ocoPlacedAt == null) this.ocoPlacedAt = LocalDateTime.now();
    }
}
