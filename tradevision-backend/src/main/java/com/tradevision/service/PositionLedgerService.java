package com.tradevision.service;

import com.tradevision.model.FillRecord;
import com.tradevision.repository.FillRecordRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Treats the fill ledger (FillRecord) as the source of truth for position quantity: rather than
 * trusting whatever service last called Position.setQuantity(...), this reconstructs the real
 * net quantity from the fills themselves, so "where did this quantity come from" always has a
 * traceable answer. The flow is Fills -> Position Ledger -> Current Position.
 *
 * This works in three layers:
 *   1. Reconstructability -- given an order or a position, compute what the fill ledger says its
 *      real net quantity is, independent of whatever Position.quantity currently holds.
 *   2. Cross-checking with real consequences -- a genuine disagreement between the ledger and
 *      the believed quantity raises a CRITICAL incident and halts the profile, at every
 *      quantity-setting call site (entry, OCO placement/close, emergency-flatten, late-fill).
 *   3. Authoritative derivation -- resolvedQuantity() gives callers the ledger's own value to use
 *      as the position's real quantity whenever there's a genuine mismatch and the ledger
 *      recording for this position is known-complete (not flagged
 *      Position.ledgerRecordingIncomplete). When the ledger disagrees with a locally-computed
 *      figure and there's no known reason to distrust the ledger, the ledger wins and the
 *      position is saved with its number -- the escalation (halt + incident) still fires
 *      regardless, since a mismatch is worth investigating even after being corrected, as it
 *      usually points at a bug in whatever produced the locally-computed figure.
 *
 * The one case this deliberately does not auto-correct: a mismatch where the ledger recording
 * is itself known-incomplete (Position.ledgerRecordingIncomplete = true). There, the ledger's
 * reconstruction is built from a known-partial fill history, so trusting it over the
 * locally-computed figure would be trading one unverified number for a worse one. That case
 * still halts and raises an incident, but keeps the locally-computed quantity rather than
 * overwriting it with a number known to be built from incomplete data.
 *
 * NO_LEDGER_DATA (see ReconcileStatus below) is deliberately never treated as a match. Every
 * call site in this codebase runs this check immediately after fillLedgerService.recordFills()
 * for the same order/position, so a position reaching these checks should always have ledger
 * data -- a position with zero fill records despite a confirmed exchange-side fill is a real
 * discrepancy and must be treated exactly as seriously as a genuine quantity mismatch.
 */
@Service
@RequiredArgsConstructor
public class PositionLedgerService {

    private static final Logger log = LoggerFactory.getLogger(PositionLedgerService.class);

    private final FillRecordRepository fillRecordRepo;

    /**
     * Sums every FillRecord for the given order — BUY fills add, SELL fills subtract — giving
     * the net quantity the ledger believes this order actually contributed. Returns null (not
     * zero) when there are no fill records at all, so a caller can distinguish "genuinely zero
     * net fills" from "nothing in the ledger to reconstruct from".
     */
    public BigDecimal reconstructNetQuantity(String orderId) {
        List<FillRecord> fills = fillRecordRepo.findByOrderId(orderId);
        return sumFills(fills);
    }

    /**
     * Sums EVERY fill across a position's entire history (its one entry order and however many
     * exit orders eventually closed it), using positionId as the cross-reference every fill type
     * shares. A fully closed position's fills should net to approximately zero; a still-open
     * position's net is its current real quantity. Null (not zero) when there's no ledger data
     * at all — same "distinguish genuinely zero from nothing to reconstruct from" rule.
     */
    public BigDecimal reconstructPosition(String positionId) {
        return reconstructPosition(positionId, null);
    }

