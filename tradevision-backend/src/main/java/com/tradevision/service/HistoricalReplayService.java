package com.tradevision.service;

import com.tradevision.service.broker.dto.Candle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Replays the strategy logic's own decision-making against a given candle sequence, sliding a
 * fixed-size window across it and recording what signal (if any) would have fired at each step.
 * This works because {@code ServerSignalEngine.analyze(List<Candle>)} is a pure, stateless
 * function -- no database reads, no side effects -- so it can be replayed against any candle
 * sequence directly without a separate data-storage subsystem.
 *
 * <p>Scope: this replays the strategy's decisions only, not real order-book depth, slippage, or
 * fill behavior -- {@code PaperBrokerAdapter}'s simulator (see its own class javadoc) is the
 * closer analogue for that, and even that is an approximation rather than a recorded real market.
 * This also does not fetch or store historical candle data itself; the caller supplies the candle
 * sequence, from wherever they already have it (a {@code BrokerAdapter}'s recent-candle fetch, or
 * a caller-provided historical set).
 */
@Service
@RequiredArgsConstructor
public class HistoricalReplayService {

    private final ServerSignalEngine serverSignalEngine;

    public record ReplayResult(long candleTime, ServerSignalEngine.Signal signal) {}

    /**
     * Slides a window of `windowSize` candles across `allCandles`, calling analyze() once per
     * step (as if that step's own window were the most recent data available at that moment),
     * and records every step that produced a real, non-null signal. windowSize should match
     * whatever the live scanner itself typically uses (see AutonomousScannerService's own
     * closedCandles sizing) for the replay to be a meaningful approximation of what the live
     * system would actually have seen.
     */
    public List<ReplayResult> replay(List<Candle> allCandles, int windowSize) {
        if (allCandles == null || allCandles.size() < windowSize) {
            return List.of();
        }
        var results = new ArrayList<ReplayResult>();
        for (int end = windowSize; end <= allCandles.size(); end++) {
            List<Candle> window = allCandles.subList(end - windowSize, end);
            ServerSignalEngine.Signal signal;
            try {
                signal = serverSignalEngine.analyze(window);
            } catch (Exception e) {
                // A genuinely malformed or too-short window at this specific step shouldn't
                // abort the entire replay -- skip this one step and keep going, same "one bad
                // step doesn't poison the whole run" principle a real backtest needs.
                continue;
            }
            // analyze() never returns null; "WAIT" is its convention for "no genuine signal at
            // this step" (see its NEUTRAL/WAIT branches), so filtering on that rather than a
            // null check is what actually excludes no-signal steps.
            if (signal != null && !"WAIT".equals(signal.direction())) {
                results.add(new ReplayResult(window.get(window.size() - 1).time(), signal));
            }
        }
        return results;
    }
}
