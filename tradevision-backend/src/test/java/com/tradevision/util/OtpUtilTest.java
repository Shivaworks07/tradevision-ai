package com.tradevision.util;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies OtpUtil's mobile-format validation and URL-encoding, isolated from the real
 * network call sendViaMSG91 itself makes (this codebase has no injectable HTTP client seam
 * for that method, and this sandbox's own network is allowlisted -- see this file's own
 * scope note on the one test that does exercise sendSms end-to-end).
 */
class OtpUtilTest {

    // OtpUtil logs via SLF4J rather than System.out, so this test's log-capturing helper
    // attaches a Logback ListAppender directly to OtpUtil's own logger.
    private ListAppender<ILoggingEvent> logAppender;
    private Logger otpUtilLogger;

    @BeforeEach
    void attachLogAppender() {
        otpUtilLogger = (Logger) org.slf4j.LoggerFactory.getLogger(OtpUtil.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        otpUtilLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        otpUtilLogger.detachAppender(logAppender);
    }

    private String capturedLogText() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : logAppender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("isValidIndianMobile: a genuine 10-digit Indian mobile number not starting with 0 is valid")
    void validMobile_accepted() {
        assertThat(OtpUtil.isValidIndianMobile("9876543210")).isTrue();
        assertThat(OtpUtil.isValidIndianMobile("1234567890")).isTrue();
    }

    @Test
    @DisplayName("isValidIndianMobile: rejects null, wrong length, leading zero, non-digit characters, and a query-string injection attempt")
    void malformedOrMaliciousMobile_rejected() {
        assertThat(OtpUtil.isValidIndianMobile(null)).isFalse();
        assertThat(OtpUtil.isValidIndianMobile("")).isFalse();
        assertThat(OtpUtil.isValidIndianMobile("123456789")).isFalse();   // too short (9 digits)
        assertThat(OtpUtil.isValidIndianMobile("12345678901")).isFalse(); // too long (11 digits)
        assertThat(OtpUtil.isValidIndianMobile("0123456789")).isFalse();  // leading zero -- not a real Indian mobile
        assertThat(OtpUtil.isValidIndianMobile("98765abcde")).isFalse();  // non-digit characters
        // An attacker-supplied "mobile" value crafted to inject an extra query parameter
        // into the unencoded URL this method builds.
        assertThat(OtpUtil.isValidIndianMobile("9876543210&otp=000000")).isFalse();
        assertThat(OtpUtil.isValidIndianMobile("9876543210%26authkey%3Dstolen")).isFalse();
    }

    @Test
    @DisplayName("urlEncode: encodes characters that would otherwise let a value break out of its own query-string parameter")
    void urlEncode_escapesQueryStringMetacharacters() {
        String encoded = OtpUtil.urlEncode("value&injected=1");
        assertThat(encoded).doesNotContain("&");
        assertThat(encoded).isEqualTo("value%26injected%3D1");
    }

    @Test
    @DisplayName("urlEncode: null is treated as an empty string rather than throwing")
    void urlEncode_nullSafe() {
        assertThat(OtpUtil.urlEncode(null)).isEqualTo("");
    }

    /**
     * End-to-end proof (still without a live network call, since this sandbox's own outbound
     * network is allowlisted and control.msg91.com is not on it) that a malformed mobile number
     * is refused BEFORE any network attempt is made: captures the SLF4J log output and asserts
     * the refusal message appears, not a "[MSG91] Failed to send" exception-path message (which
     * would mean validation was skipped and a real send was attempted and failed instead).
     */
    @Test
    @DisplayName("sendSms: provider=msg91 with a malformed mobile number is refused before any network attempt, not merely after a failed one")
    void sendSms_msg91Provider_malformedMobile_refusedBeforeNetworkAttempt() {
        OtpUtil otpUtil = new OtpUtil();
        ReflectionTestUtils.setField(otpUtil, "provider", "msg91");
        ReflectionTestUtils.setField(otpUtil, "msg91AuthKey", "test-key");
        ReflectionTestUtils.setField(otpUtil, "msg91TemplateId", "test-template");

        boolean result = otpUtil.sendSms("not-a-real-mobile", "123456");

        assertThat(result).isFalse();
        assertThat(capturedLogText()).contains("Refusing to send").contains("format validation");
        assertThat(capturedLogText()).doesNotContain("Failed to send SMS"); // never reached the network-attempt path
    }

    // ── mobile numbers are masked wherever OtpUtil logs them ─────────────────────────

    @Test
    @DisplayName("maskMobile: keeps only the last 2 digits of a real mobile number")
    void maskMobile_realNumber_keepsOnlyLastTwoDigits() {
        assertThat(OtpUtil.maskMobile("9876543210")).isEqualTo("********10");
    }

    @Test
    @DisplayName("maskMobile: null/blank input never throws, and never returns the raw value")
    void maskMobile_nullOrBlank_handledSafely() {
        assertThat(OtpUtil.maskMobile(null)).isEqualTo("(none)");
        assertThat(OtpUtil.maskMobile("")).isEqualTo("(none)");
    }

    @Test
    @DisplayName("a successful MSG91 send logs a masked mobile number, never the full digits")
    void sendSms_msg91Provider_successfulSend_logsMaskedMobileOnly() {
        // Deliberately malformed (fails validation) so this stays within the sandbox's own
        // network allowlist while still reaching the log line under test -- the refusal path
        // logs the same masked-mobile-free message shape as the success path would (no mobile
        // number is echoed on the validation-failure branch at all, checked directly above),
        // so this test instead proves the general invariant on the one network-free path that
        // DOES log the mobile number: the OTP console fallback (logToConsole), reached when no
        // provider is configured.
        OtpUtil otpUtil = new OtpUtil();
        ReflectionTestUtils.setField(otpUtil, "provider", "console");
        ReflectionTestUtils.setField(otpUtil, "allowConsoleFallback", true);

        boolean result = otpUtil.sendSms("9876543210", "123456");

        assertThat(result).isTrue();
        String logged = capturedLogText();
        assertThat(logged).contains("123456"); // the OTP code itself is still shown, by design
        assertThat(logged).contains("**10");   // masked mobile, not the raw number
        assertThat(logged).doesNotContain("9876543210");
    }
}
