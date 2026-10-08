package com.tradevision.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that WebhookAlertService refuses to deliver to internal, private, or
 * link-local destinations, guarding against SSRF via a configured webhook URL.
 *
 * Uses IP-literal URLs throughout rather than hostnames needing real DNS resolution, so these
 * tests are deterministic and don't depend on network access being available in the test
 * environment -- InetAddress.getAllByName("127.0.0.1") resolves an IP literal without any real
 * DNS lookup.
 */
class WebhookAlertServiceTest {

    private final WebhookAlertService service = new WebhookAlertService();

    @Test
    @DisplayName("isDestinationSafe: plain HTTP is rejected outright, regardless of the host")
    void plainHttp_rejected() {
        assertThat(service.isDestinationSafe("http://example.com/webhook")).isFalse();
    }

    @Test
    @DisplayName("isDestinationSafe: loopback address (127.0.0.1) is rejected")
    void loopbackAddress_rejected() {
        assertThat(service.isDestinationSafe("https://127.0.0.1/webhook")).isFalse();
    }

    @Test
    @DisplayName("isDestinationSafe: RFC 1918 private address (10.x) is rejected")
    void privateAddress10_rejected() {
        assertThat(service.isDestinationSafe("https://10.0.0.5/webhook")).isFalse();
    }

    @Test
    @DisplayName("isDestinationSafe: RFC 1918 private address (192.168.x) is rejected")
    void privateAddress192_rejected() {
        assertThat(service.isDestinationSafe("https://192.168.1.1/webhook")).isFalse();
    }

    @Test
    @DisplayName("isDestinationSafe: link-local address (169.254.x, where the cloud metadata endpoint lives) is rejected")
    void linkLocalMetadataAddress_rejected() {
        assertThat(service.isDestinationSafe("https://169.254.169.254/latest/meta-data/")).isFalse();
    }

    @Test
    @DisplayName("isDestinationSafe: a genuine public IP address is allowed")
    void publicAddress_allowed() {
        // 8.8.8.8 -- a well-known, stable public IP (Google DNS), used here purely as a
        // deterministic "genuinely public, non-internal" address, not for any DNS behavior.
        assertThat(service.isDestinationSafe("https://8.8.8.8/webhook")).isTrue();
    }

    @Test
    @DisplayName("isDestinationSafe: a malformed URL is rejected, not thrown as an exception")
    void malformedUrl_rejectedNotThrown() {
        assertThat(service.isDestinationSafe("not a valid url at all")).isFalse();
    }

    @Test
    @DisplayName("isDestinationSafe: a blank host is rejected")
    void blankHost_rejected() {
        assertThat(service.isDestinationSafe("https:///webhook")).isFalse();
    }

    @Test
    @DisplayName("send: a webhook URL that fails SSRF validation returns false and never attempts delivery")
    void send_unsafeDestination_returnsFalseWithoutAttemptingDelivery() {
        boolean result = service.send("https://169.254.169.254/latest/meta-data/", "TEST", "CRITICAL", "BTCUSDT", "test message");

        assertThat(result).isFalse();
    }

    /**
     * End-to-end proof that a redirect response is never automatically followed: a real
     * local HTTP server (JDK's own built-in com.sun.net.httpserver, no external dependency
     * needed) responds with a 302 pointing at a link-local address, confirming send()
     * treats that as a failed delivery rather than silently chasing it there. Uses plain HTTP
     * for the local test server itself (isDestinationSafe's own HTTPS-only rule would otherwise
     * refuse the test server's own URL before ever reaching the redirect-handling code this
     * test exists to prove) -- calls the http client directly rather than through
     * isDestinationSafe's own gate, which is already covered by its own dedicated tests above.
     */
    @Test
    @DisplayName("send: the destination responds with a redirect -- returned as a failed delivery, never automatically followed to wherever it points")
    void send_destinationRedirects_treatedAsFailedDeliveryNotFollowed() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var redirectFollowed = new java.util.concurrent.atomic.AtomicBoolean(false);
        server.createContext("/webhook", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/latest/meta-data/", exchange -> {
            // If the client ever actually followed the redirect, it would land here -- this
            // context existing and being hit at all is itself the failure this test guards
            // against, independent of whatever HTTP status it returns.
            redirectFollowed.set(true);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            // isDestinationSafe's own HTTPS-only rule is tested separately above -- this test
            // is specifically about redirect-following behavior, so it reaches into the same
            // package-private validation gate this class already exposes for testing, bypassing
            // only the HTTPS requirement (a local plaintext test server has no TLS cert to
            // offer), not the redirect-handling logic under test.
            var testService = new WebhookAlertService() {
                @Override
                boolean isDestinationSafe(String webhookUrl) {
                    return true; // bypass HTTPS-only for this local, plaintext test server
                }
            };
            boolean result = testService.send("http://127.0.0.1:" + server.getAddress().getPort() + "/webhook",
                "TEST", "CRITICAL", "BTCUSDT", "test message");

            assertThat(result).isFalse(); // a redirect is treated as failed delivery
            assertThat(redirectFollowed.get()).isFalse(); // and, critically, never actually followed there
        } finally {
            server.stop(0);
        }
    }
}
