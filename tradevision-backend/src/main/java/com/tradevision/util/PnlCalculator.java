package com.tradevision.util;

/**
 * Computes a closed position's P&L percentage and R-multiple, shared by every exit-recording
 * path (CallResultUpdater.updateResult and TradeCallService's exit recording) so the formula is
 * defined in exactly one place. pnlPct is rounded to 3 decimal places, pnlR to 2; when the
 * stop-loss distance is zero, pnlR defaults to 0.0 rather than dividing by zero. Uses double
 * arithmetic rather than BigDecimal, consistent with the rest of this percentage/ratio
 * calculation path.
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
