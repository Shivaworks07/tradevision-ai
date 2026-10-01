package com.tradevision.model;

/**
 * User's own explicit design: "TradeVision shouldn't generate a SHORT order on an account that
 * cannot actually short." This codebase's only current broker connections are Binance SPOT
 * (confirmed: BinanceBrokerAdapter calls /api/v3/order, the spot endpoint, not /fapi/v1/order),
 * where short-selling isn't possible without margin borrowing this codebase doesn't implement.
 * SHORT and BOTH exist in this enum because the user's own design calls for them once a futures/
 * margin connection type exists -- but StrategyPlanService.validateDirectionForMarket is the
 * actual enforcement point, called at plan creation/update time, not this enum alone. An enum
 * value existing is not the same as it being usable; see that method's own javadoc for why.
 */
public enum TradeDirection {
    LONG,
    SHORT,
    BOTH
}
