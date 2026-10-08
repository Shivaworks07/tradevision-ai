package com.tradevision.service.broker.dto;

import java.math.BigDecimal;

/**
 * type is fixed to MARKET in this stage. clientOrderId is the idempotency key: pass a
 * deterministic value derived from the signal/request so a retried call is deduplicated
 * by the broker itself (Binance rejects a repeat of the same newClientOrderId within its window)
 * instead of silently doubling the position.
 */
public record OrderRequest(String symbol, String side, String type, BigDecimal quantity, String clientOrderId) {}
