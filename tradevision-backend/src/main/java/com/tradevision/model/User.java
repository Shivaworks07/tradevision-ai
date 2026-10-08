package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Data @NoArgsConstructor
@Document(collection = "users")
public class User {
    @Id private String id;
    private String firstName;
    private String lastName;
    @Indexed(unique = true, sparse = true) private String email;   // primary login
    /**
     * A generic outbound webhook URL an account holder can point at Slack, Telegram, PagerDuty,
     * or any other service that accepts an incoming webhook, so incidents (see
     * IncidentService) can reach their own alerting tool without this codebase needing a
     * separate provider-specific integration and credentials for each one. Optional —
     * null/blank means webhook delivery is simply skipped for that user, best-effort and never
     * blocking the underlying safety action, same as email alerts.
     */
    private String alertWebhookUrl;
    @Indexed(unique = true, sparse = true) private String mobile;  // optional
    private String tradingPlatform;
    private boolean verified     = false;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime lastLogin;
    private Set<String> favoriteStocks   = new HashSet<>();
    private Set<String> favoriteCryptos  = new HashSet<>();
    private Set<String> favoriteForex    = new HashSet<>();

    // ── Security ──────────────────────────────────────────────
    /** Incremented on logout — invalidates all issued tokens at once */
    private long   tokenVersion    = 1L;
    /** Hashed refresh token stored server-side for rotation */
    private String refreshTokenHash;
    // The previously-valid refresh token hash, kept for exactly one rotation cycle to detect
    // reuse -- if THIS hash (not the current one) is ever presented again, it means whoever
    // presented it holds an already-rotated-away token, which a legitimate client would never
    // do (it would be using the newly-rotated token instead). That is the signal of a stolen
    // refresh token being replayed after the legitimate client already rotated past it.
    private String previousRefreshTokenHash;
    /** When refresh token expires */
    private LocalDateTime refreshTokenExpiry;
    /** Role: USER or ADMIN */
    private String role = "USER";

    /** Track failed OTP attempts for rate limiting */
    private int    failedOtpAttempts = 0;
    private LocalDateTime lastFailedOtp;
}
