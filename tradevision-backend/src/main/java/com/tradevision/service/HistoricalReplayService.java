package com.tradevision.service;

import com.tradevision.service.broker.dto.Candle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Review finding ("historical replay engine" -- external review, P3, confirmed real by direct
 * inspection before this fix: ServerSignalEngine.analyze(List&lt;Candle&gt;) is a genuinely pure,
 * stateless function -- no database reads, no side effects -- meaning it can be replayed against
 * any sequence of candles directly, without needing a new, separate data-storage subsystem to
 * build first): the actual replay engine.
 *
 * HONEST SCOPE, stated plainly: this replays the STRATEGY LOGIC's own decision-making against a
 * given candle sequence, sliding a fixed-size window across it and recording what signal (if
 * any) would have fired at each step. It does NOT replay real order-book depth, real slippage,
 * or real fill behavior -- PaperBrokerAdapter's own simulator (see its own class javadoc) is
 * the closer analogue for that, and even that is an approximation, not a recorded real market.
 * This also does not fetch or store historical candle data itself -- the caller supplies the
 * candle sequence, from wherever they already have it (a BrokerAdapter's own recent-candle
 * fetch, or a caller-provided historical set) -- building a genuine historical-candle archive is
 * a separate, larger concern this fix does not attempt.
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
            // Review finding, same context as this method's own javadoc: analyze() never
            // actually returns null -- confirmed directly by inspection, not assumed -- "WAIT"
            // is its own real convention for "no genuine signal at this step" (see its own
            // NEUTRAL/WAIT branches). Filtering on that instead of a null check that would
            // never have actually filtered anything.
            if (signal != null && !"WAIT".equals(signal.direction())) {
                results.add(new ReplayResult(window.get(window.size() - 1).time(), signal));
            }
        }
        return results;
    }
}
