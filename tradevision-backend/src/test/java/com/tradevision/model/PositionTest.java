package com.tradevision.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding ("Fee accounting has no feeStatus (COMPLETE/PARTIAL/UNKNOWN); net P&L can look
 * exact when fees are actually partially unknown" -- P1, full context in Position.getFeeStatus's
 * own javadoc): verifies the actual fix. No test file existed for this model at all before this.
 */
class PositionTest {

    private Position position(String status, BigDecimal entryFee, BigDecimal exitFee) {
        Position p = new Position();
        p.setStatus(status);
        p.setEntryFeeQuote(entryFee);
        p.setExitFeeQuote(exitFee);
        return p;
    }

    @Test
    @DisplayName("getFeeStatus: an OPEN position with a known entry fee is COMPLETE -- exitFeeQuote isn't applicable yet, so entry-known is as complete as an open position can be")
    void openPosition_entryFeeKnown_isComplete() {
        assertThat(position("OPEN", BigDecimal.valueOf(1.5), null).getFeeStatus()).isEqualTo(Position.FeeStatus.COMPLETE);
    }

    @Test
    @DisplayName("getFeeStatus: an OPEN position with an unknown entry fee is UNKNOWN")
    void openPosition_entryFeeUnknown_isUnknown() {
        assertThat(position("OPEN", null, null).getFeeStatus()).isEqualTo(Position.FeeStatus.UNKNOWN);
    }

    @Test
    @DisplayName("getFeeStatus: a CLOSED position with both fees known is COMPLETE")
    void closedPosition_bothFeesKnown_isComplete() {
        assertThat(position("CLOSED", BigDecimal.valueOf(1.5), BigDecimal.valueOf(1.6)).getFeeStatus()).isEqualTo(Position.FeeStatus.COMPLETE);
    }

    @Test
    @DisplayName("getFeeStatus: a CLOSED position with only the entry fee known is PARTIAL -- the actual review scenario, a net P&L that would otherwise look exact")
    void closedPosition_onlyEntryFeeKnown_isPartial() {
        assertThat(position("CLOSED", BigDecimal.valueOf(1.5), null).getFeeStatus()).isEqualTo(Position.FeeStatus.PARTIAL);
    }

    @Test
    @DisplayName("getFeeStatus: a CLOSED position with only the exit fee known is PARTIAL too -- symmetric with the entry-only case")
    void closedPosition_onlyExitFeeKnown_isPartial() {
        assertThat(position("CLOSED", null, BigDecimal.valueOf(1.6)).getFeeStatus()).isEqualTo(Position.FeeStatus.PARTIAL);
    }

    @Test
    @DisplayName("getFeeStatus: a CLOSED position with neither fee known is UNKNOWN")
    void closedPosition_neitherFeeKnown_isUnknown() {
        assertThat(position("CLOSED", null, null).getFeeStatus()).isEqualTo(Position.FeeStatus.UNKNOWN);
    }

    @Test
    @DisplayName("getFeeStatus: NAKED_FLATTENED and CLOSED_UNVERIFIED_PNL are both treated as exited, same as CLOSED -- every real terminal status this codebase uses, confirmed directly against every position.setStatus(...) call site")
    void otherTerminalStatuses_treatedAsExited() {
        assertThat(position("NAKED_FLATTENED", BigDecimal.valueOf(1.5), null).getFeeStatus()).isEqualTo(Position.FeeStatus.PARTIAL);
        assertThat(position("CLOSED_UNVERIFIED_PNL", BigDecimal.valueOf(1.5), null).getFeeStatus()).isEqualTo(Position.FeeStatus.PARTIAL);
    }
}
