package com.tradevision.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private final RestTemplate http = new RestTemplate();

    @Value("${brevo.api.key:}")                       private String  apiKey;
    // Review finding (found while working on P1 #19/#20): the fallback default here used to be
    // a real personal email address — dead in practice since both application-local.properties
    // and application-prod.properties always provide their own value, but a real address has no
    // business being a source-code fallback regardless of whether it's currently reachable. A
    // generic placeholder now — if this default is ever actually used, it should fail obviously
    // (bounces, or a provider rejection) rather than silently sending from someone's real inbox.
    @Value("${brevo.from.email:noreply@example.invalid}")  private String  fromEmail;
    @Value("${mail.from.name:TradeVision AI}")        private String  fromName;
    @Value("${mail.enabled:false}")                   private boolean enabled;
    // Same fix, same reasoning as OtpUtil: mail.enabled=false is the default, so printing the
    // OTP whenever mail is disabled means an unconfigured deployment silently logs real OTPs.
    // Requires an explicit, separate opt-in for local dev.
    @Value("${app.otp.allow-console-fallback:false}") private boolean allowConsoleFallback;

    /**
     * Review finding (P1 — "OTP delivery has a functional production bug"): confirmed real, and
     * made more consequential by the earlier fix that stopped the silent console fallback — a
     * misconfigured deployment (mail.enabled=false, no fallback allowed) used to at least print
     * the OTP somewhere; now it does nothing at all. Either way, the caller (AuthService)
     * previously had no way to know delivery didn't happen, since this returned void and always
     * got treated as success. Returns true only when delivery was actually attempted (a real API
     * call went out, or the explicit dev console fallback fired) — false when nothing happened
     * or the attempt itself failed, so the caller can tell the user honestly instead of claiming
     * "OTP sent" for an OTP that never went anywhere.
     */
    public boolean sendOtp(String toEmail, String otp) {
        // P2-17 fix ("20x System.out.println, PII in logs, no correlation IDs" -- external
        // review, full context in this codebase's own new CorrelationIdFilter javadoc): this
        // debug-style block used to log the full, unmasked email address on every single call,
        // unconditionally, in every environment including production -- not gated by
        // allowConsoleFallback (that flag only controls whether the value is later printed a
        // second time in the no-provider-configured branch below). maskEmail() below is used
        // everywhere this class logs an address from here on, including inside the dev-only
        // console fallback -- the fallback's own point is showing the OTP code, not confirming
        // the full address, and a masked address is still enough to tell which account it was
        // for. Reduced to DEBUG (this fires on every OTP request; INFO is too noisy for
        // production log volume) now that log.info below already records the outcome.
        log.debug("EmailService.sendOtp() called -- mail.enabled={}, apiKey blank={}, toEmail={}",
            enabled, apiKey.isBlank(), maskEmail(toEmail));

        if (!enabled || apiKey.isBlank()) {
            log.warn("SKIPPING email send -- mail.enabled=false or BREVO_API_KEY not set");
            if (!allowConsoleFallback) {
                log.info("[EmailService] No mail provider configured and app.otp.allow-console-fallback "
                    + "is not enabled -- refusing to log the OTP. For local development, set "
                    + "app.otp.allow-console-fallback=true explicitly.");
                return false;
            }
            log.info("\n==========================================\n  OTP for {}: {}\n==========================================",
                maskEmail(toEmail), otp);
            return true;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("api-key", apiKey);

            Map<String, Object> body = Map.of(
                    "sender",      Map.of("name", fromName, "email", fromEmail),
                    "to",          List.of(Map.of("email", toEmail)),
                    "subject",     "Your TradeVision AI OTP: " + otp,
                    "htmlContent", buildEmailHtml(otp)
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = http.postForEntity(
                    "https://api.brevo.com/v3/smtp/email", request, String.class
            );

            log.info("Email sent -- status: {}", response.getStatusCode());
            return response.getStatusCode().is2xxSuccessful();

        } catch (Exception e) {
            log.error("Brevo API failed: {}", e.getMessage());
            // Review finding (this doc, "#13" — OTP fallback still exposes OTP in some
            // production configurations): this used to print the raw OTP here. This branch
            // means mail.enabled=true and a real API key were configured — i.e. production —
            // and the actual send failed. That's exactly the case the earlier OtpUtil fix
            // covered for SMS; this closes the same gap for email.
            log.error("OTP delivery to {} failed and could not fall back -- user must request a new OTP.", maskEmail(toEmail));
            return false;
        }
    }

    /**
     * Review finding (P1 #19 — "Emergency alerts are still missing"): confirmed real — a
     * CRITICAL incident (protection failed, emergency flatten failed) previously only produced
     * an audit log line and a halted profile. Nobody was actually told. Reuses this same
     * service's existing Brevo wiring (not a separate integration) — best-effort: if delivery
     * fails here, the incident itself is still durably recorded by IncidentService regardless,
     * so a failed alert email never means the incident goes unrecorded, only unnotified.
     */
    public boolean sendAlert(String toEmail, String subject, String plainTextBody) {
        if (!enabled || apiKey.isBlank() || toEmail == null || toEmail.isBlank()) {
            log.warn("Cannot send alert email (mail disabled, no API key, or no recipient) — subject: {}", subject);
            return false;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("api-key", apiKey);
            String html = "<div style=\"font-family:sans-serif;white-space:pre-wrap;\">"
                // Review finding (P1 — "sendAlert() HTML doesn't escape the message"): confirmed
                // real — incident text can originate from broker error messages, symbol data, or
                // raw exception messages, none of which are safe to embed into HTML unescaped.
                // Escape BEFORE the newline->br conversion, not after — escaping afterward would
                // also mangle the <br> tags this method itself inserts. Spring's own HtmlUtils,
                // already on the classpath via spring-boot-starter-web — no new dependency.
                + org.springframework.web.util.HtmlUtils.htmlEscape(plainTextBody).replace("\n", "<br>")
                + "</div>";
            Map<String, Object> body = Map.of(
                "sender",      Map.of("name", fromName, "email", fromEmail),
                "to",          List.of(Map.of("email", toEmail)),
                "subject",     subject,
                "htmlContent", html
            );
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = http.postForEntity("https://api.brevo.com/v3/smtp/email", request, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.error("Alert email delivery failed (subject: {}): {}", subject, e.getMessage());
            return false;
        }
    }

    /**
     * P2-17 fix ("PII in logs" -- external review, full context in sendOtp's own updated
     * comment above): masks everything but the first character of the local part and the whole
     * domain, e.g. "j***@example.com" for "john@example.com" -- enough to spot-check which
     * account a log line is about without writing a usable email address into application logs.
     * Package-private (not private) so this masking is directly unit-testable -- see
     * EmailServiceTest.
     */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "(none)";
        int at = email.indexOf('@');
        if (at <= 0) return "***"; // no '@', or '@' is the first character -- nothing safe to keep
        String local = email.substring(0, at);
        String domain = email.substring(at);
        return local.charAt(0) + "***" + domain;
    }

    private String buildEmailHtml(String otp) {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0;padding:0;background:#080C18;font-family:'Segoe UI',sans-serif;">
              <table width="100%%" cellpadding="0" cellspacing="0" style="background:#080C18;padding:40px 0;">
                <tr><td align="center">
                  <table width="480" cellpadding="0" cellspacing="0"
                         style="background:#0F1525;border:1px solid #1E2D4A;border-radius:16px;overflow:hidden;">
                    <tr>
                      <td style="background:linear-gradient(135deg,#00D4FF,#7B61FF);padding:24px 32px;text-align:center;">
                        <h1 style="margin:0;color:#fff;font-size:22px;font-weight:800;">⚡ TradeVision AI</h1>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:32px;">
                        <p style="margin:0 0 16px;color:#8895B3;font-size:15px;">Your one-time login code:</p>
                        <div style="background:#080C18;border:1px solid #2A3F6A;border-radius:12px;padding:24px;text-align:center;margin:0 0 24px;">
                          <span style="font-family:'Courier New',monospace;font-size:40px;font-weight:900;letter-spacing:12px;color:#00D4FF;">%s</span>
                        </div>
                        <p style="margin:0 0 8px;color:#4A5568;font-size:13px;">⏱ Expires in <strong style="color:#FFB800;">5 minutes</strong>.</p>
                        <p style="margin:0;color:#4A5568;font-size:12px;">If you didn't request this, ignore this email.</p>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:16px 32px;border-top:1px solid #1A2340;text-align:center;">
                        <p style="margin:0;color:#2A3F6A;font-size:11px;">TradeVision AI · Educational only · Not financial advice</p>
                      </td>
                    </tr>
                  </table>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(otp);
    }
}