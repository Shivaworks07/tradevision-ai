package com.tradevision.service.strategy.dto;

/**
 * A single higher-timeframe reading used for multi-timeframe (MTF) alignment: trend direction,
 * RSI, MACD bias, and position relative to EMA50/EMA200, condensed into one alignment score.
 * See SignalCombinerService.buildMTFContext for how these are computed.
 */
public record MTFContext(String tf, String trend, double rsi, boolean macdBull, boolean aboveEma50, boolean aboveEma200, double score) {}
