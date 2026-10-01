package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Review finding ("#4 — OMS", the recommended starting point of the autonomous-engine work):
 * the formal order lifecycle. This is the authoritative record of "what is happening with this
 * order right now" — see OrderService (the OMS itself) for the state-transition logic that owns
 * writes to this record; nothing else should mutate status directly.
 *
 * HONEST SCOPE: this coexists with ExecutedOrder in this pass, it does not replace it.
 * ExecutedOrder remains the record every existing OCO/resize/emergency-flatten path already
 * reads and writes — migrating all of those to be OMS-driven, as the review's own design
 * requires ("OMS should own order state — not PositionSafetyService"), is real further work
 * this pass doesn't take on for every path, only the highest-value one: entry order placement,
 * wired in AutoTradeService. See OrderService's own javadoc for the same disclosure in more
 * detail, including exactly which paths are and aren't migrated yet.
 */
// P0-5 fix (external review: "Global unique indexes on exchange order IDs collide across
// symbols/credentials/testnet -> a real fill with no Position" -- confirmed real by direct
// inspection: the single-field unique index on brokerOrderId below treats Binance's own
// per-(account,symbol) order-id numbering as if it were globally unique across every
// credential and every symbol this application ever trades. It is not -- Binance hands out
// order ids independently per account, so credential A's order 12345 on BTCUSDT and
// credential B's order 12345 on ETHUSDT are two completely unrelated real orders that happen
// to share a number, and the old index would have rejected the second one as a duplicate of
// the first, silently losing a real fill. Scoped to {credentialId, symbol, brokerOrderId} --
// exactly the tuple that's actually unique in the real world -- rather than the bare id alone.
// mode is deliberately NOT part of this key: BrokerCredential.mode is a fixed 1:1 field per
// credential document (a credential is either TESTNET or LIVE for its whole lifetime, never
// both), so credentialId alone already implies mode with no additional field needed.
// P3-11 fix ("The new order-ID index will block trading on a symbol after one rejected order"
// -- external review, second pass, re-audit, confirmed real by direct inspection of MongoDB's
// own documented sparse-index semantics, not just taken on faith: a SPARSE index on a COMPOUND
// key includes a document if it has ANY of the indexed fields, not only if it has ALL of them.
// credentialId and symbol are set on every Order from creation; only brokerOrderId is legitimately
// absent until the broker confirms the order. That means sparse=true here did NOT exclude
// brokerOrderId=null documents from the uniqueness check the way the P0-5 fix's own comment above
// assumed (a mistake specific to compound indexes -- sparse behaves as expected on a SINGLE-field
// index, which is why clientOrderId/User.email/User.mobile above are unaffected) -- it only
// excludes a document missing credentialId or symbol too, which never happens. So every order for
// a given credential+symbol that is still brokerOrderId=null (freshly created, or rejected before
// the broker ever assigned an id) collided on the SAME {credentialId, symbol, null} index key, and
// the second such order for that credential+symbol -- ever -- would fail as a duplicate key. On
// LIVE this meant one rejected order permanently broke OMS setup, aborting the trade and raising a
// CRITICAL incident on every subsequent signal for that symbol.
//
// Fixed with a PARTIAL index instead: partialFilter only includes a document in the index at all
// when brokerOrderId actually holds a string value, which is the exact "unique only once known"
// semantics this constraint always meant. See this class's own updated brokerOrderId field
// comment for why plain sparse (not compound) remains correct there.
//
// Existing deployments: a partial index with the same `name` as the old unique+sparse one is NOT
// automatically replaced by Spring Data's ensureIndex -- see IndexInitializer's own updated
// comment for the required migration (explicit dropIndex of the old definition first).
@CompoundIndexes({
    @CompoundIndex(name = "credential_symbol_brokerOrderId_unique",
        def = "{'credentialId': 1, 'symbol': 1, 'brokerOrderId': 1}", unique = true,
        partialFilter = "{'brokerOrderId': {'$type': 'string'}}")
})
@Data @NoArgsConstructor
@Document(collection = "orders")
public class Order {
    @Id private String id;

    /**
     * Review finding (P1 #12 -- "Order state written by whole-document save() with no
     * optimistic locking"): Spring Data MongoDB's standard optimistic-locking field -- any
     * repository.save() against a document loaded with an older version now fails with
     * OptimisticLockingFailureException instead of silently overwriting whatever changed in the
     * meantime, exactly the defense-in-depth the review asked for. The two real "lost update"
     * mechanisms this pass actually found and fixed -- state transitions bypassing
     * assertLegal/atomicUpdate, and purely-additive metadata methods doing a whole-document save
     * from a stale snapshot -- are fixed directly (see OrderService.atomicUpdate/fieldUpdate's
     * own javadoc, and reconcileStatusFromBroker for the one remaining direct-save bypass this
     * review named in PositionMonitorService). This field is the backstop for any save() path
     * not already covered by one of those: the field-level $set updates this OMS now uses
     * everywhere it can are unaffected by this (they never read-then-save a whole document, so
     * there is no version to compare), but the one remaining real save() -- create(), a brand
     * new document with no prior version to conflict with -- and any future code that adds
     * another whole-document save() are now protected by MongoDB's own optimistic lock rather
     * than relying on every future author remembering this class's own established
     * field-update-not-whole-save discipline.
     */
    @Version private Long version;

