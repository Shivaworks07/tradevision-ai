package com.tradevision.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies TradingConfigStartupGuard's bounds check on the stop-limit gap value (must be
 * non-negative and less than 1). @Value fields aren't populated by any Spring-container
 * magic in a plain unit test -- set directly via ReflectionTestUtils, mirroring what a real
 * application.properties resolution would produce, the same pattern as
 * DevSecretStartupGuardTest's setup.
 */
class TradingConfigStartupGuardTest {

    private TradingConfigStartupGuard guardWith(String gap) {
        TradingConfigStartupGuard guard = new TradingConfigStartupGuard();
        ReflectionTestUtils.setField(guard, "stopLossLimitGapPercent", gap == null ? null : new BigDecimal(gap));
        return guard;
    }

    @Test
    @DisplayName("validateStopLossLimitGapPercent: the ordinary default (0.005) passes without throwing")
    void defaultValue_doesNotThrow() {
        assertThat(catchable(() -> guardWith("0.005").validateStopLossLimitGapPercent())).isNull();
    }

    @Test
    @DisplayName("validateStopLossLimitGapPercent: zero is the boundary-inclusive minimum and passes")
    void zero_doesNotThrow() {
        assertThat(catchable(() -> guardWith("0").validateStopLossLimitGapPercent())).isNull();
    }

    @Test
    @DisplayName("validateStopLossLimitGapPercent: a value just below 1.0 passes")
    void justBelowOne_doesNotThrow() {
        assertThat(catchable(() -> guardWith("0.999").validateStopLossLimitGapPercent())).isNull();
    }

    @Test
    @DisplayName("validateStopLossLimitGapPercent: a negative value refuses to start -- it would place the stop-limit ABOVE its own trigger")
    void negative_refusesToStart() {
        assertThatThrownBy(() -> guardWith("-0.01").validateStopLossLimitGapPercent())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REFUSING TO START")
            .hasMessageContaining("-0.01");
    }

    @Test
    @DisplayName("validateStopLossLimitGapPercent: exactly 1.0 refuses to start -- it would zero out the stop-limit price")
    void exactlyOne_refusesToStart() {
        assertThatThrownBy(() -> guardWith("1.0").validateStopLossLimitGapPercent())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REFUSING TO START");
    }

    @Test
    @DisplayName("validateStopLossLimitGapPercent: a value greater than 1.0 (e.g. a stray '0.5' meant as '0.005') refuses to start")
    void greaterThanOne_refusesToStart() {
        assertThatThrownBy(() -> guardWith("5").validateStopLossLimitGapPercent())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REFUSING TO START");
    }

    @Test
    @DisplayName("validateStopLossLimitGapPercent: a null value (should be unreachable given the @Value default, but defensively checked) refuses to start")
    void nullValue_refusesToStart() {
        assertThatThrownBy(() -> guardWith(null).validateStopLossLimitGapPercent())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REFUSING TO START");
    }

    private interface ThrowingRunnable {
        void run();
    }

    private RuntimeException catchable(ThrowingRunnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }
}
