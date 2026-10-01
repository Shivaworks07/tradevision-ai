package com.tradevision.service.broker.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Review finding ("Market data" — "order-book depth, bid/ask depth imbalance"). Verified against
 * current Binance docs (multiple independent sources agree): bids sorted highest-to-lowest
 * price, asks sorted lowest-to-highest — the natural "best price first" order for each side.
 */
public record OrderBookDepth(List<PriceLevel> bids, List<PriceLevel> asks) {
    public record PriceLevel(BigDecimal price, BigDecimal quantity) {}

    /** Sum of quantity across every returned bid level. */
    public BigDecimal totalBidQuantity() {
        return bids.stream().map(PriceLevel::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Sum of quantity across every returned ask level. */
    public BigDecimal totalAskQuantity() {
        return asks.stream().map(PriceLevel::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
