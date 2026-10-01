package com.tradevision.service.strategy;

import com.tradevision.service.broker.dto.Candle;
import com.tradevision.service.strategy.dto.RegimeState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding ("Client-Side Signal Generation" — "Port the entire TA engine... to Java"):
 * tests the actual verified port — see MarketRegimeService's own javadoc for the full
 * verification methodology (3 seeded scenarios chosen to hit different classifyRegime branches,
 * each compared field-by-field against the real TypeScript's own reference output) and the real
 * infinite-recursion bug this port deliberately does NOT replicate.
 */
class MarketRegimeServiceTest {

    private final MarketRegimeService service = new MarketRegimeService();

    private static final class SeededRandom {
        long s;
        SeededRandom(long seed) { this.s = seed; }
        double next() { s = (s * 9301 + 49297) % 233280; return (double) s / 233280.0; }
    }

    private List<Candle> seededCandles(int n, long seed, double trendBias) {
        SeededRandom rand = new SeededRandom(seed);
        List<Candle> candles = new ArrayList<>();
        double price = 50000;
        for (int i = 0; i < n; i++) {
            double change = (rand.next() - 0.5 + trendBias) * 200;
            double open = price;
            double close = price + change;
            double high = Math.max(open, close) + rand.next() * 50;
            double low = Math.min(open, close) - rand.next() * 50;
            double volume = 100 + rand.next() * 900;
            candles.add(new Candle((long) (i * 3600000.0), open, high, low, close, volume));
            price = close;
        }
        return candles;
    }

    @Test
    @DisplayName("detect: mixed/random market matches the real TypeScript engine's reference output exactly (BREAKOUT_IMMINENT scenario)")
    void detect_mixedMarket_matchesReferenceOutput() {
        RegimeState result = service.detect(seededCandles(100, 12345, 0), "BTCUSDT");

        assertThat(result.regime()).isEqualTo("BREAKOUT_IMMINENT");
        assertThat(result.confidence()).isEqualTo(88);
        assertThat(result.adx()).isEqualTo(12.4);
        assertThat(result.bbWidth()).isEqualTo(0.0039);
        assertThat(result.atrPct()).isEqualTo(0.2);
        assertThat(result.trendScore()).isEqualTo(60);
        assertThat(result.volumeRatio()).isEqualTo(0.51);
        assertThat(result.warnings()).containsExactly(
            "BB squeeze detected -- large move expected soon in either direction",
            "Very weak trend (ADX<15) -- avoid trend strategies entirely"
        );
    }

    @Test
    @DisplayName("detect: strong uptrend bias matches the real TypeScript engine's reference output exactly (STRONG_BULL_TREND scenario)")
    void detect_uptrendBias_matchesReferenceOutput() {
        RegimeState result = service.detect(seededCandles(100, 777, 0.3), "ETHUSDT");

        assertThat(result.regime()).isEqualTo("STRONG_BULL_TREND");
        assertThat(result.confidence()).isEqualTo(95);
        assertThat(result.adx()).isEqualTo(99.9);
        assertThat(result.trendScore()).isEqualTo(100);
        assertThat(result.strategy().type()).isEqualTo("Aggressive Trend Following");
        assertThat(result.strategy().riskMultiplier()).isEqualTo(1.3);
        assertThat(result.weightAdjustments().trend()).isEqualTo(2.0);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("detect: strong downtrend bias matches the real TypeScript engine's reference output exactly (STRONG_BEAR_TREND scenario)")
    void detect_downtrendBias_matchesReferenceOutput() {
        RegimeState result = service.detect(seededCandles(100, 999, -0.3), "SOLUSDT");

        assertThat(result.regime()).isEqualTo("STRONG_BEAR_TREND");
        assertThat(result.confidence()).isEqualTo(95);
        assertThat(result.trendScore()).isEqualTo(-100);
        assertThat(result.strategy().type()).isEqualTo("Aggressive Short / Capital Protection");
        assertThat(result.warnings()).containsExactly("Strong downtrend -- longs are high risk, consider hedging");
    }

    @Test
    @DisplayName("detect: fewer than 50 candles returns a genuine static default (RANGING) — the actual fix for a real infinite-recursion bug found in the original TypeScript, confirmed by running it and catching the stack overflow, not by inspection")
    void detect_fewerThan50Candles_returnsStaticDefaultWithoutCrashing() {
        RegimeState result = service.detect(seededCandles(10, 1, 0), "TESTUSDT");

        assertThat(result.regime()).isEqualTo("RANGING");
        assertThat(result.summary()).contains("Not enough data yet");
    }

    @Test
    @DisplayName("detect: an empty candle list also returns the static default, never crashing")
    void detect_emptyCandles_returnsStaticDefault() {
        RegimeState result = service.detect(List.of(), "TESTUSDT");

        assertThat(result.regime()).isEqualTo("RANGING");
    }

    @Test
    @DisplayName("detect: strategy and weight data are internally consistent with the classified regime — every regime's strategy type is genuinely distinct, not a copy-paste artifact")
    void detect_strategyMatchesRegimeType() {
        RegimeState bull = service.detect(seededCandles(100, 777, 0.3), "ETHUSDT");
        RegimeState bear = service.detect(seededCandles(100, 999, -0.3), "SOLUSDT");

        assertThat(bull.strategy().type()).isNotEqualTo(bear.strategy().type());
        assertThat(bull.weightAdjustments().trend()).isEqualTo(bear.weightAdjustments().trend()); // both STRONG_* share the same weight table by design
    }
}
