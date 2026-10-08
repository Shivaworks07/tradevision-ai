package com.tradevision.service.broker.dto;

import java.math.BigDecimal;

/**
 * The 24hr ticker fields a dynamic universe-ranking pipeline needs: quoteVolume is the liquidity
 * signal (USDT-denominated 24hr volume, not base-asset volume, which isn't comparable across
 * symbols with wildly different prices), bidPrice/askPrice give the real spread, and
 * priceChangePercent gives a cheap first-pass volatility signal without a separate candles call
 * for every candidate symbol.
 */
public record TickerStats(
    String symbol,
    BigDecimal quoteVolume,        // 24hr volume in USDT terms -- the real liquidity signal
    BigDecimal priceChangePercent, // 24hr price change, signed -- a cheap volatility proxy
    BigDecimal bidPrice,
    BigDecimal askPrice
) {}
