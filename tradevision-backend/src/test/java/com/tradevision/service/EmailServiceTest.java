package com.tradevision.service;

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
 * P2-17 fix ("20x System.out.println, PII in logs, no correlation IDs" -- external review, full
 * context in EmailService's own updated sendOtp comment): this class's own sendOtp() used to log
 * the full, unmasked recipient email address unconditionally, on every call, in every
 * environment. This file did not exist before this fix.
 */
class EmailServiceTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger emailServiceLogger;

    @BeforeEach
    void attachLogAppender() {
        emailServiceLogger = (Logger) org.slf4j.LoggerFactory.getLogger(EmailService.class);
        emailServiceLogger.setLevel(ch.qos.logback.classic.Level.DEBUG); // sendOtp's own header line is DEBUG now
        logAppender = new ListAppender<>();
        logAppender.start();
        emailServiceLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        emailServiceLogger.detachAppender(logAppender);
        emailServiceLogger.setLevel(null);
    }

    private String capturedLogText() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : logAppender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("maskEmail: keeps only the first character of the local part and the whole domain")
    void maskEmail_realAddress_masksLocalPartExceptFirstChar() {
        assertThat(EmailService.maskEmail("john.doe@example.com")).isEqualTo("j***@example.com");
    }

    @Test
    @DisplayName("maskEmail: null/blank/malformed input never throws and never returns the raw value")
    void maskEmail_edgeCases_handledSafely() {
        assertThat(EmailService.maskEmail(null)).isEqualTo("(none)");
        assertThat(EmailService.maskEmail("")).isEqualTo("(none)");
        assertThat(EmailService.maskEmail("not-an-email")).isEqualTo("***");
    }

    @Test
    @DisplayName("sendOtp: mail disabled and console fallback allowed -- logs the OTP (by design, for local dev) but only a masked email address, never the full address")
    void sendOtp_consoleFallback_logsMaskedEmailOnly() {
        EmailService service = new EmailService();
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "apiKey", "");
        ReflectionTestUtils.setField(service, "allowConsoleFallback", true);

        boolean result = service.sendOtp("jane.doe@example.com", "654321");

        assertThat(result).isTrue();
        String logged = capturedLogText();
        assertThat(logged).contains("654321");            // the OTP code itself is still shown, by design
        assertThat(logged).contains("j***@example.com");  // masked address
        assertThat(logged).doesNotContain("jane.doe@example.com");
    }

    @Test
    @DisplayName("sendOtp: mail disabled and console fallback NOT allowed -- refuses, and never logs the email or OTP at all")
    void sendOtp_noFallbackAllowed_neverLogsEmailOrOtp() {
        EmailService service = new EmailService();
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "apiKey", "");
        ReflectionTestUtils.setField(service, "allowConsoleFallback", false);

        boolean result = service.sendOtp("jane.doe@example.com", "654321");

        assertThat(result).isFalse();
        String logged = capturedLogText();
        assertThat(logged).doesNotContain("jane.doe@example.com");
        assertThat(logged).doesNotContain("654321");
    }
}
