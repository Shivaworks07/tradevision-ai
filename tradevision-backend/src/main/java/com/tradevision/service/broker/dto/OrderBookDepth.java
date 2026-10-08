package com.tradevision.service.broker.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Order-book depth snapshot used for bid/ask depth-imbalance signals. Bids are sorted
 * highest-to-lowest price, asks lowest-to-highest — the natural "best price first" order for
 * each side, matching Binance's own documented response shape.
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
