package com.tradevision.controller;

import com.tradevision.dto.*;
import com.tradevision.service.AuthService;
import com.tradevision.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * Handles registration/login via OTP, token issuance and refresh, logout, profile, and account
 * existence checks.
 *
 * Operates in dual-mode for token delivery: every token-issuing endpoint sets HttpOnly cookies
 * alongside the existing JSON response body, so a frontend that reads tokens from the body keeps
 * working while one that relies on cookies also works. SecurityConfig's jwtFilter accepts either
 * the Authorization header or the cookie, preferring the header when both are present.
 *
 * Because HttpOnly cookies are sent by the browser automatically on every request to this
 * origin (unlike a header-based token), SameSite=Strict is set on both auth cookies to block
 * cross-site CSRF while still allowing the same-site frontend's own calls through.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
// CORS is handled centrally by CorsConfig's global CorsFilter; no per-controller
// @CrossOrigin is needed here.
public class AuthController {

    private final AuthService authService;
    private final JwtUtil     jwt;
    // Per-IP throttle for OTP requests, so a single IP can't cycle through many identifiers to
    // send unlimited OTP requests. Reuses the same spoofing-aware IP extraction as
    // ProxyController rather than a second, independent implementation.
    private final com.tradevision.service.DistributedRateLimitService distributedRateLimitService;
    @org.springframework.beans.factory.annotation.Value("${app.proxy.trust-forwarded-for:false}")
    private boolean trustForwardedFor;
    // Restricts which proxy/LB source ranges X-Forwarded-For is trusted from, once
    // trustForwardedFor is enabled; same convention as ProxyController's identical field.
    @org.springframework.beans.factory.annotation.Value("${app.proxy.trusted-proxy-cidrs:}")
    private String trustedProxyCidrs;
    /**
     * Per-IP OTP request cap, keyed purely by client IP with no identifier component, so it
     * bounds scripted enumeration/cycling from one source without being the primary defense
     * against any single account being targeted -- OtpRateLimitService (per-identifier) and the
     * per-identifier cooldown on resend already cover that. The default is generous because a
     * shared IP (corporate office, campus Wi-Fi, mobile-carrier CGNAT pool) can legitimately
     * have many distinct real users behind it, and a low cap would let a handful of unrelated
     * logins lock out everyone else sharing that IP. Configurable so a deployment behind a known
     * large shared-IP population can tune it without a code change.
     */
    @org.springframework.beans.factory.annotation.Value("${app.auth.otp-requests-per-ip-per-hour:150}")
    private int maxOtpRequestsPerIpPerWindow;
    private static final long OTP_IP_WINDOW_SECONDS = 3600;

    private static final String ACCESS_COOKIE = "tv_access_token";
    private static final String REFRESH_COOKIE = "tv_refresh_token";

    // Secure requires HTTPS: defaults to true for safety in a real deployment, overridable for
    // local HTTP-only dev, the same configurable-with-a-safe-default pattern used elsewhere in
    // this codebase (e.g. mail.enabled/allow-console-fallback).
    @Value("${app.auth.cookie-secure:true}")
    private boolean cookieSecure;

