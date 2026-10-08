package com.tradevision.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Request to create or update a broker credential's risk profile: position sizing, exposure
 * caps, and autonomous-trading safety limits. Fields that RiskEngineService dereferences
 * directly (dailyLossLimitQuote, maxPositionQuoteAmount) are @NotNull, since a null here would
 * otherwise reach the service and throw on the very first risk check for that credential; every
 * numeric field carries a real upper bound, not just a lower one. enabledSymbols is capped since
 * it's iterated by the scanner on every signal check, so an unbounded set is a real, growing
 * per-check cost. See isCrossFieldValid for the relationships enforced between the exposure caps.
 */
@Data
public class RiskProfileRequest {
    @NotBlank private String credentialId;
    private boolean autoTradeEnabled;
    // Required only when credentialId resolves to a LIVE-mode credential -- deliberately not
    // @NotBlank, since that would also demand it for every ordinary TESTNET/PAPER save. Request
    // one first via POST /api/broker/risk-profile/{credentialId}/request-otp.
    private String stepUpOtp;
    // 500 is a generous cap for any realistic trading strategy, while still ruling out an
    // unbounded or mistaken submission; this set is iterated by the scanner on every signal
    // check.
    @NotNull @Size(max = 500) private Set<String> enabledSymbols;
    @Min(0) @Max(100) private double minConfidence = 75.0;
    @NotNull @DecimalMin("0.0") private BigDecimal maxPositionQuoteAmount;
    @Min(1) @Max(50) private int maxConcurrentTrades = 1;
    @NotNull @DecimalMin("0.0") private BigDecimal dailyLossLimitQuote;
    // Capped at 2% per trade: a real risk-management ceiling for how much of the account's
    // equity a single trade may risk, not just a validation formality.
    @DecimalMin("0.01") @DecimalMax("2.0") private double riskPerTradePercent = 1.0;
    @DecimalMin("0.0") private BigDecimal maxTotalExposureQuote;
    @DecimalMin("0.0") private BigDecimal maxSymbolExposureQuote;
    @DecimalMin("0.01") @DecimalMax("50.0") private double maxPriceDeviationPercent = 1.5;
    // Configures correlated-symbol exposure grouping; both optional, and an omitted or empty map
    // leaves the feature inert. The underlying check is a plain read-then-compare, not an atomic
    // reservation the way total/symbol exposure is handled by ExposureReservationService.
    private java.util.Map<String, java.util.Set<String>> correlationGroups;
    private java.util.Map<String, BigDecimal> correlationGroupCaps;

    // Configures RiskProfile's drawdown/rate/circuit-breaker safety limits. All optional with
    // defaults matching the model's own defaults, so an omitted field keeps that protection
    // disabled rather than silently enforcing a limit the caller didn't ask for.
    @Min(0) @Max(100) private double maxDrawdownPercent = 0; // 0 = disabled, matching RiskProfile's own default
    @Min(0) @Max(1000) private int maxOrdersPerHour = 0; // 0 = disabled
    @Min(0) @Max(100) private int maxConsecutiveAutoTradeLosses = 0; // 0 = disabled
    @Min(1) @Max(100) private int circuitBreakerThreshold = 3;

    /**
     * Ensures a single position's or symbol's exposure cap never exceeds the account-wide total
     * exposure cap, which would otherwise be incoherent. Implemented as a method-level
     * @AssertTrue rather than a custom class-level constraint, since this is just a couple of
     * straightforward comparisons between fields on this same class. Each comparison is skipped
     * (treated as valid) when either side is null, since maxTotalExposureQuote and
     * maxSymbolExposureQuote are genuinely optional and a null means that cap simply isn't
     * configured, not that it's invalid.
     */
    @AssertTrue(message = "maxPositionQuoteAmount and maxSymbolExposureQuote must not exceed maxTotalExposureQuote when both are configured "
        + "(a single position, or a single symbol's total exposure, cannot be allowed to exceed the whole account's own exposure cap).")
    public boolean isCrossFieldValid() {
        if (maxTotalExposureQuote == null) return true;
        if (maxPositionQuoteAmount != null && maxPositionQuoteAmount.compareTo(maxTotalExposureQuote) > 0) return false;
        if (maxSymbolExposureQuote != null && maxSymbolExposureQuote.compareTo(maxTotalExposureQuote) > 0) return false;
        return true;
    }
}
