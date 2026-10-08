package com.tradevision.service;

import com.tradevision.model.BrokerMode;
import com.tradevision.model.Position;
import com.tradevision.model.TradeCallRecord;
import java.math.BigDecimal;
import com.tradevision.repository.PositionRepository;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.Candle;
import com.tradevision.service.broker.dto.SpreadInfo;
import com.tradevision.service.strategy.MarketRegimeService;
import com.tradevision.service.strategy.SignalCombinerService;
import com.tradevision.service.strategy.SmcEngineService;
import com.tradevision.service.strategy.VolumeProfileService;
import com.tradevision.service.strategy.dto.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers NoTradeFilterService, the "second opinion" safety gate for every autonomous trade,
 * focused on the regime/SMC/volume-profile/MTF enrichment logic (see NoTradeFilterService's
 * field/method comments for the full design and its scope -- order-flow is deliberately
 * excluded from this critical path). ServerSignalEngine is mocked here rather than exercised for
 * real, since its own correctness is separately verified elsewhere -- these tests isolate and
 * verify the enrichment logic, not the base engine.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NoTradeFilterServiceTest {

    @Mock PositionRepository positionRepo;
    @Mock ExchangeHealthService exchangeHealth;
    @Mock ServerSignalEngine serverSignalEngine;
    // Needed because this service calls mlWeightService.getWeights before every analyze() call.
    // Without this mock, @InjectMocks would leave the field null -- functionally safe (the
    // service's try/catch falls back to the no-weights overload on any exception), but relying
    // on a silently-caught NPE isn't the standard to test to.
    @Mock MLWeightService mlWeightService;
    @Mock MarketRegimeService marketRegimeService;
    @Mock SmcEngineService smcEngineService;
    @Mock VolumeProfileService volumeProfileService;
    @Mock SignalCombinerService signalCombinerService;
    @Mock BrokerAdapter adapter;

    @InjectMocks NoTradeFilterService service;

    private List<Candle> fakeCandles(int n) {
        List<Candle> candles = new ArrayList<>();
        double price = 50000;
        for (int i = 0; i < n; i++) {
            candles.add(new Candle((long) i * 3600000L, price, price + 50, price - 50, price + 10, 100));
            price += 10;
        }
        return candles;
    }

    private TradeCallRecord passingSignal() {
        TradeCallRecord signal = new TradeCallRecord();
        signal.setSymbol("BTCUSDT");
        signal.setDirection("LONG");
        signal.setTimeframe("1H");
        // These 4 fields are BigDecimal, matching TradeCallRecord's field types.
        signal.setEntryPrice(java.math.BigDecimal.valueOf(50000));
        signal.setStopLoss(java.math.BigDecimal.valueOf(49700)); // distance 300 -- within [MIN_SL_ATR_RATIO, MAX_SL_ATR_RATIO] * ~100 ATR from fakeCandles()
        signal.setRrRatio(java.math.BigDecimal.valueOf(2.0));
        signal.setAtr(java.math.BigDecimal.ZERO);
        return signal;
    }

    private ServerSignalEngine.Signal longSignal(double confidence) {
        return new ServerSignalEngine.Signal("LONG", "BUY", confidence, 50000, 49700, 51000, 52000, 53000, 60, 20, 40);
    }

    /** Sets up every gate before the enrichment point to pass, so tests reach the new logic. */
    private void stubEverythingUpToEnrichment(ServerSignalEngine.Signal baseSignal) {
        when(exchangeHealth.check(any())).thenReturn(new ExchangeHealthService.HealthStatus(true, null, 0, 0, 0));
        when(adapter.getSpread(any(), any())).thenReturn(new SpreadInfo(BigDecimal.valueOf(100), BigDecimal.valueOf(100.1), 0.1));
        when(positionRepo.findByUserIdAndCredentialIdAndStatus(any(), any(), any())).thenReturn(List.of());
        when(adapter.getRecentCandles(any(), any(), anyInt(), any())).thenReturn(fakeCandles(220));
        // The normal, successful path calls the 2-arg analyze(candles, weights) overload first;
        // the 1-arg stub below is kept too since the fallback branch on a weights-fetch failure
        // still uses it. Without the 2-arg stub, the unstubbed call would return Mockito's null
        // default, which the code would NPE on calling .direction() outside its own try/catch,
        // since analyze() itself doesn't throw.
        when(serverSignalEngine.analyze(any(), any())).thenReturn(baseSignal);
        when(serverSignalEngine.analyze(any())).thenReturn(baseSignal);
    }

    @Test
    @DisplayName("check: enrichment failing (e.g. a bug in SMC/regime) falls back to the unenriched base signal, never blocking a trade the rest of the method would otherwise accept")
    void enrichmentFailure_fallsBackToBaseSignal_tradeStillProceeds() {
        stubEverythingUpToEnrichment(longSignal(80));
        when(smcEngineService.analyze(any(), any())).thenThrow(new RuntimeException("simulated SMC bug"));

        var result = service.check("user1", "cred1", passingSignal(), adapter, "key", BrokerMode.TESTNET);

        assertThat(result.tradeable()).isTrue();
        assertThat(result.serverSignal().direction()).isEqualTo("LONG");
        assertThat(result.serverSignal().confidence()).isEqualTo(80.0); // the original, unenriched confidence -- untouched by the failed enrichment
    }

    @Test
    @DisplayName("check: fetches ML weights via mlWeightService (keyed by this signal's market/symbol) and passes them to the server's independent re-computation, so this gate scores with the same weights the live autonomous scanner uses, not a stale fixed baseline that could cause a spurious disagreement")
    void check_fetchesAndPassesMLWeightsToIndependentRecomputation() {
        ServerSignalEngine.Signal baseSignal = longSignal(80);
        stubEverythingUpToEnrichment(baseSignal);
        var weights = new com.tradevision.model.MLWeights();
        TradeCallRecord signal = passingSignal();
        when(mlWeightService.getWeights(signal.getMarket(), "BTCUSDT")).thenReturn(weights);
        when(serverSignalEngine.analyze(any(), eq(weights))).thenReturn(baseSignal);

        service.check("user1", "cred1", signal, adapter, "key", BrokerMode.TESTNET);

        verify(mlWeightService).getWeights(signal.getMarket(), "BTCUSDT");
        verify(serverSignalEngine).analyze(any(), eq(weights));
    }

    @Test
    @DisplayName("check: mlWeightService.getWeights throwing (a transient MongoDB failure) does NOT block this gate -- falls back to the no-weights overload, the same fixed-weight scoring this gate has always used")
    void check_mlWeightsFetchFails_fallsBackGracefullyRatherThanBlockingGate() {
        ServerSignalEngine.Signal baseSignal = longSignal(80);
        stubEverythingUpToEnrichment(baseSignal);
        when(mlWeightService.getWeights(any(), any())).thenThrow(new RuntimeException("simulated Mongo failure"));

        var result = service.check("user1", "cred1", passingSignal(), adapter, "key", BrokerMode.TESTNET);

        // The gate must still evaluate normally (via the 1-arg fallback stubbed in
        // stubEverythingUpToEnrichment), not fail outright just because the weights lookup did.
        assertThat(result.tradeable()).isTrue();
    }

    @Test
    @DisplayName("check: a successful enrichment that flips direction to WAIT (conflicting regime/SMC/MTF signals) refuses the trade, the same as any other direction disagreement")
    void enrichmentFlipsToWait_refusesTrade() {
        stubEverythingUpToEnrichment(longSignal(40));
        when(smcEngineService.analyze(any(), any())).thenReturn(
            new SMCAnalysis(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, "RANGING", "NEUTRAL", 0, List.of(), "", null));
        when(marketRegimeService.detect(any(), any())).thenReturn(
            new RegimeState("RANGING", 50, "Ranging", "N", "#888", 10, 20, 0.02, 1.0, 30, 1.0,
                new RegimeState.Strategy("x", "x", 1, 1, 1), new RegimeState.WeightAdjustments(1,1,1,1,1,1,1,1), "", List.of()));
        when(volumeProfileService.analyze(any(), anyInt())).thenReturn(
            new VolumeProfile(49000, 49500, 48500, List.of(), List.of(), List.of(), 50000, "INSIDE_VA", "NEUTRAL", null, null, "", List.of()));
        when(signalCombinerService.combine(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(
            new CombinedSignal("WAIT", "NEUTRAL", 35, BigDecimal.valueOf(50000), BigDecimal.valueOf(49000), BigDecimal.valueOf(51000), BigDecimal.valueOf(52000), BigDecimal.valueOf(53000),
                "RANGING", "Ranging", "N", "#888", 50.0, List.of(),
                List.of(new MTFContext("4h", "DOWN", 70, false, false, false, -40)), "MTF conflict",
                "NEUTRAL", null, 0, null, null, List.of(), "INSIDE_VA", 49000.0, 49500.0, 48500.0, false, ""));

        var result = service.check("user1", "cred1", passingSignal(), adapter, "key", BrokerMode.TESTNET);

        assertThat(result.tradeable()).isFalse();
        assertThat(result.reason()).contains("enriched server analysis").contains("WAIT");
    }

    @Test
    @DisplayName("check: a successful enrichment that keeps direction LONG but adjusts confidence uses the ENRICHED confidence for the rest of the pipeline, not the original")
    void enrichmentAdjustsConfidence_usesEnrichedValue() {
        stubEverythingUpToEnrichment(longSignal(60));
        when(smcEngineService.analyze(any(), any())).thenReturn(
            new SMCAnalysis(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, "BULL_TREND", "BULLISH", 80, List.of(), "", null));
        when(marketRegimeService.detect(any(), any())).thenReturn(
            new RegimeState("STRONG_BULL_TREND", 90, "Strong Bull", "R", "#0f0", 30, 40, 0.03, 0.5, 60, 1.0,
                new RegimeState.Strategy("x", "x", 1.3, 1.2, 5.0), new RegimeState.WeightAdjustments(1,1,1,1,1,1,1,1), "", List.of()));
        when(volumeProfileService.analyze(any(), anyInt())).thenReturn(
            new VolumeProfile(49000, 49500, 48500, List.of(), List.of(), List.of(), 50000, "ABOVE_VAH", "BULLISH", null, null, "", List.of()));
        when(signalCombinerService.combine(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(
            new CombinedSignal("LONG", "STRONG BUY", 96, BigDecimal.valueOf(50000), BigDecimal.valueOf(49000), BigDecimal.valueOf(51000), BigDecimal.valueOf(52000), BigDecimal.valueOf(53000),
                "STRONG_BULL_TREND", "Strong Bull", "R", "#0f0", 90.0, List.of(),
                List.of(new MTFContext("4h", "UP", 40, true, true, true, 40)), "ALL aligned",
                "BULLISH", null, 80, "BULL", 45.0, List.of(), "ABOVE_VAH", 49000.0, 49500.0, 48500.0, false, ""));

        var result = service.check("user1", "cred1", passingSignal(), adapter, "key", BrokerMode.TESTNET);

        assertThat(result.tradeable()).isTrue();
        assertThat(result.serverSignal().confidence()).isEqualTo(96.0); // the enriched value, not the original 60
        assertThat(result.serverSignal().direction()).isEqualTo("LONG");
    }

    @Test
    @DisplayName("check: entry/stopLoss/targets/atrPercent are NEVER modified by enrichment, only direction/confidence/signalLabel -- confirmed directly rather than assumed")
    void enrichment_neverTouchesExecutionCriticalNumbers() {
        ServerSignalEngine.Signal base = longSignal(60);
        stubEverythingUpToEnrichment(base);
        when(smcEngineService.analyze(any(), any())).thenReturn(
            new SMCAnalysis(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, "BULL_TREND", "BULLISH", 80, List.of(), "", null));
        when(marketRegimeService.detect(any(), any())).thenReturn(
            new RegimeState("BULL_TREND", 70, "Bull", "B", "#0f0", 20, 25, 0.03, 1.1, 40, 1.0,
                new RegimeState.Strategy("x", "x", 1.1, 1.5, 3.5), new RegimeState.WeightAdjustments(1,1,1,1,1,1,1,1), "", List.of()));
        when(volumeProfileService.analyze(any(), anyInt())).thenReturn(
            new VolumeProfile(49000, 49500, 48500, List.of(), List.of(), List.of(), 50000, "INSIDE_VA", "NEUTRAL", null, null, "", List.of()));
        when(signalCombinerService.combine(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(
            // Deliberately different entry/SL/targets than `base` to prove the real code ignores
            // whatever combine() returns for these fields and keeps base's own values instead.
            new CombinedSignal("LONG", "BUY", 85, BigDecimal.valueOf(99999), BigDecimal.valueOf(88888), BigDecimal.valueOf(77777), BigDecimal.valueOf(66666), BigDecimal.valueOf(55555),
                "BULL_TREND", "Bull", "B", "#0f0", 70.0, List.of(),
                List.of(new MTFContext("4h", "UP", 30, true, true, true, 20)), "Partial alignment",
                "BULLISH", null, 80, null, null, List.of(), "INSIDE_VA", 49000.0, 49500.0, 48500.0, false, ""));

        var result = service.check("user1", "cred1", passingSignal(), adapter, "key", BrokerMode.TESTNET);

        assertThat(result.serverSignal().entry()).isEqualTo(base.entry());
        assertThat(result.serverSignal().stopLoss()).isEqualTo(base.stopLoss());
        assertThat(result.serverSignal().target1()).isEqualTo(base.target1());
        assertThat(result.serverSignal().target2()).isEqualTo(base.target2());
        assertThat(result.serverSignal().target3()).isEqualTo(base.target3());
        assertThat(result.serverSignal().atrPercent()).isEqualTo(base.atrPercent());
        assertThat(result.serverSignal().confidence()).isEqualTo(85.0); // this one DID change -- confirms the test setup itself is meaningful, not just permissive
    }

    /**
     * adapter.getRecentCandles' last element is always the still-forming candle for the current
     * interval (matching AutonomousScannerService's documented assumption about that same
     * method). Verifies checkIndicatorDivergence drops it before calling
     * ServerSignalEngine.analyze, the same as the scanner does, instead of passing the full raw
     * list straight through.
     */
    @Test
    @DisplayName("check: the last (still-forming/unclosed) candle from adapter.getRecentCandles is dropped before ServerSignalEngine.analyze is called -- execution now analyzes the same CLOSED data the scanner itself would have")
    void check_dropsUnclosedFinalCandleBeforeServerSignalAnalysis() {
        ServerSignalEngine.Signal base = longSignal(80);
        stubEverythingUpToEnrichment(base);
        List<Candle> rawCandles = fakeCandles(220); // fakeCandles' own last element stands in for the unclosed, in-progress candle
        when(adapter.getRecentCandles(any(), any(), anyInt(), any())).thenReturn(rawCandles);

        service.check("user1", "cred1", passingSignal(), adapter, "key", BrokerMode.TESTNET);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(serverSignalEngine).analyze(captor.capture(), any());
        List<Candle> passedCandles = captor.getValue();
        assertThat(passedCandles).hasSize(rawCandles.size() - 1);
        assertThat(passedCandles).doesNotContain(rawCandles.get(rawCandles.size() - 1)); // the raw last (unclosed) candle itself must never reach the engine
        assertThat(passedCandles).containsExactlyElementsOf(rawCandles.subList(0, rawCandles.size() - 1));
    }

    /**
     * The client claims a healthy 2.0 R:R (passingSignal's rrRatio), but the server-computed
     * signal (what actually drives execution) has a much tighter real R:R -- verifies this is
     * rejected, i.e. the R:R check is against the server-computed number, not the client's claim.
     */
    @Test
    @DisplayName("check: rejects a trade whose SERVER-computed R:R is below the minimum, even when the client's own claimed rrRatio looks healthy")
    void check_rejectsOnServerComputedRrRatio_regardlessOfClientClaim() {
        // entry 50000, SL 49700 (risk 300), target1 50450 (reward 450) -> server R:R = 1.5... make it fail: reward 300 -> R:R 1.0
        ServerSignalEngine.Signal weakRrSignal = new ServerSignalEngine.Signal("LONG", "BUY", 80, 50000, 49700, 50300, 52000, 53000, 60, 20, 40);
        stubEverythingUpToEnrichment(weakRrSignal);
        TradeCallRecord signal = passingSignal();
        signal.setRrRatio(java.math.BigDecimal.valueOf(2.0)); // client claims a healthy R:R -- must not save this trade

        var result = service.check("user1", "cred1", signal, adapter, "key", BrokerMode.TESTNET);

        assertThat(result.tradeable()).isFalse();
        assertThat(result.reason()).contains("Server-computed risk:reward");
    }

    @Test
    @DisplayName("check: accepts a trade whose SERVER-computed R:R clears the minimum, even when the client's own claimed rrRatio looks weak")
    void check_acceptsOnServerComputedRrRatio_evenWhenClientClaimIsWeak() {
        // entry 50000, SL 49700 (risk 300), target1 51000 (reward 1000) -> server R:R = 3.33, well above 1.5
        stubEverythingUpToEnrichment(longSignal(80));
        TradeCallRecord signal = passingSignal();
        signal.setRrRatio(java.math.BigDecimal.valueOf(0.1)); // client claims a terrible R:R -- must not block a trade whose real numbers are fine

        var result = service.check("user1", "cred1", signal, adapter, "key", BrokerMode.TESTNET);

        assertThat(result.tradeable()).isTrue();
    }
}
