package com.tradevision.model;

/**
 * A single, easy-to-reason-about summary of a credential's trading state, derived from the
 * underlying mode/autoTradeEnabled/liveAutoTradeAuthorized/tradingHalted flags that are spread
 * across BrokerCredential and RiskProfile. This is a derived read model, not a replacement for
 * those underlying fields — every consumer of those four flags still reads/writes them
 * directly; this exists purely to give a dashboard or caller one clear state to show instead of
 * reasoning through four booleans combined.
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
