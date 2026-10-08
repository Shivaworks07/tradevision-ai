package com.tradevision.service.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.BrokerType;
import com.tradevision.service.broker.dto.*;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import com.tradevision.service.ExchangeHealthService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Binance spot adapter. Only the testnet base URL has actually been exercised —
 * OrderExecutionService/AutoTradeService decide whether LIVE is allowed to be called at all;
 * this class will happily sign requests against either host if asked.
 *
 * Binance auth: HMAC-SHA256 over the query string, using the account's API secret as the HMAC key,
 * sent as a `signature` param alongside a `timestamp`. The API key itself goes in the
 * X-MBX-APIKEY header, never in the query string.
 *
 * Review item #26: transient failures (429 rate-limit, 418 IP-banned-briefly, 5xx) are retried
 * with exponential backoff + jitter. This is only safe to do blindly for order placement because
 * of review item #12's idempotency work — every order carries a clientOrderId, so a retried
 * placeOrder() that actually succeeded the first time gets rejected by Binance as a duplicate
 * instead of opening a second position. GET/DELETE calls are naturally idempotent regardless.
 */
@Component
public class BinanceBrokerAdapter implements BrokerAdapter {

    private static final Logger log = LoggerFactory.getLogger(BinanceBrokerAdapter.class);
    private static final int MAX_RETRIES = 3;
    // Review finding (P1 #8 -- "Binance 418/429 handled by blind retry"): confirmed real -- 418
    // (IP auto-ban) and 429 (rate limit) used to be retried with the SAME generic exponential
    // backoff as an ordinary 5xx, ignoring Binance's own Retry-After header entirely. Retrying
    // into an active IP ban is exactly what EXTENDS that ban, and retrying into an active rate
    // limit risks tripping the ban in the first place. Fallback durations, used only when
    // Binance's own response is missing the Retry-After header it's documented to normally
    // include -- deliberately conservative (long enough that a genuinely banned/limited IP isn't
    // hammered again seconds later), never a substitute for the real header when present.
    private static final long DEFAULT_BAN_FALLBACK_SECONDS = 120;
    private static final long DEFAULT_RATE_LIMIT_FALLBACK_SECONDS = 60;
    // Review finding (P1 #8, full context above): a Spring singleton bean, so these are
    // genuinely process-wide -- a 418/429 hit by ANY call through this adapter (any credential,
    // any thread) opens the SAME circuit for every other call, exactly as the review's own fix
    // ("open a global circuit") asks for, rather than each caller independently discovering and
    // separately backing off from the same real, shared IP-level ban/limit.
    private final AtomicReference<Instant> bannedUntil = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> rateLimitedUntil = new AtomicReference<>(Instant.EPOCH);

    private static final String TESTNET_BASE = "https://testnet.binance.vision";
    private static final String LIVE_BASE = "https://api.binance.com";

    private final ExchangeHealthService exchangeHealth;

    public BinanceBrokerAdapter(ExchangeHealthService exchangeHealth) {
        this.exchangeHealth = exchangeHealth;
    }

    private final RestTemplate http = buildRestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    // Symbol rules rarely change — cache them for an hour rather than hitting exchangeInfo on every order.
    private final ConcurrentHashMap<String, AtomicReference<CachedRules>> rulesCache = new ConcurrentHashMap<>();
    private record CachedRules(SymbolRules rules, Instant fetchedAt) {}

    // Review item #25: local clock drift vs. the exchange's clock can push a signed request
    // outside recvWindow and get it silently rejected. Refreshed periodically rather than once
    // at startup, since drift accumulates.
    /**
     * Review finding ("Exchange server-time offset is shared between LIVE and TESTNET" --
     * external review, twenty-second pass, P1, confirmed real by direct inspection before this
     * fix: a single AtomicReference cached the offset from whichever base URL happened to sync
     * most recently, then applied that SAME cached value to every signed request regardless of
     * mode -- a TESTNET time sample could get reused for LIVE signing, or vice versa. Usually
     * the difference is tiny, but a signed, money-moving request should not rely on that
     * assumption): the actual fix -- keyed by base URL (already the exact value that
     * distinguishes LIVE from TESTNET at every call site in this class), so each maintains its
     * own, independently-synced offset.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicReference<Long>> serverTimeOffsetMsByBase = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicReference<Instant>> offsetCheckedAtByBase = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public BrokerType getType() {
        return BrokerType.BINANCE;
    }


    @Override
    public AccountPermissions getAccountPermissions(String apiKey, String apiSecret, BrokerMode mode) {
        JsonNode account = signedGet(apiKey, apiSecret, mode, "/api/v3/account", "");
        return new AccountPermissions(
            account.path("canTrade").asBoolean(false),
            account.path("canWithdraw").asBoolean(true),   // default to true (unsafe) if field is ever missing
            account.path("canDeposit").asBoolean(false)
        );
    }

    /**
     * Review finding (P1 #9, full context in BrokerAdapter.getAccountUid's own javadoc): Binance's
     * own documented `/api/v3/account` response includes a numeric `uid` field identifying the
     * real account the key belongs to -- read from the same response this class already fetches
     * for getAccountPermissions, just not previously looked at.
     */
    @Override
    public String getAccountUid(String apiKey, String apiSecret, BrokerMode mode) {
        JsonNode account = signedGet(apiKey, apiSecret, mode, "/api/v3/account", "");
        JsonNode uidNode = account.path("uid");
        return uidNode.isMissingNode() || uidNode.isNull() ? null : uidNode.asText(null);
    }

    /**
     * Review finding (P1 #10, full context in BrokerAdapter.getApiKeyRestrictions' own javadoc):
     * the real, key-level permissions -- `GET /sapi/v1/account/apiRestrictions`, confirmed against
     * current Binance API documentation before writing this -- as opposed to getAccountPermissions'
     * own /api/v3/account.canWithdraw, which is an account-wide flag and not what this specific
     * key is restricted to. Every field defaults to the UNSAFE reading if the broker's response is
     * ever missing it -- see ApiKeyRestrictions' own class javadoc.
     */
    @Override
    public ApiKeyRestrictions getApiKeyRestrictions(String apiKey, String apiSecret, BrokerMode mode) {
        JsonNode restrictions = signedGet(apiKey, apiSecret, mode, "/sapi/v1/account/apiRestrictions", "");
        return new ApiKeyRestrictions(
            restrictions.path("ipRestrict").asBoolean(false),                    // default false (unsafe: not restricted)
            restrictions.path("enableWithdrawals").asBoolean(true),              // default true (unsafe)
            restrictions.path("enableInternalTransfer").asBoolean(true),         // default true (unsafe)
            restrictions.path("permitsUniversalTransfer").asBoolean(true),       // default true (unsafe)
            restrictions.path("enableSpotAndMarginTrading").asBoolean(false)     // default false (unsafe: cannot assume trading is enabled)
        );
    }

    @Override
    public List<AssetBalance> getBalance(String apiKey, String apiSecret, BrokerMode mode) {
        JsonNode account = signedGet(apiKey, apiSecret, mode, "/api/v3/account", "");
        List<AssetBalance> balances = new ArrayList<>();
        for (JsonNode b : account.path("balances")) {
            BigDecimal free = new BigDecimal(b.path("free").asText("0"));
            BigDecimal locked = new BigDecimal(b.path("locked").asText("0"));
            if (free.signum() > 0 || locked.signum() > 0) {
                balances.add(new AssetBalance(b.path("asset").asText(), free, locked));
            }
        }
        return balances;
    }

    /**
     * Review finding (P1-1 — "Paper mode is routed to the real Binance adapter (testnet) during
     * reconciliation and exits"): PaperBrokerAdapter is the ONLY adapter that is ever allowed to
     * receive mode == PAPER — it simulates fills entirely in-memory/Mongo and never calls out to
     * a real exchange. This class talks to the real Binance REST API (testnet or live base URL)
     * for every order-mutating call, so a PAPER-mode request reaching here at all is always a
     * caller bug (some code path resolved the adapter directly instead of going through
     * BrokerCredentialService.adapterForCredential, which is the one place that correctly routes
     * PAPER credentials to PaperBrokerAdapter instead). Failing loudly here is deliberate
     * defense-in-depth: silently placing a "paper" order for real on testnet is exactly the bug
     * this finding describes, so this must never be allowed to happen quietly again.
     */
    private static void rejectIfPaper(BrokerMode mode, String operation) {
        if (mode == BrokerMode.PAPER) {
            throw new IllegalStateException("BinanceBrokerAdapter." + operation + " called with BrokerMode.PAPER — "
                + "PAPER credentials must be routed through PaperBrokerAdapter (see "
                + "BrokerCredentialService.adapterForCredential), never directly to the real broker adapter.");
        }
    }

    @Override
    public OrderResult placeOrder(String apiKey, String apiSecret, BrokerMode mode, OrderRequest request) {
        rejectIfPaper(mode, "placeOrder");
        // Review finding ("Broker health still incomplete" — "Per-symbol rejection rate:
        // Missing"): wrapped once here rather than scattered across doPlaceOrder's own multiple
        // return points (validation failures, the success path, the recovery-after-error path)
        // — every path funnels through this single record call, so nothing gets missed.
        OrderResult result = doPlaceOrder(apiKey, apiSecret, mode, request);
        // P0-2 fix: doPlaceOrder has already returned a real, resolved OrderResult at this point
        // (the order itself is done, for better or worse) -- a failure recording this purely
        // observational per-symbol stat must never turn an already-successful placement into a
        // thrown exception that the caller (AutoTradeService) would treat as "nothing happened."
        try {
            exchangeHealth.recordOrderOutcome(request.symbol(), result.success());
        } catch (Exception e) {
            log.warn("Could not record order outcome for {} (non-fatal, purely observational): {}", request.symbol(), e.getMessage());
        }
        return result;
    }

