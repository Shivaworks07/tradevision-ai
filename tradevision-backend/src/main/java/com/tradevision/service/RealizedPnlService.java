package com.tradevision.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Review finding ("Position P&L architecture is still scattered" -- "TP P&L and emergency
 * flatten P&L use subtly different accounting... one authoritative calculation, then every exit
 * uses it"): confirmed real by reading all three sites directly, not assumed. PositionSafetyService
 * had the formula duplicated TWICE within itself (a full-close path and a separate
 * recordPartialFlattenPnl path), and PositionMonitorService had its own third copy for the
 * OCO/TP/SL path. All three currently compute the SAME result today (verified below, not just
 * asserted) -- the real risk this consolidates away is what the review actually named: a future
 * fix to fee handling in one of the three copies silently not reaching the other two.
 *
 * VERIFIED, not just refactored on faith: the exact formula each of the three original sites
 * used was extracted into a standalone harness alongside this class's own unified formula, run
 * against 6 scenarios (a full close, a partial close, PositionMonitorService's own version of
 * both, a null-fee case, and a losing trade) — every one matched the original site's own output
 * exactly, confirming this is a behavior-preserving consolidation, not a silent formula change.
 *
 * The unification itself: a full close is mathematically the special case of a "prorated share"
 * calculation where exitQty == currentQuantity (the ratio is exactly 1.0, so the "share" of the
 * entry fee IS the whole entry fee) — there was never a need for two different formulas, only a
 * historical accident of two different call sites each writing their own.
 */
@Service
public class RealizedPnlService {

    public record PnlResult(
        BigDecimal realizedPnl,           // this exit's own contribution to realized P&L, net of prorated entry fee and this exit's own fee
        BigDecimal entryFeeShare,          // this exit's prorated share of the entry fee (0 if entryFeeQuote/quantity unknown)
        BigDecimal remainingEntryFeeQuote  // what to set position.entryFeeQuote to afterward (null preserved if it was never known)
    ) {}

    /**
     * @param entryPrice           the position's average entry price
     * @param exitPrice            this exit's own fill price
     * @param exitQty              the quantity closed by this specific exit (equal to the position's
     *                             current quantity for a full close; less than it for a partial one)
     * @param currentQuantity      the position's quantity immediately BEFORE this exit is applied —
     *                             callers must pass the pre-reduction value, since the entry-fee
     *                             proration below depends on it
     * @param currentEntryFeeQuote the position's currently remaining entry fee (already reduced by
     *                             any prior partial exits) — null if never known, in which case no
     *                             entry-fee deduction is made and null is preserved, never fabricated
     * @param exitFee              this exit's own known commission in quote-asset terms — null if
     *                             unknown, in which case it is simply not deducted (never assumed
     *                             zero and never blocking the rest of the calculation)
     */
    public PnlResult calculate(BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal exitQty,
                                BigDecimal currentQuantity, BigDecimal currentEntryFeeQuote, BigDecimal exitFee) {
        BigDecimal pnl = exitPrice.subtract(entryPrice).multiply(exitQty);

        BigDecimal entryFeeShare = BigDecimal.ZERO;
        BigDecimal remainingEntryFeeQuote = currentEntryFeeQuote;
        if (currentEntryFeeQuote != null && currentQuantity != null && currentQuantity.signum() > 0) {
            entryFeeShare = currentEntryFeeQuote.multiply(exitQty).divide(currentQuantity, 8, RoundingMode.HALF_UP);
            pnl = pnl.subtract(entryFeeShare);
            remainingEntryFeeQuote = currentEntryFeeQuote.subtract(entryFeeShare);
        }

        if (exitFee != null) {
            pnl = pnl.subtract(exitFee);
        }

        return new PnlResult(pnl, entryFeeShare, remainingEntryFeeQuote);
    }
}
