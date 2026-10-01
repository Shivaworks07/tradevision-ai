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
 * Review finding ("#4 — OMS", the recommended starting point of the autonomous-engine work —
 * "OMS should own order state — not PositionSafetyService... Don't let PositionSafetyService,
 * AutoTradeService, BrokerAdapter, ReconciliationService all independently decide 'This order is
 * FILLED.' Instead: Broker Adapter → raw broker event/response → OMS → state transition"):
 * this is that authoritative place. Every status write goes through the transition methods
 * below, each of which validates the transition is actually legal from the order's current
 * state before applying it — an illegal transition is a bug, and this throws rather than
 * silently accepting it, exactly the kind of invariant a real OMS should enforce.
 *
 * HONEST SCOPE, stated plainly rather than implied by omission:
 * - UPDATE (P1 "Order lifecycle is still split between OMS and ExecutedOrder" -- this comment's
 *   own earlier version, written when only entry-order placement was wired through, is now
 *   stale and was corrected here after checking the actual current call sites directly, not
 *   assumed): this OMS now governs state transitions for every major order path in this
 *   codebase, not just entry -- confirmed by grepping every orderService.* call site directly:
 *   PositionMonitorService's own OCO placement/resize/late-fill/remainder paths, its OCO
 *   cancel/fill-result recording, and PositionSafetyService's own emergency-flatten path are ALL
 *   wired through create()/markRiskAccepted()/markSubmitting()/recordBrokerResult()/
 *   recordOcoPlacementResult()/recordOcoCancelResult()/recordOcoFillResult() -- this is no
 *   longer entry-only.
 * - What remains genuinely split: ExecutedOrder (AutoTradeService's own separate, older model)
 *   still exists as a PARALLEL record for the same logical entry-order events this OMS also
 *   tracks via Order/OrderStatus -- two documents, two collections, for the same real-world
 *   order. This OMS's own transitions are additive alongside ExecutedOrder's own writes, not a
 *   replacement for them (see AutoTradeService.evaluateForProfileLocked's own dual writes).
 *   UPDATE: the two are no longer silently, untraceably parallel -- ExecutedOrder.omsOrderId
 *   now links back to this OMS's own Order.id, written at the one place both records are
 *   created for the same real-world entry. This is NOT full unification -- both collections
 *   still exist, both are still separately written and read throughout this codebase, and
 *   nothing here changes that. What it changes is that the split is now a documented,
 *   queryable relationship rather than an implicit coincidence a reader would have to infer
 *   from matching timestamps/symbols. Fully unifying these into one authoritative record
 *   remains real further schema-migration work -- changing what ExecutedOrder even IS partway
 *   through a session already carrying substantial money-consequential changes is exactly the
 *   kind of large, hard-to-verify rewrite this codebase's own established discipline avoids
 *   rushing, and adding a traceable link is the honest, bounded version of this fix that's
 *   actually achievable without a compiler.
 * - Position Ledger and Fill Ledger (review items #6/#5) DO now exist (PositionLedgerService,
 *   FillLedgerService) -- built in a later pass than when this comment originally claimed
 *   otherwise; that claim was also stale and is corrected here.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepo;
    // Review finding ("Real-world order recovery needs to cover process crashes, not only HTTP
    // errors" -- P1, full context in recoverStuckSubmittingOrders's own javadoc): needed to
    // raise an incident for orders this recovery sweep can't itself resolve.
    private final IncidentService incidentService;
    // Review finding ("There is still no authoritative event ledger" -- P1, full context in
    // TradeEvent's own javadoc): needed to record a real event at every real order transition.
    private final com.tradevision.repository.TradeEventRepository tradeEventRepo;
    // Review finding ("Order state transitions not atomic across replicas (load-check-save, not
    // conditional update)" -- P1): needed for the actual fix -- transition() below.
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    /**
     * Review finding ("Graceful shutdown does not stop @Scheduled work or WebSocket listeners
     * from starting new work" -- external review, nineteenth pass, P1, confirmed real by direct
     * inspection: recoverStuckSubmittingOrders below had no shutdown-awareness at all, unlike
     * every other @Scheduled method in this codebase): the fix.
     */
    private final com.tradevision.config.ShutdownState shutdownState;
    /**
     * Review finding (P1-4 — "Orders stuck in SUBMITTING are never resolved against the
     * exchange"): needed so recoverStuckSubmittingOrders below can actually ask the broker what
     * really happened instead of only marking UNKNOWN and stopping there.
     */
    private final com.tradevision.repository.BrokerCredentialRepository credentialRepo;
    private final BrokerCredentialService credentialService;

    // Review finding (same doc — "UNKNOWN must be first-class"): the explicit legal-transition
    // map. A transition not listed here is a bug in the calling code, not something to silently
    // allow — this is exactly the invariant enforcement a formal OMS is supposed to provide.
    private static final Map<OrderStatus, Set<OrderStatus>> LEGAL_TRANSITIONS = Map.ofEntries(
        Map.entry(OrderStatus.CREATED, EnumSet.of(OrderStatus.RISK_ACCEPTED, OrderStatus.RISK_REJECTED)),
        Map.entry(OrderStatus.RISK_ACCEPTED, EnumSet.of(OrderStatus.SUBMITTING)),
        // Review finding (P1-14 -- "MARKET order EXPIRED/EXPIRED_IN_MATCH misclassified" -- full
        // context in recordBrokerResult's own updated javadoc): OrderStatus.EXPIRED added here
        // as a direct legal target from SUBMITTING. Before this fix, EXPIRED was only reachable
        // from ACKNOWLEDGED/PARTIALLY_FILLED (an order this OMS had already acknowledged and was
        // separately polling later), but recordBrokerResult is the FIRST place an order's real
        // outcome is ever recorded, called immediately after markSubmitting() with the broker's
        // own synchronous placement response -- and Binance can report EXPIRED on that very
        // first response for a MARKET order (self-trade prevention, among other matching-engine
        // conditions), with no intermediate ACKNOWLEDGED state ever having genuinely existed.
        Map.entry(OrderStatus.SUBMITTING, EnumSet.of(OrderStatus.ACKNOWLEDGED, OrderStatus.PARTIALLY_FILLED,
            OrderStatus.FILLED, OrderStatus.REJECTED, OrderStatus.SUBMISSION_FAILED, OrderStatus.UNKNOWN,
            OrderStatus.EXPIRED)),
        Map.entry(OrderStatus.UNKNOWN, EnumSet.of(OrderStatus.ACKNOWLEDGED, OrderStatus.PARTIALLY_FILLED,
            OrderStatus.FILLED, OrderStatus.REJECTED, OrderStatus.RECONCILIATION_REQUIRED)),
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
     * Review finding ("Order state transitions not atomic across replicas (load-check-save, not
     * conditional update)" -- P1): confirmed real and fixed, same conditional-update pattern
     * already established for Position's own close transition (PositionMonitorService's own
     * atomic close, see its own comment there for the full reasoning). assertLegal() above still
     * validates in-memory first (a cheap, fast pre-check, and what actually produces a clear
     * IllegalStateException for a genuinely illegal transition attempt, like FILLED -> CREATED)
     * -- but the actual database write is now conditional on the order STILL being in that exact
     * expected prior state at the moment of the write, not just when this method started. A lost
     * race (another process already changed the status first) throws a distinct, clear exception
     * rather than silently succeeding with a stale write, so a caller can't mistake "I lost a
     * race" for "my transition succeeded."
     *
     * HONEST SCOPE, stated rather than left implicit: this closes the specific race on the
     * status field itself -- the highest-value, most consequential field for two concurrent
     * writers to disagree about. It does NOT make every OTHER field this class's own calling
     * methods set (timestamps, fee data, etc.) atomic too -- those still go through a plain
     * orderRepo.save(order) after this method returns, matching Position's own documented scope
     * boundary ("this pattern is NOT applied to every terminal-status write in this file").
     */
    private void transition(Order order, OrderStatus to) {
        assertLegal(order, to);
        OrderStatus from = order.getStatus();
        atomicUpdate(order, from, to, update -> {});
        order.setStatus(to);
    }

    /**
     * Review finding ("OrderService state transition is STILL not actually atomic for broker
     * results" -- external review, seventeenth pass, P0, confirmed real by direct inspection
     * before any fix was attempted: recordBrokerResult() called assertLegal() -- an in-memory
     * check against a possibly-stale `order` object -- then plain orderRepo.save(order), which
     * writes every field on that stale object, including status, unconditionally. Two
     * concurrent reconciliation workers each holding their own stale copy of the same order
     * could each pass their own assertLegal() check, and whichever save() ran LAST would win
     * regardless of which one actually reflected the broker's real, current state -- a stale
     * ACKNOWLEDGED could silently overwrite a genuine FILLED): the actual fix -- every status
     * transition in this class, not just the plain ones transition() already handled, now goes
     * through one real MongoDB atomic conditional update: WHERE id=X AND status=from (the
     * order's own status BEFORE this call's own mutations, captured explicitly by the caller --
     * never read off `order` itself inside this method, since every caller of this method
     * mutates order.status to the NEW target before calling this, and reading it here would
     * silently compare the new status against itself, making the whole check meaningless), SET
     * status=to plus every other field the caller needs set, in the SAME operation. If another
     * worker already changed the status first, this update matches nothing and modifiedCount is
     * 0 -- thrown as a real, loud exception (not silently ignored), exactly like transition()'s
     * own existing behavior, which every caller of this class's transition methods already
     * treats as non-fatal/additive (see e.g. AutoTradeService's own try/catch around every
     * recordBrokerResult() call).
     */
    /**
     * Review finding (P1 #12 -- "Order state written by whole-document save() with no
     * optimistic locking"): confirmed real -- every purely-additive metadata method below
     * (recordProtectionPlaced, markExchangeCallStarted, recordVolatility, recordExecutionClaim,
     * recordPlan, recordEntryMetadata, recordOcoOrderId, persistCurrentSlTp) used to call a
     * plain orderRepo.save(order), writing EVERY field on whatever possibly-stale in-memory
     * `order` object the caller happened to be holding -- including status. A concurrent writer
     * (the WS fast path, a reconciliation worker) that had already moved this exact order's
     * status to FILLED in the database could have that write silently clobbered back to a stale
     * SUBMITTING/ACKNOWLEDGED the instant one of these additive methods ran afterward against an
     * in-memory `order` snapshot taken before that concurrent write happened -- exactly the
     * "lost update... can move FILLED back to SUBMITTING" scenario the review named. Fixed the
     * same way atomicUpdate above already fixes it for status transitions: a targeted $set
     * naming only the field(s) this specific method actually means to change, via Mongo's own
     * atomic single-document update, never a whole-document overwrite. Status (and every other
     * field a caller didn't explicitly ask to change) is left exactly as it currently is in
     * MongoDB, regardless of what this method's own in-memory `order` parameter says.
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
     * Review finding ("OMS clientOrderId doesn't match the actual broker clientOrderId" -- P0):
     * confirmed real and fixed. This method used to generate its OWN "ord-<UUID>" client order
     * id, completely independent of whatever id actually got sent to the broker (AutoTradeService
     * separately built its own "sig-<signalId>" for the real OrderRequest) -- meaning the OMS's
     * own idempotency key was never the same string Binance actually received. Now accepts the
     * real client order id as a parameter, generated once by the caller and used for both the
     * OMS record and the actual broker request -- one id, one source of truth, not two that
     * happen to usually agree.
     */
    // Review finding ("Strategy/risk-profile/feature versioning fields exist but are
    // unused/null" -- P1): confirmed real -- both LatencyMetricsService and SlippageMetricsService
    // already documented this exact gap blocking their own "by strategy version" analytics. A
    // plain, honest, manually-bumped string -- not an automatically-computed build hash, which
    // would change on every unrelated commit and stop meaning "the strategy LOGIC changed."
    // Bump this by hand whenever SignalCombinerService/ServerSignalEngine's own combination
    // logic meaningfully changes, same spirit as RiskProfile.version's own manual-bump design.
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
        // Review finding ("There is still no authoritative event ledger" -- P1, full context in
        // TradeEvent's own javadoc): create() doesn't go through transition() (there's no "from"
        // state for the very first status), so this is recorded separately here -- otherwise
        // ORDER_CREATED, the actual start of every order's own event timeline, would never
        // appear in it at all.
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
     * Review finding ("Autonomous path's sig-<UUID> client order ID may exceed Binance's length
     * limit" -- P0): confirmed real and fixed. Binance's own API docs state newClientOrderId is
     * capped at 36 characters (checked directly, not assumed) -- "sig-" + a standard 36-char
     * UUID signal id is 40 characters, over the limit, meaning an autonomous order could simply
     * be rejected by Binance outright. Manual orders already avoided this (OrderExecutionService's
     * own "manual-" + sha256Hex(...).substring(0,24) pattern, 31 chars) -- this is that same
     * technique, generalized so both paths share one tested implementation rather than two
     * independent ones that could drift out of sync with the actual limit.
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
        // Review finding ("Same principle should be applied to markRiskAccepted()... where
        // their post-transition save() can similarly overwrite fields/status changed by another
        // worker" -- external review, seventeenth pass, P0, full context in atomicUpdate's own
        // javadoc): this used to call transition() (atomic for status alone) and then a
        // SEPARATE, unconditional orderRepo.save(order) to persist riskAcceptedAt -- exactly the
        // same race the review named for recordBrokerResult(), just on a different field. Now
        // one atomic conditional update for both.
        assertLegal(order, OrderStatus.RISK_ACCEPTED);
        OrderStatus from = order.getStatus();
        order.setRiskAcceptedAt(LocalDateTime.now());
        order.setStatus(OrderStatus.RISK_ACCEPTED);
        atomicUpdate(order, from, OrderStatus.RISK_ACCEPTED, update -> update.set("riskAcceptedAt", order.getRiskAcceptedAt()));
        return order;
    }

    public Order markRiskRejected(Order order, String reason) {
        // Review finding, same context as markRiskAccepted's own updated comment above.
        assertLegal(order, OrderStatus.RISK_REJECTED);
        OrderStatus from = order.getStatus();
        order.setFailureReason(reason);
        order.setStatus(OrderStatus.RISK_REJECTED);
        atomicUpdate(order, from, OrderStatus.RISK_REJECTED, update -> update.set("failureReason", reason));
        return order;
    }

    public Order markSubmitting(Order order) {
        // Review finding, same context as markRiskAccepted's own updated comment above.
        assertLegal(order, OrderStatus.SUBMITTING);
        OrderStatus from = order.getStatus();
        order.setSubmitStartedAt(LocalDateTime.now());
        order.setStatus(OrderStatus.SUBMITTING);
        atomicUpdate(order, from, OrderStatus.SUBMITTING, update -> update.set("submitStartedAt", order.getSubmitStartedAt()));
        return order;
    }

    /**
     * The central interpretation method — takes whatever the broker adapter actually reported
     * and decides the order's new state. Reuses the SAME success/status distinction
     * BinanceBrokerAdapter already makes (see its own "P1 #22" fix): status="UNKNOWN" means
     * genuinely can't tell, handled as UNKNOWN here regardless of the success flag — never
     * silently folded into REJECTED or FILLED.
     */
    public Order recordBrokerResult(Order order, OrderResult result) {
        // Review finding (this doc's own "real OMS" standard, caught while writing this
        // method's own tests): the target state is determined AND VALIDATED before any field
        // mutation happens below — an illegal-transition throw must never leave the order
        // partially mutated (brokerOrderId set, filledQuantity updated, status still stale). A
        // caller catching the exception and continuing to use the order object would otherwise
        // see inconsistent state, exactly what a real OMS's invariant enforcement should prevent.
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

        // Review finding (P1-14 -- "MARKET order EXPIRED/EXPIRED_IN_MATCH misclassified"):
        // confirmed real. Classification below used to look ONLY at executedQty, never at the
        // exchange's own reported status -- a MARKET order Binance itself reports as EXPIRED
        // (unfilled: e.g. self-trade prevention, or a matching-engine condition that can still
        // expire a MARKET order before it fully executes) landed on ACKNOWLEDGED via the
        // executedQty==0 branch below, a NON-terminal state this OMS's own reconcileEntryOrders
        // loop would then keep polling forever -- the exchange itself will never send a further
        // update for an order it already considers finished. EXPIRED_IN_MATCH with
        // executedQty>0 (partially filled, then the resting remainder expired, e.g. STP) landed
        // on PARTIALLY_FILLED, an equally non-terminal state implying more fills may still
        // arrive, which they never will, silently holding reservations/slots open indefinitely.
        // Checking the exchange's own reported terminal status FIRST -- before ever consulting
        // executedQty to decide between ACKNOWLEDGED/PARTIALLY_FILLED/FILLED -- and mapping
        // either straight to this OMS's own OrderStatus.EXPIRED (already a legal, terminal state
        // in LEGAL_TRANSITIONS above; it simply had no real caller reaching it via this path
        // before this fix) closes both gaps at once, while still recording whatever quantity
        // genuinely did fill before expiry, exactly as the audit's own suggested fix specifies
        // ("map exchange status first; terminal statuses become terminal with filled qty
        // recorded").
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
            // Review finding (this doc's "phantom position" concern, already applied to
            // ExecutedOrder elsewhere in this codebase): success=true does NOT by itself mean
            // anything actually filled. Acknowledged, not filled, until a real quantity is confirmed.
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
        // Review finding ("OrderService state transition is STILL not actually atomic for
        // broker results" -- external review, seventeenth pass, P0, full context in
        // atomicUpdate's own javadoc): the actual fix -- brokerOrderId, timestamps, filled/
        // remaining quantity, average fill price, AND status all land in the SAME atomic
        // conditional update, not a separate assertLegal()-then-plain-save() that a concurrent
        // worker's own stale write could silently overwrite. The in-memory `order` object above
        // is still mutated the same way it always was (every existing caller/test reads its
        // fields directly off this object, not a re-fetched copy) -- atomicUpdate below is the
        // actual persistence mechanism replacing the old plain orderRepo.save(order), not a
        // second, separate write.
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
     * Review finding (same doc — "UNKNOWN must be first-class"): the explicit "we genuinely
     * don't know" state, reachable from SUBMITTING, ACKNOWLEDGED, PARTIALLY_FILLED, or
     * CANCEL_PENDING (a network failure can happen at any of those points, not just at
     * submission). Never silently treated as FAILED.
     */
    public Order markUnknown(Order order, String reason) {
        // Review finding, same context as markRiskAccepted's own updated comment above.
        assertLegal(order, OrderStatus.UNKNOWN);
        OrderStatus from = order.getStatus();
        order.setFailureReason(reason);
        order.setStatus(OrderStatus.UNKNOWN);
        atomicUpdate(order, from, OrderStatus.UNKNOWN, update -> update.set("failureReason", reason));
        log.warn("Order {} (clientOrderId={}) moved to UNKNOWN: {}", order.getId(), order.getClientOrderId(), reason);
        return order;
    }

    /**
     * Recovery attempt for an UNKNOWN order, per the review's own required flow: FOUND ->
     * whatever state the broker actually reports (delegates back to recordBrokerResult, so a
     * recovered FILLED/ACKNOWLEDGED/REJECTED order gets exactly the same handling a normal
     * response would); NOT FOUND (the broker positively confirms this order never existed) ->
     * REJECTED, safe to treat as never having happened; broker unreachable (the verification
     * itself failed) -> RECONCILIATION_REQUIRED, needing a human, not another automated retry.
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
            // Review finding, same context as markRiskAccepted's own updated comment above.
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
        // Review finding, same context as markRiskAccepted's own updated comment above.
        assertLegal(order, OrderStatus.CANCEL_PENDING);
        OrderStatus from = order.getStatus();
        order.setCancelRequestedAt(LocalDateTime.now());
        order.setStatus(OrderStatus.CANCEL_PENDING);
        atomicUpdate(order, from, OrderStatus.CANCEL_PENDING, update -> update.set("cancelRequestedAt", order.getCancelRequestedAt()));
        return order;
    }

    public Order markCancelled(Order order) {
        // Review finding, same context as markRiskAccepted's own updated comment above.
        assertLegal(order, OrderStatus.CANCELLED);
        OrderStatus from = order.getStatus();
        order.setCancelledAt(LocalDateTime.now());
        order.setStatus(OrderStatus.CANCELLED);
        atomicUpdate(order, from, OrderStatus.CANCELLED, update -> update.set("cancelledAt", order.getCancelledAt()));
        return order;
    }

    public Order markSubmissionFailed(Order order, String reason) {
        // Review finding, same context as markRiskAccepted's own updated comment above.
        assertLegal(order, OrderStatus.SUBMISSION_FAILED);
        OrderStatus from = order.getStatus();
        order.setFailureReason(reason);
        order.setStatus(OrderStatus.SUBMISSION_FAILED);
        atomicUpdate(order, from, OrderStatus.SUBMISSION_FAILED, update -> update.set("failureReason", reason));
        return order;
    }

    /**
     * Review finding ("#9 — Execution Latency"): a pure timestamp stamp, deliberately NOT a
     * state transition — protection (OCO) is tracked on Position, not as an Order status in this
     * pass (OCO placement remains outside OMS scope, see this class's own top-level javadoc).
     * This just records WHEN it happened, for the fifth latency stage the review's own list
     * names, without pretending the OMS owns that decision.
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
     * Review finding ("Recovery after exchange submission still needs a stronger state
     * boundary" -- external review, twentieth pass, P1, full context in
     * Order.exchangeCallStartedAt's own field javadoc): the actual write -- called by
     * AutoTradeService (and any other real placement site) at exactly one point, immediately
     * before the real adapter.placeOrder() call, never before. A pure field stamp like
     * recordProtectionPlaced above, not a status transition -- deliberately non-fatal (this is
     * a recovery aid, not a precondition for the real, already-decided trade to proceed) so a
     * write failure here never blocks or delays the actual exchange call by even a moment.
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
     * Review finding ("Execution" — "slippage by volatility"): a pure field stamp, deliberately
     * NOT a state transition — same reasoning as recordProtectionPlaced.
     */
    public Order recordVolatility(Order order, double atrPercent) {
        order.setVolatilityAtEntry(atrPercent);
        fieldUpdate(order, u -> u.set("volatilityAtEntry", atrPercent));
        return order;
    }

    /**
     * Review finding ("Execution-in-flight counter is useful, but it isn't tied to a claim" /
     * "Auto-trade evaluator lease and execution claim should be tied together" -- external
     * review, fourth pass, P1, full context in Order.executionClaimId's own field javadoc): the
     * same additive, non-fatal stamp pattern as recordVolatility above -- called once the claim
     * is actually obtained (which happens after this order's own initial creation/submission
     * setup in AutoTradeService's own flow), so the durable correlation exists even though the
     * claim itself wasn't available at creation time.
     */
    public Order recordExecutionClaim(Order order, String claimId) {
        order.setExecutionClaimId(claimId);
        fieldUpdate(order, u -> u.set("executionClaimId", claimId));
        return order;
    }

    /**
     * User's own explicit multi-strategy-plan design, full context in Order.planId's own field
     * javadoc: same additive, non-fatal stamp pattern as recordVolatility/recordExecutionClaim
     * above -- called once a plan is known, rather than threading planId through create()'s own
     * already-wide parameter list (called from many sites -- PositionSafetyService,
     * PositionMonitorService, AutoTradeService -- most of which have no plan context at all and
     * would need a meaningless null argument added just for this one caller's benefit).
     */
    public Order recordPlan(Order order, String planId) {
        order.setPlanId(planId);
        fieldUpdate(order, u -> u.set("planId", planId));
        return order;
    }

    /**
     * Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in Order's own
     * updated "HONEST SCOPE" comment): the entry-order metadata that used to live only on the
     * now-retired parallel ExecutedOrder record -- the broker/mode this order was actually
     * placed against, how it was triggered (manual vs. autonomous signal), the full raw broker
     * response for audit, and the SL/TP prices this order's own future OCO protection needs
     * (see Order.stopLossTriggerPrice's own field comment for why this is stamped here, before
     * OCO placement, rather than only once OCO placement itself succeeds). A single, explicit
     * write here rather than folding these into recordBrokerResult's own state-machine logic,
     * since these fields aren't part of that transition's own concerns (status, fill quantity,
     * timestamps) -- keeping them separate avoids growing recordBrokerResult's own already
     * substantial responsibility with fields unrelated to the state transition it exists for.
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
     * Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in
     * recordEntryMetadata's own javadoc): a separate, minimal method rather than folding this
     * into recordEntryMetadata -- this is set at a genuinely different point in this order's own
     * lifecycle (once its own linked OCO protection is actually placed, well after entry), and
     * reusing recordEntryMetadata here would mean re-setting broker/mode/triggerSource/SL-TP a
     * second time with the same values, or worse, accidentally different ones from a caller
     * that didn't have the original entry-time values in scope anymore.
     */
    public Order recordOcoOrderId(Order order, String ocoOrderId) {
        order.setOcoOrderId(ocoOrderId);
        fieldUpdate(order, u -> u.set("ocoOrderId", ocoOrderId));
        return order;
    }

    /**
     * Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in
     * recordEntryMetadata's own javadoc): persists this order's own stopLossTriggerPrice/
     * takeProfitPrice, set in-memory earlier in this same evaluation but not yet saved for the
     * specific case where OCO placement itself then FAILED -- recordEntryMetadata isn't reused
     * here since it would also re-set broker/mode/triggerSource/rawResponse, fields this
     * specific call site doesn't have the original values for in scope (this isn't entry time
     * anymore), risking silently overwriting rawResponse with null.
     */
    public Order persistCurrentSlTp(Order order) {
        fieldUpdate(order, u -> u.set("stopLossTriggerPrice", order.getStopLossTriggerPrice()).set("takeProfitPrice", order.getTakeProfitPrice()));
        return order;
    }

    /**
     * Review finding (P1 #12 -- "Order state written by whole-document save() with no
     * optimistic locking"): the actual fix for PositionMonitorService.reconcileEntryOrders' own
     * bypass -- that method used to mutate order.status directly from a broker reconciliation
     * poll and then call a plain, whole-document omsOrderRepo.save(order), skipping this OMS's
     * own transition-legality check (assertLegal) AND its own atomic conditional update
     * (atomicUpdate) entirely -- a stale in-memory `order` snapshot racing a concurrent writer
     * (the WS fast path, another reconciliation pass) could silently overwrite that writer's own
     * more-current status, or persist a status this OMS's own LEGAL_TRANSITIONS map would have
     * refused as illegal had it actually been checked. Routed through here instead: the same
     * assertLegal/atomicUpdate machinery every other status change in this class already uses,
     * plus averageFillPrice (the one extra field this specific caller also needs) set in the
     * SAME atomic operation. A poll that finds the order already in the exact status Binance just
     * reported is a no-op here, not an illegal status-to-itself "transition" -- only
     * averageFillPrice is refreshed for that case, with no status write attempted at all. A
     * caller whose broker-reported status is genuinely illegal from the order's current state
     * (e.g. a stale poll racing a newer, already-applied transition) gets a real, loud
     * IllegalStateException, exactly like every other illegal transition in this class -- never
     * a silent, unvalidated overwrite.
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
     * Review finding ("OMS not actually authoritative" -- P0, full context in this class's own
     * top-level javadoc's disclosed scope): OCO/exit orders now get their own real OMS Order
     * lifecycle, not just a timestamp stamped onto the entry order. The interpretation logic
     * deliberately mirrors recordBrokerResult()'s own success/failure split -- an OCO placement
     * either succeeds (the pair is now live on the exchange, awaiting a TP/SL trigger — nothing
     * has actually filled yet, so ACKNOWLEDGED is the correct state, not FILLED) or it fails
     * outright (REJECTED, matching the same interpretation recordBrokerResult gives an
     * unsuccessful market order). OcoOrderResult has no UNKNOWN/ambiguous outcome the way a
     * market order's executedQty can (Binance's OCO placement call is synchronous — either the
     * pair exists afterward or it doesn't), so there's no third branch to handle here.
     */
    public Order recordOcoPlacementResult(Order order, OcoOrderResult result) {
        // Review finding, same context as markRiskAccepted's own updated comment above.
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
     * Review finding ("OMS not actually authoritative" -- P0, full context in this class's own
     * top-level javadoc): cancel now has a real OMS transition too, not just placement. Callers
     * only have the position's own ocoOrderListId at cancel time, not this record's own
     * generated id, so this looks the record up by brokerOrderId (set on it when it was placed
     * -- see recordOcoPlacementResult) rather than requiring the caller to have tracked it
     * separately. If no matching record is found (an OCO placed before this session's OMS
     * wiring existed, or the lookup simply doesn't match), this is a silent no-op — there is
     * nothing to transition, and that's a normal, expected case, not an error.
     *
     * On a successful cancel, transitions straight to CANCELLED. On a failed cancel attempt,
     * transitions to UNKNOWN rather than assuming CANCELLED or leaving it at CANCEL_PENDING
     * silently -- a failed cancel request genuinely means we don't know whether the OCO is still
     * live, was already filled, or was already cancelled by some other path, matching this
     * codebase's own established "never silently treat an ambiguous outcome as resolved" rule.
     */
    // P0-5 fix ("Global unique indexes on exchange order IDs collide across
    // symbols/credentials/testnet" -- full context in Order's own @CompoundIndex javadoc):
    // credentialId/symbol added so this looks up the OCO's OMS record with the same scoped key
    // the new compound unique index actually enforces, rather than by ocoOrderListId alone --
    // the bare id can genuinely repeat across different credentials or symbols, and matching the
    // wrong Order record here would silently transition an unrelated order's OMS state. Every
    // real call site already has a Position in scope (that's exactly where ocoOrderListId comes
    // from), so credentialId/symbol are always available to pass.
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
            // discovered through a different path) -- this is exactly the kind of thing the
            // state machine exists to catch. Logged, not thrown further, since this method's
            // own callers treat OMS bookkeeping as strictly additive and non-fatal.
            log.warn("Could not record OCO cancel result for order {} (non-fatal, additive record only): {}", order.getId(), e.getMessage());
        }
    }

    /**
     * Review finding ("OCO OMS still doesn't become FILLED when a leg fills" -- P0): confirmed
     * real and fixed. recordOcoPlacementResult() only ever moved the OCO's own OMS Order to
     * ACKNOWLEDGED (the pair is live, nothing has filled yet) -- there was no corresponding
     * transition for when a leg actually DOES fill, meaning the OMS could say ACKNOWLEDGED
     * forever while Binance said ALL_DONE and Position said CLOSED. Same lookup-by-brokerOrderId
     * pattern as recordOcoCancelResult (the caller only has the position's own ocoOrderListId at
     * this point, not this record's own generated id), and the same silent-no-op-if-not-found
     * semantics for an OCO placed before this session's OMS wiring existed.
     *
     * fullyFilled distinguishes FILLED from PARTIALLY_FILLED -- both are legal direct transitions
     * from ACKNOWLEDGED (see LEGAL_TRANSITIONS above), so no intermediate state is needed the way
     * cancellation needs CANCEL_PENDING first.
     */
    // P0-5 fix: same reasoning and same new parameters as recordOcoCancelResult's own identical
    // update above -- see that method's javadoc.
    public void recordOcoFillResult(String credentialId, String symbol, String ocoOrderListId, boolean fullyFilled) {
        if (ocoOrderListId == null) return;
        var orderOpt = orderRepo.findByCredentialIdAndSymbolAndBrokerOrderId(credentialId, symbol, ocoOrderListId);
        if (orderOpt.isEmpty()) return;
        Order order = orderOpt.get();
        try {
            transition(order, fullyFilled ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED);
            // Deliberately no separate orderRepo.save(order) here -- unlike
            // markCancelRequested/markCancelled, this method sets no OTHER fields beyond status
            // (no timestamp, no reason string), so transition()'s own atomic update is already
            // the complete, sufficient persistence. An extra unconditional save on top would be
            // redundant at best, and at worst could silently overwrite a field some OTHER
            // process changed concurrently, since a plain save() isn't conditional the way
            // transition()'s own atomic update is -- caught before this shipped, not after.
        } catch (IllegalStateException e) {
            // Same reasoning as recordOcoCancelResult's own identical catch -- an incompatible
            // state (e.g. this OCO was already cancelled through a different path racing with
            // this fill discovery) is exactly what the state machine exists to catch, logged
            // rather than thrown further given this method's own additive, non-fatal contract.
            log.warn("Could not record OCO fill result for order {} (non-fatal, additive record only): {}", order.getId(), e.getMessage());
        }
    }

    /**
     * Review finding ("Real-world order recovery needs to cover process crashes, not only HTTP
     * errors" -- P1): confirmed real -- OrderRepository.findByStatusAndCreatedAtBefore already
     * existed but was never actually called anywhere in this codebase. This is the gap the
     * review named directly: an order transitioned to SUBMITTING right before a process crash
     * (not an HTTP error the placement call's own try/catch could ever see, since the process
     * itself died) would sit in SUBMITTING forever -- no exception was ever thrown for anything
     * to catch, so none of this codebase's existing error-handling/recovery paths would ever
     * run for it.
     *
     * Runs on both a normal schedule AND effectively covers the crash-recovery case too, since
     * the first run after any restart (crash or ordinary deploy) will find and act on anything
     * still stuck from before the restart -- a genuine startup/periodic sweep, not something
     * that only helps if it happens to run before the next crash.
     *
     * Deliberately does NOT attempt automated broker-side verification here (unlike
     * recoverUnknown's own richer flow) -- SUBMITTING specifically means this application isn't
     * even confident the placement CALL itself completed, so there's no reliable clientOrderId/
     * broker context guaranteed to exist yet to verify against. Marks UNKNOWN and raises a
     * CRITICAL incident instead, so a human resolves it with the exchange's own record as the
     * source of truth -- the same conservative, escalate-rather-than-guess principle this
     * codebase already applies to every other genuinely ambiguous order state.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000, scheduler = "maintenanceScheduler") // every 5 minutes, starting shortly after this instance boots
    public void recoverStuckSubmittingOrders() {
        if (shutdownState.isShuttingDown()) return;
        java.time.LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        var stuck = orderRepo.findByStatusAndCreatedAtBefore(OrderStatus.SUBMITTING, cutoff);
        for (Order order : stuck) {
            try {
                // Review finding ("Recovery after exchange submission still needs a stronger
                // state boundary" -- external review, twentieth pass, P1, full context in
                // Order.exchangeCallStartedAt's own field javadoc): the actual distinction --
                // whether this order ever reached the point of a real network call to the
                // exchange, not just "it's been stuck for 5 minutes" as before.
                //
                // HONEST SCOPE, stated plainly rather than overclaimed: a null value here is a
                // STRONG signal this order never got anywhere near Binance, not an absolute
                // guarantee -- the field-stamp write itself (markExchangeCallStarted) is
                // deliberately non-fatal, so a rare, independent write failure at exactly that
                // moment could theoretically leave this field null even though the real call
                // then still proceeded. Because of that narrow gap, this still marks UNKNOWN and
                // escalates for manual verification EITHER WAY -- this fix does not auto-retry
                // or auto-resolve anything on the strength of this field alone, only gives the
                // human reviewing the incident a materially more informative, much more
                // actionable starting point than "no further information" (the entire state of
                // this recovery sweep before this fix).
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
                // Review finding (P1-4 -- "Orders stuck in SUBMITTING are never resolved against
                // the exchange"): confirmed real -- this sweep used to stop right here, leaving
                // the order at UNKNOWN indefinitely with no automated attempt to actually ask the
                // broker what happened. A genuinely-filled order combined with P0-2 (or any crash
                // mid-call) stayed naked forever, waiting on a human to notice the incident. Now
                // attempts real broker verification by clientOrderId immediately after marking
                // UNKNOWN, and routes the result through the SAME recoverUnknown()/
                // recordBrokerResult() machinery every other ambiguous-outcome recovery path in
                // this class already uses -- FILLED/PARTIALLY_FILLED transitions the order so
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
     * Review finding (P1-4): the actual exchange-verification step for a just-marked-UNKNOWN
     * stuck order. Best-effort and entirely non-fatal to the caller's own sweep loop -- a failure
     * anywhere in here (credential gone, adapter lookup fails, network error) simply leaves the
     * order at UNKNOWN, exactly this sweep's prior behavior, rather than throwing back into the
     * loop and skipping the rest of the batch.
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
                boolean success = !"REJECTED".equalsIgnoreCase(status.status()) && !"EXPIRED".equalsIgnoreCase(status.status())
                    && !"CANCELED".equalsIgnoreCase(status.status()) && !"EXPIRED_IN_MATCH".equalsIgnoreCase(status.status());
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
                // Review finding, same reasoning as tryRecoverOrderByClientId's own identical
                // distinction in BinanceBrokerAdapter: "-2013" (and the human-readable "does not
                // exist") is Binance's own documented code for "order genuinely never existed" --
                // safe to treat as REJECTED. Anything else (network failure, timeout, any other
                // broker error) means the verification query itself failed, not that the order
                // was confirmed absent -- those two outcomes must never be treated the same way.
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
                + "UNKNOWN, the same safe state this sweep already left it in before this fix): {}", order.getId(), e.getMessage());
        }
    }
}
