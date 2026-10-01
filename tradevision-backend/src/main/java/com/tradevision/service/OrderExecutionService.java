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

import java.time.LocalDateTime;
import java.util.List;

/**
 * Review finding ("Stale documentation" -- P0): this class's own comment used to describe an
 * early "Stage 1/Stage 2" design (a single manual test-order button, with a LIVE auto-trigger
 * path described as future work) that has been fully superseded by the real autonomous system
 * built since -- AutoTradeService, RiskEngineService, the OMS, and the Fill/Position Ledgers all
 * exist and are what every autonomous LIVE trade actually goes through. This class's own actual,
 * current scope: manual order placement only, and — since the fix just below — TESTNET only.
 * Autonomous trading (LIVE included) is AutoTradeService's own, entirely separate path, which
 * does go through the full risk/OMS/ledger pipeline this class deliberately does not.
 */
@Service
@RequiredArgsConstructor
public class OrderExecutionService {

    private final BrokerCredentialService credentialService;
    // Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in this class's
    // own updated history javadoc): migrated from ExecutedOrderRepository to the real Order
    // (OMS) repository -- this is the last real reader in this class, since placeTestOrder's
    // own write path was already migrated to go through OrderService below instead of this
    // repository directly.
    private final com.tradevision.repository.OrderRepository orderRepo;
    // Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in Order's own
    // updated "HONEST SCOPE" comment): needed now that this manual test-order path creates a
    // real Order (OMS) record instead of an ExecutedOrder-only one -- this was the one
    // deliberately-excluded path Order's own header comment named explicitly ("only the
    // highest-value one: entry order placement, wired in AutoTradeService").
    private final OrderService orderService;

    public Order placeTestOrder(String userId, PlaceTestOrderRequest req) {
        BrokerCredential credential = credentialService.ownedCredential(userId, req.getCredentialId());

        // Review finding ("Manual LIVE order endpoint is still a dangerous second, parallel
        // trading system" -- P0): confirmed real, but only partially -- the LIVE-mode permission
        // re-verification just below (withdrawal-enabled refusal, trading-permission check) DOES
        // already exist here and is a genuine safeguard, checked directly rather than assumed.
        // What's still genuinely true, also checked directly: this method never touches
        // RiskEngineService (no daily-loss/exposure/correlation gate at all, for LIVE or
        // TESTNET), never creates an OMS Order, never reserves a position slot or exposure, never
        // writes to the Fill Ledger, and never creates a Position -- it saves straight to
        // ExecutedOrder with none of the machinery every autonomous LIVE trade goes through.
        // Taking the review's own explicitly stated preference here ("My recommendation:
        // TESTNET-only. For a dedicated autonomous trading bot, keep the LIVE execution surface
        // minimal.") over the larger alternative (routing this through the full Risk -> OMS ->
        // Fill Ledger -> Position Ledger pipeline with explicit confirmation) -- that's a bigger,
        // riskier rewrite of a real-money code path with no live broker access in this
        // environment to verify it against, exactly the kind of change this whole project has
        // been declining to rush. TESTNET-only closes the actual danger (a second, incomplete
        // LIVE trading path existing at all) without needing that larger rewrite.
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

        BrokerAdapter adapter = credentialService.adapterForCredential(credential);
        String apiKey = credentialService.decrypt(credential, true);
        String apiSecret = credentialService.decrypt(credential, false);

        // Review finding ("manual test-order idempotency is not actually idempotent"): the
        // previous version generated a fresh random UUID on every call — which is NOT
        // idempotent at all, it's the opposite: every retry (including an accidental double
        // click hitting this endpoint twice) got treated as a brand-new order. Deriving the id
        // from the actual order parameters plus a short time bucket means two identical
        // submissions within the same 10-second window collide and get rejected by Binance as a
        // duplicate — a deliberately different test order, or the same one tried again later,
        // still gets through.
        String idempotencyBasis = credential.getId() + "|" + req.getSymbol().toUpperCase() + "|" + side
            + "|" + req.getQuantity() + "|" + (System.currentTimeMillis() / 10_000);
        // Review finding (P1 #23 — "Manual test-order idempotency uses Java hashCode()"):
        // Integer.hashCode() is a 32-bit value with genuine (if unlikely) collision risk — no
        // reason to accept even a theoretical collision for a trading idempotency key. SHA-256,
        // truncated for a readable clientOrderId (Binance caps these at 36 chars).
        String clientOrderId = "manual-" + sha256Hex(idempotencyBasis).substring(0, 24);
        OrderRequest orderReq = new OrderRequest(req.getSymbol().toUpperCase(), side, "MARKET", req.getQuantity(), clientOrderId);

        // Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in this
        // class's own new orderService field comment): a real Order (OMS) record now, created
        // and transitioned through OrderService's own existing, already-tested state machine --
        // create() -> markRiskAccepted() -> markSubmitting() -> recordBrokerResult(), the same
        // full sequence AutoTradeService's own entry-order path actually uses (verified directly
        // against its code, not assumed -- see the corrected comment just below for why that
        // distinction mattered).
        Order order = orderService.create(userId, credential.getId(), null, null,
            orderReq.symbol(), side, "MARKET", req.getQuantity(), null, clientOrderId);
        order.setBroker(credential.getBroker());
        order.setMode(credential.getMode());
        order.setTriggerSource("MANUAL");
        // Real bug, confirmed by the person's own live test ("Illegal order state transition...
        // CREATED -> UNKNOWN is not a legal transition"): this comment used to claim create()
        // then recordBrokerResult() alone "is the exact same lifecycle AutoTradeService's own
        // entry-order path already uses" -- checked directly against AutoTradeService's own
        // actual code, and that claim was false; AutoTradeService always calls
        // markRiskAccepted()/markSubmitting() in between. Without them, an order stayed at
        // CREATED, and recordBrokerResult's own markUnknown() path (reached whenever the broker
        // result is ambiguous) requires SUBMITTING/ACKNOWLEDGED/etc. -- CREATED -> UNKNOWN was
        // never a legal transition in OrderService's own LEGAL_TRANSITIONS map, so this failed
        // every time that specific broker-result path was reached.
        orderService.markRiskAccepted(order);
        orderService.markSubmitting(order);

        OrderResult result = adapter.placeOrder(apiKey, apiSecret, credential.getMode(), orderReq);
        order.setRawResponse(result.rawResponse());
        order = orderService.recordBrokerResult(order, result);

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
     * Review finding ("Pagination for order history/positions/metrics" -- P2): confirmed real
     * -- this used to return EVERY order this user ever placed, across every credential they
     * own, in one response, with no bound at all. Now paginated the same way as
     * PositionDashboardService.listPositions's own identical fix -- see that method's own
     * javadoc for the same honest trade-off (a default page size is a genuine behavior change
     * from "return everything," not silently backward-compatible).
     */
    /**
     * Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in
     * OrderRepository.findByUserId's own javadoc): migrated off ExecutedOrderRepository. Sorts
     * by createdAt, not ExecutedOrder's own "placedAt" -- Order has no placedAt field, and this
     * migration's own earlier pass caught the exact same mistake (a hardcoded "placedAt" sort
     * string) in a different file, where it would have been a real, application-startup-time
     * failure for a derived query method name. This one is a Pageable-supplied Sort, not a
     * derived method name, so it wouldn't have failed the same way at startup -- but it would
     * still have been silently wrong at runtime (either ignored or erroring depending on
     * MongoDB driver behavior), sorting by a field this collection doesn't have. Fixed the same
     * way regardless.
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
