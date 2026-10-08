package com.tradevision.service;

import com.tradevision.model.*;
import com.tradevision.repository.*;
import com.tradevision.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock UserRepository    userRepo;
    @Mock OtpRepository     otpRepo;
    @Mock EmailService      emailService;
    @Mock org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    // AuthService calls OtpUtil.sendSms internally, not SmsService, so this mocks the
    // rate limiter that AuthService actually depends on.
    @Mock OtpRateLimitService otpRateLimitService;
    // Needed because setAlertWebhookUrl() calls this to validate a URL before saving it.
    @Mock WebhookAlertService webhookAlertService;
    @InjectMocks AuthService authService;

    @Spy JwtUtil jwt = new JwtUtil();
    @Spy OtpUtil otpUtil = new OtpUtil();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(jwt,         "secret",          "TestSecret_256bit_Key_ForJUnit_TradeVision_2025!!");
        ReflectionTestUtils.setField(jwt,         "expiration",      86400000L);
        ReflectionTestUtils.setField(jwt,         "refreshExpiration", 604800000L);
        // otpHmacSecret is @Value-injected in real Spring wiring — this @Spy is constructed
        // manually, so it needs the same field set directly, or hashOtp() NPEs on a null secret.
        ReflectionTestUtils.setField(otpUtil,     "otpHmacSecret",   "TestOtpHmacSecret_ForJUnit_TradeVision_2025");
        ReflectionTestUtils.setField(otpUtil,     "provider",        "console");
        // sendSms returns a real success/failure signal instead of void — the console fallback
        // only "succeeds" when explicitly opted into, matching the safe-by-default behavior.
        // This test wants to verify the SUCCESS path, so it opts in explicitly, the same way
        // real local dev would.
        ReflectionTestUtils.setField(otpUtil,     "allowConsoleFallback", true);
        ReflectionTestUtils.setField(authService, "otpExpiryMin",    5);
        ReflectionTestUtils.setField(authService, "jwt",             jwt);
        ReflectionTestUtils.setField(authService, "otpUtil",         otpUtil);

        when(otpRateLimitService.checkAndRecord(any(), any()))
            .thenReturn(new OtpRateLimitService.RateLimitResult(true, null));
        // Realistic "the atomic claim succeeded" defaults for the two mongoTemplate calls
        // verifyOtp() makes -- an unstubbed updateFirst()/remove() would otherwise NPE on
        // .getModifiedCount()/.getDeletedCount() (Mockito's own real default for an unstubbed
        // object-returning call is null), breaking every existing verifyOtp() test in this file.
        // A test that specifically wants to exercise the lost-the-claim path overrides these
        // explicitly.
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(OtpRecord.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
        when(mongoTemplate.remove(any(), eq(OtpRecord.class)))
            .thenReturn(com.mongodb.client.result.DeleteResult.acknowledged(1));
    }

    @Test @DisplayName("sendOtp: saves a hashed OTP (never the raw code) and sends via OtpUtil")
    void sendOtp_savesHashedAndSends() {
        when(userRepo.findByMobile(any())).thenReturn(Optional.empty());
        when(otpRepo.deleteByMobileAndPurpose(any(),any())).thenReturn(0L);
        ArgumentCaptor<OtpRecord> savedCaptor = ArgumentCaptor.forClass(OtpRecord.class);
        when(otpRepo.save(savedCaptor.capture())).thenAnswer(i -> i.getArguments()[0]);

        var resp = authService.sendOtp("9000000000", "LOGIN");

        assertThat(resp.isSuccess()).isTrue();
        // The stored value must be a hash, not a 6-digit code.
        String stored = savedCaptor.getValue().getOtpHash();
        assertThat(stored).isNotNull();
        assertThat(stored).doesNotMatch("^\\d{6}$"); // a raw OTP would match this; a hex HMAC digest won't
    }

    @Test @DisplayName("sendOtp: refused when the rate limiter rejects the request")
    void sendOtp_respectsRateLimit() {
        when(userRepo.findByMobile(any())).thenReturn(Optional.empty());
        when(otpRateLimitService.checkAndRecord(any(), any()))
            .thenReturn(new OtpRateLimitService.RateLimitResult(false, "Please wait 30 seconds before requesting another OTP."));

        var resp = authService.sendOtp("9000000003", "LOGIN");

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getMessage()).containsIgnoringCase("wait");
        verify(otpRepo, never()).save(any());
    }

    @Test @DisplayName("sendOtp: reports failure honestly when delivery didn't actually happen")
    void sendOtp_reportsFailureWhenDeliveryDidNotHappen() {
        when(userRepo.findByMobile(any())).thenReturn(Optional.empty());
        when(otpRepo.deleteByMobileAndPurpose(any(),any())).thenReturn(0L);
        when(otpRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);
        // Simulates the real safe-by-default state: no provider configured, console fallback not
        // explicitly enabled — sendSms correctly does nothing and returns false.
        ReflectionTestUtils.setField(otpUtil, "allowConsoleFallback", false);

        var resp = authService.sendOtp("9000000005", "LOGIN");

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getMessage()).containsIgnoringCase("unavailable");
    }

    @Test @DisplayName("verifyOtp: wrong code increments failed attempts")
    void verifyOtp_wrongCode() {
        var req = new com.tradevision.dto.OtpVerifyRequest();
        req.setMobile("9000000001"); req.setCode("999999"); req.setPurpose("LOGIN");

        // Stored hash corresponds to a DIFFERENT code ("123456") than what's submitted — using
        // the same hashOtp() the real code uses, so this is a genuine hash mismatch, not a
        // coincidentally-matching literal string.
        String storedHash = otpUtil.hashOtp("123456", "9000000001", "LOGIN");
        OtpRecord otp = new OtpRecord("9000000001", storedHash, "LOGIN");
        when(otpRepo.findByMobileAndPurposeOrderByCreatedAtDesc(any(),any()))
            .thenReturn(List.of(otp));

        User user = new User(); user.setMobile("9000000001");
        when(userRepo.findByMobile(any())).thenReturn(Optional.of(user));
        when(userRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        var resp = authService.verifyOtp(req);
        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getMessage()).containsIgnoringCase("invalid");
    }

    @Test @DisplayName("verifyOtp: correct code (matching hash) succeeds")
    void verifyOtp_correctCode() {
        var req = new com.tradevision.dto.OtpVerifyRequest();
        req.setMobile("9000000004"); req.setCode("654321"); req.setPurpose("LOGIN");

        String storedHash = otpUtil.hashOtp("654321", "9000000004", "LOGIN");
        OtpRecord otp = new OtpRecord("9000000004", storedHash, "LOGIN");
        when(otpRepo.findByMobileAndPurposeOrderByCreatedAtDesc(any(),any()))
            .thenReturn(List.of(otp));
        when(userRepo.findByMobile(any())).thenReturn(Optional.empty());
        when(userRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        var resp = authService.verifyOtp(req);
        assertThat(resp.isSuccess()).isTrue();
    }

    @Test @DisplayName("verifyOtp: attempt-limit claim already exhausted (5 wrong guesses already made) rejects immediately, WITHOUT even comparing the submitted code")
    void verifyOtp_attemptLimitExhausted_rejectsBeforeComparingCode() {
        var req = new com.tradevision.dto.OtpVerifyRequest();
        req.setMobile("9000000005"); req.setCode("654321"); req.setPurpose("LOGIN"); // the CORRECT code

        String storedHash = otpUtil.hashOtp("654321", "9000000005", "LOGIN");
        OtpRecord otp = new OtpRecord("9000000005", storedHash, "LOGIN");
        when(otpRepo.findByMobileAndPurposeOrderByCreatedAtDesc(any(), any())).thenReturn(List.of(otp));
        // The atomic claim itself fails -- simulating attemptCount already at the limit.
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(OtpRecord.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 0L, null));

        var resp = authService.verifyOtp(req);

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getMessage()).containsIgnoringCase("too many");
        // The OTP record is never deleted -- correctly rejected before ever reaching the hash
        // comparison or consumption step, even though the submitted code was actually correct.
        verify(mongoTemplate, never()).remove(any(), eq(OtpRecord.class));
    }

    @Test @DisplayName("verifyOtp: the consume-claim (atomic delete) losing its race -- another concurrent request already consumed this exact OTP -- rejects with an already-used message, never issuing a second session for the same code")
    void verifyOtp_consumeClaimLostRace_rejectsAlreadyUsed() {
        var req = new com.tradevision.dto.OtpVerifyRequest();
        req.setMobile("9000000006"); req.setCode("111222"); req.setPurpose("LOGIN");

        String storedHash = otpUtil.hashOtp("111222", "9000000006", "LOGIN");
        OtpRecord otp = new OtpRecord("9000000006", storedHash, "LOGIN");
        when(otpRepo.findByMobileAndPurposeOrderByCreatedAtDesc(any(), any())).thenReturn(List.of(otp));
        // The attempt claim succeeds (still under the limit), but the consume-claim right after
        // the hash comparison loses its race -- another concurrent request already deleted it.
        when(mongoTemplate.remove(any(), eq(OtpRecord.class))).thenReturn(com.mongodb.client.result.DeleteResult.acknowledged(0));

        var resp = authService.verifyOtp(req);

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getMessage()).containsIgnoringCase("already been used");
        // Never proceeds to find-or-create a user / issue a session for this losing request.
        verify(userRepo, never()).save(any());
    }

    // ── Step-up OTP for mid-session, high-stakes actions ───────────

    @Test @DisplayName("sendStepUpOtp: sends to the user's own on-file email, never a caller-supplied destination")
    void sendStepUpOtp_sendsToUsersOwnEmail() {
        User user = new User(); user.setId("u1"); user.setEmail("trader@example.com");
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));
        when(userRepo.findByMobile(any())).thenReturn(Optional.empty());
        when(otpRepo.deleteByMobileAndPurpose(any(), any())).thenReturn(0L);
        when(otpRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);
        when(emailService.sendOtp(eq("trader@example.com"), any())).thenReturn(true);

        var resp = authService.sendStepUpOtp("u1", "LIVE_AUTOTRADE_STEPUP");

        assertThat(resp.isSuccess()).isTrue();
        verify(otpRateLimitService).checkAndRecord("trader@example.com", "LIVE_AUTOTRADE_STEPUP");
    }

    @Test @DisplayName("sendStepUpOtp: user has no email or mobile on file — refused rather than silently sending nowhere")
    void sendStepUpOtp_noIdentifierOnFile_refused() {
        User user = new User(); user.setId("u1");
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));

        var resp = authService.sendStepUpOtp("u1", "LIVE_AUTOTRADE_STEPUP");

        assertThat(resp.isSuccess()).isFalse();
        verify(otpRepo, never()).save(any());
    }

    @Test @DisplayName("sendStepUpOtp: unknown user — refused")
    void sendStepUpOtp_unknownUser_refused() {
        when(userRepo.findById("ghost")).thenReturn(Optional.empty());

        var resp = authService.sendStepUpOtp("ghost", "LIVE_AUTOTRADE_STEPUP");

        assertThat(resp.isSuccess()).isFalse();
    }

    @Test @DisplayName("verifyStepUpOtp: correct, freshly-issued code against the user's own identifier succeeds without throwing")
    void verifyStepUpOtp_correctCode_succeeds() {
        User user = new User(); user.setId("u1"); user.setEmail("trader@example.com");
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));
        String storedHash = otpUtil.hashOtp("777888", "trader@example.com", "LIVE_AUTOTRADE_STEPUP");
        OtpRecord otp = new OtpRecord("trader@example.com", storedHash, "LIVE_AUTOTRADE_STEPUP");
        when(otpRepo.findByMobileAndPurposeOrderByCreatedAtDesc("trader@example.com", "LIVE_AUTOTRADE_STEPUP"))
            .thenReturn(List.of(otp));

        assertThatCode(() -> authService.verifyStepUpOtp("u1", "LIVE_AUTOTRADE_STEPUP", "777888"))
            .doesNotThrowAnyException();
    }

    @Test @DisplayName("verifyStepUpOtp: wrong code throws, naming the problem, and never consumes the OTP record")
    void verifyStepUpOtp_wrongCode_throws() {
        User user = new User(); user.setId("u1"); user.setEmail("trader@example.com");
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));
        String storedHash = otpUtil.hashOtp("777888", "trader@example.com", "LIVE_AUTOTRADE_STEPUP");
        OtpRecord otp = new OtpRecord("trader@example.com", storedHash, "LIVE_AUTOTRADE_STEPUP");
        when(otpRepo.findByMobileAndPurposeOrderByCreatedAtDesc("trader@example.com", "LIVE_AUTOTRADE_STEPUP"))
            .thenReturn(List.of(otp));

        assertThatThrownBy(() -> authService.verifyStepUpOtp("u1", "LIVE_AUTOTRADE_STEPUP", "000000"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid");
        verify(mongoTemplate, never()).remove(any(), eq(OtpRecord.class));
    }

    @Test @DisplayName("verifyStepUpOtp: no code was requested at all (nothing on record for this purpose) throws a clear, distinct error")
    void verifyStepUpOtp_noOtpRequested_throws() {
        User user = new User(); user.setId("u1"); user.setEmail("trader@example.com");
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));
        when(otpRepo.findByMobileAndPurposeOrderByCreatedAtDesc("trader@example.com", "LIVE_AUTOTRADE_STEPUP"))
            .thenReturn(List.of());

        assertThatThrownBy(() -> authService.verifyStepUpOtp("u1", "LIVE_AUTOTRADE_STEPUP", "123456"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No OTP found");
    }

    @Test @DisplayName("verifyStepUpOtp: blank/missing code throws immediately, without even querying for an OTP record")
    void verifyStepUpOtp_blankCode_throwsWithoutQuery() {
        User user = new User(); user.setId("u1"); user.setEmail("trader@example.com");
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.verifyStepUpOtp("u1", "LIVE_AUTOTRADE_STEPUP", null))
            .isInstanceOf(IllegalArgumentException.class);
        verify(otpRepo, never()).findByMobileAndPurposeOrderByCreatedAtDesc(any(), any());
    }

    @Test @DisplayName("verifyStepUpOtp: a LOGIN-purpose OTP cannot be replayed as a step-up code -- distinct purposes stay fully separate")
    void verifyStepUpOtp_loginOtpCannotBeReplayedAsStepUp() {
        User user = new User(); user.setId("u1"); user.setEmail("trader@example.com");
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));
        // A real, valid LOGIN OTP exists for this identifier...
        String loginHash = otpUtil.hashOtp("999111", "trader@example.com", "LOGIN");
        OtpRecord loginOtp = new OtpRecord("trader@example.com", loginHash, "LOGIN");
        when(otpRepo.findByMobileAndPurposeOrderByCreatedAtDesc("trader@example.com", "LOGIN"))
            .thenReturn(List.of(loginOtp));
        // ...but nothing has ever been requested under the step-up purpose.
        when(otpRepo.findByMobileAndPurposeOrderByCreatedAtDesc("trader@example.com", "LIVE_AUTOTRADE_STEPUP"))
            .thenReturn(List.of());

        assertThatThrownBy(() -> authService.verifyStepUpOtp("u1", "LIVE_AUTOTRADE_STEPUP", "999111"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No OTP found");
    }

    @Test @DisplayName("checkMobile: returns registered status")
    void checkMobile_registered() {
        when(userRepo.existsByMobile("9000000002")).thenReturn(true);
        var resp = authService.checkMobile("9000000002");
        assertThat(resp.isSuccess()).isTrue();
    }

    /**
     * Tests for setAlertWebhookUrl's write path, including URL validation before save.
     */
    @Test @DisplayName("setAlertWebhookUrl: a valid, SSRF-safe URL is validated then saved onto the user")
    void setAlertWebhookUrl_validUrl_savesOntoUser() {
        User user = new User(); user.setId("u1");
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));
        when(userRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);
        when(webhookAlertService.isDestinationSafe("https://hooks.example.com/abc")).thenReturn(true);

        var resp = authService.setAlertWebhookUrl("u1", "https://hooks.example.com/abc");

        assertThat(resp.isSuccess()).isTrue();
        verify(userRepo).save(argThat(u -> "https://hooks.example.com/abc".equals(u.getAlertWebhookUrl())));
    }

    @Test @DisplayName("setAlertWebhookUrl: a URL that fails SSRF validation is rejected and never saved")
    void setAlertWebhookUrl_unsafeUrl_rejectedWithoutSaving() {
        when(webhookAlertService.isDestinationSafe("https://169.254.169.254/latest/meta-data")).thenReturn(false);

        var resp = authService.setAlertWebhookUrl("u1", "https://169.254.169.254/latest/meta-data");

        assertThat(resp.isSuccess()).isFalse();
        verify(userRepo, never()).save(any());
        verify(userRepo, never()).findById(any());
    }

    @Test @DisplayName("setAlertWebhookUrl: a blank/null url clears the webhook rather than being rejected")
    void setAlertWebhookUrl_blankUrl_clearsWebhook() {
        User user = new User(); user.setId("u1"); user.setAlertWebhookUrl("https://old.example.com/hook");
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));
        when(userRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        var resp = authService.setAlertWebhookUrl("u1", "");

        assertThat(resp.isSuccess()).isTrue();
        verify(userRepo).save(argThat(u -> u.getAlertWebhookUrl() == null));
        verify(webhookAlertService, never()).isDestinationSafe(any());
    }

    @Test @DisplayName("setAlertWebhookUrl: user not found")
    void setAlertWebhookUrl_userNotFound_returnsError() {
        when(userRepo.findById("ghost")).thenReturn(Optional.empty());
        when(webhookAlertService.isDestinationSafe(any())).thenReturn(true);

        var resp = authService.setAlertWebhookUrl("ghost", "https://hooks.example.com/abc");

        assertThat(resp.isSuccess()).isFalse();
    }

    @Test @DisplayName("logout: increments token version")
    void logout_incrementsVersion() {
        User user = new User(); user.setId("u1"); user.setTokenVersion(1L);
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));
        when(userRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        authService.logout("u1");
        verify(userRepo).save(argThat(u -> u.getTokenVersion() == 2L));
    }

    // ── Refresh token rotation atomicity ─────────────

    @Test
    @DisplayName("refreshToken: rotates atomically when the presented hash still matches — the normal, uncontested case")
    void refreshToken_rotatesSuccessfully() {
        String realRefreshToken = jwt.generateRefreshToken("user1");
        String realHash = jwt.hashToken(realRefreshToken);

        User user = new User();
        user.setId("user1");
        user.setMobile("9000000001");
        user.setRefreshTokenHash(realHash);
        user.setRefreshTokenExpiry(java.time.LocalDateTime.now().plusDays(1));
        when(userRepo.findById("user1")).thenReturn(Optional.of(user));

        User postRotation = new User();
        postRotation.setId("user1");
        postRotation.setMobile("9000000001");
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(User.class))).thenReturn(postRotation);

        var resp = authService.refreshToken(realRefreshToken);

        assertThat(resp.isSuccess()).isTrue();
        // Confirms the compare-and-swap query actually conditioned on the SAME hash this request
        // read and validated — not a blind unconditional update.
        ArgumentCaptor<org.springframework.data.mongodb.core.query.Query> queryCaptor =
            ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        verify(mongoTemplate).findAndModify(queryCaptor.capture(), any(), any(), eq(User.class));
        assertThat(queryCaptor.getValue().getQueryObject().toString()).contains(realHash);
    }

    @Test
    @DisplayName("refreshToken: a lost concurrency race (another request already rotated this exact token) returns a clear error, not a silently-broken success")
    void refreshToken_lostRaceReturnsError() {
        String realRefreshToken = jwt.generateRefreshToken("user1");
        String realHash = jwt.hashToken(realRefreshToken);

        User user = new User();
        user.setId("user1");
        user.setMobile("9000000001");
        user.setRefreshTokenHash(realHash);
        user.setRefreshTokenExpiry(java.time.LocalDateTime.now().plusDays(1));
        when(userRepo.findById("user1")).thenReturn(Optional.of(user));

        // Simulates: by the time this request's atomic update runs, a concurrent request already
        // won and rotated the hash away — the conditional query no longer matches anything.
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(User.class))).thenReturn(null);

        var resp = authService.refreshToken(realRefreshToken);

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getMessage()).containsIgnoringCase("already used");
    }

    @Test
    @DisplayName("refreshToken: presenting a previously-rotated-away token (not the current one) is detected as reuse, and revokes the user's ENTIRE current session as a precaution")
    void refreshToken_reuseOfPreviousToken_revokesEntireSession() {
        String oldRefreshToken = jwt.generateRefreshToken("user1");
        String oldHash = jwt.hashToken(oldRefreshToken);
        String currentHash = "some-different-current-hash"; // simulates rotation having already happened

        User user = new User();
        user.setId("user1");
        user.setMobile("9000000001");
        user.setRefreshTokenHash(currentHash); // NOT oldHash -- already rotated past it
        user.setPreviousRefreshTokenHash(oldHash); // the exact hash being reused
        user.setRefreshTokenExpiry(java.time.LocalDateTime.now().plusDays(1));
        user.setTokenVersion(5L);
        when(userRepo.findById("user1")).thenReturn(Optional.of(user));
        when(userRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        var resp = authService.refreshToken(oldRefreshToken);

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getMessage()).containsIgnoringCase("revoked");
        verify(userRepo).save(argThat(u ->
            u.getTokenVersion() == 6L // bumped, invalidating every issued access token too
            && u.getRefreshTokenHash() == null
            && u.getPreviousRefreshTokenHash() == null));
        // Never issues a fresh token pair for this reuse attempt.
        verify(mongoTemplate, never()).findAndModify(any(), any(), any(), eq(User.class));
    }

    @Test
    @DisplayName("refreshToken: a token that matches neither the current nor the previous hash is just an ordinary invalid token -- no session-wide revocation, since this isn't the specific reuse signal")
    void refreshToken_completelyUnrelatedToken_ordinaryInvalidError() {
        User user = new User();
        user.setId("user1");
        user.setMobile("9000000001");
        user.setRefreshTokenHash("current-hash");
        user.setPreviousRefreshTokenHash("previous-hash");
        when(userRepo.findById("user1")).thenReturn(Optional.of(user));

        var resp = authService.refreshToken(jwt.generateRefreshToken("user1")); // a fresh, unrelated token

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getMessage()).containsIgnoringCase("invalid");
        verify(userRepo, never()).save(any());
    }
}
