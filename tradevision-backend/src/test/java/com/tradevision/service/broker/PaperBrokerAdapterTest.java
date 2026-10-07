package com.tradevision.service.broker;

import com.tradevision.model.BrokerMode;
import com.tradevision.model.PaperOco;
import com.tradevision.repository.PaperOcoRepository;
import com.tradevision.service.broker.dto.OrderRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Review finding ("No pure paper-trading mode with full isolation" -- external review,
 * eighteenth pass, P0, full context in PaperBrokerAdapter's own class javadoc): the actual
 * tests proving the two things that matter most about this class -- market orders simulate a
 * real, immediate fill at a genuinely fetched current price, and OCO resolution correctly
 * detects a real price crossing either trigger, entirely without ever sending an authenticated
 * request to Binance.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaperBrokerAdapterTest {

    @Mock BrokerAdapter realAdapter;
    @Mock PaperOcoRepository paperOcoRepo;
    @Mock java.util.Random random;

    private PaperBrokerAdapter service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new PaperBrokerAdapter(realAdapter, paperOcoRepo);
        // Review finding ("Add slippage/partial-fill simulation" -- external review, P3, full
        // context in PaperBrokerAdapter's own updated random field javadoc): defaults to NEVER
        // triggering a partial fill (nextInt(10) == 0 is the only trigger condition) -- every
        // existing test in this file stays fully deterministic unless a test explicitly
        // overrides this stub to test partial-fill behavior on its own.
        service.setRandomForTesting(random);
        when(random.nextInt(10)).thenReturn(1);
    }

    @Test
    @DisplayName("getType: never actually consulted for dispatch, but must still return a valid, non-null BrokerType")
    void getType_returnsBinance() {
        assertThat(service.getType()).isEqualTo(com.tradevision.model.BrokerType.BINANCE);
    }

    @Test
    @DisplayName("getAccountPermissions: always simulated as tradeable, never able to withdraw -- no real Binance call involved at all")
    void getAccountPermissions_alwaysSimulatedSafeDefaults() {
        var result = service.getAccountPermissions("anything", "anything", BrokerMode.PAPER);

        assertThat(result.canTrade()).isTrue();
        assertThat(result.canWithdraw()).isFalse();
        verifyNoInteractions(realAdapter);
    }

    @Test
    @DisplayName("getApiKeyRestrictions: always simulated as safe (IP-restricted, no withdrawal/transfer rights, spot trading enabled) -- no real Binance call involved at all")
    void getApiKeyRestrictions_alwaysSimulatedSafeDefaults() {
        var result = service.getApiKeyRestrictions("anything", "anything", BrokerMode.PAPER);

        assertThat(result.ipRestrict()).isTrue();
        assertThat(result.enableWithdrawals()).isFalse();
        assertThat(result.enableInternalTransfer()).isFalse();
        assertThat(result.permitsUniversalTransfer()).isFalse();
        assertThat(result.enableSpotAndMarginTrading()).isTrue();
        verifyNoInteractions(realAdapter);
    }

    @Test
    @DisplayName("placeOrder: fetches the REAL current price (via the real adapter, forced to LIVE mode) and simulates an immediate, complete fill with real slippage applied against the requester -- a BUY fills slightly ABOVE the observed price")
    void placeOrder_simulatesImmediateFillWithSlippageAgainstBuyer() {
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(65000));
        OrderRequest req = new OrderRequest("BTCUSDT", "BUY", "MARKET", BigDecimal.valueOf(0.1), "client-1");

        var result = service.placeOrder("fake-key", "fake-secret", BrokerMode.PAPER, req);

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo("FILLED");
        assertThat(result.executedQty()).isEqualByComparingTo(BigDecimal.valueOf(0.1));
        // Review finding ("Paper fills are unrealistically perfect" -- external review,
        // twenty-third pass, P2, full context in SIMULATED_SLIPPAGE_RATE's own field javadoc):
        // 65000 * 1.0005 = 65032.5 -- a BUY now fills slightly ABOVE the observed price, not
        // exactly at it, matching the actual direction real market-order slippage moves.
        assertThat(result.fillPrice()).isEqualByComparingTo(BigDecimal.valueOf(65032.5));
        assertThat(result.brokerOrderId()).startsWith("PAPER-");
        // The real adapter is used ONLY for the public, unauthenticated price lookup -- never
        // for anything resembling an authenticated order-placement call.
        verify(realAdapter, never()).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("placeOrder: a SELL fills slightly BELOW the observed price -- the opposite direction from a BUY, both moving against the requester")
    void placeOrder_sellFillsBelowObservedPrice() {
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(65000));
        OrderRequest req = new OrderRequest("BTCUSDT", "SELL", "MARKET", BigDecimal.valueOf(0.1), "client-1");

        var result = service.placeOrder("fake-key", "fake-secret", BrokerMode.PAPER, req);

        // 65000 * 0.9995 = 64967.5
        assertThat(result.fillPrice()).isEqualByComparingTo(BigDecimal.valueOf(64967.5));
    }

    /**
     * Review finding ("Paper trading balance is fixed" -- external review, twenty-third pass,
     * P2, full context in simulatedUsdtBalance's own field javadoc): the actual test proving
     * the balance now genuinely tracks fills, rather than always returning a flat 100_000.
     */
    @Test
    @DisplayName("getBalance / placeOrder: a BUY fill genuinely reduces the tracked USDT balance by the real notional plus the simulated fee -- no longer a permanently flat 100_000")
    void buyFill_reducesTrackedBalance() {
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(100));
        OrderRequest req = new OrderRequest("BTCUSDT", "BUY", "MARKET", BigDecimal.valueOf(10), "client-1");

        var before = service.getBalance("k", "s", BrokerMode.PAPER).get(0).free();
        service.placeOrder("fake-key", "fake-secret", BrokerMode.PAPER, req);
        var after = service.getBalance("k", "s", BrokerMode.PAPER).get(0).free();

        // fillPrice = 100 * 1.0005 = 100.05; notional = 10 * 100.05 = 1000.5; fee = 1000.5 *
        // 0.001 = 1.0005; total spent = 1001.5005
        assertThat(before.subtract(after)).isEqualByComparingTo(BigDecimal.valueOf(1001.5005));
    }

    @Test
    @DisplayName("placeOrder: when even the real current price can't be fetched, fails honestly rather than fabricating a fill")
    void placeOrder_noPriceAvailable_failsHonestly() {
        when(realAdapter.getCurrentPrice(any(), any())).thenReturn(null);
        OrderRequest req = new OrderRequest("BTCUSDT", "BUY", "MARKET", BigDecimal.valueOf(0.1), "client-1");

        var result = service.placeOrder("fake-key", "fake-secret", BrokerMode.PAPER, req);

        assertThat(result.success()).isFalse();
    }

    @Test
    @DisplayName("getOcoStatus: a LONG position's TP triggers correctly when the real current price rises to or above it")
    void getOcoStatus_takeProfitTriggered() {
        PaperOco oco = new PaperOco();
        oco.setId("oco-1");
        oco.setSymbol("BTCUSDT");
        oco.setQuantity(BigDecimal.valueOf(0.1));
        oco.setTakeProfitPrice(BigDecimal.valueOf(70000));
        oco.setStopLossPrice(BigDecimal.valueOf(60000));
        when(paperOcoRepo.findById("oco-1")).thenReturn(java.util.Optional.of(oco));
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(70500));

        var result = service.getOcoStatus("k", "s", BrokerMode.PAPER, "oco-1");

        assertThat(result.listStatus()).isEqualTo("ALL_DONE");
        assertThat(result.legs()).anyMatch(leg -> leg.type().equals("LIMIT_MAKER") && leg.status().equals("FILLED"));
        verify(paperOcoRepo).save(argThat(saved -> "TP_FILLED".equals(saved.getStatus())));
    }

    @Test
    @DisplayName("getOcoStatus: a LONG position's SL triggers correctly when the real current price falls to or below it")
    void getOcoStatus_stopLossTriggered() {
        PaperOco oco = new PaperOco();
        oco.setId("oco-1");
        oco.setSymbol("BTCUSDT");
        oco.setQuantity(BigDecimal.valueOf(0.1));
        oco.setTakeProfitPrice(BigDecimal.valueOf(70000));
        oco.setStopLossPrice(BigDecimal.valueOf(60000));
        when(paperOcoRepo.findById("oco-1")).thenReturn(java.util.Optional.of(oco));
        // Audit finding (P1-4 -- full context in getOcoStatus_stopLossGap_largeGap_
        // triggersButDoesNotFill below): close enough to the 60,000 trigger to stay within the
        // simulated stop-limit's own resting buffer (0.5%, i.e. within 300 of the trigger) --
        // this test is about a clean, ordinary stop-loss trigger filling normally, not the
        // real-world "triggered but gapped past the resting limit" scenario that test covers.
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(59900));

        var result = service.getOcoStatus("k", "s", BrokerMode.PAPER, "oco-1");

        assertThat(result.listStatus()).isEqualTo("ALL_DONE");
        assertThat(result.legs()).anyMatch(leg -> leg.type().equals("STOP_LOSS") && leg.status().equals("FILLED"));
    }

    @Test
    @DisplayName("getOcoStatus: neither trigger crossed yet -- stays EXECUTING, no premature resolution")
    void getOcoStatus_neitherTriggered_staysActive() {
        PaperOco oco = new PaperOco();
        oco.setId("oco-1");
        oco.setSymbol("BTCUSDT");
        oco.setQuantity(BigDecimal.valueOf(0.1));
        oco.setTakeProfitPrice(BigDecimal.valueOf(70000));
        oco.setStopLossPrice(BigDecimal.valueOf(60000));
        when(paperOcoRepo.findById("oco-1")).thenReturn(java.util.Optional.of(oco));
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(65000));

        var result = service.getOcoStatus("k", "s", BrokerMode.PAPER, "oco-1");

        assertThat(result.listStatus()).isEqualTo("EXECUTING");
        verify(paperOcoRepo, never()).save(any());
    }

    @Test
    @DisplayName("getCurrentPrice: delegates to the real adapter, but ALWAYS with LIVE mode regardless of what was passed in -- genuine current market data, never testnet's own, potentially thin/stale prices")
    void getCurrentPrice_delegatesWithLiveModeForced() {
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(65000));

        var result = service.getCurrentPrice("BTCUSDT", BrokerMode.PAPER);

        assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(65000));
        verify(realAdapter).getCurrentPrice("BTCUSDT", BrokerMode.LIVE);
        verify(realAdapter, never()).getCurrentPrice(eq("BTCUSDT"), eq(BrokerMode.PAPER));
    }

    /**
     * Review finding ("Add slippage/partial-fill simulation" -- external review, P3, full
     * context in PaperBrokerAdapter's own updated PARTIAL_FILL_NOTIONAL_THRESHOLD javadoc): the
     * actual test proving partial-fill simulation works, explicitly forcing the roll via the
     * injected mock Random rather than relying on statistical luck.
     */
    @Test
    @DisplayName("placeOrder: a large order (above the notional threshold), with the partial-fill roll explicitly forced, genuinely fills less than requested and reports PARTIALLY_FILLED")
    void placeOrder_largeOrderPartialFillForced_fillsLessThanRequested() {
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(65000));
        when(random.nextInt(10)).thenReturn(0); // forces the partial-fill branch
        when(random.nextDouble()).thenReturn(0.5); // fillFraction = 0.70 + 0.5*0.29 = 0.845
        OrderRequest req = new OrderRequest("BTCUSDT", "BUY", "MARKET", BigDecimal.valueOf(0.1), "client-1"); // 0.1 * ~65000 far exceeds the $5000 threshold

        var result = service.placeOrder("fake-key", "fake-secret", BrokerMode.PAPER, req);

        assertThat(result.status()).isEqualTo("PARTIALLY_FILLED");
        // 0.1 * 0.845 = 0.0845
        assertThat(result.executedQty()).isEqualByComparingTo(BigDecimal.valueOf(0.0845));
    }

    @Test
    @DisplayName("placeOrder: a small order (below the notional threshold) never partial-fills, even with the roll explicitly forced -- small orders are simply never eligible")
    void placeOrder_smallOrderBelowThreshold_neverPartialFillsEvenIfRollForced() {
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(100));
        when(random.nextInt(10)).thenReturn(0); // would force a partial fill IF this order were eligible
        OrderRequest req = new OrderRequest("BTCUSDT", "BUY", "MARKET", BigDecimal.valueOf(1), "client-1"); // 1 * ~100 = ~$100, well below the $5000 threshold

        var result = service.placeOrder("fake-key", "fake-secret", BrokerMode.PAPER, req);

        assertThat(result.status()).isEqualTo("FILLED");
        assertThat(result.executedQty()).isEqualByComparingTo(BigDecimal.valueOf(1));
    }

    /**
     * Review finding ("PaperBrokerAdapter still reports simulated market orders as effectively
     * filled during later status lookup" -- external review, thirtieth pass, P2, full context
     * in simulatedOrderStatusByBrokerOrderId's own field javadoc): the actual test proving the
     * fix -- a genuinely PARTIALLY_FILLED order from placeOrder must still read back as
     * PARTIALLY_FILLED on a later status check, not silently upgraded to FILLED.
     */
    @Test
    @DisplayName("getOrderStatus / getOrderStatusByClientOrderId: a genuinely PARTIALLY_FILLED order from placeOrder reads back as PARTIALLY_FILLED, with the real executed quantity -- never silently upgraded to FILLED")
    void getOrderStatus_afterPartialFill_correctlyReportsPartiallyFilled() {
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(65000));
        when(random.nextInt(10)).thenReturn(0);
        when(random.nextDouble()).thenReturn(0.5);
        OrderRequest req = new OrderRequest("BTCUSDT", "BUY", "MARKET", BigDecimal.valueOf(0.1), "client-1");
        var placeResult = service.placeOrder("fake-key", "fake-secret", BrokerMode.PAPER, req);
        assertThat(placeResult.status()).isEqualTo("PARTIALLY_FILLED"); // confirms the setup itself is genuinely partial

        var statusByBrokerId = service.getOrderStatus("fake-key", "fake-secret", BrokerMode.PAPER, "BTCUSDT", placeResult.brokerOrderId());
        var statusByClientId = service.getOrderStatusByClientOrderId("fake-key", "fake-secret", BrokerMode.PAPER, "BTCUSDT", "client-1");

        assertThat(statusByBrokerId.status()).isEqualTo("PARTIALLY_FILLED");
        assertThat(statusByBrokerId.executedQty()).isEqualByComparingTo(placeResult.executedQty());
        assertThat(statusByClientId.status()).isEqualTo("PARTIALLY_FILLED");
        assertThat(statusByClientId.executedQty()).isEqualByComparingTo(placeResult.executedQty());
    }

    @Test
    @DisplayName("getOrderStatus: a genuinely, fully FILLED order from placeOrder still correctly reads back as FILLED -- confirms this fix didn't accidentally break the normal, non-partial case")
    void getOrderStatus_afterFullFill_correctlyReportsFilled() {
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(100));
        OrderRequest req = new OrderRequest("BTCUSDT", "BUY", "MARKET", BigDecimal.valueOf(1), "client-2");
        var placeResult = service.placeOrder("fake-key", "fake-secret", BrokerMode.PAPER, req);

        var status = service.getOrderStatus("fake-key", "fake-secret", BrokerMode.PAPER, "BTCUSDT", placeResult.brokerOrderId());

        assertThat(status.status()).isEqualTo("FILLED");
        assertThat(status.executedQty()).isEqualByComparingTo(BigDecimal.valueOf(1));
    }

    @Test
    @DisplayName("P2-4 fix (\"unknown order IDs default to FILLED with null qty\"): a brokerOrderId this instance never actually recorded (e.g. an OCO leg, or an order from a different adapter instance) now reports UNKNOWN, never a fabricated FILLED with a null executedQty")
    void getOrderStatus_unrecordedBrokerOrderId_reportsUnknownNotFabricatedFilled() {
        var status = service.getOrderStatus("fake-key", "fake-secret", BrokerMode.PAPER, "BTCUSDT", "never-seen-this-id");

        assertThat(status.status()).isEqualTo("UNKNOWN");
        assertThat(status.executedQty()).isNull();
    }

    @Test
    @DisplayName("P2-4 fix, same context as getOrderStatus's own updated test above: getOrderStatusByClientOrderId reports UNKNOWN too for a clientOrderId this instance never actually recorded")
    void getOrderStatusByClientOrderId_unrecordedClientOrderId_reportsUnknownNotFabricatedFilled() {
        var status = service.getOrderStatusByClientOrderId("fake-key", "fake-secret", BrokerMode.PAPER, "BTCUSDT", "never-seen-this-client-id");

        assertThat(status.status()).isEqualTo("UNKNOWN");
        assertThat(status.executedQty()).isNull();
    }

    // ── P2-4: balance floor (no negative balance), step-size rounding, durable balance
    // persistence, and SL/TP gap-aware fill price ──────────────────────────────────────────

    @Test
    @DisplayName("P2-4 fix (\"can go negative\"): placeOrder rejects a BUY whose simulated cost exceeds the tracked balance, instead of letting the balance go negative")
    void placeOrder_rejectsBuy_whenInsufficientSimulatedBalance() {
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(100000));
        // Default starting balance is 100,000 USDT (see simulatedUsdtBalance's own field javadoc)
        // -- an order for 2 BTC at 100,000 each is a 200,000 USDT notional, well beyond it.
        OrderRequest req = new OrderRequest("BTCUSDT", "BUY", "MARKET", BigDecimal.valueOf(2), "client-neg-1");

        var result = service.placeOrder("fake-key", "fake-secret", BrokerMode.PAPER, req);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.errorMessage()).containsIgnoringCase("insufficient");
    }

    @Test
    @DisplayName("P2-4 fix, same context as the insufficient-balance rejection test above: a genuinely affordable BUY is never rejected, and the balance never actually goes negative across a sequence of real, affordable fills")
    void placeOrder_neverLetsBalanceGoNegative_acrossAffordableFills() {
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(100));
        for (int i = 0; i < 50; i++) {
            OrderRequest req = new OrderRequest("BTCUSDT", "BUY", "MARKET", BigDecimal.valueOf(1), "client-seq-" + i);
            service.placeOrder("fake-key", "fake-secret", BrokerMode.PAPER, req);
        }

        var balance = service.getBalance("fake-key", "fake-secret", BrokerMode.PAPER).get(0).free();
        assertThat(balance.signum()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("P2-4 fix (\"SL fills at exact SL (no gap)\"): a stop-loss that the real market price has already gapped past reports the ACTUAL observed price, not the stored trigger price")
    void getOcoStatus_stopLossGap_reportsActualObservedPriceNotTriggerPrice() {
        PaperOco oco = new PaperOco();
        oco.setId("oco-gap-1");
        oco.setSymbol("BTCUSDT");
        oco.setQuantity(BigDecimal.valueOf(1));
        oco.setTakeProfitPrice(BigDecimal.valueOf(70000));
        oco.setStopLossPrice(BigDecimal.valueOf(60000));
        when(paperOcoRepo.findById("oco-gap-1")).thenReturn(java.util.Optional.of(oco));
        // The real market has gapped slightly past the 60,000 stop trigger -- still within the
        // simulated stop-limit's own 0.5% resting buffer (P1-4, full context in
        // STOP_LIMIT_NON_FILL_GAP_RATE's own field javadoc), so this still fills, just not
        // exactly at the stored trigger price -- a real Binance stop-loss in a fast-moving
        // market can and does fill at a materially worse price than its own trigger. A LARGER
        // gap that blows through that buffer entirely is covered by the dedicated
        // getOcoStatus_stopLossGap_largeGap_triggersButDoesNotFill test below instead.
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(59800));

        var status = service.getOcoStatus("fake-key", "fake-secret", BrokerMode.PAPER, "oco-gap-1");

        var slLeg = status.legs().stream().filter(l -> l.orderId().endsWith("-SL")).findFirst().orElseThrow();
        assertThat(slLeg.status()).isEqualTo("FILLED");
        assertThat(slLeg.price()).isEqualByComparingTo("59800"); // the real observed price, NOT the 60000 trigger price
    }

    // ── P1-4: "Improve PaperBrokerAdapter realism... stop-limit-non-fill simulation" ──

    @Test
    @DisplayName("P1-4: a stop-loss trigger the market has gapped WELL past (beyond the simulated stop-limit's own 0.5% resting buffer) is left triggered-but-UNFILLED, not auto-resolved -- exactly the real scenario PositionMonitorService's P0-3 watchdog exists to catch")
    void getOcoStatus_stopLossGap_largeGap_triggersButDoesNotFill() {
        PaperOco oco = new PaperOco();
        oco.setId("oco-biggap-1");
        oco.setSymbol("BTCUSDT");
        oco.setQuantity(BigDecimal.valueOf(1));
        oco.setTakeProfitPrice(BigDecimal.valueOf(70000));
        oco.setStopLossPrice(BigDecimal.valueOf(60000));
        when(paperOcoRepo.findById("oco-biggap-1")).thenReturn(java.util.Optional.of(oco));
        // 58,500 is 2.5% below the 60,000 trigger -- well past the simulated 0.5% resting buffer.
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(58500));

        var status = service.getOcoStatus("fake-key", "fake-secret", BrokerMode.PAPER, "oco-biggap-1");

        // Deliberately NOT ALL_DONE and the SL leg deliberately NOT FILLED -- that exact
        // combination is what PositionMonitorService.handleStopTriggeredButUnfilled keys off of.
        assertThat(status.listStatus()).isEqualTo("EXECUTING");
        var slLeg = status.legs().stream().filter(l -> l.orderId().endsWith("-SL")).findFirst().orElseThrow();
        assertThat(slLeg.status()).isEqualTo("NEW");
        verify(paperOcoRepo).save(argThat(saved -> "SL_TRIGGERED_UNFILLED".equals(saved.getStatus())));
    }

    @Test
    @DisplayName("P1-4: a triggered-but-unfilled stop-limit OCO can still be cancelled -- the real emergencyFlatten flow cancels the stuck exit OCO before placing a fresh market sell")
    void cancelOco_slTriggeredUnfilled_cancelsSuccessfully() {
        PaperOco oco = new PaperOco();
        oco.setId("oco-biggap-1");
        oco.setStatus("SL_TRIGGERED_UNFILLED");
        when(paperOcoRepo.findById("oco-biggap-1")).thenReturn(java.util.Optional.of(oco));

        var result = service.cancelOco("k", "s", BrokerMode.PAPER, "BTCUSDT", "oco-biggap-1");

        assertThat(result.success()).isTrue();
        verify(paperOcoRepo).save(argThat(saved -> "CANCELLED".equals(saved.getStatus())));
    }

    @Test
    @DisplayName("P1-4: per-asset oversell rejection -- a SELL for an asset this PAPER credential has real tracked holdings for, but not enough of, is rejected rather than simulated")
    void placeOrder_sellExceedsTrackedAssetHoldings_rejected() {
        var rules = new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT", BigDecimal.valueOf(0.01), BigDecimal.valueOf(0.0001),
            BigDecimal.valueOf(0.0001), BigDecimal.ZERO, 2, 4, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
        when(realAdapter.getSymbolRules("BTCUSDT", BrokerMode.LIVE)).thenReturn(rules);
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(65000));
        // Buys exactly 0.05 BTC first.
        service.placeOrder("k", "s", BrokerMode.PAPER, new OrderRequest("BTCUSDT", "BUY", "MARKET", BigDecimal.valueOf(0.05), "buy-1"));

        // Then tries to sell MORE than that -- a real exchange would reject this outright.
        var result = service.placeOrder("k", "s", BrokerMode.PAPER,
            new OrderRequest("BTCUSDT", "SELL", "MARKET", BigDecimal.valueOf(0.2), "sell-1"));

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.errorMessage()).containsIgnoringCase("insufficient");
    }

    @Test
    @DisplayName("P1-4: per-asset holdings allow selling up to exactly what was bought, and reduce holdings by what actually sold")
    void placeOrder_sellWithinTrackedAssetHoldings_succeeds() {
        var rules = new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT", BigDecimal.valueOf(0.01), BigDecimal.valueOf(0.0001),
            BigDecimal.valueOf(0.0001), BigDecimal.ZERO, 2, 4, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
        when(realAdapter.getSymbolRules("BTCUSDT", BrokerMode.LIVE)).thenReturn(rules);
        when(realAdapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(65000));
        service.placeOrder("k", "s", BrokerMode.PAPER, new OrderRequest("BTCUSDT", "BUY", "MARKET", BigDecimal.valueOf(0.1), "buy-1"));

        var result = service.placeOrder("k", "s", BrokerMode.PAPER,
            new OrderRequest("BTCUSDT", "SELL", "MARKET", BigDecimal.valueOf(0.1), "sell-1"));

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo("FILLED");

        // Selling again (nothing left) is rejected -- holdings were genuinely decremented.
        var secondSell = service.placeOrder("k", "s", BrokerMode.PAPER,
            new OrderRequest("BTCUSDT", "SELL", "MARKET", BigDecimal.valueOf(0.01), "sell-2"));
        assertThat(secondSell.success()).isFalse();
    }
}
