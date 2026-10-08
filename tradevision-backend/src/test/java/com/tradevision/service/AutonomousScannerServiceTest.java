package com.tradevision.service;

import com.tradevision.dto.TradeCallRequest;
import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.BrokerType;
import com.tradevision.model.Position;
import com.tradevision.model.RiskProfile;
import com.tradevision.model.TradeCallRecord;
import com.tradevision.repository.BrokerCredentialRepository;
import com.tradevision.repository.RiskProfileRepository;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.Candle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies the autonomous discovery flow: candles fetched from the broker directly (not from a
 * frontend request), run through ServerSignalEngine, and dispatched via
 * TradeCallService.saveCall() with zero browser interaction anywhere in the path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutonomousScannerServiceTest {

    @Mock RiskProfileRepository riskProfileRepo;
    @Mock BrokerCredentialRepository credentialRepo;
    @Mock BrokerAdapter adapter;
    @Mock DynamicUniverseService dynamicUniverseService;
    @Mock StrategyPlanService strategyPlanService;
    @Mock com.tradevision.repository.PositionRepository positionRepo;
    @Mock PositionSafetyService positionSafetyService;
    @Mock com.tradevision.service.BrokerCredentialService credentialService;
    @Mock ServerSignalEngine serverSignalEngine;
    // Needed because this class calls mlWeightService.getWeights before every analyze() call,
    // and the subsequent analyze() call always uses the 2-arg overload (see the stubs below).
    @Mock MLWeightService mlWeightService;
    @Mock TradeCallService tradeCallService;
    @Mock com.tradevision.config.ShutdownState shutdownState;
    @Mock com.tradevision.config.StartupState startupState;
    @Mock com.tradevision.config.TradingHeartbeatService heartbeatService;
    @Mock com.tradevision.repository.ScannedCandleRepository scannedCandleRepo;
    @Mock MarketDataQualityService marketDataQualityService;
    @Mock com.tradevision.service.strategy.MarketRegimeService marketRegimeService;
    @Mock com.tradevision.service.strategy.SmcEngineService smcEngineService;
    @Mock com.tradevision.service.strategy.VolumeProfileService volumeProfileService;
    @Mock com.tradevision.service.strategy.OrderFlowService orderFlowService;
    // Needed because order-flow enrichment is budget-gated.
    @Mock ExchangeHealthService exchangeHealth;
    @Mock com.tradevision.service.strategy.SignalCombinerService signalCombinerService;
    // Needed because a cache miss on the in-memory cooldown map falls back to this repository.
    @Mock com.tradevision.repository.TradeCallRepository tradeCallRepository;

    @InjectMocks AutonomousScannerService service;

    private RiskProfile profile;
    private BrokerCredential credential;
    private com.tradevision.model.StrategyPlan defaultPlan;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "adapters", List.of(adapter));
        when(adapter.getType()).thenReturn(BrokerType.BINANCE);
        // Every per-credential adapter lookup in this service goes through
        // credentialService.adapterForCredential(...) instead of a local BrokerType-keyed
        // adapterMap, so PAPER credentials route to the simulated adapter instead of the real
        // one. Tests exercise TESTNET/LIVE credentials by default, so this just returns the same
        // mocked adapter.
        when(credentialService.adapterForCredential(any())).thenReturn(adapter);
        when(startupState.isTradingEnabled()).thenReturn(true);
        // The scanner gates every symbol through this check before analysis — stubbed here so
        // tests that don't care about data quality aren't NPE'd by Mockito's default null return
        // for the unstubbed QualityResult object, which would otherwise happen the instant
        // .safe() is called on it.
        when(marketDataQualityService.isMarketSafeToTrade(any(), any(), anyLong(), any(), any()))
            .thenReturn(MarketDataQualityService.QualityResult.ok());
        // Order-flow enrichment is budget-gated; a realistic "budget is healthy" default keeps
        // tests that don't care about request-weight budget from being NPE'd by Mockito's
        // default null return for the unstubbed RequestBudgetStatus object.
        when(exchangeHealth.checkRequestBudget()).thenReturn(new ExchangeHealthService.RequestBudgetStatus(0, 6000, 0.0, true));
        // scanForProfile iterates strategyPlanService.getEnabledPlans(...) instead of scanning
        // directly off the profile -- an unstubbed mock would otherwise return an empty list,
        // meaning no plan, and therefore no symbol, would ever be scanned. A single default plan
        // matching this fixture's profile settings (minConfidence, timeframe) keeps every
        // existing test's assumptions -- "TIER1 gets scanned", "confidence threshold is 60.0" --
        // true.
        var defaultPlan = new com.tradevision.model.StrategyPlan();
        defaultPlan.setId("plan1");
        defaultPlan.setEnabled(true);
        defaultPlan.setDirection(com.tradevision.model.TradeDirection.LONG);
        defaultPlan.setMinConfidence(60.0);
        this.defaultPlan = defaultPlan;
        when(strategyPlanService.getEnabledPlans(any())).thenReturn(List.of(defaultPlan));
        // scanForPlan gates every plan through isWithinSession before scanning any symbol at
        // all -- an unstubbed boolean-returning mock defaults to false in Mockito, which would
        // make a test that never explicitly stubbed this silently skip straight past
        // scanOneSymbol, never reaching saveCall. A test that specifically wants the
        // outside-session path overrides this explicitly, as a few already do.
        when(strategyPlanService.isWithinSession(any())).thenReturn(true);

        credential = new BrokerCredential();
        credential.setId("cred1");
        credential.setBroker(BrokerType.BINANCE);
        credential.setMode(BrokerMode.TESTNET);
        credential.setActive(true);

        profile = new RiskProfile();
        profile.setId("profile1");
        profile.setUserId("user1");
        profile.setCredentialId("cred1");
        profile.setAutoTradeEnabled(true);
        profile.setMinConfidence(60.0);
        // TIER1 is not scanned unconditionally -- it must also be explicitly present in the
        // account's own risk-profile whitelist. This default fixture profile enables every
        // TIER1 symbol so every existing test in this file that exercises a TIER1 symbol
        // (overwhelmingly BTCUSDT) keeps working unchanged; a test that specifically wants to
        // prove the whitelist restriction itself overrides this.
        profile.setEnabledSymbols(new java.util.HashSet<>(AutonomousScannerService.TIER1_SYMBOLS));

        when(riskProfileRepo.findByAutoTradeEnabledTrueAndTradingHaltedFalse()).thenReturn(List.of(profile));
        when(credentialRepo.findById("cred1")).thenReturn(Optional.of(credential));
        // Default: "never signaled before" for tests that don't care about the cooldown
        // fallback -- matches an empty in-memory map, so nothing else in this file needs to
        // change.
        when(tradeCallRepository.findFirstByUserIdAndPlanIdAndSymbolOrderByCalledAtDesc(any(), any(), any()))
            .thenReturn(Optional.empty());
    }

    // Test helper: the account's own risk-profile whitelist, TIER1 plus one or more extra
    // symbols a specific test needs (e.g. a plan-only symbol like SCAMCOINUSDT/DOGEUSDT).
    private static java.util.Set<String> union(java.util.Set<String> base, String... extra) {
        var result = new java.util.HashSet<>(base);
        result.addAll(java.util.List.of(extra));
        return result;
    }

    private List<Candle> fakeCandles() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 60; i++) candles.add(new Candle(i * 3600L, 100, 101, 99, 100, 1000));
        return candles;
    }

    @Test
    @DisplayName("a plan with a restrictive profile whitelist (only ADAUSDT) never scans or dispatches TIER1 symbols like BTCUSDT, even though the plan itself exists and is enabled")
    void profileWhitelistRestrictsToOneSymbol_tier1NeverScannedOrExecuted() {
        profile.setEnabledSymbols(java.util.Set.of("ADAUSDT")); // the user's own explicit "only ADAUSDT" choice
        when(adapter.getRecentCandles(any(), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(signal);

        service.scan();

        // Not even a candle fetch for any TIER1 symbol -- the whitelist gate runs before
        // scanOneSymbol is ever called for a symbol outside it.
        verify(adapter, never()).getRecentCandles(eq("BTCUSDT"), any(), anyInt(), any());
        verify(adapter, never()).getRecentCandles(eq("ETHUSDT"), any(), anyInt(), any());
        verify(tradeCallService, never()).saveCall(any(), argThat(req -> !"ADAUSDT".equals(req.getSymbol())), anyBoolean());
    }

    @Test
    @DisplayName("scan: a qualifying LONG signal is dispatched through TradeCallService.saveCall — no browser/frontend request anywhere in the path")
    void qualifyingSignal_dispatchedAutonomously() {
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        when(adapter.getRecentCandles(argThat(s -> !"BTCUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(List.of());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(signal);

        service.scan();

        ArgumentCaptor<TradeCallRequest> reqCaptor = ArgumentCaptor.forClass(TradeCallRequest.class);
        verify(tradeCallService).saveCall(eq("user1"), reqCaptor.capture(), eq(true));
        TradeCallRequest req = reqCaptor.getValue();
        assertThat(req.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(req.getDirection()).isEqualTo("LONG");
        assertThat(req.getConfidence()).isEqualTo(75);
        assertThat(req.getEntryPrice()).isEqualTo(100.0);
        assertThat(req.getStopLoss()).isEqualTo(95.0);
    }

    @Test
    @DisplayName("scan: fetches this symbol's own learned ML weights via mlWeightService and passes them to analyze()")
    void scan_fetchesAndPassesMLWeights() {
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        when(adapter.getRecentCandles(argThat(s -> !"BTCUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(List.of());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        var weights = new com.tradevision.model.MLWeights();
        when(mlWeightService.getWeights("CRYPTO", "BTCUSDT")).thenReturn(weights);
        when(serverSignalEngine.analyze(any(), eq(weights))).thenReturn(signal);

        service.scan();

        verify(mlWeightService).getWeights("CRYPTO", "BTCUSDT");
        verify(serverSignalEngine).analyze(any(), eq(weights));
    }

    @Test
    @DisplayName("scan: mlWeightService.getWeights throwing (a transient MongoDB failure) does NOT prevent the scan from proceeding -- falls back to null (fixed-weight scoring), the same graceful-degradation reasoning as this class's own broader error handling elsewhere")
    void scan_mlWeightsFetchFails_fallsBackGracefullyRatherThanAbortingScan() {
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        when(adapter.getRecentCandles(argThat(s -> !"BTCUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(List.of());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(mlWeightService.getWeights(any(), any())).thenThrow(new RuntimeException("simulated Mongo failure"));
        when(serverSignalEngine.analyze(any(), isNull())).thenReturn(signal);

        service.scan();

        // The scan must still complete and dispatch the signal, not abort just because the
        // weights lookup failed.
        verify(tradeCallService).saveCall(eq("user1"), any(), eq(true));
    }

    @Test
    @DisplayName("scan: a second scan against the SAME closed candle never re-runs strategy analysis, even after the unrelated signal cooldown has expired -- isolated from the pre-existing time-based cooldown by clearing it between calls so this test verifies the candle-dedup mechanism specifically, not the cooldown incidentally blocking the second call too")
    void sameClosedCandle_secondScanSkipsAnalysisEntirely() {
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        when(adapter.getRecentCandles(argThat(s -> !"BTCUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(List.of());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(signal);

        service.scan();
        // Clear the unrelated, pre-existing time-based cooldown so it can't be the reason a
        // second dispatch doesn't happen -- isolates the candle-dedup mechanism specifically.
        ReflectionTestUtils.setField(service, "lastSignalAt", new java.util.concurrent.ConcurrentHashMap<>());
        service.scan();

        // analyze() was called exactly once across BOTH scans -- the second one's own candles
        // are identical (fakeCandles() is deterministic), so the new candle-dedup check skips
        // re-analysis entirely, never even reaching serverSignalEngine.analyze() the second time.
        verify(serverSignalEngine, times(1)).analyze(any(), any());
    }

    // ── SMC/regime/volume-profile enrichment ────────────────────────────────────

    @Test
    @DisplayName("scan: a qualifying signal has TradeCallRequest's smcBias/regime/vpLocation fields populated from the real ported services, not a no-op stub")
    void qualifyingSignal_populatesEnrichmentFields() {
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        when(adapter.getRecentCandles(argThat(s -> !"BTCUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(List.of());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(signal);
        when(smcEngineService.analyze(any(), any())).thenReturn(
            new com.tradevision.service.strategy.dto.SMCAnalysis(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                new com.tradevision.service.strategy.dto.SMCAnalysis.PremiumDiscount(100, 110, 90, "DISCOUNT", 0.4),
                "BULL_TREND", "BULLISH", 75, List.of(), "", null));
        when(marketRegimeService.detect(any(), any())).thenReturn(
            new com.tradevision.service.strategy.dto.RegimeState("BULL_TREND", 80, "Bull Trend", "B", "#0f0", 10, 25.5, 0.04, 1.2, 40, 1.1,
                new com.tradevision.service.strategy.dto.RegimeState.Strategy("x", "x", 1.1, 1.5, 3.5),
                new com.tradevision.service.strategy.dto.RegimeState.WeightAdjustments(1, 1, 1, 1, 1, 1, 1, 1), "", List.of()));
        when(volumeProfileService.analyze(any(), anyInt())).thenReturn(
            new com.tradevision.service.strategy.dto.VolumeProfile(98, 102, 96, List.of(), List.of(), List.of(), 100, "INSIDE_VA", "NEUTRAL", null, null, "", List.of()));

        service.scan();

        ArgumentCaptor<TradeCallRequest> reqCaptor = ArgumentCaptor.forClass(TradeCallRequest.class);
        verify(tradeCallService).saveCall(eq("user1"), reqCaptor.capture(), eq(true));
        TradeCallRequest req = reqCaptor.getValue();
        assertThat(req.getSmcBias()).isEqualTo("BULLISH");
        assertThat(req.getSmcBiasStrength()).isEqualTo(75);
        assertThat(req.getPdZone()).isEqualTo("DISCOUNT");
        assertThat(req.getRegime()).isEqualTo("BULL_TREND");
        assertThat(req.getRegimeAdx()).isEqualTo(25.5);
        assertThat(req.getVpLocation()).isEqualTo("INSIDE_VA");
        assertThat(req.getVpPoc()).isEqualTo(98.0);
        // direction/confidence/entryPrice remain driven by ServerSignalEngine + the existing
        // pre-filter gates, completely unaffected by this enrichment (observability fields only).
        assertThat(req.getDirection()).isEqualTo("LONG");
        assertThat(req.getConfidence()).isEqualTo(75);
    }

    @Test
    @DisplayName("scan: SMC/regime/volume-profile enrichment failing (e.g. a bug in one of those services) never blocks the underlying signal from being dispatched -- additive, non-fatal by design")
    void enrichmentFailure_doesNotBlockSignalDispatch() {
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        when(adapter.getRecentCandles(argThat(s -> !"BTCUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(List.of());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(signal);
        when(smcEngineService.analyze(any(), any())).thenThrow(new RuntimeException("simulated SMC engine bug"));
        // marketRegimeService/volumeProfileService left unstubbed -- return null, which the
        // real code never reaches anyway since smcEngineService.analyze() throws first, but
        // this confirms the try/catch wraps the WHOLE enrichment block, not just one call.

        service.scan();

        ArgumentCaptor<TradeCallRequest> reqCaptor = ArgumentCaptor.forClass(TradeCallRequest.class);
        verify(tradeCallService).saveCall(eq("user1"), reqCaptor.capture(), eq(true));
        TradeCallRequest req = reqCaptor.getValue();
        assertThat(req.getDirection()).isEqualTo("LONG"); // dispatched successfully despite the enrichment failure
        assertThat(req.getSmcBias()).isNull(); // enrichment fields simply stay unset, not fabricated
    }

    @Test
    @DisplayName("scan: order-flow and MTF context are populated from the real ported services when a higher timeframe is genuinely available")
    void qualifyingSignal_populatesOrderFlowAndMtfFields() {
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        when(adapter.getRecentCandles(argThat(s -> !"BTCUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(List.of());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(signal);
        when(orderFlowService.analyze("BTCUSDT")).thenReturn(
            new com.tradevision.service.strategy.dto.OrderFlowAnalysis(
                new com.tradevision.service.strategy.dto.OrderFlowAnalysis.FundingData("BTCUSDT", 0.02, 0.02, "LONG_BIAS", ""),
                new com.tradevision.service.strategy.dto.OrderFlowAnalysis.OpenInterestData("BTCUSDT", 1000, 1, 1, 1, "LONGS_BUILDING", ""),
                new com.tradevision.service.strategy.dto.OrderFlowAnalysis.CVDData(100, "RISING", 200, 100, ""),
                "BULL", 45, List.of(), "", true));
        when(signalCombinerService.combine(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(
            new com.tradevision.service.strategy.dto.CombinedSignal("LONG", "BUY", 80,
                BigDecimal.valueOf(100), BigDecimal.valueOf(95), BigDecimal.valueOf(110), BigDecimal.valueOf(120), BigDecimal.valueOf(130),
                null, null, null, null, null, null,
                List.of(new com.tradevision.service.strategy.dto.MTFContext("4h", "UP", 40, true, true, true, 30),
                        new com.tradevision.service.strategy.dto.MTFContext("1d", "STRONG_UP", 45, true, true, true, 60)),
                "Partial alignment", null, null, null, "BULL", 45.0, List.of(), null, null, null, null, false, ""));

        service.scan();

        ArgumentCaptor<TradeCallRequest> reqCaptor = ArgumentCaptor.forClass(TradeCallRequest.class);
        verify(tradeCallService).saveCall(eq("user1"), reqCaptor.capture(), eq(true));
        TradeCallRequest req = reqCaptor.getValue();
        assertThat(req.getOfBias()).isEqualTo("BULL");
        assertThat(req.getOfScore()).isEqualTo(45);
        assertThat(req.getFundingRate()).isEqualTo(0.02);
        assertThat(req.getOiSignal()).isEqualTo("LONGS_BUILDING");
        assertThat(req.getCvdTrend()).isEqualTo("RISING");
        assertThat(req.getMtfAlignment()).isEqualTo("Partial alignment");
        assertThat(req.getHtf1Trend()).isEqualTo("UP");
        assertThat(req.getHtf2Trend()).isEqualTo("STRONG_UP");
        // direction/confidence remain the base signal's own values -- the combined signal's own
        // (different) confidence of 80 is never used for execution, even with order-flow/MTF
        // wired in too.
        assertThat(req.getDirection()).isEqualTo("LONG");
        assertThat(req.getConfidence()).isEqualTo(75);
    }

    @Test
    @DisplayName("scan: an unhealthy request-weight budget skips order-flow/MTF enrichment entirely (never even calls orderFlowService), but the rest of the scan still completes normally")
    void unhealthyRequestBudget_skipsOrderFlowEnrichment_butScanStillCompletes() {
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        when(adapter.getRecentCandles(argThat(s -> !"BTCUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(List.of());
        ServerSignalEngine.Signal signal2 = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(signal2);
        when(exchangeHealth.checkRequestBudget()).thenReturn(new ExchangeHealthService.RequestBudgetStatus(5800, 6000, 0.967, false));

        service.scan();

        verifyNoInteractions(orderFlowService);
        ArgumentCaptor<TradeCallRequest> reqCaptor2 = ArgumentCaptor.forClass(TradeCallRequest.class);
        verify(tradeCallService).saveCall(eq("user1"), reqCaptor2.capture(), eq(true));
        TradeCallRequest req2 = reqCaptor2.getValue();
        assertThat(req2.getOfBias()).isNull(); // skipped, not fabricated
        assertThat(req2.getMtfAlignment()).isNull();
        // the rest of the scan -- the actual signal dispatch -- still completes normally.
        assertThat(req2.getDirection()).isEqualTo("LONG");
        assertThat(req2.getConfidence()).isEqualTo(75);
    }

    // ── liquidity-aware universe ──

    @Test
    @DisplayName("scan: a user-added, low-liquidity symbol is skipped entirely (never even reaches serverSignalEngine.analyze)")
    void userAddedLowLiquiditySymbol_skippedEntirely() {
        defaultPlan.setEnabledSymbols(java.util.Set.of("SCAMCOINUSDT")); // symbol universe is owned by the plan, not the profile
        // The plan's own universe must still survive intersection with the profile's own whitelist.
        profile.setEnabledSymbols(union(AutonomousScannerService.TIER1_SYMBOLS, "SCAMCOINUSDT"));
        List<Candle> illiquidCandles = new ArrayList<>();
        for (int i = 0; i < 60; i++) illiquidCandles.add(new Candle(i * 3600L, 0.001, 0.0011, 0.0009, 0.001, 500)); // 500 * 0.001 = $0.50/candle avg quote volume
        when(adapter.getRecentCandles(eq("SCAMCOINUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(illiquidCandles);
        when(adapter.getRecentCandles(argThat(s -> !"SCAMCOINUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(fakeCandles());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(signal);

        service.scan();

        // The illiquid symbol's own candles were fetched (that's unavoidable -- liquidity can
        // only be judged from the data), but analyze() was never called for it specifically --
        // confirmed by checking the argument, since TIER1 symbols DO still call analyze().
        verify(serverSignalEngine, never()).analyze(eq(illiquidCandles.subList(0, illiquidCandles.size() - 1)), any());
    }

    @Test
    @DisplayName("scan: a user-added symbol with sufficient liquidity is NOT skipped -- this check only filters genuinely illiquid symbols, not every non-TIER1 one")
    void userAddedSufficientLiquiditySymbol_notSkipped() {
        defaultPlan.setEnabledSymbols(java.util.Set.of("DECENTCOINUSDT")); // symbol universe is owned by the plan, not the profile
        // The plan's own universe must still survive intersection with the profile's own whitelist.
        profile.setEnabledSymbols(union(AutonomousScannerService.TIER1_SYMBOLS, "DECENTCOINUSDT"));
        // fakeCandles() itself: close=100, volume=1000 -> $100,000 avg quote volume, exactly AT
        // (not below) the threshold -- deliberately reused here to confirm the boundary itself
        // doesn't wrongly exclude a symbol that meets it.
        when(adapter.getRecentCandles(eq("DECENTCOINUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        when(adapter.getRecentCandles(argThat(s -> !"DECENTCOINUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(List.of());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(signal);

        service.scan();

        verify(tradeCallService).saveCall(eq("user1"), argThat(req -> "DECENTCOINUSDT".equals(req.getSymbol())), eq(true));
    }

    @Test
    @DisplayName("scan: order-flow/MTF enrichment failing never blocks signal dispatch, and never discards SMC/regime/volume-profile enrichment already captured -- independent per-component failure isolation")
    void orderFlowEnrichmentFailure_doesNotDiscardOtherEnrichment() {
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        when(adapter.getRecentCandles(argThat(s -> !"BTCUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(List.of());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(signal);
        when(smcEngineService.analyze(any(), any())).thenReturn(
            new com.tradevision.service.strategy.dto.SMCAnalysis(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                "BULL_TREND", "BULLISH", 75, List.of(), "", null));
        when(marketRegimeService.detect(any(), any())).thenReturn(
            new com.tradevision.service.strategy.dto.RegimeState("BULL_TREND", 80, "Bull Trend", "B", "#0f0", 10, 25.5, 0.04, 1.2, 40, 1.1,
                new com.tradevision.service.strategy.dto.RegimeState.Strategy("x", "x", 1.1, 1.5, 3.5),
                new com.tradevision.service.strategy.dto.RegimeState.WeightAdjustments(1, 1, 1, 1, 1, 1, 1, 1), "", List.of()));
        when(volumeProfileService.analyze(any(), anyInt())).thenReturn(
            new com.tradevision.service.strategy.dto.VolumeProfile(98, 102, 96, List.of(), List.of(), List.of(), 100, "INSIDE_VA", "NEUTRAL", null, null, "", List.of()));
        when(orderFlowService.analyze(any())).thenThrow(new RuntimeException("simulated futures API outage"));

        service.scan();

        ArgumentCaptor<TradeCallRequest> reqCaptor = ArgumentCaptor.forClass(TradeCallRequest.class);
        verify(tradeCallService).saveCall(eq("user1"), reqCaptor.capture(), eq(true));
        TradeCallRequest req = reqCaptor.getValue();
        assertThat(req.getDirection()).isEqualTo("LONG"); // still dispatched
        assertThat(req.getSmcBias()).isEqualTo("BULLISH"); // SMC enrichment survives the order-flow failure
        assertThat(req.getOfBias()).isNull(); // order-flow fields simply stay unset, not fabricated
    }

    @Test
    @DisplayName("scan: a SHORT/WAIT direction is never dispatched — matches this codebase's own spot-only, long-only design everywhere else")
    void nonLongDirection_neverDispatched() {
        when(adapter.getRecentCandles(any(), any(), anyInt(), any())).thenReturn(fakeCandles());
        ServerSignalEngine.Signal shortSignal = new ServerSignalEngine.Signal("SHORT", "SELL", 80.0, 100.0, 105.0, 90.0, 80.0, 70.0, 20, 60, -40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(shortSignal);

        service.scan();

        verify(tradeCallService, never()).saveCall(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("scan: a SHORT/bearish signal, with the plan's own exitOnSignalReversal enabled, emergency-flattens an OPEN position this exact plan holds on this exact symbol -- the user's own explicit design (\"15m strategy changes strongly bearish? -> EXIT\")")
    void signalReversal_planOptedIn_emergencyFlattensOwnPosition() {
        defaultPlan.setExitOnSignalReversal(true);
        when(adapter.getRecentCandles(any(), any(), anyInt(), any())).thenReturn(fakeCandles());
        ServerSignalEngine.Signal shortSignal = new ServerSignalEngine.Signal("SHORT", "SELL", 80.0, 100.0, 105.0, 90.0, 80.0, 70.0, 20, 60, -40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(shortSignal);
        Position openPosition = new Position();
        openPosition.setId("pos1"); openPosition.setStatus("OPEN");
        when(positionRepo.findByPlanIdAndSymbolAndStatus(eq("plan1"), any(), eq("OPEN"))).thenReturn(List.of(openPosition));

        service.scan();

        // atLeastOnce, not exactly once: the shared serverSignalEngine.analyze stub applies to
        // every TIER1 symbol this scan processes, and the shared positionRepo stub matches any
        // symbol too -- this test verifies the reversal-exit mechanism actually fires, not a
        // specific call count that depends on how many symbols happen to trigger it.
        // A signal-reversal exit is routine, plan-configured behavior, not a protection
        // failure -- it goes through exitPosition() so a clean close doesn't halt the profile.
        verify(positionSafetyService, atLeastOnce()).exitPosition(eq(credential), eq(adapter), any(), any(), eq(openPosition), contains("SIGNAL_REVERSAL"));
    }

    @Test
    @DisplayName("scan: a SHORT/bearish signal, with exitOnSignalReversal NOT enabled (the default), never even queries for an open position -- this feature is opt-in per plan, never a silent behavior change")
    void signalReversal_planNotOptedIn_neverChecksOrFlattens() {
        when(adapter.getRecentCandles(any(), any(), anyInt(), any())).thenReturn(fakeCandles());
        ServerSignalEngine.Signal shortSignal = new ServerSignalEngine.Signal("SHORT", "SELL", 80.0, 100.0, 105.0, 90.0, 80.0, 70.0, 20, 60, -40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(shortSignal);

        service.scan();

        verify(positionRepo, never()).findByPlanIdAndSymbolAndStatus(any(), any(), any());
        verify(positionSafetyService, never()).emergencyFlatten(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("scan: confidence below the plan's own minConfidence is never dispatched — cheap pre-filter, not a substitute for AutoTradeService's own authoritative gate")
    void belowMinConfidence_neverDispatched() {
        defaultPlan.setMinConfidence(80.0);
        when(adapter.getRecentCandles(any(), any(), anyInt(), any())).thenReturn(fakeCandles());
        ServerSignalEngine.Signal lowConfidence = new ServerSignalEngine.Signal("LONG", "BUY", 50.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(lowConfidence);

        service.scan();

        verify(tradeCallService, never()).saveCall(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("scan: insufficient candle history is never dispatched — refuses rather than analyzing on too little data")
    void insufficientCandles_neverDispatched() {
        when(adapter.getRecentCandles(any(), any(), anyInt(), any())).thenReturn(List.of(new Candle(0, 100, 101, 99, 100, 1000))); // only 1 candle

        service.scan();

        verify(serverSignalEngine, never()).analyze(any(), any());
        verify(tradeCallService, never()).saveCall(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("scan: a market-data quality failure blocks the symbol before analysis even runs")
    void marketDataQualityFailure_blocksBeforeAnalysis() {
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        when(adapter.getRecentCandles(argThat(s -> !"BTCUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(List.of());
        when(marketDataQualityService.isMarketSafeToTrade(eq("BTCUSDT"), any(), anyLong(), any(), any()))
            .thenReturn(new MarketDataQualityService.QualityResult(false, List.of("Spread too wide: 5.0%")));

        service.scan();

        verify(serverSignalEngine, never()).analyze(any(), any());
        verify(tradeCallService, never()).saveCall(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("scan: the forming (last, potentially incomplete) candle is dropped before strategy analysis — only closed candles reach ServerSignalEngine")
    void formingCandle_droppedBeforeAnalysis() {
        List<Candle> allCandles = fakeCandles(); // 60 candles: index 0..59
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(allCandles);
        when(adapter.getRecentCandles(argThat(s -> !"BTCUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(List.of());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(signal);

        service.scan();

        ArgumentCaptor<List<Candle>> candlesCaptor = ArgumentCaptor.forClass(List.class);
        verify(serverSignalEngine).analyze(candlesCaptor.capture(), any());
        List<Candle> analyzed = candlesCaptor.getValue();
        assertThat(analyzed).hasSize(allCandles.size() - 1); // one fewer than fetched
        assertThat(analyzed).doesNotContain(allCandles.get(allCandles.size() - 1)); // the last (forming) candle specifically excluded
        assertThat(analyzed.get(analyzed.size() - 1)).isEqualTo(allCandles.get(allCandles.size() - 2)); // the new last element is the second-to-last fetched candle
    }

    // ── Config-driven scan timeframe ("Config for scanner universe / interval") ──────────────

    @Test
    @DisplayName("timeframeToSeconds: every Binance-supported interval this scanner could realistically use converts correctly, verified against Binance's own official docs")
    void timeframeToSeconds_realIntervals() {
        assertThat(AutonomousScannerService.timeframeToSeconds("1m")).isEqualTo(60);
        assertThat(AutonomousScannerService.timeframeToSeconds("15m")).isEqualTo(900);
        assertThat(AutonomousScannerService.timeframeToSeconds("1h")).isEqualTo(3600);
        assertThat(AutonomousScannerService.timeframeToSeconds("4h")).isEqualTo(14400);
        assertThat(AutonomousScannerService.timeframeToSeconds("1d")).isEqualTo(86400);
    }

    @Test
    @DisplayName("timeframeToSeconds: \"1m\" (minutes) and \"1M\" (months) are distinguished correctly — Binance's own case-sensitive convention")
    void timeframeToSeconds_caseSensitiveMinutesVsMonths() {
        assertThat(AutonomousScannerService.timeframeToSeconds("1m")).isEqualTo(60);
        assertThat(AutonomousScannerService.timeframeToSeconds("1M")).isEqualTo(2592000);
    }

    @Test
    @DisplayName("timeframeToSeconds: null or an unrecognized value falls back to the safe 3600-second (1h) default rather than throwing")
    void timeframeToSeconds_invalidFallsBackSafely() {
        assertThat(AutonomousScannerService.timeframeToSeconds(null)).isEqualTo(3600);
        assertThat(AutonomousScannerService.timeframeToSeconds("not-a-real-interval")).isEqualTo(3600);
    }

    @Test
    @DisplayName("scan: a profile's own scanTimeframe is what actually gets requested from the broker, not the hardcoded default")
    void scan_usesProfilesOwnScanTimeframe() {
        defaultPlan.setTimeframe("15m"); // timeframe is owned by the plan, not the profile
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());

        service.scan();

        verify(adapter).getRecentCandles(eq("BTCUSDT"), eq("15m"), anyInt(), eq(BrokerMode.TESTNET));
    }

    @Test
    @DisplayName("scan: a profile with no scanTimeframe set (blank) falls back to the default \"1h\", not an empty/invalid request")
    void scan_blankScanTimeframe_fallsBackToDefault() {
        defaultPlan.setTimeframe(""); // blank plan timeframe falls back to the default
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());

        service.scan();

        verify(adapter).getRecentCandles(eq("BTCUSDT"), eq("1h"), anyInt(), eq(BrokerMode.TESTNET));
    }

    @Test
    @DisplayName("scan: a symbol already signaled within the cooldown window is skipped on the next scan cycle")
    void withinCooldown_skipsResignal() {
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        when(adapter.getRecentCandles(argThat(s -> !"BTCUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(List.of());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(signal);

        service.scan(); // first cycle: dispatches
        service.scan(); // second cycle, immediately after: should be skipped by the cooldown

        verify(tradeCallService, times(1)).saveCall(any(), any(), eq(true));
    }

    @Test
    @DisplayName("scan: does nothing before startup reconciliation has confirmed real broker state")
    void beforeStartupTradingEnabled_doesNothing() {
        when(startupState.isTradingEnabled()).thenReturn(false);

        service.scan();

        verify(riskProfileRepo, never()).findByAutoTradeEnabledTrueAndTradingHaltedFalse();
    }

    @Test
    @DisplayName("scan: does nothing during shutdown")
    void duringShutdown_doesNothing() {
        when(shutdownState.isShuttingDown()).thenReturn(true);

        service.scan();

        verify(riskProfileRepo, never()).findByAutoTradeEnabledTrueAndTradingHaltedFalse();
    }

    @Test
    @DisplayName("scan: a profile with autoTradeHalted=true is skipped, even though tradingHalted (the broader flag) is false — the strategy consecutive-loss breaker actually taking effect")
    void autoTradeHaltedProfile_skipped() {
        profile.setAutoTradeHalted(true);
        when(adapter.getRecentCandles(any(), any(), anyInt(), any())).thenReturn(fakeCandles());

        service.scan();

        verify(serverSignalEngine, never()).analyze(any(), any());
        verify(tradeCallService, never()).saveCall(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("scan: one profile's scan failing (bad credential, adapter error) doesn't stop the scan for other profiles in the same pass")
    void oneProfileFails_othersStillScanned() {
        RiskProfile secondProfile = new RiskProfile();
        secondProfile.setId("profile2");
        secondProfile.setUserId("user2");
        secondProfile.setCredentialId("cred2");
        secondProfile.setAutoTradeEnabled(true);
        secondProfile.setMinConfidence(60.0);
        secondProfile.setEnabledSymbols(new java.util.HashSet<>(AutonomousScannerService.TIER1_SYMBOLS)); // BTCUSDT must be explicitly whitelisted
        when(riskProfileRepo.findByAutoTradeEnabledTrueAndTradingHaltedFalse()).thenReturn(List.of(profile, secondProfile));
        when(credentialRepo.findById("cred1")).thenThrow(new RuntimeException("simulated database error"));

        BrokerCredential credential2 = new BrokerCredential();
        credential2.setId("cred2");
        credential2.setBroker(BrokerType.BINANCE);
        credential2.setMode(BrokerMode.TESTNET);
        credential2.setActive(true);
        when(credentialRepo.findById("cred2")).thenReturn(Optional.of(credential2));
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        when(adapter.getRecentCandles(argThat(s -> !"BTCUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(List.of());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(signal);

        service.scan(); // must not throw despite profile1's credential lookup failing

        verify(tradeCallService).saveCall(eq("user2"), any(), eq(true)); // profile2's scan still ran
    }

    /**
     * Tests for the shared candidate-gathering logic Kline1mStreamService relies on.
     */
    @Test
    @DisplayName("compute1mScanTargets: a 1m plan within session produces one target per TIER1/enabledSymbols symbol")
    void compute1mScanTargets_1mPlanWithinSession_producesTargets() {
        defaultPlan.setTimeframe("1m");
        defaultPlan.setEnabledSymbols(java.util.Set.of("DOGEUSDT"));
        profile.setEnabledSymbols(union(AutonomousScannerService.TIER1_SYMBOLS, "DOGEUSDT"));
        when(strategyPlanService.isWithinSession(defaultPlan)).thenReturn(true);

        var targets = service.compute1mScanTargets();

        assertThat(targets).extracting(AutonomousScannerService.OneMinuteScanTarget::symbol)
            .contains("BTCUSDT", "DOGEUSDT"); // TIER1 plus the plan's own explicit symbol
        assertThat(targets).allMatch(t -> t.plan() == defaultPlan && t.profile() == profile && t.credential() == credential);
    }

    @Test
    @DisplayName("compute1mScanTargets: a plan with a different timeframe (not 1m) produces no targets at all")
    void compute1mScanTargets_nonOneMinutePlan_producesNoTargets() {
        defaultPlan.setTimeframe("5m");
        when(strategyPlanService.isWithinSession(defaultPlan)).thenReturn(true);

        var targets = service.compute1mScanTargets();

        assertThat(targets).isEmpty();
    }

    @Test
    @DisplayName("compute1mScanTargets: a 1m plan outside its own configured session produces no targets -- same session rule the periodic scanner already respects")
    void compute1mScanTargets_1mPlanOutsideSession_producesNoTargets() {
        defaultPlan.setTimeframe("1m");
        when(strategyPlanService.isWithinSession(defaultPlan)).thenReturn(false);

        var targets = service.compute1mScanTargets();

        assertThat(targets).isEmpty();
    }

    /**
     * Tests for the cross-instance candle claim. Private method, accessed via reflection.
     */
    private boolean invokeTryClaim(String candleDedupKey, long candleCloseTimeMillis, BrokerMode mode) throws Exception {
        var m = AutonomousScannerService.class.getDeclaredMethod("tryClaimCandleProcessing", String.class, long.class, BrokerMode.class);
        m.setAccessible(true);
        return (boolean) m.invoke(service, candleDedupKey, candleCloseTimeMillis, mode);
    }

    @Test
    @DisplayName("tryClaimCandleProcessing: a successful save claims the candle -- returns true")
    void tryClaimCandleProcessing_saveSucceeds_returnsTrue() throws Exception {
        when(scannedCandleRepo.save(any())).thenReturn(new com.tradevision.model.ScannedCandle());

        boolean result = invokeTryClaim("cred1|BTCUSDT|1h", 12345L, BrokerMode.LIVE);

        assertThat(result).isTrue();
        verify(scannedCandleRepo).save(argThat(c -> "cred1|BTCUSDT|1h|12345".equals(c.getClaimKey())));
    }

    @Test
    @DisplayName("tryClaimCandleProcessing: a DuplicateKeyException means this exact candle was already claimed -- returns false, the real cross-instance guarantee, regardless of mode")
    void tryClaimCandleProcessing_duplicateKey_returnsFalse() throws Exception {
        when(scannedCandleRepo.save(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("simulated duplicate"));

        boolean result = invokeTryClaim("cred1|BTCUSDT|1h", 12345L, BrokerMode.LIVE);

        assertThat(result).isFalse();
    }

    /**
     * The failure-handling policy differs by mode: LIVE fails closed, TESTNET/PAPER fails open.
     */
    @Test
    @DisplayName("tryClaimCandleProcessing: for LIVE, any OTHER exception (e.g. a transient connection failure) now fails CLOSED -- returns false, since an unknown duplicate evaluation is worse than a missed signal for real money")
    void tryClaimCandleProcessing_otherException_liveFailsClosed() throws Exception {
        when(scannedCandleRepo.save(any())).thenThrow(new RuntimeException("simulated transient connection failure"));

        boolean result = invokeTryClaim("cred1|BTCUSDT|1h", 12345L, BrokerMode.LIVE);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("tryClaimCandleProcessing: for TESTNET/PAPER, any OTHER exception still fails OPEN -- returns true, since no real money is at risk there")
    void tryClaimCandleProcessing_otherException_testnetFailsOpen() throws Exception {
        when(scannedCandleRepo.save(any())).thenThrow(new RuntimeException("simulated transient connection failure"));

        boolean result = invokeTryClaim("cred1|BTCUSDT|1h", 12345L, BrokerMode.TESTNET);

        assertThat(result).isTrue();
    }

    /**
     * Simulates "this is a fresh process" (the in-memory lastSignalAt map is empty, exactly as
     * it would be right after a restart) by never calling scan() a first time in this test, and
     * instead stubbing the durable repository fallback directly to return a very recent
     * TradeCallRecord for this exact user+plan+symbol -- proving the durable fallback enforces
     * the cooldown even when the in-memory map alone would say "never signaled".
     */
    @Test
    @DisplayName("scan: a recent persisted TradeCallRecord for this exact user+plan+symbol still enforces cooldown even with an empty in-memory map (simulating a just-restarted process)")
    void cooldown_fallsBackToPersistedTradeCallRecord_afterSimulatedRestart() {
        defaultPlan.setCooldownMinutes(15);
        var recentCall = new TradeCallRecord();
        recentCall.setCalledAt(java.time.LocalDateTime.now().minusMinutes(2)); // well inside a 15-minute cooldown
        when(tradeCallRepository.findFirstByUserIdAndPlanIdAndSymbolOrderByCalledAtDesc("user1", "plan1", "BTCUSDT"))
            .thenReturn(Optional.of(recentCall));
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(signal);

        service.scan();

        verify(tradeCallService, never()).saveCall(any(), argThat(req -> "BTCUSDT".equals(req.getSymbol())), anyBoolean());
    }

    @Test
    @DisplayName("scan: a persisted TradeCallRecord OLDER than the plan's own cooldown does not block a new signal, and the in-memory map is repopulated from it so a second scan doesn't re-query the repository")
    void cooldown_persistedRecordOlderThanCooldown_allowsNewSignal_andCachesInMemory() {
        defaultPlan.setCooldownMinutes(15);
        var oldCall = new TradeCallRecord();
        oldCall.setCalledAt(java.time.LocalDateTime.now().minusMinutes(30)); // outside the 15-minute cooldown
        when(tradeCallRepository.findFirstByUserIdAndPlanIdAndSymbolOrderByCalledAtDesc("user1", "plan1", "BTCUSDT"))
            .thenReturn(Optional.of(oldCall));
        when(adapter.getRecentCandles(eq("BTCUSDT"), anyString(), anyInt(), eq(BrokerMode.TESTNET))).thenReturn(fakeCandles());
        when(adapter.getRecentCandles(argThat(s -> !"BTCUSDT".equals(s)), anyString(), anyInt(), any())).thenReturn(List.of());
        ServerSignalEngine.Signal signal = new ServerSignalEngine.Signal("LONG", "BUY", 75.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(serverSignalEngine.analyze(any(), any())).thenReturn(signal);

        service.scan();
        verify(tradeCallService, times(1)).saveCall(eq("user1"), argThat(req -> "BTCUSDT".equals(req.getSymbol())), eq(true));

        // A second scan immediately after: the in-memory map now holds this process's own fresh
        // Instant.now() from the just-dispatched signal above (set right after saveCall), which
        // alone is enough to enforce the cooldown -- the repository fallback must not be queried
        // again for the same key.
        service.scan();
        verify(tradeCallRepository, times(1))
            .findFirstByUserIdAndPlanIdAndSymbolOrderByCalledAtDesc("user1", "plan1", "BTCUSDT");
    }
}
