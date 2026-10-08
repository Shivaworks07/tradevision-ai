package com.tradevision.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the cross-field risk validation on RiskProfileRequest. Tests isCrossFieldValid()
 * directly as a plain method call rather than through the full Jakarta Bean Validation
 * framework (Validation.buildDefaultValidatorFactory()), since pulling in a validator
 * factory to confirm a single boolean method's logic is more machinery than needed.
 */
class RiskProfileRequestTest {

    private RiskProfileRequest baseRequest() {
        RiskProfileRequest req = new RiskProfileRequest();
        req.setCredentialId("cred1");
        req.setMaxPositionQuoteAmount(BigDecimal.TEN);
        req.setDailyLossLimitQuote(BigDecimal.TEN);
        return req;
    }

    @Test
    @DisplayName("isCrossFieldValid: maxTotalExposureQuote not configured (null) -- always valid, since the whole feature is inert when omitted")
    void crossFieldValid_totalExposureNotConfigured_alwaysValid() {
        RiskProfileRequest req = baseRequest();
        req.setMaxPositionQuoteAmount(BigDecimal.valueOf(1_000_000)); // would be invalid if maxTotalExposureQuote were set

        assertThat(req.isCrossFieldValid()).isTrue();
    }

    @Test
    @DisplayName("isCrossFieldValid: maxPositionQuoteAmount exceeds maxTotalExposureQuote -- invalid")
    void crossFieldValid_positionExceedsTotal_invalid() {
        RiskProfileRequest req = baseRequest();
        req.setMaxPositionQuoteAmount(BigDecimal.valueOf(500));
        req.setMaxTotalExposureQuote(BigDecimal.valueOf(400));

        assertThat(req.isCrossFieldValid()).isFalse();
    }

    @Test
    @DisplayName("isCrossFieldValid: maxSymbolExposureQuote exceeds maxTotalExposureQuote -- invalid")
    void crossFieldValid_symbolExposureExceedsTotal_invalid() {
        RiskProfileRequest req = baseRequest();
        req.setMaxSymbolExposureQuote(BigDecimal.valueOf(500));
        req.setMaxTotalExposureQuote(BigDecimal.valueOf(400));

        assertThat(req.isCrossFieldValid()).isFalse();
    }

    @Test
    @DisplayName("isCrossFieldValid: both caps genuinely at or below the total -- valid")
    void crossFieldValid_bothCapsWithinTotal_valid() {
        RiskProfileRequest req = baseRequest();
        req.setMaxPositionQuoteAmount(BigDecimal.valueOf(100));
        req.setMaxSymbolExposureQuote(BigDecimal.valueOf(200));
        req.setMaxTotalExposureQuote(BigDecimal.valueOf(500));

        assertThat(req.isCrossFieldValid()).isTrue();
    }

    @Test
    @DisplayName("isCrossFieldValid: a cap exactly equal to the total is valid (not exceeding it, per the '> 0' comparison, not '>= 0')")
    void crossFieldValid_capExactlyEqualToTotal_valid() {
        RiskProfileRequest req = baseRequest();
        req.setMaxPositionQuoteAmount(BigDecimal.valueOf(500));
        req.setMaxTotalExposureQuote(BigDecimal.valueOf(500));

        assertThat(req.isCrossFieldValid()).isTrue();
    }

    @Test
    @DisplayName("isCrossFieldValid: maxSymbolExposureQuote not configured (null) while maxPositionQuoteAmount and maxTotalExposureQuote both are -- only the configured comparison is checked, the null side is skipped")
    void crossFieldValid_symbolExposureNull_onlyPositionCheckApplies() {
        RiskProfileRequest req = baseRequest();
        req.setMaxPositionQuoteAmount(BigDecimal.valueOf(100)); // within total
        req.setMaxTotalExposureQuote(BigDecimal.valueOf(500));
        // maxSymbolExposureQuote left null -- must not throw a NullPointerException.

        assertThat(req.isCrossFieldValid()).isTrue();
    }
}
