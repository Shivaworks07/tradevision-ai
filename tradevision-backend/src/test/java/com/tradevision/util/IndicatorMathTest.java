package com.tradevision.util;

import com.tradevision.service.broker.dto.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review item #26: these formulas are the server-side cross-check against whatever the browser
 * claims a signal's RSI/ATR are (review item #2's hardening) — if the math itself is wrong, that
 * whole defense is worthless. Uses reference cases whose correct answer can be verified by hand
 * from the algorithm definition, not just "does it run".
 */
class IndicatorMathTest {

    private List<Candle> candlesFromCloses(double[] closes) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            candles.add(new Candle(i, closes[i], closes[i] + 5, closes[i] - 5, closes[i], 1000));
        }
        return candles;
    }

    @Test
    @DisplayName("wilderRsi: an all-gains series (every close higher than the last) is exactly 100 — avgLoss is 0")
    void wilderRsi_allGains_is100() {
        double[] closes = new double[20];
        for (int i = 0; i < closes.length; i++) closes[i] = 100 + i; // strictly increasing
        assertThat(IndicatorMath.wilderRsi(candlesFromCloses(closes), 14)).isEqualTo(100.0);
    }

    @Test
    @DisplayName("wilderRsi: an all-losses series (every close lower than the last) is exactly 0 — avgGain is 0")
    void wilderRsi_allLosses_is0() {
        double[] closes = new double[20];
        for (int i = 0; i < closes.length; i++) closes[i] = 200 - i; // strictly decreasing
        assertThat(IndicatorMath.wilderRsi(candlesFromCloses(closes), 14)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("wilderRsi: too little history (<= period+1 candles) returns the neutral default of 50")
    void wilderRsi_insufficientHistory_returns50() {
        double[] closes = {100, 101, 102};
        assertThat(IndicatorMath.wilderRsi(candlesFromCloses(closes), 14)).isEqualTo(50.0);
    }

    @Test
    @DisplayName("atr: constant true range (fixed high-low band, no gaps) equals that constant exactly")
    void atr_constantRange_equalsThatRange() {
        List<Candle> candles = new ArrayList<>();
        // open/close = 100 every candle, high = 105, low = 95 → true range = 10 every time,
        // and |high-prevClose| = |low-prevClose| = 5 < 10, so max() always picks h-lo.
        for (int i = 0; i < 20; i++) {
            candles.add(new Candle(i, 100, 105, 95, 100, 1000));
        }
        assertThat(IndicatorMath.atr(candles, 14)).isEqualTo(10.0);
    }

    @Test
    @DisplayName("atr: fewer candles than the period returns 0 rather than throwing")
    void atr_insufficientHistory_returnsZero() {
        List<Candle> candles = candlesFromCloses(new double[]{100, 101, 102});
        assertThat(IndicatorMath.atr(candles, 14)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("ema: on a perfectly flat price series, EMA converges to exactly that flat price")
    void ema_flatSeries_convergesToThatPrice() {
        double[] closes = new double[60];
        java.util.Arrays.fill(closes, 100.0);
        assertThat(IndicatorMath.ema(candlesFromCloses(closes), 50)).isEqualTo(100.0);
    }

    @Test
    @DisplayName("ema: a price ramp above a flat base is reflected upward, but stays below the latest close (EMA lags)")
    void ema_risingSeries_laggingBelowLatestClose() {
        double[] closes = new double[60];
        for (int i = 0; i < 40; i++) closes[i] = 100;      // flat base
        for (int i = 40; i < 60; i++) closes[i] = 100 + (i - 39) * 5; // then a sharp ramp up
        List<Candle> candles = candlesFromCloses(closes);
        double ema = IndicatorMath.ema(candles, 50);
        double latestClose = closes[closes.length - 1];
        assertThat(ema).isGreaterThan(100.0); // pulled up by the ramp
        assertThat(ema).isLessThan(latestClose); // but an EMA always lags a sharp recent move
    }
}
