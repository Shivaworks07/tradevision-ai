package com.tradevision.service.broker.dto;

import java.math.BigDecimal;
import java.util.List;

public record OrderResult(
    boolean success,
    String brokerOrderId,
    String clientOrderId,
    String status,
    BigDecimal executedQty,   // actual filled quantity — may be less than requested (partial fill)
    BigDecimal fillPrice,
    String rawResponse,
    String errorMessage,
    List<Fill> fills          // real per-fill commission data, when available (entry orders only — OCO exit legs need a separate myTrades lookup, see BrokerAdapter.getFillsForOrder)
) {
    public static OrderResult failure(String errorMessage, String rawResponse) {
        return new OrderResult(false, null, null, "ERROR", null, null, rawResponse, errorMessage, List.of());
    }

    /** Convenience constructor for call sites that don't have fill-level data (cancels, recovered orders, etc). */
    public OrderResult(boolean success, String brokerOrderId, String clientOrderId, String status,
                        BigDecimal executedQty, BigDecimal fillPrice, String rawResponse, String errorMessage) {
        this(success, brokerOrderId, clientOrderId, status, executedQty, fillPrice, rawResponse, errorMessage, List.of());
    }
}
