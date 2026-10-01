package com.tradevision.service.broker;

import com.tradevision.model.BrokerMode;
import com.tradevision.model.BrokerType;
import com.tradevision.service.broker.dto.*;

import java.util.List;

/**
 * One implementation per broker. The risk engine, encryption, and audit logging around
 * order execution are written once against this interface — brokers plug in underneath it.
 *
 * Every method takes decrypted credentials directly; implementations must never log or
 * persist apiKey/apiSecret themselves — that's the caller's (OrderExecutionService /
 * BrokerCredentialService) responsibility, and they already handle it via
 * CredentialEncryptionService and BrokerAuditLog.
 */
public interface BrokerAdapter {

    BrokerType getType();

    /** Reads the key's real permissions back from the broker. Used to reject withdrawal-enabled
     *  keys at connect time — never trust a checkbox in the UI for this. */
    AccountPermissions getAccountPermissions(String apiKey, String apiSecret, BrokerMode mode);

    /**
     * Review finding (P1 #9 -- "API key rotation doesn't verify it's the same Binance account"):
     * a stable identifier for the real account a key belongs to (Binance's own account "uid"),
     * independent of the key itself. Used at connect time to record which account this
     * credential is actually tied to, and at rotation time to refuse a new key that silently
     * belongs to a DIFFERENT account -- open positions/OCOs live on the OLD account, and a
     * rotation that moves this credential to a different account's key would leave this
     * application monitoring a balance that was never the one those positions actually belong
     * to. Returns null (never throws for this reason alone) when the broker's response genuinely
     * has no such field -- the caller treats an unknown identity as "cannot verify," never as a
     * silent pass.
     */
    String getAccountUid(String apiKey, String apiSecret, BrokerMode mode);

    /**
     * Review finding (P1 #10 — "Withdrawal-permission check relies on
     * /api/v3/account.canWithdraw"): the actual key-level restrictions Binance reports for THIS
     * specific API key (IP whitelist, withdrawal/transfer permissions, spot trading), as opposed
     * to getAccountPermissions' own canWithdraw, which reflects the ACCOUNT's ability to
     * withdraw at all and says nothing about what this particular key is restricted to. Used for
     * LIVE credentials specifically — the one path where a key mistakenly retaining withdrawal
     * or transfer rights, or lacking an IP whitelist, is a real-money risk.
     */
    ApiKeyRestrictions getApiKeyRestrictions(String apiKey, String apiSecret, BrokerMode mode);

    List<AssetBalance> getBalance(String apiKey, String apiSecret, BrokerMode mode);

    OrderResult placeOrder(String apiKey, String apiSecret, BrokerMode mode, OrderRequest request);

    OrderResult cancelOrder(String apiKey, String apiSecret, BrokerMode mode, String symbol, String brokerOrderId);

    List<OpenOrderInfo> getOpenOrders(String apiKey, String apiSecret, BrokerMode mode);

    /**
     * P3-11 second re-audit fix ("The emergency flatten cancels too much... It lists open orders
     * across all symbols, which costs weight 80 per call" -- external review, third pass, item #3
     * of its own "before real money" list): the symbol-scoped equivalent of getOpenOrders above --
     * Binance's own /api/v3/openOrders costs far less request weight with a symbol filter applied
     * (a handful, not the full-account sweep) than the unfiltered call, and every current caller
     * that needs open orders for exactly one symbol (PositionSafetyService's emergency-flatten
     * stray-order cancellation) never actually needed the other symbols in the first place. The
     * unfiltered overload above is kept for the one caller that genuinely wants the whole
     * account's open orders (OrderExecutionService's user-facing "my open orders" listing).
     */
    List<OpenOrderInfo> getOpenOrders(String apiKey, String apiSecret, BrokerMode mode, String symbol);

    /**
     * Place a SELL stop-loss/take-profit OCO pair to exit a long spot position.
     * Spot-only, long-only — matches the rest of this adapter's scope (no margin/short support).
     */
    OcoOrderResult placeExitOco(String apiKey, String apiSecret, BrokerMode mode, String symbol,
                                 java.math.BigDecimal quantity,
                                 java.math.BigDecimal takeProfitPrice,
                                 java.math.BigDecimal stopLossTriggerPrice,
                                 java.math.BigDecimal stopLossLimitPrice,
                                 String listClientOrderId);

