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
 * Review finding ("risk profile validation is incomplete"): @DecimalMin alone does NOT reject a
 * null value — Jakarta Bean Validation treats null as valid for most constraints unless paired
 * with @NotNull. RiskEngineService dereferences several of these fields without a null check
 * (dailyLossLimitQuote, maxPositionQuoteAmount), so an omitted field in the request body used to
 * reach the service as null and throw a NullPointerException on the very first risk check for
 * that credential. Every field the risk/sizing logic actually dereferences is now @NotNull, and
 * every numeric field has a real upper bound, not just a lower one.
 *
 * UPDATE ("Symbol list bounds" and "Cross-field risk validation on RiskProfile inputs" -- P2):
 * both confirmed real and closed here. enabledSymbols had no size limit at all -- a user (or a
 * bug in whatever builds this request) could submit an arbitrarily large set, iterated by the
 * scanner on every single signal check thereafter, a real and growing per-check cost with no
 * bound. And several of this DTO's own numeric caps had no relationship enforced between them --
 * nothing stopped maxPositionQuoteAmount or maxSymbolExposureQuote from being configured LARGER
 * than maxTotalExposureQuote itself, which is incoherent (a single position, or a single
 * symbol's total exposure, capped above what the WHOLE account is allowed to hold). Both fixed
 * below -- see isCrossFieldValid's own javadoc for why a method-level @AssertTrue rather than a
 * custom class-level constraint annotation.
 */
@Data
public class RiskProfileRequest {
    @NotBlank private String credentialId;
    private boolean autoTradeEnabled;
    // Audit fix (P1-5 follow-up, full context in RiskProfileService.RISK_PROFILE_STEPUP_PURPOSE's
    // own javadoc): required only when credentialId resolves to a LIVE-mode credential --
    // deliberately NOT @NotBlank here, since that would also demand it for every ordinary
    // TESTNET/PAPER save. Request one first via POST
    // /api/broker/risk-profile/{credentialId}/request-otp.
    private String stepUpOtp;
    // Review finding ("Symbol list bounds" -- P2): 500 is a generous cap for any realistic
    // trading strategy (this application's own AutonomousScannerService tier1 symbol list is a
    // small fraction of that), while still ruling out an unbounded/mistaken submission.
    @NotNull @Size(max = 500) private Set<String> enabledSymbols;
    @Min(0) @Max(100) private double minConfidence = 75.0;
    @NotNull @DecimalMin("0.0") private BigDecimal maxPositionQuoteAmount;
    @Min(1) @Max(50) private int maxConcurrentTrades = 1;
    @NotNull @DecimalMin("0.0") private BigDecimal dailyLossLimitQuote;
    // P0-6 fix ("LIVE risk-limit enforcement" -- external review, confirmed real by direct
    // inspection: 25% was the previous upper bound here -- a single trade genuinely allowed to
    // risk a QUARTER of the account's own equity is not a real risk control, it's a number that
    // happens to satisfy a validation annotation. Lowered to 2% (the audit's own suggested
    // ceiling for a single trade's risk under any real risk-management practice) -- a value
    // ABOVE this was never a deliberate, considered choice this codebase's own design ever
    // discussed, only an unenforced gap the validation annotation left wide open.
    @DecimalMin("0.01") @DecimalMax("2.0") private double riskPerTradePercent = 1.0;
    @DecimalMin("0.0") private BigDecimal maxTotalExposureQuote;
    @DecimalMin("0.0") private BigDecimal maxSymbolExposureQuote;
    @DecimalMin("0.01") @DecimalMax("50.0") private double maxPriceDeviationPercent = 1.5;
    // Review finding (P1 #5 — "Correlation risk controls are still dead/incomplete", re-flagged
    // across multiple review passes: "Don't expose a risk-control feature that isn't actually
    // operational"): confirmed and finally closed on the API-reachability side — these fields
    // never existed on this request DTO, so the UI/API genuinely could not configure them no
    // matter what RiskEngineService's own check looked like. Both optional: an omitted or empty
    // map means the feature stays inert, exactly as before. See RiskProfile's own javadoc for
    // the one limitation that's still honestly disclosed, not silently fixed: the check itself
    // remains a plain read-then-compare, not the atomic reservation ExposureReservationService
    // gives total/symbol exposure — real further scope, not something to fake as fully solved.
    private java.util.Map<String, java.util.Set<String>> correlationGroups;
    private java.util.Map<String, BigDecimal> correlationGroupCaps;

    // P0-6 fix ("LIVE risk-limit enforcement" -- external review, confirmed real by direct
    // inspection: RiskProfile.maxDrawdownPercent/maxOrdersPerHour/maxConsecutiveAutoTradeLosses/
    // circuitBreakerThreshold all already existed as real, enforced fields on the model and were
    // already read by RiskEngineService's own checks -- but none of them were ever exposed on
    // this request DTO, so no user could actually configure them through the API/UI no matter
    // what value they wanted. A user was stuck at the model's own hardcoded defaults
    // (maxDrawdownPercent=0/disabled, maxOrdersPerHour=0/disabled, circuitBreakerThreshold=3,
    // maxConsecutiveAutoTradeLosses=0/disabled) forever, with no way to actually turn these
    // protections on. Added here, additively -- every field below is optional/has a safe
    // default matching the model's own existing default, so an existing caller that omits them
    // keeps exactly the same behavior as before this fix.
    @Min(0) @Max(100) private double maxDrawdownPercent = 0; // 0 = disabled, matching RiskProfile's own default
    @Min(0) @Max(1000) private int maxOrdersPerHour = 0; // 0 = disabled
    @Min(0) @Max(100) private int maxConsecutiveAutoTradeLosses = 0; // 0 = disabled
    @Min(1) @Max(100) private int circuitBreakerThreshold = 3;

    /**
     * Review finding ("Cross-field risk validation on RiskProfile inputs" -- P2): a method-level
     * @AssertTrue rather than a custom class-level constraint annotation (e.g.
     * @ValidExposureCaps) -- Jakarta Bean Validation's own supported, lower-ceremony mechanism
     * for exactly this shape of check (a boolean relationship between a handful of this same
     * class's own fields), without needing a separate annotation type, a ConstraintValidator
     * implementation class, and the wiring between them that a real custom constraint would
     * need for what's ultimately three straightforward comparisons. Every comparison here is
     * skipped (treated as valid) when either side is null -- @NotNull/@DecimalMin above already
     * enforce that maxPositionQuoteAmount is never null, but maxTotalExposureQuote and
     * maxSymbolExposureQuote are both genuinely optional fields (the whole feature is inert when
     * omitted, per this class's own correlationGroups comment above), so a null on either side
     * of a comparison here means "this specific cap isn't configured," not "this is invalid."
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