    private void setAuthCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        ResponseCookie access = ResponseCookie.from(ACCESS_COOKIE, accessToken)
            .httpOnly(true).secure(cookieSecure).sameSite("Strict").path("/")
            .maxAge(jwt.getAccessExpirationMs() / 1000).build();
        ResponseCookie refresh = ResponseCookie.from(REFRESH_COOKIE, refreshToken)
            .httpOnly(true).secure(cookieSecure).sameSite("Strict").path("/")
            .maxAge(jwt.getRefreshExpirationMs() / 1000).build();
        response.addHeader(HttpHeaders.SET_COOKIE, access.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refresh.toString());
    }

    private void clearAuthCookies(HttpServletResponse response) {
        ResponseCookie access = ResponseCookie.from(ACCESS_COOKIE, "")
            .httpOnly(true).secure(cookieSecure).sameSite("Strict").path("/").maxAge(0).build();
        ResponseCookie refresh = ResponseCookie.from(REFRESH_COOKIE, "")
            .httpOnly(true).secure(cookieSecure).sameSite("Strict").path("/").maxAge(0).build();
        response.addHeader(HttpHeaders.SET_COOKIE, access.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refresh.toString());
    }

    /**
     * Sets the access/refresh cookies from a TokenResponse, then clears both token fields on
     * that same object so the tokens never appear in the JSON response body, only in the
     * HttpOnly cookies. Mutates in place (rather than building a narrower response type) since
     * every caller already holds a reference to the same object it's about to return.
     */
    private void setCookiesIfTokenResponse(HttpServletResponse response, ApiResponse<?> apiResponse) {
        if (apiResponse != null && apiResponse.getData() instanceof TokenResponse tr) {
            setAuthCookies(response, tr.getAccessToken(), tr.getRefreshToken());
            tr.setAccessToken(null);
            tr.setRefreshToken(null);
        }
    }

    // ── OTP flow ──────────────────────────────────────────────
    /** Same spoofing-aware extraction as ProxyController.rateLimited's own private helper -- see
     *  that method's own comment for why trustForwardedFor defaults to false. */
    private String clientIp(HttpServletRequest req) {
        return com.tradevision.util.ClientIpResolver.resolve(req, trustForwardedFor, trustedProxyCidrs);
    }

    @PostMapping("/register/initiate")
    public ResponseEntity<?> initRegister(@RequestBody Map<String,String> req, HttpServletRequest httpReq) {
        if (!distributedRateLimitService.allow("otp_initiate_by_ip", clientIp(httpReq), maxOtpRequestsPerIpPerWindow, OTP_IP_WINDOW_SECONDS)) {
            return ResponseEntity.status(429).body(ApiResponse.error("Too many OTP requests from this network. Please try again later."));
        }
        // Accept email or mobile
        String identifier = req.getOrDefault("email", req.get("mobile"));
        if (identifier == null) return ResponseEntity.badRequest().body(ApiResponse.error("Email is required"));
        return ResponseEntity.ok(authService.sendOtp(identifier, "REGISTER"));
    }

    @PostMapping("/login/initiate")
    public ResponseEntity<?> initLogin(@RequestBody Map<String,String> req, HttpServletRequest httpReq) {
        if (!distributedRateLimitService.allow("otp_initiate_by_ip", clientIp(httpReq), maxOtpRequestsPerIpPerWindow, OTP_IP_WINDOW_SECONDS)) {
            return ResponseEntity.status(429).body(ApiResponse.error("Too many OTP requests from this network. Please try again later."));
        }
        String identifier = req.getOrDefault("email", req.get("mobile"));
        if (identifier == null) return ResponseEntity.badRequest().body(ApiResponse.error("Email is required"));
        return ResponseEntity.ok(authService.sendOtp(identifier, "LOGIN"));
    }

    @PostMapping("/register/verify")
    public ResponseEntity<?> verifyRegister(@RequestBody OtpVerifyRequest req, HttpServletResponse response) {
        req.setPurpose("REGISTER");
        ApiResponse<?> result = authService.verifyOtp(req);
        setCookiesIfTokenResponse(response, result);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/login/verify")
    public ResponseEntity<?> verifyLogin(@RequestBody OtpVerifyRequest req, HttpServletResponse response) {
        req.setPurpose("LOGIN");
        ApiResponse<?> result = authService.verifyOtp(req);
        setCookiesIfTokenResponse(response, result);
        return ResponseEntity.ok(result);
    }

    // ── Token management ──────────────────────────────────────
    /**
     * Refreshes the access token using only the refresh token cookie -- never a request body --
     * so the refresh token never needs to surface in browser instrumentation, request logging,
     * debugging tools, or proxies; it lives only in the HttpOnly cookie the browser attaches
     * automatically.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String cookieRt,
                                      HttpServletResponse response) {
        if (cookieRt == null || cookieRt.isBlank()) return ResponseEntity.badRequest().body(ApiResponse.error("refreshToken required"));
        ApiResponse<?> result = authService.refreshToken(cookieRt);
        setCookiesIfTokenResponse(response, result);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@AuthenticationPrincipal String userId,
                                     HttpServletResponse response) {
        // /api/auth/** is public in SecurityConfig, so userId can legitimately still be null
        // here (an already-expired or never-valid session attempting to log out). Clearing
        // cookies unconditionally either way is correct and harmless.
        clearAuthCookies(response);
        if (userId == null) return ResponseEntity.badRequest().body(ApiResponse.error("Not authenticated."));
        return ResponseEntity.ok(authService.logout(userId));
    }

    // ── Profile ───────────────────────────────────────────────
    @GetMapping("/profile")
    public ResponseEntity<?> profile(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(authService.getProfile(userId));
    }

    @PutMapping("/profile")
    public ResponseEntity<?> updateProfile(
        @AuthenticationPrincipal String userId,
        @RequestBody UserDto req) {
        return ResponseEntity.ok(authService.updateProfile(userId, req));
    }

    /**
     * OTP resend is throttled both per-IP (same limit as initiate) and per-identifier (a short
     * cooldown), so neither a single IP cycling through many identifiers nor a single identifier
     * being hammered repeatedly (from one IP or many) can run up SMS/email provider cost
     * unthrottled.
     */
    private static final int MAX_OTP_RESENDS_PER_IDENTIFIER_PER_WINDOW = 1;
    private static final long OTP_RESEND_IDENTIFIER_COOLDOWN_SECONDS = 30;

    @PostMapping("/otp/resend")
    public ResponseEntity<?> resendOtp(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String mobile,
            @RequestParam(defaultValue = "LOGIN") String purpose,
            HttpServletRequest httpReq) {
        if (!distributedRateLimitService.allow("otp_initiate_by_ip", clientIp(httpReq), maxOtpRequestsPerIpPerWindow, OTP_IP_WINDOW_SECONDS)) {
            return ResponseEntity.status(429).body(ApiResponse.error("Too many OTP requests from this network. Please try again later."));
        }
        String identifier = email != null ? email : mobile;
        if (identifier == null || identifier.isBlank())
            return ResponseEntity.badRequest().body(ApiResponse.error("Email is required"));
        String normalizedIdentifier = identifier.trim().toLowerCase();
        if (!distributedRateLimitService.allow("otp_resend_by_identifier", normalizedIdentifier,
                MAX_OTP_RESENDS_PER_IDENTIFIER_PER_WINDOW, OTP_RESEND_IDENTIFIER_COOLDOWN_SECONDS)) {
            return ResponseEntity.status(429).body(ApiResponse.error("Please wait before requesting another code for this account."));
        }
        return ResponseEntity.ok(authService.sendOtp(normalizedIdentifier, purpose));
    }

    /**
     * Checks whether an email/mobile is already registered, used by a signup form's live
     * availability check. Rate-limited per-IP (same DistributedRateLimitService infrastructure
     * as the OTP endpoints) because this directly reveals account existence and would otherwise
     * let an attacker enumerate registered users at an unbounded pace; the response shape itself
     * (registered: true/false) stays the same since that's the endpoint's legitimate purpose --
     * only the rate at which it can be queried is bounded. The default is more generous than the
     * OTP limits since this is hit on every keystroke pause during signup, a legitimate pattern
     * OTP sending doesn't have. Configurable for the same shared-IP reasons as
     * maxOtpRequestsPerIpPerWindow above.
     */
    @org.springframework.beans.factory.annotation.Value("${app.auth.account-checks-per-ip-per-hour:200}")
    private int maxAccountCheckRequestsPerIpPerWindow;
    private static final long ACCOUNT_CHECK_IP_WINDOW_SECONDS = 3600;

    @GetMapping("/check-email")
    public ResponseEntity<?> checkEmail(@RequestParam String email, HttpServletRequest httpReq) {
        if (!distributedRateLimitService.allow("account_check_by_ip", clientIp(httpReq),
                maxAccountCheckRequestsPerIpPerWindow, ACCOUNT_CHECK_IP_WINDOW_SECONDS)) {
            return ResponseEntity.status(429).body(ApiResponse.error("Too many requests from this network. Please try again later."));
        }
        return ResponseEntity.ok(authService.checkEmail(email));
    }

    @GetMapping("/check-mobile")
    public ResponseEntity<?> checkMobile(@RequestParam String mobile, HttpServletRequest httpReq) {
        if (!distributedRateLimitService.allow("account_check_by_ip", clientIp(httpReq),
                maxAccountCheckRequestsPerIpPerWindow, ACCOUNT_CHECK_IP_WINDOW_SECONDS)) {
            return ResponseEntity.status(429).body(ApiResponse.error("Too many requests from this network. Please try again later."));
        }
        return ResponseEntity.ok(authService.checkMobile(mobile));
    }
}