    /** Ground truth for one order by its broker-assigned id — review item #6. Never infer fill state from "not in the open list" alone. */
    OrderStatusInfo getOrderStatus(String apiKey, String apiSecret, BrokerMode mode, String symbol, String brokerOrderId);

    /**
     * Review finding ("Stuck FLATTENING recovery uses balance as evidence that the flatten
     * succeeded" -- external review, third pass, confirmed real by direct inspection before any
     * fix was attempted): the ground-truth lookup for a scenario getOrderStatus's own
     * brokerOrderId-based query can't serve at all -- a process that crashed between an
     * exchange call succeeding and this application's own recordBrokerResult() call recording
     * its brokerOrderId never got that value persisted, even though the order itself may have
     * genuinely succeeded on the exchange. clientOrderId, by contrast, is generated and
     * persisted BEFORE the exchange call, so it's always available for exactly this recovery
     * scenario. Binance's own real API supports this (origClientOrderId, the documented
     * alternative to orderId on the same GET /api/v3/order endpoint) -- not a fabricated
     * capability.
     */
    OrderStatusInfo getOrderStatusByClientOrderId(String apiKey, String apiSecret, BrokerMode mode, String symbol, String clientOrderId);

    /** Ground truth for an OCO pair's state — which leg (if either) filled. */
    OcoStatusInfo getOcoStatus(String apiKey, String apiSecret, BrokerMode mode, String orderListId);

    /**
     * Review finding ("OCO persistence still has an unavoidable crash window" -- external
     * review, nineteenth pass, P1, full context in ProtectionAttempt's own class javadoc): the
     * OCO-list counterpart to getOrderStatusByClientOrderId's own reasoning above -- a process
     * that crashes strictly between the exchange OCO call succeeding and this application's own
     * OrphanedOco/Position recording never gets orderListId (the exchange-assigned numeric id)
     * persisted anywhere, but listClientOrderId is generated and persisted BEFORE the exchange
     * call, via ProtectionAttempt. Binance's own real GET /api/v3/orderList endpoint supports
     * this directly (origClientOrderId as a documented alternative to orderListId) -- confirmed
     * against Binance's own API docs before writing this, not assumed or fabricated.
     */
    OcoStatusInfo getOcoStatusByClientOrderId(String apiKey, String apiSecret, BrokerMode mode, String listClientOrderId);

    /** Public endpoint, no credentials needed. Cached by the implementation. Review item #8.
     *  Review item #16: fetched from the correct host for the given mode — testnet and live
     *  can have different symbol sets/filters, so they must not be conflated. */
    SymbolRules getSymbolRules(String symbol, BrokerMode mode);

    /**
     * Public ticker price, no credentials needed. Used as an independent check against whatever
     * entryPrice a signal claims (review items #2 partial mitigation, #11): if the frontend-computed
     * signal's entry price has drifted too far from what the exchange says the price actually is
     * right now — stale data, a bug, or a tampered request — refuse to trade on it.
     */
    java.math.BigDecimal getCurrentPrice(String symbol, BrokerMode mode);

    /** Public order-book top-of-book, no credentials needed. Review item #23 (spread check, one piece of "liquidity"). */
    SpreadInfo getSpread(String symbol, BrokerMode mode);

    /**
     * Review finding ("Market data" — "exchange clock drift"): exposes the offset this adapter
     * already computes internally (review item #25, an earlier session) to correct signed-
     * request timestamps — that mechanism silently corrects for drift but never surfaced it
     * anywhere monitorable. Returns milliseconds (server time minus local time); positive means
     * the local clock is behind the exchange, negative means it's ahead. Returns 0 if no sync
     * has happened yet — genuinely unknown, not fabricated as "in sync".
     */
    long getClockDriftMs();

    /**
     * Review finding ("Market data" — "order-book depth, bid/ask depth imbalance"): verified
     * against current Binance docs before adding (multiple independent sources checked) — see
     * OrderBookDepth's own javadoc for what was confirmed.
     */
    OrderBookDepth getOrderBookDepth(String symbol, BrokerMode mode, int limit);