    /**
     * Nets out base-asset commission here, with the same rule PositionSafetyService.
     * computeNetQuantity already uses for the entry side -- a BUY fill's contribution is reduced
     * by its own commission when (and only when) that fill's commission was paid in the base
     * asset itself. Binance's default BUY commission asset is the base asset unless a BNB fee
     * discount is enabled, so a raw, gross sum of fill quantities would otherwise disagree with
     * the fee-adjusted "believed" quantity every caller compares against, even for a genuinely
     * correct position. A SELL's proceeds are paid in the quote asset, so a SELL's commission is
     * never denominated in the base asset in practice and never reduces the base-asset quantity
     * sold -- only BUY fills are adjusted, matching computeNetQuantity's own scope exactly.
     * baseAsset is optional: when a call site genuinely cannot resolve it, this falls back to
     * the commission-unaware sum rather than guessing which asset is "base" here.
     */
    public BigDecimal reconstructPosition(String positionId, String baseAsset) {
        List<FillRecord> fills = fillRecordRepo.findByPositionIdOrderByExecutedAtAsc(positionId);
        return sumFills(fills, baseAsset);
    }

    private BigDecimal sumFills(List<FillRecord> fills) {
        return sumFills(fills, null);
    }

    private BigDecimal sumFills(List<FillRecord> fills, String baseAsset) {
        if (fills.isEmpty()) return null;
        BigDecimal net = BigDecimal.ZERO;
        for (FillRecord f : fills) {
            if (f.getQuantity() == null) continue;
            if ("SELL".equalsIgnoreCase(f.getSide())) {
                net = net.subtract(f.getQuantity());
            } else {
                BigDecimal qty = f.getQuantity();
                if (baseAsset != null && f.getCommissionAmount() != null && f.getCommissionAsset() != null
                        && f.getCommissionAsset().equalsIgnoreCase(baseAsset)) {
                    qty = qty.subtract(f.getCommissionAmount());
                }
                net = net.add(qty);
            }
        }
        return net;
    }

    /**
     * Extends the "derive from the fill ledger" approach used for quantity to fees: sums
     * quoteCommission (only ever set when the commission was genuinely paid in the quote asset)
     * across every fill this position has, giving an independent, ledger-derived total that can
     * be cross-checked against the locally-computed entryFeeQuote+exitFeeQuote.
     *
     * Scope: this does not itself replace RealizedPnlService's own calculation the way
     * Position.quantity is overridden by the ledger on a mismatch (see
     * PositionLedgerService.ReconcileResult.resolvedQuantity). Doing the equivalent for P&L --
     * recomputing and overriding realizedPnlQuote from ledger-derived fees on a mismatch -- would
     * mean re-running RealizedPnlService's own formula with the ledger's fee figure at the same
     * reconciliation call sites quantity already uses. This method exists so that check can be
     * built on top of it without first having to write the ledger-side aggregation.
     *
     * Returns null (not zero) when there's no known quote-asset fee data across every fill --
     * either no fills at all, or every fill's commission was paid in a different asset (a
     * BNB-fee-discount trade, say) -- the same "distinguish genuinely zero from nothing to
     * reconstruct from" rule the quantity methods above use.
     */
    public BigDecimal reconstructTotalFees(String positionId) {
        List<FillRecord> fills = fillRecordRepo.findByPositionIdOrderByExecutedAtAsc(positionId);
        if (fills.isEmpty()) return null;
        BigDecimal total = null;
        for (FillRecord f : fills) {
            if (f.getQuoteCommission() == null) continue;
            total = (total == null ? BigDecimal.ZERO : total).add(f.getQuoteCommission());
        }
        return total;
    }

    /**
     * Three real outcomes, not two: MATCH and MISMATCH alone cannot distinguish "the ledger
     * agrees" from "there is no ledger data to agree or disagree with", and the two cases call
     * for the same escalation, not for treating absence of data as agreement.
     */
    public enum ReconcileStatus { MATCH, MISMATCH, NO_LEDGER_DATA }

    /**
     * matches() is kept as a derived convenience -- true only for MATCH -- so every existing
     * caller written against the original boolean-shaped contract (if (!result.matches())
     * escalate) automatically treats NO_LEDGER_DATA exactly as seriously as MISMATCH, with zero
     * changes needed at any call site. New code that needs to distinguish the two reasons a
     * result didn\'t match should read status() directly.
     */
    public record ReconcileResult(ReconcileStatus status, BigDecimal ledgerQuantity, BigDecimal believedQuantity) {
        public boolean matches() { return status == ReconcileStatus.MATCH; }

