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

    // Optional, and only meaningful for a BUY: without these, a manually placed TESTNET order
    // has no way to be protected once PositionMonitorService's reconciliation pass discovers
    // its fill and tries to place the exit OCO -- left null, the fill is discovered and
    // immediately emergency-flattened, unprotected (see
    // PositionMonitorService.createPositionForLateDiscoveredFill). Validated for a sane ordering
    // (SL below TP for a long) in OrderExecutionService.placeTestOrder before being accepted.
    @DecimalMin(value = "0.00000001") private BigDecimal takeProfitPrice;
    @DecimalMin(value = "0.00000001") private BigDecimal stopLossTriggerPrice;
}
