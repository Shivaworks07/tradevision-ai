package com.tradevision.service;

import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.LiveCanaryRecord;
import com.tradevision.model.Order;
import com.tradevision.model.OrderStatus;
import com.tradevision.model.Position;
import com.tradevision.repository.LiveCanaryRecordRepository;
import com.tradevision.repository.PositionRepository;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.OrderRequest;
import com.tradevision.service.broker.dto.OrderResult;
import com.tradevision.service.broker.dto.SymbolRules;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Proves a LIVE broker credential can actually place and resolve a real order before autonomous
 * trading is allowed to rely on it. A canary places one minimal, real BUY order with tight
 * protective TP/SL and tracks it through fill, Position creation, and OCO placement using the
 * exact same pipeline every other LIVE order already goes through.
 *
 * This is an additional safeguard alongside every other LIVE gate in
 * RiskProfileService.authorizeLiveAutoTrade (confirmation phrase, risk-limit completeness, Mongo
 * transaction support, re-verified broker permissions) -- those all remain in force, and a
 * passing canary is checked alongside them, not instead of them. A PASSED result stays valid for
 * {@value #VALID_HOURS} hours (see hasRecentPassingCanary), the same "proven recently enough to
 * trust" window this codebase already uses elsewhere for credential validation, rather than
 * requiring a real order before every single autonomous trade -- which would itself add
 * real-money risk and exchange load instead of removing it.
 */
@Service
@RequiredArgsConstructor
public class LiveCanaryService {

    private static final Logger log = LoggerFactory.getLogger(LiveCanaryService.class);

    private final BrokerCredentialService credentialService;
    private final OrderService orderService;
    private final com.tradevision.repository.OrderRepository orderRepo;
    private final PositionRepository positionRepo;
    private final PositionSafetyService positionSafetyService;
    private final LiveCanaryRecordRepository canaryRepo;
    private final IncidentService incidentService;
    private final com.tradevision.config.ShutdownState shutdownState;

    private static final String REQUIRED_PHRASE = "I CONFIRM THIS PLACES A REAL LIVE ORDER WITH REAL MONEY";
    /** How long a PASSED canary remains good enough to satisfy authorizeLiveAutoTrade's gate. */
    private static final long VALID_HOURS = 24;
    /** How long a PENDING canary is given to resolve (order fill -> Position -> real OCO placed)
     *  before this sweep gives up and marks it FAILED -- PositionMonitorService's own
     *  reconciliation runs every 60 seconds, so 15 minutes is generous headroom, not a tight race. */
    private static final long TIMEOUT_MINUTES = 15;
    /** Defensive ceiling on the computed canary notional -- see startCanary's own javadoc for why
     *  this never trusts a computed quantity blindly, regardless of what the exchange's own
     *  reported minNotional/price say. A real canary order is meant to be the smallest real
     *  amount that proves the pipeline works, never a meaningful trade in its own right. */
    private static final BigDecimal MAX_CANARY_NOTIONAL_USD = new BigDecimal("25");
    /** Buffer applied over the exchange's own reported minNotional so the order still clears it
     *  after this adapter's own stepSize rounding (always rounds DOWN) and any price movement
     *  between this quote and the moment the order actually executes. */
    private static final BigDecimal NOTIONAL_BUFFER = new BigDecimal("1.15");

    /**
     * Places one real, minimal LIVE order on the given symbol and tracks it through to a real
     * Position with real OCO protection, via the SAME pipeline every other LIVE order already
     * uses (OrderService's state machine, PositionMonitorService's own reconciliation, a real
     * protective OCO) -- nothing here is a separate, parallel execution path.
     *
     * The order's quantity is deliberately NEVER a caller-supplied number, by design: this is
     * the one admin-triggered LIVE order-placement surface in this codebase that does NOT take a
     * manual quantity (contrast with the deliberately-TESTNET-only OrderExecutionService.
     * placeTestOrder, which does) -- an admin confirming a canary should be confirming "run the
     * minimal proof trade," not typing a quantity that could be fat-fingered into something far
     * larger than intended on a LIVE credential. The quantity is instead derived from the
     * exchange's own reported minNotional for this exact symbol, with a buffer for rounding/
     * price drift, and hard-capped at MAX_CANARY_NOTIONAL_USD regardless of what that
     * computation produces.
     */
    public LiveCanaryRecord startCanary(String userId, String credentialId, String symbol, String confirmationPhrase) {
        if (!REQUIRED_PHRASE.equals(confirmationPhrase)) {
            throw new IllegalArgumentException("Confirmation phrase did not match. Send exactly: \"" + REQUIRED_PHRASE + "\"");
        }
        BrokerCredential credential = credentialService.ownedCredential(userId, credentialId);
        if (credential.getMode() != BrokerMode.LIVE) {
            throw new IllegalArgumentException("A live canary only applies to a LIVE credential -- this credential is "
                + credential.getMode() + ".");
        }
        if (credential.isWithdrawalEnabled()) {
            // Should be unreachable (BrokerCredentialService never saves a withdrawal-enabled
            // key) -- same defensive re-check as OrderExecutionService.placeTestOrder.
            throw new IllegalStateException("Refusing to trade: this credential is flagged as withdrawal-enabled.");
        }
        String upperSymbol = symbol.toUpperCase();

        BrokerAdapter adapter = credentialService.adapterForCredential(credential);
        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);

        SymbolRules rules = adapter.getSymbolRules(upperSymbol, BrokerMode.LIVE);
        BigDecimal currentPrice = adapter.getCurrentPrice(upperSymbol, BrokerMode.LIVE);
        if (currentPrice == null || currentPrice.signum() <= 0) {
            throw new IllegalStateException("Could not read a current price for " + upperSymbol + " -- refusing to size a live canary order.");
        }
        BigDecimal targetNotional = rules.minNotional() != null && rules.minNotional().signum() > 0
            ? rules.minNotional().multiply(NOTIONAL_BUFFER)
            // Some symbols' NOTIONAL filter doesn't apply to MARKET orders at all / reports zero
            // -- a small, fixed fallback notional rather than "no floor", so this never computes
            // a quantity so small the exchange's own absolute minQty filter rejects it instead.
            : new BigDecimal("10").multiply(NOTIONAL_BUFFER);
        if (targetNotional.compareTo(MAX_CANARY_NOTIONAL_USD) > 0) {
            throw new IllegalStateException("Refusing to place a live canary order: the computed notional (" + targetNotional
                + ") for " + upperSymbol + " exceeds this safeguard's own " + MAX_CANARY_NOTIONAL_USD
                + " ceiling. Pick a lower-priced symbol, or treat this as a sign something about this symbol's "
                + "reported rules is unexpected and investigate before trading it LIVE at all.");
        }
        BigDecimal quantity = targetNotional.divide(currentPrice, 8, RoundingMode.UP);

        // Tight, symmetric TP/SL around the current price -- real protection, not a meaningful
        // profit/loss target. Mirrors OrderExecutionService.placeTestOrder's own validated
        // BUY-only, SL-below-TP shape, so this exercises the exact same reconciliation/OCO-
        // placement path a real manual or autonomous BUY with protection would.
        BigDecimal takeProfitPrice = currentPrice.multiply(new BigDecimal("1.02")).setScale(rules.pricePrecision(), RoundingMode.UP);
        BigDecimal stopLossTriggerPrice = currentPrice.multiply(new BigDecimal("0.98")).setScale(rules.pricePrecision(), RoundingMode.DOWN);

        String idempotencyBasis = credential.getId() + "|" + upperSymbol + "|canary|" + (System.currentTimeMillis() / 10_000);
        String clientOrderId = OrderService.generateClientOrderId("canary", idempotencyBasis);
        OrderRequest orderReq = new OrderRequest(upperSymbol, "BUY", "MARKET", quantity, clientOrderId);

        Order order = orderService.create(userId, credential.getId(), null, null,
            upperSymbol, "BUY", "MARKET", quantity, currentPrice, clientOrderId);
        orderService.markRiskAccepted(order);
        orderService.markSubmitting(order);

        OrderResult result = adapter.placeOrder(apiKey, apiSecret, BrokerMode.LIVE, orderReq);
        order = orderService.recordBrokerResult(order, result);
        order = orderService.recordEntryMetadata(order, credential.getBroker(), BrokerMode.LIVE, "LIVE_CANARY",
            result.rawResponse(), stopLossTriggerPrice, takeProfitPrice);

        credentialService.audit(userId, credential.getId(), credential.getBroker(),
            result.success() ? "LIVE_CANARY_ORDER_PLACED" : "LIVE_CANARY_ORDER_FAILED",
            result.success()
                ? "Live canary BUY " + quantity + " " + upperSymbol + " -> " + result.status()
                : "Live canary BUY " + quantity + " " + upperSymbol + " failed: " + result.errorMessage());

        LiveCanaryRecord record = new LiveCanaryRecord();
        record.setUserId(userId);
        record.setCredentialId(credential.getId());
        record.setBroker(credential.getBroker());
        record.setSymbol(upperSymbol);
        record.setQuantity(quantity);
        record.setOrderId(order.getId());
        record.setEntryOrderId(order.getBrokerOrderId());

        if (isTerminalFailure(order.getStatus())) {
            record.setStatus("FAILED");
            record.setCompletedAt(LocalDateTime.now());
            record.setFailureReason(order.getFailureReason() != null ? order.getFailureReason() : "Order ended in " + order.getStatus());
        }
        return canaryRepo.save(record);
    }

    private boolean isTerminalFailure(OrderStatus status) {
        return status == OrderStatus.REJECTED || status == OrderStatus.SUBMISSION_FAILED
            || status == OrderStatus.CANCELLED || status == OrderStatus.EXPIRED;
    }

    /**
     * The latest canary attempt for a credential, regardless of outcome -- backs the
     * status-check endpoint. Re-verifies ownership itself (the same pattern every other
     * credential-scoped service method in this codebase uses) rather than trusting a bare
     * credentialId handed in from the controller.
     */
    public java.util.Optional<LiveCanaryRecord> latestFor(String userId, String credentialId) {
        credentialService.ownedCredential(userId, credentialId);
        return canaryRepo.findFirstByCredentialIdOrderByStartedAtDesc(credentialId);
    }

    /**
     * Has this exact credential had a real, PASSED live canary within the last {@value
     * #VALID_HOURS} hours? This is the actual gate RiskProfileService.authorizeLiveAutoTrade
     * calls -- see this class's own top-level javadoc for the honest scope of what that does and
     * doesn't replace.
     */
    public boolean hasRecentPassingCanary(String credentialId) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(VALID_HOURS);
        return !canaryRepo.findByCredentialIdAndStatusAndCompletedAtAfterOrderByCompletedAtDesc(credentialId, "PASSED", cutoff).isEmpty();
    }

    /**
     * The reconciliation sweep for PENDING canaries. Deliberately does NOT reimplement any part
     * of fill-detection, Position-creation, or OCO-placement -- those already happen for this
     * order via the exact same, already-proven PositionMonitorService reconciliation pass every
     * other order in this codebase goes through (see PlaceTestOrderRequest/OrderExecutionService's
     * own history for why that machinery is trusted rather than duplicated here). This sweep only
     * ever reads that resulting state back and (a) records PASSED/FAILED on this narrower record,
     * and (b) cleans up a PASSED canary's own test position immediately, rather than leaving a
     * real, open LIVE position sitting around purely because it happened to be a proof trade.
     */
    @Scheduled(fixedDelay = 120_000, initialDelay = 60_000, scheduler = "maintenanceScheduler")
    public void reconcilePendingCanaries() {
        if (shutdownState.isShuttingDown()) return;
        List<LiveCanaryRecord> pending = canaryRepo.findByStatus("PENDING");
        for (LiveCanaryRecord record : pending) {
            try {
                reconcileOne(record);
            } catch (Exception e) {
                log.error("Live canary reconciliation sweep itself failed for record {} (non-fatal to the rest of the sweep, this "
                    + "specific record remains PENDING): {}", record.getId(), e.getMessage());
            }
        }
    }

    private void reconcileOne(LiveCanaryRecord record) {
        Order order = orderRepo.findById(record.getOrderId()).orElse(null);
        if (order == null) {
            fail(record, "The underlying OMS order for this canary attempt could not be found.");
            return;
        }
        if (isTerminalFailure(order.getStatus())) {
            fail(record, order.getFailureReason() != null ? order.getFailureReason() : "Order ended in " + order.getStatus());
            return;
        }
        // Keep entryOrderId current -- it's null at creation time until the broker actually
        // acknowledges/fills the order, which can happen after this record's own initial save.
        if (record.getEntryOrderId() == null && order.getBrokerOrderId() != null) {
            record.setEntryOrderId(order.getBrokerOrderId());
            canaryRepo.save(record);
        }
        boolean filled = order.getStatus() == OrderStatus.FILLED || order.getStatus() == OrderStatus.PARTIALLY_FILLED;
        if (filled && record.getEntryOrderId() != null) {
            var positionOpt = positionRepo.findByCredentialIdAndSymbolAndEntryOrderId(
                record.getCredentialId(), record.getSymbol(), record.getEntryOrderId());
            if (positionOpt.isPresent()) {
                Position position = positionOpt.get();
                record.setPositionId(position.getId());
                if (position.getOcoOrderListId() != null) {
                    pass(record, position);
                    return;
                }
                // Position exists but OCO not placed yet -- give the normal reconciliation pass
                // more time rather than treating "not yet" as "failed," unless we've timed out.
            }
        }
        if (LocalDateTime.now().isAfter(record.getStartedAt().plusMinutes(TIMEOUT_MINUTES))) {
            fail(record, "Timed out after " + TIMEOUT_MINUTES + " minutes waiting for a protected Position to "
                + "result from this canary order (order ended at status " + order.getStatus() + ").");
        } else {
            canaryRepo.save(record);
        }
    }

    private void pass(LiveCanaryRecord record, Position position) {
        record.setStatus("PASSED");
        record.setCompletedAt(LocalDateTime.now());
        canaryRepo.save(record);
        credentialService.audit(record.getUserId(), record.getCredentialId(), record.getBroker(), "LIVE_CANARY_PASSED",
            "Live canary order for " + record.getSymbol() + " confirmed end-to-end: filled, Position " + position.getId()
                + " created, real OCO protection placed (" + position.getOcoOrderListId() + "). Closing the test position now.");
        try {
            closeCanaryPosition(record, position);
        } catch (Exception e) {
            log.warn("Could not clean up live canary test position {} after a PASSED result (non-fatal -- the canary result "
                + "itself still stands; the position remains open under this application's own normal monitoring/protection, "
                + "same as any other real position): {}", position.getId(), e.getMessage());
        }
    }

    private void closeCanaryPosition(LiveCanaryRecord record, Position position) {
        if (!Set.of("OPEN").contains(position.getStatus())) return; // already closing/closed via normal monitoring
        BrokerCredential credential = credentialService.ownedCredential(record.getUserId(), record.getCredentialId());
        BrokerAdapter adapter = credentialService.adapterForCredential(credential);
        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);
        // A routine, planned close of a proof trade -- exitPosition (not emergencyFlatten), so a
        // successful close doesn't halt the profile or raise a CRITICAL incident for what is, by
        // definition, an intentional and expected action. See PositionSafetyService.exitPosition's
        // own javadoc for the exact same reasoning applied to other planned exits.
        positionSafetyService.exitPosition(credential, adapter, apiKey, apiSecret, position, "LIVE_CANARY_CLEANUP");
    }

    private void fail(LiveCanaryRecord record, String reason) {
        record.setStatus("FAILED");
        record.setCompletedAt(LocalDateTime.now());
        record.setFailureReason(reason);
        canaryRepo.save(record);
        credentialService.audit(record.getUserId(), record.getCredentialId(), record.getBroker(), "LIVE_CANARY_FAILED", reason);
        // A failed canary is exactly the signal authorizeLiveAutoTrade's own new gate exists to
        // act on, but it's also independently worth a human's attention immediately -- a LIVE
        // order that didn't resolve cleanly into a protected position is the same class of
        // concern PositionSafetyService/IncidentService already treat as CRITICAL elsewhere.
        incidentService.raiseCritical(record.getUserId(), record.getCredentialId(), record.getPositionId(), record.getOrderId(),
            record.getSymbol(), "PROTECTION_UNKNOWN", "Live canary failed: " + reason);
    }
}
