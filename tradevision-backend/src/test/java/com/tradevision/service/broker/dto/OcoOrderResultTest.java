package com.tradevision.service.broker.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that verificationUncertain is structurally distinct from a confirmed
 * failure, and that every constructor/factory other than the uncertain() factory
 * correctly defaults it to false (a confirmed result).
 */
class OcoOrderResultTest {

    @Test
    void uncertain_setsSuccessFalseAndVerificationUncertainTrue() {
        var result = OcoOrderResult.uncertain("network timeout", "{}");

        assertThat(result.success()).isFalse();
        assertThat(result.verificationUncertain()).isTrue();
        assertThat(result.errorMessage()).isEqualTo("network timeout");
        assertThat(result.ocoOrderListId()).isNull();
    }

    @Test
    void failure_setsVerificationUncertainFalse_aConfirmedResultNotAnUncertainOne() {
        var result = OcoOrderResult.failure("insufficient balance", "{}");

        assertThat(result.success()).isFalse();
        assertThat(result.verificationUncertain()).isFalse();
    }

    @Test
    void fourArgConstructor_defaultsVerificationUncertainFalse() {
        var result = new OcoOrderResult(true, "oco-1", "{}", null);

        assertThat(result.verificationUncertain()).isFalse();
    }

    @Test
    void fiveArgConstructor_defaultsVerificationUncertainFalse() {
        var result = new OcoOrderResult(true, "oco-1", "{}", null, BigDecimal.valueOf(0.9));

        assertThat(result.verificationUncertain()).isFalse();
        assertThat(result.actualProtectedQuantity()).isEqualByComparingTo(BigDecimal.valueOf(0.9));
    }

    @Test
    void sixArgCanonicalConstructor_setsVerificationUncertainExplicitly() {
        var result = new OcoOrderResult(false, null, "{}", "timeout", null, true);

        assertThat(result.verificationUncertain()).isTrue();
    }
}
