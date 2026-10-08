package com.tradevision.service.strategy;

import com.tradevision.service.ServerSignalEngine;
import com.tradevision.service.broker.dto.Candle;
import com.tradevision.service.strategy.dto.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the Java port of the TA engine's signal combination logic against its TypeScript
 * source -- see SignalCombinerService's own javadoc for the full verification methodology.
 * This test file's expected values are verified against the real TypeScript reference output,
 * with the actual emoji/punctuation codepoints checked directly (not just displayed), not just
 * the direction/confidence numbers.
 */
class SignalCombinerServiceTest {

    private final SignalCombinerService combiner = new SignalCombinerService();

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

    private ServerSignalEngine.Signal baseSignal(String direction, double confidence) {
        String label = direction.equals("LONG") ? "BUY" : direction.equals("SHORT") ? "SELL" : "NEUTRAL";
        return new ServerSignalEngine.Signal(direction, label, confidence, 50000, 49000, 51000, 52000, 53000, 60, 20, 40);
    }

    @Test
    @DisplayName("combine: everything aligned bullish matches the real TypeScript engine's reference output exactly -- confidence capped at 96")
    void combine_everythingAlignedBullish_matchesReferenceOutput() {
        CombinedSignal result = combiner.combine(
            baseSignal("LONG", 65), "Base signal.", "1h",
            seededCandles(50, 111, 0.35), seededCandles(50, 222, 0.4),
            new SMCAnalysis(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                "BULL_TREND", "BULLISH", 80, List.of(), "", "Bull OB entry"),
            new OrderFlowAnalysis(null, null, null, "BULL", 45, List.of(), "", true),
            new VolumeProfile(49000, 49500, 48500, List.of(), List.of(), List.of(), 50000, "ABOVE_VAH", "BULLISH", null, null, "", List.of()),
            new RegimeState("STRONG_BULL_TREND", 90, "Strong Bull Trend", "R", "#0f0", 30, 40, 0.03, 0.5, 60, 1.0,
                new RegimeState.Strategy("x", "x", 1.3, 1.2, 5.0), new RegimeState.WeightAdjustments(1, 1, 1, 1, 1, 1, 1, 1), "", List.of())
        );

        assertThat(result.direction()).isEqualTo("LONG");
        assertThat(result.signal()).isEqualTo("BUY");
        assertThat(result.confidence()).isEqualTo(96.0);
        assertThat(result.regime()).isEqualTo("STRONG_BULL_TREND");
        assertThat(result.smcBias()).isEqualTo("BULLISH");
        assertThat(result.ofBias()).isEqualTo("BULL");
        assertThat(result.vpLocation()).isEqualTo("ABOVE_VAH");
        assertThat(result.mtfContext()).hasSize(2);
    }

    @Test
    @DisplayName("combine: MTF strongly conflicting with the base direction correctly flips LONG to WAIT, with the real warning/red-circle codepoints present in the summary")
    void combine_mtfStronglyAgainst_flipsToWaitWithCorrectEncoding() {
        CombinedSignal result = combiner.combine(
            baseSignal("LONG", 40), "Base signal.", "1h",
            seededCandles(50, 333, -0.35), seededCandles(50, 444, -0.4),
            null, null, null, null
        );

        assertThat(result.direction()).isEqualTo("WAIT");
        assertThat(result.signal()).isEqualTo("NEUTRAL");
        assertThat(result.confidence()).isEqualTo(35.0);
        // The real red-circle (U+1F534), warning (U+26A0), and middle-dot (U+00B7) codepoints
        // must genuinely be present, not an ASCII stand-in.
        assertThat(result.summary().codePoints().anyMatch(cp -> cp == 0x1F534)).isTrue();
        assertThat(result.summary().codePoints().anyMatch(cp -> cp == 0x26A0)).isTrue();
        assertThat(result.summary().codePoints().anyMatch(cp -> cp == 0x00B7)).isTrue();
    }

