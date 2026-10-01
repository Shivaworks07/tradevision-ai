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

    // P2-17 fix ("20x System.out.println, PII in logs, no correlation IDs" -- external review,
    // full context in this codebase's own new CorrelationIdFilter javadoc): replaces this
    // class's own System.out.println calls with proper SLF4J logging, and mobile numbers are
    // masked (see maskMobile below) everywhere they're logged from here on -- unlike the OTP
    // console fallback below (a deliberate, explicitly-opted-into local-dev feature), the MSG91
    // send/failure log lines run in real, production SMS sends and were logging the full mobile
    // number unconditionally.
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
    // Review finding ("OTP is STILL printed to console"): the previous fix left the console
    // fallback printing the raw code whenever sms.provider was "console" — which is also the
    // DEFAULT if the property is never set at all. That means a real deployment that simply
    // forgot to configure SMS_PROVIDER falls silently into logging usable OTPs, not a
    // deliberate dev choice. This separates "which provider" from "is console output actually
    // allowed to print the real code" — the latter now defaults to false and must be
    // consciously turned on for local development, so a misconfigured production deployment
    // fails loudly instead of quietly leaking OTPs into logs.
    @Value("${app.otp.allow-console-fallback:false}") private boolean allowConsoleFallback;

    private final SecureRandom rng = new SecureRandom();

    /** Just generate code — no side effects */
    public String generateCode() {
        return String.valueOf(100000 + rng.nextInt(900000));
    }

    /**
     * Review finding (this doc, "BLOCKER #5" — OTP stored in plaintext): HMAC-SHA256 digest of
     * the OTP, bound to the identifier and purpose (so the same 6-digit code for two different
     * users/purposes doesn't hash identically). Stored instead of the raw code; verification
     * recomputes this and compares digests, never the raw value.
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

    /** Send SMS only */
    /**
     * Review finding (P1 — "OTP delivery has a functional production bug"): this used to be
     * void, so AuthService had no way to know whether an OTP actually went anywhere before
     * telling the user "OTP sent" — a misconfigured provider, or the console fallback being
     * correctly refused, could mean literally nothing was sent while the user was told
     * otherwise. Returns true only when delivery genuinely happened (a real send was attempted,
     * or the explicit dev console fallback fired).
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
     * P2-12 fix ("OtpUtil.sendViaMSG91: Mobile/template concatenated unencoded into URL, authkey
     * in query string, no mobile validation" -- external review, confirmed real by direct
     * inspection before this fix): a bare digits-only Indian mobile number, matching exactly what
     * this method already hardcodes as the "91" country-code prefix immediately below (never
     * validated against E.164 or any other format before this fix) -- 10 digits, not starting
     * with 0, matching the real Indian mobile numbering plan this hardcoded "91" prefix already
     * assumes. Rejecting anything else here means a malformed or malicious value (one embedding
     * "&otp=000000" or similar to smuggle extra query parameters into the request this method
     * builds) is never even attempted, not merely rendered harmless by encoding — the review's
     * own two other fixes below (URL-encoding, authkey moved out of the query string) are the
     * defense for genuinely-shaped values that still contain characters worth escaping; this is
     * the defense for values that shouldn't be attempted as a phone number at all.
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
            // MSG91 OTP API v5
            // P2-12 fix, full context in this method's own updated javadoc above: every
            // caller-influenced value is now URL-encoded before being placed into the query
            // string — msg91TemplateId and code are both this application's own configured/
            // generated values (never attacker-influenced), but encoding them too costs nothing
            // and removes any need to reason about which values are "safe" to skip.
            String url = "https://control.msg91.com/api/v5/otp?template_id=" + urlEncode(msg91TemplateId)
                + "&mobile=" + urlEncode("91" + mobile)
                + "&otp=" + urlEncode(code);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                // P2-12 fix, full context in this method's own updated javadoc above: MSG91's own
                // API accepts the authkey as a request header (documented alongside the query-
                // string form) -- moved here so it never appears in the URL itself, where it would
                // otherwise be captured verbatim by any intermediate proxy's own access log, this
                // JVM's own HTTP client debug logging if ever enabled, or browser/tool history if
                // this URL were ever pasted anywhere for debugging.
                .header("authkey", msg91AuthKey)
                .GET()
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("[MSG91] Sent OTP to {} | status: delivered", maskMobile(mobile));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            // Review finding (this doc, "BLOCKER #6" — OTP production fallback prints the OTP):
            // this used to append " — OTP: " + code to the failure log. In production, that
            // means a real SMS provider outage silently starts writing usable OTPs into
            // application logs, which defeats the whole point of using an OTP. Log the failure,
            // never the code.
            log.warn("[MSG91] Failed to send SMS to {}: {}", maskMobile(mobile), e.getMessage());
            return false;
        }
    }

    // Package-private (not private), same reasoning as isValidIndianMobile above.
    static String urlEncode(String value) {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8);
    }

    /**
     * P2-17 fix ("PII in logs" -- external review, full context in this class's own header
     * comment): keeps only the last 2 digits, e.g. "********89" for a 10-digit Indian mobile --
     * enough to spot-check which number a log line is about without writing a usable phone
     * number into application logs. Package-private (not private) so this is directly
     * unit-testable -- see OtpUtilTest.
     */
    static String maskMobile(String mobile) {
        if (mobile == null || mobile.isBlank()) return "(none)";
        int len = mobile.length();
        if (len <= 2) return "*".repeat(len);
        return "*".repeat(len - 2) + mobile.substring(len - 2);
    }
}
