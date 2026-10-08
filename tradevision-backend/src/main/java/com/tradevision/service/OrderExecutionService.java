package com.tradevision.service;

import com.tradevision.dto.PlaceTestOrderRequest;
import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.Order;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.OpenOrderInfo;
import com.tradevision.service.broker.dto.OrderRequest;
import com.tradevision.service.broker.dto.OrderResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Handles manual, TESTNET-only order placement — a lightweight path for placing a one-off test
 * order outside the autonomous trading system. Autonomous trading (including LIVE) is handled
 * entirely separately by AutoTradeService, which goes through the full risk engine, OMS, and
 * Fill/Position Ledger pipeline that this class deliberately does not.
 */
@Service
@RequiredArgsConstructor
public class OrderExecutionService {

    private final BrokerCredentialService credentialService;
    // Used for read access to Order (OMS) records; placeTestOrder itself writes through
    // OrderService below rather than this repository directly.
    private final com.tradevision.repository.OrderRepository orderRepo;
    // Creates and transitions a real Order (OMS) record for each manual test order, so manual
    // orders are visible through the same order-management machinery as autonomous trades.
    private final OrderService orderService;

    public Order placeTestOrder(String userId, PlaceTestOrderRequest req) {
        BrokerCredential credential = credentialService.ownedCredential(userId, req.getCredentialId());

        // Manual order placement never touches RiskEngineService (no daily-loss/exposure/
        // correlation gate), never reserves a position slot or exposure, and never writes to
        // the Fill Ledger — it's a minimal path with none of the machinery autonomous LIVE
        // trading goes through. Keeping this endpoint TESTNET-only avoids having a second,
        // incomplete LIVE trading path rather than requiring every manual order to be routed
        // through the full Risk -> OMS -> Fill Ledger -> Position Ledger pipeline.
        if (credential.getMode() == BrokerMode.LIVE) {
            credentialService.audit(userId, credential.getId(), credential.getBroker(), "MANUAL_LIVE_ORDER_REFUSED_TESTNET_ONLY",
                "Manual order refused: this endpoint is TESTNET-only. It bypasses the risk engine, OMS, fill ledger, "
                    + "and position lifecycle that every autonomous LIVE trade goes through, so it is not a safe surface "
                    + "for real capital regardless of the credential's own permissions.");
            throw new IllegalStateException("Manual order placement is TESTNET-only. This endpoint does not go through "
                + "the same risk checks, order management, and position tracking that autonomous LIVE trading does.");
        }
        if (credential.isWithdrawalEnabled()) {
            // Should be unreachable (BrokerCredentialService never saves a withdrawal-enabled key),
            // but an order-execution path is exactly where a defensive re-check earns its keep.
            throw new IllegalStateException("Refusing to trade: this credential is flagged as withdrawal-enabled.");
        }

        String side = req.getSide().toUpperCase();
        if (!side.equals("BUY") && !side.equals("SELL")) {
            throw new IllegalArgumentException("side must be BUY or SELL");
        }

        // Take-profit/stop-loss are optional on both ends: a manual order with neither is
        // placed unprotected, as before. If either is set, both must be, and must actually
        // bracket a long correctly — validated here, before any real order reaches the
        // exchange, rather than accepting a combination that would only fail later at OCO
        // placement time.
        BigDecimal takeProfitPrice = req.getTakeProfitPrice();
        BigDecimal stopLossTriggerPrice = req.getStopLossTriggerPrice();
        if ((takeProfitPrice == null) != (stopLossTriggerPrice == null)) {
            throw new IllegalArgumentException("takeProfitPrice and stopLossTriggerPrice must both be provided, or both left out.");
        }
        if (takeProfitPrice != null) {
            if (!side.equals("BUY")) {
                throw new IllegalArgumentException("takeProfitPrice/stopLossTriggerPrice are only meaningful for a BUY (the protective "
                    + "OCO exit sells back out of a long) -- leave them out for a SELL.");
            }
            if (stopLossTriggerPrice.compareTo(takeProfitPrice) >= 0) {
                throw new IllegalArgumentException("stopLossTriggerPrice must be below takeProfitPrice for a long.");
            }
        }

        BrokerAdapter adapter = credentialService.adapterForCredential(credential);
        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);

        // Deriving the client order id from the actual order parameters plus a short time
        // bucket makes retries genuinely idempotent: two identical submissions within the same
        // 10-second window collide and get rejected by Binance as a duplicate, while a
        // deliberately different test order, or the same one tried again later, still goes
        // through.
        String idempotencyBasis = credential.getId() + "|" + req.getSymbol().toUpperCase() + "|" + side
            + "|" + req.getQuantity() + "|" + (System.currentTimeMillis() / 10_000);
        // SHA-256 avoids the collision risk of a 32-bit hashCode() for a trading idempotency
        // key, truncated for a readable clientOrderId (Binance caps these at 36 chars).
        String clientOrderId = "manual-" + sha256Hex(idempotencyBasis).substring(0, 24);
        OrderRequest orderReq = new OrderRequest(req.getSymbol().toUpperCase(), side, "MARKET", req.getQuantity(), clientOrderId);

        // Creates and transitions the Order through the same state machine sequence
        // AutoTradeService's entry-order path uses: create() -> markRiskAccepted() ->
        // markSubmitting() -> recordBrokerResult(). markRiskAccepted/markSubmitting must run
        // between create() and recordBrokerResult(), since recordBrokerResult's ambiguous-result
        // path (markUnknown()) requires the order to already be in SUBMITTING/ACKNOWLEDGED/etc —
        // CREATED -> UNKNOWN is not a legal transition.
        Order order = orderService.create(userId, credential.getId(), null, null,
            orderReq.symbol(), side, "MARKET", req.getQuantity(), null, clientOrderId);
        orderService.markRiskAccepted(order);
        orderService.markSubmitting(order);

        OrderResult result = adapter.placeOrder(apiKey, apiSecret, credential.getMode(), orderReq);
        order = orderService.recordBrokerResult(order, result);
        // recordBrokerResult only persists the fields its own state transition owns (status,
        // failureReason, brokerOrderId, quantities), so broker/mode/triggerSource/rawResponse —
        // along with the TP/SL a later reconciliation pass needs to protect this fill with a
        // real OCO instead of emergency-flattening it unprotected — are written separately here.
        order = orderService.recordEntryMetadata(order, credential.getBroker(), credential.getMode(), "MANUAL",
            result.rawResponse(), stopLossTriggerPrice, takeProfitPrice);

        credentialService.audit(userId, credential.getId(), credential.getBroker(),
            result.success() ? "ORDER_PLACED" : "ORDER_FAILED",
            result.success()
                ? "Manual " + side + " " + orderReq.quantity() + " " + orderReq.symbol() + " -> " + result.status()
                : "Manual " + side + " " + orderReq.quantity() + " " + orderReq.symbol() + " failed: " + result.errorMessage());

        return order;
    }

    public List<OpenOrderInfo> getOpenOrders(String userId, String credentialId) {
        BrokerCredential credential = credentialService.ownedCredential(userId, credentialId);
        BrokerAdapter adapter = credentialService.adapterForCredential(credential);
        return adapter.getOpenOrders(
            credentialService.decrypt(credential, true),
            credentialService.decrypt(credential, false),
            credential.getMode());
    }

    /**
     * Returns this user's order history, paginated across all of their credentials, sorted by
     * creation time descending. Page size is capped at 200 to keep each response bounded.
     */
    public List<Order> history(String userId, int page, int size) {
        var pageable = org.springframework.data.domain.PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200),
            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        return orderRepo.findByUserId(userId, pageable);
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
}
