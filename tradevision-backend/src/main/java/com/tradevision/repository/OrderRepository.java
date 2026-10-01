package com.tradevision.repository;

import com.tradevision.model.Order;
import com.tradevision.model.OrderStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends MongoRepository<Order, String> {
    Optional<Order> findByClientOrderId(String clientOrderId);
    /**
     * Review finding ("strategy-versioned execution provenance" -- external review, P3,
     * confirmed real by direct inspection before this fix: Order.strategyVersion is genuinely
     * populated on every order (see OrderService.STRATEGY_VERSION's own javadoc), but nothing
     * anywhere let an operator actually trace which specific orders a given strategy version
     * produced): the actual bounded query the provenance lookup needs.
     */
    List<Order> findByStrategyVersionOrderByCreatedAtDesc(String strategyVersion, org.springframework.data.domain.Pageable pageable);
    // Review finding ("OMS not actually authoritative" -- P0, full context in OrderService's own
    // top-level javadoc): needed to look up an OCO's own OMS Order record (by the broker's own
    // ocoOrderListId, stored as brokerOrderId — see recordOcoPlacementResult's own javadoc) at
    // cancel time, since the caller only has the position's ocoOrderListId at that point, not
    // this record's own generated id.
    Optional<Order> findByBrokerOrderId(String brokerOrderId);
    // P0-5 fix ("Global unique indexes on exchange order IDs collide across
    // symbols/credentials/testnet" -- full context in Order's own @CompoundIndex javadoc): the
    // real, scoped lookup that matches the new compound unique index -- brokerOrderId alone is
    // only unique per (credentialId, symbol) in the real world, so every call site that used to
    // look a record up by brokerOrderId alone risked matching the WRONG order (a different
    // credential or symbol's order that happens to share the same broker-issued id number).
    // findByBrokerOrderId above is kept only for any caller that genuinely cannot supply
    // credentialId/symbol -- new and migrated call sites use this one.
    Optional<Order> findByCredentialIdAndSymbolAndBrokerOrderId(String credentialId, String symbol, String brokerOrderId);
    List<Order> findByStatus(OrderStatus status);
    // Review finding ("Symbol list bounds" / unbounded metrics queries -- P2, confirmed real
    // while investigating "position protection status" separately): LatencyMetricsService and
    // SlippageMetricsService both used to call findByStatus(FILLED) above -- EVERY filled order
    // ever placed, across every user, then filter down to a lookback window in memory. Currently
    // unreachable (neither service is called from any controller or scheduled job in this
    // codebase, confirmed by search), so the practical risk is latent, not active -- but the
    // query itself is real and would become a genuine, unbounded cost the moment either service
    // is wired into a live endpoint. Pushes the same cutoff both services already compute down
    // into the database query itself, rather than fetching everything and filtering after.
    List<Order> findByStatusAndCreatedAtAfter(OrderStatus status, java.time.LocalDateTime cutoff);
    List<Order> findByUserIdAndStatus(String userId, OrderStatus status);
    // Backs OrderService's own stuck-UNKNOWN sweep, matching the same
    // "recover, don't silently retry forever" pattern established for auto-trade signal recovery.
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, java.time.LocalDateTime cutoff);
    // Review finding ("Other important remaining production work" — "order-frequency limits"):
    // backs RiskEngineService's new rolling-window entry-order-frequency check.
    long countByCredentialIdAndCreatedAtAfter(String credentialId, java.time.LocalDateTime cutoff);
    // Review finding ("Resume does not prove existing positions are protected" -- P0, full
    // context in RiskProfileService.resume's own updated javadoc): needed to check for
    // unresolved UNKNOWN/RECONCILIATION_REQUIRED orders on this specific credential before
    // allowing a resume.
    List<Order> findByCredentialIdAndStatusIn(String credentialId, List<OrderStatus> statuses);
    // Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in
    // TradeCallService's own updated getStats method): needed to migrate that read off
    // ExecutedOrderRepository's own identical method.
    List<Order> findBySignalIdIn(List<String> signalIds);
    // Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in
    // TradeCallService.updateResult's own updated safety-gate comment): the real-order
    // existence check that blocks a client from overwriting a genuinely auto-traded signal's
    // outcome, migrated off ExecutedOrderRepository's own identical method.
    boolean existsBySignalId(String signalId);
    // Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in
    // PositionMonitorService.reconcileEntryOrders's own updated javadoc): the pending-entry
    // reconciliation query, migrated off ExecutedOrderRepository's own identical method.
    // ExecutedOrder's own raw broker status strings "NEW"/"PARTIALLY_FILLED" map to this OMS's
    // own ACKNOWLEDGED/PARTIALLY_FILLED states -- the same real-world "submitted but not yet
    // fully filled" condition, in this codebase's own formal state machine instead of a raw
    // broker string. Sorts by createdAt, not ExecutedOrder's own "placedAt" -- Order has no
    // placedAt field at all, and an initial version of this migration used that name directly,
    // which would have failed at application STARTUP (Spring Data validates a derived query
    // method's field references against the real entity when the repository bean is created),
    // not merely at test time. Caught and fixed before it could reach that point.
    List<Order> findByCredentialIdAndStatusInOrderByCreatedAtAsc(String credentialId, List<OrderStatus> statuses);
    /**
     * Diagnosed live, real bug (session continuation -- confirmed via production logs: a
     * FILLED manual test order with genuinely no Position ever created for it, surviving
     * repeated "Reconcile now" clicks and scheduled reconciliation passes): reconcileEntryOrders'
     * own pendingEntries query above only ever fetches ACKNOWLEDGED/PARTIALLY_FILLED orders --
     * a market order that fills SYNCHRONOUSLY at placement (the normal case for a Binance
     * MARKET order, whose own place-order response can already show status=FILLED) has its
     * Order.status set to FILLED immediately, before this reconciliation loop ever runs for it
     * -- so it can never appear in that query's result set, meaning createPositionForLateDiscoveredFill
     * (built specifically to catch "order FILLED, no Position") could never actually run for the
     * single most common real-world case. This is the query that closes that gap: recently-FILLED
     * orders, bounded by createdAt so this never re-scans this credential's entire order history
     * forever on every single reconciliation pass -- an order still missing a Position after this
     * window has closed needs a human, not an indefinite hot-path retry.
     */
    List<Order> findByCredentialIdAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(String credentialId, OrderStatus status, java.time.LocalDateTime cutoff);
    /**
     * P0 fix (production incident: reconcileEntryOrders' own recently-FILLED query above had no
     * side filter at all, so a genuine SELL order -- placed by emergency/naked flatten to close
     * a position -- was later rediscovered by that same query as an unresolved "entry," and
     * createPositionForLateDiscoveredFill hardcoded BUY regardless of the real side, creating a
     * new phantom Position from what was actually an exit. That phantom position's own
     * emergency-flatten then produced ANOTHER real SELL, rediscovered the same way on the next
     * reconciliation pass -- a self-sustaining infinite loop, confirmed in production via a
     * chained sequence of real Binance TESTNET order IDs, each one's own flatten SELL becoming
     * the next phantom position's "entry order ID." The actual fix: this query enforces side ==
     * "BUY" at the database level, per the explicit requirement not to rely only on
     * post-query filtering in the caller -- a SELL order can never be returned by this query at
     * all, regardless of what any calling code does or doesn't check afterward. See
     * createPositionForLateDiscoveredFill's own second, independent defensive guard for the
     * belt-and-suspenders check on the caller side.
     */
    List<Order> findByCredentialIdAndSideAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
        String credentialId, String side, OrderStatus status, java.time.LocalDateTime cutoff);
    // Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in
    // PositionDashboardService.listPositions's own updated javadoc): the batch entry-order
    // lookup this N+1-fix method uses, migrated off ExecutedOrderRepository's own identical
    // method.
    List<Order> findByBrokerOrderIdIn(List<String> brokerOrderIds);
    // P0-5 fix: the scoped equivalent of the batch lookup above, for the one caller
    // (PositionDashboardService.listPositions) that already filters its positions down to a
    // single credentialId before doing this batch lookup -- narrows the match space from "any
    // credential, any symbol" down to "this credential, any symbol" so a same-numbered order id
    // belonging to a DIFFERENT credential can never be matched in. Still not narrowed to a single
    // symbol (that caller's positions legitimately span multiple symbols within one credential),
    // so the caller itself keys its lookup map by symbol+brokerOrderId, not brokerOrderId alone.
    List<Order> findByCredentialIdAndBrokerOrderIdIn(String credentialId, List<String> brokerOrderIds);
    // Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in
    // OrderExecutionService.history's own updated javadoc): the user-facing order-history
    // endpoint's own paginated query, migrated off ExecutedOrderRepository's own identical
    // method.
    List<Order> findByUserId(String userId, org.springframework.data.domain.Pageable pageable);
    // Review finding ("Stuck FLATTENING recovery uses balance as evidence that the flatten
    // succeeded" -- external review, third pass, full context in
    // PositionMonitorService.recoverStuckFlattening's own updated javadoc): lets recovery find
    // this specific position's own most recent flatten attempt (there can be more than one --
    // attemptFlatten's own retry), to query the exchange for that exact order's own real,
    // definitive status by clientOrderId, rather than relying solely on account balance.
    List<Order> findByPositionIdAndOrderRoleOrderByCreatedAtDesc(String positionId, String orderRole);
}
