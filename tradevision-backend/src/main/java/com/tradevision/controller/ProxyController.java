package com.tradevision.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.ByteBuffer;
import com.tradevision.service.DistributedRateLimitService;
import lombok.RequiredArgsConstructor;

/**
 * Server-side proxy for external market-data APIs (Binance, Yahoo Finance, Frankfurter/ECB,
 * Fear & Greed, NSE India, CoinGecko). In production (served from the Spring Boot jar), Angular
 * can't use the dev-time ng proxy, so this controller forwards the request and returns the
 * upstream response directly.
 *
 * Restricted to GET only, since all six destinations are read-only public market data with no
 * legitimate reason to forward POST/PUT/DELETE. Guarded by a per-IP rate limit, connect/read
 * timeouts, and a response-size cap, so this can't be used to tie up a server thread or pull
 * down an oversized payload, or to get this server's IP rate-limited/blocked by an upstream
 * provider's anti-abuse systems (which would also break this app's own legitimate calls sharing
 * that IP).
 */
@RestController
@RequiredArgsConstructor
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);
    /**
     * Uses the JDK's java.net.http.HttpClient with a custom streaming BodySubscriber
     * (SizeCappedBodySubscriber, below) rather than RestTemplate, so the response-size cap is
     * enforced as bytes arrive: the download is aborted mid-flight the instant MAX_RESPONSE_BYTES
     * is exceeded, instead of only checking length after the entire body has already been
     * buffered into memory. HttpClient has been a JDK-bundled API since Java 11, so no new
     * dependency is needed.
     */
    private final java.net.http.HttpClient http = buildHttpClient();

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final long MAX_RESPONSE_BYTES = 2_000_000; // 2MB — every real response from these six APIs is tiny by comparison
    // 60 requests per 60-second window: a per-IP limit generous enough for legitimate UI
    // polling, restrictive enough that this server can't be trivially used as an open
    // proxy/resource amplifier. Tune based on real traffic.
    private static final int MAX_REQUESTS_PER_WINDOW = 60;
    private static final long WINDOW_MS = 60_000;

    // Backed by MongoDB so the rate limit is shared across every replica, rather than each
    // instance enforcing its own independent count (which would multiply the effective limit by
    // the replica count). Uses a fixed-window counter, trading some precision versus a true
    // sliding window for simplicity on infrastructure this codebase already has.
    private final DistributedRateLimitService distributedRateLimitService;

    // Trusting X-Forwarded-For unconditionally would let an attacker hitting this app directly
    // (no reverse proxy in front) set any value and get a fresh rate-limit bucket per request.
    // Defaults to false; set true only when genuinely deployed behind infra (a load balancer,
    // Nginx, etc.) that sets this header itself.
    @Value("${app.proxy.trust-forwarded-for:false}")
    private boolean trustForwardedFor;
    /**
     * Restricts which source IPs X-Forwarded-For is trusted from once trustForwardedFor is
     * enabled, so that flag alone doesn't trust the header from any source. Comma-separated
     * CIDRs (e.g. "10.0.0.0/8,172.16.0.0/12"); left blank, any source is trusted once the flag
     * is on, since only the deployment's operator knows its real proxy/LB source range(s).
     */
    @Value("${app.proxy.trusted-proxy-cidrs:}")
    private String trustedProxyCidrs;

    private boolean rateLimited(HttpServletRequest req) {
        String ip = com.tradevision.util.ClientIpResolver.resolve(req, trustForwardedFor, trustedProxyCidrs);
        return !distributedRateLimitService.allow("proxy_rate_limit", ip, MAX_REQUESTS_PER_WINDOW, WINDOW_MS / 1000);
    }

    private ResponseEntity<String> rateLimitResponse() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body("{\"error\":\"Too many proxy requests — try again shortly.\"}");
    }

    // The MongoDB-backed counter (proxy_rate_limit collection, one document per IP) is bounded
    // by a TTL index on that collection, which expires a stale IP's rate-limit document
    // automatically rather than needing an application-level periodic sweep.

    // Only /fng-api/** (the landing page's Fear & Greed widget) is called before login; every
    // other endpoint here is only called from authenticated-area frontend services (currency,
    // live-data, backtest, option-chain, order-flow), so those five require authentication.
    private ResponseEntity<String> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"error\":\"Authentication required.\"}");
    }

    // ── Binance Spot ──────────────────────────────────────────
    @GetMapping("/binance-spot/**")
    public ResponseEntity<String> binanceSpot(@org.springframework.security.core.annotation.AuthenticationPrincipal String userId, HttpServletRequest req) {
        if (userId == null) return unauthorized();
        if (rateLimited(req)) return rateLimitResponse();
        String path = req.getRequestURI().replace("/binance-spot", "");
        String qs   = req.getQueryString() != null ? "?" + req.getQueryString() : "";
        return forward("https://api.binance.com" + path + qs, req, null);
    }

    // ── Binance Futures ───────────────────────────────────────
    @GetMapping("/binance-futures/**")
    public ResponseEntity<String> binanceFutures(@org.springframework.security.core.annotation.AuthenticationPrincipal String userId, HttpServletRequest req) {
        if (userId == null) return unauthorized();
        if (rateLimited(req)) return rateLimitResponse();
        String path = req.getRequestURI().replace("/binance-futures", "");
        String qs   = req.getQueryString() != null ? "?" + req.getQueryString() : "";
        return forward("https://fapi.binance.com" + path + qs, req, null);
    }

    // ── Yahoo Finance ─────────────────────────────────────────
    @GetMapping("/yf-api/**")
    public ResponseEntity<String> yahooFinance(@org.springframework.security.core.annotation.AuthenticationPrincipal String userId, HttpServletRequest req) {
        if (userId == null) return unauthorized();
        if (rateLimited(req)) return rateLimitResponse();
        String path = req.getRequestURI().replace("/yf-api", "");
        String qs   = req.getQueryString() != null ? "?" + req.getQueryString() : "";
        return forward("https://query1.finance.yahoo.com" + path + qs, req, null);
    }

    // ── Forex / ECB ───────────────────────────────────────────
    @GetMapping("/forex-api/**")
    public ResponseEntity<String> forexApi(@org.springframework.security.core.annotation.AuthenticationPrincipal String userId, HttpServletRequest req) {
        if (userId == null) return unauthorized();
        if (rateLimited(req)) return rateLimitResponse();
        String path = req.getRequestURI().replace("/forex-api", "/v1");
        String qs   = req.getQueryString() != null ? "?" + req.getQueryString() : "";
        return forward("https://api.frankfurter.app" + path + qs, req, null);
    }

    // ── Fear & Greed Index ────────────────────────────────────
    // Deliberately left anonymous, since this is the one endpoint the landing page calls before
    // login. To avoid an anonymous caller triggering a fresh upstream fetch on every request,
    // responses are cached server-side: alternative.me's Fear & Greed value only changes once a
    // day, so at most one real upstream call happens per cache window, no matter how many
    // anonymous callers hit this endpoint. The per-IP rate limit above still applies on top as
    // defense in depth. Cache key is the full request path+query (the upstream API takes an
    // optional "limit"/"format" param) so different query shapes don't collide; a 60-second TTL
    // keeps the widget feeling live while collapsing bursts of anonymous traffic.
    private static final long FNG_CACHE_TTL_MS = 60_000;
    private final java.util.concurrent.ConcurrentHashMap<String, FngCacheEntry> fngCache = new java.util.concurrent.ConcurrentHashMap<>();

    private record FngCacheEntry(ResponseEntity<String> response, long cachedAtMs) {
        boolean isFresh(long nowMs) {
            return nowMs - cachedAtMs < FNG_CACHE_TTL_MS;
        }
    }

    @GetMapping("/fng-api/**")
    public ResponseEntity<String> fngApi(HttpServletRequest req) {
        if (rateLimited(req)) return rateLimitResponse();
        String path = req.getRequestURI().replace("/fng-api", "");
        String qs   = req.getQueryString() != null ? "?" + req.getQueryString() : "";
        String url = "https://api.alternative.me" + path + qs;

        long now = System.currentTimeMillis();
        FngCacheEntry cached = fngCache.get(url);
        if (cached != null && cached.isFresh(now)) {
            return cached.response();
        }

        ResponseEntity<String> resp = forward(url, req, null);
        // Only cache genuine success -- never cache a rate-limit/upstream-failure response, or a
        // transient outage would get "stuck" and served to every anonymous caller for the TTL.
        if (resp.getStatusCode().is2xxSuccessful()) {
            fngCache.put(url, new FngCacheEntry(resp, now));
        }
        return resp;
    }

    // ── NSE India ─────────────────────────────────────────────
    @GetMapping("/nse-api/**")
    public ResponseEntity<String> nseApi(@org.springframework.security.core.annotation.AuthenticationPrincipal String userId, HttpServletRequest req) {
        if (userId == null) return unauthorized();
        if (rateLimited(req)) return rateLimitResponse();
        String path = req.getRequestURI().replace("/nse-api", "");
        String qs   = req.getQueryString() != null ? "?" + req.getQueryString() : "";
        return forward("https://www.nseindia.com" + path + qs, req, null);
    }

    // ── CoinGecko ─────────────────────────────────────────────
    // Proxied through the backend, matching the pattern for every other external API this app
    // routes through here, rather than calling api.coingecko.com directly from the browser and
    // having to widen the CSP to allow a third-party domain directly.
    @GetMapping("/coingecko-api/**")
    public ResponseEntity<String> coinGeckoApi(@org.springframework.security.core.annotation.AuthenticationPrincipal String userId, HttpServletRequest req) {
        if (userId == null) return unauthorized();
        if (rateLimited(req)) return rateLimitResponse();
        String path = req.getRequestURI().replace("/coingecko-api", "");
        String qs   = req.getQueryString() != null ? "?" + req.getQueryString() : "";
        return forward("https://api.coingecko.com" + path + qs, req, null);
    }

    // ── Helper ────────────────────────────────────────────────
    private ResponseEntity<String> forward(String url, HttpServletRequest req, String body) {
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(java.time.Duration.ofMillis(READ_TIMEOUT_MS))
                .header("User-Agent", "Mozilla/5.0 TradeVisionAI/1.0")
                .header("Accept", "application/json")
                .build();

            // The cap is enforced inside the streaming subscriber below -- by the time send()
            // returns (successfully or not), either the whole body arrived under
            // MAX_RESPONSE_BYTES, or the download was already aborted mid-stream.
            java.net.http.HttpResponse<String> resp = http.send(request, new SizeCappedBodyHandler(MAX_RESPONSE_BYTES));

            HttpHeaders respHeaders = new HttpHeaders();
            respHeaders.setContentType(MediaType.APPLICATION_JSON);
            return ResponseEntity.status(resp.statusCode())
                .headers(respHeaders)
                .body(resp.body());

        } catch (Exception e) {
            if (isResponseTooLarge(e)) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"Upstream response too large to proxy.\"}");
            }
            // This proxy is reachable by anonymous callers for some routes, so the raw upstream
            // error message is never returned to the client -- only an opaque reference id,
            // with the real detail logged server-side.
            String refId = "TV-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            log.error("Proxy request to {} failed [{}]: {}", url, refId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body("{\"error\":\"Proxy request failed. Reference ID: " + refId + "\"}");
        }
    }

    /** Walks the full cause chain (java.net.http.HttpClient wraps a BodySubscriber failure in
     *  an IOException) looking for the specific marker thrown by SizeCappedBodySubscriber below. */
    private boolean isResponseTooLarge(Throwable e) {
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof ResponseTooLargeException) return true;
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private static final class ResponseTooLargeException extends RuntimeException {
        ResponseTooLargeException() {
            super("Upstream response exceeded the proxy's byte cap", null, false, false); // no stack trace needed -- this is a control-flow signal, not a real error
        }
    }

    /** Streaming response-size cap: a java.net.http.HttpResponse.BodyHandler that hands out a
     *  SizeCappedBodySubscriber per request, so the byte limit is enforced as data arrives, not
     *  after the fact. */
    private static final class SizeCappedBodyHandler implements java.net.http.HttpResponse.BodyHandler<String> {
        private final long maxBytes;

        SizeCappedBodyHandler(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public java.net.http.HttpResponse.BodySubscriber<String> apply(java.net.http.HttpResponse.ResponseInfo responseInfo) {
            return new SizeCappedBodySubscriber(maxBytes);
        }
    }

    /** The actual streaming cap. Counts bytes as each chunk is delivered by the HTTP client and,
     *  the moment the running total exceeds maxBytes, cancels the upstream subscription (aborting
     *  the in-flight download immediately) and fails the body future with ResponseTooLargeException
     *  -- never buffers a single byte past the cap, unlike the prior RestTemplate-based approach
     *  this replaces, which had already fully downloaded and buffered the whole oversized body
     *  before its own after-the-fact length check ever ran. */
    private static final class SizeCappedBodySubscriber implements java.net.http.HttpResponse.BodySubscriber<String> {
        private final long maxBytes;
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;
        private long receivedBytes = 0;

        SizeCappedBodySubscriber(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(java.util.List<ByteBuffer> buffers) {
            for (ByteBuffer bb : buffers) {
                int chunkLen = bb.remaining();
                receivedBytes += chunkLen;
                if (receivedBytes > maxBytes) {
                    // Abort mid-stream: never finish buffering (or even keep receiving) a
                    // response that has already exceeded the cap.
                    subscription.cancel();
                    result.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                byte[] chunk = new byte[chunkLen];
                bb.get(chunk);
                buffer.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toString(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public java.util.concurrent.CompletionStage<String> getBody() {
            return result;
        }
    }

    private java.net.http.HttpClient buildHttpClient() {
        return java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();
    }
}
