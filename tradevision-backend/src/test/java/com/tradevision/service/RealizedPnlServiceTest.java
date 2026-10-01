package com.tradevision.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding ("Position P&L architecture is still scattered" -- full context in
 * RealizedPnlService's own javadoc): verifies the actual consolidated formula against the exact
 * behavior of all three original sites it replaces (PositionSafetyService's full-close path,
 * its own separate recordPartialFlattenPnl path, and PositionMonitorService's OCO/TP/SL path) --
 * confirmed via a standalone harness before this file was written, reproduced here as permanent
 * test coverage none of the three original, scattered implementations ever had.
 */
class RealizedPnlServiceTest {

    private final RealizedPnlService service = new RealizedPnlService();

    private static final BigDecimal ENTRY_PRICE = new BigDecimal("50000");
    private static final BigDecimal EXIT_PRICE = new BigDecimal("51000");
    private static final BigDecimal FULL_QTY = new BigDecimal("1.0");
    private static final BigDecimal ENTRY_FEE = new BigDecimal("5.0");
    private static final BigDecimal EXIT_FEE = new BigDecimal("5.1");

    @Test
    @DisplayName("calculate: a full close (exitQty == currentQuantity) matches PositionSafetyService's original full-close formula exactly -- (51000-50000)*1.0 - 5.0 - 5.1 = 989.9")
    void fullClose_matchesOriginalFullCloseFormula() {
        var result = service.calculate(ENTRY_PRICE, EXIT_PRICE, FULL_QTY, FULL_QTY, ENTRY_FEE, EXIT_FEE);

        assertThat(result.realizedPnl()).isEqualByComparingTo("989.9");
        assertThat(result.remainingEntryFeeQuote()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("calculate: a partial close (0.4 of 1.0) matches both PositionSafetyService's and PositionMonitorService's original partial-close formulas exactly -- confirmed identical between the two original sites, not just internally consistent")
    void partialClose_matchesBothOriginalPartialCloseFormulas() {
        var result = service.calculate(ENTRY_PRICE, EXIT_PRICE, new BigDecimal("0.4"), FULL_QTY, ENTRY_FEE, EXIT_FEE);

        assertThat(result.realizedPnl()).isEqualByComparingTo("392.9");
        assertThat(result.entryFeeShare()).isEqualByComparingTo("2.0"); // 5.0 * 0.4/1.0
        assertThat(result.remainingEntryFeeQuote()).isEqualByComparingTo("3.0"); // 5.0 - 2.0
    }

    @Test
    @DisplayName("calculate: a null entryFeeQuote (never known) deducts no entry fee at all and preserves null, never fabricating a zero or any other value")
    void nullEntryFeeQuote_neverFabricated() {
        var result = service.calculate(ENTRY_PRICE, EXIT_PRICE, FULL_QTY, FULL_QTY, null, null);

        assertThat(result.realizedPnl()).isEqualByComparingTo("1000"); // raw price difference only, no fee deductions
        assertThat(result.remainingEntryFeeQuote()).isNull();
        assertThat(result.entryFeeShare()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("calculate: a known entryFeeQuote with an unknown (null) exitFee still deducts the entry fee -- the two fees are deducted independently, never gated on each other")
    void unknownExitFee_stillDeductsKnownEntryFee() {
        var result = service.calculate(ENTRY_PRICE, EXIT_PRICE, FULL_QTY, FULL_QTY, ENTRY_FEE, null);

        assertThat(result.realizedPnl()).isEqualByComparingTo("995.0"); // 1000 - 5.0 entry fee, no exit fee deducted
    }

    @Test
    @DisplayName("calculate: a losing trade (exit below entry) is computed correctly, fees making the loss larger, not smaller")
    void losingTrade_feesIncreaseTheLoss() {
        var result = service.calculate(ENTRY_PRICE, new BigDecimal("49000"), FULL_QTY, FULL_QTY, ENTRY_FEE, EXIT_FEE);

        assertThat(result.realizedPnl()).isEqualByComparingTo("-1010.1"); // -1000 - 5.0 - 5.1
    }

    @Test
    @DisplayName("calculate: a currentQuantity of zero is handled gracefully -- no division by zero, no entry-fee deduction attempted, since there's nothing to prorate against")
    void zeroCurrentQuantity_noDeductionAttempted() {
        var result = service.calculate(ENTRY_PRICE, EXIT_PRICE, BigDecimal.ZERO, BigDecimal.ZERO, ENTRY_FEE, null);

        assertThat(result.entryFeeShare()).isEqualByComparingTo("0");
        assertThat(result.remainingEntryFeeQuote()).isEqualByComparingTo(ENTRY_FEE); // untouched, since nothing was actually deducted
    }
}
