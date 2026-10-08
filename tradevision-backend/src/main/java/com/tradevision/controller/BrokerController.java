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
 * Manages a user's exchange broker credentials and the trading actions performed through them:
 * connecting and rotating API keys, viewing balances and order history, placing manual test
 * orders on testnet, configuring risk profiles for autonomous trading, and the kill-switch /
 * emergency-revocation controls for stopping trading quickly.
 *
 * Identity for every endpoint here comes from Spring Security's {@code SecurityContext} via
 * {@code @AuthenticationPrincipal}, populated by the JWT filter from the authenticated session;
 * all endpoints sit behind {@code .anyRequest().authenticated()} in SecurityConfig.
 */
@RestController
@RequestMapping("/api/broker")
@RequiredArgsConstructor
// CORS is handled centrally by CorsConfig's global CorsFilter; no per-controller
// @CrossOrigin is needed here.
public class BrokerController {

    private static final Logger log = LoggerFactory.getLogger(BrokerController.class);

    private final BrokerCredentialService credentialService;
    private final OrderExecutionService orderExecutionService;
    private final RiskProfileService riskProfileService;
    // Drives the live-canary order flow: a small real-money probe order used to validate a
    // LIVE credential end-to-end before autonomous trading is authorized on it.
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
     * Rotates the API key/secret pair on an existing broker credential in place, keeping the
     * credential's id and linked history (positions, orders) unchanged.
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
            // Credential not found for this user.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            // The new key was reachable and real, but rejected on its own merits (withdrawal
            // enabled, or trading disabled).
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
            // The credential exists but still has open positions this application must keep
            // managing, so deletion is refused until those are closed.
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
            // e.getMessage() here could be a raw Binance API error body, a network exception, or
            // a parsing failure -- none of which should reach the client directly, so log the
            // detail server-side and return only an opaque reference id.
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
            // placeTestOrder returns an OMS Order record; status is the OrderStatus enum, not a
            // String, so comparing it with .equals(OrderStatus.REJECTED) below is required --
            // comparing against a String literal would never match.
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
                                      // Paginated to bound response size for accounts with long
                                      // order histories; callers that relied on getting every
                                      // record back in one call need to page through explicitly.
                                      @RequestParam(required = false, defaultValue = "0") int page,
                                      @RequestParam(required = false, defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok("OK", orderExecutionService.history(userId, page, size)));
    }

    // ── Risk profile / auto-trade configuration ──────────────

    /**
     * Sends a step-up verification code required before editing a LIVE credential's risk
     * profile; call this first, then submit the code as "stepUpOtp" in the upsertRiskProfile
     * request body. Not required for a TESTNET/PAPER credential, where upsert() never checks it.
     */
    @PostMapping("/risk-profile/{credentialId}/request-otp")
    public ResponseEntity<?> requestRiskProfileStepUpOtp(@AuthenticationPrincipal String userId, @PathVariable String credentialId) {
        try {
            riskProfileService.requestRiskProfileStepUpOtp(userId);
            return ResponseEntity.ok(ApiResponse.ok("A verification code has been sent. Submit it as \"stepUpOtp\" when saving "
                + "this risk profile."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
    }

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
            // The credential/profile exists but resuming is refused because of an unresolved
            // unsafe position.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Sends the step-up verification code required to authorize autonomous LIVE trading, to the
     * caller's on-file email/mobile. Call this first, then submit the code as "stepUpOtp" in the
     * authorize-live-autotrade request body.
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
     * Places one real, minimal LIVE order on the given symbol as a real-money sanity check on a
     * newly connected credential, gated behind the same explicit confirmation-phrase pattern as
     * authorize-live-autotrade. Returns the PENDING record immediately; the order resolves to
     * PASSED/FAILED asynchronously via LiveCanaryService's reconciliation sweep -- poll
     * GET .../live-canary/{credentialId} for the outcome.
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
     * Full emergency response, distinct from the kill switch above (which only halts autonomous
     * trading): deactivates every broker credential for this user, closing off manual order
     * placement too, and invalidates the current session to force re-authentication everywhere.
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

    // Connecting a LIVE credential is its own flow rather than a flag on an existing TESTNET
    // credential, since Binance testnet and mainnet keys are genuinely separate credentials.
    // Two-step confirmation (request then confirm within a short window) guards the operation
    // that can touch real money. There is no "revert to testnet" here -- a LIVE credential never
    // shares a row with a TESTNET one, so removing LIVE access means deleting the LIVE
    // credential (DELETE /{id}), which leaves any separate TESTNET credential untouched.
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

    /**
     * Sends the step-up verification code required before confirming a LIVE connection; call
     * this before confirmLiveConnect, then submit the code as "stepUpOtp" in that request body.
     */
    @PostMapping("/connect/live/request-otp")
    public ResponseEntity<?> requestCredentialChangeStepUpOtpForConnect(@AuthenticationPrincipal String userId) {
        try {
            credentialService.requestCredentialChangeStepUpOtp(userId);
            return ResponseEntity.ok(ApiResponse.ok("A verification code has been sent. Submit it as \"stepUpOtp\" when confirming "
                + "this LIVE connection."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/connect/live/confirm")
    public ResponseEntity<?> confirmLiveConnect(@AuthenticationPrincipal String userId,
                                                 @RequestBody Map<String, String> body) {
        try {
            BrokerCredentialResponse saved = credentialService.confirmLiveConnect(userId, body.get("confirmToken"), body.get("stepUpOtp"));
            return ResponseEntity.ok(ApiResponse.ok("LIVE credential connected. Real funds are at risk.", saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * Sends the step-up verification code required to rotate an existing LIVE credential's API
     * key, using the same two-step request/confirm ceremony and step-up purpose as the LIVE
     * connect flow, so the new key is verified against the same Binance account before it
     * replaces the old one. Call this before .../rotate-key/confirm.
     */
    @PostMapping("/{id}/rotate-key/request-otp")
    public ResponseEntity<?> requestCredentialChangeStepUpOtpForRotation(@AuthenticationPrincipal String userId, @PathVariable String id) {
        try {
            credentialService.requestCredentialChangeStepUpOtp(userId);
            return ResponseEntity.ok(ApiResponse.ok("A verification code has been sent. Submit it as \"stepUpOtp\" when confirming "
                + "this key rotation."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
    }

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
            var saved = credentialService.confirmApiKeyRotation(userId, body.get("confirmToken"), body.get("stepUpOtp"));
            return ResponseEntity.ok(ApiResponse.ok("API key rotated -- this credential's own id and all linked history are unchanged.", saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
        }
    }
}
