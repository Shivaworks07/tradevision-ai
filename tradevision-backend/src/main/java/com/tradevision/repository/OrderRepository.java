package com.tradevision.repository;

import com.tradevision.model.Order;
import com.tradevision.model.OrderStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends MongoRepository<Order, String> {
    Optional<Order> findByClientOrderId(String clientOrderId);
    /**
     * Bounded, paginated query letting an operator trace which specific orders a given
     * strategy version produced.
     */
    List<Order> findByStrategyVersionOrderByCreatedAtDesc(String strategyVersion, org.springframework.data.domain.Pageable pageable);
    // Looks up an OCO's OMS Order record by the broker's own ocoOrderListId (stored as
    // brokerOrderId) at cancel time, since the caller only has the position's
    // ocoOrderListId at that point, not this record's own generated id.
    Optional<Order> findByBrokerOrderId(String brokerOrderId);
    // The scoped lookup matching the compound unique index on (credentialId, symbol,
    // brokerOrderId) — brokerOrderId alone is only unique per credential and symbol, so a
    // lookup by brokerOrderId alone risks matching a different credential or symbol's order
    // that happens to share the same broker-issued id. findByBrokerOrderId above is kept
    // only for callers that genuinely cannot supply credentialId/symbol.
    Optional<Order> findByCredentialIdAndSymbolAndBrokerOrderId(String credentialId, String symbol, String brokerOrderId);
    List<Order> findByStatus(OrderStatus status);
    // Bounded alternative to findByStatus(FILLED) above for metrics services that only need
    // a lookback window: pushes the cutoff down into the database query itself rather than
    // fetching every filled order ever placed and filtering in memory.
    List<Order> findByStatusAndCreatedAtAfter(OrderStatus status, java.time.LocalDateTime cutoff);
    List<Order> findByUserIdAndStatus(String userId, OrderStatus status);
    // Backs OrderService's stuck-UNKNOWN sweep, matching the same "recover, don't silently
    // retry forever" pattern used for auto-trade signal recovery.
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, java.time.LocalDateTime cutoff);
    // Backs RiskEngineService's rolling-window entry-order-frequency check.
    long countByCredentialIdAndCreatedAtAfter(String credentialId, java.time.LocalDateTime cutoff);
    // Checks for unresolved UNKNOWN/RECONCILIATION_REQUIRED orders on this credential before
    // allowing a risk profile to resume trading.
    List<Order> findByCredentialIdAndStatusIn(String credentialId, List<OrderStatus> statuses);
    // Backs TradeCallService's stats query, matching orders to their originating signal.
    List<Order> findBySignalIdIn(List<String> signalIds);
    // Existence check that blocks a client from overwriting a genuinely auto-traded signal's
    // outcome.
    boolean existsBySignalId(String signalId);
    // Pending-entry reconciliation query for PositionMonitorService. ACKNOWLEDGED and
    // PARTIALLY_FILLED represent the same real-world "submitted but not yet fully filled"
    // condition, expressed through this OMS's own formal state machine rather than a raw
    // broker status string. Sorted by createdAt, since Order has no separate placedAt field.
    List<Order> findByCredentialIdAndStatusInOrderByCreatedAtAsc(String credentialId, List<OrderStatus> statuses);
    /**
     * Finds recently-FILLED orders for a credential, bounded by createdAt so reconciliation
     * never re-scans the credential's entire order history on every pass. This catches
     * orders that fill synchronously at placement — the normal case for a market order,
     * whose place-order response can already show status=FILLED — which the
     * ACKNOWLEDGED/PARTIALLY_FILLED pendingEntries query above can never see, since those
     * orders are already FILLED before that reconciliation loop runs for them. An order
     * still missing a Position once this window has closed needs a human to look at it
     * rather than an indefinite automated retry.
     */
    List<Order> findByCredentialIdAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(String credentialId, OrderStatus status, java.time.LocalDateTime cutoff);
    /**
     * Same recently-FILLED lookup as above, but enforces side == "BUY" at the database
     * level. A SELL order (for example, one placed by an emergency flatten to close a
     * position) must never be treated as an unresolved entry and used to create a new
     * phantom position — that phantom position's own flatten would then produce another
     * SELL, which this query would rediscover on the next pass, looping indefinitely.
     * Restricting the query itself to BUY orders means a SELL can never be returned here
     * regardless of what the caller does or doesn't check afterward; see
     * createPositionForLateDiscoveredFill's own independent guard for the matching
     * caller-side check.
     */
    List<Order> findByCredentialIdAndSideAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
        String credentialId, String side, OrderStatus status, java.time.LocalDateTime cutoff);
    // Batch entry-order lookup used to avoid N+1 queries when building the positions
    // dashboard.
    List<Order> findByBrokerOrderIdIn(List<String> brokerOrderIds);
    // Scoped equivalent of the batch lookup above for callers that have already filtered
    // their positions down to a single credentialId: narrows the match space from "any
    // credential, any symbol" to "this credential, any symbol" so a same-numbered order id
    // from a different credential can never be matched in. Still spans multiple symbols
    // within that credential, so the caller keys its own lookup map by symbol+brokerOrderId,
    // not brokerOrderId alone.
    List<Order> findByCredentialIdAndBrokerOrderIdIn(String credentialId, List<String> brokerOrderIds);
    // Paginated query backing the user-facing order-history endpoint.
    List<Order> findByUserId(String userId, org.springframework.data.domain.Pageable pageable);
    // Finds a position's most recent flatten attempt (there can be more than one, since
    // attemptFlatten retries), so recovery can query the exchange for that exact order's
    // real, definitive status by clientOrderId, rather than relying solely on account
    // balance as evidence the flatten succeeded.
    List<Order> findByPositionIdAndOrderRoleOrderByCreatedAtDesc(String positionId, String orderRole);
}
