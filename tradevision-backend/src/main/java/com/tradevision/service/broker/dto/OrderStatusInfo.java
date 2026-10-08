package com.tradevision.service.broker.dto;

import java.math.BigDecimal;

/** Ground truth for one order, looked up directly by brokerOrderId — never inferred from whether it's still in the open-orders list. */
public record OrderStatusInfo(String status, BigDecimal executedQty, BigDecimal avgPrice, String rawResponse) {}
