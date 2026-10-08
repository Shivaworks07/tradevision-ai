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
    // A generic, non-deliverable placeholder: both application-local.properties and
    // application-prod.properties always supply a real value, so this default is never meant to
    // be used in practice. If it ever is used, sending should fail obviously (bounce or provider
    // rejection) rather than silently delivering to a real inbox.
    @Value("${brevo.from.email:noreply@example.invalid}")  private String  fromEmail;
    @Value("${mail.from.name:TradeVision AI}")        private String  fromName;
    @Value("${mail.enabled:false}")                   private boolean enabled;
    // mail.enabled=false is the default, so printing the OTP to the console whenever mail is
    // disabled would mean an unconfigured deployment silently logs real OTPs. This requires an
    // explicit, separate opt-in for local development.
    @Value("${app.otp.allow-console-fallback:false}") private boolean allowConsoleFallback;

    /**
     * Sends the given OTP to {@code toEmail} and reports whether delivery was actually attempted
     * -- a real API call went out, or the explicit dev console fallback fired -- so the caller
     * ({@code AuthService}) can tell the user honestly instead of claiming "OTP sent" when
     * nothing was delivered (mail disabled with no fallback allowed, or the send itself failed).
     */
    public boolean sendOtp(String toEmail, String otp) {
        // Logged at DEBUG, not INFO: this fires on every OTP request, and the outcome is already
        // recorded via log.info below. The email address is masked (see maskEmail) everywhere
        // this class logs one, including in the dev-only console fallback, since a masked
        // address is still enough to tell which account a log line is about.
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
            // Reaching here means mail was enabled with a real API key configured (production)
            // and the send itself failed. The raw OTP is never logged as a fallback; the user
            // must request a new one instead.
            log.error("OTP delivery to {} failed and could not fall back -- user must request a new OTP.", maskEmail(toEmail));
            return false;
        }
    }

    /**
     * Sends a plain-text alert email (e.g. for a critical incident such as a failed protection
     * check or a failed emergency flatten) using this service's existing Brevo wiring. This is
     * best-effort notification: delivery failure here never affects whether the underlying
     * incident is recorded, since that's handled durably by {@code IncidentService} regardless --
     * a failed alert email only means the incident goes unnotified, not unrecorded.
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
                // Incident text can originate from broker error messages, symbol data, or raw
                // exception messages, none of which are safe to embed into HTML unescaped.
                // Escaping must happen before the newline->br conversion, not after, or it would
                // mangle the <br> tags inserted below. Uses Spring's HtmlUtils, already on the
                // classpath via spring-boot-starter-web.
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
     * Masks everything but the first character of the local part and the whole domain, e.g.
     * "j***@example.com" for "john@example.com" -- enough to spot-check which account a log line
     * is about without writing a usable email address into application logs. Package-private (not
     * private) so this masking is directly unit-testable -- see EmailServiceTest.
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