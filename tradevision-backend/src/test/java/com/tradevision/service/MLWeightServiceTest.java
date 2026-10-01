package com.tradevision.service;

import com.tradevision.model.MLWeights;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Review finding ("Strategy engine is not the complete strategy actually represented by the
 * frontend" -- P1, full context in MLWeightService's own header javadoc): verifies this is a
 * genuinely faithful port of TaEngineService.updateMLFromOutcome, not just a plausible-looking
 * approximation -- every expected weight value below is hand-computed against the actual
 * frontend algorithm (learning rate 0.05, win nudges by the full rate, loss nudges by half),
 * not against this Java port's own logic (which would just prove the port is internally
 * consistent, not that it matches the frontend it's supposed to be porting).
 *
 * Uses the same real, stateful in-memory fake backing store technique as
 * ExchangeHealthServiceTest -- MLWeightService's own recordOutcome/getWeights round-trip through
 * multiple calls within a single test to build up totalCalls past the warm-up threshold, which a
 * stateless per-call Mockito stub can't represent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MLWeightServiceTest {

    @Mock MongoTemplate mongoTemplate;
    private MLWeightService service;

    private final Map<String, MLWeights> store = new HashMap<>();

    @BeforeEach
    void setup() {
        service = new MLWeightService(mongoTemplate);
        store.clear();

        when(mongoTemplate.findById(anyString(), eq(MLWeights.class)))
            .thenAnswer(inv -> store.get((String) inv.getArgument(0)));

        when(mongoTemplate.upsert(any(Query.class), any(Update.class), eq(MLWeights.class)))
            .thenAnswer(inv -> {
                Query query = inv.getArgument(0);
                Update update = inv.getArgument(1);
                String id = query.getQueryObject().get("_id").toString();
                MLWeights w = store.computeIfAbsent(id, k -> {
                    MLWeights fresh = new MLWeights();
                    fresh.setId(k);
                    return fresh;
                });
                org.bson.Document setDoc = update.getUpdateObject().get("$set", org.bson.Document.class);
                if (setDoc.get("symbol") != null) w.setSymbol((String) setDoc.get("symbol"));
                if (setDoc.get("totalCalls") != null) w.setTotalCalls(((Number) setDoc.get("totalCalls")).intValue());
                if (setDoc.get("wins") != null) w.setWins(((Number) setDoc.get("wins")).intValue());
                if (setDoc.get("losses") != null) w.setLosses(((Number) setDoc.get("losses")).intValue());
                if (setDoc.get("winRate") != null) w.setWinRate(((Number) setDoc.get("winRate")).doubleValue());
                if (setDoc.get("rsiWeight") != null) w.setRsiWeight(((Number) setDoc.get("rsiWeight")).doubleValue());
                if (setDoc.get("macdWeight") != null) w.setMacdWeight(((Number) setDoc.get("macdWeight")).doubleValue());
                if (setDoc.get("patternsWeight") != null) w.setPatternsWeight(((Number) setDoc.get("patternsWeight")).doubleValue());
                if (setDoc.get("volumeWeight") != null) w.setVolumeWeight(((Number) setDoc.get("volumeWeight")).doubleValue());
                return null;
            });
    }

    @Test
    @DisplayName("getWeights: a symbol with no recorded outcomes yet returns the faithful defaults (1.0 for all four weights) -- matching the frontend's own defaultMLMemory fallback")
    void getWeights_noData_returnsDefaults() {
        MLWeights w = service.getWeights("CRYPTO", "BTCUSDT");

        assertThat(w.getRsiWeight()).isEqualTo(1.0);
        assertThat(w.getMacdWeight()).isEqualTo(1.0);
        assertThat(w.getPatternsWeight()).isEqualTo(1.0);
        assertThat(w.getVolumeWeight()).isEqualTo(1.0);
        assertThat(w.getWinRate()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("recordOutcome: below the 5-call warm-up threshold, totalCalls/wins/winRate update but NO weight is ever adjusted -- matching the frontend's own totalCalls>=5 gate exactly")
    void recordOutcome_belowWarmup_noWeightAdjustment() {
        // 4 wins, all with conditions that WOULD trigger every weight's adjustment if warm-up had passed.
        for (int i = 0; i < 4; i++) {
            service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_T1", true, 30.0, true, List.of("Bullish Hammer"), 2.0);
        }

        MLWeights w = service.getWeights("CRYPTO", "BTCUSDT");
        assertThat(w.getTotalCalls()).isEqualTo(4);
        assertThat(w.getWins()).isEqualTo(4);
        assertThat(w.getWinRate()).isEqualTo(100.0);
        // Still all at the untouched default -- warm-up hasn't been reached yet.
        assertThat(w.getRsiWeight()).isEqualTo(1.0);
        assertThat(w.getMacdWeight()).isEqualTo(1.0);
        assertThat(w.getPatternsWeight()).isEqualTo(1.0);
        assertThat(w.getVolumeWeight()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordOutcome: RSI bullish signal + LONG + win -- rsiWeight increases by exactly the learning rate (0.05), the other three weights stay untouched -- the actual review fix (\"Strategy engine is not the complete strategy actually represented by the frontend\")")
    void recordOutcome_rsiWinCondition_increasesOnlyRsiWeight() {
        // 4 calls to reach the warm-up boundary with conditions that do NOT trigger macd/pattern/volume.
        for (int i = 0; i < 4; i++) {
            service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_SL", false, 50.0, true, List.of(), 1.0);
        }
        // The 5th call: rsi14=30 (<40, bull signal), wasLong=true, isWin=true -- the exact RSI win condition.
        service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_T1", true, 30.0, false, List.of(), 1.0);

        MLWeights w = service.getWeights("CRYPTO", "BTCUSDT");
        assertThat(w.getTotalCalls()).isEqualTo(5);
        assertThat(w.getRsiWeight()).isEqualTo(1.05); // 1.0 + 0.05
        assertThat(w.getMacdWeight()).isEqualTo(1.0);
        assertThat(w.getPatternsWeight()).isEqualTo(1.0);
        assertThat(w.getVolumeWeight()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordOutcome: RSI bullish signal + LONG + LOSS -- rsiWeight decreases by half the learning rate (0.025), matching the frontend's own asymmetric win/loss nudge exactly")
    void recordOutcome_rsiLossCondition_decreasesByHalfLearningRate() {
        for (int i = 0; i < 4; i++) {
            service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_T1", true, 50.0, true, List.of(), 1.0);
        }
        // rsi14=30 (bull signal), wasLong=true, isWin=false -- the exact RSI loss condition.
        service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_SL", true, 30.0, true, List.of(), 1.0);

        MLWeights w = service.getWeights("CRYPTO", "BTCUSDT");
        assertThat(w.getRsiWeight()).isEqualTo(0.975); // 1.0 - (0.05 * 0.5)
    }

    @Test
    @DisplayName("recordOutcome: MACD aligned with direction + win -- macdWeight increases by the learning rate")
    void recordOutcome_macdAlignedWin_increasesMacdWeight() {
        for (int i = 0; i < 4; i++) {
            service.recordOutcome("CRYPTO", "ETHUSDT", "HIT_SL", true, 50.0, false, List.of(), 1.0);
        }
        // macdBull=true, wasLong=true -- aligned. isWin=true.
        service.recordOutcome("CRYPTO", "ETHUSDT", "HIT_T2", true, 50.0, true, List.of(), 1.0);

        MLWeights w = service.getWeights("CRYPTO", "ETHUSDT");
        assertThat(w.getMacdWeight()).isEqualTo(1.05);
        assertThat(w.getRsiWeight()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordOutcome: a bullish-named pattern present + win -- patternsWeight increases")
    void recordOutcome_bullishPatternWin_increasesPatternsWeight() {
        for (int i = 0; i < 4; i++) {
            service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_SL", true, 50.0, false, List.of(), 1.0);
        }
        service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_T1", true, 50.0, false, List.of("Three White Soldiers"), 1.0);

        MLWeights w = service.getWeights("CRYPTO", "BTCUSDT");
        assertThat(w.getPatternsWeight()).isEqualTo(1.05);
    }

    @Test
    @DisplayName("recordOutcome: preserves the frontend's own real quirk -- a pattern name containing a bullish substring counts as \"bullish\" even when the pattern is actually bearish (e.g. \"Bearish Engulfing\" still contains \"Engulfing\") -- deliberately NOT fixed during the port, since faithful porting means matching what the frontend's code actually does")
    void recordOutcome_bearishNamedPatternStillMatchesSubstring_preservedQuirk() {
        for (int i = 0; i < 4; i++) {
            service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_SL", true, 50.0, false, List.of(), 1.0);
        }
        service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_T1", true, 50.0, false, List.of("Bearish Engulfing"), 1.0);

        MLWeights w = service.getWeights("CRYPTO", "BTCUSDT");
        assertThat(w.getPatternsWeight()).isEqualTo(1.05); // still triggers, matching the frontend's own substring-only check
    }

    @Test
    @DisplayName("recordOutcome: high volume ratio + win -- volumeWeight increases")
    void recordOutcome_highVolumeWin_increasesVolumeWeight() {
        for (int i = 0; i < 4; i++) {
            service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_SL", true, 50.0, false, List.of(), 1.0);
        }
        service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_T1", true, 50.0, false, List.of(), 2.0); // >1.5

        MLWeights w = service.getWeights("CRYPTO", "BTCUSDT");
        assertThat(w.getVolumeWeight()).isEqualTo(1.05);
    }

    @Test
    @DisplayName("recordOutcome: a weight already at the maximum (2.0) does not exceed it on another win -- the ceiling clamp is real")
    void recordOutcome_weightAtMax_clampedNotExceeded() {
        // Drive rsiWeight up repeatedly with the exact win condition until it hits the ceiling.
        for (int i = 0; i < 4; i++) {
            service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_SL", true, 50.0, false, List.of(), 1.0);
        }
        for (int i = 0; i < 25; i++) { // 25 * 0.05 = 1.25, comfortably past the 2.0 ceiling from 1.0
            service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_T1", true, 30.0, false, List.of(), 1.0);
        }

        MLWeights w = service.getWeights("CRYPTO", "BTCUSDT");
        assertThat(w.getRsiWeight()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("recordOutcome: a weight already at the minimum (0.3) does not go below it on another loss -- the floor clamp is real")
    void recordOutcome_weightAtMin_clampedNotBelow() {
        for (int i = 0; i < 4; i++) {
            service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_T1", true, 50.0, false, List.of(), 1.0);
        }
        for (int i = 0; i < 60; i++) { // 60 * 0.025 = 1.5, comfortably past the 0.3 floor from 1.0
            service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_SL", true, 30.0, false, List.of(), 1.0);
        }

        MLWeights w = service.getWeights("CRYPTO", "BTCUSDT");
        assertThat(w.getRsiWeight()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("recordOutcome: different symbols are tracked completely independently, matching the frontend's own per-symbol mlStore keying")
    void recordOutcome_differentSymbols_independentTracking() {
        for (int i = 0; i < 5; i++) {
            service.recordOutcome("CRYPTO", "BTCUSDT", "HIT_T1", true, 30.0, false, List.of(), 1.0);
        }
        MLWeights eth = service.getWeights("CRYPTO", "ETHUSDT");

        assertThat(eth.getTotalCalls()).isEqualTo(0);
        assertThat(eth.getRsiWeight()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordOutcome: a result that is neither a HIT_T win nor HIT_SL (e.g. EXPIRED) increments neither wins nor losses -- matching the frontend's own else-if chain exactly")
    void recordOutcome_expiredResult_incrementsNeitherWinsNorLosses() {
        service.recordOutcome("CRYPTO", "BTCUSDT", "EXPIRED", true, 50.0, false, List.of(), 1.0);

        MLWeights w = service.getWeights("CRYPTO", "BTCUSDT");
        assertThat(w.getTotalCalls()).isEqualTo(1);
        assertThat(w.getWins()).isEqualTo(0);
        assertThat(w.getLosses()).isEqualTo(0);
    }
}
