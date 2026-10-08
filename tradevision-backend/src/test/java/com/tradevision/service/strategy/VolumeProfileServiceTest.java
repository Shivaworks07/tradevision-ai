package com.tradevision.service.strategy;

import com.tradevision.service.broker.dto.Candle;
import com.tradevision.service.strategy.dto.VolumeProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the Java port of the TA engine's volume profile calculation against its TypeScript
 * source -- see VolumeProfileService's own javadoc for the full verification methodology (a
 * real seeded reference run of the actual TypeScript file, compared field-by-field against this
 * class). The exact expected values below are that same reference output, not a fresh
 * hand-derivation of what the "right" answer should be.
 */
class VolumeProfileServiceTest {

    private final VolumeProfileService service = new VolumeProfileService();

    /** Exact port of the LCG used to generate the reference dataset — see the class javadoc. */
    private static final class SeededRandom {
        long s;
        SeededRandom(long seed) { this.s = seed; }
        double next() { s = (s * 9301 + 49297) % 233280; return (double) s / 233280.0; }
    }

    private List<Candle> seededCandles(int n, long seed) {
        SeededRandom rand = new SeededRandom(seed);
        List<Candle> candles = new ArrayList<>();
        double price = 50000;
        for (int i = 0; i < n; i++) {
            double change = (rand.next() - 0.5) * 200;
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
    @DisplayName("analyze: matches the real TypeScript engine's reference output exactly, for the seeded dataset that output was captured from")
    void analyze_matchesRealTypeScriptReferenceOutput() {
        VolumeProfile result = service.analyze(seededCandles(100, 12345), 50);

        assertThat(result.poc()).isEqualTo(49663.1582);
        assertThat(result.vah()).isEqualTo(49742.5784);
        assertThat(result.val()).isEqualTo(49530.7911);
        assertThat(result.hvns()).containsExactly(49610.2114, 49663.1582, 49716.105);
        assertThat(result.lvns()).containsExactly(49888.1822, 49954.3658);
        assertThat(result.currentPrice()).isEqualTo(49643.7586);
        assertThat(result.priceLocation()).isEqualTo("INSIDE_VA");
        assertThat(result.bias()).isEqualTo("NEUTRAL");
        assertThat(result.nearestHVN().price()).isEqualTo(49663.15820258916);
        assertThat(result.nearestHVN().direction()).isEqualTo("ABOVE");
        assertThat(result.nearestLVN().price()).isEqualTo(49888.18222093621);
    }

    @Test
    @DisplayName("analyze: fewer than 20 candles returns the empty profile — never fabricates a value area from insufficient data")
    void analyze_fewerThan20Candles_returnsEmptyProfile() {
        List<Candle> candles = seededCandles(10, 1);

        VolumeProfile result = service.analyze(candles, 50);

        assertThat(result.interpretation()).isEqualTo("Need 20+ candles for volume profile.");
        assertThat(result.buckets()).isEmpty();
        assertThat(result.hvns()).isEmpty();
    }

    @Test
    @DisplayName("analyze: a zero price range (all candles identical) returns the empty profile rather than dividing by a zero bucket size")
    void analyze_zeroRange_returnsEmptyProfile() {
        List<Candle> flat = new ArrayList<>();
        for (int i = 0; i < 25; i++) flat.add(new Candle(i, 100, 100, 100, 100, 500));

        VolumeProfile result = service.analyze(flat, 50);

        assertThat(result.interpretation()).isEqualTo("Need 20+ candles for volume profile.");
    }

    @Test
    @DisplayName("analyze: POC is always marked isPOC=true in the bucket list, and is the single highest-volume bucket")
    void analyze_pocBucketCorrectlyMarked() {
        VolumeProfile result = service.analyze(seededCandles(100, 12345), 50);

        long pocCount = result.buckets().stream().filter(VolumeProfile.VolumeBucket::isPOC).count();
        assertThat(pocCount).isEqualTo(1);
        double maxVolume = result.buckets().stream().mapToDouble(VolumeProfile.VolumeBucket::volume).max().orElseThrow();
        VolumeProfile.VolumeBucket pocBucket = result.buckets().stream().filter(VolumeProfile.VolumeBucket::isPOC).findFirst().orElseThrow();
        assertThat(pocBucket.volume()).isEqualTo(maxVolume);
    }

    @Test
    @DisplayName("analyze: bucket volumes sum to (approximately) 100% — the distribution logic doesn't lose or fabricate volume")
    void analyze_bucketPercentagesSumToApproximately100() {
        VolumeProfile result = service.analyze(seededCandles(100, 12345), 50);

        double totalPercent = result.buckets().stream().mapToDouble(VolumeProfile.VolumeBucket::percent).sum();
        assertThat(totalPercent).isCloseTo(100.0, org.assertj.core.data.Offset.offset(1.0)); // small tolerance for per-bucket rounding to 2dp
    }
}
