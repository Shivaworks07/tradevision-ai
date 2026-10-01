package com.tradevision.service.broker.dto;

import java.math.BigDecimal;

public record SpreadInfo(BigDecimal bidPrice, BigDecimal askPrice, double spreadPercent) {}
