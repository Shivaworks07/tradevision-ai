package com.tradevision.service.broker.dto;

import java.math.BigDecimal;

/** Exchange filters for a symbol — every order must be rounded/validated against these before submission.
 *  baseAsset/quoteAsset come straight from exchangeInfo rather than being guessed from a hardcoded suffix list.
 *
 *  Carries the full NOTIONAL filter shape (minNotional, maxNotional, and whether each bound
 *  applies to MARKET orders) plus PERCENT_PRICE_BY_SIDE's multiplierUp/multiplierDown, since an
 *  order whose notional is too large, or a MARKET order the filter doesn't apply to, needs to be
 *  validated differently from the simple minNotional-only case -- see BinanceBrokerAdapter's
 *  doPlaceOrder/placeExitOco for where these are actually enforced pre-submit. */
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
    // Maximum price*quantity this symbol's NOTIONAL/MIN_NOTIONAL filter allows. Zero means "no
    // maximum reported" (some symbols' filters omit maxNotional entirely), never "unlimited to
    // enforce as a real bound" -- callers only compare against this when > 0.
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
