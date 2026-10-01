package com.tradevision.service.broker.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding ("OCO recovery still returns null for some verification failures" -- external
 * review, twenty-fourth pass, P1, full context in this record's own class javadoc): the actual
 * tests proving verificationUncertain is structurally distinct from a confirmed failure, and
 * that every existing constructor/factory correctly defaults it to false (a confirmed result),
 * unaffected by this fix.
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
