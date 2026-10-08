package com.tradevision.controller;

import com.tradevision.dto.ApiResponse;
import com.tradevision.dto.OtpVerifyRequest;
import com.tradevision.dto.TokenResponse;
import com.tradevision.service.AuthService;
import com.tradevision.util.JwtUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests the dual-mode cookie design -- see AuthController's own javadoc for the full design
 * rationale (dual-mode not a hard cutover, why SameSite=Strict is the right choice given this
 * frontend and API share the same site), in the same plain-Mockito-unit-test style already
 * established throughout this codebase's other tests (no MockMvc/Spring context loading
 * anywhere else here, so none introduced here either).
 *
 * LIMITATION: this test file could not actually be compiled or run against the real Spring
 * Framework in this development sandbox -- Maven Central is blocked here, so there's no real
 * spring-web jar available to produce an actual ResponseCookie instance. To reduce that gap as
 * far as this sandbox allows: built a spec-compliant stand-in of ResponseCookie (matching RFC
 * 6265's own Set-Cookie serialization format, which is Spring's own documented contract for
 * this class) and ran this file's exact setAuthCookies()/clearAuthCookies() logic against it --
 * every assertion below passed against that realistic output. That's stronger evidence than an
 * untested assumption, but it is still not the same guarantee as running against the real jar.
 * Whoever runs this in a real Maven build should confirm it passes before relying on it.
 *
 * logout() uses @AuthenticationPrincipal rather than @RequestHeader, so its test below passes
 * an already-resolved userId directly rather than a Bearer-prefixed token string parsed via a
 * mocked JwtUtil -- matching what @AuthenticationPrincipal actually hands the controller at
 * runtime.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthControllerTest {

    @Mock AuthService authService;
    @Mock JwtUtil jwt;
    @Mock HttpServletResponse response;
    @Mock jakarta.servlet.http.HttpServletRequest httpRequest;
    @Mock com.tradevision.service.DistributedRateLimitService distributedRateLimitService;

    @InjectMocks AuthController controller;

    @BeforeEach
    void setCookieSecureDefault() throws Exception {
        // @Value-injected fields aren't populated outside a Spring context -- set directly via
        // reflection, matching this field's own real default (app.auth.cookie-secure:true).
        Field f = AuthController.class.getDeclaredField("cookieSecure");
        f.setAccessible(true);
        f.set(controller, true);
        when(jwt.getAccessExpirationMs()).thenReturn(1_800_000L);
        when(jwt.getRefreshExpirationMs()).thenReturn(604_800_000L);
    }

    private List<String> capturedSetCookieHeaders() {
        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(response, atLeast(0)).addHeader(nameCaptor.capture(), valueCaptor.capture());
        return valueCaptor.getAllValues();
    }

    @Test
    @DisplayName("verifyLogin: a successful OTP verification sets BOTH the access and refresh cookies, each HttpOnly/Secure/SameSite=Strict, alongside the unchanged JSON response body")
    void verifyLogin_success_setsHttpOnlyCookies() {
        OtpVerifyRequest req = new OtpVerifyRequest();
        req.setEmail("trader@example.com");
        req.setCode("123456");
        ApiResponse<?> result = ApiResponse.ok("Authenticated.", new TokenResponse("acc-token", "ref-token", null));
        doReturn(result).when(authService).verifyOtp(any());

        controller.verifyLogin(req, response);

        List<String> cookies = capturedSetCookieHeaders();
        assertThat(cookies).hasSize(2);
        assertThat(cookies).anySatisfy(c -> {
            assertThat(c).contains("tv_access_token=acc-token");
            assertThat(c).contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/");
        });
        assertThat(cookies).anySatisfy(c -> assertThat(c).contains("tv_refresh_token=ref-token"));
    }

    @Test
    @DisplayName("verifyLogin: after cookies are set, the SAME TokenResponse object that becomes the JSON response body no longer contains the access or refresh token -- dual token issuance (body + cookie) is removed once the frontend is cookie-only, confirmed against the exact object ResponseEntity.ok(result) would serialize, not a separate one")
    void verifyLogin_success_stripsTokensFromResponseBody() {
        OtpVerifyRequest req = new OtpVerifyRequest();
        req.setEmail("trader@example.com");
        req.setCode("123456");
        TokenResponse tr = new TokenResponse("acc-token", "ref-token", null);
        ApiResponse<?> result = ApiResponse.ok("Authenticated.", tr);
        doReturn(result).when(authService).verifyOtp(any());

        var response2 = controller.verifyLogin(req, response);

        assertThat(tr.getAccessToken()).isNull();
        assertThat(tr.getRefreshToken()).isNull();
        // The response body IS this same object -- confirming the caller gets the stripped version.
        assertThat(((ApiResponse<?>) response2.getBody()).getData()).isSameAs(tr);
    }

    @Test
    @DisplayName("verifyLogin: a failed OTP verification (invalid code) sets NO cookies -- the JSON error body is still the actual source of truth for the caller")
    void verifyLogin_failure_setsNoCookies() {
        OtpVerifyRequest req = new OtpVerifyRequest();
        doReturn(ApiResponse.error("Invalid OTP.")).when(authService).verifyOtp(any());

        controller.verifyLogin(req, response);

        verify(response, never()).addHeader(anyString(), anyString());
    }

    /**
     * Verifies the current, cookie-only behavior -- refresh tokens are never accepted through
     * the request body.
     */
    @Test
    @DisplayName("refresh: uses the cookie's refresh token -- the only source this endpoint accepts now")
    void refresh_usesCookieToken() {
        doReturn(ApiResponse.ok("ok", new TokenResponse("new-acc", "new-ref", null))).when(authService).refreshToken("cookie-token");

        controller.refresh("cookie-token", response);

        verify(authService).refreshToken("cookie-token");
    }

    @Test
    @DisplayName("refresh: no cookie present -- a clear 400 error, never an NPE or a call to the service with a null/blank token")
    void refresh_noCookie_returnsBadRequest() {
        var result = controller.refresh(null, response);

        assertThat(result.getStatusCode().value()).isEqualTo(400);
        verify(authService, never()).refreshToken(any());
    }

    @Test
    @DisplayName("refresh: a blank cookie value is treated the same as no cookie at all -- a clear 400, never passed through to the service")
    void refresh_blankCookie_returnsBadRequest() {
        var result = controller.refresh("   ", response);

        assertThat(result.getStatusCode().value()).isEqualTo(400);
        verify(authService, never()).refreshToken(any());
    }

    @Test
    @DisplayName("refresh: a successful refresh also rotates BOTH cookies to the newly-issued tokens")
    void refresh_success_rotatesCookies() {
        doReturn(ApiResponse.ok("ok", new TokenResponse("rotated-acc", "rotated-ref", null))).when(authService).refreshToken(any());

        controller.refresh("old-token", response);

        List<String> cookies = capturedSetCookieHeaders();
        assertThat(cookies).anySatisfy(c -> assertThat(c).contains("tv_access_token=rotated-acc"));
        assertThat(cookies).anySatisfy(c -> assertThat(c).contains("tv_refresh_token=rotated-ref"));
    }

    @Test
    @DisplayName("logout: always clears both cookies (Max-Age=0), even when called with no Authorization header/cookie at all -- clearing a cookie that was never set is harmless, and a stray client-side cookie must never survive an attempted logout")
    void logout_alwaysClearsCookiesEvenWithoutAuth() {
        var result = controller.logout(null, response);

        List<String> cookies = capturedSetCookieHeaders();
        assertThat(cookies).hasSize(2);
        assertThat(cookies).allSatisfy(c -> assertThat(c).contains("Max-Age=0"));
        assertThat(result.getStatusCode().value()).isEqualTo(400); // still reports "not authenticated" -- clearing cookies doesn't fake a successful logout
    }

    @Test
    @DisplayName("logout: with an authenticated principal (@AuthenticationPrincipal, populated from SecurityContext regardless of whether the request arrived via header or cookie), clears cookies AND calls the real logout")
    void logout_withAuthenticatedPrincipal_clearsCookiesAndLogsOut() {
        doReturn(ApiResponse.ok("Logged out.")).when(authService).logout("user1");

        controller.logout("user1", response);

        verify(authService).logout("user1");
        List<String> cookies = capturedSetCookieHeaders();
        assertThat(cookies).hasSize(2);
    }

    /**
     * Confirms the per-IP thresholds are real @Value-injected, per-deployment-configurable
     * fields (not hardcoded constants) with substantially raised defaults, so an operator
     * behind a known large shared-IP population can tune them without a code change, and
     * normal shared-IP traffic volumes don't collaterally trip a low hardcoded limit --
     * avoiding shared-IP users being locked out of login/OTP by other users' activity on the
     * same IP.
     */
    @Test
    @DisplayName("per-IP OTP and account-check thresholds are @Value-injected fields (configurable per deployment), with raised defaults")
    void perIpThresholds_areConfigurableFieldsWithRaisedDefaults() throws Exception {
        Field otpField = AuthController.class.getDeclaredField("maxOtpRequestsPerIpPerWindow");
        assertThat(otpField.getAnnotation(org.springframework.beans.factory.annotation.Value.class).value())
            .isEqualTo("${app.auth.otp-requests-per-ip-per-hour:150}");

        Field accountCheckField = AuthController.class.getDeclaredField("maxAccountCheckRequestsPerIpPerWindow");
        assertThat(accountCheckField.getAnnotation(org.springframework.beans.factory.annotation.Value.class).value())
            .isEqualTo("${app.auth.account-checks-per-ip-per-hour:200}");
    }

    /**
     * Tests for IP-level rate limiting on the authentication endpoints.
     */
    @Test
    @DisplayName("initLogin: within the per-IP rate limit, proceeds normally")
    void initLogin_withinRateLimit_proceedsNormally() {
        when(distributedRateLimitService.allow(eq("otp_initiate_by_ip"), any(), anyInt(), anyLong())).thenReturn(true);
        when(httpRequest.getRemoteAddr()).thenReturn("1.2.3.4");
        doReturn(ApiResponse.ok("OTP sent.")).when(authService).sendOtp("user@example.com", "LOGIN");

        var result = controller.initLogin(Map.of("email", "user@example.com"), httpRequest);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        verify(authService).sendOtp("user@example.com", "LOGIN");
    }

    @Test
    @DisplayName("initLogin: once the per-IP rate limit is exceeded, returns 429 and never even reaches AuthService.sendOtp")
    void initLogin_rateLimitExceeded_returns429WithoutCallingAuthService() {
        when(distributedRateLimitService.allow(eq("otp_initiate_by_ip"), any(), anyInt(), anyLong())).thenReturn(false);
        when(httpRequest.getRemoteAddr()).thenReturn("1.2.3.4");

        var result = controller.initLogin(Map.of("email", "user@example.com"), httpRequest);

        assertThat(result.getStatusCode().value()).isEqualTo(429);
        verify(authService, never()).sendOtp(any(), any());
    }

    @Test
    @DisplayName("initRegister: once the per-IP rate limit is exceeded, returns 429 and never even reaches AuthService.sendOtp -- same protection as initLogin, since a single IP cycling through many different identifiers should be caught regardless of which endpoint it targets")
    void initRegister_rateLimitExceeded_returns429WithoutCallingAuthService() {
        when(distributedRateLimitService.allow(eq("otp_initiate_by_ip"), any(), anyInt(), anyLong())).thenReturn(false);
        when(httpRequest.getRemoteAddr()).thenReturn("1.2.3.4");

        var result = controller.initRegister(Map.of("email", "newuser@example.com"), httpRequest);

        assertThat(result.getStatusCode().value()).isEqualTo(429);
        verify(authService, never()).sendOtp(any(), any());
    }

    /**
     * With trustForwardedFor enabled AND the request's real remote address inside a configured
     * trusted-proxy CIDR, two different X-Forwarded-For client IPs get their own independent
     * rate-limit buckets, rather than both being collapsed onto the shared load balancer IP a
     * bare trustForwardedFor=false (or an untrusted remote address) would report instead.
     */
    @Test
    @DisplayName("initLogin: two different clients behind the same trusted load balancer are rate-limited by their own X-Forwarded-For IP, not the shared LB IP")
    void initLogin_behindTrustedProxy_countsTwoClientIpsSeparately() throws Exception {
        Field trustField = AuthController.class.getDeclaredField("trustForwardedFor");
        trustField.setAccessible(true);
        trustField.set(controller, true);
        Field cidrField = AuthController.class.getDeclaredField("trustedProxyCidrs");
        cidrField.setAccessible(true);
        cidrField.set(controller, "10.0.0.0/8");

        // Both requests arrive from the same trusted load balancer (10.0.0.5), but carry
        // different real-client X-Forwarded-For values.
        when(httpRequest.getRemoteAddr()).thenReturn("10.0.0.5");
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.10");
        when(distributedRateLimitService.allow(eq("otp_initiate_by_ip"), eq("203.0.113.10"), anyInt(), anyLong())).thenReturn(true);
        doReturn(ApiResponse.ok("OTP sent.")).when(authService).sendOtp("user@example.com", "LOGIN");
        var result1 = controller.initLogin(Map.of("email", "user@example.com"), httpRequest);
        assertThat(result1.getStatusCode().value()).isEqualTo(200);

        // Second client, same LB, but this one has already exhausted ITS OWN limit -- it must be
        // rejected independently of the first client's own fresh bucket.
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("203.0.113.20");
        when(distributedRateLimitService.allow(eq("otp_initiate_by_ip"), eq("203.0.113.20"), anyInt(), anyLong())).thenReturn(false);
        var result2 = controller.initLogin(Map.of("email", "user2@example.com"), httpRequest);
        assertThat(result2.getStatusCode().value()).isEqualTo(429);

        // Confirms the shared LB IP itself was never used as a rate-limit key for either request.
        verify(distributedRateLimitService, never()).allow(eq("otp_initiate_by_ip"), eq("10.0.0.5"), anyInt(), anyLong());
    }

    @Test
    @DisplayName("initLogin: X-Forwarded-For from a remote address OUTSIDE the configured trusted-proxy CIDRs is never honored, even with trustForwardedFor enabled -- closes the all-or-nothing spoofing gap")
    void initLogin_untrustedRemoteAddress_ignoresForwardedForEvenWhenEnabled() throws Exception {
        Field trustField = AuthController.class.getDeclaredField("trustForwardedFor");
        trustField.setAccessible(true);
        trustField.set(controller, true);
        Field cidrField = AuthController.class.getDeclaredField("trustedProxyCidrs");
        cidrField.setAccessible(true);
        cidrField.set(controller, "10.0.0.0/8");

        // This request reaches the app directly (or via some untrusted hop) -- 203.0.113.99 is
        // NOT inside 10.0.0.0/8 -- so its own forged X-Forwarded-For must be ignored entirely.
        when(httpRequest.getRemoteAddr()).thenReturn("203.0.113.99");
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4");
        when(distributedRateLimitService.allow(eq("otp_initiate_by_ip"), eq("203.0.113.99"), anyInt(), anyLong())).thenReturn(true);
        doReturn(ApiResponse.ok("OTP sent.")).when(authService).sendOtp("user@example.com", "LOGIN");

        var result = controller.initLogin(Map.of("email", "user@example.com"), httpRequest);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        verify(distributedRateLimitService).allow(eq("otp_initiate_by_ip"), eq("203.0.113.99"), anyInt(), anyLong());
        verify(distributedRateLimitService, never()).allow(eq("otp_initiate_by_ip"), eq("1.2.3.4"), anyInt(), anyLong());
    }

    /**
     * Confirms both endpoints are genuinely rate-limited per IP and reject with 429 once
     * exceeded, never reaching AuthService (and therefore never leaking the
     * registered:true/false result) once the limit is hit -- closing an unauthenticated,
     * unthrottled account enumeration gap.
     */
    @Test
    @DisplayName("checkEmail: within the per-IP rate limit, proceeds normally")
    void checkEmail_withinRateLimit_proceedsNormally() {
        when(distributedRateLimitService.allow(eq("account_check_by_ip"), any(), anyInt(), anyLong())).thenReturn(true);
        when(httpRequest.getRemoteAddr()).thenReturn("1.2.3.4");
        doReturn(ApiResponse.ok("OK", Map.of("registered", true))).when(authService).checkEmail("user@example.com");

        var result = controller.checkEmail("user@example.com", httpRequest);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        verify(authService).checkEmail("user@example.com");
    }

    @Test
    @DisplayName("checkEmail: once the per-IP rate limit is exceeded, returns 429 and never even reaches AuthService.checkEmail")
    void checkEmail_rateLimitExceeded_returns429WithoutCallingAuthService() {
        when(distributedRateLimitService.allow(eq("account_check_by_ip"), any(), anyInt(), anyLong())).thenReturn(false);
        when(httpRequest.getRemoteAddr()).thenReturn("1.2.3.4");

        var result = controller.checkEmail("user@example.com", httpRequest);

        assertThat(result.getStatusCode().value()).isEqualTo(429);
        verify(authService, never()).checkEmail(any());
    }

    @Test
    @DisplayName("checkMobile: within the per-IP rate limit, proceeds normally")
    void checkMobile_withinRateLimit_proceedsNormally() {
        when(distributedRateLimitService.allow(eq("account_check_by_ip"), any(), anyInt(), anyLong())).thenReturn(true);
        when(httpRequest.getRemoteAddr()).thenReturn("1.2.3.4");
        doReturn(ApiResponse.ok("OK", Map.of("registered", false))).when(authService).checkMobile("9999999999");

        var result = controller.checkMobile("9999999999", httpRequest);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        verify(authService).checkMobile("9999999999");
    }

    @Test
    @DisplayName("checkMobile: once the per-IP rate limit is exceeded, returns 429 and never even reaches AuthService.checkMobile -- same enumeration protection as checkEmail")
    void checkMobile_rateLimitExceeded_returns429WithoutCallingAuthService() {
        when(distributedRateLimitService.allow(eq("account_check_by_ip"), any(), anyInt(), anyLong())).thenReturn(false);
        when(httpRequest.getRemoteAddr()).thenReturn("1.2.3.4");

        var result = controller.checkMobile("9999999999", httpRequest);

        assertThat(result.getStatusCode().value()).isEqualTo(429);
        verify(authService, never()).checkMobile(any());
    }

    @Test
    @DisplayName("checkEmail and checkMobile share the same per-IP rate-limit bucket (\"account_check_by_ip\") -- an attacker can't dodge the limit by alternating which endpoint they hit")
    void checkEmailAndCheckMobile_shareSameRateLimitBucket() {
        when(distributedRateLimitService.allow(eq("account_check_by_ip"), eq("1.2.3.4"), anyInt(), anyLong())).thenReturn(true);
        when(httpRequest.getRemoteAddr()).thenReturn("1.2.3.4");
        doReturn(ApiResponse.ok("OK", Map.of("registered", true))).when(authService).checkEmail(any());
        doReturn(ApiResponse.ok("OK", Map.of("registered", true))).when(authService).checkMobile(any());

        controller.checkEmail("a@example.com", httpRequest);
        controller.checkMobile("1234567890", httpRequest);

        verify(distributedRateLimitService, times(2)).allow(eq("account_check_by_ip"), eq("1.2.3.4"), anyInt(), anyLong());
    }

    /**
     * Tests for OTP resend's IP-level throttling -- both the per-IP limit (matching
     * initLogin/initRegister's own established protection) and the per-identifier cooldown this
     * endpoint specifically needs.
     */
    @Test
    @DisplayName("resendOtp: within both the per-IP limit and the per-identifier cooldown, proceeds normally")
    void resendOtp_withinBothLimits_proceedsNormally() {
        when(distributedRateLimitService.allow(eq("otp_initiate_by_ip"), any(), anyInt(), anyLong())).thenReturn(true);
        when(distributedRateLimitService.allow(eq("otp_resend_by_identifier"), any(), anyInt(), anyLong())).thenReturn(true);
        when(httpRequest.getRemoteAddr()).thenReturn("1.2.3.4");
        doReturn(ApiResponse.ok("OTP sent.")).when(authService).sendOtp("user@example.com", "LOGIN");

        var result = controller.resendOtp("user@example.com", null, "LOGIN", httpRequest);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        verify(authService).sendOtp("user@example.com", "LOGIN");
    }

    @Test
    @DisplayName("resendOtp: per-IP rate limit exceeded -- 429, never even checks the per-identifier cooldown or reaches AuthService")
    void resendOtp_perIpLimitExceeded_returns429WithoutFurtherChecks() {
        when(distributedRateLimitService.allow(eq("otp_initiate_by_ip"), any(), anyInt(), anyLong())).thenReturn(false);
        when(httpRequest.getRemoteAddr()).thenReturn("1.2.3.4");

        var result = controller.resendOtp("user@example.com", null, "LOGIN", httpRequest);

        assertThat(result.getStatusCode().value()).isEqualTo(429);
        verify(distributedRateLimitService, never()).allow(eq("otp_resend_by_identifier"), any(), anyInt(), anyLong());
        verify(authService, never()).sendOtp(any(), any());
    }

    @Test
    @DisplayName("resendOtp: per-identifier cooldown still active (a resend was just requested for this exact identifier) -- 429, even from a genuinely different, otherwise-unthrottled IP")
    void resendOtp_perIdentifierCooldownActive_returns429EvenFromFreshIp() {
        when(distributedRateLimitService.allow(eq("otp_initiate_by_ip"), any(), anyInt(), anyLong())).thenReturn(true);
        when(distributedRateLimitService.allow(eq("otp_resend_by_identifier"), eq("user@example.com"), anyInt(), anyLong())).thenReturn(false);
        when(httpRequest.getRemoteAddr()).thenReturn("5.6.7.8");

        var result = controller.resendOtp("user@example.com", null, "LOGIN", httpRequest);

        assertThat(result.getStatusCode().value()).isEqualTo(429);
        verify(authService, never()).sendOtp(any(), any());
    }

    @Test
    @DisplayName("resendOtp: the identifier is normalized (trimmed, lowercased) before being used as the per-identifier rate-limit key, so 'User@Example.com ' and 'user@example.com' share the same cooldown rather than bypassing it via case/whitespace variation")
    void resendOtp_normalizesIdentifierForRateLimitKey() {
        when(distributedRateLimitService.allow(eq("otp_initiate_by_ip"), any(), anyInt(), anyLong())).thenReturn(true);
        when(distributedRateLimitService.allow(eq("otp_resend_by_identifier"), any(), anyInt(), anyLong())).thenReturn(true);
        when(httpRequest.getRemoteAddr()).thenReturn("1.2.3.4");
        doReturn(ApiResponse.ok("OTP sent.")).when(authService).sendOtp(any(), any());

        controller.resendOtp(" User@Example.com ", null, "LOGIN", httpRequest);

        verify(distributedRateLimitService).allow(eq("otp_resend_by_identifier"), eq("user@example.com"), anyInt(), anyLong());
        verify(authService).sendOtp("user@example.com", "LOGIN");
    }
}
