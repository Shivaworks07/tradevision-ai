package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Tracks OTP send frequency per identifier+purpose, independent of whether a User record
 * exists yet for that identifier — this is what lets a brand-new signup identifier be rate
 * limited on OTP sends (not just failed verification attempts) to control SMS/email cost and
 * provider abuse.
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
