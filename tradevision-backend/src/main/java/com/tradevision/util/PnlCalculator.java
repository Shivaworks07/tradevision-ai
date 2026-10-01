package com.tradevision.util;

/**
 * Review finding ("Financial model still mixes double and BigDecimal" -- external review,
 * sixteenth pass, P2, discovered while investigating that same finding, not a separate report):
 * this exact P&L%/R-multiple formula was duplicated verbatim in two places --
 * CallResultUpdater.updateResult and TradeCallService's own exit-recording path -- confirmed
 * identical by direct textual comparison before extracting, not assumed. Pure mechanical
 * extraction, zero semantic change: same double arithmetic, same rounding (3 decimal places for
 * pnlPct, 2 for pnlR), same "stop-loss distance is zero" edge case (pnlR defaults to 0.0 rather
 * than dividing by zero). Deliberately still double, not BigDecimal -- the broader
 * double-vs-BigDecimal question this same review raised is a separate, larger, higher-risk
 * change (see this session's own conversation history for why that one was deliberately NOT
 * attempted alongside this consolidation) that touches live percentage/ratio arithmetic across
 * several more files and needs its own dedicated, compiler-verified pass.
 */
public final class PnlCalculator {

    private PnlCalculator() {}

    public record PnlResult(double pnlPct, double pnlR) {}

    /**
     * @param entryPrice the position's own entry price
     * @param exitPrice  the price the position resolved at (SL/TP hit, or a manual exit)
     * @param stopLoss   the position's own stop-loss price, used only to derive the R-multiple's
     *                   own denominator (the SL distance) -- unrelated to whether exitPrice
     *                   itself was the SL being hit
     * @param isLong     LONG vs SHORT direction, since the sign of a favorable move flips
     */
    public static PnlResult compute(double entryPrice, double exitPrice, double stopLoss, boolean isLong) {
        double pnlPct = isLong
            ? (exitPrice - entryPrice) / entryPrice * 100
            : (entryPrice - exitPrice) / entryPrice * 100;
        double roundedPnlPct = Math.round(pnlPct * 1000.0) / 1000.0;
        double slDistance = Math.abs(entryPrice - stopLoss);
        double pnlR = slDistance > 0 ? Math.round((pnlPct / (slDistance / entryPrice * 100)) * 100.0) / 100.0 : 0.0;
        return new PnlResult(roundedPnlPct, pnlR);
    }
}
