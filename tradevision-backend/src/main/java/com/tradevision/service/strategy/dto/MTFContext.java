package com.tradevision.service.strategy.dto;

/**
 * Review finding ("Client-Side Signal Generation" / "Move this to Angular to Java"): mirrors the
 * real frontend's MTFContext interface — see SignalCombinerService's own javadoc.
 */
public record MTFContext(String tf, String trend, double rsi, boolean macdBull, boolean aboveEma50, boolean aboveEma200, double score) {}
