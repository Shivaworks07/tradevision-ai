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
 * Server-side adaptive weight learning for per-indicator scoring, mirroring the frontend's
 * localStorage-based ML weight learning (TaEngineService.updateMLFromOutcome in
 * trading-analyst/src/app/services/ta-engine.service.ts) so both sides apply the same logic:
 * same learning rate (0.05), same reward/penalty asymmetry (a win nudges a weight up by the
 * full learning rate, a loss nudges it down by only half), same [0.3, 2.0] clamp, and the same
 * totalCalls>=5 warm-up before any adjustment happens. Only 4 of the weights declared on
 * MLWeights are adjusted here (rsi, macd, patterns, volume) — see MLWeights's own class
 * javadoc for why.
 *
 * The bullish-pattern match intentionally matches any pattern name CONTAINING one of a fixed
 * set of substrings (Engulfing, Star, Soldiers, Hammer, Marubozu) regardless of whether the
 * pattern is actually bullish or bearish (a "Bearish Engulfing" still matches, since it
 * contains "Engulfing") — this mirrors the frontend's matching behavior exactly so weight
 * learning stays consistent between client and server.
 *
 * This class computes and persists learned weights, and is independently unit-tested. It is
 * not wired into ServerSignalEngine.analyze()'s live scoring, and no caller in this codebase
 * yet invokes recordOutcome() when a trade's real result becomes known — that integration
 * (calling recordOutcome on resolution, and having analyze() consult stored weights instead of
 * fixed 1.0 defaults) is handled as a separate step so it can be reviewed and verified on its
 * own before it changes live scoring behavior.
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

    /** Returns the current learned weights for this symbol, or defaults (1.0 for all four)
     *  if none have been recorded yet, matching the frontend's own fallback behavior. */
    public MLWeights getWeights(String market, String symbol) {
        MLWeights existing = mongoTemplate.findById(keyFor(market, symbol), MLWeights.class);
        if (existing != null) return existing;
        MLWeights fresh = new MLWeights();
        fresh.setId(keyFor(market, symbol));
        fresh.setSymbol(symbol);
        return fresh;
    }

    /**
     * Applies the learning update once a signal's real trade outcome is known, adjusting the
     * per-indicator weights for this symbol based on whether each indicator agreed with the
     * trade's direction. See this class's own header javadoc for the design rationale.
     *
     * @param result the resolved outcome string (e.g. "HIT_T1", "HIT_SL", ...) -- a win is any
     *               result starting with "HIT_T".
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
        // A HIT_SL result increments losses; a result that's neither a HIT_T win nor HIT_SL
        // (e.g. EXPIRED) increments neither wins nor losses.
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
