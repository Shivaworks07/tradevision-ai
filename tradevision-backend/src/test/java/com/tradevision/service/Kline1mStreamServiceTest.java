package com.tradevision.service;

import com.tradevision.model.BrokerCredential;
import com.tradevision.model.RiskProfile;
import com.tradevision.model.StrategyPlan;
import com.tradevision.service.broker.BrokerAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers the message-handling logic that turns a closed-1m-candle event into a scanOneSymbol
 * trigger. handleMessage() is private and this service's real network connection can't be
 * exercised in a test (same limitation as BinanceUserDataStreamServiceTest), so it is accessed
 * via reflection with the routingBySymbol field populated directly, exactly what
 * reconcileSubscriptions() would have built from a real compute1mScanTargets() call.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Kline1mStreamServiceTest {

    @Mock AutonomousScannerService scannerService;
    @Mock com.tradevision.config.ShutdownState shutdownState;
    @Mock BrokerAdapter adapter;

    private Kline1mStreamService service;
    private RiskProfile profile;
    private BrokerCredential credential;
    private StrategyPlan plan;

    @BeforeEach
    void setUp() {
        service = new Kline1mStreamService(scannerService, shutdownState);
        profile = new RiskProfile(); profile.setId("profile1"); profile.setUserId("user1"); profile.setCredentialId("cred1");
        credential = new BrokerCredential(); credential.setId("cred1");
        plan = new StrategyPlan(); plan.setId("plan1"); plan.setTimeframe("1m");
    }

    private void invokeHandleMessage(String json) throws Exception {
        Method m = Kline1mStreamService.class.getDeclaredMethod("handleMessage", String.class);
        m.setAccessible(true);
        m.invoke(service, json);
    }

    private void setRouting(String symbol, AutonomousScannerService.OneMinuteScanTarget target) throws Exception {
        var field = Kline1mStreamService.class.getDeclaredField("routingBySymbol");
        field.setAccessible(true);
        field.set(service, Map.of(symbol, List.of(target)));
    }

    @Test
    @DisplayName("handleMessage: a genuinely closed (x=true) kline for a routed symbol triggers scanOneSymbol for every target watching it")
    void closedCandleForRoutedSymbol_triggersScanOneSymbol() throws Exception {
        var target = new AutonomousScannerService.OneMinuteScanTarget(profile, credential, adapter, plan, "BTCUSDT");
        setRouting("BTCUSDT", target);
        String json = "{\"stream\":\"btcusdt@kline_1m\",\"data\":{\"e\":\"kline\",\"s\":\"BTCUSDT\",\"k\":{\"s\":\"BTCUSDT\",\"i\":\"1m\",\"x\":true,\"o\":\"100\",\"c\":\"101\",\"h\":\"102\",\"l\":\"99\",\"v\":\"5\"}}}";

        invokeHandleMessage(json);

        verify(scannerService).scanOneSymbol(profile, credential, adapter, "BTCUSDT", plan);
    }

    @Test
    @DisplayName("handleMessage: a NOT-yet-closed (x=false) kline never triggers scanOneSymbol -- an in-progress candle must never be treated as a real close event")
    void inProgressCandle_neverTriggersScanOneSymbol() throws Exception {
        var target = new AutonomousScannerService.OneMinuteScanTarget(profile, credential, adapter, plan, "BTCUSDT");
        setRouting("BTCUSDT", target);
        String json = "{\"stream\":\"btcusdt@kline_1m\",\"data\":{\"e\":\"kline\",\"s\":\"BTCUSDT\",\"k\":{\"s\":\"BTCUSDT\",\"i\":\"1m\",\"x\":false,\"o\":\"100\",\"c\":\"101\",\"h\":\"102\",\"l\":\"99\",\"v\":\"5\"}}}";

        invokeHandleMessage(json);

        verify(scannerService, never()).scanOneSymbol(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("handleMessage: a closed candle for a symbol nobody is currently watching is silently ignored, not an error")
    void closedCandleForUnroutedSymbol_isIgnored() throws Exception {
        var target = new AutonomousScannerService.OneMinuteScanTarget(profile, credential, adapter, plan, "BTCUSDT");
        setRouting("BTCUSDT", target); // only BTCUSDT is routed
        String json = "{\"stream\":\"ethusdt@kline_1m\",\"data\":{\"e\":\"kline\",\"s\":\"ETHUSDT\",\"k\":{\"s\":\"ETHUSDT\",\"i\":\"1m\",\"x\":true,\"o\":\"100\",\"c\":\"101\",\"h\":\"102\",\"l\":\"99\",\"v\":\"5\"}}}";

        invokeHandleMessage(json);

        verify(scannerService, never()).scanOneSymbol(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("handleMessage: a non-kline event type (e.g. a future stream this service was never meant to handle) is ignored, not an error")
    void nonKlineEventType_isIgnored() throws Exception {
        String json = "{\"stream\":\"btcusdt@trade\",\"data\":{\"e\":\"trade\",\"s\":\"BTCUSDT\"}}";

        invokeHandleMessage(json); // must not throw

        verify(scannerService, never()).scanOneSymbol(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("handleMessage: one target's own scanOneSymbol throwing does not stop the other targets watching the same symbol from being triggered")
    void oneTargetFailure_doesNotStopTheOthers() throws Exception {
        var plan2 = new StrategyPlan(); plan2.setId("plan2"); plan2.setTimeframe("1m");
        var target1 = new AutonomousScannerService.OneMinuteScanTarget(profile, credential, adapter, plan, "BTCUSDT");
        var target2 = new AutonomousScannerService.OneMinuteScanTarget(profile, credential, adapter, plan2, "BTCUSDT");
        var field = Kline1mStreamService.class.getDeclaredField("routingBySymbol");
        field.setAccessible(true);
        field.set(service, Map.of("BTCUSDT", List.of(target1, target2)));
        doThrow(new RuntimeException("simulated failure")).when(scannerService).scanOneSymbol(profile, credential, adapter, "BTCUSDT", plan);
        String json = "{\"stream\":\"btcusdt@kline_1m\",\"data\":{\"e\":\"kline\",\"s\":\"BTCUSDT\",\"k\":{\"s\":\"BTCUSDT\",\"i\":\"1m\",\"x\":true,\"o\":\"100\",\"c\":\"101\",\"h\":\"102\",\"l\":\"99\",\"v\":\"5\"}}}";

        invokeHandleMessage(json); // must not throw despite target1's own scanOneSymbol throwing

        verify(scannerService).scanOneSymbol(profile, credential, adapter, "BTCUSDT", plan2);
    }
}
