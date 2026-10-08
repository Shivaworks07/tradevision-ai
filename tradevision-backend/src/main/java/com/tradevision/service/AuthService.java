package com.tradevision.service;

import com.tradevision.dto.*;
import com.tradevision.model.*;
import com.tradevision.repository.*;
import com.tradevision.util.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository    userRepo;
    private final OtpRepository     otpRepo;
    private final JwtUtil           jwt;
    private final OtpUtil           otpUtil;
    private final EmailService      emailService;
    private final OtpRateLimitService otpRateLimitService;
    private final MongoTemplate     mongoTemplate;
    /**
     * Used by setAlertWebhookUrl below to validate a webhook URL at save time, reusing
     * WebhookAlertService's own isDestinationSafe check rather than duplicating SSRF
     * validation logic here.
     */
    private final WebhookAlertService webhookAlertService;

    @Value("${app.otp.expiry-minutes:5}") private int otpExpiryMin;
    private static final int MAX_OTP_ATTEMPTS  = 5;
    private static final int OTP_BLOCK_MINUTES = 15;
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    // ── Validate identifier (email or mobile) ─────────────────
    private boolean isEmail(String identifier) {
        return identifier != null && identifier.contains("@");
    }

    // ── Send OTP ──────────────────────────────────────────────
    public ApiResponse<?> sendOtp(String identifier, String purpose) {
        identifier = identifier.trim().toLowerCase();
        final String id = identifier;

        // Validate email format
        if (isEmail(id) && !EMAIL_PATTERN.matcher(id).matches())
            return ApiResponse.error("Invalid email address.");

        // Rate limit check
        findUser(id).ifPresent(user -> {
            if (user.getFailedOtpAttempts() >= MAX_OTP_ATTEMPTS) {
                LocalDateTime blockUntil = user.getLastFailedOtp() != null
                    ? user.getLastFailedOtp().plusMinutes(OTP_BLOCK_MINUTES) : LocalDateTime.MIN;
                if (LocalDateTime.now().isBefore(blockUntil))
                    throw new RuntimeException("Too many attempts. Try again in " + OTP_BLOCK_MINUTES + " minutes.");
                user.setFailedOtpAttempts(0);
                userRepo.save(user);
            }
        });

        // Send-frequency rate limit, independent of whether this identifier already has a
        // User record — applies even to brand-new signups, not just existing accounts.
        var rateLimit = otpRateLimitService.checkAndRecord(id, purpose);
        if (!rateLimit.allowed()) {
            return ApiResponse.error(rateLimit.reason());
        }

        // Generate and save OTP
        otpRepo.deleteByMobileAndPurpose(id, purpose);
        String code = otpUtil.generateCode();
        otpRepo.save(new OtpRecord(id, otpUtil.hashOtp(code, id, purpose), purpose));

        // Send via email or SMS. Delivery status is checked and surfaced to the caller rather
        // than assumed, since a misconfigured deployment (mail disabled, no SMS provider
        // configured) should report failure instead of claiming the OTP was sent.
        boolean delivered = isEmail(id) ? emailService.sendOtp(id, code) : otpUtil.sendSms(id, code);
        if (!delivered) {
            return ApiResponse.error("OTP delivery is currently unavailable. Please try again later or contact support.");
        }
        return ApiResponse.ok("OTP sent to " + id);
    }

    /**
     * Issues a fresh OTP for step-up (re-)authentication ahead of a high-stakes, mid-session
     * action such as RiskProfileService.authorizeLiveAutoTrade, which enables real-money
     * autonomous trading. The code is always sent to the caller's own on-file email/mobile,
     * never to an attacker-suppliable destination, so this can't be used to push a
     * verification code anywhere else. Reuses this class's existing OTP infrastructure (rate
     * limiting, hashing, expiry) rather than a separate pipeline.
     */
    public ApiResponse<?> sendStepUpOtp(String userId, String purpose) {
        var userOpt = userRepo.findById(userId);
        if (userOpt.isEmpty()) return ApiResponse.error("User not found.");
        User user = userOpt.get();
        String identifier = user.getEmail() != null ? user.getEmail() : user.getMobile();
        if (identifier == null || identifier.isBlank())
            return ApiResponse.error("No verified email or mobile on file to send a step-up verification code to.");
        return sendOtp(identifier, purpose);
    }

    /**
     * Verifies a step-up OTP for a mid-session, high-stakes action (see sendStepUpOtp's own
     * javadoc and RiskProfileService.authorizeLiveAutoTrade, which calls this). Always checks
     * against the CALLER'S OWN on-file identifier -- never a caller-supplied one -- so this
     * can't be used to verify a code sent to somewhere else. Throws IllegalArgumentException
     * with a user-facing message on any failure (no code sent, expired, wrong, already used,
     * rate limited); the caller surfaces that message directly, exactly like every other
     * authorizeLiveAutoTrade refusal.
     */
    public void verifyStepUpOtp(String userId, String purpose, String code) {
        var userOpt = userRepo.findById(userId);
        if (userOpt.isEmpty()) throw new IllegalArgumentException("User not found.");
        User user = userOpt.get();
        String identifier = user.getEmail() != null ? user.getEmail() : user.getMobile();
        if (identifier == null || identifier.isBlank())
            throw new IllegalArgumentException("No verified email or mobile on file to verify a step-up code against.");
        if (code == null || code.isBlank())
            throw new IllegalArgumentException("A fresh verification code is required for this action. Request one first via "
                + "POST /api/broker/risk-profile/{credentialId}/authorize-live-autotrade/request-otp.");
        String error = verifyOtpCodeOnly(identifier.trim().toLowerCase(), purpose, code);
        if (error != null) throw new IllegalArgumentException(error);
    }

    /**
     * The shared core of OTP code verification -- attempt-limit claim, hash compare, atomic
     * single-use consumption -- factored out so both login/registration (verifyOtp) and the
     * step-up flow (verifyStepUpOtp) go through the exact same hardening, without the step-up
     * path picking up verifyOtp's login/session-issuance side effects, which don't belong in a
     * mid-session "prove it's still you" check. Returns null on success, or a user-facing
     * error message on failure.
     */
    private String verifyOtpCodeOnly(String identifier, String purpose, String code) {
        var otpList = otpRepo.findByMobileAndPurposeOrderByCreatedAtDesc(identifier, purpose);
        if (otpList.isEmpty()) return "No OTP found. Request a new one.";

        OtpRecord otp = otpList.get(0);
        if (otp.getCreatedAt().plusMinutes(otpExpiryMin).isBefore(LocalDateTime.now()))
            return "OTP expired. Request a new one.";

        // Claims an attempt slot via a single atomic MongoDB conditional update on the OTP
        // record itself (WHERE id=X AND used=false AND attemptCount < MAX) that increments
        // attemptCount and fails as ONE indivisible operation, rather than a separate
        // read-then-check-then-write with its own race window. This means two concurrent
        // wrong-guess requests against the same OTP cannot both slip through and both
        // increment past the limit -- Mongo evaluates the filter and applies the $inc
        // atomically per document, so only requests that see attemptCount still under the
        // limit at the exact moment of their own update succeed.
        var attemptClaim = mongoTemplate.updateFirst(
            new Query(where("id").is(otp.getId()).and("used").is(false).and("attemptCount").lt(MAX_OTP_ATTEMPTS)),
            new Update().inc("attemptCount", 1),
            OtpRecord.class);
        if (attemptClaim.getModifiedCount() == 0) {
            // Either already used (a concurrent request won first) or genuinely out of attempts
            // -- same rejection message either way, deliberately not distinguishing which, to
            // avoid handing an attacker a signal about which guess was "closer" to succeeding.
            return "Too many incorrect attempts for this OTP. Request a new one.";
        }

        // Compare hashes only -- the raw OTP is never stored. MessageDigest.isEqual is
        // constant-time, avoiding a timing side-channel on the comparison itself.
        String submittedHash = otpUtil.hashOtp(code, identifier, purpose);
        if (!java.security.MessageDigest.isEqual(
                otp.getOtpHash().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                submittedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            incrementFailures(identifier);
            return "Invalid OTP. Please check and try again.";
        }

        // Consumes the OTP via an atomic conditional delete rather than a plain delete, so
        // that only one of two concurrent requests presenting the same correct code can ever
        // win. A plain delete here would let both requests pass the hash comparison above
        // before either removes the record, and both would then proceed to create a session.
        // deleteCount==0 means a concurrent request already consumed this exact OTP record
        // between this request's read and this claim -- correctly rejected rather than
        // silently issuing a second session for a code that's already been spent.
        var consumeClaim = mongoTemplate.remove(new Query(where("id").is(otp.getId())), OtpRecord.class);
        if (consumeClaim.getDeletedCount() == 0) {
            return "This OTP has already been used. Request a new one.";
        }
        return null;
    }

    // ── Verify OTP + login/register ───────────────────────────
    public ApiResponse<?> verifyOtp(OtpVerifyRequest req) {
        String identifier = (req.getEmail() != null ? req.getEmail() : req.getMobile());
        if (identifier == null || identifier.isBlank())
            return ApiResponse.error("Email is required.");
        identifier = identifier.trim().toLowerCase();

        // The attempt-limit claim / hash compare / single-use consumption lives in one shared
        // helper (see its own javadoc) so the step-up flow reuses the exact same hardening
        // instead of a second copy.
        String otpError = verifyOtpCodeOnly(identifier, req.getPurpose(), req.getCode());
        if (otpError != null) return ApiResponse.error(otpError);

        // Find or create user
        final String finalId = identifier;
        User user = findUser(identifier).orElseGet(() -> {
            User nu = new User();
            if (isEmail(finalId)) nu.setEmail(finalId);
            else                  nu.setMobile(finalId);
            nu.setFirstName(req.getFirstName() != null ? req.getFirstName() : "Trader");
            nu.setLastName(req.getLastName() != null ? req.getLastName() : "");
            return nu;
        });
        user.setVerified(true);
        user.setLastLogin(LocalDateTime.now());
        user.setFailedOtpAttempts(0);
        user = userRepo.save(user);

        // Issue tokens
        String accessToken  = jwt.generateToken(
            user.getEmail() != null ? user.getEmail() : user.getMobile(),
            user.getId(), user.getTokenVersion());
        String refreshToken = jwt.generateRefreshToken(user.getId());
        user.setRefreshTokenHash(jwt.hashToken(refreshToken));
        user.setRefreshTokenExpiry(LocalDateTime.now().plusSeconds(jwt.getRefreshExpirationMs()/1000));
        userRepo.save(user);

        return ApiResponse.ok("Authenticated.", new TokenResponse(accessToken, refreshToken, toDto(user)));
    }

    // ── Refresh ───────────────────────────────────────────────
    public ApiResponse<?> refreshToken(String refreshToken) {
        try {
            if (!jwt.isValid(refreshToken) || !jwt.isRefreshToken(refreshToken))
                return ApiResponse.error("Invalid refresh token.");
            String userId = jwt.getMobile(refreshToken);
            var userOpt = userRepo.findById(userId);
            if (userOpt.isEmpty()) return ApiResponse.error("User not found.");
            User user = userOpt.get();
            String presentedHash = jwt.hashToken(refreshToken);
            if (!presentedHash.equals(user.getRefreshTokenHash())) {
                // Refresh-token reuse detection (full context in
                // User.previousRefreshTokenHash's own field comment): a mismatch here is
                // checked specifically against the JUST-rotated-away hash rather than treated
                // as a generic failure. A legitimate client always uses its most recently
                // issued refresh token; presenting the one just before it means whoever's
                // presenting this token isn't the legitimate client that received the latest
                // rotation -- a real signal of token theft, not just an ordinary stale/
                // already-used request. Responds by revoking the CURRENT valid session too
                // (not just rejecting this one request), since if a copy of an old token
                // leaked, the current one may well have too.
                if (presentedHash.equals(user.getPreviousRefreshTokenHash())) {
                    log.error("Refresh token reuse detected for user {} -- a previously-rotated-away token was presented again. "
                        + "Revoking this user's entire current session as a precaution.", user.getId());
                    user.setTokenVersion(user.getTokenVersion() + 1);
                    user.setRefreshTokenHash(null);
                    user.setPreviousRefreshTokenHash(null);
                    user.setRefreshTokenExpiry(null);
                    userRepo.save(user);
                    return ApiResponse.error("This session has been revoked for security reasons (a previously-used refresh token was "
                        + "presented again). Please log in again.");
                }
                return ApiResponse.error("Invalid refresh token.");
            }
            if (user.getRefreshTokenExpiry() != null &&
                user.getRefreshTokenExpiry().isBefore(LocalDateTime.now()))
                return ApiResponse.error("Session expired. Please login again.");

            String newAccess  = jwt.generateToken(
                user.getEmail() != null ? user.getEmail() : user.getMobile(),
                user.getId(), user.getTokenVersion());
            String newRefresh = jwt.generateRefreshToken(user.getId());
            String newHash = jwt.hashToken(newRefresh);
            LocalDateTime newExpiry = LocalDateTime.now().plusSeconds(jwt.getRefreshExpirationMs()/1000);

            // Atomic compare-and-swap on the refresh token: only succeeds if refreshTokenHash
            // still equals exactly the hash this request read and validated above. This
            // closes the race between two concurrent requests presenting the same still-valid
            // token -- without it, both could pass the read check and both write, with the
            // last write silently winning. Here the loser gets a clear "already used" error
            // instead of a refresh token that looks successful but was immediately invalidated
            // by the winner.
            //
            // This closes the race itself; reuse detection (revoking the entire session the
            // instant a rotated-away token is presented again) is implemented separately
            // above, at the mismatch branch -- see User.previousRefreshTokenHash's own field
            // comment for the full design.
            var updated = mongoTemplate.findAndModify(
                new org.springframework.data.mongodb.core.query.Query(
                    org.springframework.data.mongodb.core.query.Criteria.where("id").is(user.getId())
                        .and("refreshTokenHash").is(presentedHash)),
                new org.springframework.data.mongodb.core.query.Update()
                    .set("refreshTokenHash", newHash).set("previousRefreshTokenHash", presentedHash).set("refreshTokenExpiry", newExpiry),
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), User.class);

            if (updated == null) {
                log.warn("Refresh token rotation lost a concurrency race for user {} — token was already rotated by a concurrent request.", user.getId());
                return ApiResponse.error("This refresh token was already used. Please log in again if this wasn't expected.");
            }
            return ApiResponse.ok("Token refreshed.", new TokenResponse(newAccess, newRefresh, toDto(updated)));
        } catch (Exception e) { return ApiResponse.error("Token refresh failed."); }
    }

    // ── Logout ────────────────────────────────────────────────
    public ApiResponse<?> logout(String userId) {
        userRepo.findById(userId).ifPresent(user -> {
            user.setTokenVersion(user.getTokenVersion() + 1);
            user.setRefreshTokenHash(null);
            user.setRefreshTokenExpiry(null);
            userRepo.save(user);
        });
        return ApiResponse.ok("Logged out.");
    }

    // ── Profile ───────────────────────────────────────────────
    public ApiResponse<?> getProfile(String userId) {
        return userRepo.findById(userId)
            .map(u -> ApiResponse.ok("OK", toDto(u)))
            .orElse(ApiResponse.error("User not found."));
    }

    public ApiResponse<?> updateProfile(String userId, UserDto req) {
        return userRepo.findById(userId).map(user -> {
            if (req.getFirstName() != null) user.setFirstName(req.getFirstName());
            if (req.getLastName()  != null) user.setLastName(req.getLastName());
            if (req.getTradingPlatform() != null) user.setTradingPlatform(req.getTradingPlatform());
            return ApiResponse.ok("Updated.", toDto(userRepo.save(user)));
        }).orElse(ApiResponse.error("User not found."));
    }

    /**
     * Sets or clears the account's alert webhook URL. A blank/null url clears the field,
     * letting an account holder turn the feature back off without a separate "disable"
     * endpoint. A non-blank url is validated with the same SSRF safety check
     * WebhookAlertService.send re-runs on every actual delivery, so a user gets immediate,
     * save-time feedback that their URL is invalid rather than silently having every future
     * alert delivery fail. This is deliberately a courtesy check, not a substitute for
     * send-time validation: a hostname can legitimately resolve differently between save-time
     * and send-time (DNS rebinding, or simply changing over time), which is why
     * WebhookAlertService's own javadoc documents re-validating fresh on every send.
     */
    public ApiResponse<?> setAlertWebhookUrl(String userId, String url) {
        String trimmed = url != null ? url.trim() : null;
        if (trimmed != null && !trimmed.isEmpty() && !webhookAlertService.isDestinationSafe(trimmed)) {
            return ApiResponse.error("Webhook URL must be a valid HTTPS URL that does not resolve to a private, "
                + "loopback, or link-local address.");
        }
        return userRepo.findById(userId).map(user -> {
            user.setAlertWebhookUrl(trimmed == null || trimmed.isEmpty() ? null : trimmed);
            userRepo.save(user);
            return ApiResponse.ok(trimmed == null || trimmed.isEmpty() ? "Alert webhook cleared." : "Alert webhook saved.");
        }).orElse(ApiResponse.error("User not found."));
    }

    public ApiResponse<?> checkEmail(String email) {
        return ApiResponse.ok("OK", java.util.Map.of("registered", userRepo.existsByEmail(email.trim().toLowerCase())));
    }

    public ApiResponse<?> checkMobile(String mobile) {
        return ApiResponse.ok("OK", java.util.Map.of("registered", userRepo.existsByMobile(mobile)));
    }

    // ── Favourites ────────────────────────────────────────────
    public ApiResponse<?> toggleFavorite(String userId, FavoriteRequest req) {
        return userRepo.findById(userId).map(user -> {
            String sym = req.getSymbol().toUpperCase().trim();
            boolean add = req.isAdd();
            switch (req.getType().toUpperCase()) {
                case "STOCK"  -> { if (add) user.getFavoriteStocks().add(sym);  else user.getFavoriteStocks().remove(sym); }
                case "CRYPTO" -> { if (add) user.getFavoriteCryptos().add(sym); else user.getFavoriteCryptos().remove(sym); }
                case "FOREX"  -> { if (add) user.getFavoriteForex().add(sym);   else user.getFavoriteForex().remove(sym); }
            }
            return ApiResponse.ok(add ? "Added" : "Removed", toDto(userRepo.save(user)));
        }).orElse(ApiResponse.error("User not found."));
    }

    // ── Helpers ───────────────────────────────────────────────
    private java.util.Optional<User> findUser(String identifier) {
        if (isEmail(identifier)) return userRepo.findByEmail(identifier);
        return userRepo.findByMobile(identifier);
    }

    /**
     * Increments the failed-OTP-attempts counter atomically via $inc rather than a
     * read-modify-write, so two concurrent wrong-guess requests can't both read the same
     * starting count and have one increment silently lost -- which would understate how many
     * real failed attempts happened and could delay a lockout that should have triggered.
     */
    private void incrementFailures(String identifier) {
        findUser(identifier).ifPresent(u -> {
            mongoTemplate.updateFirst(
                new Query(where("id").is(u.getId())),
                new Update().inc("failedOtpAttempts", 1).set("lastFailedOtp", LocalDateTime.now()),
                User.class);
        });
    }

    private UserDto toDto(User u) {
        UserDto dto = new UserDto();
        dto.setId(u.getId()); dto.setFirstName(u.getFirstName()); dto.setLastName(u.getLastName());
        dto.setEmail(u.getEmail()); dto.setMobile(u.getMobile());
        dto.setTradingPlatform(u.getTradingPlatform());
        dto.setFavoriteStocks(u.getFavoriteStocks()); dto.setFavoriteCryptos(u.getFavoriteCryptos());
        dto.setFavoriteForex(u.getFavoriteForex());
        dto.setAlertWebhookUrl(u.getAlertWebhookUrl());
        return dto;
    }
}