        /**
         * Returns the ledger's own value when this is a genuine MISMATCH and the ledger
         * recording for this position is known-complete (ledgerRecordingIncomplete=false) -- the
         * ledger wins over a locally-computed figure it disagrees with. Returns believedQuantity
         * in every other case: a MATCH (nothing to resolve), NO_LEDGER_DATA (nothing to derive
         * from), or a MISMATCH where the ledger itself is known-incomplete (trusting a partial
         * reconstruction over the locally-computed figure would be trading one unverified number
         * for a worse one).
         */
        public BigDecimal resolvedQuantity(boolean ledgerRecordingIncomplete) {
            if (status == ReconcileStatus.MISMATCH && !ledgerRecordingIncomplete && ledgerQuantity != null) {
                return ledgerQuantity;
            }
            return believedQuantity;
        }
    }

    /**
     * Cross-check, not a silent correction: compares the ledger\'s reconstructed quantity for
     * this order against whatever quantity the caller currently believes is correct. Returns the
     * full comparison so the caller (which has the richer context -- credential, profile,
     * symbol, and now whether ITS OWN ledger recording is known-complete -- this class
     * deliberately doesn\'t hold any of that) can decide how to escalate, and can call
     * resolvedQuantity() to get the actual value to use going forward.
     */
    public ReconcileResult reconcileAgainstLedger(String orderId, BigDecimal believedQuantity) {
        return reconcile(reconstructNetQuantity(orderId), believedQuantity, "order", orderId);
    }

    /**
     * Same cross-check as reconcileAgainstLedger, but scoped to a whole position's fill history
     * (via reconstructPosition) rather than a single order. The natural expected value for a
     * just-closed position is BigDecimal.ZERO (everything bought was eventually sold).
     *
     * baseAsset, when the caller can resolve it, lets reconstructPosition net out base-asset
     * commission on the BUY side the same way the caller's own "believed" figure already does --
     * without it, this comparison would flag a real position as a mismatch purely because one
     * side of the comparison is fee-adjusted and the other isn't, for every position that paid
     * any base-asset commission at all.
     */
    public ReconcileResult reconcilePositionAgainstLedger(String positionId, BigDecimal believedQuantity, String baseAsset) {
        return reconcile(reconstructPosition(positionId, baseAsset), believedQuantity, "position", positionId);
    }

    /** Back-compat overload for call sites that genuinely cannot resolve a base asset (same
     *  honest "don't guess" fallback reconstructPosition(String, String) documents). */
    public ReconcileResult reconcilePositionAgainstLedger(String positionId, BigDecimal believedQuantity) {
        return reconcilePositionAgainstLedger(positionId, believedQuantity, null);
    }

    private ReconcileResult reconcile(BigDecimal ledgerQuantity, BigDecimal believedQuantity, String kind, String id) {
        if (ledgerQuantity == null) {
            log.warn("No fill ledger data for {} {} despite this check running immediately after a fill was recorded — "
                + "treated as NO_LEDGER_DATA, not a match. This is a real discrepancy worth investigating.", kind, id);
            return new ReconcileResult(ReconcileStatus.NO_LEDGER_DATA, null, believedQuantity);
        }
        if (believedQuantity == null || ledgerQuantity.subtract(believedQuantity).abs().compareTo(new BigDecimal("0.00000001")) > 0) {
            log.warn("Fill ledger mismatch for {} {}: ledger reconstructs {}, but the caller believes {}. "
                + "This is a real discrepancy worth investigating.", kind, id, ledgerQuantity, believedQuantity);
            return new ReconcileResult(ReconcileStatus.MISMATCH, ledgerQuantity, believedQuantity);
        }
        return new ReconcileResult(ReconcileStatus.MATCH, ledgerQuantity, believedQuantity);
    }
}
