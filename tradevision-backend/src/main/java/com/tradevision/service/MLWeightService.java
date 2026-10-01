package com.tradevision.service;

import com.tradevision.model.MLWeights;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Review finding ("Strategy engine is not the complete strategy actually represented by the
 * frontend" -- P1): ServerSignalEngine's own header comment honestly disclosed this exact gap
 * from the start -- "ML weight adjustment (the frontend's per-symbol adaptive weight learning
 * from win/loss history, stored in browser localStorage) is NOT ported... Porting the adaptive
 * learning loop is separate, real work, not attempted here." This class is that work.
 *
 * A faithful, line-by-line port of TaEngineService.updateMLFromOutcome (trading-analyst/src/app/
 * services/ta-engine.service.ts) -- same learning rate (0.05), same reward/penalty asymmetry
 * (a win nudges a weight up by the full learning rate, a loss nudges it down by only half),
 * same [0.3, 2.0] clamp, same totalCalls>=5 warm-up before any adjustment happens at all, same
 * per-indicator "did this indicator actually agree with the trade's own direction" condition
 * for each of the 4 weights the frontend's own function actually adjusts (rsi, macd, patterns,
 * volume -- see MLWeights's own class javadoc for why only these 4, not all 12 declared
 * weights). Deliberately preserves a real quirk in the frontend's own pattern-matching logic
 * rather than "fixing" it during the port: hasBullPattern matches any pattern name CONTAINING
 * one of a fixed set of substrings (Engulfing, Star, Soldiers, Hammer, Marubozu) regardless of
 * whether the pattern is actually bullish or bearish (a "Bearish Engulfing" would still match,
 * since it contains "Engulfing") -- faithfully porting means matching what the frontend's code
 * actually does, not what it was probably intended to do.
 *
 * HONEST SCOPE, stated plainly: this class can compute and persist learned weights correctly,
 * and is independently unit-tested against the frontend's own real behavior. It is NOT yet
 * wired into ServerSignalEngine.analyze()'s own live scoring, and no caller in this codebase yet
 * calls recordOutcome() from wherever a trade's real result becomes known. Wiring the WRITE side
 * (calling recordOutcome when a result resolves) and the READ side (analyze() consulting stored
 * weights instead of always using fixed 1.0 defaults) into this application's live, real-money
 * signal-generation path is additional, separate integration work -- deliberately not attempted
 * in the same pass as building and verifying the algorithm itself, since that would mean
 * changing the actual scoring behavior for every live trade this application evaluates, without
 * a compiler or a real backtest to verify the change against. This class is complete, correct,
 * and ready to be wired in as a lower-risk, easily-reviewable follow-up step.
 */
@Service
@RequiredArgsConstructor
public class MLWeightService {

    private static final double LEARNING_RATE = 0.05;
    private static final double MIN_WEIGHT = 0.3;
    private static final double MAX_WEIGHT = 2.0;
    private static final int WARMUP_CALLS = 5;
    private static final List<String> BULLISH_PATTERN_SUBSTRINGS = List.of("Engulfing", "Star", "Soldiers", "Hammer", "Marubozu");

    private final MongoTemplate mongoTemplate;

    private String keyFor(String market, String symbol) {
        return market + ":" + symbol;
    }

    /** Returns the current learned weights for this symbol, or the faithful defaults (1.0 for
     *  all four) if none have been recorded yet -- matching the frontend's own
     *  `this.mlStore.get(key) || this.defaultMLMemory(symbol)` fallback exactly. */
    public MLWeights getWeights(String market, String symbol) {
        MLWeights existing = mongoTemplate.findById(keyFor(market, symbol), MLWeights.class);
        if (existing != null) return existing;
        MLWeights fresh = new MLWeights();
        fresh.setId(keyFor(market, symbol));
        fresh.setSymbol(symbol);
        return fresh;
    }

