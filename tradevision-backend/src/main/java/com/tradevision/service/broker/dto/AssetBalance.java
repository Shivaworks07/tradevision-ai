package com.tradevision.service.broker.dto;

import java.math.BigDecimal;

public record AssetBalance(String asset, BigDecimal free, BigDecimal locked) {}
