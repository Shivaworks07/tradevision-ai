package com.tradevision.model.features;

import lombok.Data;
import lombok.NoArgsConstructor;

/** RSI, MACD, EMA, BB, Stoch, ADX, Williams %R, OBV, Volume, MTF */
@Data @NoArgsConstructor
public class TechnicalFeatures {
    double  rsi;
    String  rsiZone;
    double  macdHistogram;
    boolean macdBull;
    double  adx;
    double  atrPct;
    String  trendEMA;
    String  bbSignal;
    double  stochK;
    double  stochD;
    double  williamsR;
    String  vwapSignal;
    String  obvSignal;
    double  volumeRatio;
    String  volumeSignal;
    boolean bullishDivergence;
    boolean bearishDivergence;
    String  mtfAlignment;
    String  htf1Trend;
    String  htf2Trend;
}
