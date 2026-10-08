package com.tradevision.service;

import com.tradevision.dto.PlaceTestOrderRequest;
import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.BrokerType;
import com.tradevision.repository.OrderRepository;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.OrderResult;
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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies that the manual order endpoint is TESTNET-only, unconditionally -- it bypasses the
 * risk engine, OMS, fill ledger, and position lifecycle, so it must never be reachable for LIVE
 * regardless of whether the credential's permissions happen to be clean.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderExecutionServiceTest {

    @Mock BrokerCredentialService credentialService;
    @Mock OrderRepository orderRepo;
    @Mock BrokerAdapter adapter;
    // Needed because placeTestOrder creates a real Order (OMS) record via
    // orderService.create/recordBrokerResult instead of an ExecutedOrder-only one. Without this
    // mock, @InjectMocks would leave the field null, and the unconditional (no try/catch) call
    // to it would NPE for real, breaking every existing test that reaches this method's own
    // successful body.
    @Mock OrderService orderService;

    @InjectMocks OrderExecutionService service;

    private BrokerCredential testnetCredential;
    private BrokerCredential liveCredential;
    private PlaceTestOrderRequest req;

    @BeforeEach
    void setup() {
        // A realistic "order created" default -- an unstubbed orderService.create() would
        // otherwise return Mockito's own null default, and this class's own code immediately
        // calls order.setBroker(...) on the result, which would NPE. A real, mutable Order
        // instance so those field mutations and the later recordBrokerResult() call both have
        // something real to work with.
        when(orderService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> new com.tradevision.model.Order());
        // recordBrokerResult's own real logic (status transitions, legality checks) isn't
        // relevant to what this test file's own existing tests actually verify -- a realistic
        // pass-through default (return the same order it was given, status left as whatever the
        // test itself set up via the real create() stub above) keeps this file's own existing
        // assertions meaningful without re-implementing OrderService's own state machine here a
        // second time.
        when(orderService.recordBrokerResult(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        // Same reasoning as recordBrokerResult's own stub above, now that placeTestOrder also
        // persists broker/mode/triggerSource/TP-SL through recordEntryMetadata (see
        // OrderExecutionService's own updated comment on why that moved out of raw setters).
        when(orderService.recordEntryMetadata(any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> inv.getArgument(0));
        testnetCredential = new BrokerCredential();
        testnetCredential.setId("cred1");
        testnetCredential.setBroker(BrokerType.BINANCE);
        testnetCredential.setMode(BrokerMode.TESTNET);

        liveCredential = new BrokerCredential();
        liveCredential.setId("cred1");
        liveCredential.setBroker(BrokerType.BINANCE);
        liveCredential.setMode(BrokerMode.LIVE);

        req = new PlaceTestOrderRequest();
        req.setCredentialId("cred1");
        req.setSymbol("BTCUSDT");
        req.setSide("BUY");
        req.setQuantity(BigDecimal.valueOf(0.001));

        when(credentialService.adapterForCredential(any())).thenReturn(adapter);
        when(credentialService.decrypt(any(), anyBoolean())).thenReturn("secret");
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            new OrderResult(true, "b1", "manual-x", "FILLED", BigDecimal.valueOf(0.001), BigDecimal.valueOf(100), "{}", null, List.of()));
    }

    @Test
    @DisplayName("placeTestOrder: TESTNET proceeds normally, exactly as before -- this fix only changes LIVE behavior")
    void testnetOrder_proceedsNormally() {
        when(credentialService.ownedCredential("user1", "cred1")).thenReturn(testnetCredential);

        service.placeTestOrder("user1", req);

        verify(adapter).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("placeTestOrder: LIVE is unconditionally refused, regardless of the credential's own broker permissions")
    void liveOrder_unconditionallyRefused() {
        when(credentialService.ownedCredential("user1", "cred1")).thenReturn(liveCredential);

        assertThatThrownBy(() -> service.placeTestOrder("user1", req)).isInstanceOf(IllegalStateException.class);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        verify(credentialService).audit(eq("user1"), eq("cred1"), any(), eq("MANUAL_LIVE_ORDER_REFUSED_TESTNET_ONLY"), any());
    }

    @Test
    @DisplayName("placeTestOrder: LIVE is refused before ever calling getAccountPermissions or any other broker call -- confirming this is a hard, unconditional gate, not a check that could still fall through under some broker response")
    void liveOrder_refusedBeforeAnyBrokerCall() {
        when(credentialService.ownedCredential("user1", "cred1")).thenReturn(liveCredential);

        assertThatThrownBy(() -> service.placeTestOrder("user1", req)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(adapter);
    }

    @Test
    @DisplayName("history: uses a bounded Pageable, not an unbounded query")
    void history_usesPageableNotUnboundedQuery() {
        when(orderRepo.findByUserId(any(), any(org.springframework.data.domain.Pageable.class))).thenReturn(java.util.List.of());

        service.history("user1", 0, 50);

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pageableCaptor =
            org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(orderRepo).findByUserId(eq("user1"), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("history: page size above the 200 cap is clamped down")
    void history_pageSizeAboveCap_clampedTo200() {
        when(orderRepo.findByUserId(any(), any(org.springframework.data.domain.Pageable.class))).thenReturn(java.util.List.of());

        service.history("user1", 0, 5000);

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pageableCaptor =
            org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(orderRepo).findByUserId(eq("user1"), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(200);
    }

    /**
     * Proves markRiskAccepted()/markSubmitting() are genuinely called, in the correct order,
     * before recordBrokerResult() -- not just that the method runs without throwing
     * (recordBrokerResult is stubbed as a pass-through in this file's own @BeforeEach, so a
     * missing call here would not otherwise be caught).
     */
    @Test
    @DisplayName("placeTestOrder: transitions the OMS order through markRiskAccepted then markSubmitting before recordBrokerResult -- the exact sequence AutoTradeService's own entry-order path uses, without which an ambiguous broker result cannot legally reach markUnknown() (CREATED -> UNKNOWN is not a legal transition)")
    void placeTestOrder_transitionsThroughRiskAcceptedAndSubmitting() {
        when(credentialService.ownedCredential("user1", "cred1")).thenReturn(testnetCredential);
        when(credentialService.adapterForCredential(testnetCredential)).thenReturn(adapter);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            new com.tradevision.service.broker.dto.OrderResult(true, "broker1", "client1", "NEW",
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "{}", null));

        service.placeTestOrder("user1", req);

        var inOrder = org.mockito.Mockito.inOrder(orderService);
        inOrder.verify(orderService).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        inOrder.verify(orderService).markRiskAccepted(any());
        inOrder.verify(orderService).markSubmitting(any());
        inOrder.verify(orderService).recordBrokerResult(any(), any());
    }

    /**
     * A manual BUY with both TP and SL carries them all the way through to the OMS order via
     * recordEntryMetadata, instead of the fill being discovered later with nothing to protect it.
     */
    @Test
    @DisplayName("placeTestOrder: BUY with takeProfitPrice/stopLossTriggerPrice persists both via recordEntryMetadata, as MANUAL trigger source")
    void placeTestOrder_buyWithTpSl_persistsViaRecordEntryMetadata() {
        when(credentialService.ownedCredential("user1", "cred1")).thenReturn(testnetCredential);
        req.setTakeProfitPrice(BigDecimal.valueOf(110));
        req.setStopLossTriggerPrice(BigDecimal.valueOf(95));

        service.placeTestOrder("user1", req);

        verify(orderService).recordEntryMetadata(any(), eq(BrokerType.BINANCE), eq(BrokerMode.TESTNET), eq("MANUAL"),
            any(), eq(BigDecimal.valueOf(95)), eq(BigDecimal.valueOf(110)));
    }

    @Test
    @DisplayName("placeTestOrder: TP/SL both left null is unchanged, existing behavior -- recordEntryMetadata is still called, but with null TP/SL")
    void placeTestOrder_noTpSl_recordEntryMetadataCalledWithNulls() {
        when(credentialService.ownedCredential("user1", "cred1")).thenReturn(testnetCredential);

        service.placeTestOrder("user1", req);

        verify(orderService).recordEntryMetadata(any(), any(), any(), eq("MANUAL"), any(), isNull(), isNull());
    }

    @Test
    @DisplayName("placeTestOrder: only one of takeProfitPrice/stopLossTriggerPrice provided is rejected before any broker call")
    void placeTestOrder_onlyOneOfTpSl_rejected() {
        when(credentialService.ownedCredential("user1", "cred1")).thenReturn(testnetCredential);
        req.setTakeProfitPrice(BigDecimal.valueOf(110));

        assertThatThrownBy(() -> service.placeTestOrder("user1", req)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(adapter);
    }

    @Test
    @DisplayName("placeTestOrder: stopLossTriggerPrice at or above takeProfitPrice is rejected before any broker call")
    void placeTestOrder_stopLossAboveTakeProfit_rejected() {
        when(credentialService.ownedCredential("user1", "cred1")).thenReturn(testnetCredential);
        req.setTakeProfitPrice(BigDecimal.valueOf(100));
        req.setStopLossTriggerPrice(BigDecimal.valueOf(100));

        assertThatThrownBy(() -> service.placeTestOrder("user1", req)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(adapter);
    }

    @Test
    @DisplayName("placeTestOrder: TP/SL on a SELL is rejected -- they only protect a long")
    void placeTestOrder_tpSlOnSell_rejected() {
        when(credentialService.ownedCredential("user1", "cred1")).thenReturn(testnetCredential);
        req.setSide("SELL");
        req.setTakeProfitPrice(BigDecimal.valueOf(110));
        req.setStopLossTriggerPrice(BigDecimal.valueOf(95));

        assertThatThrownBy(() -> service.placeTestOrder("user1", req)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(adapter);
    }
}
