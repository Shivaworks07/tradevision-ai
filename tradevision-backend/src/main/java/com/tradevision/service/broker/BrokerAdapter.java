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
     * A stable identifier for the real account a key belongs to (Binance's own account "uid"),
     * independent of the key itself. Used at connect time to record which account this
     * credential is actually tied to, and at rotation time to refuse a new key that silently
     * belongs to a different account -- open positions/OCOs live on the old account, and a
     * rotation that moves this credential to a different account's key would leave this
     * application monitoring a balance that was never the one those positions actually belong
     * to. Returns null (never throws for this reason alone) when the broker's response has no
     * such field -- the caller treats an unknown identity as "cannot verify," never as a silent
     * pass.
     */
    String getAccountUid(String apiKey, String apiSecret, BrokerMode mode);

    /**
     * The key-level restrictions Binance reports for this specific API key (IP whitelist,
     * withdrawal/transfer permissions, spot trading), as opposed to getAccountPermissions' own
     * canWithdraw, which reflects the account's ability to withdraw at all and says nothing
     * about what this particular key is restricted to. Used for LIVE credentials specifically --
     * the one path where a key mistakenly retaining withdrawal or transfer rights, or lacking an
     * IP whitelist, is a real-money risk.
     */
    ApiKeyRestrictions getApiKeyRestrictions(String apiKey, String apiSecret, BrokerMode mode);

    List<AssetBalance> getBalance(String apiKey, String apiSecret, BrokerMode mode);

    OrderResult placeOrder(String apiKey, String apiSecret, BrokerMode mode, OrderRequest request);

    OrderResult cancelOrder(String apiKey, String apiSecret, BrokerMode mode, String symbol, String brokerOrderId);

    List<OpenOrderInfo> getOpenOrders(String apiKey, String apiSecret, BrokerMode mode);

    /**
     * The symbol-scoped equivalent of getOpenOrders above: Binance's /api/v3/openOrders costs far
     * less request weight with a symbol filter applied than the unfiltered, full-account call, and
     * callers that only need one symbol's open orders (PositionSafetyService's emergency-flatten
     * stray-order cancellation) should use this rather than the unfiltered overload. The
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

    /** Ground truth for one order by its broker-assigned id. Never infer fill state from "not in the open list" alone. */
    OrderStatusInfo getOrderStatus(String apiKey, String apiSecret, BrokerMode mode, String symbol, String brokerOrderId);

    /**
     * The ground-truth lookup for a scenario getOrderStatus's brokerOrderId-based query can't
     * serve: a process that crashed between an exchange call succeeding and this application's
     * recordBrokerResult() call recording its brokerOrderId never gets that value persisted, even
     * though the order itself may have genuinely succeeded on the exchange. clientOrderId, by
     * contrast, is generated and persisted before the exchange call, so it's always available for
     * exactly this recovery scenario, via Binance's documented origClientOrderId alternative to
     * orderId on the same GET /api/v3/order endpoint.
     */
    OrderStatusInfo getOrderStatusByClientOrderId(String apiKey, String apiSecret, BrokerMode mode, String symbol, String clientOrderId);

    /** Ground truth for an OCO pair's state — which leg (if either) filled. */
    OcoStatusInfo getOcoStatus(String apiKey, String apiSecret, BrokerMode mode, String orderListId);

    /**
     * The OCO-list counterpart to getOrderStatusByClientOrderId's reasoning above: a process that
     * crashes strictly between the exchange OCO call succeeding and this application's own
     * OrphanedOco/Position recording never gets orderListId (the exchange-assigned numeric id)
     * persisted anywhere, but listClientOrderId is generated and persisted before the exchange
     * call, via ProtectionAttempt. Binance's GET /api/v3/orderList endpoint supports this directly
     * via origClientOrderId as a documented alternative to orderListId.
     */
    OcoStatusInfo getOcoStatusByClientOrderId(String apiKey, String apiSecret, BrokerMode mode, String listClientOrderId);

    /** Public endpoint, no credentials needed. Cached by the implementation.
     *  Fetched from the correct host for the given mode — testnet and live can have different
     *  symbol sets/filters, so they must not be conflated. */
    SymbolRules getSymbolRules(String symbol, BrokerMode mode);

    /**
     * Public ticker price, no credentials needed. Used as an independent check against whatever
     * entryPrice a signal claims: if the frontend-computed signal's entry price has drifted too
     * far from what the exchange says the price actually is right now — stale data, a bug, or a
     * tampered request — refuse to trade on it.
     */
    java.math.BigDecimal getCurrentPrice(String symbol, BrokerMode mode);

    /** Public order-book top-of-book, no credentials needed. Backs the spread check, one piece of liquidity screening. */
    SpreadInfo getSpread(String symbol, BrokerMode mode);

    /**
     * Exposes the clock-drift offset this adapter computes internally to correct signed-request
     * timestamps, so that correction is monitorable rather than silent. Returns milliseconds
     * (server time minus local time); positive means the local clock is behind the exchange,
     * negative means it's ahead. Returns 0 if no sync has happened yet — genuinely unknown, not
     * fabricated as "in sync".
     */
    long getClockDriftMs();

    /**
     * Order-book depth for bid/ask depth-imbalance signals — see OrderBookDepth's own javadoc.
     */
    OrderBookDepth getOrderBookDepth(String symbol, BrokerMode mode, int limit);

    /**
     * Public candle history, no credentials needed. Used to independently recompute a couple of
     * core indicators server-side from data the backend fetched itself and compare against what
     * the signal claims.
     */
    java.util.List<Candle> getRecentCandles(String symbol, String interval, int limit, BrokerMode mode);

    /** Cancels an entire OCO order-list — needed when the protection quantity turns out to be
     *  wrong and must be replaced, not just left mismatched. */
    OcoOrderResult cancelOco(String apiKey, String apiSecret, BrokerMode mode, String symbol, String orderListId);

    /** Real fill/commission data for a specific order. Needed for OCO exit legs, whose fills
     *  aren't returned inline the way a direct order placement's are. */
    java.util.List<Fill> getFillsForOrder(String apiKey, String apiSecret, BrokerMode mode, String symbol, String orderId);

    /**
     * The exchange-wide symbol discovery a dynamic universe needs -- every symbol currently in
     * TRADING status with USDT as its quote asset and spot trading actually allowed for it.
     * Deliberately returns raw symbols, not SymbolRules, since fetching full rules (tick/step
     * size, minNotional, etc.) for hundreds of symbols up front would be wasteful when only a
     * handful ever make the final cut -- callers fetch full rules via getSymbolRules(), which
     * already caches per symbol, only for the symbols that survive this discovery and ranking
     * pipeline.
     */
    java.util.List<String> getAllTradableUsdtSymbols(BrokerMode mode);

    /**
     * The liquidity/volatility/spread data the universe-ranking pipeline needs, for every symbol
     * in one call rather than one round-trip per candidate.
     */
    java.util.List<com.tradevision.service.broker.dto.TickerStats> getAll24hrTickers(BrokerMode mode);

    /**
     * The real price of a commission asset at the fill timestamp, not "current" price, which
     * would distort realized P&L if used as a stand-in for a historical value. This lets a
     * non-quote-asset commission (e.g. BNB on a BTCUSDT trade) be converted into quoteCommission
     * rather than left null. Uses a narrow kline window at the exact timestamp (1-minute
     * interval, startTime=timestamp, endTime=timestamp+60000) rather than "recent" candles,
     * which would silently return the wrong (current) price for anything but a same-minute
     * fill. Returns null (never a guess) if no candle exists for that window at all -- callers
     * must treat that as genuinely unknown.
     */
    java.math.BigDecimal getHistoricalPrice(String symbol, long timestampMillis, BrokerMode mode);

    /**
     * The listen-key lifecycle only -- pure, bounded REST calls (create/keepalive/close), the
     * part of user-data-stream support that's safe to build and verify without a live streaming
     * connection. A listen key alone does nothing without a WebSocket client actually connecting
     * to it and consuming events; that streaming/reconnection/message-parsing layer is separate,
     * further work this interface does not cover.
     */
    String createListenKey(String apiKey, String apiSecret, BrokerMode mode);
    void keepAliveListenKey(String apiKey, String apiSecret, String listenKey, BrokerMode mode);
    void closeListenKey(String apiKey, String apiSecret, String listenKey, BrokerMode mode);
}
