package com.tradevision.service.strategy;

import com.tradevision.service.broker.dto.Candle;
import com.tradevision.service.strategy.dto.SMCAnalysis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding ("Client-Side Signal Generation" — "Port the entire TA engine... to Java"):
 * tests the actual verified port — see SmcEngineService's own javadoc for the full verification
 * methodology (3 large seeded scenarios, each compared field-by-field against the real
 * TypeScript's own reference output) and the genuine cross-language floating-point display
 * formatting difference found and documented (never a logic difference) along the way.
 */
class SmcEngineServiceTest {

    private final SmcEngineService service = new SmcEngineService();

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
            double change = (rand.next() - 0.5 + trendBias) * 300;
            double open = price;
            double close = price + change;
            double high = Math.max(open, close) + rand.next() * 80;
            double low = Math.min(open, close) - rand.next() * 80;
            double volume = 100 + rand.next() * 900;
            candles.add(new Candle((long) (i * 3600000.0), open, high, low, close, volume));
            price = close;
        }
        return candles;
    }

    @Test
    @DisplayName("analyze: mixed market matches the real TypeScript engine's reference output exactly — bias, structure, and entry setup")
    void analyze_mixedMarket_matchesReferenceOutput() {
        SMCAnalysis result = service.analyze(seededCandles(150, 12345, 0), "BTCUSDT");

        assertThat(result.trend()).isEqualTo("BULL_TREND");
        assertThat(result.bias()).isEqualTo("BULLISH");
        assertThat(result.biasStrength()).isEqualTo(100);
        assertThat(result.orderBlocks()).hasSize(4);
        assertThat(result.fairValueGaps()).hasSize(3);
        assertThat(result.premiumDiscount().currentZone()).isEqualTo("PREMIUM");
        assertThat(result.premiumDiscount().fibLevel()).isEqualTo(0.707);
        assertThat(result.entrySetup()).isEqualTo("Potential LONG: At Bull OB (49926.94-49960.84). Wait for mitigation + bullish reaction.");
        assertThat(result.keyLevels().get(0).price()).isEqualTo(50295.69770233198);
    }

    @Test
    @DisplayName("analyze: strong uptrend produces BULLISH bias with a monotonic price series suppressing swing points and structure breaks — matches real reference output including the empty-list cases")
    void analyze_strongUptrend_matchesReferenceOutput() {
        SMCAnalysis result = service.analyze(seededCandles(150, 5555, 0.4), "ETHUSDT");

        assertThat(result.trend()).isEqualTo("BULL_TREND");
        assertThat(result.bias()).isEqualTo("BULLISH");
        assertThat(result.swingHighs()).isEmpty(); // a strongly monotonic trend has no clear swing reversals at lookback=5
        assertThat(result.structureBreaks()).isEmpty();
        assertThat(result.entrySetup()).isNull(); // no order block or FVG currently near price
    }

    @Test
    @DisplayName("analyze: strong downtrend matches the real TypeScript engine's reference output exactly")
    void analyze_strongDowntrend_matchesReferenceOutput() {
        SMCAnalysis result = service.analyze(seededCandles(150, 8888, -0.4), "SOLUSDT");

        assertThat(result.trend()).isEqualTo("BEAR_TREND");
        assertThat(result.bias()).isEqualTo("BEARISH");
        assertThat(result.biasStrength()).isEqualTo(100);
        assertThat(result.entrySetup()).isNull();
    }

    @Test
    @DisplayName("analyze: fewer than 50 candles returns the honest empty analysis without crashing")
    void analyze_fewerThan50Candles_returnsEmptyAnalysis() {
        SMCAnalysis result = service.analyze(seededCandles(10, 1, 0), "TESTUSDT");

        assertThat(result.trend()).isEqualTo("RANGING");
        assertThat(result.bias()).isEqualTo("NEUTRAL");
        assertThat(result.summary()).isEqualTo("Need 50+ candles for SMC analysis.");
        assertThat(result.orderBlocks()).isEmpty();
        assertThat(result.entrySetup()).isNull();
    }

    @Test
    @DisplayName("analyze: an empty candle list also returns the empty analysis, never crashing")
    void analyze_emptyCandles_returnsEmptyAnalysis() {
        SMCAnalysis result = service.analyze(List.of(), "TESTUSDT");

        assertThat(result.trend()).isEqualTo("RANGING");
    }

    @Test
    @DisplayName("analyze: order blocks are correctly directional — BULL order blocks in a bullish scenario, BEAR order blocks in a bearish one, never mixed up")
    void analyze_orderBlockDirectionsMatchScenario() {
        SMCAnalysis bullish = service.analyze(seededCandles(150, 5555, 0.4), "ETHUSDT");
        SMCAnalysis bearish = service.analyze(seededCandles(150, 8888, -0.4), "SOLUSDT");

        assertThat(bullish.orderBlocks()).allMatch(ob -> ob.direction().equals("BULL"));
        assertThat(bearish.orderBlocks()).allMatch(ob -> ob.direction().equals("BEAR"));
    }

    @Test
    @DisplayName("analyze: keyLevels are sorted by distance from current price, closest first")
    void analyze_keyLevelsSortedByDistance() {
        SMCAnalysis result = service.analyze(seededCandles(150, 12345, 0), "BTCUSDT");
        double price = seededCandles(150, 12345, 0).get(149).close();

        List<Double> distances = result.keyLevels().stream().map(k -> Math.abs(k.price() - price)).toList();
        for (int i = 1; i < distances.size(); i++) {
            assertThat(distances.get(i)).isGreaterThanOrEqualTo(distances.get(i - 1));
        }
    }
}
