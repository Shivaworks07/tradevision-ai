package com.tradevision.service.broker.dto;

import java.math.BigDecimal;

/** Exchange filters for a symbol — every order must be rounded/validated against these before submission (review item #8).
 *  baseAsset/quoteAsset come straight from exchangeInfo (review item #14) — no more guessing quote asset from a hardcoded suffix list.
 *
 *  P2-3 fix ("getSymbolRules: PERCENT_PRICE_BY_SIDE, MAX_NUM_ALGO_ORDERS, NOTIONAL maxNotional/
 *  applyMinToMarket not enforced; OCO legs not checked vs minNotional" -- external review,
 *  confirmed real by direct inspection: this record used to carry only minNotional, never
 *  maxNotional or the applyMinToMarket/applyMaxToMarket flags Binance's own NOTIONAL filter
 *  actually specifies -- meaning an order whose notional was too LARGE, or a MARKET order this
 *  filter genuinely doesn't apply to, was validated identically to one where the filter always
 *  applies. maxNotional/applyMinToMarket/applyMaxToMarket added below, and multiplierUp/
 *  multiplierDown for PERCENT_PRICE_BY_SIDE -- see BinanceBrokerAdapter's own updated
 *  doPlaceOrder/placeExitOco javadoc for where these are actually enforced pre-submit now, and
 *  for the one item (MAX_NUM_ALGO_ORDERS) this pass disclosed rather than enforced. */
public record SymbolRules(
    String symbol,
    String baseAsset,
    String quoteAsset,
    BigDecimal tickSize,      // price increment
    BigDecimal stepSize,      // quantity increment
    BigDecimal minQty,
    BigDecimal minNotional,   // minimum price*quantity
    int pricePrecision,
    int quantityPrecision,
    // P2-3 fix: maximum price*quantity this symbol's NOTIONAL/MIN_NOTIONAL filter allows. Zero
    // means "no maximum reported" (some symbols' filters omit maxNotional entirely), never
    // "unlimited to enforce as a real bound" -- callers only compare against this when > 0.
    BigDecimal maxNotional,
    // Whether the exchange's own NOTIONAL filter applies its min/max bound to MARKET orders
    // specifically -- Binance's documented filter shape: a LIMIT order is always checked, but a
    // MARKET order is only checked when the corresponding applyMinToMarket/applyMaxToMarket flag
    // is true for this symbol.
    boolean applyMinNotionalToMarket,
    boolean applyMaxNotionalToMarket,
    // PERCENT_PRICE_BY_SIDE: a LIMIT/STOP_LOSS_LIMIT order's price must fall within
    // [referencePrice * multiplierDown, referencePrice * multiplierUp] or Binance rejects it
    // outright. Zero on either means "filter not present for this symbol" -- callers only
    // enforce this bound when both are > 0.
    BigDecimal multiplierUp,
    BigDecimal multiplierDown
) {}
