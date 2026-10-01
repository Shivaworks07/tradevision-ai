package com.tradevision.service.broker.dto;

import java.math.BigDecimal;

/** Ground truth for one order, looked up by brokerOrderId — never inferred from "is it still in the open-orders list" (review item #6). */
public record OrderStatusInfo(String status, BigDecimal executedQty, BigDecimal avgPrice, String rawResponse) {}
