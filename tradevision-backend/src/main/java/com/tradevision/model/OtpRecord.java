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
    /** An HMAC-SHA256 digest of the OTP (see AuthService/OtpUtil), never the raw code — a
     *  database read never yields a directly-usable OTP. */
    private String otpHash;
    private String purpose;
    private LocalDateTime createdAt;
    private boolean used = false;
    // Tracks failed verification attempts against this specific OTP record, which always
    // exists once sendOtp() has run regardless of whether a User record exists yet (a
    // brand-new signup has no User until verification succeeds, so this can't live on User).
    // See AuthService.verifyOtp's own javadoc for the atomic check-and-increment this backs.
    private int attemptCount = 0;
    @Indexed(expireAfterSeconds = 300) private LocalDateTime expiresAt;

    public OtpRecord() {}
    public OtpRecord(String mobile, String otpHash, String purpose) {
        this.mobile    = mobile; this.otpHash = otpHash; this.purpose = purpose;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = LocalDateTime.now().plusMinutes(5);
    }
}