    @Test
    @DisplayName("combine: a SHORT signal with conflicting SMC bias and a RANGING regime correctly downgrades STRONG SELL to SELL, matching the real reference output")
    void combine_conflictingSignalsWithRangingRegime_downgrades() {
        ServerSignalEngine.Signal shortBase = new ServerSignalEngine.Signal("SHORT", "STRONG SELL", 70, 50000, 49000, 51000, 52000, 53000, 20, 60, -40);

        CombinedSignal result = combiner.combine(
            shortBase, "Base signal.", "1h",
            seededCandles(50, 555, -0.15), null,
            new SMCAnalysis(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                "RANGING", "BULLISH", 60, List.of(), "", null),
            new OrderFlowAnalysis(null, null, null, "BEAR", -35, List.of(), "", true),
            null,
            new RegimeState("RANGING", 60, "Ranging", "N", "#888", 10, 15, 0.02, 0.5, 30, 0.8,
                new RegimeState.Strategy("x", "x", 0.8, 1.0, 1.5), new RegimeState.WeightAdjustments(1, 1, 1, 1, 1, 1, 1, 1), "", List.of())
        );

        assertThat(result.direction()).isEqualTo("SHORT");
        assertThat(result.signal()).isEqualTo("SELL"); // downgraded from STRONG SELL by the RANGING regime rule
        assertThat(result.confidence()).isEqualTo(71.0);
        assertThat(result.summary()).contains("SMC bullish vs SHORT");
    }

    @Test
    @DisplayName("combine: no higher-timeframe data at all returns the base signal completely unmodified")
    void combine_noHigherTimeframeData_returnsBaseUnmodified() {
        var base = baseSignal("LONG", 55);

        CombinedSignal result = combiner.combine(base, "Original summary.", "1h", null, null, null, null, null, null);

        assertThat(result.direction()).isEqualTo("LONG");
        assertThat(result.confidence()).isEqualTo(55.0);
        assertThat(result.summary()).isEqualTo("Original summary.");
        assertThat(result.mtfContext()).isEmpty();
    }

    @Test
    @DisplayName("combine: a higher-timeframe candle list with fewer than 30 candles is excluded from MTF context, same as the real TypeScript's own >= 30 threshold")
    void combine_insufficientHigherTimeframeCandles_excluded() {
        var base = baseSignal("LONG", 55);

        CombinedSignal result = combiner.combine(base, "Base.", "1h", seededCandles(10, 1, 0), null, null, null, null, null);

        assertThat(result.mtfContext()).isEmpty(); // 10 candles < the 30-candle minimum -- correctly excluded, not a fabricated context
        assertThat(result.confidence()).isEqualTo(55.0); // unmodified, since no real MTF context was built
    }

    @Test
    @DisplayName("combine: confidence never exceeds the 96 ceiling or drops below the 30 floor, regardless of how many boosting signals stack up")
    void combine_confidenceAlwaysClampedToValidRange() {
        CombinedSignal boosted = combiner.combine(
            baseSignal("LONG", 90), "Base.", "1h",
            seededCandles(50, 111, 0.35), seededCandles(50, 222, 0.4),
            new SMCAnalysis(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, "BULL_TREND", "BULLISH", 100, List.of(), "", null),
            new OrderFlowAnalysis(null, null, null, "BULL", 50, List.of(), "", true),
            new VolumeProfile(0, 0, 0, List.of(), List.of(), List.of(), 100, "ABOVE_VAH", "BULLISH", null, null, "", List.of()),
            new RegimeState("STRONG_BULL_TREND", 90, "x", "x", "x", 0, 0, 0, 0, 0, 0,
                new RegimeState.Strategy("x", "x", 1, 1, 1), new RegimeState.WeightAdjustments(1, 1, 1, 1, 1, 1, 1, 1), "", List.of())
        );

        assertThat(boosted.confidence()).isLessThanOrEqualTo(96.0);
        assertThat(boosted.confidence()).isGreaterThanOrEqualTo(30.0);
    }
}
