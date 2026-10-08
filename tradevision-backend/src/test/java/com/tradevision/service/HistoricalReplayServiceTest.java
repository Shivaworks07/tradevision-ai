package com.tradevision.service;

import com.tradevision.service.broker.dto.Candle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Covers the window-sliding and WAIT-filtering logic in HistoricalReplayService. Uses a mocked
 * ServerSignalEngine to control exactly what each step returns, testing this service's own logic
 * in isolation from the real strategy decisions.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HistoricalReplayServiceTest {

    @Mock ServerSignalEngine serverSignalEngine;
    @InjectMocks HistoricalReplayService service;

    private Candle candle(long time) {
        return new Candle(time, 100, 105, 95, 100, 1000);
    }

    private ServerSignalEngine.Signal waitSignal() {
        return new ServerSignalEngine.Signal("WAIT", "NEUTRAL", 40, 100, 95, 105, 110, 115, 50, 20, 40);
    }

    private ServerSignalEngine.Signal longSignal() {
        return new ServerSignalEngine.Signal("LONG", "STRONG_BUY", 80, 100, 95, 105, 110, 115, 60, 20, 40);
    }

    @Test
    @DisplayName("replay: fewer candles than windowSize returns an empty result immediately, never calling analyze at all")
    void replay_fewerCandlesThanWindow_returnsEmpty() {
        var candles = List.of(candle(1), candle(2));

        var results = service.replay(candles, 10);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("replay: a real, non-WAIT signal at a given step is recorded, tagged with that step's own final candle time")
    void replay_realSignal_recordedWithCorrectCandleTime() {
        var candles = List.of(candle(100), candle(200), candle(300));
        when(serverSignalEngine.analyze(any())).thenReturn(longSignal());

        var results = service.replay(candles, 2);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).candleTime()).isEqualTo(200); // window [candle(100), candle(200)] -- last candle's own time
    }

    @Test
    @DisplayName("replay: a WAIT signal (the real 'no genuine signal' convention analyze() actually uses) is correctly filtered out, not recorded")
    void replay_waitSignal_filteredOut() {
        var candles = List.of(candle(100), candle(200), candle(300));
        when(serverSignalEngine.analyze(any())).thenReturn(waitSignal());

        var results = service.replay(candles, 2);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("replay: the window genuinely slides -- a 5-candle sequence with windowSize=3 calls analyze exactly 3 times (steps ending at candle 3, 4, and 5)")
    void replay_windowSlidesCorrectly_callsAnalyzeExpectedNumberOfTimes() {
        var candles = List.of(candle(1), candle(2), candle(3), candle(4), candle(5));
        when(serverSignalEngine.analyze(any())).thenReturn(waitSignal());

        service.replay(candles, 3);

        org.mockito.Mockito.verify(serverSignalEngine, org.mockito.Mockito.times(3)).analyze(any());
    }

    @Test
    @DisplayName("replay: a genuinely malformed step (analyze throws) is skipped, not allowed to abort the entire replay")
    void replay_analyzeThrowsAtOneStep_skipsAndContinues() {
        // 3 candles, windowSize=2 -> exactly 2 iterations (end=2, end=3) -- deliberately matching
        // the 2 explicitly stubbed responses below exactly, rather than relying on Mockito's own
        // "repeat the last stub" behavior for a third, unstubbed-for call.
        var candles = List.of(candle(1), candle(2), candle(3));
        when(serverSignalEngine.analyze(any()))
            .thenThrow(new RuntimeException("simulated malformed window"))
            .thenReturn(longSignal());

        var results = service.replay(candles, 2);

        // First step threw and was skipped; the second step still produced a real signal.
        assertThat(results).hasSize(1);
    }
}
