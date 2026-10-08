package com.tradevision.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Single authoritative realized-P&L calculation shared by every exit path (full close,
 * partial close, and OCO/TP/SL flattening). Centralizing this here means a future change to
 * fee handling only has to be made in one place instead of being kept in sync across every
 * call site that closes a position.
 *
 * A full close is mathematically just the special case of a "prorated share" calculation
 * where exitQty equals currentQuantity (the ratio is exactly 1.0, so the "share" of the entry
 * fee is the whole entry fee) — so one formula covers both full and partial exits without
 * needing to branch on which kind of exit it is.
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
