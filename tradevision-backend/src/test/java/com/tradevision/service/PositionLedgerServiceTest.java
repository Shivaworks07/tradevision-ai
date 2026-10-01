package com.tradevision.service;

import com.tradevision.model.FillRecord;
import com.tradevision.repository.FillRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Review finding ("Position Ledger is still not authoritative" -- full context in
 * PositionLedgerService's own javadoc): no test file existed for this class at all before this
 * pass, despite it now being where every position-close and entry-time reconciliation check in
 * this codebase gets its answer from. Covers the reconstruction math itself and the
 * ReconcileResult contract its callers (AutoTradeService, PositionMonitorService,
 * PositionSafetyService) now depend on to decide whether to escalate.
 */
@ExtendWith(MockitoExtension.class)
class PositionLedgerServiceTest {

    @Mock FillRecordRepository fillRecordRepo;

    @InjectMocks PositionLedgerService service;

    private FillRecord fill(String side, double qty) {
        // Review finding (critical, discovered while extending this test file for
        // reconstructTotalFees -- full context in PositionLedgerService's own new method
        // javadoc): FillRecord has had NO setters since this session's own earlier immutability
        // fix (@Data replaced with @Getter-only, see FillRecord's own javadoc) except setId --
        // this helper's own f.setSide()/f.setQuantity() calls do not exist on the class at all,
        // meaning this ENTIRE TEST FILE could not compile, which would break the WHOLE backend
        // test suite's compilation (one file's compile error blocks every other test file too).
        // Fixed to use the all-args constructor instead, matching every other fixture in this
        // codebase written since the immutability fix (SlippageMetricsServiceTest's own order()
        // helper, etc.).
        return new FillRecord(null, null, null, null, null, null, null,
            null, null, null, side,
            null, BigDecimal.valueOf(qty),
            null, null, null,
            null, null, false);
    }

    @Test
    @DisplayName("reconstructNetQuantity: BUY fills add, SELL fills subtract, netting to the real remaining quantity")
    void reconstructNetQuantity_buySellNetsCorrectly() {
        when(fillRecordRepo.findByOrderId("order1")).thenReturn(
            List.of(fill("BUY", 1.0), fill("SELL", 0.3)));

        BigDecimal net = service.reconstructNetQuantity("order1");

        assertThat(net).isEqualByComparingTo("0.7");
    }

    @Test
    @DisplayName("reconstructNetQuantity: no fill records at all returns null, not zero -- distinguishing 'genuinely zero net fills' from 'nothing in the ledger to reconstruct from'")
    void reconstructNetQuantity_noRecords_returnsNullNotZero() {
        when(fillRecordRepo.findByOrderId("order1")).thenReturn(List.of());

        assertThat(service.reconstructNetQuantity("order1")).isNull();
    }

    @Test
    @DisplayName("reconstructPosition: a fully round-tripped position (bought then fully sold) nets to zero across its entire multi-order fill history")
    void reconstructPosition_fullyClosedPosition_netsToZero() {
        when(fillRecordRepo.findByPositionIdOrderByExecutedAtAsc("pos1")).thenReturn(
            List.of(fill("BUY", 1.0), fill("SELL", 0.4), fill("SELL", 0.6)));

        assertThat(service.reconstructPosition("pos1")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("reconstructPosition: fill records with a null quantity are safely skipped rather than NPEing the whole reconstruction")
    void reconstructPosition_nullQuantityFill_safelySkipped() {
        // Constructed directly (not via the fill() helper + a setter afterward) -- FillRecord
        // has had no setters since this session's own earlier immutability fix except setId,
        // same reasoning as the fill() helper's own fix above.
        FillRecord nullQtyFill = new FillRecord(null, null, null, null, null, null, null,
            null, null, null, "BUY",
            null, null,
            null, null, null,
            null, null, false);
        when(fillRecordRepo.findByPositionIdOrderByExecutedAtAsc("pos1")).thenReturn(
            List.of(fill("BUY", 1.0), nullQtyFill));

        assertThat(service.reconstructPosition("pos1")).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("reconcileAgainstLedger: a genuine agreement between the ledger and the believed quantity reports matches=true")
    void reconcileAgainstLedger_agreement_matchesTrue() {
        when(fillRecordRepo.findByOrderId("order1")).thenReturn(List.of(fill("BUY", 1.0)));

        var result = service.reconcileAgainstLedger("order1", BigDecimal.valueOf(1.0));

        assertThat(result.matches()).isTrue();
        assertThat(result.ledgerQuantity()).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("reconcileAgainstLedger: a genuine disagreement between the ledger and the believed quantity reports matches=false, with both values preserved for the caller's own escalation")
    void reconcileAgainstLedger_disagreement_matchesFalse() {
        when(fillRecordRepo.findByOrderId("order1")).thenReturn(List.of(fill("BUY", 1.0)));

        var result = service.reconcileAgainstLedger("order1", BigDecimal.valueOf(0.5));

        assertThat(result.matches()).isFalse();
        assertThat(result.ledgerQuantity()).isEqualByComparingTo("1.0");
        assertThat(result.believedQuantity()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("reconcileAgainstLedger: no ledger data at all is now treated as NO_LEDGER_DATA, NOT a match -- the actual fix for the review's own follow-up (\"PositionLedgerService still treats 'no ledger data' as a match\"). matches() correctly returns false so every existing caller escalates without needing any change.")
    void reconcileAgainstLedger_noLedgerData_isNotAMatch() {
        when(fillRecordRepo.findByOrderId("order1")).thenReturn(List.of());

        var result = service.reconcileAgainstLedger("order1", BigDecimal.valueOf(1.0));

        assertThat(result.matches()).isFalse();
        assertThat(result.status()).isEqualTo(PositionLedgerService.ReconcileStatus.NO_LEDGER_DATA);
        assertThat(result.ledgerQuantity()).isNull();
    }

    @Test
    @DisplayName("reconcilePositionAgainstLedger: a fully-closed position's real ~0 net matches the expected BigDecimal.ZERO believed quantity")
    void reconcilePositionAgainstLedger_closedPosition_matchesZero() {
        when(fillRecordRepo.findByPositionIdOrderByExecutedAtAsc("pos1")).thenReturn(
            List.of(fill("BUY", 1.0), fill("SELL", 1.0)));

        var result = service.reconcilePositionAgainstLedger("pos1", BigDecimal.ZERO);

        assertThat(result.matches()).isTrue();
    }

    @Test
    @DisplayName("reconcilePositionAgainstLedger: a position believed fully closed but whose ledger still nets to a real remaining quantity is a genuine mismatch -- exactly the scenario the review's own escalation exists for")
    void reconcilePositionAgainstLedger_incompletelyClosedPosition_isMismatch() {
        when(fillRecordRepo.findByPositionIdOrderByExecutedAtAsc("pos1")).thenReturn(
            List.of(fill("BUY", 1.0), fill("SELL", 0.7))); // only 0.7 of 1.0 actually sold, per the ledger

        var result = service.reconcilePositionAgainstLedger("pos1", BigDecimal.ZERO);

        assertThat(result.matches()).isFalse();
        assertThat(result.ledgerQuantity()).isEqualByComparingTo("0.3");
    }

    // ── resolvedQuantity (review finding "Position Ledger is still not authoritative" -- full
    // context in ReconcileResult.resolvedQuantity's own javadoc) ────────────────

    @Test
    @DisplayName("resolvedQuantity: a genuine MISMATCH with a known-complete ledger recording resolves to the LEDGER's value -- the actual derivation, not just a louder warning")
    void resolvedQuantity_mismatchWithCompleteLedger_resolvesToLedgerValue() {
        when(fillRecordRepo.findByOrderId("order1")).thenReturn(List.of(fill("BUY", 1.0)));

        var result = service.reconcileAgainstLedger("order1", BigDecimal.valueOf(0.5));

        assertThat(result.resolvedQuantity(false)).isEqualByComparingTo("1.0"); // ledgerRecordingIncomplete=false
    }

    @Test
    @DisplayName("resolvedQuantity: a genuine MISMATCH where the ledger recording is itself known-incomplete resolves to the BELIEVED value instead -- trusting a partial reconstruction over the locally-computed figure would be trading one unverified number for a worse one")
    void resolvedQuantity_mismatchWithIncompleteLedger_resolvesToBelievedValue() {
        when(fillRecordRepo.findByOrderId("order1")).thenReturn(List.of(fill("BUY", 1.0)));

        var result = service.reconcileAgainstLedger("order1", BigDecimal.valueOf(0.5));

        assertThat(result.resolvedQuantity(true)).isEqualByComparingTo("0.5"); // ledgerRecordingIncomplete=true
    }

    @Test
    @DisplayName("resolvedQuantity: a genuine MATCH resolves to the believed value regardless of the incomplete flag -- there's nothing to resolve when they already agree")
    void resolvedQuantity_match_resolvesToBelievedValue() {
        when(fillRecordRepo.findByOrderId("order1")).thenReturn(List.of(fill("BUY", 1.0)));

        var result = service.reconcileAgainstLedger("order1", BigDecimal.valueOf(1.0));

        assertThat(result.resolvedQuantity(false)).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("resolvedQuantity: NO_LEDGER_DATA resolves to the believed value -- there is nothing in the ledger to derive FROM")
    void resolvedQuantity_noLedgerData_resolvesToBelievedValue() {
        when(fillRecordRepo.findByOrderId("order1")).thenReturn(List.of());

        var result = service.reconcileAgainstLedger("order1", BigDecimal.valueOf(1.0));

        assertThat(result.resolvedQuantity(false)).isEqualByComparingTo("1.0");
    }

    // ── reconstructTotalFees (review finding "Position P&L architecture is still scattered" --
    // continued, full context in the method's own javadoc) ────────────────

    private FillRecord fillWithQuoteCommission(double qty, Double quoteCommission) {
        return new FillRecord(null, null, null, null, null, null, null,
            null, null, null, "BUY",
            null, BigDecimal.valueOf(qty),
            null, null, quoteCommission == null ? null : BigDecimal.valueOf(quoteCommission),
            null, null, false);
    }

    @Test
    @DisplayName("reconstructTotalFees: sums quoteCommission across every fill for a position")
    void reconstructTotalFees_sumsAcrossFills() {
        when(fillRecordRepo.findByPositionIdOrderByExecutedAtAsc("pos1")).thenReturn(
            List.of(fillWithQuoteCommission(1.0, 1.5), fillWithQuoteCommission(1.0, 0.8)));

        assertThat(service.reconstructTotalFees("pos1")).isEqualByComparingTo("2.3");
    }

    @Test
    @DisplayName("reconstructTotalFees: a fill with no known quote-asset commission (e.g. a BNB-fee-discount trade) is skipped, not treated as zero")
    void reconstructTotalFees_unknownCommissionSkipped() {
        when(fillRecordRepo.findByPositionIdOrderByExecutedAtAsc("pos1")).thenReturn(
            List.of(fillWithQuoteCommission(1.0, 1.5), fillWithQuoteCommission(1.0, null)));

        assertThat(service.reconstructTotalFees("pos1")).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("reconstructTotalFees: no fills at all, or every fill's commission unknown, returns null -- distinguishes genuinely zero from nothing to reconstruct from, same rule as this class's own quantity methods")
    void reconstructTotalFees_noKnownData_returnsNull() {
        when(fillRecordRepo.findByPositionIdOrderByExecutedAtAsc("pos1")).thenReturn(
            List.of(fillWithQuoteCommission(1.0, null)));

        assertThat(service.reconstructTotalFees("pos1")).isNull();
    }
}
