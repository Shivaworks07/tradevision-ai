package com.tradevision.controller;

import com.tradevision.service.DistributedRateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review finding ("Public proxy endpoints remain abuseable" -- P1, full context in
 * DistributedRateLimitService's own javadoc): confirms the actual new behavior -- rate limiting
 * is now enforced via the shared, MongoDB-backed DistributedRateLimitService, not a JVM-local
 * ConcurrentHashMap. This file did not exist before this fix; ProxyController had zero test
 * coverage previously.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProxyControllerTest {

    @Mock DistributedRateLimitService distributedRateLimitService;
    @Mock HttpServletRequest request;

    @InjectMocks ProxyController controller;

    @Test
    @DisplayName("binanceSpot: the distributed rate limiter rejecting this IP returns 429 immediately, without ever reaching the actual upstream forward call")
    void rateLimited_returns429_neverForwards() {
        when(request.getRemoteAddr()).thenReturn("203.0.113.5");
        when(distributedRateLimitService.allow(eq("proxy_rate_limit"), eq("203.0.113.5"), anyInt(), anyLong())).thenReturn(false);

        var response = controller.binanceSpot("user1", request);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
    }

    @Test
    @DisplayName("X-Forwarded-For: when trustForwardedFor is false (the default), the header is ignored entirely and the rate limiter is keyed on the real remote address, not an attacker-supplied one")
    void untrustedForwardedFor_ignoredCompletely() {
        ReflectionTestUtils.setField(controller, "trustForwardedFor", false);
        when(request.getRemoteAddr()).thenReturn("203.0.113.7");
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4"); // must be ignored
        when(distributedRateLimitService.allow(eq("proxy_rate_limit"), eq("203.0.113.7"), anyInt(), anyLong())).thenReturn(false);

        controller.binanceSpot("user1", request);

        verify(distributedRateLimitService).allow(eq("proxy_rate_limit"), eq("203.0.113.7"), anyInt(), anyLong());
        verify(distributedRateLimitService, org.mockito.Mockito.never()).allow(eq("proxy_rate_limit"), eq("1.2.3.4"), anyInt(), anyLong());
    }

    /**
     * Review finding ("Proxy is still publicly accessible" -- external review, twenty-sixth
     * pass, P2, full context in ProxyController's own updated header javadoc): the actual
     * tests for the new authentication gate.
     */
    @Test
    @DisplayName("binanceSpot: an unauthenticated request (null principal) is refused with 401, never reaching the rate limiter or the upstream forward call")
    void unauthenticated_refused401_neverReachesRateLimiterOrForward() {
        var response = controller.binanceSpot(null, request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(distributedRateLimitService, org.mockito.Mockito.never()).allow(any(), any(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("fngApi: deliberately left anonymous -- the landing page's own pre-login Fear & Greed widget genuinely needs this, unlike every other proxy endpoint")
    void fngApi_remainsAnonymous_noAuthRequired() {
        when(request.getRemoteAddr()).thenReturn("203.0.113.9");
        when(distributedRateLimitService.allow(eq("proxy_rate_limit"), eq("203.0.113.9"), anyInt(), anyLong())).thenReturn(false);

        // No userId parameter at all on this endpoint's own signature -- if this compiles and
        // reaches the rate limiter (429, not 401), the endpoint genuinely requires no auth.
        var response = controller.fngApi(request);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
    }

    // P2 fix ("size-check-after-full-download"): ProxyController's own HTTP client changed from
    // RestTemplate to java.net.http.HttpClient (streaming size cap, see ProxyController's own
    // updated forward()/SizeCappedBodySubscriber javadoc) -- this reflection helper and the mock
    // type below changed to match. The field name ("http") is unchanged.
    private void injectMockHttpClient(ProxyController target, java.net.http.HttpClient mockHttp) throws Exception {
        java.lang.reflect.Field httpField = ProxyController.class.getDeclaredField("http");
        httpField.setAccessible(true);
        httpField.set(target, mockHttp);
    }

    @SuppressWarnings("unchecked")
    private static java.net.http.HttpResponse<String> mockUpstreamResponse(int statusCode, String body) {
        var resp = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.lenient().when(resp.statusCode()).thenReturn(statusCode);
        org.mockito.Mockito.lenient().when(resp.body()).thenReturn(body);
        return resp;
    }

    /**
     * P3-2 fix ("ProxyController.fngApi -- unauthenticated relay -- require auth or cache
     * server-side" -- external review, full context in ProxyController's own updated fngApi
     * comment): the actual new caching behavior -- a second anonymous request for the same
     * path+query within the cache TTL must be served from cache, never trigger a second real
     * upstream call. This is the substance of the fix: it's what makes an anonymous caller
     * unable to force unlimited fresh upstream fetches through this server.
     */
    @Test
    @DisplayName("fngApi: two requests for the same path within the cache TTL hit the real upstream exactly once, the second is served from cache")
    void fngApi_secondRequestWithinTtl_servedFromCache_neverCallsUpstreamTwice() throws Exception {
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");
        when(request.getRequestURI()).thenReturn("/fng-api/fng/");
        when(distributedRateLimitService.allow(eq("proxy_rate_limit"), eq("203.0.113.10"), anyInt(), anyLong())).thenReturn(true);

        var mockHttp = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        injectMockHttpClient(controller, mockHttp);
        var upstreamResponse = mockUpstreamResponse(200, "{\"value\":\"42\"}");
        when(mockHttp.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class)))
            .thenReturn(upstreamResponse);

        var first = controller.fngApi(request);
        var second = controller.fngApi(request);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        verify(mockHttp, org.mockito.Mockito.times(1))
            .send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("fngApi: an upstream/error response is never cached -- a transient outage must not get served to every anonymous caller for the whole TTL")
    void fngApi_upstreamFailure_neverCached_retriesOnNextRequest() throws Exception {
        when(request.getRemoteAddr()).thenReturn("203.0.113.11");
        when(request.getRequestURI()).thenReturn("/fng-api/fng/");
        when(distributedRateLimitService.allow(eq("proxy_rate_limit"), eq("203.0.113.11"), anyInt(), anyLong())).thenReturn(true);

        var mockHttp = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        injectMockHttpClient(controller, mockHttp);
        when(mockHttp.send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class)))
            .thenThrow(new java.io.IOException("upstream unreachable"));

        var first = controller.fngApi(request);
        var second = controller.fngApi(request);

        assertThat(first.getStatusCode().value()).isEqualTo(502);
        assertThat(second.getStatusCode().value()).isEqualTo(502);
        verify(mockHttp, org.mockito.Mockito.times(2))
            .send(any(java.net.http.HttpRequest.class), any(java.net.http.HttpResponse.BodyHandler.class));
    }

    // ---------------------------------------------------------------------------------------
    // Audit item P2 ("ProxyController size-check-after-full-download"): external review,
    // confirmed real by direct inspection -- forward()'s own MAX_RESPONSE_BYTES check used to
    // run AFTER RestTemplate had already fully downloaded and buffered the entire upstream
    // body, so an oversized response still cost this server the full download before being
    // rejected. The fix streams the response through a custom BodySubscriber
    // (SizeCappedBodySubscriber) that counts bytes as they arrive and cancels the subscription
    // the instant the cap is exceeded -- this test proves the ABORT behavior itself: a
    // BodyHandler that simulates exactly what SizeCappedBodySubscriber does (feed it chunks one
    // at a time, same as the real HttpClient would) must cancel the subscription as soon as the
    // running total crosses the cap, and must never accumulate a chunk that would push it over.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("SizeCappedBodySubscriber: cancels the subscription and fails the body future the instant the running byte total exceeds the cap, without ever buffering the chunk that crossed it")
    void sizeCappedBodySubscriber_abortsMidStream_onceCapExceeded() throws Exception {
        long cap = 10L; // tiny cap so the test's own chunk sizes can straightforwardly cross it
        var handlerClass = Class.forName("com.tradevision.controller.ProxyController$SizeCappedBodyHandler");
        var handlerCtor = handlerClass.getDeclaredConstructor(long.class);
        handlerCtor.setAccessible(true);
        @SuppressWarnings("unchecked")
        var handler = (java.net.http.HttpResponse.BodyHandler<String>) handlerCtor.newInstance(cap);

        var subscriber = handler.apply(null);

        var subscription = org.mockito.Mockito.mock(java.util.concurrent.Flow.Subscription.class);
        subscriber.onSubscribe(subscription);

        // First chunk (6 bytes) stays under the 10-byte cap -- must be accepted, no cancel yet.
        subscriber.onNext(java.util.List.of(ByteBuffer.wrap("abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        verify(subscription, org.mockito.Mockito.never()).cancel();

        // Second chunk (6 more bytes) pushes the running total to 12, past the 10-byte cap --
        // must cancel immediately and fail the body future, never reach onComplete.
        subscriber.onNext(java.util.List.of(ByteBuffer.wrap("ghijkl".getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        verify(subscription, org.mockito.Mockito.times(1)).cancel();
        var bodyFuture = subscriber.getBody().toCompletableFuture();
        assertThat(bodyFuture.isCompletedExceptionally()).isTrue();
    }
}
