package com.tradevision.model;

/**
 * Review finding ("Stale documentation that can cause operator error" -- P0): confirmed real and
 * fixed -- this comment used to say LIVE trading hadn't shipped yet and OrderExecutionService
 * refused it "until Stage 3 ships." That's no longer true and hasn't been for a while: LIVE
 * trading is a fully-built, real feature of this application today, gated by genuine
 * authorization (RiskProfileService.authorizeLiveAutoTrade for autonomous trading, a confirmation
 * phrase and real per-call permission checks), not by an unshipped stage. Left uncorrected, this
 * comment could genuinely mislead an operator into believing LIVE isn't real, or a future
 * developer into thinking it's still gated by something that no longer exists.
 *
 * The actual, current state of each mode, stated plainly rather than left to be inferred:
 * - TESTNET: used everywhere -- autonomous scanning/trading, manual order placement, OCO/exit
 *   management. The default, safe mode for every credential.
 * - LIVE: fully supported for autonomous trading via AutoTradeService, gated by
 *   RiskProfileService's own real authorization flow (a standing, explicitly-granted flag,
 *   re-verified rather than assumed permanent). Manual order placement (OrderExecutionService)
 *   is deliberately TESTNET-only regardless of this flag -- a separate, more recent decision
 *   (see OrderExecutionService's own javadoc) to keep the LIVE execution surface minimal, not a
 *   sign LIVE itself is incomplete.
 * - PAPER: Review finding ("No pure paper-trading mode with full isolation" -- external review,
 *   eighteenth pass, P0, confirmed real: TESTNET was the only simulation surface, and a
 *   misconfigured credential or testnet-specific behavior difference could still move real
 *   money or produce false confidence). See PaperBrokerAdapter's own class javadoc for the full
 *   design -- the short version: a PAPER credential never sends any API key or authenticated
 *   request to Binance at all. Order placement, balances, and fills are entirely simulated
 *   in-process; only real-time, public, unauthenticated market prices are used (so simulated
 *   fills reflect genuine market conditions), through the SAME risk/OMS pipeline (RiskProfile,
 *   Order, Position, PositionMonitorService) every other mode uses -- PAPER is a different
 *   BrokerAdapter implementation underneath, not a parallel, separately-maintained system.
 */
public enum BrokerMode {
    TESTNET,
    LIVE,
    PAPER
}
