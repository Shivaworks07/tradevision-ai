package com.tradevision.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.regex.Pattern;

@Component
public class OtpUtil {

    // Mobile numbers are masked (see maskMobile below) everywhere they're logged, since the
    // MSG91 send/failure log lines run during real, production SMS sends.
    private static final Logger log = LoggerFactory.getLogger(OtpUtil.class);

    // ── To enable real SMS, add these to application.properties ──
    // sms.provider=msg91
    // sms.msg91.auth-key=YOUR_MSG91_AUTH_KEY
    // sms.msg91.template-id=YOUR_TEMPLATE_ID
    // sms.msg91.sender-id=TVISION
    //
    // OR for Twilio:
    // sms.provider=twilio
    // sms.twilio.account-sid=YOUR_SID
    // sms.twilio.auth-token=YOUR_TOKEN
    // sms.twilio.from-number=+1XXXXXXXXXX

    @Value("${sms.provider:console}")         private String provider;
    @Value("${sms.msg91.auth-key:}")          private String msg91AuthKey;
    @Value("${sms.msg91.template-id:}")       private String msg91TemplateId;
    @Value("${sms.msg91.sender-id:TVISION}")  private String msg91SenderId;
    @Value("${app.otp.hmac-secret}")          private String otpHmacSecret;
    // Separate from "which provider" (sms.provider, default "console"): whether console output
    // is actually allowed to print the real code. Defaults to false and must be consciously
    // turned on for local development, so a deployment that forgot to configure sms.provider
    // fails loudly instead of quietly leaking OTPs into logs.
    @Value("${app.otp.allow-console-fallback:false}") private boolean allowConsoleFallback;

    private final SecureRandom rng = new SecureRandom();

    /** Just generate code — no side effects */
    public String generateCode() {
        return String.valueOf(100000 + rng.nextInt(900000));
    }

    /**
     * HMAC-SHA256 digest of the OTP, bound to the identifier and purpose so the same 6-digit
     * code for two different users/purposes doesn't hash identically. Stored instead of the raw
     * code; verification recomputes this and compares digests, never the raw value.
     */
    public String hashOtp(String otp, String identifier, String purpose) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(otpHmacSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((otp + "|" + identifier + "|" + purpose).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash OTP", e);
        }
    }

    /**
     * Sends the OTP via the configured provider. Returns true only when delivery genuinely
     * happened — a real send was attempted, or the explicit dev console fallback fired — so
     * callers can tell the user accurately whether an OTP actually went anywhere, rather than
     * assuming success for a misconfigured provider or a correctly-refused console fallback.
     */
    public boolean sendSms(String mobile, String code) {
        return switch (provider) {
            case "msg91" -> sendViaMSG91(mobile, code);
            case "twilio" -> {
                // Never actually implemented — honest about that rather than claiming success for a no-op.
                log.warn("[Twilio] Not configured -- SMS not sent. Add real Twilio integration or use sms.provider=msg91.");
                yield false;
            }
            default -> logToConsole(mobile, code);
        };
    }

    @Deprecated
    public String generate(String mobile) {
        String code = generateCode();

        switch (provider) {
            case "msg91"   -> sendViaMSG91(mobile, code);
            case "twilio"  -> log.warn("[Twilio integration - add to application.properties]");
            default        -> logToConsole(mobile, code);
        }
        return code;
    }

    private boolean logToConsole(String mobile, String code) {
        if (!allowConsoleFallback) {
            log.info("[OtpUtil] No SMS provider configured (sms.provider is unset or 'console') and "
                + "app.otp.allow-console-fallback is not enabled -- refusing to log the OTP. For local development, "
                + "set app.otp.allow-console-fallback=true explicitly. For a real deployment, configure a real "
                + "sms.provider instead.");
            return false;
        }
        log.info("\n==========================================\n  OTP for {}: {}\n  (Set sms.provider=msg91 for real SMS)\n==========================================",
            maskMobile(mobile), code);
        return true;
    }

    /**
     * Matches a bare 10-digit Indian mobile number not starting with 0, consistent with the
     * hardcoded "91" country-code prefix sendViaMSG91 applies below. Rejecting anything else
     * means a malformed or malicious value (e.g. one embedding "&otp=000000" to smuggle extra
     * query parameters into the request this method builds) is never even attempted, rather than
     * merely rendered harmless by URL-encoding.
     */
    private static final Pattern INDIAN_MOBILE_PATTERN = Pattern.compile("^[1-9]\\d{9}$");

    // Package-private (not private) specifically so this validation can be unit-tested in
    // isolation from the real network call sendViaMSG91 makes -- see OtpUtilTest.
    static boolean isValidIndianMobile(String mobile) {
        return mobile != null && INDIAN_MOBILE_PATTERN.matcher(mobile).matches();
    }

    private boolean sendViaMSG91(String mobile, String code) {
        if (!isValidIndianMobile(mobile)) {
            log.warn("[MSG91] Refusing to send -- mobile number failed format validation (must be exactly 10 digits, not starting with 0).");
            return false;
        }
        try {
            // MSG91 OTP API v5. Every value placed into the query string is URL-encoded,
            // including msg91TemplateId and code which are both this application's own
            // configured/generated values, since encoding them costs nothing and removes any
            // need to reason about which values are safe to skip.
            String url = "https://control.msg91.com/api/v5/otp?template_id=" + urlEncode(msg91TemplateId)
                + "&mobile=" + urlEncode("91" + mobile)
                + "&otp=" + urlEncode(code);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                // MSG91 accepts the authkey as a request header (documented alongside the
                // query-string form); passed here so it never appears in the URL itself, where
                // it would otherwise be captured verbatim by an intermediate proxy's access log,
                // HTTP client debug logging, or browser/tool history.
                .header("authkey", msg91AuthKey)
                .GET()
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("[MSG91] Sent OTP to {} | status: delivered", maskMobile(mobile));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            // Logs the failure, never the code — an SMS provider outage must not write usable
            // OTPs into application logs.
            log.warn("[MSG91] Failed to send SMS to {}: {}", maskMobile(mobile), e.getMessage());
            return false;
        }
    }

    // Package-private (not private), same reasoning as isValidIndianMobile above.
    static String urlEncode(String value) {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8);
    }

    /**
     * Masks a mobile number for logging, keeping only the last 2 digits (e.g. "********89" for a
     * 10-digit Indian mobile) — enough to spot-check which number a log line is about without
     * writing a usable phone number into application logs.
     */
    static String maskMobile(String mobile) {
        if (mobile == null || mobile.isBlank()) return "(none)";
        int len = mobile.length();
        if (len <= 2) return "*".repeat(len);
        return "*".repeat(len - 2) + mobile.substring(len - 2);
    }
}
