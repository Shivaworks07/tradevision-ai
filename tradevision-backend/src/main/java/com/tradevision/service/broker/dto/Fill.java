package com.tradevision.service.broker.dto;

import java.math.BigDecimal;

/**
 * One actual trade fill, with real fee data from Binance.
 *
 * tradeId/executedAt feed the fill ledger. /api/v3/myTrades reliably returns both (id, time),
 * but the order-response embedded fills array is ambiguous across sources on whether tradeId is
 * present there — parsed defensively either way in BinanceBrokerAdapter, with both fields
 * nullable here rather than assumed always present.
 */
public record Fill(BigDecimal price, BigDecimal qty, BigDecimal commission, String commissionAsset,
                    String tradeId, java.time.LocalDateTime executedAt) {
    /** Convenience constructor for call sites that don't have trade-id/timestamp data. */
    public Fill(BigDecimal price, BigDecimal qty, BigDecimal commission, String commissionAsset) {
        this(price, qty, commission, commissionAsset, null, null);
    }
}
