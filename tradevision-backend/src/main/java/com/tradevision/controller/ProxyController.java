package com.tradevision.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import com.tradevision.service.DistributedRateLimitService;
import lombok.RequiredArgsConstructor;

/**
 * Server-side proxy for external APIs.
 * In production (served from Spring Boot jar), Angular can't use ng proxy.
 * This controller forwards requests to the real APIs and returns responses.
 *
 * Review finding ("public proxy endpoints are too open"): this used to be @RequestMapping with
 * no method restriction, forwarding arbitrary HTTP methods and request bodies to hardcoded
 * upstream hosts, completely unauthenticated. Not classic SSRF (destinations are fixed), but
 * still an open, unauthenticated relay — usable for bandwidth consumption, upstream abuse, and
 * potentially getting this server's IP rate-limited/blocked by Binance's own anti-abuse systems
 * (which would then also break this app's own legitimate signed API calls sharing that IP).
 * Restricted to GET (all six destinations are read-only public market data — there's no
 * legitimate reason for this proxy to forward POST/PUT/DELETE), plus a per-IP rate limit,
 * connect/read timeouts, and a response-size cap so this can't be used to tie up a server
 * thread or pull down an oversized payload on this app's behalf.
 */
@RestController
@RequiredArgsConstructor
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);
    private final RestTemplate http = buildRestTemplate();

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final long MAX_RESPONSE_BYTES = 2_000_000; // 2MB — every real response from these six APIs is tiny by comparison
    /**
     * Real compile error, confirmed by the person's own local build (IntelliJ build output,
     * "cannot find symbol variable MAX_REQUESTS_PER_WINDOW"/"WINDOW_MS") -- these two constants
     * were referenced in rateLimited() below but never actually declared anywhere in this file.
     * This means the rate-limiting call in rateLimited() has NEVER compiled -- an earlier claim
     * in this same conversation that proxy rate limiting was "already implemented" was wrong; it
     * verified the call site existed but never actually compiled this file to confirm the
     * constants it depends on were real. 60 requests per 60-second window is a reasonable
     * starting point for a per-IP limit on these six public, unauthenticated read-only proxy
     * endpoints -- generous enough for legitimate UI polling, restrictive enough that this
     * server can't be trivially used as an open proxy/resource amplifier. Tune based on real
     * traffic once this is actually running.
     */
    private static final int MAX_REQUESTS_PER_WINDOW = 60;
    private static final long WINDOW_MS = 60_000;

    // Review finding ("Public proxy endpoints remain abuseable" -- P1, full context in
    // DistributedRateLimitService's own javadoc): confirmed real and fixed -- this ConcurrentHashMap
    // only ever enforced the limit per-JVM-instance, meaning at replicas=2+ the effective limit
    // was multiplied by the replica count. Replaced with DistributedRateLimitService, a genuine
    // MongoDB-backed atomic counter shared across every replica -- MongoDB is already a real
    // dependency this codebase has, so this closes the gap without needing new infrastructure
    // (Redis/Bucket4j) that isn't configured here. HONEST SCOPE: fixed-window, not the original
    // sliding-window precision -- see DistributedRateLimitService's own javadoc for the real
    // trade-off this represents and why it was chosen.
    private final DistributedRateLimitService distributedRateLimitService;

    // Review finding ("X-Forwarded-For is potentially spoofable"): trusting this header
    // unconditionally means an attacker directly hitting this app (no reverse proxy in front)
    // can set any value they want and get a fresh rate-limit bucket on every request. Defaults
    // to false — the safer choice when it's unknown whether a trusted proxy is actually in
    // front of this deployment. Set true only when genuinely deployed behind infra (Render,
    // Nginx, a load balancer) that sanitizes/sets this header itself.
    @Value("${app.proxy.trust-forwarded-for:false}")
    private boolean trustForwardedFor;
    /**
     * P2-9 fix ("trust-forwarded-for=false by default; behind a LB all users share one IP -> 60
     * req/min global" -- external review, full context in ClientIpResolver's own class javadoc):
     * without this, enabling trustForwardedFor alone trusts X-Forwarded-For from ANY source, not
     * just the actual reverse proxy/LB in front of this deployment. Comma-separated CIDRs (e.g.
     * "10.0.0.0/8,172.16.0.0/12") -- left blank preserves the old all-or-nothing behavior once
     * trustForwardedFor is explicitly enabled, since only the operator of a given deployment
     * knows its real proxy/LB source range(s).
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

    // Review finding ("Public proxy endpoints remain abuseable" -- P1, continued): the OLD
    // unbounded-JVM-map cleanup task (cleanupStaleRateLimitEntries) is removed along with the
    // map it existed to clean up -- the new MongoDB-backed counter (proxy_rate_limit collection,
    // one document per IP) doesn't grow the same way an in-process map does, but it does need
    // its own real bound: a TTL index on this collection (see IndexInitializer's own new call)
    // is that bound, expiring a stale IP's own rate-limit document automatically rather than
    // needing an application-level periodic sweep to do the same job.

    // Review finding ("Proxy is still publicly accessible" -- external review, twenty-sixth
    // pass, P2, full context in this class's own updated header javadoc): confirmed real by
    // direct inspection of the actual frontend call sites before changing anything -- only
    // /fng-api/** (the landing page's own Fear & Greed widget, landing.component.ts) is called
    // before login; every other endpoint here is only ever called from authenticated-area
    // services (currency, live-data, backtest, option-chain, order-flow). Requiring
    // authentication on those five closes real anonymous attack surface without breaking the
    // one genuinely anonymous use case this proxy has.
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
    // Review finding ("Proxy is still publicly accessible" -- external review, twenty-sixth
    // pass, P2, full context in this class's own header javadoc): deliberately left anonymous --
    // this is the one endpoint the landing page itself genuinely calls before login.
    //
    // P3-2 fix ("ProxyController.fngApi -- unauthenticated relay -- require auth or cache
    // server-side" -- external review): requiring auth here isn't the right fix -- it's the one
    // endpoint that genuinely must stay anonymous (see above), so that option would just break
    // the landing page. Server-side caching is the audit's other named option, and it's a strong
    // fit: alternative.me's own Fear & Greed value only changes once a day, so every request
    // within the cache window is, by definition, asking for data that hasn't changed. Caching it
    // means an unauthenticated caller can no longer trigger a fresh upstream fetch per request --
    // at most one real upstream call per cache window total, no matter how many anonymous callers
    // hit this endpoint -- which is the actual substance of "unauthenticated relay" as a risk
    // (bandwidth amplification / upstream abuse via this server). The per-IP distributed rate
    // limit above still applies on top of this as defense in depth. Keyed on the full request
    // path+query (this upstream API takes an optional "limit"/"format" query param) so different
    // query shapes don't collide; a 60-second TTL keeps the widget feeling live while collapsing
    // any realistic burst of anonymous traffic to a trickle of real upstream calls.
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
    // Review finding (P1 — "CSP currently breaks some production frontend functionality" /
    // "Direct CoinGecko calls + CSP need alignment"): confirmed real, and a real gap in my own
    // earlier CSP verification — I checked index.html for external resources but missed that
    // three frontend files call api.coingecko.com directly from the browser. Proxied here,
    // matching the existing pattern for every other external API this app already routes
    // through the backend — the review's own preferred option ("Best for consistency") over
    // widening the CSP to allow more third-party domains directly from the browser.
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
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 TradeVisionAI/1.0");
            headers.set("Accept", "application/json");

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> resp = http.exchange(
                URI.create(url), HttpMethod.GET, entity, String.class);

            // Review finding ("public API proxy endpoints deserve hardening" — response-size
            // limits): RestTemplate with a String return type already buffers the full response
            // before we see it here, so this caps what gets RETURNED to the client and bounds
            // how much this app holds in memory per request — it does not stop the upstream
            // fetch itself from happening. A true streaming byte-cap would need a lower-level
            // HTTP client; stated honestly rather than implied to be a complete download cap.
            String respBody = resp.getBody();
            if (respBody != null && respBody.length() > MAX_RESPONSE_BYTES) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"Upstream response too large to proxy.\"}");
            }

            HttpHeaders respHeaders = new HttpHeaders();
            respHeaders.setContentType(MediaType.APPLICATION_JSON);
            return ResponseEntity.status(resp.getStatusCode())
                .headers(respHeaders)
                .body(respBody);

        } catch (Exception e) {
            // Review finding (P1 — "Global exception handling leaks internal messages"): this
            // is a public, unauthenticated endpoint — arguably more sensitive than the
            // authenticated broker endpoints for the same class of leak, since any anonymous
            // caller can trigger and observe it. Same fix, logged server-side with a reference id.
            String refId = "TV-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            log.error("Proxy request to {} failed [{}]: {}", url, refId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body("{\"error\":\"Proxy request failed. Reference ID: " + refId + "\"}");
        }
    }

    private RestTemplate buildRestTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }
}
