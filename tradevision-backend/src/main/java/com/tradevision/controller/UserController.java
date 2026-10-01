package com.tradevision.controller;

import com.tradevision.dto.*;
import com.tradevision.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Review finding ("Frontend authentication migration is incomplete and currently breaks
 * authenticated APIs" -- P0): confirmed real and fixed. This controller's own
 * @RequestHeader("Authorization") String token required Spring to find that literal header on
 * every request -- but the frontend no longer sends one at all once the access token lives only
 * in an HttpOnly cookie (this class's own interceptor only sets the header when a localStorage
 * token exists, which is never true anymore). Spring rejects a missing required header with its
 * own 400 before this method's body ever runs, regardless of whether the request was genuinely
 * authenticated via the cookie -- meaning every endpoint here was broken for a cookie-only
 * session, silently, since the SecurityContext itself was already correctly authenticated the
 * whole time.
 *
 * Fixed at the root cause, not by re-adding a header the frontend doesn't send: identity now
 * comes from Spring Security's own SecurityContext via @AuthenticationPrincipal, which the JWT
 * filter (SecurityConfig's own jwtFilter) already populates with the bare userId string as the
 * authentication's principal -- confirmed directly by reading that filter's own
 * `new UsernamePasswordAuthenticationToken(userId, null, ...)` call, not assumed. Every endpoint
 * here sits behind `.anyRequest().authenticated()` in SecurityConfig, so Spring Security's own
 * authorization filter has already rejected any request that didn't authenticate before this
 * controller is ever reached -- @AuthenticationPrincipal cannot see a null/anonymous principal
 * here by construction, not by convention.
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
// Review finding ("@CrossOrigin still has hardcoded localhost origins" -- external review,
// thirty-fifth pass, P2, full context in NewsController's own identical fix): removed --
// CorsConfig's own global CorsFilter already covers this endpoint.
public class UserController {

    private final AuthService authService;

    @GetMapping("/profile")
    public ResponseEntity<?> getProfile(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(authService.getProfile(userId));
    }

    @PostMapping("/favorites")
    public ResponseEntity<?> toggleFavorite(
        @AuthenticationPrincipal String userId,
        @RequestBody FavoriteRequest req) {
        return ResponseEntity.ok(authService.toggleFavorite(userId, req));
    }

    /**
     * P2-14 fix ("WebhookAlertService/User.alertWebhookUrl: no endpoint sets the webhook, feature
     * is dead" -- external review, full context in AuthService's own webhookAlertService field
     * javadoc): the actual missing endpoint. Accepts {"url": "..."} -- an empty/missing url
     * clears the webhook (AuthService.setAlertWebhookUrl's own established behavior).
     */
    @PutMapping("/alert-webhook")
    public ResponseEntity<?> setAlertWebhook(
        @AuthenticationPrincipal String userId,
        @RequestBody java.util.Map<String, String> body) {
        return ResponseEntity.ok(authService.setAlertWebhookUrl(userId, body.get("url")));
    }
}