    // Review finding ("Order.clientOrderId needs a unique DB constraint" -- P0): confirmed real
    // and fixed. clientOrderId is always set at create() time now (never null, confirmed by
    // OrderService.create()'s own signature requiring it as a parameter after this session's
    // clientOrderId-consistency fix) -- a plain unique index is correct and safe here, no
    // sparse needed since there's never a null value to worry about colliding.
    @Indexed(unique = true) private String clientOrderId;
    // Review finding ("Same principle for brokerOrderId, once known, with careful handling of
    // nulls" -- same P0 item): brokerOrderId is genuinely different from clientOrderId -- it's
    // only known once the broker actually confirms the order (recordBrokerResult(), well after
    // create()), so multiple Order documents legitimately have brokerOrderId=null
    // simultaneously (freshly created, not yet submitted or acknowledged). unique=true alone
    // would reject every SECOND such order as a duplicate null -- sparse=true is what excludes
    // documents missing the field from the uniqueness check entirely, the same pattern already
    // established in this codebase for User.email/User.mobile (also optional, unique-when-present
    // fields) -- checked directly rather than guessed at the right annotation combination.
    // P0-5 fix: no longer unique on its own -- see this class's own @CompoundIndex above, which
    // is the real, scoped uniqueness constraint now. Kept as a plain (non-unique) index purely
    // for lookup performance on the many existing single-field queries that still filter by this
    // field alongside credentialId/symbol.
    @Indexed private String brokerOrderId;

    @Indexed private String userId;
    private String credentialId;
    private String positionId;
    /** The TradeCallRecord id that caused this order — same convention as ExecutedOrder.signalId. */
    private String signalId;
    /** Which StrategyPlan authorized this order -- null for an order with no associated plan. See TradeCallRecord.planId's own field javadoc. */
    private String planId;
    /**
     * Review finding ("Execution-in-flight counter is useful, but it isn't tied to a claim" /
     * "Auto-trade evaluator lease and execution claim should be tied together" -- external
     * review, fourth pass, P1): the actual correlation the review asks for -- which specific
     * execution claim (see RiskProfileService.ExecutionClaim) authorized this exact order,
     * recorded durably on the order itself rather than only living in-memory for the duration of
     * one evaluateSignal() call. Together with signalId above, this lets recovery/audit answer
     * the review's own named question directly from this document alone: "this exact signal
     * evaluation produced this exact execution authorization which produced this exact exchange
     * order." Null for orders not created through the claim-gated LIVE/TESTNET auto-trade path
     * (e.g. manual flatten, reconciliation-recovery placements), which have no claim to record.
     */
    private String executionClaimId;
    /** Reserved for a future formal risk-decision audit record — not built in this pass, kept null. */
    private String decisionId;

    private String symbol;
    private String side;   // BUY / SELL
    private String type;   // MARKET (only type this codebase places)

    private BigDecimal requestedQuantity;
    // Review finding ("#9 — Slippage"): repurposed as the slippage REFERENCE price (the
    // signal's expected entry) rather than a literal limit price — this codebase only places
    // MARKET orders, which have no real limit price, so "requested" here means "what we
    // expected to get", not "what we told the broker to fill at exactly".
    private BigDecimal requestedPrice;
    private BigDecimal filledQuantity = BigDecimal.ZERO;
    private BigDecimal remainingQuantity;
    private BigDecimal averageFillPrice;

