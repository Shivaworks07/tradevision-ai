package com.tradevision.model;

/**
 * The trading environment a broker credential operates against.
 *
 * - TESTNET: used everywhere -- autonomous scanning/trading, manual order placement, OCO/exit
 *   management. The default, safe mode for every credential.
 * - LIVE: fully supported for autonomous trading via AutoTradeService, gated by
 *   RiskProfileService's own authorization flow (a standing, explicitly-granted flag,
 *   re-verified rather than assumed permanent). Manual order placement (OrderExecutionService)
 *   is deliberately TESTNET-only regardless of this flag, keeping the LIVE execution surface
 *   minimal.
 * - PAPER: a fully isolated simulation mode. See PaperBrokerAdapter's own class javadoc for the
 *   full design -- the short version: a PAPER credential never sends any API key or authenticated
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