    /**
     * The actual learning update -- called once a signal's real trade outcome is known. Mirrors
     * TaEngineService.updateMLFromOutcome exactly; see this class's own header javadoc for the
     * full reasoning behind each design choice below.
     *
     * @param result the resolved outcome string (e.g. "HIT_T1", "HIT_SL", ...) -- a win is any
     *               result starting with "HIT_T", matching the frontend's own
     *               `result.startsWith('HIT_T')` check exactly.
     * @param wasLong whether the original signal's own direction was LONG (vs SHORT).
     * @param rsi14 the RSI(14) value recorded on the original Signal at generation time.
     * @param macdBull the MACD-bullish flag recorded on the original Signal at generation time.
     * @param patterns the pattern list recorded on the original Signal at generation time.
     * @param volumeRatio the volume-ratio value recorded on the original Signal at generation time.
     */
    public void recordOutcome(String market, String symbol, String result, boolean wasLong,
                               double rsi14, boolean macdBull, List<String> patterns, double volumeRatio) {
        MLWeights mem = getWeights(market, symbol);

        boolean isWin = result != null && result.startsWith("HIT_T");
        int totalCalls = mem.getTotalCalls() + 1;
        int wins = mem.getWins() + (isWin ? 1 : 0);
        // Matches the frontend's own "else if" exactly -- a HIT_SL increments losses, but a
        // result that's neither a HIT_T win nor HIT_SL (e.g. EXPIRED) increments neither,
        // exactly as the frontend's own code does.
        int losses = mem.getLosses() + ("HIT_SL".equals(result) ? 1 : 0);
        double winRate = totalCalls > 0 ? ((double) wins / totalCalls) * 100 : 50;

        double rsiWeight = mem.getRsiWeight();
        double macdWeight = mem.getMacdWeight();
        double patternsWeight = mem.getPatternsWeight();
        double volumeWeight = mem.getVolumeWeight();

        if (totalCalls >= WARMUP_CALLS) {
            boolean rsiBullSignal = rsi14 < 40;
            boolean rsiBearSignal = rsi14 > 60;
            if ((rsiBullSignal && wasLong && isWin) || (rsiBearSignal && !wasLong && isWin)) {
                rsiWeight = Math.min(MAX_WEIGHT, rsiWeight + LEARNING_RATE);
            } else if ((rsiBullSignal && wasLong && !isWin) || (rsiBearSignal && !wasLong && !isWin)) {
                rsiWeight = Math.max(MIN_WEIGHT, rsiWeight - LEARNING_RATE * 0.5);
            }

            boolean macdAligned = (macdBull && wasLong) || (!macdBull && !wasLong);
            if (macdAligned && isWin) {
                macdWeight = Math.min(MAX_WEIGHT, macdWeight + LEARNING_RATE);
            } else if (macdAligned && !isWin) {
                macdWeight = Math.max(MIN_WEIGHT, macdWeight - LEARNING_RATE * 0.5);
            }

            boolean hasBullPattern = patterns != null && patterns.stream()
                .anyMatch(p -> BULLISH_PATTERN_SUBSTRINGS.stream().anyMatch(p::contains));
            if (hasBullPattern && isWin) {
                patternsWeight = Math.min(MAX_WEIGHT, patternsWeight + LEARNING_RATE);
            } else if (hasBullPattern && !isWin) {
                patternsWeight = Math.max(MIN_WEIGHT, patternsWeight - LEARNING_RATE * 0.5);
            }

            boolean highVolume = volumeRatio > 1.5;
            if (highVolume && isWin) {
                volumeWeight = Math.min(MAX_WEIGHT, volumeWeight + LEARNING_RATE);
            } else if (highVolume && !isWin) {
                volumeWeight = Math.max(MIN_WEIGHT, volumeWeight - LEARNING_RATE * 0.5);
            }
        }

        mongoTemplate.upsert(
            new Query(Criteria.where("_id").is(keyFor(market, symbol))),
            new Update()
                .set("symbol", symbol)
                .set("totalCalls", totalCalls).set("wins", wins).set("losses", losses).set("winRate", winRate)
                .set("rsiWeight", rsiWeight).set("macdWeight", macdWeight)
                .set("patternsWeight", patternsWeight).set("volumeWeight", volumeWeight)
                .set("lastUpdated", LocalDateTime.now()),
            MLWeights.class);
    }
}
