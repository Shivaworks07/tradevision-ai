package com.tradevision.model;

/**
 * Review finding (P1 #18 — "Trading state is still represented by multiple booleans"):
 * confirmed real — mode, autoTradeEnabled, liveAutoTradeAuthorized, tradingHalted (spread across
 * two different model classes) allow combinations that are hard to reason about directly.
 *
 * Honest scope: this is a DERIVED read model, not a replacement for the underlying booleans.
 * Refactoring every consumer of those four fields to read/write a single enum instead would be a
 * much larger, higher-regression-risk change across every service that currently checks them
 * directly — not something to do as a side effect of adding a UI-friendly summary. This gives
 * the review's own actual goal (an execution engine that's easier to reason about, a dashboard
 * that can show one clear state instead of four booleans) without that larger risk.
 */
public enum TradingState {
    TESTNET,                    // mode=TESTNET, not halted
    TESTNET_HALTED,              // mode=TESTNET, tradingHalted=true
    LIVE_MANUAL_ONLY,            // mode=LIVE, autoTradeEnabled=false (or liveAutoTradeAuthorized=false) — trades only via manual/UI actions
    LIVE_AUTHORIZING,            // mode=LIVE, autoTradeEnabled=true, liveAutoTradeAuthorized=false — waiting on the explicit live-autotrade ceremony
    LIVE_AUTO_TRADING,           // mode=LIVE, autoTradeEnabled=true, liveAutoTradeAuthorized=true, not halted
    LIVE_HALTED;                 // mode=LIVE, tradingHalted=true

    public static TradingState of(BrokerCredential credential, RiskProfile profile) {
        boolean live = credential.getMode() == BrokerMode.LIVE;
        boolean halted = profile != null && profile.isTradingHalted();

        if (!live) return halted ? TESTNET_HALTED : TESTNET;
        if (halted) return LIVE_HALTED;
        if (profile == null || !profile.isAutoTradeEnabled()) return LIVE_MANUAL_ONLY;
        if (!profile.isLiveAutoTradeAuthorized()) return LIVE_AUTHORIZING;
        return LIVE_AUTO_TRADING;
    }
}
