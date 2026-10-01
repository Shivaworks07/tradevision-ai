package com.tradevision.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PlaceTestOrderRequest {
    @NotBlank private String credentialId;
    @NotBlank private String symbol;
    @NotBlank private String side; // BUY or SELL
    @DecimalMin(value = "0.00000001") private BigDecimal quantity;
}
