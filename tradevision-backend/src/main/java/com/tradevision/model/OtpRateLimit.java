package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Review finding (this doc, "BLOCKER #7" — OTP resend has insufficient rate limiting): the
 * previous rate limiting only counted FAILED VERIFICATION attempts, and only for identifiers
 * that already had a User record — a brand-new identifier could be hammered for OTP sends with
 * zero limiting (SMS cost, email abuse, provider rate-limit problems). This tracks send
 * frequency directly, per identifier+purpose, independent of whether a User exists yet.
 */
@Data @NoArgsConstructor
@Document(collection = "otp_rate_limits")
public class OtpRateLimit {
    @Id private String id;
    @Indexed(unique = true) private String key; // identifier + "|" + purpose
    private LocalDateTime windowStart = LocalDateTime.now();
    private int sendCount = 0;
    private LocalDateTime lastSentAt;
}