    private OrderResult doPlaceOrder(String apiKey, String apiSecret, BrokerMode mode, OrderRequest request) {
        if (!"MARKET".equalsIgnoreCase(request.type())) {
            return OrderResult.failure("Only MARKET orders are supported at this stage", null);
        }

        // Review item #8: round/validate against real exchange filters before sending anything.
        SymbolRules rules;
        try {
            rules = getSymbolRules(request.symbol(), mode);
        } catch (Exception e) {
            return OrderResult.failure("Could not load symbol rules for " + request.symbol() + ": " + e.getMessage(), null);
        }
        BigDecimal quantity = roundDownToStep(request.quantity(), rules.stepSize());
        if (quantity.compareTo(rules.minQty()) < 0) {
            return OrderResult.failure("Quantity " + quantity + " is below " + request.symbol() + "'s minimum " + rules.minQty(), null);
        }

        // P2-3 fix ("NOTIONAL maxNotional/applyMinToMarket not enforced" -- full context in
        // getSymbolRules' own updated comment above): a MARKET order's notional is only checked
        // against min/maxNotional when this symbol's own filter says the MARKET order type is
        // actually subject to it (applyMin/MaxNotionalToMarket) -- Binance itself doesn't apply
        // this filter uniformly, and enforcing it unconditionally would reject orders Binance
        // itself would have accepted. Uses getCurrentPrice as the best available pre-submit price
        // reference (this adapter's own established source for "what does this symbol cost right
        // now", already used elsewhere for spread/drawdown checks) -- the real fill price can
        // differ slightly by the time the order actually executes, so this is a pre-submit sanity
        // check, not a guarantee Binance's own final notional check can never still reject.
        try {
            BigDecimal currentPrice = getCurrentPrice(request.symbol(), mode);
            if (currentPrice != null && currentPrice.signum() > 0) {
                BigDecimal notional = quantity.multiply(currentPrice);
                if (rules.applyMinNotionalToMarket() && rules.minNotional().signum() > 0 && notional.compareTo(rules.minNotional()) < 0) {
                    return OrderResult.failure("Order notional " + notional + " is below " + request.symbol()
                        + "'s minimum " + rules.minNotional() + " (at current price " + currentPrice + ")", null);
                }
                if (rules.applyMaxNotionalToMarket() && rules.maxNotional().signum() > 0 && notional.compareTo(rules.maxNotional()) > 0) {
                    return OrderResult.failure("Order notional " + notional + " exceeds " + request.symbol()
                        + "'s maximum " + rules.maxNotional() + " (at current price " + currentPrice + ")", null);
                }
            }
        } catch (Exception e) {
            // Consistent with this method's own existing fail-open-on-observability philosophy
            // elsewhere (exchangeHealth.recordOrderOutcome's own identical try/catch above): a
            // failed pre-submit PRICE lookup must never block an otherwise-valid order -- Binance
            // itself remains the authoritative final check regardless.
            log.debug("Could not fetch current price for {} to pre-validate notional (non-fatal, Binance's own final check remains authoritative): {}",
                request.symbol(), e.getMessage());
        }

        String query = "symbol=" + enc(request.symbol().toUpperCase())
            + "&side=" + enc(request.side().toUpperCase())
            + "&type=MARKET"
            + "&quantity=" + enc(quantity.toPlainString());
        // Review item #12: idempotency. A retried call with the same clientOrderId is rejected by
        // Binance as a duplicate instead of silently opening a second position.
        if (request.clientOrderId() != null && !request.clientOrderId().isBlank()) {
            query += "&newClientOrderId=" + enc(request.clientOrderId());
        }

        try {
            JsonNode resp = signedPost(apiKey, apiSecret, mode, "/api/v3/order", query, false); // non-idempotent: places a real order, never auto-retried
            String rawJson = resp.toString();
            String status = resp.path("status").asText("UNKNOWN");
            BigDecimal fillPrice = extractAverageFillPrice(resp);
            List<Fill> fills = extractFills(resp);
            // Review finding ("Binance adapter can fabricate a full fill" — P0): confirmed real
            // and fixed. .asText(quantity.toPlainString()) meant a response missing
            // executedQty entirely got silently treated as "fully filled at the requested
            // quantity" -- exactly the phantom-fill class of bug this whole codebase has been
            // built around eliminating everywhere else. Extracted into its own method (below)
            // specifically so this exact logic is directly unit-testable without needing to
            // mock the full HTTP call chain this class has no other way to intercept
            // (RestTemplate is inline-initialized here, matching this codebase's own
            // established pattern, and isn't mockable as a result) -- this class had no test
            // file at all before this fix, despite being the one place that actually places
            // real orders.
            ExecutedQtyResult resolved = resolveExecutedQty(resp, status, quantity, request.symbol());
            return buildOrderResultOrUnknown(resp, rawJson, resolved, fillPrice, request.clientOrderId(), request.symbol(), fills);
        } catch (BinanceApiException e) {
            evictSymbolRulesCacheOnFilterFailure(request.symbol(), mode, e);
            // Review item #9: don't just trust "the HTTP call threw" as "the order wasn't placed".
            // If we sent a clientOrderId, check with Binance directly before reporting failure —
            // a network timeout can mean the order actually went through and only the response
            // was lost. Relying solely on Binance rejecting a clientOrderId duplicate on a retry
            // (review item #12) is necessary but not sufficient on its own.
            if (request.clientOrderId() != null && !request.clientOrderId().isBlank()) {
                OrderResult recovered = tryRecoverOrderByClientId(apiKey, apiSecret, mode, request.symbol(), request.clientOrderId());
                if (recovered != null) return recovered;
            }
            return OrderResult.failure(e.getMessage(), e.rawBody);
        }
    }

    /**
     * Review finding ("Broker response can be treated as successful without mandatory broker
     * IDs" -- P0): confirmed real and fixed -- this used to unconditionally return success=true
     * regardless of whether Binance's own response actually included an orderId. A malformed or
     * incomplete response (orderId missing/null) would then be trusted downstream as a genuine
     * successful placement -- AutoTradeService stores result.brokerOrderId() directly onto
     * Position.entryOrderId, so a null here means a real OPEN position gets created with no way
     * to ever look up its own broker order again. Same "UNKNOWN, not falsely confident" pattern
     * this class's own tryRecoverOrderByClientId already established for its own
     * ambiguous-verification case -- genuinely unable to trust this response as a clean success,
     * so it isn't reported as one.
     *
     * Extracted into its own method specifically so this exact check is directly unit-testable
     * without needing to mock the full HTTP call chain this class has no other way to intercept
     * -- same reasoning, same pattern as resolveExecutedQty's own identical extraction above.
     */
    private OrderResult buildOrderResultOrUnknown(JsonNode resp, String rawJson, ExecutedQtyResult resolved,
                                                    BigDecimal fillPrice, String fallbackClientOrderId, String symbol, List<Fill> fills) {
        String brokerOrderId = resp.path("orderId").asText(null);
        if (brokerOrderId == null) {
            log.error("Binance order placement response for {} is missing orderId entirely -- cannot trust this as a successful placement. Raw response: {}",
                symbol, rawJson);
            return new OrderResult(false, null, resp.path("clientOrderId").asText(fallbackClientOrderId), "UNKNOWN",
                resolved.executedQty(), fillPrice, rawJson,
                "Broker response was missing orderId -- cannot confirm this order was actually placed.", fills);
        }
        return new OrderResult(true, brokerOrderId, resp.path("clientOrderId").asText(null),
            resolved.status(), resolved.executedQty(), fillPrice, rawJson, null, fills);
    }

