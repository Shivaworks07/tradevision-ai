package com.tradevision.service.broker.dto;

import java.math.BigDecimal;

/**
 * Review finding ("Strategy universe is still hard-coded" -- external review, fifth pass, P1
 * feature request, confirmed real by direct inspection: TIER1_SYMBOLS is exactly the fixed
 * five-symbol set the review describes): the actual market data a dynamic universe-ranking
 * pipeline needs -- quoteVolume is the real liquidity signal (USDT-denominated 24hr volume, not
 * base-asset volume, which isn't comparable across symbols with wildly different prices),
 * bidPrice/askPrice give the real spread, priceChangePercent gives a cheap first-pass volatility
 * signal without a separate candles call for every candidate symbol.
 */
public record TickerStats(
    String symbol,
    BigDecimal quoteVolume,        // 24hr volume in USDT terms -- the real liquidity signal
    BigDecimal priceChangePercent, // 24hr price change, signed -- a cheap volatility proxy
    BigDecimal bidPrice,
    BigDecimal askPrice
) {}
