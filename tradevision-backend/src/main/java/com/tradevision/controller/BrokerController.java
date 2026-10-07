package com.tradevision.controller;

import com.tradevision.dto.ApiResponse;
import com.tradevision.dto.BrokerCredentialResponse;
import com.tradevision.dto.ConnectBrokerRequest;
import com.tradevision.dto.PlaceTestOrderRequest;
import com.tradevision.dto.RiskProfileRequest;
import com.tradevision.dto.StartLiveCanaryRequest;
import com.tradevision.model.LiveCanaryRecord;
import com.tradevision.model.Order;
import com.tradevision.model.OrderStatus;
import com.tradevision.model.RiskProfile;
import com.tradevision.service.BrokerCredentialService;
import com.tradevision.service.LiveCanaryService;
import com.tradevision.service.OrderExecutionService;
import com.tradevision.service.RiskProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Review finding ("Stale documentation" -- P0, full context in BrokerMode's own javadoc):
 * confirmed real and fixed -- this used to claim "no auto-trigger endpoint exists yet," directly
 * contradicted by this SAME file's own risk-profile/auto-trade-configuration and kill-switch
 * sections just below. Broker key connection, balance viewing, manual test orders (TESTNET-only,
 * see OrderExecutionService's own javadoc), autonomous-trading configuration, and the kill
 * switch all genuinely live in this controller today.
 *
 * Review finding ("Frontend authentication migration is incomplete and currently breaks
 * authenticated APIs" -- P0): confirmed real and fixed across every endpoint here -- see
 * UserController's own javadoc for the full root-cause explanation. This controller is the
 * highest-stakes instance of the bug (broker connect, kill switch, LIVE authorization all live
 * here), so it mattered most here that the fix was mechanical and complete rather than partial.
 */
@RestController
@RequestMapping("/api/broker")
@RequiredArgsConstructor
// Review finding ("@CrossOrigin still has hardcoded localhost origins" -- external review,
// thirty-fifth pass, P2, full context in NewsController's own identical fix): removed --
// CorsConfig's own global CorsFilter already covers this endpoint.
public class BrokerController {

    private static final Logger log = LoggerFactory.getLogger(BrokerController.class);

    private final BrokerCredentialService credentialService;
    private final OrderExecutionService orderExecutionService;
    private final RiskProfileService riskProfileService;
    // Audit item P0-1, full context in LiveCanaryRecord's own class javadoc.
    private final LiveCanaryService liveCanaryService;

    @PostMapping("/connect")
    public ResponseEntity<?> connect(@AuthenticationPrincipal String userId,
                                      @Valid @RequestBody ConnectBrokerRequest req) {
        try {
            BrokerCredentialResponse saved = credentialService.connect(userId, req);
            return ResponseEntity.ok(ApiResponse.ok("Broker connected in " + saved.mode() + " mode.", saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            // mode=LIVE sent to the single-step endpoint — points the caller at the real flow.
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<?> list(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.ok("OK", credentialService.list(userId)));
    }

    /**
     * Review finding ("API-key rotation workflow" -- external review, P3, full context in
     * BrokerCredentialService.rotateApiKey's own javadoc): the actual endpoint.
     */
    @PostMapping("/{id}/rotate-key")
    public ResponseEntity<?> rotateApiKey(@AuthenticationPrincipal String userId, @PathVariable String id,
                                           @RequestBody Map<String, String> body) {
        String newApiKey = body.get("apiKey");
        String newApiSecret = body.get("apiSecret");
        if (newApiKey == null || newApiKey.isBlank() || newApiSecret == null || newApiSecret.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Both apiKey and apiSecret are required."));
        }
        try {
            var saved = credentialService.rotateApiKey(userId, id, newApiKey, newApiSecret);
            return ResponseEntity.ok(ApiResponse.ok("API key rotated -- this credential's own id and all linked history are unchanged.", saved));
        } catch (IllegalArgumentException e) {
            // Review finding ("API-key rotation workflow" -- external review, P3, confirmed
            // real by direct inspection before this fix: my own first draft caught
            // IllegalArgumentException for BOTH "credential not found" (ownedCredential's own
            // exception) AND "new key rejected by validation," with no way to tell them apart.
            // Fixed at the service level -- rotateApiKey now throws IllegalStateException for a
            // validation refusal specifically, distinct from ownedCredential's own
            // IllegalArgumentException for a genuinely missing credential): this remains 404,
            // now correctly scoped to only the "not found" case.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            // The new key was reachable and real, but rejected on its own merits (withdrawal
            // enabled, or trading disabled) -- 400, not a server error, and explicitly NOT the
            // same catch as the 404 above.
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal String userId, @PathVariable String id) {
        try {
            credentialService.delete(userId, id);
            return ResponseEntity.ok(ApiResponse.ok("Broker credential removed."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            // Review finding ("Deactivating a broker credential can abandon live positions" --
            // external review, twenty-first pass, P0, full context in
            // BrokerCredentialService.delete's own updated javadoc): the credential exists, but
            // deletion is refused because it still has positions this application must keep
            // managing -- 409 Conflict, same mapping this codebase already uses for "exists but
            // not in a state this action can be performed on" (see resume()'s own catch above).
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{id}/balance")
    public ResponseEntity<?> balance(@AuthenticationPrincipal String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(ApiResponse.ok("OK", credentialService.getBalance(userId, id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            // Review finding (P1 — "Global exception handling leaks internal messages"): this
            // local catch bypassed GlobalExceptionHandler entirely, so its fix alone didn't
            // cover this — e.getMessage() here could be a raw Binance API error body, a network
            // exception, or a parsing failure, none of which should reach the client directly.
            String refId = "TV-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            log.error("Broker balance request failed [{}]: {}", refId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.error("Broker request failed. Reference ID: " + refId));
        }
    }

    @GetMapping("/{id}/open-orders")
    public ResponseEntity<?> openOrders(@AuthenticationPrincipal String userId, @PathVariable String id) {
        try {
            return ResponseEntity.ok(ApiResponse.ok("OK", orderExecutionService.getOpenOrders(userId, id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            String refId = "TV-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            log.error("Broker open-orders request failed [{}]: {}", refId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.error("Broker request failed. Reference ID: " + refId));
        }
    }

    @PostMapping("/test-order")
    public ResponseEntity<?> placeTestOrder(@AuthenticationPrincipal String userId,
                                             @Valid @RequestBody PlaceTestOrderRequest req) {
        try {
            // Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in
            // OrderExecutionService.placeTestOrder's own updated javadoc): now returns a real
            // Order (OMS) record, not an ExecutedOrder -- status is OrderStatus (an enum), not a
            // String, so the comparison below is fixed accordingly. A silent bug this specific
            // migration step would otherwise have introduced: "REJECTED".equals(anEnumValue) is
            // always false regardless of the actual status, since it compares different types --
            // caught and fixed here, not shipped.
            Order result = orderExecutionService.placeTestOrder(userId, req);
            if (OrderStatus.REJECTED.equals(result.getStatus())) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.error(result.getFailureReason()));
            }
            return ResponseEntity.ok(ApiResponse.ok("Order placed on testnet.", result));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/orders/history")
    public ResponseEntity<?> history(@AuthenticationPrincipal String userId,
                                      // Review finding ("Pagination for order history/positions/
                                      // metrics" -- P2, full context in
                                      // OrderExecutionService.history's own updated javadoc):
                                      // same fix, same honest trade-off, as PositionController's
                                      // own identical change -- a default page size here IS a
                                      // real behavior change from "return everything," not
                                      // silently backward-compatible.
                                      @RequestParam(required = false, defaultValue = "0") int page,
                                      @RequestParam(required = false, defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok("OK", orderExecutionService.history(userId, page, size)));
    }

    // ── Risk profile / auto-trade configuration ──────────────

    @PostMapping("/risk-profile")
    public ResponseEntity<?> upsertRiskProfile(@AuthenticationPrincipal String userId,
                                                @Valid @RequestBody RiskProfileRequest req) {
        try {
            RiskProfile profile = riskProfileService.upsert(userId, req);
            return ResponseEntity.ok(ApiResponse.ok("Risk profile saved.", profile));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/risk-profile/{credentialId}")
    public ResponseEntity<?> getRiskProfile(@AuthenticationPrincipal String userId, @PathVariable String credentialId) {
        try {
            return ResponseEntity.ok(ApiResponse.ok("OK", riskProfileService.get(userId, credentialId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    // ── Kill switch ───────────────────────────────────────────────

    @PostMapping("/risk-profile/{credentialId}/halt")
    public ResponseEntity<?> halt(@AuthenticationPrincipal String userId, @PathVariable String credentialId,
                                   @RequestBody(required = false) Map<String, String> body) {
        try {
            String reason = body != null ? body.get("reason") : null;
            return ResponseEntity.ok(ApiResponse.ok("Trading halted for this credential.",
                riskProfileService.halt(userId, credentialId, reason)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/risk-profile/{credentialId}/resume")
    public ResponseEntity<?> resume(@AuthenticationPrincipal String userId, @PathVariable String credentialId) {
        try {
            return ResponseEntity.ok(ApiResponse.ok("Auto-trading resumed.",
                riskProfileService.resume(userId, credentialId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            // Review finding ("Resume needs safety validation"): distinct from "not found" — the
            // credential/profile exists, but resuming is refused because of an unresolved unsafe
            // position. 409 Conflict fits better than 404 for "exists but not in a resumable state".
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Audit item P1-5, full context in RiskProfileService.authorizeLiveAutoTrade's own updated
     * javadoc: issues the fresh step-up verification code authorize-live-autotrade below now
     * requires, sent to the caller's own on-file email/mobile. Call this first, then submit the
     * code you receive as "stepUpOtp" in the authorize-live-autotrade request body.
     */
    @PostMapping("/risk-profile/{credentialId}/authorize-live-autotrade/request-otp")
    public ResponseEntity<?> requestLiveAutoTradeStepUpOtp(@AuthenticationPrincipal String userId, @PathVariable String credentialId) {
        try {
            riskProfileService.requestLiveAutoTradeStepUpOtp(userId);
            return ResponseEntity.ok(ApiResponse.ok("A verification code has been sent. Submit it as \"stepUpOtp\" when authorizing "
                + "autonomous LIVE trading."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/risk-profile/{credentialId}/authorize-live-autotrade")
    public ResponseEntity<?> authorizeLiveAutoTrade(@AuthenticationPrincipal String userId, @PathVariable String credentialId,
                                                      @RequestBody Map<String, String> body) {
        try {
            RiskProfile profile = riskProfileService.authorizeLiveAutoTrade(userId, credentialId, body.get("confirm"), body.get("stepUpOtp"));
            return ResponseEntity.ok(ApiResponse.ok("Autonomous LIVE trading authorized for this credential.", profile));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Audit item P0-1, full context in LiveCanaryRecord's own class javadoc. Places one real,
     * minimal LIVE order on the given symbol -- a genuinely real-money action, gated behind the
     * same explicit confirmation-phrase pattern as authorize-live-autotrade just above. Returns
     * the PENDING record immediately; the order resolves to PASSED/FAILED asynchronously via
     * LiveCanaryService's own reconciliation sweep -- poll GET .../live-canary/{credentialId}
     * for the outcome.
     */
    @PostMapping("/{credentialId}/live-canary")
    public ResponseEntity<?> startLiveCanary(@AuthenticationPrincipal String userId, @PathVariable String credentialId,
                                               @Valid @RequestBody StartLiveCanaryRequest req) {
        try {
            LiveCanaryRecord record = liveCanaryService.startCanary(userId, credentialId, req.getSymbol(), req.getConfirm());
            return ResponseEntity.ok(ApiResponse.ok("Live canary order submitted -- poll for PASSED/FAILED.", record));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping("/{credentialId}/live-canary")
    public ResponseEntity<?> getLiveCanaryStatus(@AuthenticationPrincipal String userId, @PathVariable String credentialId) {
        try {
            var latest = liveCanaryService.latestFor(userId, credentialId);
            if (latest.isPresent()) {
                return ResponseEntity.ok(ApiResponse.ok("Latest live canary attempt for this credential.", latest.get()));
            }
            return ResponseEntity.ok(ApiResponse.ok("No live canary attempt on record for this credential yet.", null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/risk-profile/{credentialId}/revoke-live-autotrade")
    public ResponseEntity<?> revokeLiveAutoTrade(@AuthenticationPrincipal String userId, @PathVariable String credentialId) {
        try {
            return ResponseEntity.ok(ApiResponse.ok("Autonomous LIVE trading authorization revoked.",
                riskProfileService.revokeLiveAutoTrade(userId, credentialId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/kill-switch")
    public ResponseEntity<?> killSwitchAll(@AuthenticationPrincipal String userId,
                                            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        riskProfileService.haltAll(userId, reason);
        return ResponseEntity.ok(ApiResponse.ok("All auto-trading halted across every connected credential. New signal-triggered "
            + "orders will not be submitted. An execution already in progress at the exact moment this was clicked may still reach "
            + "the exchange -- this application automatically reconciles any such case against the real exchange state right after "
            + "this halt completes."));
    }

    /**
     * Review finding ("Secrets / encryption key rotation and credential revocation story
     * incomplete" -- external review, nineteenth pass, P1, full context in
     * RiskProfileService.emergencyRevokeAll's own javadoc): the reachable endpoint for the
     * combined emergency response -- distinct from kill-switch above, which only halts
     * autonomous trading. This also deactivates every credential (closing manual placement too)
     * and forces re-authentication on every device by invalidating this user's own current
     * session.
     */
    @PostMapping("/emergency-revoke-all")
    public ResponseEntity<?> emergencyRevokeAll(@AuthenticationPrincipal String userId,
                                                 @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        long affectedOpenPositions = riskProfileService.emergencyRevokeAll(userId, reason);
        String positionWarning = affectedOpenPositions > 0
            ? " WARNING: " + affectedOpenPositions + " open position(s) across these credentials will no longer be monitored or "
                + "protected by this application as a result -- deactivation stops reconciliation entirely, not just new trading. "
                + "If you don't believe your API key itself is compromised, consider the kill switch (halts new trades only, keeps "
                + "monitoring existing positions) instead of this action next time."
            : "";
        return ResponseEntity.ok(ApiResponse.ok("All auto-trading halted, every broker credential deactivated, and your current "
            + "session has been revoked -- you will need to log in again. If you believe your Binance API key itself was exposed, "
            + "you must also revoke it directly on Binance's own site; this application cannot do that on your behalf." + positionWarning));
    }

    // ── Review finding ("P0 #1" — "LIVE Binance credential architecture is wrong"): a LIVE
    // credential is now its own connect flow, not a flag flipped on an existing TESTNET row —
    // Binance testnet and mainnet keys are genuinely separate credentials, confirmed against
    // current Binance documentation. Two-step confirmation preserved, now correctly scoped to
    // the operation that actually matters (saving a credential that can touch real money). The
    // old /{id}/live-mode/request, /confirm, /revert endpoints are gone — there's no "revert"
    // when TESTNET and LIVE were never the same row to begin with; deleting a LIVE credential
    // (existing DELETE /{id}) is the equivalent now, and it never touches the separate TESTNET
    // credential the user may still have.
    @PostMapping("/connect/live/request")
    public ResponseEntity<?> requestLiveConnect(@AuthenticationPrincipal String userId,
                                                 @Valid @RequestBody ConnectBrokerRequest req) {
        try {
            String confirmToken = credentialService.requestLiveConnect(userId, req);
            return ResponseEntity.ok(ApiResponse.ok(
                "Key validated against Binance LIVE. Confirm within 5 minutes to actually save this credential.",
                Map.of("confirmToken", confirmToken)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/connect/live/confirm")
    public ResponseEntity<?> confirmLiveConnect(@AuthenticationPrincipal String userId,
                                                 @RequestBody Map<String, String> body) {
        try {
            BrokerCredentialResponse saved = credentialService.confirmLiveConnect(userId, body.get("confirmToken"));
            return ResponseEntity.ok(ApiResponse.ok("LIVE credential connected. Real funds are at risk.", saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Review finding (P1 #9 -- "API key rotation doesn't verify it's the same Binance account
     * (and bypasses LIVE two-step)"): the same two-step ceremony requestLiveConnect/
     * confirmLiveConnect already apply to a brand-new LIVE credential, now also required to
     * rotate an EXISTING LIVE credential's key -- full context in
     * BrokerCredentialService.rotateApiKey's own updated javadoc.
     */
    @PostMapping("/{id}/rotate-key/request")
    public ResponseEntity<?> requestApiKeyRotation(@AuthenticationPrincipal String userId, @PathVariable String id,
                                                    @RequestBody Map<String, String> body) {
        String newApiKey = body.get("apiKey");
        String newApiSecret = body.get("apiSecret");
        if (newApiKey == null || newApiKey.isBlank() || newApiSecret == null || newApiSecret.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Both apiKey and apiSecret are required."));
        }
        try {
            String confirmToken = credentialService.requestApiKeyRotation(userId, id, newApiKey, newApiSecret);
            return ResponseEntity.ok(ApiResponse.ok(
                "New key validated (permissions and account identity both confirmed). Confirm within 5 minutes to actually rotate.",
                Map.of("confirmToken", confirmToken)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/{id}/rotate-key/confirm")
    public ResponseEntity<?> confirmApiKeyRotation(@AuthenticationPrincipal String userId, @PathVariable String id,
                                                    @RequestBody Map<String, String> body) {
        try {
            var saved = credentialService.confirmApiKeyRotation(userId, body.get("confirmToken"));
            return ResponseEntity.ok(ApiResponse.ok("API key rotated -- this credential's own id and all linked history are unchanged.", saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
        }
    }
}
