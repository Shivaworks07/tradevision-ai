package com.tradevision.controller;

import com.tradevision.dto.*;
import com.tradevision.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Manages the authenticated user's profile, favorites, and alert webhook settings.
 *
 * Identity comes from Spring Security's SecurityContext via @AuthenticationPrincipal, which the
 * JWT filter (SecurityConfig's jwtFilter) populates with the authenticated userId as the
 * principal. Access relies on the cookie-based session rather than a bearer header, since every
 * endpoint here sits behind `.anyRequest().authenticated()` in SecurityConfig.
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
// CORS is handled centrally by CorsConfig's global CorsFilter; no per-controller
// @CrossOrigin is needed here.
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
     * Sets or clears the URL that trade/price alerts are posted to. Accepts {"url": "..."}; an
     * empty or missing url clears the webhook.
     */
    @PutMapping("/alert-webhook")
    public ResponseEntity<?> setAlertWebhook(
        @AuthenticationPrincipal String userId,
        @RequestBody java.util.Map<String, String> body) {
        return ResponseEntity.ok(authService.setAlertWebhookUrl(userId, body.get("url")));
    }
}
