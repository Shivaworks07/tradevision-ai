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

    // Follow-up fix (ported from an earlier local build of this codebase, re-verified against
    // this repo's own current OrderExecutionService/OrderService/PositionMonitorService before
    // applying rather than copied blind): optional, and only meaningful for a BUY -- without
    // these, a manually placed TESTNET order has no way to be protected once
    // PositionMonitorService's own reconciliation pass discovers its fill and tries to place the
    // exit OCO. See PositionMonitorService.createPositionForLateDiscoveredFill's own "No SL/TP
    // recorded ... emergency-flattening" check, which is exactly what fires when these are left
    // null -- left null, behavior is unchanged from before this field existed (the fill is
    // discovered and immediately emergency-flattened, unprotected). Validated for a sane
    // ordering (SL below TP for a long) in OrderExecutionService.placeTestOrder before being
    // accepted. OrderService.recordEntryMetadata already accepts and persists both (confirmed
    // directly, not assumed) -- this DTO and placeTestOrder's own call site were the only pieces
    // actually missing.
    @DecimalMin(value = "0.00000001") private BigDecimal takeProfitPrice;
    @DecimalMin(value = "0.00000001") private BigDecimal stopLossTriggerPrice;
}
