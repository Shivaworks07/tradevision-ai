package com.tradevision.service;

import com.tradevision.model.Order;
import com.tradevision.model.OrderStatus;
import com.tradevision.repository.OrderRepository;
import com.tradevision.service.broker.dto.OcoOrderResult;
import com.tradevision.service.broker.dto.OrderResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The authoritative order management system (OMS) for order state. Rather than letting
 * PositionSafetyService, AutoTradeService, BrokerAdapter, and ReconciliationService each
 * independently decide an order's status, every status write flows through the transition
 * methods here, each of which validates that the transition is actually legal from the order's
 * current state before applying it. An illegal transition is treated as a bug and throws, which
 * is the invariant a real OMS is expected to enforce.
 *
 * This governs state transitions for every major order path in this codebase, not just entry:
 * PositionMonitorService's OCO placement/resize/late-fill/remainder paths, its OCO cancel/
 * fill-result recording, and PositionSafetyService's emergency-flatten path are all wired
 * through create()/markRiskAccepted()/markSubmitting()/recordBrokerResult()/
 * recordOcoPlacementResult()/recordOcoCancelResult()/recordOcoFillResult().
 *
 * ExecutedOrder (AutoTradeService's separate, older model) still exists as a parallel record for
 * the same logical entry-order events this OMS also tracks via Order/OrderStatus — two
 * documents, two collections, for the same real-world order. This OMS's transitions are additive
 * alongside ExecutedOrder's own writes, not a replacement for them. ExecutedOrder.omsOrderId
 * links back to this OMS's Order.id, written at the one place both records are created for the
 * same real-world entry, so the relationship between the two is documented and queryable rather
 * than inferred from matching timestamps/symbols. The two collections are not yet fully unified
 * into one authoritative record; that remains further schema-migration work.
 *
 * Position Ledger and Fill Ledger (PositionLedgerService, FillLedgerService) exist separately
 * and record the fuller event history alongside this OMS's state transitions.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepo;
    // Used to raise an incident for orders the recovery sweep can't resolve on its own.
    private final IncidentService incidentService;
    // Records a trade event at every order transition, forming the authoritative event history.
    private final com.tradevision.repository.TradeEventRepository tradeEventRepo;
    // Backs the atomic conditional updates (transition() below) that keep status transitions
    // consistent across concurrent writers.
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    /** Lets recoverStuckSubmittingOrders skip its sweep while the application is shutting down. */
    private final com.tradevision.config.ShutdownState shutdownState;
    /** Lets recoverStuckSubmittingOrders ask the broker directly what happened to a stuck order,
     *  instead of only marking it UNKNOWN and stopping there. */
    private final com.tradevision.repository.BrokerCredentialRepository credentialRepo;
    private final BrokerCredentialService credentialService;

    // The explicit legal-transition map. A transition not listed here is a bug in the calling
    // code, not something to silently allow — this is the invariant enforcement a formal OMS is
    // expected to provide.
    private static final Map<OrderStatus, Set<OrderStatus>> LEGAL_TRANSITIONS = Map.ofEntries(
        Map.entry(OrderStatus.CREATED, EnumSet.of(OrderStatus.RISK_ACCEPTED, OrderStatus.RISK_REJECTED)),
        Map.entry(OrderStatus.RISK_ACCEPTED, EnumSet.of(OrderStatus.SUBMITTING)),
        // EXPIRED is a direct legal target from SUBMITTING because recordBrokerResult is the
        // first place an order's real outcome is ever recorded, called immediately after
        // markSubmitting() with the broker's own synchronous placement response — and Binance
        // can report EXPIRED on that very first response for a MARKET order (self-trade
        // prevention, among other matching-engine conditions), with no intermediate
        // ACKNOWLEDGED state ever having genuinely existed.
        Map.entry(OrderStatus.SUBMITTING, EnumSet.of(OrderStatus.ACKNOWLEDGED, OrderStatus.PARTIALLY_FILLED,
            OrderStatus.FILLED, OrderStatus.REJECTED, OrderStatus.SUBMISSION_FAILED, OrderStatus.UNKNOWN,
            OrderStatus.EXPIRED)),
        // EXPIRED is also a direct legal target from UNKNOWN: a stuck order recovered via broker
        // verification can genuinely turn out to be EXPIRED/EXPIRED_IN_MATCH (with or without a
        // partial fill beforehand), and recordBrokerResult's dedicated, fill-preserving EXPIRED
        // branch needs to be reachable from UNKNOWN for that recovery path to work.
        Map.entry(OrderStatus.UNKNOWN, EnumSet.of(OrderStatus.ACKNOWLEDGED, OrderStatus.PARTIALLY_FILLED,
            OrderStatus.FILLED, OrderStatus.REJECTED, OrderStatus.RECONCILIATION_REQUIRED, OrderStatus.EXPIRED)),
        Map.entry(OrderStatus.ACKNOWLEDGED, EnumSet.of(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED,
            OrderStatus.CANCEL_PENDING, OrderStatus.UNKNOWN, OrderStatus.EXPIRED)),
        Map.entry(OrderStatus.PARTIALLY_FILLED, EnumSet.of(OrderStatus.FILLED, OrderStatus.CANCEL_PENDING,
            OrderStatus.UNKNOWN, OrderStatus.EXPIRED)),
        Map.entry(OrderStatus.CANCEL_PENDING, EnumSet.of(OrderStatus.CANCELLED, OrderStatus.FILLED,
            OrderStatus.PARTIALLY_FILLED, OrderStatus.UNKNOWN)),
        // Terminal states — nothing legally transitions out of these.
        Map.entry(OrderStatus.RISK_REJECTED, EnumSet.noneOf(OrderStatus.class)),
        Map.entry(OrderStatus.SUBMISSION_FAILED, EnumSet.noneOf(OrderStatus.class)),
        Map.entry(OrderStatus.FILLED, EnumSet.noneOf(OrderStatus.class)),
        Map.entry(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class)),
        Map.entry(OrderStatus.EXPIRED, EnumSet.noneOf(OrderStatus.class)),
        Map.entry(OrderStatus.REJECTED, EnumSet.noneOf(OrderStatus.class)),
        Map.entry(OrderStatus.RECONCILIATION_REQUIRED, EnumSet.noneOf(OrderStatus.class))
    );

    private void assertLegal(Order order, OrderStatus to) {
        Set<OrderStatus> legal = LEGAL_TRANSITIONS.getOrDefault(order.getStatus(), Set.of());
        if (!legal.contains(to)) {
            throw new IllegalStateException("Illegal order state transition for order " + order.getId()
                + ": " + order.getStatus() + " -> " + to + " is not a legal transition.");
        }
    }

    /**
     * Applies a status transition, validating legality in-memory first via assertLegal() (a
     * cheap pre-check that produces a clear IllegalStateException for an obviously illegal
     * transition like FILLED -> CREATED), then performing the actual database write as a
     * conditional update: it only applies if the order is still in that exact expected prior
     * state at the moment of the write, not just when this method started. If another process
     * already changed the status first, this throws a distinct exception rather than silently
     * succeeding with a stale write, so a caller can't mistake a lost race for a successful
     * transition.
     *
     * This closes the race on the status field specifically — the highest-value field for two
     * concurrent writers to disagree about. It does not make every other field (timestamps, fee
     * data, etc.) atomic; those are set via the caller's own atomicUpdate call alongside the
     * status write, or through fieldUpdate for purely additive metadata.
     */
    private void transition(Order order, OrderStatus to) {
        assertLegal(order, to);
        OrderStatus from = order.getStatus();
        atomicUpdate(order, from, to, update -> {});
        order.setStatus(to);
    }

    /**
     * fieldUpdate applies a targeted $set naming only the field(s) a specific method actually
     * means to change, via a single atomic MongoDB document update, rather than a whole-document
     * overwrite. This means status (and every other field a caller didn't explicitly ask to
     * change) is left exactly as it currently is in MongoDB, regardless of what this method's
     * in-memory `order` parameter says — important for purely-additive metadata methods
     * (recordProtectionPlaced, markExchangeCallStarted, recordVolatility, recordExecutionClaim,
     * recordPlan, recordEntryMetadata, recordOcoOrderId, persistCurrentSlTp) that run against a
     * possibly-stale in-memory order snapshot while a concurrent writer (the WS fast path, a
     * reconciliation worker) may have already moved the order further along in the database.
     */
    private void fieldUpdate(Order order, java.util.function.Consumer<org.springframework.data.mongodb.core.query.Update> fieldSetter) {
        var update = new org.springframework.data.mongodb.core.query.Update();
        fieldSetter.accept(update);
        mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(order.getId())),
            update, Order.class);
    }

    private void atomicUpdate(Order order, OrderStatus from, OrderStatus to, java.util.function.Consumer<org.springframework.data.mongodb.core.query.Update> fieldSetter) {
        var update = new org.springframework.data.mongodb.core.query.Update().set("status", to);
        fieldSetter.accept(update);
        var result = mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(order.getId()).and("status").is(from)),
            update, Order.class);
        if (result.getModifiedCount() == 0) {
            throw new IllegalStateException("Lost a race transitioning order " + order.getId() + " from " + from
                + " to " + to + " -- another process already changed its status first.");
        }
        try {
            tradeEventRepo.save(new com.tradevision.model.TradeEvent(order.getUserId(), order.getCredentialId(),
                order.getPositionId(), order.getId(), order.getSignalId(), order.getSymbol(),
                "ORDER_" + to.name(), "Transitioned from " + from + " to " + to));
        } catch (Exception e) {
            log.warn("Could not record trade event for order {} transitioning to {} (non-fatal, additive record only): {}",
                order.getId(), to, e.getMessage());
        }
    }


    /**
     * Creates a new Order (OMS) record in CREATED status. The client order id is accepted as a
     * parameter rather than generated here, so the same id is used both for this OMS record and
     * for the actual broker request — one id, one source of truth, so the OMS's idempotency key
     * always matches the string Binance actually received.
     */
    // A plain, manually-bumped version string (not an automatically-computed build hash, which
    // would change on every unrelated commit and stop meaning "the strategy logic changed").
    // Bump this by hand whenever SignalCombinerService/ServerSignalEngine's combination logic
    // meaningfully changes, so analytics can group orders by which strategy version produced
    // them.
    public static final String STRATEGY_VERSION = "v1";

    public Order create(String userId, String credentialId, String positionId, String signalId,
                         String symbol, String side, String type, BigDecimal requestedQuantity, BigDecimal requestedPrice,
                         String clientOrderId) {
        Order order = new Order();
        order.setUserId(userId);
        order.setCredentialId(credentialId);
        order.setPositionId(positionId);
        order.setSignalId(signalId);
        order.setSymbol(symbol);
        order.setSide(side);
        order.setType(type);
        order.setRequestedQuantity(requestedQuantity);
        order.setRequestedPrice(requestedPrice);
        order.setRemainingQuantity(requestedQuantity);
        order.setClientOrderId(clientOrderId);
        order.setStrategyVersion(STRATEGY_VERSION);
        order.setStatus(OrderStatus.CREATED);
        Order saved = orderRepo.save(order);
        // create() doesn't go through transition() (there's no "from" state for the very first
        // status), so the ORDER_CREATED event — the start of this order's event timeline — is
        // recorded separately here.
        try {
            tradeEventRepo.save(new com.tradevision.model.TradeEvent(saved.getUserId(), saved.getCredentialId(),
                saved.getPositionId(), saved.getId(), saved.getSignalId(), saved.getSymbol(),
                "ORDER_CREATED", "Order created for " + saved.getSymbol() + " " + saved.getSide() + " " + saved.getType()));
        } catch (Exception e) {
            log.warn("Could not record trade event for order {} creation (non-fatal, additive record only): {}", saved.getId(), e.getMessage());
        }
        return saved;
    }

    /**
     * Generates a deterministic client order id that stays within Binance's 36-character
     * newClientOrderId limit, by combining a short prefix with a truncated SHA-256 hash of the
     * basis string rather than concatenating a raw UUID (which alone can exceed the limit).
     * Shared by both manual and autonomous order paths so they use one tested implementation
     * rather than two that could drift out of sync with the actual limit.
     *
     * @param prefix a short, human-readable tag (e.g. "tv-s" for a signal-driven order) --
     *               kept intentionally brief since every character here comes out of the same
     *               36-character budget as the hash itself
     * @param basis  the value to derive a deterministic, collision-resistant id from (a signal
     *               id, an idempotency basis string, etc.) -- the same basis always produces the
     *               same output, which is what makes this suitable as an actual idempotency key
     */
    public static String generateClientOrderId(String prefix, String basis) {
        String candidate = prefix + "-" + sha256Hex(basis).substring(0, 24);
        if (candidate.length() > 36) {
            // Defensive only -- with the fixed hash length above and any prefix under 11
            // characters this can't actually happen, but a client order id that's silently
            // too long and gets rejected by Binance is exactly the class of bug this method
            // exists to prevent, so this never trusts the arithmetic blindly.
            candidate = candidate.substring(0, 36);
        }
        return candidate;
    }

    private static String sha256Hex(String input) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never happens on any real JVM
        }
    }

    public Order markRiskAccepted(Order order) {
        // Status and riskAcceptedAt are set in one atomic conditional update, rather than a
        // status transition followed by a separate unconditional save, so a concurrent writer
        // can't overwrite either field between the two.
        assertLegal(order, OrderStatus.RISK_ACCEPTED);
        OrderStatus from = order.getStatus();
        order.setRiskAcceptedAt(LocalDateTime.now());
        order.setStatus(OrderStatus.RISK_ACCEPTED);
        atomicUpdate(order, from, OrderStatus.RISK_ACCEPTED, update -> update.set("riskAcceptedAt", order.getRiskAcceptedAt()));
        return order;
    }

    public Order markRiskRejected(Order order, String reason) {
        // Status and failure reason are persisted together in one atomic conditional update.
        assertLegal(order, OrderStatus.RISK_REJECTED);
        OrderStatus from = order.getStatus();
        order.setFailureReason(reason);
        order.setStatus(OrderStatus.RISK_REJECTED);
        atomicUpdate(order, from, OrderStatus.RISK_REJECTED, update -> update.set("failureReason", reason));
        return order;
    }

    public Order markSubmitting(Order order) {
        // Status and submitStartedAt are persisted together in one atomic conditional update.
        assertLegal(order, OrderStatus.SUBMITTING);
        OrderStatus from = order.getStatus();
        order.setSubmitStartedAt(LocalDateTime.now());
        order.setStatus(OrderStatus.SUBMITTING);
        atomicUpdate(order, from, OrderStatus.SUBMITTING, update -> update.set("submitStartedAt", order.getSubmitStartedAt()));
        return order;
    }

    /**
     * The central interpretation method — takes whatever the broker adapter actually reported
     * and decides the order's new state. Mirrors BinanceBrokerAdapter's own success/status
     * distinction: status="UNKNOWN" means genuinely can't tell, handled as UNKNOWN here
     * regardless of the success flag — never silently folded into REJECTED or FILLED.
     */
    public Order recordBrokerResult(Order order, OrderResult result) {
        // The target state is determined and validated before any field mutation happens below,
        // so an illegal-transition throw never leaves the order partially mutated (brokerOrderId
        // set, filledQuantity updated, status still stale) — a caller catching the exception and
        // continuing to use the order object would otherwise see inconsistent state.
        if ("UNKNOWN".equals(result.status())) {
            return markUnknown(order, result.errorMessage() != null ? result.errorMessage() : "Broker result status was UNKNOWN.");
        }
        if (!result.success()) {
            assertLegal(order, OrderStatus.REJECTED);
            OrderStatus from = order.getStatus();
            order.setFailureReason(result.errorMessage());
            order.setBrokerOrderId(result.brokerOrderId());
            order.setStatus(OrderStatus.REJECTED);
            atomicUpdate(order, from, OrderStatus.REJECTED, update -> update
                .set("failureReason", order.getFailureReason())
                .set("brokerOrderId", order.getBrokerOrderId()));
            return order;
        }

        BigDecimal executedQty = result.executedQty();

        // The exchange's own reported terminal status is checked first, before ever consulting
        // executedQty to decide between ACKNOWLEDGED/PARTIALLY_FILLED/FILLED. A MARKET order
        // Binance reports as EXPIRED (e.g. self-trade prevention, or another matching-engine
        // condition that can expire a MARKET order before it fully executes) or
        // EXPIRED_IN_MATCH (partially filled, then the resting remainder expired) must map
        // straight to OrderStatus.EXPIRED rather than ACKNOWLEDGED/PARTIALLY_FILLED — those are
        // non-terminal states that would leave reconcileEntryOrders polling forever for an order
        // the exchange already considers finished. Whatever quantity genuinely did fill before
        // expiry is still recorded.
        if ("EXPIRED".equalsIgnoreCase(result.status()) || "EXPIRED_IN_MATCH".equalsIgnoreCase(result.status())) {
            BigDecimal filled = (executedQty != null && executedQty.signum() > 0) ? executedQty : BigDecimal.ZERO;
            assertLegal(order, OrderStatus.EXPIRED);
            OrderStatus from = order.getStatus();
            order.setBrokerOrderId(result.brokerOrderId());
            if (order.getBrokerAckAt() == null) order.setBrokerAckAt(LocalDateTime.now());
            if (filled.signum() > 0 && order.getFirstFillAt() == null) order.setFirstFillAt(LocalDateTime.now());
            order.setFilledQuantity(filled);
            order.setRemainingQuantity(order.getRequestedQuantity().subtract(filled));
            if (filled.signum() > 0) order.setAverageFillPrice(result.fillPrice());
            order.setStatus(OrderStatus.EXPIRED);
            atomicUpdate(order, from, OrderStatus.EXPIRED, update -> {
                update.set("brokerOrderId", order.getBrokerOrderId())
                    .set("brokerAckAt", order.getBrokerAckAt())
                    .set("filledQuantity", order.getFilledQuantity())
                    .set("remainingQuantity", order.getRemainingQuantity());
                if (filled.signum() > 0) {
                    update.set("firstFillAt", order.getFirstFillAt())
                        .set("averageFillPrice", order.getAverageFillPrice());
                }
            });
            return order;
        }

        if (executedQty == null || executedQty.signum() <= 0) {
            // success=true does not by itself mean anything actually filled. Acknowledged, not
            // filled, until a real quantity is confirmed.
            assertLegal(order, OrderStatus.ACKNOWLEDGED);
            OrderStatus from = order.getStatus();
            order.setBrokerOrderId(result.brokerOrderId());
            if (order.getBrokerAckAt() == null) order.setBrokerAckAt(LocalDateTime.now());
            order.setStatus(OrderStatus.ACKNOWLEDGED);
            atomicUpdate(order, from, OrderStatus.ACKNOWLEDGED, update -> update
                .set("brokerOrderId", order.getBrokerOrderId())
                .set("brokerAckAt", order.getBrokerAckAt()));
            return order;
        }

        boolean fullyFilled = executedQty.compareTo(order.getRequestedQuantity()) >= 0;
        OrderStatus target = fullyFilled ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        assertLegal(order, target);
        OrderStatus from = order.getStatus();

        order.setBrokerOrderId(result.brokerOrderId());
        if (order.getBrokerAckAt() == null) order.setBrokerAckAt(LocalDateTime.now());
        if (order.getFirstFillAt() == null) order.setFirstFillAt(LocalDateTime.now());
        order.setFilledQuantity(executedQty);
        order.setRemainingQuantity(order.getRequestedQuantity().subtract(executedQty));
        order.setAverageFillPrice(result.fillPrice());
        order.setStatus(target);
        if (fullyFilled) order.setFilledAt(LocalDateTime.now());
        // brokerOrderId, timestamps, filled/remaining quantity, average fill price, and status
        // all land in the same atomic conditional update, so a concurrent worker's stale write
        // can't silently overwrite any one of them independently. The in-memory `order` object
        // above is mutated the same way callers/tests expect to read it; atomicUpdate below is
        // the actual persistence mechanism.
        atomicUpdate(order, from, target, update -> {
            update.set("brokerOrderId", order.getBrokerOrderId())
                .set("brokerAckAt", order.getBrokerAckAt())
                .set("firstFillAt", order.getFirstFillAt())
                .set("filledQuantity", order.getFilledQuantity())
                .set("remainingQuantity", order.getRemainingQuantity())
                .set("averageFillPrice", order.getAverageFillPrice());
            if (fullyFilled) update.set("filledAt", order.getFilledAt());
        });
        return order;
    }



    /**
     * Moves an order to the explicit "we genuinely don't know" state, reachable from
     * SUBMITTING, ACKNOWLEDGED, PARTIALLY_FILLED, or CANCEL_PENDING, since a network failure can
     * happen at any of those points, not just at submission. Never silently treated as a
     * terminal failure.
     */
    public Order markUnknown(Order order, String reason) {
        // Status and failure reason are persisted together in one atomic conditional update.
        assertLegal(order, OrderStatus.UNKNOWN);
        OrderStatus from = order.getStatus();
        order.setFailureReason(reason);
        order.setStatus(OrderStatus.UNKNOWN);
        atomicUpdate(order, from, OrderStatus.UNKNOWN, update -> update.set("failureReason", reason));
        log.warn("Order {} (clientOrderId={}) moved to UNKNOWN: {}", order.getId(), order.getClientOrderId(), reason);
        return order;
    }

    /**
     * Recovery attempt for an UNKNOWN order: FOUND resolves to whatever state the broker
     * actually reports (delegated back to recordBrokerResult, so a recovered
     * FILLED/ACKNOWLEDGED/REJECTED order gets exactly the same handling a normal response
     * would); NOT FOUND (the broker positively confirms this order never existed) resolves to
     * REJECTED, safe to treat as never having happened; broker unreachable (the verification
     * query itself failed) resolves to RECONCILIATION_REQUIRED, needing a human rather than
     * another automated retry.
     */
    public Order recoverUnknown(Order order, OrderResult recoveryResult, boolean verificationSucceeded) {
        // Contract: verificationSucceeded=false means the RECOVERY QUERY ITSELF failed (network
        // error, broker unreachable) — recoveryResult should be null in that case, not a
        // synthesized status="UNKNOWN" OrderResult; that status value specifically means
        // "genuinely couldn't verify", which is exactly what verificationSucceeded=false already
        // captures. A caller passing verificationSucceeded=true alongside a status="UNKNOWN"
        // result would hit recordBrokerResult's own UNKNOWN handling, which — correctly — has no
        // legal UNKNOWN-to-UNKNOWN transition and would throw, surfacing the caller's own bug
        // rather than silently looping.
        if (!verificationSucceeded) {
            // Status and failure reason are persisted together in one atomic conditional update.
            assertLegal(order, OrderStatus.RECONCILIATION_REQUIRED);
            OrderStatus from = order.getStatus();
            String reason = "UNKNOWN order could not be recovered — broker verification itself failed. Needs manual reconciliation.";
            order.setFailureReason(reason);
            order.setStatus(OrderStatus.RECONCILIATION_REQUIRED);
            atomicUpdate(order, from, OrderStatus.RECONCILIATION_REQUIRED, update -> update.set("failureReason", reason));
            return order;
        }
        if (recoveryResult == null) {
            // Broker positively confirms: this order never existed. Safe to treat as rejected.
            assertLegal(order, OrderStatus.REJECTED);
            OrderStatus from = order.getStatus();
            String reason = "Broker confirms this order was never accepted — safe to treat as failed.";
            order.setFailureReason(reason);
            order.setStatus(OrderStatus.REJECTED);
            atomicUpdate(order, from, OrderStatus.REJECTED, update -> update.set("failureReason", reason));
            return order;
        }
        // Found — route through the normal interpretation logic, same as a live response. No
        // explicit state reset here: this method's own precondition is that order.getStatus()
        // is already UNKNOWN (it's a recovery for an unknown order) — recordBrokerResult's own
        // transition() call validates that directly. Forcing the state here instead would mask
        // a real caller bug (recovering an order that was never actually UNKNOWN) rather than
        // surface it as the IllegalStateException it should be.
        return recordBrokerResult(order, recoveryResult);
    }

    public Order markCancelRequested(Order order) {
        // Status and cancelRequestedAt are persisted together in one atomic conditional update.
        assertLegal(order, OrderStatus.CANCEL_PENDING);
        OrderStatus from = order.getStatus();
        order.setCancelRequestedAt(LocalDateTime.now());
        order.setStatus(OrderStatus.CANCEL_PENDING);
        atomicUpdate(order, from, OrderStatus.CANCEL_PENDING, update -> update.set("cancelRequestedAt", order.getCancelRequestedAt()));
        return order;
    }

    public Order markCancelled(Order order) {
        // Status and cancelledAt are persisted together in one atomic conditional update.
        assertLegal(order, OrderStatus.CANCELLED);
        OrderStatus from = order.getStatus();
        order.setCancelledAt(LocalDateTime.now());
        order.setStatus(OrderStatus.CANCELLED);
        atomicUpdate(order, from, OrderStatus.CANCELLED, update -> update.set("cancelledAt", order.getCancelledAt()));
        return order;
    }

    public Order markSubmissionFailed(Order order, String reason) {
        // Status and failure reason are persisted together in one atomic conditional update.
        assertLegal(order, OrderStatus.SUBMISSION_FAILED);
        OrderStatus from = order.getStatus();
        order.setFailureReason(reason);
        order.setStatus(OrderStatus.SUBMISSION_FAILED);
        atomicUpdate(order, from, OrderStatus.SUBMISSION_FAILED, update -> update.set("failureReason", reason));
        return order;
    }

    /**
     * Stamps when protection (OCO) was placed for this order. This is a pure timestamp, not a
     * status transition — protection is tracked on Position, not as an Order status, so this
     * only records when it happened, for latency reporting, without the OMS owning that
     * decision.
     */
    public Order recordProtectionPlaced(Order order) {
        if (order.getProtectionPlacedAt() == null) {
            LocalDateTime now = LocalDateTime.now();
            order.setProtectionPlacedAt(now);
            fieldUpdate(order, u -> u.set("protectionPlacedAt", now));
        }
        return order;
    }

    /**
     * Called at exactly one point, immediately before the real adapter.placeOrder() call, to
     * record that the exchange call is about to start. This is a recovery aid for
     * recoverStuckSubmittingOrders, not a precondition for the trade to proceed, so it's a pure
     * field stamp rather than a status transition, and deliberately non-fatal: a write failure
     * here must never block or delay the actual exchange call.
     */
    public void markExchangeCallStarted(Order order) {
        LocalDateTime now = LocalDateTime.now();
        order.setExchangeCallStartedAt(now);
        try {
            fieldUpdate(order, u -> u.set("exchangeCallStartedAt", now));
        } catch (Exception e) {
            log.warn("Could not persist exchangeCallStartedAt for order {} (non-fatal -- the real exchange call proceeds regardless; "
                + "if this process crashes before a later, successful write records this, recovery will conservatively treat this "
                + "order as possibly-contacted, the same safe default as before this field existed): {}", order.getId(), e.getMessage());
        }
    }

    /**
     * Records the ATR-based volatility reading at entry. A pure field stamp, not a status
     * transition, same reasoning as recordProtectionPlaced.
     */
    public Order recordVolatility(Order order, double atrPercent) {
        order.setVolatilityAtEntry(atrPercent);
        fieldUpdate(order, u -> u.set("volatilityAtEntry", atrPercent));
        return order;
    }

    /**
     * Links this order to the execution claim that authorized it, so the order and its claim
     * remain correlated for later auditing. Called once the claim is actually obtained, which
     * happens after this order's initial creation/submission setup in AutoTradeService's flow,
     * since the claim isn't available at creation time.
     */
    public Order recordExecutionClaim(Order order, String claimId) {
        order.setExecutionClaimId(claimId);
        fieldUpdate(order, u -> u.set("executionClaimId", claimId));
        return order;
    }

    /**
     * Links this order to its multi-strategy plan, when one exists. Set as a separate stamp
     * rather than threaded through create()'s already-wide parameter list, since create() is
     * called from many sites — PositionSafetyService, PositionMonitorService, AutoTradeService —
     * most of which have no plan context at all.
     */
    public Order recordPlan(Order order, String planId) {
        order.setPlanId(planId);
        fieldUpdate(order, u -> u.set("planId", planId));
        return order;
    }

    /**
     * Records entry-order metadata: the broker/mode this order was actually placed against, how
     * it was triggered (manual vs. autonomous signal), the full raw broker response for audit,
     * and the SL/TP prices this order's future OCO protection needs. Written as a single,
     * explicit call rather than folded into recordBrokerResult's state-machine logic, since
     * these fields aren't part of that transition's own concerns (status, fill quantity,
     * timestamps) — keeping them separate avoids growing recordBrokerResult's responsibility
     * with fields unrelated to the state transition it exists for.
     */
    public Order recordEntryMetadata(Order order, com.tradevision.model.BrokerType broker, com.tradevision.model.BrokerMode mode,
                                      String triggerSource, String rawResponse, BigDecimal stopLossTrigger, BigDecimal takeProfit) {
        order.setBroker(broker);
        order.setMode(mode);
        order.setTriggerSource(triggerSource);
        order.setRawResponse(rawResponse);
        order.setStopLossTriggerPrice(stopLossTrigger);
        order.setTakeProfitPrice(takeProfit);
        fieldUpdate(order, u -> u.set("broker", broker).set("mode", mode).set("triggerSource", triggerSource)
            .set("rawResponse", rawResponse).set("stopLossTriggerPrice", stopLossTrigger).set("takeProfitPrice", takeProfit));
        return order;
    }

    /**
     * Records the id of this order's linked OCO protection order. Kept as a separate, minimal
     * method rather than folded into recordEntryMetadata, since this is set at a genuinely
     * different point in the order's lifecycle (once OCO protection is actually placed, well
     * after entry) — reusing recordEntryMetadata here would mean re-setting
     * broker/mode/triggerSource/SL-TP from a caller that no longer has the original entry-time
     * values in scope.
     */
    public Order recordOcoOrderId(Order order, String ocoOrderId) {
        order.setOcoOrderId(ocoOrderId);
        fieldUpdate(order, u -> u.set("ocoOrderId", ocoOrderId));
        return order;
    }

    /**
     * Persists this order's current stopLossTriggerPrice/takeProfitPrice, for the case where
     * they were set in-memory earlier in the same evaluation but not yet saved — e.g. when OCO
     * placement itself then failed. recordEntryMetadata isn't reused here since it would also
     * re-set broker/mode/triggerSource/rawResponse, fields this call site doesn't have the
     * original entry-time values for in scope, risking silently overwriting rawResponse with
     * null.
     */
    public Order persistCurrentSlTp(Order order) {
        fieldUpdate(order, u -> u.set("stopLossTriggerPrice", order.getStopLossTriggerPrice()).set("takeProfitPrice", order.getTakeProfitPrice()));
        return order;
    }

    /**
     * Reconciles an order's status against what the broker reports during a reconciliation poll,
     * going through the same assertLegal/atomicUpdate machinery every other status change in
     * this class uses, plus averageFillPrice (the one extra field this caller needs) set in the
     * same atomic operation. A poll that finds the order already in the exact status Binance just
     * reported is a no-op here, not an illegal status-to-itself "transition" — only
     * averageFillPrice is refreshed for that case, with no status write attempted. A caller whose
     * broker-reported status is genuinely illegal from the order's current state (e.g. a stale
     * poll racing a newer, already-applied transition) gets a real IllegalStateException, never a
     * silent, unvalidated overwrite.
     */
    public Order reconcileStatusFromBroker(Order order, OrderStatus newStatus, BigDecimal averageFillPrice) {
        if (newStatus == order.getStatus()) {
            if (averageFillPrice != null) {
                order.setAverageFillPrice(averageFillPrice);
                fieldUpdate(order, u -> u.set("averageFillPrice", averageFillPrice));
            }
            return order;
        }
        assertLegal(order, newStatus);
        OrderStatus from = order.getStatus();
        atomicUpdate(order, from, newStatus, update -> {
            if (averageFillPrice != null) update.set("averageFillPrice", averageFillPrice);
        });
        order.setStatus(newStatus);
        if (averageFillPrice != null) order.setAverageFillPrice(averageFillPrice);
        return order;
    }

    /**
     * Records the result of placing an OCO (take-profit/stop-loss) order, giving OCO/exit orders
     * their own real OMS Order lifecycle rather than just a timestamp on the entry order. The
     * interpretation logic mirrors recordBrokerResult()'s success/failure split: an OCO
     * placement either succeeds (the pair is now live on the exchange, awaiting a TP/SL trigger —
     * nothing has filled yet, so ACKNOWLEDGED is correct, not FILLED) or it fails outright
     * (REJECTED). OcoOrderResult has no UNKNOWN/ambiguous outcome the way a market order's
     * executedQty can, since Binance's OCO placement call is synchronous — either the pair exists
     * afterward or it doesn't — so there's no third branch to handle here.
     */
    public Order recordOcoPlacementResult(Order order, OcoOrderResult result) {
        // Status and failure reason are persisted together in one atomic conditional update.
        if (!result.success()) {
            assertLegal(order, OrderStatus.REJECTED);
            OrderStatus from = order.getStatus();
            order.setFailureReason(result.errorMessage());
            order.setStatus(OrderStatus.REJECTED);
            atomicUpdate(order, from, OrderStatus.REJECTED, update -> update.set("failureReason", order.getFailureReason()));
            return order;
        }
        assertLegal(order, OrderStatus.ACKNOWLEDGED);
        OrderStatus from = order.getStatus();
        order.setBrokerOrderId(result.ocoOrderListId());
        if (order.getBrokerAckAt() == null) order.setBrokerAckAt(LocalDateTime.now());
        order.setStatus(OrderStatus.ACKNOWLEDGED);
        atomicUpdate(order, from, OrderStatus.ACKNOWLEDGED, update -> update
            .set("brokerOrderId", order.getBrokerOrderId())
            .set("brokerAckAt", order.getBrokerAckAt()));
        return order;
    }

    /**
     * Records the result of cancelling an OCO order, giving cancellation a real OMS transition
     * too, not just placement. Callers only have the position's ocoOrderListId at cancel time,
     * not this record's own generated id, so this looks the record up by brokerOrderId (set on
     * it when it was placed, see recordOcoPlacementResult) rather than requiring the caller to
     * track it separately. If no matching record is found, this is a silent no-op — there is
     * nothing to transition, which is a normal, expected case, not an error.
     *
     * On a successful cancel, transitions straight to CANCELLED. On a failed cancel attempt,
     * transitions to UNKNOWN rather than assuming CANCELLED or leaving it at CANCEL_PENDING
     * silently — a failed cancel request genuinely means we don't know whether the OCO is still
     * live, was already filled, or was already cancelled by some other path.
     */
    // credentialId/symbol scope this lookup to the same compound key the unique index on
    // brokerOrderId enforces, rather than ocoOrderListId alone — the bare id can repeat across
    // different credentials or symbols, and matching the wrong Order record here would silently
    // transition an unrelated order's OMS state. Every real call site already has a Position in
    // scope (that's where ocoOrderListId comes from), so credentialId/symbol are always
    // available to pass.
    public void recordOcoCancelResult(String credentialId, String symbol, String ocoOrderListId, OcoOrderResult cancelResult) {
        if (ocoOrderListId == null) return;
        var orderOpt = orderRepo.findByCredentialIdAndSymbolAndBrokerOrderId(credentialId, symbol, ocoOrderListId);
        if (orderOpt.isEmpty()) return;
        Order order = orderOpt.get();
        try {
            markCancelRequested(order);
            if (cancelResult.success()) {
                markCancelled(order);
            } else {
                markUnknown(order, "Cancel request failed: " + cancelResult.errorMessage());
            }
        } catch (IllegalStateException e) {
            // Already in a terminal or otherwise-incompatible state (e.g. a race with a fill
            // discovered through a different path) — exactly what the state machine exists to
            // catch. Logged, not thrown further, since this method's callers treat OMS
            // bookkeeping as strictly additive and non-fatal.
            log.warn("Could not record OCO cancel result for order {} (non-fatal, additive record only): {}", order.getId(), e.getMessage());
        }
    }

    /**
     * Transitions the OCO's OMS Order to FILLED or PARTIALLY_FILLED once a leg actually fills —
     * recordOcoPlacementResult only ever moves it to ACKNOWLEDGED (the pair is live, nothing has
     * filled yet), so this covers the fill side of the lifecycle. Uses the same
     * lookup-by-brokerOrderId pattern as recordOcoCancelResult (the caller only has the
     * position's ocoOrderListId at this point, not this record's own generated id), with the
     * same silent-no-op-if-not-found semantics.
     *
     * fullyFilled distinguishes FILLED from PARTIALLY_FILLED — both are legal direct transitions
     * from ACKNOWLEDGED (see LEGAL_TRANSITIONS above), so no intermediate state is needed the way
     * cancellation needs CANCEL_PENDING first.
     */
    public void recordOcoFillResult(String credentialId, String symbol, String ocoOrderListId, boolean fullyFilled) {
        if (ocoOrderListId == null) return;
        var orderOpt = orderRepo.findByCredentialIdAndSymbolAndBrokerOrderId(credentialId, symbol, ocoOrderListId);
        if (orderOpt.isEmpty()) return;
        Order order = orderOpt.get();
        try {
            transition(order, fullyFilled ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
            // No separate orderRepo.save(order) here: unlike markCancelRequested/markCancelled,
            // this method sets no other fields beyond status (no timestamp, no reason string),
            // so transition()'s own atomic update is already complete, sufficient persistence.
            // An extra unconditional save on top would be redundant at best, and at worst could
            // silently overwrite a field some other process changed concurrently, since a plain
            // save() isn't conditional the way transition()'s atomic update is.
        } catch (IllegalStateException e) {
            // An incompatible state (e.g. this OCO was already cancelled through a different
            // path racing with this fill discovery) is exactly what the state machine exists to
            // catch, logged rather than thrown further given this method's additive, non-fatal
            // contract.
            log.warn("Could not record OCO fill result for order {} (non-fatal, additive record only): {}", order.getId(), e.getMessage());
        }
    }

    /**
     * Periodic sweep that recovers orders stuck in SUBMITTING, covering process crashes as well
     * as ordinary request failures: an order transitioned to SUBMITTING right before a process
     * crash never gets a chance to throw an exception for anything to catch, so it would
     * otherwise sit in SUBMITTING forever. Running on a fixed schedule also effectively covers
     * crash recovery, since the first run after any restart finds and acts on anything still
     * stuck from before the restart.
     *
     * Marks each stuck order UNKNOWN and raises a CRITICAL incident, then immediately attempts
     * real broker verification (see attemptBrokerVerification) rather than leaving it at UNKNOWN
     * indefinitely.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000, scheduler = "maintenanceScheduler") // every 5 minutes, starting shortly after this instance boots
    public void recoverStuckSubmittingOrders() {
        if (shutdownState.isShuttingDown()) return;
        java.time.LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        var stuck = orderRepo.findByStatusAndCreatedAtBefore(OrderStatus.SUBMITTING, cutoff);
        for (Order order : stuck) {
            try {
                // Distinguishes whether this order ever reached the point of a real network call
                // to the exchange, not just "it's been stuck for 5 minutes". A null value here
                // is a strong signal this order never got anywhere near Binance, not an absolute
                // guarantee — the field-stamp write itself (markExchangeCallStarted) is
                // deliberately non-fatal, so a rare, independent write failure at exactly that
                // moment could theoretically leave this field null even though the real call
                // then still proceeded. Because of that narrow gap, this still marks UNKNOWN and
                // escalates for manual verification either way; it never auto-retries or
                // auto-resolves on the strength of this field alone, only gives the human
                // reviewing the incident a more informative, actionable starting point.
                boolean neverReachedExchange = order.getExchangeCallStartedAt() == null;
                String reason = neverReachedExchange
                    ? "Order has been stuck in SUBMITTING for over 5 minutes, and exchangeCallStartedAt was NEVER set -- a strong "
                        + "signal (not an absolute guarantee) that this order never reached the point of a real network call to the "
                        + "exchange at all. The crash most likely happened during risk re-checks, plan/account claims, or exposure "
                        + "reservation -- all of which run before the real exchange call and never touch Binance. Still requires manual "
                        + "verification by clientOrderId (" + order.getClientOrderId() + ") before any automated retry, since a rare, "
                        + "independent failure recording this field cannot be fully ruled out."
                    : "Order has been stuck in SUBMITTING for over 5 minutes, and exchangeCallStartedAt IS set -- the real exchange "
                        + "call was actually sent before this process crashed or restarted, so its outcome is genuinely unknown. The "
                        + "real exchange-side state must be verified manually by clientOrderId (" + order.getClientOrderId()
                        + ") before any further automated action against this credential.";
                markUnknown(order, reason);
                incidentService.raiseCritical(order.getUserId(), order.getCredentialId(), null, order.getId(),
                    order.getSymbol(), "ORDER_STUCK_IN_SUBMITTING", reason);
                // Attempts real broker verification by clientOrderId immediately after marking
                // UNKNOWN, routing the result through the same recoverUnknown()/
                // recordBrokerResult() machinery every other ambiguous-outcome recovery path in
                // this class uses — FILLED/PARTIALLY_FILLED transitions the order so
                // PositionMonitorService.reconcileEntryOrders' own existing "FILLED BUY order
                // with no Position yet" sweep picks it up on its next pass and creates + protects
                // the position through its already-proven late-fill path, exactly the review's
                // own requested outcome, without duplicating that position-creation logic here.
                attemptBrokerVerification(order);
            } catch (Exception e) {
                log.error("Recovery sweep itself failed for stuck order {} (non-fatal to the rest of the sweep, but this specific "
                    + "order remains unresolved): {}", order.getId(), e.getMessage());
            }
        }
    }

    /**
     * The exchange-verification step for a just-marked-UNKNOWN stuck order. Best-effort and
     * entirely non-fatal to the caller's sweep loop — a failure anywhere in here (credential
     * gone, adapter lookup fails, network error) simply leaves the order at UNKNOWN rather than
     * throwing back into the loop and skipping the rest of the batch.
     */
    private void attemptBrokerVerification(Order order) {
        try {
            var credentialOpt = credentialRepo.findById(order.getCredentialId());
            if (credentialOpt.isEmpty() || !credentialOpt.get().isActive()) {
                log.warn("Cannot verify stuck order {} against the exchange -- credential {} is missing or inactive. Leaving at UNKNOWN.",
                    order.getId(), order.getCredentialId());
                return;
            }
            var credential = credentialOpt.get();
            var adapter = credentialService.adapterForCredential(credential);
            if (adapter == null) return;
            String apiKey = credentialService.decrypt(credential, true);
            String apiSecret = credentialService.decrypt(credential, false);
            com.tradevision.service.broker.dto.OrderResult recoveryResult;
            boolean verificationSucceeded;
            try {
                var status = adapter.getOrderStatusByClientOrderId(apiKey, apiSecret, credential.getMode(),
                    order.getSymbol(), order.getClientOrderId());
                verificationSucceeded = true;
                BigDecimal executedQty = status.executedQty() != null ? status.executedQty() : BigDecimal.ZERO;
                // REJECTED alone always means success=false — a genuinely broker-rejected order
                // has never filled anything, by definition. EXPIRED/CANCELED/EXPIRED_IN_MATCH are
                // only treated as a failure when executedQty is genuinely zero; the moment any of
                // those three terminal statuses comes back with a confirmed executedQty > 0, this
                // is treated as a successful result instead, so recordBrokerResult can classify
                // it by the real quantity (PARTIALLY_FILLED/FILLED, or its dedicated
                // fill-preserving EXPIRED branch) rather than discarding a real fill as REJECTED
                // with no Order record pointing at it and no Position ever created.
                boolean genuinelyRejected = "REJECTED".equalsIgnoreCase(status.status());
                boolean terminalNoFillStatus = "EXPIRED".equalsIgnoreCase(status.status())
                    || "CANCELED".equalsIgnoreCase(status.status()) || "EXPIRED_IN_MATCH".equalsIgnoreCase(status.status());
                boolean success = executedQty.signum() > 0 ? true : !(genuinelyRejected || terminalNoFillStatus);
                // brokerOrderId itself isn't a separate field on OrderStatusInfo -- extracted
                // from its own rawResponse, the same raw JSON this adapter call already parsed
                // its other fields from.
                String brokerOrderId = null;
                try {
                    brokerOrderId = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(status.rawResponse()).path("orderId").asText(null);
                } catch (Exception ignored) {
                    // Falls through with brokerOrderId==null -- recordBrokerResult below still
                    // records the real status/quantity even without it.
                }
                recoveryResult = new com.tradevision.service.broker.dto.OrderResult(success, brokerOrderId,
                    order.getClientOrderId(), status.status(), executedQty, status.avgPrice(), status.rawResponse(),
                    success ? null : "Broker reports this order as " + status.status(), java.util.List.of());
            } catch (Exception e) {
                // "-2013" (and the human-readable "does not exist") is Binance's documented code
                // for "order genuinely never existed", safe to treat as REJECTED. Anything else
                // (network failure, timeout, any other broker error) means the verification
                // query itself failed, not that the order was confirmed absent — those two
                // outcomes must never be treated the same way.
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("-2013") || msg.toLowerCase(java.util.Locale.ROOT).contains("does not exist")) {
                    verificationSucceeded = true;
                    recoveryResult = null; // recoverUnknown's own "confirmed absent" contract
                } else {
                    log.warn("Broker verification failed for stuck order {} (clientOrderId={}): {} -- leaving at UNKNOWN for manual review.",
                        order.getId(), order.getClientOrderId(), e.getMessage());
                    verificationSucceeded = false;
                    recoveryResult = null;
                }
            }
            Order recovered = recoverUnknown(order, recoveryResult, verificationSucceeded);
            log.info("Stuck order {} (clientOrderId={}) resolved via broker verification: now {}.",
                order.getId(), order.getClientOrderId(), recovered.getStatus());
        } catch (Exception e) {
            log.warn("Broker verification attempt itself failed unexpectedly for stuck order {} (non-fatal -- order remains at "
                + "UNKNOWN, awaiting manual review): {}", order.getId(), e.getMessage());
        }
    }
}
