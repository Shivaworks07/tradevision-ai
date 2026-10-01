package com.tradevision.service.broker.dto;

import java.math.BigDecimal;

/**
 * One actual trade fill, with real fee data from Binance — review item #12.
 *
 * tradeId/executedAt added for "#6 — Fill Ledger": verified against current Binance docs before
 * adding these — /api/v3/myTrades reliably returns both (id, time), confirmed consistently
 * across multiple independent sources. The order-response embedded fills array is genuinely
 * ambiguous across sources on whether tradeId is present there (older forum reports say no,
 * current official docs examples say yes) — parsed defensively either way in
 * BinanceBrokerAdapter, both fields nullable here rather than assumed always present.
 */
public record Fill(BigDecimal price, BigDecimal qty, BigDecimal commission, String commissionAsset,
                    String tradeId, java.time.LocalDateTime executedAt) {
    /** Convenience constructor for call sites that don't have trade-id/timestamp data. */
    public Fill(BigDecimal price, BigDecimal qty, BigDecimal commission, String commissionAsset) {
        this(price, qty, commission, commissionAsset, null, null);
    }
}
