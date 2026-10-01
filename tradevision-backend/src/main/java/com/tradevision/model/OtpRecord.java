package com.tradevision.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.LocalDateTime;

@Data
@Document(collection = "otp_records")
public class OtpRecord {
    @Id private String id;
    private String mobile;
    /** Review finding (this doc, "BLOCKER #5" — OTP stored in plaintext): this now stores an
     *  HMAC-SHA256 digest of the OTP (see AuthService/OtpUtil), never the raw code. A database
     *  read no longer yields a directly-usable OTP. */
    private String otpHash;
    private String purpose;
    private LocalDateTime createdAt;
    private boolean used = false;
    // Review finding ("OTP verification has no effective attempt/rate limit" -- P0): confirmed
    // real -- verifyOtp() incremented User.failedOtpAttempts on failure but never CHECKED it
    // before allowing another guess, and failedOtpAttempts itself doesn't even exist yet for a
    // brand-new signup (no User record until verification succeeds). Tracked here instead,
    // scoped to this exact OTP record, which always exists once sendOtp() has run regardless of
    // whether the user does. See AuthService.verifyOtp's own updated javadoc for the atomic
    // check-and-increment this field backs.
    private int attemptCount = 0;
    @Indexed(expireAfterSeconds = 300) private LocalDateTime expiresAt;

    public OtpRecord() {}
    public OtpRecord(String mobile, String otpHash, String purpose) {
        this.mobile    = mobile; this.otpHash = otpHash; this.purpose = purpose;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = LocalDateTime.now().plusMinutes(5);
    }
}