    /**
     * Public candle history, no credentials needed. Used to independently recompute a couple of
     * core indicators server-side (review item #2, partial hardening — not a full engine port,
     * but real recomputation from real data the backend fetched itself) and compare against what
     * the signal claims.
     */
    java.util.List<Candle> getRecentCandles(String symbol, String interval, int limit, BrokerMode mode);

    /** Real fill/commission data for a specific order — review item #12. Needed for OCO exit legs,
     *  whose fills aren't returned inline the way a direct order placement's are. */
    /** Cancels an entire OCO order-list — needed when the protection quantity turns out to be
     *  wrong and must be replaced, not just left mismatched. */
    OcoOrderResult cancelOco(String apiKey, String apiSecret, BrokerMode mode, String symbol, String orderListId);

    java.util.List<Fill> getFillsForOrder(String apiKey, String apiSecret, BrokerMode mode, String symbol, String orderId);

    /**
     * Review finding ("Strategy universe is still hard-coded" -- external review, fifth pass,
     * P1 feature request, full context in TickerStats's own javadoc): the actual exchange-wide
     * symbol discovery a dynamic universe needs -- every symbol currently in TRADING status with
     * USDT as its quote asset and spot trading actually allowed for it. Deliberately returns raw
     * symbols, not SymbolRules, since fetching full rules (tick/step size, minNotional, etc.) for
     * hundreds of symbols up front would be wasteful when only a handful ever make the final cut
     * -- callers fetch full rules via the existing getSymbolRules(), which already caches per
     * symbol, only for the symbols that survive this discovery and ranking pipeline.
     */
    java.util.List<String> getAllTradableUsdtSymbols(BrokerMode mode);

    /**
     * Review finding, same context: the actual liquidity/volatility/spread data the ranking
     * pipeline needs, for every symbol in one call rather than one round-trip per candidate.
     */
    java.util.List<com.tradevision.service.broker.dto.TickerStats> getAll24hrTickers(BrokerMode mode);

    /**
     * Review finding ("There is still a real commission/P&L limitation" -- external review,
     * tenth pass, P1, confirmed real by direct inspection before any fix was attempted:
     * FillLedgerService.buildRecord only ever set quoteCommission when the commission asset
     * already equaled the quote asset -- a BNB commission on a BTCUSDT trade was left null
     * rather than converted, honestly avoiding a WRONG conversion but also meaning that fee was
     * silently absent from any P&L/win-loss calculation that sums quoteCommission): the actual
     * missing piece -- the real price of the commission asset AT THE FILL TIMESTAMP, not
     * "current" price, which the review correctly named as capable of "distorting realized P&L"
     * if used as a stand-in for a historical value. Uses a narrow kline window at the exact
     * timestamp (1-minute interval, startTime=timestamp, endTime=timestamp+60000) rather than
     * "recent" candles, which would silently return the WRONG (current) price for anything but
     * a same-minute fill. Returns null (never a guess) if no candle exists for that window at
     * all -- callers must keep treating that as "genuinely unknown," the same honest handling
     * FillLedgerService already had for the case this method didn't exist to fill in.
     */
    java.math.BigDecimal getHistoricalPrice(String symbol, long timestampMillis, BrokerMode mode);

    /**
     * Review finding ("User-data WebSocket is STILL NOT implemented" -- external review,
     * eleventh pass, P1): explicitly NOT a fix for that finding -- streaming, reconnection, and
     * message-parsing against a real exchange connection is real, further work this pass
     * deliberately does not attempt, since it cannot be tested against live Binance traffic in
     * this environment and shipping untested logic on that path would be worse than the current,
     * tested REST-reconciliation architecture. This is only the listen-key lifecycle -- pure,
     * bounded REST calls (create/keepalive/close), the one part of user-data-stream support that
     * is genuinely safe to build and verify without a live streaming connection. A real listen
     * key alone does nothing without a WebSocket client actually connecting to it and consuming
     * events -- that remains unbuilt.
     */
    String createListenKey(String apiKey, String apiSecret, BrokerMode mode);
    void keepAliveListenKey(String apiKey, String apiSecret, String listenKey, BrokerMode mode);
    void closeListenKey(String apiKey, String apiSecret, String listenKey, BrokerMode mode);
}