    /** Returns a real OrderResult if the order actually exists on Binance despite the placement
     *  call throwing, a status="UNKNOWN" OrderResult if we genuinely can't tell either way (the
     *  verification query itself failed), or null only when Binance positively confirms the
     *  order never existed. */
    private OrderResult tryRecoverOrderByClientId(String apiKey, String apiSecret, BrokerMode mode, String symbol, String clientOrderId) {
        try {
            String query = "symbol=" + enc(symbol.toUpperCase()) + "&origClientOrderId=" + enc(clientOrderId);
            JsonNode resp = signedGet(apiKey, apiSecret, mode, "/api/v3/order", query);
            if (resp.path("orderId").isMissingNode()) return null; // Binance positively confirms: never existed
            String claimedStatus = resp.path("status").asText("UNKNOWN");
            // Review finding ("Binance adapter still has residual fabrication risk on incomplete
            // responses (partially fixed)" -- external review, nineteenth pass, P1, confirmed
            // real by direct inspection before this fix: this method used to parse executedQty
            // with its own separate `new BigDecimal(resp.path("executedQty").asText("0"))`,
            // never applying resolveExecutedQty's own missing/out-of-range protections -- a
            // recovered response genuinely claiming FILLED but missing executedQty would
            // silently produce a contradictory OrderResult, status=FILLED with executedQty=0,
            // rather than the UNKNOWN downgrade resolveExecutedQty already applies everywhere
            // else in this file for exactly this scenario): the fix -- reuse the same, already-
            // hardened resolveExecutedQty here too, so every parse path in this file shares one
            // set of protections rather than two independently-maintained ones that could drift.
            // requestedQty is unknown at this recovery point (this is a lookup, not a placement
            // with a known request quantity to bound against) -- pass the parsed value itself as
            // its own upper bound, so resolveExecutedQty's own "> requested" sanity check never
            // spuriously rejects a value it has no independent way to bound here.
            BigDecimal rawExecutedQty = new BigDecimal(resp.path("executedQty").asText("0"));
            var resolved = resolveExecutedQty(resp, claimedStatus, rawExecutedQty.max(BigDecimal.ZERO), symbol);
            BigDecimal cumQuote = new BigDecimal(resp.path("cummulativeQuoteQty").asText("0"));
            BigDecimal fillPrice = resolved.executedQty().signum() > 0 ? cumQuote.divide(resolved.executedQty(), 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            log.info("Recovered order state for clientOrderId {} after a placement-call error: status={}", clientOrderId, resolved.status());
            return new OrderResult(true, resp.path("orderId").asText(null), clientOrderId, resolved.status(), resolved.executedQty(), fillPrice, resp.toString(), null);
        } catch (Exception e) {
            // Review finding (P1 #22 — "Broker API retry logic... needs a true UNKNOWN state"):
            // confirmed real — this used to return null here, which the caller treated
            // identically to "Binance confirms this order never existed" (line above). Those are
            // NOT the same thing: this branch means the verification query ITSELF failed — the
            // order's real state on the exchange is genuinely unknown, not confirmed absent.
            // Returning a distinguishable status="UNKNOWN" result lets the caller halt for
            // manual review instead of releasing the slot/exposure reservation as if nothing
            // happened, which could let a second trade start while this one is still genuinely
            // unresolved on the exchange.
            log.warn("Could not verify order state for clientOrderId {} after a placement-call error: {}", clientOrderId, e.getMessage());
            return new OrderResult(false, null, clientOrderId, "UNKNOWN", null, null, null,
                "Order placement failed AND its real state could not be verified: " + e.getMessage(), java.util.List.of());
        }
    }

    private record ExecutedQtyResult(BigDecimal executedQty, String status) {}

    /**
     * Review finding ("Binance adapter can fabricate a full fill" — P0): the extracted,
     * directly-testable core of the fix. Never infers full execution from a missing
     * executedQty field (unlike the code this replaced, which defaulted to the full requested
     * quantity) — defaults to zero instead, matching every other executedQty parse already in
     * this file. Also validates the field IS present against sanity bounds: negative or
     * impossibly-large (greater than what was actually requested) values are exactly as
     * untrustworthy as a missing field. Either failure mode downgrades status to UNKNOWN
     * regardless of what Binance's own status field separately claimed — UNKNOWN already has
     * real, built downstream handling in this codebase (AutoTradeService raises a CRITICAL
     * incident and halts the profile on it), so routing a fabricated-fill risk into that
     * existing safety path is the correct fix, not a new one.
     */
    private ExecutedQtyResult resolveExecutedQty(JsonNode resp, String claimedStatus, BigDecimal requestedQty, String symbol) {
        if (!resp.hasNonNull("executedQty")) {
            log.warn("Binance order response for {} is missing executedQty entirely (claimed status: {}) -- refusing to infer full execution from an absent field, treating as UNKNOWN.",
                symbol, claimedStatus);
            return new ExecutedQtyResult(BigDecimal.ZERO, "UNKNOWN");
        }
        BigDecimal executedQty = new BigDecimal(resp.path("executedQty").asText("0"));
        if (executedQty.signum() < 0 || executedQty.compareTo(requestedQty) > 0) {
            log.warn("Binance executedQty {} for a {} order of {} is outside [0, requested] -- refusing to trust it, treating as UNKNOWN.",
                executedQty, symbol, requestedQty);
            return new ExecutedQtyResult(BigDecimal.ZERO, "UNKNOWN");
        }
        return new ExecutedQtyResult(executedQty, claimedStatus);
    }

    /**
     * Same verify-before-declaring-failure pattern as tryRecoverOrderByClientId, for OCOs.
     *
     * Review finding ("OCO recovery still returns null for some verification failures" --
     * external review, twenty-fourth pass, P1, full context in OcoOrderResult's own updated
     * class javadoc): this used to return null both when Binance positively confirmed no OCO
     * exists (resp.path("orderListId").isMissingNode()) AND when the verification query itself
     * threw -- its one caller could not tell the two apart. Never returns null now: a confirmed
     * absence is a genuine OcoOrderResult.failure(...) (safe to treat as "no OCO exists"), while
     * a verification failure is OcoOrderResult.uncertain(...) (must NOT be treated the same way).
     */
    private OcoOrderResult tryRecoverOcoByListClientOrderId(String apiKey, String apiSecret, BrokerMode mode, String listClientOrderId) {
        try {
            String query = "listClientOrderId=" + enc(listClientOrderId);
            JsonNode resp = signedGet(apiKey, apiSecret, mode, "/api/v3/orderList", query);
            if (resp.path("orderListId").isMissingNode()) {
                // Binance positively confirms: no OCO exists under this client id at all. This
                // is a genuine, confirmed absence -- safe to treat as an ordinary failed
                // placement, not the "uncertain" state this fix exists to separate out.
                return OcoOrderResult.failure("Binance confirms no OCO exists for listClientOrderId " + listClientOrderId, resp.toString());
            }
            String listStatus = resp.path("listOrderStatus").asText("UNKNOWN");
            String orderListId = resp.path("orderListId").asText(null);
            log.info("Recovered OCO state for listClientOrderId {} after a placement-call error: listOrderStatus={}",
                listClientOrderId, listStatus);
            // Review finding (P1 #4 — "OCO recovery doesn't fully verify recovered list state"):
            // confirmed real — this used to return success=true unconditionally as soon as ANY
            // OCO record was found, regardless of listOrderStatus. "EXEC_STARTED" (genuinely
            // active) is the only state that actually means protection succeeded. "ALL_DONE"
            // could mean a leg already FILLED (the position may already be partially or fully
            // closed) or REJECTED (protection never actually activated) — both are real failures
            // to report as such, not silently treated as success.
            if ("EXEC_STARTED".equalsIgnoreCase(listStatus)) {
                return new OcoOrderResult(true, orderListId, resp.toString(), null);
            }
            // Not active — but the orderListId is still returned (with success=false) so the
            // caller can record it on the position and let emergencyFlatten's own OCO-aware
            // state machine (see its "P0 #1" fix) verify actual fill state before ever selling,
            // rather than the caller treating "not active" as "nothing exists to worry about".
            return new OcoOrderResult(false, orderListId, resp.toString(),
                "OCO recovered but listOrderStatus=" + listStatus + ", not active protection — needs verification before any flatten.");
        } catch (Exception e) {
            log.warn("Could not verify OCO state for listClientOrderId {} after a placement-call error: {}", listClientOrderId, e.getMessage());
            // Review finding, same context as this method's own updated javadoc: the actual
            // fix -- the verification query itself failing is genuinely NOT the same as
            // Binance confirming absence. A real, active OCO might exist that this specific
            // check simply couldn't reach right now (a transient network issue, for instance).
            return OcoOrderResult.uncertain("Could not verify OCO state for listClientOrderId " + listClientOrderId
                + " after a placement-call error: " + e.getMessage(), null);
        }
    }

    @Override
    public OrderResult cancelOrder(String apiKey, String apiSecret, BrokerMode mode, String symbol, String brokerOrderId) {
        rejectIfPaper(mode, "cancelOrder");
        String query = "symbol=" + enc(symbol.toUpperCase()) + "&orderId=" + enc(brokerOrderId);
        try {
            JsonNode resp = signedDelete(apiKey, apiSecret, mode, "/api/v3/order", query);
            return new OrderResult(true, resp.path("orderId").asText(null), resp.path("clientOrderId").asText(null),
                resp.path("status").asText("CANCELED"), null, null, resp.toString(), null);
        } catch (BinanceApiException e) {
            return OrderResult.failure(e.getMessage(), e.rawBody);
        }
    }

    @Override
    public List<OpenOrderInfo> getOpenOrders(String apiKey, String apiSecret, BrokerMode mode) {
        return getOpenOrdersInternal(apiKey, apiSecret, mode, "");
    }

    @Override
    public List<OpenOrderInfo> getOpenOrders(String apiKey, String apiSecret, BrokerMode mode, String symbol) {
        // P3-11 second re-audit fix (full context in BrokerAdapter's own updated javadoc): the
        // real, cheaper query -- Binance's own documented weight for GET /api/v3/openOrders is
        // far higher without a symbol filter (a full-account sweep) than with one (checked
        // directly against Binance's own API docs before writing this, not assumed).
        return getOpenOrdersInternal(apiKey, apiSecret, mode, "symbol=" + enc(symbol.toUpperCase()));
    }

    private List<OpenOrderInfo> getOpenOrdersInternal(String apiKey, String apiSecret, BrokerMode mode, String query) {
        JsonNode orders = signedGet(apiKey, apiSecret, mode, "/api/v3/openOrders", query);
        List<OpenOrderInfo> result = new ArrayList<>();
        for (JsonNode o : orders) {
            // Third re-audit fix (full context in OpenOrderInfo's own updated javadoc): Binance
            // returns orderListId on every entry, using -1 (not absent) to mean "not part of any
            // order list". Normalize that sentinel to null here so PositionSafetyService's
            // ownership check can do a plain non-null comparison against Position.ocoOrderListId
            // without every call site having to know about Binance's own "-1 means none" convention.
            String orderListId = o.path("orderListId").asText(null);
            if (orderListId == null || "-1".equals(orderListId)) {
                orderListId = null;
            }
            result.add(new OpenOrderInfo(
                o.path("orderId").asText(null),
                orderListId,
                o.path("symbol").asText(),
                o.path("side").asText(),
                o.path("status").asText(),
                new BigDecimal(o.path("origQty").asText("0")),
                new BigDecimal(o.path("price").asText("0"))
            ));
        }
        return result;
    }

    @Override
    public OcoOrderResult placeExitOco(String apiKey, String apiSecret, BrokerMode mode, String symbol,
                                        BigDecimal quantity, BigDecimal takeProfitPrice,
                                        BigDecimal stopLossTriggerPrice, BigDecimal stopLossLimitPrice,
                                        String listClientOrderId) {
        rejectIfPaper(mode, "placeExitOco");
        SymbolRules rules;
        try {
            rules = getSymbolRules(symbol, mode);
        } catch (Exception e) {
            return OcoOrderResult.failure("Could not load symbol rules for " + symbol + ": " + e.getMessage(), null);
        }
        BigDecimal roundedQty = roundDownToStep(quantity, rules.stepSize());
        BigDecimal tp = roundToTick(takeProfitPrice, rules.tickSize());
        BigDecimal stopTrigger = roundToTick(stopLossTriggerPrice, rules.tickSize());
        BigDecimal stopLimit = roundToTick(stopLossLimitPrice, rules.tickSize());

        // P2-3 fix ("OCO legs not checked vs minNotional" -- external review, confirmed real by
        // direct inspection: neither OCO leg's own notional (roundedQty * its own limit price)
        // was ever checked against this symbol's minNotional before submission. Both legs of an
        // OCO are real LIMIT-family orders (LIMIT_MAKER take-profit, STOP_LOSS_LIMIT stop) --
        // Binance's own NOTIONAL/MIN_NOTIONAL filter always applies to LIMIT orders regardless of
        // applyMinToMarket (that flag only ever exempts MARKET orders). This is exactly the
        // audit's own named danger: this application only ever calls placeExitOco AFTER a real
        // entry fill, to protect an already-open position -- an OCO rejected by Binance at this
        // point doesn't just fail a pre-trade check, it leaves a real, already-filled position
        // with NO protective exit in place at all, forcing PositionMonitorService's own
        // unprotected-position recovery path (a forced flatten or a degraded, unverified state)
        // instead of the clean protection this call was supposed to install. Checking both legs'
        // own notional here, before ever reaching the network, converts that into an ordinary,
        // recoverable "OCO placement failed" result the existing retry/recovery machinery already
        // handles, instead of a live position silently losing its safety net.
        if (rules.minNotional().signum() > 0) {
            BigDecimal tpNotional = roundedQty.multiply(tp);
            if (tpNotional.compareTo(rules.minNotional()) < 0) {
                return OcoOrderResult.failure("OCO take-profit leg notional " + tpNotional + " is below " + symbol
                    + "'s minimum " + rules.minNotional() + " -- refusing to submit an OCO Binance would reject, "
                    + "leaving this position temporarily unprotected instead of silently attempting it.", null);
            }
            BigDecimal slNotional = roundedQty.multiply(stopLimit);
            if (slNotional.compareTo(rules.minNotional()) < 0) {
                return OcoOrderResult.failure("OCO stop-loss leg notional " + slNotional + " is below " + symbol
                    + "'s minimum " + rules.minNotional() + " -- refusing to submit an OCO Binance would reject, "
                    + "leaving this position temporarily unprotected instead of silently attempting it.", null);
            }
        }
        // P2-3 fix ("PERCENT_PRICE_BY_SIDE not enforced"): both OCO leg prices must fall within
        // [currentPrice * multiplierDown, currentPrice * multiplierUp] or Binance rejects the
        // whole OCO outright. Binance's own documented PERCENT_PRICE_BY_SIDE semantics (verified
        // against its filter docs, not assumed): multiplierDown is itself already the fractional
        // lower-bound multiplier (e.g. 0.95 for "no more than 5% below"), applied by
        // MULTIPLICATION exactly like multiplierUp -- an earlier version of this fix divided by
        // multiplierDown instead, which for any multiplierDown < 1 produces a lowerBound ABOVE
        // currentPrice (and often above upperBound too, inverting the band entirely and rejecting
        // essentially every valid OCO). Caught by this method's own new unit test
        // (placeExitOco_placesOco_whenWithinAllBounds asserting a genuinely valid, in-band OCO is
        // NOT rejected) before ever reaching a real order. Uses getCurrentPrice as the reference
        // price -- an honestly-disclosed approximation of Binance's own actual reference (a
        // weighted average price this adapter has no other established source for), same
        // reasoning as doPlaceOrder's own identical notional pre-check above: a pre-submit sanity
        // check, not a guarantee against Binance's own final rejection.
        if (rules.multiplierUp().signum() > 0 && rules.multiplierDown().signum() > 0) {
            try {
                BigDecimal currentPrice = getCurrentPrice(symbol, mode);
                if (currentPrice != null && currentPrice.signum() > 0) {
                    BigDecimal upperBound = currentPrice.multiply(rules.multiplierUp());
                    BigDecimal lowerBound = currentPrice.multiply(rules.multiplierDown());
                    if (tp.compareTo(upperBound) > 0 || tp.compareTo(lowerBound) < 0) {
                        return OcoOrderResult.failure("OCO take-profit price " + tp + " for " + symbol
                            + " falls outside the exchange's allowed band [" + lowerBound + ", " + upperBound
                            + "] around the current price " + currentPrice + " -- refusing to submit an OCO Binance would reject.", null);
                    }
                    if (stopLimit.compareTo(upperBound) > 0 || stopLimit.compareTo(lowerBound) < 0) {
                        return OcoOrderResult.failure("OCO stop-loss limit price " + stopLimit + " for " + symbol
                            + " falls outside the exchange's allowed band [" + lowerBound + ", " + upperBound
                            + "] around the current price " + currentPrice + " -- refusing to submit an OCO Binance would reject.", null);
                    }
                }
            } catch (Exception e) {
                log.debug("Could not fetch current price for {} to pre-validate PERCENT_PRICE_BY_SIDE (non-fatal, Binance's own final check remains authoritative): {}",
                    symbol, e.getMessage());
            }
        }

        // Review item #4 (fixed, verified against Binance's own changelog): POST /api/v3/order/oco
        // is deprecated and removed from the Testnet REST docs. This uses the replacement
        // POST /api/v3/orderList/oco, which has a different "above"/"below" parameter shape —
        // not just a renamed endpoint. For a SELL-side exit OCO (closing a long): per Binance's
        // own error messages ("A take profit order in a sell OCO must be above", "A stop loss
        // order in a sell OCO must be below"), the take-profit leg is "above" and the stop-loss
        // leg is "below".
        //
        // HONEST CAVEAT: this has been verified against Binance's current published API docs
        // (checked via live web search, not from training-data memory), but this sandbox has no
        // network path to binance.com/testnet.binance.vision, so this exact request has never
        // actually been fired at a real Binance server. Test this against Spot Testnet yourself
        // before trusting it with an order.
        String query = "symbol=" + enc(symbol.toUpperCase())
            + "&side=SELL"
            + "&quantity=" + enc(roundedQty.toPlainString())
            + "&aboveType=LIMIT_MAKER"
            + "&abovePrice=" + enc(tp.toPlainString())
            + "&belowType=STOP_LOSS_LIMIT"
            + "&belowPrice=" + enc(stopLimit.toPlainString())
            + "&belowStopPrice=" + enc(stopTrigger.toPlainString())
            + "&belowTimeInForce=GTC";
        // Review item #3 (this doc's numbering): listClientOrderId is a real, documented, optional
        // param on this endpoint — "a new order list with the same listClientOrderId is accepted
        // only when the previous one is filled or completely expired" (verified via live search).
        // Without this, a lost response after a successful OCO placement had no reliable way to
        // be told apart from a genuine failure.
        if (listClientOrderId != null && !listClientOrderId.isBlank()) {
            query += "&listClientOrderId=" + enc(listClientOrderId);
        }
        try {
            JsonNode resp = signedPost(apiKey, apiSecret, mode, "/api/v3/orderList/oco", query, false); // non-idempotent: places a real OCO, never auto-retried
            // Review finding ("Broker response can be treated as successful without mandatory
            // broker IDs" -- P0, full context in doPlaceOrder's own identical fix above): same
            return buildOcoResultOrFailure(resp, symbol, roundedQty);
        } catch (BinanceApiException e) {
            evictSymbolRulesCacheOnFilterFailure(symbol, mode, e);
            // Review item #4: verify before declaring failure, same pattern as entry-order
            // recovery (review item #9) — a lost response doesn't mean the OCO wasn't created.
            // Review finding ("OCO recovery still returns null for some verification failures"
            // -- external review, twenty-fourth pass, P1, full context in
            // tryRecoverOcoByListClientOrderId's own updated javadoc): that method never
            // returns null now -- always returns a genuine result, including the new
            // "uncertain" state -- so this simply returns it directly, no null check needed.
            if (listClientOrderId != null && !listClientOrderId.isBlank()) {
                return tryRecoverOcoByListClientOrderId(apiKey, apiSecret, mode, listClientOrderId);
            }
            return OcoOrderResult.failure(e.getMessage(), e.rawBody);
        }
    }

    /**
     * Review finding ("Broker response can be treated as successful without mandatory broker
     * IDs" -- P0, full context in BinanceBrokerAdapter.buildOrderResultOrUnknown's own identical
     * fix): same gap, same fix, same reason for extraction into its own directly-testable
     * method -- this used to unconditionally return success=true regardless of whether
     * Binance's own response actually included an orderListId. Position.ocoOrderListId is set
     * directly from this value; a null here means AutoTradeService would believe protection was
     * successfully installed while having no actual way to ever look up, cancel, or verify that
     * OCO again.
     */
    private OcoOrderResult buildOcoResultOrFailure(JsonNode resp, String symbol, BigDecimal roundedQty) {
        String ocoOrderListId = resp.path("orderListId").asText(null);
        if (ocoOrderListId == null) {
            log.error("Binance OCO placement response for {} is missing orderListId entirely -- cannot trust this as a successful placement. Raw response: {}",
                symbol, resp.toString());
            return OcoOrderResult.failure("Broker response was missing orderListId -- cannot confirm this OCO was actually placed.", resp.toString());
        }
        return new OcoOrderResult(true, ocoOrderListId, resp.toString(), null, roundedQty);
    }

    @Override
    public OrderStatusInfo getOrderStatus(String apiKey, String apiSecret, BrokerMode mode, String symbol, String brokerOrderId) {
        String query = "symbol=" + enc(symbol.toUpperCase()) + "&orderId=" + enc(brokerOrderId);
        JsonNode resp = signedGet(apiKey, apiSecret, mode, "/api/v3/order", query);
        return new OrderStatusInfo(
            resp.path("status").asText("UNKNOWN"),
            new BigDecimal(resp.path("executedQty").asText("0")),
            resp.path("cummulativeQuoteQty").asDouble(0) > 0 && resp.path("executedQty").asDouble(0) > 0
                ? new BigDecimal(resp.path("cummulativeQuoteQty").asText("0"))
                    .divide(new BigDecimal(resp.path("executedQty").asText("1")), 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO,
            resp.toString()
        );
    }

    /**
     * Review finding ("Stuck FLATTENING recovery uses balance as evidence that the flatten
     * succeeded" -- external review, third pass, full context in BrokerAdapter's own updated
     * interface javadoc): the actual Binance implementation -- origClientOrderId is a real,
     * documented parameter on the same GET /api/v3/order endpoint getOrderStatus already uses,
     * not a different or fabricated capability.
     */
    @Override
    public OrderStatusInfo getOrderStatusByClientOrderId(String apiKey, String apiSecret, BrokerMode mode, String symbol, String clientOrderId) {
        String query = "symbol=" + enc(symbol.toUpperCase()) + "&origClientOrderId=" + enc(clientOrderId);
        JsonNode resp = signedGet(apiKey, apiSecret, mode, "/api/v3/order", query);
        return new OrderStatusInfo(
            resp.path("status").asText("UNKNOWN"),
            new BigDecimal(resp.path("executedQty").asText("0")),
            resp.path("cummulativeQuoteQty").asDouble(0) > 0 && resp.path("executedQty").asDouble(0) > 0
                ? new BigDecimal(resp.path("cummulativeQuoteQty").asText("0"))
                    .divide(new BigDecimal(resp.path("executedQty").asText("1")), 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO,
            resp.toString()
        );
    }

    @Override
    public OcoStatusInfo getOcoStatus(String apiKey, String apiSecret, BrokerMode mode, String orderListId) {
        JsonNode resp = signedGet(apiKey, apiSecret, mode, "/api/v3/orderList", "orderListId=" + enc(orderListId));
        return parseOcoStatusResponse(apiKey, apiSecret, mode, resp, orderListId);
    }

    /**
     * Review finding ("OCO persistence still has an unavoidable crash window" -- external
     * review, nineteenth pass, P1, full context in this method's own interface javadoc and
     * ProtectionAttempt's own class javadoc): the actual implementation, using Binance's real,
     * documented origClientOrderId parameter on the same GET /api/v3/orderList endpoint
     * getOcoStatus above already uses -- confirmed against Binance's own API docs before writing
     * this. Shares the exact same response-parsing logic as getOcoStatus (extracted into
     * parseOcoStatusResponse below) since the response shape is identical regardless of which
     * query parameter located it.
     */
    @Override
    public OcoStatusInfo getOcoStatusByClientOrderId(String apiKey, String apiSecret, BrokerMode mode, String listClientOrderId) {
        JsonNode resp = signedGet(apiKey, apiSecret, mode, "/api/v3/orderList", "origClientOrderId=" + enc(listClientOrderId));
        return parseOcoStatusResponse(apiKey, apiSecret, mode, resp, listClientOrderId);
    }

    private OcoStatusInfo parseOcoStatusResponse(String apiKey, String apiSecret, BrokerMode mode, JsonNode resp, String queryedBy) {
        String symbol = resp.path("symbol").asText(null);
        List<OcoStatusInfo.Leg> legs = new ArrayList<>();
        for (JsonNode o : resp.path("orders")) {
            // Review item #5 (fixed): the orderList response only gives {symbol, orderId,
            // clientOrderId} per leg — status/price/executedQty are NOT actually present in it.
            // The previous version silently defaulted those to UNKNOWN/0, which is exactly the
            // "exitPrice = entry price, P&L = 0" danger the review flagged. Query each leg's real
            // status individually instead of trusting fields that were never really there.
            String legOrderId = o.path("orderId").asText(null);
            if (legOrderId == null || symbol == null) continue;
            try {
                JsonNode legResp = signedGet(apiKey, apiSecret, mode, "/api/v3/order", "symbol=" + enc(symbol) + "&orderId=" + enc(legOrderId));
                legs.add(parseOcoLeg(legOrderId, legResp));
            } catch (Exception e) {
                log.warn("Could not resolve OCO leg {} for orderList {}: {}", legOrderId, queryedBy, e.getMessage());
                legs.add(new OcoStatusInfo.Leg(legOrderId, "UNKNOWN", "UNKNOWN", "UNKNOWN", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
            }
        }
        return new OcoStatusInfo(resp.path("orderListId").asText(null), resp.path("listOrderStatus").asText("UNKNOWN"), legs, resp.toString());
    }


    /**
     * Review finding ("Missing broker/OMS test scenarios (missing-executedQty, OCO-lifecycle,
     * etc.)" -- P1): confirmed real -- getOcoStatus's own leg-parsing was previously inline
     * inside a method that also makes two real network calls (signedGet), which is exactly why
     * no OCO-lifecycle test existed here at all before this, unlike resolveExecutedQty's own
     * already-tested pure-parsing design. Extracted this piece specifically because it's the
     * actual per-leg parsing logic (avgPrice division, status/side/type extraction) — the same
     * kind of "given real Binance response JSON, does this produce the right internal
     * representation" question resolveExecutedQty already answers for fills, now answerable for
     * OCO legs too, via direct reflection-based invocation matching this test file's own
     * established pattern (see BinanceBrokerAdapterTest's own resolveExecutedQty tests).
     */
    private OcoStatusInfo.Leg parseOcoLeg(String legOrderId, JsonNode legResp) {
        BigDecimal executedQty = new BigDecimal(legResp.path("executedQty").asText("0"));
        BigDecimal cumQuote = new BigDecimal(legResp.path("cummulativeQuoteQty").asText("0"));
        BigDecimal avgPrice = executedQty.signum() > 0 ? cumQuote.divide(executedQty, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        // Review finding ("Recovery auto-attach needs quantity verification" -- external review,
        // thirty-eighth pass, P1, full context in OcoStatusInfo.Leg.origQty's own updated field
        // javadoc): origQty is genuinely present in this exact response (GET /api/v3/order) --
        // simply never extracted before this fix.
        BigDecimal origQty = new BigDecimal(legResp.path("origQty").asText("0"));
        return new OcoStatusInfo.Leg(
            legOrderId,
            legResp.path("side").asText("UNKNOWN"),
            legResp.path("type").asText("UNKNOWN"),
            legResp.path("status").asText("UNKNOWN"),
            avgPrice,
            executedQty,
            origQty
        );
    }

    @Override
    public SymbolRules getSymbolRules(String symbol, BrokerMode mode) {
        // Review item #16 (fixed): cache key now includes mode — testnet and live are fetched
        // and cached separately, never conflated.
        String key = symbol.toUpperCase() + ":" + mode;
        AtomicReference<CachedRules> ref = rulesCache.computeIfAbsent(key, k -> new AtomicReference<>());
        CachedRules cached = ref.get();
        if (cached != null && cached.fetchedAt().isAfter(Instant.now().minusSeconds(3600))) {
            return cached.rules();
        }
        String path = "/api/v3/exchangeInfo?symbol=" + symbol.toUpperCase();
        try {
            // Audit finding (P1-3, full context in publicGet's own javadoc).
            JsonNode root = publicGet(mode, path);
            JsonNode symbolNode = root.path("symbols").get(0);
            String baseAsset = symbolNode.path("baseAsset").asText(null);
            String quoteAsset = symbolNode.path("quoteAsset").asText(null);
            BigDecimal tickSize = BigDecimal.ONE, stepSize = BigDecimal.ONE, minQty = BigDecimal.ZERO, minNotional = BigDecimal.ZERO;
            // P2-3 fix ("PERCENT_PRICE_BY_SIDE, MAX_NUM_ALGO_ORDERS, NOTIONAL maxNotional/
            // applyMinToMarket not enforced; OCO legs not checked vs minNotional" -- external
            // review, confirmed real by direct inspection: this method used to read ONLY
            // minNotional from the MIN_NOTIONAL/NOTIONAL filter, discarding maxNotional and the
            // applyMinToMarket/applyMaxToMarket flags entirely, and never read PERCENT_PRICE_BY_
            // SIDE at all -- so nothing downstream could enforce either, even though both are
            // real Binance-side rejection reasons this application would otherwise only discover
            // AFTER attempting to submit (see this method's own updated javadoc below for exactly
            // how dangerous that is for an OCO leg specifically). Parsed here now; see
            // doPlaceOrder/placeExitOco's own updated comments for where these are actually
            // enforced pre-submit.
            BigDecimal maxNotional = BigDecimal.ZERO;
            boolean applyMinNotionalToMarket = false, applyMaxNotionalToMarket = false;
            BigDecimal multiplierUp = BigDecimal.ZERO, multiplierDown = BigDecimal.ZERO;
            for (JsonNode f : symbolNode.path("filters")) {
                switch (f.path("filterType").asText()) {
                    case "PRICE_FILTER" -> tickSize = new BigDecimal(f.path("tickSize").asText("1"));
                    case "LOT_SIZE" -> {
                        stepSize = new BigDecimal(f.path("stepSize").asText("1"));
                        minQty = new BigDecimal(f.path("minQty").asText("0"));
                    }
                    // Review item #15 (partial): also honor MARKET_LOT_SIZE, which is the filter
                    // that actually applies to MARKET orders specifically — LOT_SIZE alone can be
                    // looser than what a market order is really allowed. If present, it tightens
                    // (never loosens) the step/min-qty already read from LOT_SIZE.
                    case "MARKET_LOT_SIZE" -> {
                        BigDecimal marketStep = new BigDecimal(f.path("stepSize").asText("0"));
                        BigDecimal marketMinQty = new BigDecimal(f.path("minQty").asText("0"));
                        if (marketStep.signum() > 0 && marketStep.compareTo(stepSize) > 0) stepSize = marketStep;
                        if (marketMinQty.compareTo(minQty) > 0) minQty = marketMinQty;
                    }
                    case "MIN_NOTIONAL", "NOTIONAL" -> {
                        minNotional = new BigDecimal(f.path("minNotional").asText("0"));
                        // Binance's own two filter shapes: the legacy MIN_NOTIONAL has no
                        // maxNotional/applyMaxToMarket at all (only applyToMarket, for the min);
                        // the current NOTIONAL filter has both applyMinToMarket/applyMaxToMarket
                        // separately. asText defaults keep this correct either way.
                        if (f.hasNonNull("maxNotional")) maxNotional = new BigDecimal(f.path("maxNotional").asText("0"));
                        boolean applyToMarket = f.path("applyToMarket").asBoolean(false);
                        applyMinNotionalToMarket = f.path("applyMinToMarket").asBoolean(applyToMarket);
                        applyMaxNotionalToMarket = f.path("applyMaxToMarket").asBoolean(false);
                    }
                    case "PERCENT_PRICE_BY_SIDE", "PERCENT_PRICE" -> {
                        multiplierUp = new BigDecimal(f.path("multiplierUp").asText("0"));
                        multiplierDown = new BigDecimal(f.path("multiplierDown").asText("0"));
                    }
                    default -> {
                        // P2-3 fix: MAX_NUM_ALGO_ORDERS deliberately still NOT enforced client-side
                        // here, disclosed rather than silently ignored -- see doPlaceOrder/
                        // placeExitOco's own updated javadoc for why this application's own
                        // execution model (position-slot reservations already cap this application
                        // to at most one open entry order and one open OCO per position, enforced
                        // well before this adapter is ever reached) makes hitting Binance's own
                        // account-wide algo-order cap structurally unlikely, and for what a real
                        // fix would need (querying open-order-count pre-submit) that this pass
                        // didn't build.
                    }
                }
            }
            SymbolRules rules = new SymbolRules(symbol.toUpperCase(), baseAsset, quoteAsset, tickSize, stepSize, minQty, minNotional,
                decimalPlaces(tickSize), decimalPlaces(stepSize), maxNotional, applyMinNotionalToMarket, applyMaxNotionalToMarket,
                multiplierUp, multiplierDown);
            ref.set(new CachedRules(rules, Instant.now()));
            return rules;
        } catch (Exception e) {
            // P2-16 fix ("GlobalExceptionHandler.handleBadState returns IllegalStateException
            // messages to clients verbatim" -- external review, full context in
            // GlobalExceptionHandler's own updated javadoc): confirmed real here specifically --
            // e.getMessage() on this catch can be a raw upstream HTTP/JSON error body straight
            // from Binance (or an internal client-library detail), which handleBadState's own
            // contract (every IllegalStateException message is safe, hand-authored, user-facing
            // text) never anticipated. Logged in full server-side; the client now gets a clean,
            // generic message with no embedded upstream/internal detail. Every other
            // "Failed to fetch ..." catch in this class below has the identical fix, for the
            // identical reason -- not re-explained at each site.
            log.error("Failed to load exchange rules for {} ({}): {}", symbol, mode, e.getMessage(), e);
            throw new IllegalStateException("Failed to load exchange rules for " + symbol + ". Please try again shortly.");
        }
    }

    /**
     * P2-3 fix ("refresh cache on -1013" -- external review, full context in getSymbolRules' own
     * updated comment above): this symbol-rules cache is otherwise trusted for a full hour (see
     * getSymbolRules' own TTL check) -- if Binance itself just rejected a real order/OCO with
     * error code -1013 ("Filter failure"), that's a direct signal this application's own cached
     * filters are wrong or stale (the exchange changed them, or this cache was ever mistaken in
     * the first place) RIGHT NOW, not up to an hour from now. Evicting here means the very next
     * attempt (whatever retry/recovery path the caller takes) re-fetches fresh filters instead of
     * repeating the same now-known-wrong validation against the same stale cache. Deliberately
     * does NOT retry the failed order/OCO itself here -- that placement was already sent
     * (non-idempotent, per this class's own established pattern for real order/OCO submission)
     * and its own caller already has dedicated recovery logic for exactly that.
     */
    private void evictSymbolRulesCacheOnFilterFailure(String symbol, BrokerMode mode, BinanceApiException e) {
        Integer code = extractBinanceErrorCode(e.rawBody);
        if (code != null && code == -1013) {
            String key = symbol.toUpperCase() + ":" + mode;
            boolean evicted = rulesCache.remove(key) != null;
            log.warn("Binance rejected an order for {} ({}) with error code -1013 (filter failure) -- {} symbol-rules cache entry for this "
                + "symbol/mode so the next attempt re-fetches current filters instead of repeating the same stale validation.",
                symbol, mode, evicted ? "evicted the" : "no cached entry found to evict (already fresh, or never cached) for the");
        }
    }

    /** Extracts Binance's own {"code": -1013, "msg": "..."} error code from a raw error response body, or null if absent/unparseable. */
    private Integer extractBinanceErrorCode(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return null;
        try {
            JsonNode node = mapper.readTree(rawBody);
            return node.hasNonNull("code") ? node.path("code").asInt() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public BigDecimal getCurrentPrice(String symbol, BrokerMode mode) {
        // Review finding (this doc, "BLOCKER #1"): these three public market-data endpoints were
        // hardcoded to LIVE_BASE regardless of mode — a TESTNET credential's orders were being
        // risk-checked and price-validated against LIVE market data, not the testnet environment
        // it was actually about to trade against. Testnet and live are genuinely different
        // markets (different liquidity, different prices) — this now routes to the correct host.
        String path = "/api/v3/ticker/price?symbol=" + symbol.toUpperCase();
        try {
            // Audit finding (P1-3, full context in publicGet's own javadoc): routed through the
            // same circuit-aware path every signed call already used -- this used to call
            // http.exchange(...) directly here, bypassing the shared 418/429 circuit entirely.
            JsonNode root = publicGet(mode, path);
            return new BigDecimal(root.path("price").asText("0"));
        } catch (Exception e) {
            log.error("Failed to fetch current price for {} ({}): {}", symbol, mode, e.getMessage(), e);
            throw new IllegalStateException("Failed to fetch current price for " + symbol + ". Please try again shortly.");
        }
    }

    /**
     * Review finding, same context as serverTimeOffsetMsByBase's own field javadoc: this
     * interface method (BrokerAdapter.getClockDriftMs()) takes no mode parameter at all --
     * changing that would ripple to every implementor (including PaperBrokerAdapter) and this
     * method's own callers, for a purely diagnostic drift-monitoring value
     * (MarketDataQualityService), not anything used for actual request-signing correctness
     * (syncServerTime above, which IS correctly per-base now). Reports the LIVE base's own
     * offset specifically -- the more safety-relevant one to monitor for drift -- rather than
     * whichever base happened to sync most recently, which is exactly the ambiguity this whole
     * fix exists to remove elsewhere. Falls back to 0 if LIVE has never actually been synced yet
     * (e.g. a credential that has only ever used TESTNET).
     */
    @Override
    public long getClockDriftMs() {
        var liveOffset = serverTimeOffsetMsByBase.get(LIVE_BASE);
        return liveOffset != null ? liveOffset.get() : 0L;
    }

    @Override
    public OrderBookDepth getOrderBookDepth(String symbol, BrokerMode mode, int limit) {
        String path = "/api/v3/depth?symbol=" + symbol.toUpperCase() + "&limit=" + limit;
        try {
            // Audit finding (P1-3, full context in publicGet's own javadoc).
            JsonNode root = publicGet(mode, path);
            List<OrderBookDepth.PriceLevel> bids = new ArrayList<>();
            for (JsonNode b : root.path("bids")) {
                bids.add(new OrderBookDepth.PriceLevel(new BigDecimal(b.get(0).asText("0")), new BigDecimal(b.get(1).asText("0"))));
            }
            List<OrderBookDepth.PriceLevel> asks = new ArrayList<>();
            for (JsonNode a : root.path("asks")) {
                asks.add(new OrderBookDepth.PriceLevel(new BigDecimal(a.get(0).asText("0")), new BigDecimal(a.get(1).asText("0"))));
            }
            return new OrderBookDepth(bids, asks);
        } catch (Exception e) {
            log.error("Failed to fetch order book depth for {} ({}): {}", symbol, mode, e.getMessage(), e);
            throw new IllegalStateException("Failed to fetch order book depth for " + symbol + ". Please try again shortly.");
        }
    }

    @Override
    public SpreadInfo getSpread(String symbol, BrokerMode mode) {
        String path = "/api/v3/ticker/bookTicker?symbol=" + symbol.toUpperCase();
        try {
            // Audit finding (P1-3, full context in publicGet's own javadoc).
            JsonNode root = publicGet(mode, path);
            BigDecimal bid = new BigDecimal(root.path("bidPrice").asText("0"));
            BigDecimal ask = new BigDecimal(root.path("askPrice").asText("0"));
            if (bid.signum() <= 0 || ask.signum() <= 0) return new SpreadInfo(bid, ask, 100.0);
            double mid = bid.add(ask).doubleValue() / 2.0;
            double spreadPct = ask.subtract(bid).doubleValue() / mid * 100.0;
            return new SpreadInfo(bid, ask, spreadPct);
        } catch (Exception e) {
            log.error("Failed to fetch spread for {} ({}): {}", symbol, mode, e.getMessage(), e);
            throw new IllegalStateException("Failed to fetch spread for " + symbol + ". Please try again shortly.");
        }
    }

    @Override
    public List<Candle> getRecentCandles(String symbol, String interval, int limit, BrokerMode mode) {
        String path = "/api/v3/klines?symbol=" + symbol.toUpperCase() + "&interval=" + interval + "&limit=" + limit;
        try {
            // Audit finding (P1-3, full context in publicGet's own javadoc).
            JsonNode root = publicGet(mode, path);
            List<Candle> candles = new ArrayList<>();
            for (JsonNode k : root) {
                candles.add(new Candle(
                    k.get(0).asLong(),
                    k.get(1).asDouble(), k.get(2).asDouble(), k.get(3).asDouble(), k.get(4).asDouble(),
                    k.get(5).asDouble()
                ));
            }
            return candles;
        } catch (Exception e) {
            log.error("Failed to fetch candles for {} ({}): {}", symbol, mode, e.getMessage(), e);
            throw new IllegalStateException("Failed to fetch candles for " + symbol + ". Please try again shortly.");
        }
    }

    // ── internals ─────────────────────────────────────────────────────────

    private BigDecimal roundDownToStep(BigDecimal value, BigDecimal step) {
        if (step == null || step.signum() <= 0) return value;
        return value.divide(step, 0, RoundingMode.DOWN).multiply(step).stripTrailingZeros();
    }

    private BigDecimal roundToTick(BigDecimal value, BigDecimal tick) {
        if (tick == null || tick.signum() <= 0) return value;
        return value.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick).stripTrailingZeros();
    }

    private int decimalPlaces(BigDecimal value) {
        String plain = value.stripTrailingZeros().toPlainString();
        int dot = plain.indexOf('.');
        return dot < 0 ? 0 : plain.length() - dot - 1;
    }

    private BigDecimal extractAverageFillPrice(JsonNode orderResponse) {
        JsonNode fills = orderResponse.path("fills");
        if (!fills.isArray() || fills.isEmpty()) return BigDecimal.ZERO;
        BigDecimal totalQty = BigDecimal.ZERO, totalCost = BigDecimal.ZERO;
        for (JsonNode f : fills) {
            BigDecimal qty = new BigDecimal(f.path("qty").asText("0"));
            BigDecimal price = new BigDecimal(f.path("price").asText("0"));
            totalQty = totalQty.add(qty);
            totalCost = totalCost.add(qty.multiply(price));
        }
        return totalQty.signum() == 0 ? BigDecimal.ZERO : totalCost.divide(totalQty, 8, RoundingMode.HALF_UP);
    }

    /** Review item #12: real per-fill commission, straight from the order placement response.
     *  Review finding ("#6 — Fill Ledger"): tradeId extracted defensively (see Fill's own
     *  javadoc for why) — present or absent, never assumed. executedAt uses the order's own
     *  transactTime as the best available approximation; the embedded fills array itself
     *  carries no per-fill timestamp in any source checked. */
    private List<Fill> extractFills(JsonNode orderResponse) {
        List<Fill> result = new ArrayList<>();
        java.time.LocalDateTime orderTransactTime = orderResponse.path("transactTime").isMissingNode() ? null
            : java.time.Instant.ofEpochMilli(orderResponse.path("transactTime").asLong())
                .atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
        for (JsonNode f : orderResponse.path("fills")) {
            result.add(new Fill(
                new BigDecimal(f.path("price").asText("0")),
                new BigDecimal(f.path("qty").asText("0")),
                new BigDecimal(f.path("commission").asText("0")),
                f.path("commissionAsset").asText(null),
                f.path("tradeId").isMissingNode() ? null : f.path("tradeId").asText(),
                orderTransactTime
            ));
        }
        return result;
    }

    @Override
    public OcoOrderResult cancelOco(String apiKey, String apiSecret, BrokerMode mode, String symbol, String orderListId) {
        rejectIfPaper(mode, "cancelOco");
        String query = "symbol=" + enc(symbol.toUpperCase()) + "&orderListId=" + enc(orderListId);
        try {
            JsonNode resp = signedDelete(apiKey, apiSecret, mode, "/api/v3/orderList", query);
            return new OcoOrderResult(true, resp.path("orderListId").asText(null), resp.toString(), null);
        } catch (BinanceApiException e) {
            return OcoOrderResult.failure(e.getMessage(), e.rawBody);
        }
    }

    @Override
    public List<Fill> getFillsForOrder(String apiKey, String apiSecret, BrokerMode mode, String symbol, String orderId) {
        try {
            JsonNode resp = signedGet(apiKey, apiSecret, mode, "/api/v3/myTrades", "symbol=" + enc(symbol.toUpperCase()) + "&orderId=" + enc(orderId));
            List<Fill> result = new ArrayList<>();
            for (JsonNode t : resp) {
                // Review finding ("#6 — Fill Ledger"): id and time verified against current
                // Binance docs (multiple independent sources agree) — myTrades reliably returns
                // both, unlike the order-response embedded fills array's tradeId ambiguity.
                java.time.LocalDateTime executedAt = t.path("time").isMissingNode() ? null
                    : java.time.Instant.ofEpochMilli(t.path("time").asLong()).atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
                result.add(new Fill(
                    new BigDecimal(t.path("price").asText("0")),
                    new BigDecimal(t.path("qty").asText("0")),
                    new BigDecimal(t.path("commission").asText("0")),
                    t.path("commissionAsset").asText(null),
                    t.path("id").isMissingNode() ? null : t.path("id").asText(),
                    executedAt
                ));
            }
            return result;
        } catch (Exception e) {
            log.warn("Could not fetch fills for order {} on {}: {}", orderId, symbol, e.getMessage());
            return List.of();
        }
    }

    /**
     * Review finding ("Strategy universe is still hard-coded" -- external review, fifth pass,
     * P1 feature request, full context in BrokerAdapter's own updated interface javadoc): a
     * public, unsigned market-data call (no apiKey/apiSecret needed, same as getCurrentPrice and
     * getRecentCandles above) against the same /api/v3/exchangeInfo endpoint getSymbolRules
     * already uses for a single symbol -- called WITHOUT the ?symbol= parameter, which returns
     * every symbol on the exchange in one response.
     */
    @Override
    public List<String> getAllTradableUsdtSymbols(BrokerMode mode) {
        String path = "/api/v3/exchangeInfo";
        try {
            // Audit finding (P1-3, full context in publicGet's own javadoc).
            JsonNode root = publicGet(mode, path);
            List<String> symbols = new ArrayList<>();
            for (JsonNode s : root.path("symbols")) {
                if ("TRADING".equals(s.path("status").asText())
                        && "USDT".equals(s.path("quoteAsset").asText())
                        && s.path("isSpotTradingAllowed").asBoolean(false)) {
                    symbols.add(s.path("symbol").asText());
                }
            }
            return symbols;
        } catch (Exception e) {
            log.error("Failed to fetch tradable USDT symbols ({}): {}", mode, e.getMessage(), e);
            throw new IllegalStateException("Failed to fetch tradable USDT symbols. Please try again shortly.");
        }
    }

    /**
     * Review finding, same context: /api/v3/ticker/24hr called WITHOUT a symbol -- returns every
     * symbol's 24hr stats in one response (a heavier call than the single-symbol form -- weight
     * 40 vs 1, per Binance's own documented rate-limit weighting -- but still one call rather
     * than hundreds).
     */
    @Override
    public List<com.tradevision.service.broker.dto.TickerStats> getAll24hrTickers(BrokerMode mode) {
        String path = "/api/v3/ticker/24hr";
        try {
            // Audit finding (P1-3, full context in publicGet's own javadoc).
            JsonNode root = publicGet(mode, path);
            List<com.tradevision.service.broker.dto.TickerStats> tickers = new ArrayList<>();
            for (JsonNode t : root) {
                tickers.add(new com.tradevision.service.broker.dto.TickerStats(
                    t.path("symbol").asText(),
                    new BigDecimal(t.path("quoteVolume").asText("0")),
                    new BigDecimal(t.path("priceChangePercent").asText("0")),
                    new BigDecimal(t.path("bidPrice").asText("0")),
                    new BigDecimal(t.path("askPrice").asText("0"))
                ));
            }
            return tickers;
        } catch (Exception e) {
            log.error("Failed to fetch 24hr tickers ({}): {}", mode, e.getMessage(), e);
            throw new IllegalStateException("Failed to fetch 24hr tickers. Please try again shortly.");
        }
    }

    /**
     * Review finding ("There is still a real commission/P&L limitation" -- external review,
     * tenth pass, P1, full context in BrokerAdapter's own updated interface javadoc): a public,
     * unsigned market-data call (no apiKey/apiSecret needed, same pattern as
     * getAllTradableUsdtSymbols/getAll24hrTickers above) against /api/v3/klines with an explicit
     * startTime/endTime window at the fill's own timestamp -- confirmed via Binance's own public
     * API documentation that these parameters are real and supported before writing this method,
     * not assumed. A 1-minute interval is used regardless of the position's own trading
     * timeframe, since this is asking "what was this asset actually worth at this exact moment,"
     * not analyzing a trend.
     */
    @Override
    public java.math.BigDecimal getHistoricalPrice(String symbol, long timestampMillis, BrokerMode mode) {
        String path = "/api/v3/klines?symbol=" + symbol.toUpperCase() + "&interval=1m&startTime=" + timestampMillis
            + "&endTime=" + (timestampMillis + 60_000L) + "&limit=1";
        try {
            // Audit finding (P1-3, full context in publicGet's own javadoc).
            JsonNode root = publicGet(mode, path);
            if (!root.isArray() || root.isEmpty()) {
                // No candle exists for this exact window (e.g. a timestamp Binance has no
                // historical data for at all) -- genuinely unknown, never a fabricated guess.
                return null;
            }
            // Kline close price is index 4, same field position as getRecentCandles above.
            return new java.math.BigDecimal(root.get(0).get(4).asText());
        } catch (Exception e) {
            log.warn("Could not fetch historical price for {} at {} ({}): {} -- treating as genuinely unknown, not a fabricated value.",
                symbol, timestampMillis, mode, e.getMessage());
            return null;
        }
    }

    /**
     * Review finding ("User-data WebSocket is STILL NOT implemented" -- external review,
     * eleventh pass, P1, full context in BrokerAdapter's own updated interface javadoc): the
     * listen-key lifecycle only -- verified via Binance's own public API documentation before
     * writing this (POST creates/refreshes a key valid 60 minutes, PUT extends it another 60,
     * DELETE invalidates it; recommended keepalive cadence is every ~30 minutes, well inside
     * that window). Reuses this class's own existing signed-request helper (call()) rather than
     * building a separate unsigned code path -- Binance's own documented behavior for
     * USER_STREAM-type endpoints is that a signature is not required but is harmless if sent,
     * so the existing, already-exercised call() path is the lower-risk choice over adding new,
     * unverified request-signing logic just for these three endpoints.
     */
    @Override
    public String createListenKey(String apiKey, String apiSecret, BrokerMode mode) {
        JsonNode result = call(apiKey, apiSecret, mode, HttpMethod.POST, "/api/v3/userDataStream", "", true); // listen-key creation, not an order -- safe to retry
        String listenKey = result.path("listenKey").asText(null);
        if (listenKey == null) {
            // P2-16 fix (full context in getSymbolRules' own updated comment above): `result` is
            // Binance's own raw response body -- embedding it verbatim in a client-facing
            // IllegalStateException message would leak whatever Binance actually sent back.
            log.error("Binance did not return a listenKey for credential ({}): {}", mode, result);
            throw new IllegalStateException("Binance did not return a listen key for this credential. Please try again shortly.");
        }
        return listenKey;
    }

    @Override
    public void keepAliveListenKey(String apiKey, String apiSecret, String listenKey, BrokerMode mode) {
        call(apiKey, apiSecret, mode, HttpMethod.PUT, "/api/v3/userDataStream", "listenKey=" + listenKey, true);
    }

    @Override
    public void closeListenKey(String apiKey, String apiSecret, String listenKey, BrokerMode mode) {
        call(apiKey, apiSecret, mode, HttpMethod.DELETE, "/api/v3/userDataStream", "listenKey=" + listenKey, true);
    }

    private JsonNode signedGet(String apiKey, String apiSecret, BrokerMode mode, String path, String extraParams) {
        return call(apiKey, apiSecret, mode, HttpMethod.GET, path, extraParams, true);
    }

    // Real bug (P0-1, confirmed by direct inspection): the old signedPost had no way to tell
    // call() that /api/v3/order and /api/v3/orderList/oco are NOT safe to blindly retry.
    // Binance's newClientOrderId/listClientOrderId dedup only rejects a retry while the ORIGINAL
    // order/list is still open -- once it has filled, it is no longer open, and an identical
    // resend after a read-timeout is accepted as a genuine SECOND order/OCO. An 8s read timeout
    // (buildRestTemplate) during real exchange latency could therefore silently 2-4x a position.
    // This overload lets a caller mark a POST as non-idempotent so call() makes exactly ONE
    // attempt and never resends it -- the caller's own verify-by-clientOrderId recovery (already
    // in place for both placeOrder and placeExitOco) is what handles an ambiguous outcome, and it
    // runs BEFORE any second network request is ever made, not after.
    private JsonNode signedPost(String apiKey, String apiSecret, BrokerMode mode, String path, String extraParams, boolean idempotent) {
        return call(apiKey, apiSecret, mode, HttpMethod.POST, path, extraParams, idempotent);
    }

    private JsonNode signedDelete(String apiKey, String apiSecret, BrokerMode mode, String path, String extraParams) {
        // Cancels are safe to retry: worst case a retried cancel finds the order already gone
        // and Binance returns an "unknown order" error, which every caller here already treats
        // as a normal (if uncertain) outcome -- never a duplicate money-moving side effect the
        // way a resent order-placement POST would be.
        return call(apiKey, apiSecret, mode, HttpMethod.DELETE, path, extraParams, true);
    }

    private JsonNode call(String apiKey, String apiSecret, BrokerMode mode, HttpMethod method, String path, String extraParams, boolean idempotent) {
        return withCircuitBreakerAndRetry(apiKey, path, idempotent,
            () -> executeCall(apiKey, apiSecret, mode, method, path, extraParams));
    }

    /**
     * Audit finding (P1-3 -- "public Binance calls bypass the circuit-aware path" -- full
     * context in publicGet's own javadoc): the shared circuit-check-then-retry loop, extracted
     * out of what used to be this class's ONLY entry point for an actual Binance HTTP call
     * (signed requests, via call()/executeCall() above). Every public, unsigned market-data
     * method below (getCurrentPrice, getSymbolRules, getOrderBookDepth, getSpread,
     * getRecentCandles, getAllTradableUsdtSymbols, getAll24hrTickers, getHistoricalPrice) used
     * to call http.exchange(...) directly, completely bypassing BOTH the bannedUntil/
     * rateLimitedUntil circuit checks below AND the exponential-backoff retry on a transient 5xx
     * -- confirmed real by direct inspection. A public call made while the shared circuit was
     * already open (because a SIGNED call from this same process had just been 418/429'd) would
     * still fire straight at Binance, undermining the entire point of a process-wide circuit:
     * one banned/rate-limited IP, continuing to get hammered by "just" market-data requests,
     * extending its own ban. Conversely, a 418/429 received on a PUBLIC call never opened the
     * circuit at all, so every signed call right after it had no idea the IP was already in
     * trouble. Both directions are closed by routing every Binance call -- signed or public --
     * through this one shared loop.
     */
    private JsonNode withCircuitBreakerAndRetry(String healthKey, String path, boolean idempotent, java.util.function.Supplier<JsonNode> action) {
        int attempt = 0;
        while (true) {
            // Review finding (P1 #8, full context in the bannedUntil/rateLimitedUntil fields'
            // own javadoc): fail fast, before making any real network call at all, while either
            // circuit is still open -- this is what actually stops "all REST for the ban
            // duration" the review asks for, not just this one caller's own retry loop.
            Instant now = Instant.now();
            Instant banUntil = bannedUntil.get();
            if (now.isBefore(banUntil)) {
                throw new BinanceApiException("Binance IP ban (418) still in effect for "
                    + java.time.Duration.between(now, banUntil).getSeconds() + " more second(s) -- refusing to make any further "
                    + "REST call to Binance until it lifts. (path=" + path + ")", null, 418);
            }
            Instant rateLimitUntil = rateLimitedUntil.get();
            if (now.isBefore(rateLimitUntil)) {
                throw new BinanceApiException("Binance rate limit (429) still in effect for "
                    + java.time.Duration.between(now, rateLimitUntil).getSeconds() + " more second(s) -- refusing to make any "
                    + "further REST call to Binance until it lifts. (path=" + path + ")", null, 429);
            }

            long startedAt = System.currentTimeMillis();
            try {
                JsonNode result = action.get();
                safeRecordHealth(healthKey, true, System.currentTimeMillis() - startedAt);
                return result;
            } catch (BinanceApiException e) {
                safeRecordHealth(healthKey, false, System.currentTimeMillis() - startedAt);
                attempt++;

                if (e.statusCode == 418) {
                    // Review finding (P1 #8): "on 418 stop all REST for the ban duration and
                    // alert" -- confirmed this used to instead retry into an active ban with a
                    // short exponential backoff (a few seconds), which extends a real IP ban
                    // rather than respecting it. Never retried within this call, regardless of
                    // idempotency or remaining attempts -- the circuit above is what protects
                    // every OTHER call (including any retry a caller might attempt) for the rest
                    // of the ban.
                    long banSeconds = e.retryAfterSeconds != null ? e.retryAfterSeconds : DEFAULT_BAN_FALLBACK_SECONDS;
                    Instant newBanUntil = Instant.now().plusSeconds(banSeconds);
                    bannedUntil.set(newBanUntil);
                    log.error("ALERT: Binance returned 418 (IP auto-banned) for {} -- halting ALL REST calls to Binance from this "
                        + "instance for {} second(s), until {}. This is a critical exchange-connectivity failure requiring operator "
                        + "attention (a banned IP means no order placement, no cancellation, no reconciliation for every credential, "
                        + "until it lifts): {}", path, banSeconds, newBanUntil, e.getMessage());
                    throw e;
                }
                if (e.statusCode == 429) {
                    // Review finding (P1 #8): "on 429 back off per Retry-After and open a global
                    // circuit" -- the actual fix. Still retried for an idempotent call within
                    // MAX_RETRIES (matching this method's existing retry budget for every other
                    // retryable status), but the wait is now Binance's own stated Retry-After
                    // duration, not a generic exponential guess -- and every OTHER concurrent
                    // call is also held back by the same circuit set here, not just this one.
                    long waitSeconds = e.retryAfterSeconds != null ? e.retryAfterSeconds : DEFAULT_RATE_LIMIT_FALLBACK_SECONDS;
                    Instant newRateLimitUntil = Instant.now().plusSeconds(waitSeconds);
                    rateLimitedUntil.set(newRateLimitUntil);
                    log.warn("Binance returned 429 (rate limited) for {} -- opening the shared circuit for {} second(s) (per "
                        + "Retry-After), until {}: {}", path, waitSeconds, newRateLimitUntil, e.getMessage());
                    if (!idempotent || attempt > MAX_RETRIES) throw e;
                    try {
                        Thread.sleep(waitSeconds * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue; // retry now that the honored Retry-After wait has elapsed
                }

                boolean retryable = idempotent && e.statusCode >= 500;
                if (!retryable || attempt > MAX_RETRIES) {
                    if (!idempotent && attempt == 1) {
                        log.warn("Binance call to {} failed (status {}) on its one and only allowed attempt -- this call is "
                            + "non-idempotent (places a real order), so it is never automatically resent. The caller's own "
                            + "verify-by-clientOrderId recovery decides what actually happened: {}", path, e.statusCode, e.getMessage());
                    }
                    throw e;
                }
                long backoffMs = (long) (Math.pow(2, attempt) * 500) + ThreadLocalRandom.current().nextLong(0, 250);
                log.warn("Binance call to {} failed (status {}), retry {}/{} after {}ms: {}",
                    path, e.statusCode, attempt, MAX_RETRIES, backoffMs, e.getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * P0-2 fix (see class-level note near ExchangeHealthService usage): health/metrics recording
     * must never be able to turn an exchange call that already succeeded into a thrown exception.
     * Wrapped here, at the single shared call site both the success and failure paths of call()
     * go through, rather than inside ExchangeHealthService itself, so this failure mode is closed
     * for every caller of this adapter at once.
     */
    private void safeRecordHealth(String apiKey, boolean success, long latencyMs) {
        try {
            exchangeHealth.record(apiKey, success, latencyMs);
        } catch (Exception e) {
            log.warn("Could not record exchange health (non-fatal, purely observational -- must never affect the outcome of a real "
                + "exchange call): {}", e.getMessage());
        }
    }

    /**
     * Review finding ("Broker health still incomplete" — "You don't track: REQUEST_WEIGHT..."):
     * X-MBX-USED-WEIGHT-1M verified against Binance's own official docs repository (see
     * ExchangeHealthService's own comment on this same header for the full citation and the
     * per-IP-not-per-key clarification this method's behavior already reflects — it doesn't take
     * an apiKey parameter at all, on purpose).
     */
    private void recordUsedWeightFromHeaders(HttpHeaders headers) {
        if (headers == null) return;
        String weight = headers.getFirst("X-MBX-USED-WEIGHT-1M");
        if (weight == null) return;
        try {
            exchangeHealth.recordUsedWeight(Integer.parseInt(weight));
        } catch (NumberFormatException ignored) {
            // Malformed header value — never worth failing the actual API call over.
        }
    }

    private JsonNode executeCall(String apiKey, String apiSecret, BrokerMode mode, HttpMethod method, String path, String extraParams) {
        String base = mode == BrokerMode.LIVE ? LIVE_BASE : TESTNET_BASE;
        long timestamp = System.currentTimeMillis() + syncServerTime(base);
        String params = (extraParams.isEmpty() ? "" : extraParams + "&") + "timestamp=" + timestamp + "&recvWindow=5000";
        String signature = hmacSha256(apiSecret, params);
        String url = base + path + "?" + params + "&signature=" + signature;

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-MBX-APIKEY", apiKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        return executeHttpRequest(method, url, entity);
    }

    /**
     * Audit finding (P1-3, full context in publicGet's own javadoc): the public-endpoint
     * counterpart to executeCall above -- same host selection, same response handling, just no
     * signature/API-key header, since Binance's own public market-data endpoints need neither.
     * `path` already carries its own leading "?query=string" (each public method below builds
     * it exactly as before this fix); nothing else about those query strings changes.
     */
    private JsonNode executePublicCall(BrokerMode mode, String path) {
        String base = mode == BrokerMode.LIVE ? LIVE_BASE : TESTNET_BASE;
        return executeHttpRequest(HttpMethod.GET, base + path, HttpEntity.EMPTY);
    }

    /**
     * Audit finding (P1-3, full context in publicGet's own javadoc): the actual HTTP call plus
     * Binance-error-to-BinanceApiException translation, extracted here so executeCall (signed)
     * and executePublicCall (public) share the exact same response/error handling -- including
     * the X-MBX-USED-WEIGHT-1M bookkeeping and the Retry-After-aware 418/429 translation that
     * withCircuitBreakerAndRetry's own circuit logic depends on -- rather than a second,
     * independently-maintained copy for public calls that could quietly drift out of sync with
     * this one.
     */
    private JsonNode executeHttpRequest(HttpMethod method, String url, HttpEntity<?> entity) {
        try {
            ResponseEntity<String> resp = http.exchange(url, method, entity, String.class);
            // Review finding ("Broker health still incomplete" — "You don't track:
            // REQUEST_WEIGHT..."): the actual wiring, in the one shared call path most signed
            // requests (including order placement) already go through.
            recordUsedWeightFromHeaders(resp.getHeaders());
            return mapper.readTree(resp.getBody());
        } catch (HttpStatusCodeException e) {
            // Same header is present on an error response too — arguably the more important
            // moment to capture it, since a 429 specifically means the limit was just hit.
            recordUsedWeightFromHeaders(e.getResponseHeaders());
            String body = e.getResponseBodyAsString();
            String msg = body;
            try {
                JsonNode err = mapper.readTree(body);
                msg = "Binance error " + err.path("code").asText() + ": " + err.path("msg").asText(body);
            } catch (Exception ignored) { /* fall back to raw body */ }
            // Review finding (P1 #8, full context in BinanceApiException's own updated
            // retryAfterSeconds field javadoc): read here, where the real response headers are
            // still in scope -- this is Binance's own authoritative answer for how long a 418/429
            // back-off should actually last, not a guess.
            Long retryAfterSeconds = null;
            String retryAfterHeader = e.getResponseHeaders() != null ? e.getResponseHeaders().getFirst("Retry-After") : null;
            if (retryAfterHeader != null) {
                try {
                    retryAfterSeconds = Long.parseLong(retryAfterHeader.trim());
                } catch (NumberFormatException ignored) { /* malformed header -- caller falls back to its own default */ }
            }
            throw new BinanceApiException(msg, body, e.getStatusCode().value(), retryAfterSeconds);
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // Network-level failure (timeout, connection reset, DNS) — no HTTP status to read.
            // Treat as retryable by using a synthetic 503, since these are exactly the transient
            // failures review item #26 is about.
            //
            // Audit item P2 ("secrets/signed-request leakage via BinanceApiException messages"
            // -- external review, confirmed real by direct inspection: RestClientException's own
            // real-world subclass here, ResourceAccessException (what Spring's RestTemplate
            // actually throws on a genuine I/O failure), builds its message as "I/O error on
            // <method> request for \"<url>\": <cause>" -- and `url` is this adapter's own
            // fully-built, SIGNED request URL: every order parameter plus the HMAC-SHA256
            // signature itself. Using e.getMessage() directly, as this used to, put that entire
            // signed URL into BinanceApiException's own message, which
            // OrderResult/OcoOrderResult.errorMessage() then carries all the way into
            // Order.failureReason (persisted), TradingIncident.message (persisted, emailed to
            // the account owner, and sent to any user-configured webhook URL), and the audit
            // chain -- none of which should ever see a live, replayable signed request. The
            // HTTP-error-response branch just above this one is NOT affected -- it only ever
            // carries Binance's own response body, never the request URL.
            log.warn("Binance request failed (network-level, no HTTP status): {}", e.toString(), e);
            throw new BinanceApiException("Binance request failed: " + sanitizedNetworkFailureReason(e), null, 503);
        }
    }

    /**
     * Audit item P2, full context in executeHttpRequest's own updated catch block comment:
     * returns a description of a network-level failure that is safe to persist, email, or send
     * to a webhook -- never the exception's own top-level message, which embeds the full signed
     * request URL for RestClientException's real-world subclasses. Prefers the root cause's own
     * message (a genuine I/O failure's cause -- e.g. SocketTimeoutException/ConnectException --
     * describes the failure itself, such as "Read timed out" or "Connection refused", never the
     * URL), falling back to just the exception's simple class name if there is no cause or its
     * message is empty. The full, unsanitized exception (including its URL-bearing message) is
     * still logged in full, server-side only, by the caller just above this.
     */
    private String sanitizedNetworkFailureReason(Exception e) {
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
        return e.getClass().getSimpleName();
    }

    /**
     * Audit finding (P1-3 -- "public Binance calls bypass the circuit-aware path"): confirmed
     * real by direct inspection -- getCurrentPrice, getSymbolRules, getOrderBookDepth,
     * getSpread, getRecentCandles, getAllTradableUsdtSymbols, getAll24hrTickers, and
     * getHistoricalPrice all used to call http.exchange(...) directly, each with its own
     * ad hoc try/catch, completely bypassing withCircuitBreakerAndRetry's shared circuit check
     * and retry logic. This is the one entry point all of them now go through instead -- `path`
     * is exactly what each of those methods already builds (leading "?query=string" included),
     * `healthKey` is the literal "PUBLIC_ENDPOINT" string every one of them already recorded
     * health under, and the retry is marked idempotent (true) since every single one of these
     * is a plain GET with no side effects, same reasoning signedGet/signedDelete above already
     * use for their own idempotent=true.
     */
    private JsonNode publicGet(BrokerMode mode, String path) {
        return withCircuitBreakerAndRetry("PUBLIC_ENDPOINT", path, true, () -> executePublicCall(mode, path));
    }

    /** Review item #25: periodically resync against exchange server time (public endpoint, no auth) rather than trusting the local clock indefinitely. */
    private long syncServerTime(String base) {
        var offsetRef = serverTimeOffsetMsByBase.computeIfAbsent(base, b -> new AtomicReference<>(0L));
        var checkedAtRef = offsetCheckedAtByBase.computeIfAbsent(base, b -> new AtomicReference<>(Instant.EPOCH));
        if (checkedAtRef.get().isBefore(Instant.now().minusSeconds(300))) {
            try {
                ResponseEntity<String> resp = http.exchange(base + "/api/v3/time", HttpMethod.GET, HttpEntity.EMPTY, String.class);
                long serverTime = mapper.readTree(resp.getBody()).path("serverTime").asLong(System.currentTimeMillis());
                offsetRef.set(serverTime - System.currentTimeMillis());
                checkedAtRef.set(Instant.now());
            } catch (Exception e) {
                // Leave the previous offset in place — a stale offset is safer than crashing every signed call on a transient failure.
            }
        }
        return offsetRef.get();
    }

    /**
     * Review finding (verified via live web search, not memory — dated 2026-01-15, already live
     * on Testnet): Binance now requires signed request payloads to be percent-encoded BEFORE the
     * signature is computed, or the server rejects the request with -1022 INVALID_SIGNATURE. This
     * was a real, silent correctness gap in every signed call this adapter makes — caught by
     * checking current docs instead of assuming the old raw-concatenation approach still worked.
     * Applied to every dynamic parameter value in every query string built below; static literal
     * values (MARKET, SELL, GTC) contain no characters that would ever change under encoding, but
     * are passed through the same path for consistency rather than special-cased.
     */
    private String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String hmacSha256(String secret, String data) {        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign Binance request", e);
        }
    }

    private RestTemplate buildRestTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8000);
        factory.setReadTimeout(8000);
        return new RestTemplate(factory);
    }

    /** Internal only — callers see OrderResult/AccountPermissions, never this exception directly,
     *  except getAccountPermissions/getBalance which let it propagate (caller decides how to surface it). */
    static class BinanceApiException extends RuntimeException {
        final String rawBody;
        final int statusCode;
        // Review finding (P1 #8 -- "Binance 418/429 handled by blind retry"): Binance's own
        // documented contract for both 418 and 429 responses includes a Retry-After header
        // (seconds) telling the caller exactly how long to back off -- this used to be read
        // nowhere in this adapter, which instead applied the SAME generic exponential backoff
        // (attempt^2 * 500ms) used for ordinary 5xx failures, regardless of what Binance itself
        // said the real wait should be. Null when the header was absent or unparseable.
        final Long retryAfterSeconds;
        BinanceApiException(String message, String rawBody, int statusCode) {
            this(message, rawBody, statusCode, null);
        }
        BinanceApiException(String message, String rawBody, int statusCode, Long retryAfterSeconds) {
            super(message);
            this.rawBody = rawBody;
            this.statusCode = statusCode;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }
}
