package com.tradevision.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies PnlCalculator's pnlPct/pnlR formula, shared by CallResultUpdater and
 * TradeCallService, using hand-calculable values to confirm the arithmetic is correct.
 */
class PnlCalculatorTest {

    @Test
    @DisplayName("compute: a LONG position that moved favorably -- entry 100, exit 110, stop-loss 95 -- pnlPct=10.0%, pnlR=2.0 (a 10% favorable move against a 5% stop-loss distance)")
    void longFavorableMove_computesCorrectly() {
        var result = PnlCalculator.compute(100.0, 110.0, 95.0, true);

        assertThat(result.pnlPct()).isEqualTo(10.0);
        assertThat(result.pnlR()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("compute: a LONG position that hit its own stop-loss -- entry 100, exit 95 (the stop-loss itself), stop-loss 95 -- pnlPct=-5.0%, pnlR=-1.0 (exactly -1R, the defining property of a stop-loss hit)")
    void longStopLossHit_computesExactlyNegativeOneR() {
        var result = PnlCalculator.compute(100.0, 95.0, 95.0, true);

        assertThat(result.pnlPct()).isEqualTo(-5.0);
        assertThat(result.pnlR()).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("compute: a SHORT position that moved favorably -- entry 100, exit 90, stop-loss 105 -- pnlPct=10.0%, pnlR=2.0 (direction correctly flips the sign of a favorable move)")
    void shortFavorableMove_computesCorrectly() {
        var result = PnlCalculator.compute(100.0, 90.0, 105.0, false);

        assertThat(result.pnlPct()).isEqualTo(10.0);
        assertThat(result.pnlR()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("compute: stop-loss distance of exactly zero (entry price equals stop-loss price) never divides by zero -- pnlR defaults to 0.0, the same honest edge-case handling both original call sites had")
    void zeroStopLossDistance_neverDividesByZero() {
        var result = PnlCalculator.compute(100.0, 110.0, 100.0, true);

        assertThat(result.pnlR()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("compute: pnlPct rounds to 3 decimal places, pnlR rounds to 2 -- the same rounding precision both original call sites used")
    void roundingPrecisionMatchesOriginalFormula() {
        // 100 -> 103.333... is a repeating decimal, exercising the rounding itself, not just a clean number.
        var result = PnlCalculator.compute(100.0, 103.333333, 98.0, true);

        assertThat(result.pnlPct()).isEqualTo(3.333); // 3 decimal places
        assertThat(String.valueOf(result.pnlR())).matches("-?\\d+\\.\\d{1,2}"); // at most 2 decimal places
    }
}
