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
 * The formal order lifecycle, the authoritative record of "what is happening with this order
 * right now" — see OrderService (the OMS itself) for the state-transition logic that owns
 * writes to this record; nothing else should mutate status directly.
 *
 * SCOPE: this coexists with ExecutedOrder, it does not replace it. ExecutedOrder remains the
 * record every existing OCO/resize/emergency-flatten path reads and writes — migrating those
 * to be fully OMS-driven is further work not taken on for every path, only the highest-value
 * one: entry order placement, wired in AutoTradeService. See OrderService's own javadoc for
 * exactly which paths are and aren't migrated.
 */
// brokerOrderId is unique only per (account, symbol) in the real world, not globally --
// Binance hands out order ids independently per account, so credential A's order 12345 on
// BTCUSDT and credential B's order 12345 on ETHUSDT are two unrelated real orders that happen
// to share a number. Scoped to {credentialId, symbol, brokerOrderId} -- the tuple that's
// actually unique -- rather than the bare id alone. mode is deliberately NOT part of this key:
// BrokerCredential.mode is a fixed 1:1 field per credential document (a credential is either
// TESTNET or LIVE for its whole lifetime, never both), so credentialId alone already implies
// mode.
//
// This is a PARTIAL index (partialFilter), not merely sparse: a sparse index on a COMPOUND key
// includes a document if it has ANY of the indexed fields, not only if it has ALL of them.
// credentialId and symbol are set on every Order from creation; only brokerOrderId is
// legitimately absent until the broker confirms the order -- so a plain sparse compound index
// would NOT exclude brokerOrderId=null documents from the uniqueness check, and every order for
// a given credential+symbol still brokerOrderId=null (freshly created, or rejected before the
// broker assigned an id) would collide on the same {credentialId, symbol, null} index key. The
// partialFilter only includes a document in the index once brokerOrderId actually holds a
// string value, matching the intended "unique only once known" semantics. See this class's own
// brokerOrderId field comment for why plain sparse (not compound) remains correct there.
//
// Existing deployments: a partial index with the same `name` as an older unique+sparse one is
// not automatically replaced by Spring Data's ensureIndex -- see IndexInitializer's own comment
// for the required migration (explicit dropIndex of the old definition first).
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
     * Spring Data MongoDB's standard optimistic-locking field -- any repository.save() against
     * a document loaded with an older version fails with OptimisticLockingFailureException
     * instead of silently overwriting whatever changed in the meantime. The field-level $set
     * updates this OMS uses everywhere it can are unaffected (they never read-then-save a
     * whole document, so there is no version to compare); this field is the backstop for the
     * remaining whole-document save() paths -- create(), a brand new document with no prior
     * version to conflict with, and any future code that adds another whole-document save().
     */
    @Version private Long version;

    // clientOrderId is always set at create() time (required as a parameter), never null, so
    // a plain unique index is correct and safe here -- no sparse needed since there's never a
    // null value to worry about colliding.
    @Indexed(unique = true) private String clientOrderId;
    // brokerOrderId is genuinely different from clientOrderId -- it's only known once the
    // broker actually confirms the order (recordBrokerResult(), well after create()), so
    // multiple Order documents legitimately have brokerOrderId=null simultaneously (freshly
    // created, not yet submitted or acknowledged). sparse=true excludes documents missing the
    // field from the uniqueness check entirely, the same pattern used for
    // User.email/User.mobile (also optional, unique-when-present fields). The real, scoped
    // uniqueness constraint lives on the compound index above (brokerOrderId is not unique on
    // its own); this index is kept non-unique purely for lookup performance on queries that
    // filter by this field alongside credentialId/symbol.
    @Indexed private String brokerOrderId;

    @Indexed private String userId;
    private String credentialId;
    private String positionId;
    /** The TradeCallRecord id that caused this order — same convention as ExecutedOrder.signalId. */
    private String signalId;
    /** Which StrategyPlan authorized this order -- null for an order with no associated plan. See TradeCallRecord.planId's own field javadoc. */
    private String planId;
    /**
     * Which specific execution claim (see RiskProfileService.ExecutionClaim) authorized this
     * exact order, recorded durably on the order itself rather than only living in-memory for
     * the duration of one evaluateSignal() call. Together with signalId above, this lets
     * recovery/audit answer directly from this document alone: "this exact signal evaluation
     * produced this exact execution authorization which produced this exact exchange order."
     * Null for orders not created through the claim-gated LIVE/TESTNET auto-trade path (e.g.
     * manual flatten, reconciliation-recovery placements), which have no claim to record.
     */
    private String executionClaimId;
    /** Reserved for a future formal risk-decision audit record — not built in this pass, kept null. */
    private String decisionId;

    private String symbol;
    private String side;   // BUY / SELL
    private String type;   // MARKET (only type this codebase places)

    private BigDecimal requestedQuantity;
    // The slippage REFERENCE price (the signal's expected entry) rather than a literal limit
    // price -- this codebase only places MARKET orders, which have no real limit price, so
    // "requested" here means "what we expected to get", not "what we told the broker to fill
    // at exactly".
    private BigDecimal requestedPrice;
    private BigDecimal filledQuantity = BigDecimal.ZERO;
    private BigDecimal remainingQuantity;
    private BigDecimal averageFillPrice;

    private OrderStatus status = OrderStatus.CREATED;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime riskAcceptedAt;
    private LocalDateTime submitStartedAt;
    /**
     * A second, independent timestamp, set only immediately before adapter.placeOrder() is
     * actually invoked (see AutoTradeService's own call site), distinct from the order simply
     * entering SUBMITTING status. An order sits in SUBMITTING for the entire window from
     * markSubmitting() through the real exchange call -- spanning risk re-checks, atomic
     * plan/account claims, and exposure reservations, none of which touch the exchange -- so
     * null here means this order provably never reached the point of making a real network
     * call to the exchange, letting recovery (OrderService.recoverStuckSubmittingOrders) treat
     * that very differently from a stuck order where this field is set. A pure field stamp,
     * not a new OrderStatus value or a formal state transition -- the order's own status stays
     * SUBMITTING throughout this whole window; this field records a strictly narrower fact
     * within it.
     */
    private LocalDateTime exchangeCallStartedAt;
    private LocalDateTime brokerAckAt;
    private LocalDateTime firstFillAt;
    private LocalDateTime filledAt;
    // Set via OrderService.recordProtectionPlaced, called additively from AutoTradeService's
    // OCO success path once protection (the TP/SL OCO) is placed after fill.
    private LocalDateTime protectionPlacedAt;

    // ATR as a percentage of price at the moment this signal was generated -- a normalized
    // volatility measure comparable across symbols. Set via OrderService.recordVolatility,
    // called additively from AutoTradeService right after the order is created.
    private Double volatilityAtEntry;

    private LocalDateTime cancelRequestedAt;
    private LocalDateTime cancelledAt;

    private String failureCode;
    private String failureReason;

    // Populated per order: see OrderService.STRATEGY_VERSION's own javadoc for strategyVersion,
    // and AutoTradeService's own entry-path stamping for riskProfileVersion.
    private String strategyVersion;
    private String riskProfileVersion;

    // A single OCO Order record carries the actual TP/SL prices as their own fields, rather
    // than only requestedPrice=takeProfit (see this class's own requestedPrice field comment
    // for that narrower meaning) with the stop leg's prices nowhere in the OMS record at all.
    // SCOPE: this does not split an OCO into two separate child Order records (one for the TP
    // leg, one for the SL leg) -- that would mean recordOcoPlacementResult/
    // recordOcoCancelResult/recordOcoFillResult all managing two records instead of one, a
    // larger restructuring of this class's own OCO tracking. orderRole distinguishes this
    // record's own purpose ("ENTRY" or "OCO_EXIT") for a reader who doesn't want to infer it
    // from side/type alone.
    private String orderRole; // "ENTRY" or "OCO_EXIT" -- null for pre-existing records written before this field existed
    private java.math.BigDecimal takeProfitPrice;
    private java.math.BigDecimal stopLossTriggerPrice;
    private java.math.BigDecimal stopLossLimitPrice;
    /**
     * app.trading.stop-loss-limit-gap-percent is read live via @Value at every OCO placement
     * site, so a config change made while a position is already open would otherwise make its
     * later resize/late-fill/remainder OCO re-placements use a gap different from the one the
     * position's own original entry OCO was placed under. Stamped onto the ENTRY order record
     * (alongside stopLossTriggerPrice/takeProfitPrice, which this exact value derived
     * stopLossLimitPrice from at placement time) so every later re-placement for this position
     * can read back and reuse the same gap its protection has always used, rather than whatever
     * the live config happens to say at resize time. Null for pre-existing records written
     * before this field existed -- every read site falls back to the live config value in that
     * case.
     */
    private java.math.BigDecimal stopLossLimitGapPercent;

    // Fields ExecutedOrder also carries, added here additively so every remaining
    // ExecutedOrder reader/writer can be migrated to this class one at a time. Deliberately
    // not duplicating fillPrice (this class's own averageFillPrice already covers it -- this
    // codebase only ever places MARKET orders, so a single order's fill price and average
    // fill price are the same value in practice) or stopLossPrice/takeProfitPrice a second
    // time (this class's own stopLossTriggerPrice/stopLossLimitPrice/takeProfitPrice above are
    // already the more detailed, superseding version of ExecutedOrder's simpler pair).
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