    private OrderStatus status = OrderStatus.CREATED;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime riskAcceptedAt;
    private LocalDateTime submitStartedAt;
    /**
     * Review finding ("Recovery after exchange submission still needs a stronger state
     * boundary" -- external review, twentieth pass, P1, confirmed real by direct inspection: an
     * order sits in SUBMITTING for the ENTIRE window from markSubmitting() through the real
     * exchange call -- roughly 200 lines in AutoTradeService, spanning risk re-checks, atomic
     * plan/account claims, and exposure reservations, none of which touch the exchange at all.
     * A crash anywhere in that window left recoverStuckSubmittingOrders() with no way to tell
     * "this order never got anywhere near Binance" from "the real call may have been sent and
     * we simply never got the response" -- both looked identical: status=SUBMITTING, no further
     * information): the actual fix -- a second, independent timestamp, set ONLY immediately
     * before adapter.placeOrder() is actually invoked (see AutoTradeService's own call site for
     * exactly where), never before. Null means this order provably never reached the point of
     * making a real network call to the exchange -- safe to treat very differently in recovery
     * than a stuck order where this field is set (see OrderService.recoverStuckSubmittingOrders'
     * own updated javadoc for exactly how). A pure field stamp, not a new OrderStatus value or a
     * formal state transition -- the order's own status stays SUBMITTING throughout this whole
     * window, exactly as before; this field only records a strictly narrower fact within it.
     */
    private LocalDateTime exchangeCallStartedAt;
    private LocalDateTime brokerAckAt;
    private LocalDateTime firstFillAt;
    private LocalDateTime filledAt;
    // Review finding ("#9 — Execution Latency", agreed sequencing 4 -> 6 -> 5 -> 9 -> 7): the
    // fifth stage the review's own list names ("Fill -> Protection"). Set via
    // OrderService.recordProtectionPlaced, called additively from AutoTradeService's OCO
    // success path — see that call site's own comment for why this is safe to add without
    // touching the actual OCO placement logic itself.
    private LocalDateTime protectionPlacedAt;

    // Review finding ("Execution" — "slippage by volatility"): ATR as a percentage of price at
    // the moment this signal was generated — a normalized volatility measure comparable across
    // symbols. Set via OrderService.recordVolatility, called additively from AutoTradeService
    // right after the order is created.
    private Double volatilityAtEntry;

    private LocalDateTime cancelRequestedAt;
    private LocalDateTime cancelledAt;

    private String failureCode;
    private String failureReason;

    // Review finding ("Strategy/risk-profile/feature versioning fields exist but are
    // unused/null" -- P1, now closed): stale comment fixed -- these are genuinely populated now,
    // not reserved-but-null. See OrderService.STRATEGY_VERSION's own javadoc for strategyVersion,
    // and AutoTradeService's own entry-path stamping for riskProfileVersion.
    private String strategyVersion;
    private String riskProfileVersion;

    // Review finding ("OCO's OMS record doesn't store separate TP/SL prices, orderRole,
    // parentOrderId" -- P1): confirmed real and fixed, as far as this session can responsibly
    // take it without a larger, riskier restructuring. HONEST SCOPE, stated plainly: this does
    // NOT split an OCO into two separate child Order records (one for the TP leg, one for the
    // SL leg) the way the review's own language suggests -- that would mean recordOcoPlacementResult/
    // recordOcoCancelResult/recordOcoFillResult all needing to manage TWO records instead of one,
    // a genuinely larger restructuring of how this class's own OCO tracking works, not attempted
    // in this pass. What IS done: the single existing OCO Order record now carries the actual
    // TP/SL prices as their own fields, rather than only requestedPrice=takeProfit (see this
    // class's own requestedPrice field comment for that pre-existing limitation) with the stop
    // leg's own prices nowhere in the OMS record at all. orderRole distinguishes this record's
    // own purpose ("ENTRY" or "OCO_EXIT") for a reader who doesn't want to infer it from
    // side/type alone.
    private String orderRole; // "ENTRY" or "OCO_EXIT" -- null for pre-existing records written before this field existed
    private java.math.BigDecimal takeProfitPrice;
    private java.math.BigDecimal stopLossTriggerPrice;
    private java.math.BigDecimal stopLossLimitPrice;

    // Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in this class's
    // own "HONEST SCOPE" comment above, which this update supersedes): the fields ExecutedOrder
    // has that this class didn't -- added here additively so every remaining ExecutedOrder
    // reader/writer can be migrated to this class one at a time without first needing a place to
    // put the data it currently carries. Deliberately NOT duplicating fillPrice (this class's
    // own averageFillPrice already covers it -- this codebase only ever places MARKET orders,
    // so a single order's fill price and average fill price are the same value in practice) or
    // stopLossPrice/takeProfitPrice a second time (this class's own stopLossTriggerPrice/
    // stopLossLimitPrice/takeProfitPrice above are already the more detailed, superseding
    // version of ExecutedOrder's simpler pair).
    private BrokerType broker;
    private BrokerMode mode;
    /** Full broker JSON response, for audit -- same purpose as ExecutedOrder.rawResponse. */
    private String rawResponse;
    /** "MANUAL" (a human clicked the button) or "SIGNAL" (AutoTradeService) -- same as ExecutedOrder.triggerSource. */
    private String triggerSource = "MANUAL";
    /** Same purpose as ExecutedOrder.ocoOrderId -- kept as a separate field from this class's own
     *  orderRole="OCO_EXIT" mechanism, since that marks THIS record as being an OCO leg, while
     *  this field marks an ENTRY record's own linked, separate OCO order. */
    private String ocoOrderId;
}
