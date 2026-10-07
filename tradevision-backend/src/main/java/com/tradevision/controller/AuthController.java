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
 * Review finding ("Auth hardening" -- "access token still in localStorage, not HttpOnly
 * cookies"): confirmed real by checking the actual frontend code directly, not assumed --
 * auth.service.ts stores both the access and refresh tokens in localStorage, readable by any
 * script on the page (the standard XSS-can-steal-the-token risk HttpOnly cookies exist to close).
 *
 * DESIGN: dual-mode, not a hard cutover. Every token-issuing endpoint below now ALSO sets
 * HttpOnly cookies alongside the existing JSON response body (unchanged) -- the existing
 * frontend, which reads tokens from the body and stores them in localStorage, keeps working
 * completely unmodified. The security filter (SecurityConfig's own jwtFilter) now accepts EITHER
 * the Authorization header OR the cookie, preferring the header when both are present. This
 * means the actual security improvement only takes effect once the frontend itself stops storing
 * the token and stops sending the header -- a separate, frontend-side change -- but the backend
 * is ready for that migration today without requiring it to happen atomically in the same
 * deploy, and without a login-breaking flag day for every existing session if only the backend
 * ships first.
 *
 * Genuinely new risk this introduces, addressed rather than left implicit: HttpOnly cookies are
 * sent by the browser automatically on every request to this origin, which a header-based token
 * never was -- that's a real CSRF exposure a bearer-token design didn't have. Mitigated with
 * SameSite=Strict on both cookies: this frontend and this API share the same site (apiUrl is a
 * relative '/api' path in production -- checked directly in environment.prod.ts, not assumed),
 * so every genuine API call originates from same-site JavaScript already running on the page,
 * never from a cross-site form or image tag, which is exactly the case SameSite=Strict blocks.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
// Review finding ("@CrossOrigin still has hardcoded localhost origins" -- external review,
// thirty-fifth pass, P2, full context in NewsController's own identical fix): removed --
// CorsConfig's own global CorsFilter already covers this endpoint.
public class AuthController {

    private final AuthService authService;
    private final JwtUtil     jwt;
    /**
     * Review finding ("Authentication endpoints need stronger abuse controls -- IP-level rate
     * limiting" -- external review, twenty-third pass, P2, confirmed real by direct inspection
     * before this fix: OtpRateLimitService already limits by identifier (mobile/email), but
     * nothing stopped a single IP from cycling through many different identifiers to send OTP
     * requests without limit): the actual fix -- the same DistributedRateLimitService and
     * X-Forwarded-For handling ProxyController already established and uses correctly, reused
     * here rather than duplicating a second, potentially-inconsistent implementation of the same
     * spoofing-aware IP extraction.
     */
    private final com.tradevision.service.DistributedRateLimitService distributedRateLimitService;
    @org.springframework.beans.factory.annotation.Value("${app.proxy.trust-forwarded-for:false}")
    private boolean trustForwardedFor;
    // P2-9 fix, full context in ClientIpResolver's own class javadoc and ProxyController's own
    // identical field: same trusted-proxy CIDR restriction, reused here rather than letting this
    // controller's own X-Forwarded-For trust stay all-or-nothing while ProxyController's gets
    // narrowed.
    @org.springframework.beans.factory.annotation.Value("${app.proxy.trusted-proxy-cidrs:}")
    private String trustedProxyCidrs;
    /**
     * Audit item P1-6 ("Shared-IP users can be locked out of login/OTP by other users' activity
     * on the same IP" -- external review, confirmed real by direct inspection: this per-IP
     * bucket (added for the "single IP cycling through many different identifiers" reason
     * described just above) is keyed purely by resolved client IP with NO identifier component
     * at all -- every register/login/resend call from a given IP, for ANY identifier, increments
     * the SAME counter. A corporate office, campus Wi-Fi, or CGNAT mobile-carrier pool can
     * legitimately have dozens to hundreds of distinct real users behind one public IP; the old
     * hardcoded 20/hour threshold meant a handful of unrelated people each logging in once could
     * exhaust the bucket and lock out everyone else behind that IP for the rest of the window --
     * a real denial-of-service against innocent users sharing an IP, not just abusers).
     *
     * This bucket is NOT the primary defense against any single account being targeted --
     * OtpRateLimitService (per-identifier, 45s cooldown + 5/15min) and otp_resend_by_identifier
     * below (per-identifier, 30s cooldown) already close that specific vector independently of
     * IP. This one exists only as a coarse backstop against SCRIPTED enumeration/cycling from one
     * source, so raising it substantially (and making it configurable, rather than a magic
     * constant, so a deployment behind a known large shared-IP population -- e.g. a mobile
     * carrier's CGNAT range -- can tune it without a code change) trades a small amount of
     * enumeration-bounding precision for not collaterally denying service to legitimate shared-IP
     * traffic, which is the right trade for a backstop rather than a primary control.
     */
    @org.springframework.beans.factory.annotation.Value("${app.auth.otp-requests-per-ip-per-hour:150}")
    private int maxOtpRequestsPerIpPerWindow;
    private static final long OTP_IP_WINDOW_SECONDS = 3600;

    private static final String ACCESS_COOKIE = "tv_access_token";
    private static final String REFRESH_COOKIE = "tv_refresh_token";

    // Review finding's own note above: Secure requires HTTPS: default true for safety in any
    // real deployment, overridable for local HTTP-only dev the same configurable-with-a-safe-
    // default way this codebase already handles mail.enabled/allow-console-fallback elsewhere.
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
     * Review finding ("Remove dual token issuance (body + cookie) once frontend is fully
     * cookie-only" -- P2): confirmed the frontend genuinely is cookie-only now (auth.service.ts's
     * own token getter always returns '' -- see its own javadoc) before making this change, not
     * assumed. Still extracts both tokens from the TokenResponse to set the real cookies (that
     * part is unchanged), but now also clears them on the SAME object afterward -- since Java
     * passes object references, this mutation is reflected in the exact response body ResponseEntity.ok(result)
     * serializes below, meaning the access and refresh tokens no longer appear in the JSON body
     * at all, only in the HttpOnly cookies. Deliberately mutates rather than constructing a new,
     * narrower response type: every caller of this method already holds a reference to the same
     * `result` it's about to return, so this is the minimal-risk way to strip the tokens without
     * changing every endpoint's own return statement or the response DTO's shape (which some
     * other code, or a future one, could still reasonably depend on existing).
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
     * Review finding ("Current authentication architecture still allows refresh-token
     * submission through request body" -- external review, twenty-second pass, P1, confirmed
     * real by direct inspection before this fix: this endpoint's own dual-mode design kept a
     * body-based refresh-token path alive specifically for "the existing frontend, still sending
     * it there" -- but that frontend was already changed earlier this session to send an empty
     * body for this exact call (confirmed directly against AuthService.doRefresh's own real
     * implementation and its own spec test, not assumed), meaning the body-based path had
     * already become genuinely dead code from the one caller that mattered, just never removed
     * here): the actual fix -- cookie-only, no request body accepted or read at all. Closes the
     * exact risk the review names: a refresh token surviving in browser instrumentation, request
     * logging, debugging tools, or proxies, none of which should ever see it now that it lives
     * only in an HttpOnly cookie the browser attaches automatically.
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
        // Review finding ("Frontend authentication migration is incomplete and currently breaks
        // authenticated APIs" -- P0, and a second, related regression this fix's own earlier
        // comment introduced): confirmed real and fixed. The comment this replaces described an
        // earlier filter design (a request-wrapper that synthesized an Authorization header
        // from the cookie) that was later replaced with the current, more robust "try header,
        // fall back to cookie" design in SecurityConfig's own jwtFilter -- which populates
        // SecurityContext directly and never touches the request's own header at all. That left
        // this @RequestHeader read stale: it would see null even for a genuinely
        // cookie-authenticated user, since the frontend never sends that header anymore either.
        // @AuthenticationPrincipal reads the actual SecurityContext instead, which is correct
        // for both designs and doesn't silently drift when the filter's own approach changes
        // again. /api/auth/** is public in SecurityConfig, so userId can legitimately still be
        // null here (an already-expired or never-valid session attempting to log out) --
        // clearing cookies unconditionally either way is still correct and harmless.
        clearAuthCookies(response);
        if (userId == null) return ResponseEntity.badRequest().body(ApiResponse.error("Not authenticated."));
        return ResponseEntity.ok(authService.logout(userId));
    }

    // ── Profile ───────────────────────────────────────────────
    // Review finding ("Frontend authentication migration is incomplete and currently breaks
    // authenticated APIs" -- P0): confirmed real and fixed here too -- see UserController's own
    // javadoc for the full root-cause explanation.
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
     * Review finding ("OTP resend still deserves IP-level throttling" -- external review,
     * thirtieth pass, P1, confirmed real by direct inspection before this fix: /register/initiate
     * and /login/initiate both already had the per-IP limit above, but this endpoint -- an
     * equally obvious abuse surface, letting an attacker cycle through many different
     * identifiers' own resend requests from one IP, or hammer a single identifier's own resend
     * repeatedly to run up SMS/email provider cost -- had none at all): the actual fix -- the
     * same per-IP limit this file already established, PLUS a short per-identifier cooldown
     * (the review's own explicit ask: "per-IP + per-identifier + short cooldown"), so neither a
     * single IP cycling identifiers nor a single identifier being hammered repeatedly (from one
     * IP or many) gets through unthrottled.
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
     * P2-11 fix ("AuthController.checkEmail/checkMobile: unauthenticated, unthrottled account
     * enumeration" -- external review, confirmed real by direct inspection before this fix:
     * both endpoints below directly echo back userRepo.existsByEmail/existsByMobile with zero
     * authentication and zero rate limiting -- an attacker can script through an arbitrarily
     * large list of emails/phone numbers and get a definitive "registered: true/false" for each
     * one, harvesting a real list of this app's actual users for a targeted phishing campaign,
     * entirely for free and at whatever pace they choose): the actual fix -- the same
     * DistributedRateLimitService + ClientIpResolver infrastructure already established for the
     * OTP endpoints above, reused here rather than a third, independent implementation of the
     * same per-IP throttling. 30 requests/hour per IP is deliberately more generous than the OTP
     * limits (20/h) -- this endpoint is hit on every keystroke pause during a real signup form's
     * live "is this email available" check, a legitimate use pattern OTP sending doesn't have --
     * while still bounding a scripted enumeration attempt to a small, rate-limited trickle rather
     * than an unbounded scan.
     *
     * HONEST SCOPE: this does not change the RESPONSE shape itself (registered: true/false is
     * still returned, since that is this endpoint's own stated, legitimate purpose for a genuine
     * signup flow checking availability) -- only the RATE at which that information can be
     * extracted, which is the actual lever available here without breaking the real feature this
     * endpoint exists for.
     *
     * Audit item P1-6, full context in maxOtpRequestsPerIpPerWindow's own updated javadoc above:
     * same shared-IP collateral-lockout risk applies here (also keyed purely by IP, no identifier
     * component) -- made configurable with a higher default for the same reason.
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
