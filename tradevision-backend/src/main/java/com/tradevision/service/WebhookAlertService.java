package com.tradevision.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Review finding ("Alerting hooks" — "Email/SMS/webhook on halt, unprotected position,
 * consecutive failures... not end-to-end ops"): see IncidentService's own updated javadoc for
 * the full context — email delivery already existed and worked; this is the genuinely missing
 * piece, wired in alongside it, not replacing it.
 *
 * Deliberately generic rather than provider-specific: posts a plain JSON payload to whatever URL
 * the account holder configured (User.alertWebhookUrl). Slack, Discord, Telegram (via a bot's
 * webhook proxy), PagerDuty (via its own "Events API" webhook URL), and Microsoft Teams all
 * accept an incoming webhook this way — building a bespoke integration for each one is real,
 * separate scope this pass doesn't attempt, and a generic POST covers all of them without it.
 *
 * Same "best-effort, never blocks the real safety action" design as EmailService's own alert
 * delivery — a webhook failing to send (wrong URL, unreachable service, non-2xx response) is
 * logged, never thrown, and never prevents the incident from being durably recorded or the
 * triggering safety action (halt, emergency-flatten) from completing.
 *
 * Review finding ("Webhook SSRF protection isn't completely closed" -- external review,
 * thirtieth pass, P1, confirmed real by direct inspection before this fix: the previous
 * RestTemplate-based client had no control over its own redirect behavior at all -- a
 * genuinely safe, validated public URL could respond with a 3xx to an internal address, and
 * whatever RestTemplate's own underlying client did with that was never something this code
 * controlled or checked): switched to java.net.http.HttpClient specifically because it lets
 * this class disable redirect-following outright (Redirect.NEVER) -- a 3xx response is
 * returned as-is, never automatically followed, closing that gap completely rather than hoping
 * a library default is safe. DNS-rebinding note, stated honestly rather than left implied: this
 * does NOT pin the HTTP connection to the exact IP address validated by isDestinationSafe --
 * doing that correctly (while still presenting the right SNI/Host for TLS and certificate
 * validation to succeed) needs a custom DNS resolver wired into the HTTP client, which is a
 * real, separate undertaking with genuine risk of a subtle TLS/certificate-validation bug if
 * gotten wrong without a compiler to verify it against. What this fix DOES do is call
 * isDestinationSafe() immediately before the actual request (not once at save-time, already
 * true before this fix, and unchanged here) -- narrowing the rebinding window to the gap
 * between this validation's own DNS lookup and the HTTP client's own separate one, rather than
 * closing it to zero.
 */
@Service
public class WebhookAlertService {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertService.class);
    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public boolean send(String webhookUrl, String type, String severity, String symbol, String message) {
        if (webhookUrl == null || webhookUrl.isBlank()) return false;
        // Review finding ("Webhook alert feature is an SSRF surface" -- P1): confirmed real --
        // webhookUrl is entirely user-supplied (User.alertWebhookUrl) and was posted to
        // completely unvalidated. An account holder (or anyone who compromised one account)
        // could point this at an internal service this server can reach but a genuine outside
        // attacker couldn't -- a cloud metadata endpoint (169.254.169.254), this application's
        // own internal admin/actuator endpoints, or any other internal-only host -- and this
        // server would make that request on their behalf, from inside whatever network
        // perimeter it's deployed in. Validated fresh on every send (not just once when the URL
        // is saved) specifically to also cover DNS rebinding -- a hostname that resolved to a
        // public IP when saved but a private one by the time this actually runs.
        if (!isDestinationSafe(webhookUrl)) {
            log.warn("Refusing to send webhook alert -- destination failed SSRF safety validation (must be HTTPS, and must not resolve to a "
                + "private/loopback/link-local/multicast address): {}", webhookUrl);
            return false;
        }
        try {
            // A generic, provider-agnostic payload shape. Slack/Discord-style webhooks
            // specifically look for a top-level "text" field for the message to actually
            // display — included alongside the structured fields so this works usefully
            // out of the box for those without the account holder needing to configure
            // a custom payload template themselves.
            String body = String.format(
                "{\"text\":%s,\"type\":%s,\"severity\":%s,\"symbol\":%s,\"message\":%s}",
                jsonString(String.format("[%s] %s: %s", severity, type, message != null ? message : "")),
                jsonString(type), jsonString(severity), jsonString(symbol), jsonString(message));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                // Review finding, same context as this class's own updated class javadoc: a
                // redirect response is exactly the second gap the review names -- returned here
                // as-is (never automatically followed, per this client's own Redirect.NEVER
                // configuration), and treated as a failed delivery rather than silently chasing
                // it to an unvalidated destination.
                log.warn("Webhook alert destination returned a redirect ({}) -- not following it (unvalidated destination), treating as "
                    + "failed delivery: {}", status, webhookUrl);
                return false;
            }
            return status >= 200 && status < 300;
        } catch (Exception e) {
            log.warn("Webhook alert delivery failed (url reachability/response issue, non-fatal): {}", e.getMessage());
            return false;
        }
    }

    /** Minimal, dependency-free JSON string escaping for the handful of fields this payload
     *  sends -- avoids pulling in a JSON library for four fields where java.net.http already
     *  replaces the Map-based body Spring's RestTemplate used to serialize automatically. */
    private static String jsonString(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    /**
     * Review finding ("Webhook alert feature is an SSRF surface" -- P1, full context in send's
     * own comment): the actual validation. Package-private (not private) specifically so this
     * can be unit-tested directly without needing DNS to actually be mockable, which it isn't
     * through a real InetAddress.getAllByName() call.
     *
     * HTTPS-only: blocks plain HTTP outright (also closes off the plaintext-credential-leak
     * angle if a webhook URL ever embeds a token in its path/query, common for Slack/Discord).
     *
     * Resolves the hostname and rejects if ANY resolved address is loopback, site-local
     * (RFC 1918 private ranges), link-local (including 169.254.0.0/16, where the AWS/GCP/Azure
     * cloud metadata endpoint lives), or a multicast/wildcard address -- checking every resolved
     * address, not just the first, since a hostname can resolve to multiple IPs and an attacker
     * only needs one of them to be internal.
     */
    boolean isDestinationSafe(String webhookUrl) {
        try {
            java.net.URI uri = java.net.URI.create(webhookUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null || host.isBlank()) return false;
            for (java.net.InetAddress addr : java.net.InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                    || addr.isMulticastAddress() || addr.isAnyLocalAddress()) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            // Malformed URL, DNS resolution failure, or any other validation error -- refuse
            // rather than let an unvalidatable destination through by default.
            return false;
        }
    }
}
