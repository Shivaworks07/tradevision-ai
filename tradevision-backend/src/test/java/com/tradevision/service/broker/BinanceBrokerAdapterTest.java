package com.tradevision.service.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradevision.service.ExchangeHealthService;
import com.tradevision.service.broker.dto.Fill;
import com.tradevision.service.broker.dto.OcoOrderResult;
import com.tradevision.service.broker.dto.OrderResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding ("Binance adapter can fabricate a full fill" -- P0): tests the actual fix --
 * see BinanceBrokerAdapter's own resolveExecutedQty() javadoc for the full design. No test file
 * existed for this class at all before this fix, despite it being the one place that actually
 * places real orders against Binance -- confirmed by checking directly, not assumed.
 *
 * resolveExecutedQty() is accessed via reflection since it's private and this class's
 * RestTemplate is inline-initialized (matching this codebase's own established pattern
 * elsewhere -- BinanceBrokerAdapter's own buildRestTemplate(), OrderFlowService, etc.), so it
 * isn't mockable and a full doPlaceOrder() integration test isn't achievable without a real
 * network call. This tests the exact logic that was actually wrong, directly, rather than not
 * testing it at all.
 */
class BinanceBrokerAdapterTest {

    // Review finding ("Exchange health is primarily an in-memory metric" -- P1, full context in
    // ExchangeHealthService's own updated header javadoc): ExchangeHealthService now requires a
    // MongoTemplate constructor argument -- this test never exercises its actual behavior (it's
    // only a required dependency for BinanceBrokerAdapter here), so a plain Mockito.mock() is
    // the right, minimal fix rather than pulling in the full Mockito JUnit extension for this
    // otherwise-plain test class.
    private final BinanceBrokerAdapter adapter = new BinanceBrokerAdapter(
        new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class)));
    private final ObjectMapper mapper = new ObjectMapper();

    private Object invokeResolveExecutedQty(JsonNode resp, String claimedStatus, BigDecimal requestedQty, String symbol) throws Exception {
        Method m = BinanceBrokerAdapter.class.getDeclaredMethod("resolveExecutedQty", JsonNode.class, String.class, BigDecimal.class, String.class);
        m.setAccessible(true);
        return m.invoke(adapter, resp, claimedStatus, requestedQty, symbol);
    }

    private double getExecutedQty(Object result) throws Exception {
        Method m = result.getClass().getDeclaredMethod("executedQty");
        return ((BigDecimal) m.invoke(result)).doubleValue();
    }

    private String getStatus(Object result) throws Exception {
        Method m = result.getClass().getDeclaredMethod("status");
        return (String) m.invoke(result);
    }

    @Test
    @DisplayName("resolveExecutedQty: a response missing executedQty entirely is treated as ZERO filled and UNKNOWN status, never as the full requested quantity -- the actual P0 bug, confirmed fixed by direct execution, not inspection")
    void missingExecutedQty_neverInfersFullFill() throws Exception {
        JsonNode resp = mapper.readTree("{\"status\":\"FILLED\",\"orderId\":123}"); // executedQty field absent entirely

        Object result = invokeResolveExecutedQty(resp, "FILLED", new BigDecimal("1.0"), "BTCUSDT");

        assertThat(getExecutedQty(result)).isEqualTo(0.0);
        assertThat(getStatus(result)).isEqualTo("UNKNOWN"); // downgraded despite Binance's own response claiming FILLED
    }

    @Test
    @DisplayName("resolveExecutedQty: an explicit JSON null for executedQty is treated the same as a missing field, not parsed as a literal value")
    void explicitNullExecutedQty_treatedAsMissing() throws Exception {
        JsonNode resp = mapper.readTree("{\"status\":\"FILLED\",\"executedQty\":null}");

        Object result = invokeResolveExecutedQty(resp, "FILLED", new BigDecimal("1.0"), "BTCUSDT");

        assertThat(getExecutedQty(result)).isEqualTo(0.0);
        assertThat(getStatus(result)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("resolveExecutedQty: a genuinely present, valid executedQty within [0, requested] is trusted and the claimed status is preserved")
    void validExecutedQty_trustedAsIs() throws Exception {
        JsonNode resp = mapper.readTree("{\"status\":\"FILLED\",\"executedQty\":\"1.00000000\"}");

        Object result = invokeResolveExecutedQty(resp, "FILLED", new BigDecimal("1.0"), "BTCUSDT");

        assertThat(getExecutedQty(result)).isEqualTo(1.0);
        assertThat(getStatus(result)).isEqualTo("FILLED");
    }

    @Test
    @DisplayName("resolveExecutedQty: a genuine partial fill (less than requested) is trusted correctly, not treated as suspicious")
    void partialFill_trustedCorrectly() throws Exception {
        JsonNode resp = mapper.readTree("{\"status\":\"PARTIALLY_FILLED\",\"executedQty\":\"0.5\"}");

        Object result = invokeResolveExecutedQty(resp, "PARTIALLY_FILLED", new BigDecimal("1.0"), "BTCUSDT");

        assertThat(getExecutedQty(result)).isEqualTo(0.5);
        assertThat(getStatus(result)).isEqualTo("PARTIALLY_FILLED");
    }

    @Test
    @DisplayName("resolveExecutedQty: an impossible executedQty greater than requested (e.g. requested 1, executed 1.5) is refused and downgraded to UNKNOWN, never trusted")
    void impossiblyLargeExecutedQty_refused() throws Exception {
        JsonNode resp = mapper.readTree("{\"status\":\"FILLED\",\"executedQty\":\"1.5\"}");

        Object result = invokeResolveExecutedQty(resp, "FILLED", new BigDecimal("1.0"), "BTCUSDT");

        assertThat(getExecutedQty(result)).isEqualTo(0.0);
        assertThat(getStatus(result)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("resolveExecutedQty: a negative executedQty is refused and downgraded to UNKNOWN, never trusted")
    void negativeExecutedQty_refused() throws Exception {
        JsonNode resp = mapper.readTree("{\"status\":\"FILLED\",\"executedQty\":\"-0.1\"}");

        Object result = invokeResolveExecutedQty(resp, "FILLED", new BigDecimal("1.0"), "BTCUSDT");

        assertThat(getExecutedQty(result)).isEqualTo(0.0);
        assertThat(getStatus(result)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("resolveExecutedQty: executedQty exactly equal to the requested quantity (a genuine full fill) is trusted, not treated as suspicious for being at the boundary")
    void executedQtyExactlyAtRequestedBoundary_trusted() throws Exception {
        JsonNode resp = mapper.readTree("{\"status\":\"FILLED\",\"executedQty\":\"2.5\"}");

        Object result = invokeResolveExecutedQty(resp, "FILLED", new BigDecimal("2.5"), "BTCUSDT");

        assertThat(getExecutedQty(result)).isEqualTo(2.5);
        assertThat(getStatus(result)).isEqualTo("FILLED");
    }

    @Test
    @DisplayName("resolveExecutedQty: executedQty exactly ZERO (a genuinely unfilled NEW order) is trusted as zero, not treated as a missing-field case")
    void executedQtyExactlyZero_trustedAsGenuineZero() throws Exception {
        JsonNode resp = mapper.readTree("{\"status\":\"NEW\",\"executedQty\":\"0\"}");

        Object result = invokeResolveExecutedQty(resp, "NEW", new BigDecimal("1.0"), "BTCUSDT");

        assertThat(getExecutedQty(result)).isEqualTo(0.0);
        assertThat(getStatus(result)).isEqualTo("NEW"); // genuine zero with a present field -- not downgraded, unlike the missing-field case
    }

    // ── parseOcoLeg (review finding "Missing broker/OMS test scenarios (missing-executedQty,
    // OCO-lifecycle, etc.)" -- full context in the method's own javadoc) ────────────────

    private com.tradevision.service.broker.dto.OcoStatusInfo.Leg invokeParseOcoLeg(String legOrderId, JsonNode legResp) throws Exception {
        Method m = BinanceBrokerAdapter.class.getDeclaredMethod("parseOcoLeg", String.class, JsonNode.class);
        m.setAccessible(true);
        return (com.tradevision.service.broker.dto.OcoStatusInfo.Leg) m.invoke(adapter, legOrderId, legResp);
    }

    @Test
    @DisplayName("parseOcoLeg: a fully filled leg (the TAKE_PROFIT or STOP_LOSS_LIMIT that actually triggered) correctly computes its own average fill price from cumulativeQuoteQty/executedQty")
    void parseOcoLeg_fullyFilled_computesCorrectAveragePrice() throws Exception {
        JsonNode legResp = mapper.readTree("{\"side\":\"SELL\",\"type\":\"LIMIT_MAKER\",\"status\":\"FILLED\",\"executedQty\":\"1.0\",\"cummulativeQuoteQty\":\"110.5\"}");

        var leg = invokeParseOcoLeg("tp1", legResp);

        assertThat(leg.orderId()).isEqualTo("tp1");
        assertThat(leg.status()).isEqualTo("FILLED");
        assertThat(leg.executedQty()).isEqualByComparingTo("1.0");
        assertThat(leg.price()).isEqualByComparingTo("110.5"); // 110.5 / 1.0
    }

    @Test
    @DisplayName("parseOcoLeg: a partially filled leg computes its average price from the PARTIAL executedQty/cumulativeQuoteQty, not the full requested size -- the actual review scenario (\"partial fills\")")
    void parseOcoLeg_partiallyFilled_computesAveragePriceFromPartialFill() throws Exception {
        JsonNode legResp = mapper.readTree("{\"side\":\"SELL\",\"type\":\"STOP_LOSS_LIMIT\",\"status\":\"PARTIALLY_FILLED\",\"executedQty\":\"0.4\",\"cummulativeQuoteQty\":\"38.0\"}");

        var leg = invokeParseOcoLeg("sl1", legResp);

        assertThat(leg.status()).isEqualTo("PARTIALLY_FILLED");
        assertThat(leg.executedQty()).isEqualByComparingTo("0.4");
        assertThat(leg.price()).isEqualByComparingTo("95"); // 38.0 / 0.4
    }

    @Test
    @DisplayName("parseOcoLeg: the still-pending leg of an OCO pair (executedQty genuinely zero, the OTHER leg is the one that triggered) is priced at zero, not a division-by-zero exception -- the actual review scenario (\"ALL_DONE with and without a filled leg\")")
    void parseOcoLeg_stillPendingLeg_pricedAtZero_noDivisionByZero() throws Exception {
        JsonNode legResp = mapper.readTree("{\"side\":\"SELL\",\"type\":\"LIMIT_MAKER\",\"status\":\"NEW\",\"executedQty\":\"0\",\"cummulativeQuoteQty\":\"0\"}");

        var leg = invokeParseOcoLeg("tp1", legResp);

        assertThat(leg.status()).isEqualTo("NEW");
        assertThat(leg.executedQty()).isEqualByComparingTo("0");
        assertThat(leg.price()).isEqualByComparingTo("0"); // must not throw ArithmeticException on a zero-divisor
    }

    // ── buildOrderResultOrUnknown / buildOcoResultOrFailure (review finding "Broker response can
    // be treated as successful without mandatory broker IDs" -- P0, full context in both
    // methods' own javadoc) ────────────────

    private Object invokeBuildOrderResultOrUnknown(JsonNode resp, String rawJson, Object resolved, BigDecimal fillPrice,
                                                     String fallbackClientOrderId, String symbol, java.util.List<Fill> fills) throws Exception {
        Class<?> executedQtyResultClass = Class.forName("com.tradevision.service.broker.BinanceBrokerAdapter$ExecutedQtyResult");
        Method m = BinanceBrokerAdapter.class.getDeclaredMethod("buildOrderResultOrUnknown",
            JsonNode.class, String.class, executedQtyResultClass, BigDecimal.class, String.class, String.class, java.util.List.class);
        m.setAccessible(true);
        return m.invoke(adapter, resp, rawJson, resolved, fillPrice, fallbackClientOrderId, symbol, fills);
    }

    @Test
    @DisplayName("buildOrderResultOrUnknown: a response with a real orderId is reported as a genuine success -- the ordinary, expected case")
    void buildOrderResultOrUnknown_realOrderId_success() throws Exception {
        JsonNode resp = mapper.readTree("{\"orderId\":\"12345\",\"clientOrderId\":\"tv-s-abc\",\"status\":\"FILLED\",\"executedQty\":\"1.0\"}");
        Object resolved = invokeResolveExecutedQty(resp, "FILLED", new BigDecimal("1.0"), "BTCUSDT");

        Object result = invokeBuildOrderResultOrUnknown(resp, resp.toString(), resolved, new BigDecimal("100"), "tv-s-abc", "BTCUSDT", java.util.List.of());

        OrderResult orderResult = (OrderResult) result;
        assertThat(orderResult.success()).isTrue();
        assertThat(orderResult.brokerOrderId()).isEqualTo("12345");
    }

    @Test
    @DisplayName("buildOrderResultOrUnknown: a response missing orderId entirely is reported as UNKNOWN, never as a false success -- the actual review fix (\"Broker response can be treated as successful without mandatory broker IDs\")")
    void buildOrderResultOrUnknown_missingOrderId_reportsUnknownNotSuccess() throws Exception {
        JsonNode resp = mapper.readTree("{\"status\":\"FILLED\",\"executedQty\":\"1.0\"}"); // no orderId at all
        Object resolved = invokeResolveExecutedQty(resp, "FILLED", new BigDecimal("1.0"), "BTCUSDT");

        Object result = invokeBuildOrderResultOrUnknown(resp, resp.toString(), resolved, new BigDecimal("100"), "tv-s-abc", "BTCUSDT", java.util.List.of());

        OrderResult orderResult = (OrderResult) result;
        assertThat(orderResult.success()).isFalse();
        assertThat(orderResult.status()).isEqualTo("UNKNOWN");
        assertThat(orderResult.brokerOrderId()).isNull();
    }

    private Object invokeBuildOcoResultOrFailure(JsonNode resp, String symbol, BigDecimal roundedQty) throws Exception {
        Method m = BinanceBrokerAdapter.class.getDeclaredMethod("buildOcoResultOrFailure", JsonNode.class, String.class, BigDecimal.class);
        m.setAccessible(true);
        return m.invoke(adapter, resp, symbol, roundedQty);
    }

    @Test
    @DisplayName("buildOcoResultOrFailure: a response with a real orderListId is reported as a genuine success, carrying the actual rounded quantity through")
    void buildOcoResultOrFailure_realOrderListId_success() throws Exception {
        JsonNode resp = mapper.readTree("{\"orderListId\":\"999\"}");

        Object result = invokeBuildOcoResultOrFailure(resp, "BTCUSDT", new BigDecimal("0.9"));

        OcoOrderResult ocoResult = (OcoOrderResult) result;
        assertThat(ocoResult.success()).isTrue();
        assertThat(ocoResult.ocoOrderListId()).isEqualTo("999");
        assertThat(ocoResult.actualProtectedQuantity()).isEqualByComparingTo("0.9");
    }

    @Test
    @DisplayName("buildOcoResultOrFailure: a response missing orderListId entirely is reported as a failure, never as a false success -- the actual review fix (\"Broker response can be treated as successful without mandatory broker IDs\")")
    void buildOcoResultOrFailure_missingOrderListId_reportsFailureNotSuccess() throws Exception {
        JsonNode resp = mapper.readTree("{}"); // no orderListId at all

        Object result = invokeBuildOcoResultOrFailure(resp, "BTCUSDT", new BigDecimal("0.9"));

        OcoOrderResult ocoResult = (OcoOrderResult) result;
        assertThat(ocoResult.success()).isFalse();
        assertThat(ocoResult.ocoOrderListId()).isNull();
    }

    // ---------------------------------------------------------------------------------------
    // P0-1 fix ("Non-idempotent retry of MARKET order placement -> duplicate real orders"):
    // confirmed real by direct inspection -- call()'s own retry loop used to resend an identical
    // POST /api/v3/order (or /api/v3/orderList/oco) up to 3x on any timeout/5xx, INCLUDING when
    // the first attempt actually succeeded on Binance's side and only the response was lost.
    // Binance's newClientOrderId/listClientOrderId dedup only rejects a retry while the original
    // order/list is still OPEN -- once it has filled, a resend with the same id is accepted as a
    // genuine second order, not rejected as a duplicate. The fix: order/OCO placement now passes
    // idempotent=false into signedPost, so call() makes exactly ONE attempt for those two calls
    // and never resends them -- the existing verify-by-clientOrderId recovery (tryRecoverOrder-
    // ByClientId / tryRecoverOcoByListClientOrderId) is what resolves an ambiguous outcome, and
    // it runs before any second network request, not after one has already gone out.
    //
    // http is a private final field, inline-initialized in the class body (this codebase's own
    // established pattern -- see this test file's own class javadoc) rather than constructor-
    // injected, so it isn't mockable through the constructor. Reflection swaps it for a Mockito
    // mock here specifically to make this retry-vs-no-retry behavior directly testable without a
    // real network call, the same reasoning already used for resolveExecutedQty/buildOcoResult-
    // OrFailure above.
    // ---------------------------------------------------------------------------------------

    private void injectMockRestTemplate(BinanceBrokerAdapter target, org.springframework.web.client.RestTemplate mockHttp) throws Exception {
        // A plain (non-static) final field can still be overwritten via reflection after
        // setAccessible(true) in Java 21 -- the "modifiers" trick required for static final
        // fields was removed in JEP 396/403 and isn't needed (or usable) here anyway.
        java.lang.reflect.Field httpField = BinanceBrokerAdapter.class.getDeclaredField("http");
        httpField.setAccessible(true);
        httpField.set(target, mockHttp);
    }

    @Test
    @DisplayName("placeOrder: a read timeout on /api/v3/order results in exactly ONE HTTP call, never a blind retry -- a retried MARKET order after the first one already filled would be accepted by Binance as a genuine second order, not rejected as a duplicate")
    void placeOrder_timeoutOnNonIdempotentCall_makesExactlyOneAttempt() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        // doPlaceOrder loads symbol rules (a plain GET, unrelated to this test's own idempotency
        // question) before it ever reaches the POST under test -- must succeed or the POST is
        // never attempted at all and the test would trivially "pass" for the wrong reason.
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/exchangeInfo"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(
                "{\"symbols\":[{\"baseAsset\":\"BTC\",\"quoteAsset\":\"USDT\",\"filters\":["
                    + "{\"filterType\":\"PRICE_FILTER\",\"tickSize\":\"0.01\"},"
                    + "{\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.0001\",\"minQty\":\"0.0001\"}]}]}"));
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/order"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.POST),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenThrow(new org.springframework.web.client.ResourceAccessException("simulated read timeout"));
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/time"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"serverTime\":1}"));

        var result = adapter.placeOrder("key", "secret", com.tradevision.model.BrokerMode.TESTNET,
            new com.tradevision.service.broker.dto.OrderRequest("BTCUSDT", "BUY", "MARKET", new BigDecimal("0.001"), "tv-s-test1"));

        // A timeout with no clientOrderId-based recovery possible (no order exists yet to find)
        // correctly reports failure -- the point under test is HOW MANY times the POST itself
        // was sent while getting there.
        assertThat(result.success()).isFalse();
        org.mockito.Mockito.verify(mockHttp, org.mockito.Mockito.times(1)).exchange(
            org.mockito.Mockito.contains("/api/v3/order"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.POST),
            org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class));
    }

    // ── P1-8: 418/429 handling honors Retry-After and opens a shared circuit, rather than a
    // blind generic-exponential-backoff retry that extends a real IP ban ────────────────────

    @Test
    @DisplayName("P1-8: a 429 with Retry-After is retried exactly once that honored wait later (not a generic exponential guess), and succeeds once the limit lifts")
    void call_429WithRetryAfter_honorsWaitThenRetries() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.springframework.http.HttpHeaders retryHeaders = new org.springframework.http.HttpHeaders();
        retryHeaders.add("Retry-After", "1"); // kept tiny so this test actually sleeps only ~1s
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/account"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "429", retryHeaders, new byte[0], null))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"balances\":[]}"));
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/time"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"serverTime\":1}"));

        var balances = adapter.getBalance("key", "secret", com.tradevision.model.BrokerMode.TESTNET);

        assertThat(balances).isNotNull();
        org.mockito.Mockito.verify(mockHttp, org.mockito.Mockito.times(2)).exchange(
            org.mockito.Mockito.contains("/api/v3/account"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
            org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class));
    }

    @Test
    @DisplayName("P1-8: a 418 (IP ban) is NEVER retried within the same call, even though it used to be retried like a transient 5xx -- retrying into an active ban is exactly what extends it")
    void call_418_neverRetriedWithinSameCall() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.springframework.http.HttpHeaders banHeaders = new org.springframework.http.HttpHeaders();
        banHeaders.add("Retry-After", "120");
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/account"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.I_AM_A_TEAPOT, "418", banHeaders, new byte[0], null));
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/time"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"serverTime\":1}"));

        assertThatThrownByRunning(() -> adapter.getBalance("key", "secret", com.tradevision.model.BrokerMode.TESTNET));

        // Exactly one attempt -- no retry loop for 418, unlike the old behavior.
        org.mockito.Mockito.verify(mockHttp, org.mockito.Mockito.times(1)).exchange(
            org.mockito.Mockito.contains("/api/v3/account"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
            org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class));
    }

    @Test
    @DisplayName("P1-8: after a 418, a completely SEPARATE later call fails fast with NO network request at all while the ban is still in effect -- the actual \"stop all REST for the ban duration\" review fix, a shared circuit not just a per-call retry decision")
    void call_afterBan_laterCallShortCircuitsWithoutNetworkCall() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.springframework.http.HttpHeaders banHeaders = new org.springframework.http.HttpHeaders();
        banHeaders.add("Retry-After", "120");
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/account"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.I_AM_A_TEAPOT, "418", banHeaders, new byte[0], null));
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/time"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"serverTime\":1}"));
        assertThatThrownByRunning(() -> adapter.getBalance("key", "secret", com.tradevision.model.BrokerMode.TESTNET));
        org.mockito.Mockito.clearInvocations(mockHttp);

        // A completely different call, on the same adapter instance (the real, singleton-bean
        // situation) -- must fail immediately, without ever touching mockHttp at all, since the
        // 120-second ban is still active.
        Exception thrown = null;
        try {
            adapter.getBalance("key", "secret", com.tradevision.model.BrokerMode.TESTNET);
        } catch (Exception e) {
            thrown = e;
        }
        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).containsIgnoringCase("ban");
        org.mockito.Mockito.verifyNoInteractions(mockHttp);
    }

    // ── P1-3: public (unsigned) market-data calls must share the SAME circuit as signed calls ──

    @Test
    @DisplayName("P1-3: a 418 ban opened by a SIGNED call also blocks a later PUBLIC market-data call with no network request at all -- before this fix, public calls bypassed the circuit entirely")
    void call_afterBanFromSignedCall_publicCallAlsoShortCircuits() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.springframework.http.HttpHeaders banHeaders = new org.springframework.http.HttpHeaders();
        banHeaders.add("Retry-After", "120");
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/account"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.I_AM_A_TEAPOT, "418", banHeaders, new byte[0], null));
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/time"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"serverTime\":1}"));
        // Opens the ban via a SIGNED call (getBalance).
        assertThatThrownByRunning(() -> adapter.getBalance("key", "secret", com.tradevision.model.BrokerMode.TESTNET));
        org.mockito.Mockito.clearInvocations(mockHttp);

        // A PUBLIC, unsigned market-data call right after -- must fail immediately, without ever
        // touching mockHttp at all, since the exact same 120-second ban is still active. Before
        // this fix, getCurrentPrice called http.exchange(...) directly and had no idea any
        // circuit existed, so this would have gone straight to the network.
        Exception thrown = null;
        try {
            adapter.getCurrentPrice("BTCUSDT", com.tradevision.model.BrokerMode.TESTNET);
        } catch (Exception e) {
            thrown = e;
        }
        assertThat(thrown).isNotNull();
        org.mockito.Mockito.verifyNoInteractions(mockHttp);
    }

    @Test
    @DisplayName("P1-3: a 418 ban opened by a PUBLIC market-data call also blocks a later SIGNED call -- the circuit is genuinely shared in both directions, not just signed-to-signed")
    void call_afterBanFromPublicCall_signedCallAlsoShortCircuits() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.springframework.http.HttpHeaders banHeaders = new org.springframework.http.HttpHeaders();
        banHeaders.add("Retry-After", "120");
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/ticker/price"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.I_AM_A_TEAPOT, "418", banHeaders, new byte[0], null));
        // Opens the ban via a PUBLIC call (getCurrentPrice) -- IllegalStateException is the
        // friendly wrapper getCurrentPrice's own catch throws; the ban is still recorded before
        // that wrapping happens.
        assertThatThrownByRunning(() -> adapter.getCurrentPrice("BTCUSDT", com.tradevision.model.BrokerMode.TESTNET));
        org.mockito.Mockito.clearInvocations(mockHttp);

        // A SIGNED call right after -- must also fail immediately with no network request,
        // proving the circuit is one shared state, not two independent ones.
        Exception thrown = null;
        try {
            adapter.getBalance("key", "secret", com.tradevision.model.BrokerMode.TESTNET);
        } catch (Exception e) {
            thrown = e;
        }
        assertThat(thrown).isNotNull();
        org.mockito.Mockito.verifyNoInteractions(mockHttp);
    }

    @Test
    @DisplayName("P1-3: getCurrentPrice (a GET, genuinely idempotent): a 503 IS retried up to MAX_RETRIES through the shared circuit path, same as a signed call")
    void getCurrentPrice_transientFailure_stillRetriesThroughSharedPath() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/ticker/price"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenThrow(org.springframework.web.client.HttpServerErrorException.create(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "503", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownByRunning(() -> adapter.getCurrentPrice("BTCUSDT", com.tradevision.model.BrokerMode.TESTNET));

        // MAX_RETRIES=3 -> 4 total attempts (the original + 3 retries) -- proving publicGet
        // genuinely retries through withCircuitBreakerAndRetry rather than making one blind call.
        org.mockito.Mockito.verify(mockHttp, org.mockito.Mockito.times(4)).exchange(
            org.mockito.Mockito.contains("/api/v3/ticker/price"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
            org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class));
    }

    @Test
    @DisplayName("getBalance (a GET, genuinely idempotent): a 503 IS retried up to MAX_RETRIES -- this fix must not silently disable retries for the calls that were always safe to retry")
    void getBalance_transientFailureOnIdempotentCall_stillRetries() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/account"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenThrow(org.springframework.web.client.HttpServerErrorException.create(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "503", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/time"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"serverTime\":1}"));

        assertThatThrownByRunning(() -> adapter.getBalance("key", "secret", com.tradevision.model.BrokerMode.TESTNET));

        // MAX_RETRIES=3 -> 4 total attempts (the original + 3 retries) before giving up.
        org.mockito.Mockito.verify(mockHttp, org.mockito.Mockito.times(4)).exchange(
            org.mockito.Mockito.contains("/api/v3/account"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
            org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class));
    }

    private void assertThatThrownByRunning(org.junit.jupiter.api.function.Executable r) {
        try {
            r.execute();
        } catch (Throwable ignored) {
            // Expected -- this test only cares how many times the underlying HTTP call was made.
        }
    }

    // ── P1-1: PAPER credentials must never reach the real adapter's mutating calls ──

    @Test
    @DisplayName("placeOrder with BrokerMode.PAPER throws instead of hitting the real exchange")
    void placeOrder_paperMode_rejected() {
        var req = new com.tradevision.service.broker.dto.OrderRequest("BTCUSDT", "BUY", "MARKET", BigDecimal.ONE, "cid1");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> adapter.placeOrder("key", "secret", com.tradevision.model.BrokerMode.PAPER, req))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAPER");
    }

    @Test
    @DisplayName("cancelOrder with BrokerMode.PAPER throws instead of hitting the real exchange")
    void cancelOrder_paperMode_rejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> adapter.cancelOrder("key", "secret", com.tradevision.model.BrokerMode.PAPER, "BTCUSDT", "1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAPER");
    }

    @Test
    @DisplayName("placeExitOco with BrokerMode.PAPER throws instead of hitting the real exchange")
    void placeExitOco_paperMode_rejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> adapter.placeExitOco("key", "secret", com.tradevision.model.BrokerMode.PAPER, "BTCUSDT",
                    BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, "list1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAPER");
    }

    @Test
    @DisplayName("cancelOco with BrokerMode.PAPER throws instead of hitting the real exchange")
    void cancelOco_paperMode_rejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> adapter.cancelOco("key", "secret", com.tradevision.model.BrokerMode.PAPER, "BTCUSDT", "1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAPER");
    }

    // ── P1-10: the real key-level apiRestrictions check, not the account-level canWithdraw flag ──

    @Test
    @DisplayName("P1-10: getApiKeyRestrictions reads the real key-level fields from /sapi/v1/account/apiRestrictions, not /api/v3/account")
    void getApiKeyRestrictions_readsRealKeyLevelFields() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/sapi/v1/account/apiRestrictions"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(
                "{\"ipRestrict\":true,\"enableWithdrawals\":false,\"enableInternalTransfer\":false,"
                    + "\"permitsUniversalTransfer\":false,\"enableSpotAndMarginTrading\":true}"));
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/time"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"serverTime\":1}"));

        var result = adapter.getApiKeyRestrictions("key", "secret", com.tradevision.model.BrokerMode.LIVE);

        assertThat(result.ipRestrict()).isTrue();
        assertThat(result.enableWithdrawals()).isFalse();
        assertThat(result.enableInternalTransfer()).isFalse();
        assertThat(result.permitsUniversalTransfer()).isFalse();
        assertThat(result.enableSpotAndMarginTrading()).isTrue();
        org.mockito.Mockito.verify(mockHttp, org.mockito.Mockito.never()).exchange(
            org.mockito.Mockito.contains("/api/v3/account"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
            org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class));
    }

    @Test
    @DisplayName("P1-10: getApiKeyRestrictions defaults every field to the UNSAFE reading when the broker's response is missing it, never assuming a missing restriction means safe")
    void getApiKeyRestrictions_missingFields_defaultToUnsafe() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/sapi/v1/account/apiRestrictions"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{}")); // every field absent
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/time"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"serverTime\":1}"));

        var result = adapter.getApiKeyRestrictions("key", "secret", com.tradevision.model.BrokerMode.LIVE);

        assertThat(result.ipRestrict()).isFalse();              // unsafe: not restricted
        assertThat(result.enableWithdrawals()).isTrue();        // unsafe: assume withdrawal-capable
        assertThat(result.enableInternalTransfer()).isTrue();   // unsafe: assume transfer-capable
        assertThat(result.permitsUniversalTransfer()).isTrue(); // unsafe: assume transfer-capable
        assertThat(result.enableSpotAndMarginTrading()).isFalse(); // unsafe: cannot assume trading is enabled
    }

    // ── P2-3: getSymbolRules PERCENT_PRICE_BY_SIDE / NOTIONAL maxNotional / applyMinToMarket
    // parsing, doPlaceOrder/placeExitOco pre-submit notional+band gating, and the -1013
    // cache-eviction behavior ────────────────────────────────────────────────────────────

    private static final String EXCHANGE_INFO_FULL_FILTERS =
        "{\"symbols\":[{\"baseAsset\":\"BTC\",\"quoteAsset\":\"USDT\",\"filters\":["
            + "{\"filterType\":\"PRICE_FILTER\",\"tickSize\":\"0.01\"},"
            + "{\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.0001\",\"minQty\":\"0.0001\"},"
            + "{\"filterType\":\"NOTIONAL\",\"minNotional\":\"10\",\"maxNotional\":\"9000000\","
            +   "\"applyMinToMarket\":true,\"applyMaxToMarket\":true},"
            + "{\"filterType\":\"PERCENT_PRICE_BY_SIDE\",\"multiplierUp\":\"1.05\",\"multiplierDown\":\"0.95\"},"
            + "{\"filterType\":\"MAX_NUM_ALGO_ORDERS\",\"maxNumAlgoOrders\":5}"
            + "]}]}";

    @Test
    @DisplayName("P2-3: getSymbolRules parses maxNotional/applyMinToMarket/applyMaxToMarket from the NOTIONAL filter and multiplierUp/multiplierDown from PERCENT_PRICE_BY_SIDE -- previously discarded entirely")
    void getSymbolRules_parsesNewNotionalAndPercentPriceFields() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/exchangeInfo"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(EXCHANGE_INFO_FULL_FILTERS));

        var rules = adapter.getSymbolRules("BTCUSDT", com.tradevision.model.BrokerMode.TESTNET);

        assertThat(rules.minNotional()).isEqualByComparingTo("10");
        assertThat(rules.maxNotional()).isEqualByComparingTo("9000000");
        assertThat(rules.applyMinNotionalToMarket()).isTrue();
        assertThat(rules.applyMaxNotionalToMarket()).isTrue();
        assertThat(rules.multiplierUp()).isEqualByComparingTo("1.05");
        assertThat(rules.multiplierDown()).isEqualByComparingTo("0.95");
    }

    @Test
    @DisplayName("P2-3: getSymbolRules defaults maxNotional/multiplierUp/multiplierDown to ZERO when a symbol's filters omit them entirely, never a false positive band/max-notional rejection downstream")
    void getSymbolRules_missingOptionalFilters_defaultToZero() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/exchangeInfo"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(
                "{\"symbols\":[{\"baseAsset\":\"BTC\",\"quoteAsset\":\"USDT\",\"filters\":["
                    + "{\"filterType\":\"PRICE_FILTER\",\"tickSize\":\"0.01\"},"
                    + "{\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.0001\",\"minQty\":\"0.0001\"}]}]}"));

        var rules = adapter.getSymbolRules("BTCUSDT", com.tradevision.model.BrokerMode.TESTNET);

        assertThat(rules.maxNotional()).isEqualByComparingTo("0");
        assertThat(rules.multiplierUp()).isEqualByComparingTo("0");
        assertThat(rules.multiplierDown()).isEqualByComparingTo("0");
        assertThat(rules.applyMinNotionalToMarket()).isFalse();
        assertThat(rules.applyMaxNotionalToMarket()).isFalse();
    }

    private void stubTickerPrice(org.springframework.web.client.RestTemplate mockHttp, String price) {
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/ticker/price"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"price\":\"" + price + "\"}"));
    }

    private void stubServerTime(org.springframework.web.client.RestTemplate mockHttp) {
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/time"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"serverTime\":1}"));
    }

    @Test
    @DisplayName("P2-3: doPlaceOrder rejects a MARKET order whose notional falls below minNotional when applyMinToMarket=true, WITHOUT ever sending the real POST /api/v3/order -- the actual pre-submit gating fix")
    void doPlaceOrder_rejectsBelowMinNotional_whenApplyMinToMarketTrue() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/exchangeInfo"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(EXCHANGE_INFO_FULL_FILTERS));
        stubTickerPrice(mockHttp, "50000"); // 0.0001 * 50000 = 5, below minNotional 10
        stubServerTime(mockHttp);

        var result = adapter.placeOrder("key", "secret", com.tradevision.model.BrokerMode.TESTNET,
            new com.tradevision.service.broker.dto.OrderRequest("BTCUSDT", "BUY", "MARKET", new BigDecimal("0.0001"), "tv-s-test2"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).containsIgnoringCase("below").containsIgnoringCase("minimum");
        org.mockito.Mockito.verify(mockHttp, org.mockito.Mockito.never()).exchange(
            org.mockito.Mockito.contains("/api/v3/order"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.POST),
            org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class));
    }

    @Test
    @DisplayName("P2-3: doPlaceOrder rejects a MARKET order whose notional exceeds maxNotional when applyMaxToMarket=true, WITHOUT ever sending the real POST /api/v3/order")
    void doPlaceOrder_rejectsAboveMaxNotional_whenApplyMaxToMarketTrue() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/exchangeInfo"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(EXCHANGE_INFO_FULL_FILTERS));
        stubTickerPrice(mockHttp, "50000"); // 1000 * 50000 = 50,000,000 > maxNotional 9,000,000
        stubServerTime(mockHttp);

        var result = adapter.placeOrder("key", "secret", com.tradevision.model.BrokerMode.TESTNET,
            new com.tradevision.service.broker.dto.OrderRequest("BTCUSDT", "BUY", "MARKET", new BigDecimal("1000"), "tv-s-test3"));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).containsIgnoringCase("exceeds").containsIgnoringCase("maximum");
        org.mockito.Mockito.verify(mockHttp, org.mockito.Mockito.never()).exchange(
            org.mockito.Mockito.contains("/api/v3/order"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.POST),
            org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class));
    }

    @Test
    @DisplayName("P2-3: doPlaceOrder does NOT reject on notional when applyMinToMarket=false for this symbol -- the filter genuinely doesn't apply to MARKET orders here, so the order proceeds to real submission")
    void doPlaceOrder_doesNotGateOnNotional_whenApplyMinToMarketFalse() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/exchangeInfo"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(
                "{\"symbols\":[{\"baseAsset\":\"BTC\",\"quoteAsset\":\"USDT\",\"filters\":["
                    + "{\"filterType\":\"PRICE_FILTER\",\"tickSize\":\"0.01\"},"
                    + "{\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.0001\",\"minQty\":\"0.0001\"},"
                    + "{\"filterType\":\"NOTIONAL\",\"minNotional\":\"10\",\"applyMinToMarket\":false}]}]}"));
        stubTickerPrice(mockHttp, "50000"); // 0.0001 * 50000 = 5, below minNotional 10 -- but not enforced for MARKET here
        stubServerTime(mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/order"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.POST),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(
                "{\"orderId\":\"777\",\"clientOrderId\":\"tv-s-test4\",\"status\":\"FILLED\",\"executedQty\":\"0.0001\"}"));

        var result = adapter.placeOrder("key", "secret", com.tradevision.model.BrokerMode.TESTNET,
            new com.tradevision.service.broker.dto.OrderRequest("BTCUSDT", "BUY", "MARKET", new BigDecimal("0.0001"), "tv-s-test4"));

        assertThat(result.success()).isTrue();
        org.mockito.Mockito.verify(mockHttp, org.mockito.Mockito.times(1)).exchange(
            org.mockito.Mockito.contains("/api/v3/order"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.POST),
            org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class));
    }

    @Test
    @DisplayName("P2-3: placeExitOco rejects when either leg's own notional falls below minNotional, WITHOUT ever sending the real POST /api/v3/orderList/oco -- leaving a filled position temporarily unprotected is the alternative this specifically avoids")
    void placeExitOco_rejectsWhenLegNotionalBelowMinimum() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/exchangeInfo"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(
                "{\"symbols\":[{\"baseAsset\":\"BTC\",\"quoteAsset\":\"USDT\",\"filters\":["
                    + "{\"filterType\":\"PRICE_FILTER\",\"tickSize\":\"0.01\"},"
                    + "{\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.0001\",\"minQty\":\"0.0001\"},"
                    + "{\"filterType\":\"NOTIONAL\",\"minNotional\":\"10\"}]}]}"));
        stubServerTime(mockHttp);

        // roundedQty 0.0001 * tp 50000 = 5, below minNotional 10 for the take-profit leg.
        var result = adapter.placeExitOco("key", "secret", com.tradevision.model.BrokerMode.TESTNET, "BTCUSDT",
            new BigDecimal("0.0001"), new BigDecimal("50000"), new BigDecimal("49000"), new BigDecimal("48900"), "list-test1");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).containsIgnoringCase("notional").containsIgnoringCase("minimum");
        org.mockito.Mockito.verify(mockHttp, org.mockito.Mockito.never()).exchange(
            org.mockito.Mockito.contains("/api/v3/orderList/oco"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.POST),
            org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class));
    }

    @Test
    @DisplayName("P2-3: placeExitOco rejects when a leg's price falls outside the PERCENT_PRICE_BY_SIDE band around the current price, WITHOUT ever sending the real POST /api/v3/orderList/oco")
    void placeExitOco_rejectsWhenPriceOutsidePercentPriceBand() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/exchangeInfo"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(EXCHANGE_INFO_FULL_FILTERS)); // multiplierUp 1.05 / multiplierDown 0.95
        stubTickerPrice(mockHttp, "50000"); // band: [50000/0.95, 50000*1.05] = [~52631, 52500] -- take-profit way above upperBound
        stubServerTime(mockHttp);

        var result = adapter.placeExitOco("key", "secret", com.tradevision.model.BrokerMode.TESTNET, "BTCUSDT",
            new BigDecimal("1"), new BigDecimal("70000"), new BigDecimal("49000"), new BigDecimal("48900"), "list-test2");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).containsIgnoringCase("band");
        org.mockito.Mockito.verify(mockHttp, org.mockito.Mockito.never()).exchange(
            org.mockito.Mockito.contains("/api/v3/orderList/oco"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.POST),
            org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class));
    }

    @Test
    @DisplayName("P2-3: placeExitOco places the real OCO when both legs' notional and prices are within bounds -- the gating logic doesn't over-reject genuinely valid OCOs")
    void placeExitOco_placesOco_whenWithinAllBounds() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/exchangeInfo"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(EXCHANGE_INFO_FULL_FILTERS));
        stubTickerPrice(mockHttp, "50000"); // band: [~47619, 52500]
        stubServerTime(mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/orderList/oco"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.POST),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"orderListId\":\"555\"}"));

        var result = adapter.placeExitOco("key", "secret", com.tradevision.model.BrokerMode.TESTNET, "BTCUSDT",
            new BigDecimal("1"), new BigDecimal("51000"), new BigDecimal("49000"), new BigDecimal("48900"), "list-test3");

        assertThat(result.success()).isTrue();
        assertThat(result.ocoOrderListId()).isEqualTo("555");
    }

    // ── P2-3: -1013 ("Filter failure") cache eviction ──────────────────────────────────────

    private void invokeEvictSymbolRulesCacheOnFilterFailure(BinanceBrokerAdapter target, String symbol,
                                                              com.tradevision.model.BrokerMode mode, Object exception) throws Exception {
        Method m = BinanceBrokerAdapter.class.getDeclaredMethod("evictSymbolRulesCacheOnFilterFailure",
            String.class, com.tradevision.model.BrokerMode.class, BinanceBrokerAdapter.BinanceApiException.class);
        m.setAccessible(true);
        m.invoke(target, symbol, mode, exception);
    }

    private Integer invokeExtractBinanceErrorCode(BinanceBrokerAdapter target, String rawBody) throws Exception {
        Method m = BinanceBrokerAdapter.class.getDeclaredMethod("extractBinanceErrorCode", String.class);
        m.setAccessible(true);
        return (Integer) m.invoke(target, rawBody);
    }

    @Test
    @DisplayName("P2-3: extractBinanceErrorCode reads Binance's own {\"code\":-1013,...} error body correctly")
    void extractBinanceErrorCode_parsesRealErrorCode() throws Exception {
        assertThat(invokeExtractBinanceErrorCode(adapter, "{\"code\":-1013,\"msg\":\"Filter failure: NOTIONAL\"}")).isEqualTo(-1013);
    }

    @Test
    @DisplayName("P2-3: extractBinanceErrorCode returns null for a missing code, unparseable JSON, or a null/blank body, never a fabricated value")
    void extractBinanceErrorCode_returnsNullForMissingOrUnparseableBody() throws Exception {
        assertThat(invokeExtractBinanceErrorCode(adapter, "{\"msg\":\"no code field\"}")).isNull();
        assertThat(invokeExtractBinanceErrorCode(adapter, "not json at all")).isNull();
        assertThat(invokeExtractBinanceErrorCode(adapter, null)).isNull();
        assertThat(invokeExtractBinanceErrorCode(adapter, "")).isNull();
    }

    @Test
    @DisplayName("P2-3: a -1013 (\"Filter failure\") error evicts the cached symbol rules, so the very next lookup re-fetches from Binance instead of repeating the same stale validation")
    void evictSymbolRulesCacheOnFilterFailure_1013_evictsCacheEntry() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/exchangeInfo"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(EXCHANGE_INFO_FULL_FILTERS));

        adapter.getSymbolRules("BTCUSDT", com.tradevision.model.BrokerMode.TESTNET); // populates the cache
        // Within the 1hr TTL, a second call would normally be served from cache with no new HTTP call.
        adapter.getSymbolRules("BTCUSDT", com.tradevision.model.BrokerMode.TESTNET);
        org.mockito.Mockito.verify(mockHttp, org.mockito.Mockito.times(1)).exchange(
            org.mockito.Mockito.contains("/api/v3/exchangeInfo"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
            org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class));

        var filterFailure = new BinanceBrokerAdapter.BinanceApiException(
            "Filter failure", "{\"code\":-1013,\"msg\":\"Filter failure: NOTIONAL\"}", 400);
        invokeEvictSymbolRulesCacheOnFilterFailure(adapter, "BTCUSDT", com.tradevision.model.BrokerMode.TESTNET, filterFailure);

        adapter.getSymbolRules("BTCUSDT", com.tradevision.model.BrokerMode.TESTNET); // must re-fetch now that the cache was evicted
        org.mockito.Mockito.verify(mockHttp, org.mockito.Mockito.times(2)).exchange(
            org.mockito.Mockito.contains("/api/v3/exchangeInfo"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
            org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class));
    }

    @Test
    @DisplayName("P2-3: a non-(-1013) error code leaves the cached symbol rules untouched -- eviction is specific to filter failures, not every broker error")
    void evictSymbolRulesCacheOnFilterFailure_otherCode_leavesCacheIntact() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/exchangeInfo"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(EXCHANGE_INFO_FULL_FILTERS));

        adapter.getSymbolRules("BTCUSDT", com.tradevision.model.BrokerMode.TESTNET); // populates the cache

        var insufficientBalance = new BinanceBrokerAdapter.BinanceApiException(
            "Insufficient balance", "{\"code\":-2010,\"msg\":\"Account has insufficient balance\"}", 400);
        invokeEvictSymbolRulesCacheOnFilterFailure(adapter, "BTCUSDT", com.tradevision.model.BrokerMode.TESTNET, insufficientBalance);

        adapter.getSymbolRules("BTCUSDT", com.tradevision.model.BrokerMode.TESTNET); // still cached -- no new HTTP call
        org.mockito.Mockito.verify(mockHttp, org.mockito.Mockito.times(1)).exchange(
            org.mockito.Mockito.contains("/api/v3/exchangeInfo"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
            org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class));
    }

    // ── P2-16: market-data fetch failures no longer leak the raw upstream error into the
    // client-facing exception message -- GlobalExceptionHandler.handleBadState returns an
    // IllegalStateException's message to the client verbatim, so this adapter must never put
    // anything from the underlying exception (which can carry a raw Binance response body, a
    // hostname, or another internal client-library detail) into that message ─────────────────

    @Test
    @DisplayName("P2-16: getCurrentPrice failure does not leak the underlying exception's own message into the IllegalStateException surfaced to the caller")
    void getCurrentPrice_upstreamFailure_doesNotLeakUnderlyingExceptionMessage() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        String sensitiveUpstreamDetail = "internal-binance-gateway-host-10.0.4.17 timed out with body {\"secretDebugInfo\":\"xyz\"}";
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/ticker/price"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenThrow(new org.springframework.web.client.ResourceAccessException(sensitiveUpstreamDetail));

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> adapter.getCurrentPrice("BTCUSDT", com.tradevision.model.BrokerMode.TESTNET))
            .getMessage())
            .doesNotContain(sensitiveUpstreamDetail)
            .doesNotContain("10.0.4.17")
            .contains("BTCUSDT");
    }

    @Test
    @DisplayName("P2-16: createListenKey failure does not leak Binance's own raw response body into the IllegalStateException surfaced to the caller")
    void createListenKey_noListenKeyReturned_doesNotLeakRawResponseBody() throws Exception {
        var exchangeHealth = new ExchangeHealthService(org.mockito.Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class));
        var adapter = new BinanceBrokerAdapter(exchangeHealth);
        var mockHttp = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        injectMockRestTemplate(adapter, mockHttp);
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/userDataStream"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.POST),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"unexpectedField\":\"someInternalUpstreamDetail-xyz789\"}"));
        org.mockito.Mockito.when(mockHttp.exchange(
                org.mockito.Mockito.contains("/api/v3/time"), org.mockito.Mockito.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.Mockito.any(), org.mockito.Mockito.eq(String.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok("{\"serverTime\":1}"));

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> adapter.createListenKey("key", "secret", com.tradevision.model.BrokerMode.TESTNET))
            .getMessage())
            .doesNotContain("someInternalUpstreamDetail-xyz789")
            .doesNotContain("unexpectedField");
    }
}
