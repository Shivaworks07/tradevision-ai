package com.tradevision.service;

import com.tradevision.model.Order;
import com.tradevision.model.OrderStatus;
import com.tradevision.repository.OrderRepository;
import com.tradevision.service.broker.dto.OcoOrderResult;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Review finding ("#4 — OMS", the recommended starting point of the autonomous-engine work):
 * the OMS's own state-transition correctness is the single most foundational thing to verify
 * before anything else — every later piece (fill ledger, position ledger, latency tracking)
 * assumes this state machine is actually trustworthy.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock OrderRepository orderRepo;
    // Review finding ("Real-world order recovery needs to cover process crashes, not only HTTP
    // errors" -- P1, full context in OrderService.recoverStuckSubmittingOrders's own javadoc):
    // needed now that OrderService raises a critical incident for orders stuck in SUBMITTING.
    @Mock IncidentService incidentService;
    // Review finding ("Order state transitions not atomic across replicas" -- P1, full context
    // in OrderService.transition's own javadoc): needed now that every status transition goes
    // through a real conditional database update.
    @Mock org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    // Review finding ("There is still no authoritative event ledger" -- P1, full context in
    // TradeEvent's own javadoc): needed now that every transition and create() records a real
    // event. Unstubbed calls to save() safely return null by default (an object-returning
    // Mockito default), which is fine here since neither create() nor transition() ever reads
    // that return value -- unlike the MongoTemplate/UpdateResult case, no default stub is needed
    // for existing tests to keep passing.
    @Mock com.tradevision.repository.TradeEventRepository tradeEventRepo;
    @Mock com.tradevision.config.ShutdownState shutdownState;
    // Review finding (P1-4 -- "Orders stuck in SUBMITTING are never resolved against the
    // exchange"): needed now that recoverStuckSubmittingOrders actually queries the broker.
    @Mock com.tradevision.repository.BrokerCredentialRepository credentialRepo;
    @Mock BrokerCredentialService credentialService;
    @Mock com.tradevision.service.broker.BrokerAdapter adapter;
    @InjectMocks OrderService service;

    @BeforeEach
    void setup() {
        when(orderRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);
        // Review finding's own design (full context in OrderService.transition's own javadoc):
        // a realistic "the conditional update succeeded" default -- same reasoning as this
        // codebase's other @BeforeEach defaults elsewhere (RealizedPnlService, PositionLedgerService,
        // etc.) -- an unstubbed updateFirst() would otherwise return null (Mockito's default for
        // an object return type), NPEing every existing test that reaches ANY state transition,
        // which is effectively every test in this file. A test that wants to exercise the
        // lost-race path overrides this explicitly.
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(com.tradevision.model.Order.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
    }

    private Order newOrder() {
        Order o = service.create("user1", "cred1", "pos1", "sig1", "BTCUSDT", "BUY", "MARKET",
            BigDecimal.valueOf(1.0), BigDecimal.valueOf(100), "test-client-order-id-1");
        return o;
    }

    // ── Happy path ──────────────────────────────────────────────

    @Test
    @DisplayName("create: starts in CREATED with the requested fields recorded and remainingQuantity equal to requestedQuantity")
    void create_startsInCreatedState() {
        Order o = newOrder();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(o.getRequestedQuantity()).isEqualByComparingTo("1.0");
        assertThat(o.getRemainingQuantity()).isEqualByComparingTo("1.0");
        assertThat(o.getFilledQuantity()).isEqualByComparingTo("0");
        assertThat(o.getClientOrderId()).isNotBlank();
    }

    @Test
    @DisplayName("full happy path: CREATED -> RISK_ACCEPTED -> SUBMITTING -> FILLED, with every timestamp populated along the way")
    void fullHappyPath_reachesFilled() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.RISK_ACCEPTED);
        assertThat(o.getRiskAcceptedAt()).isNotNull();

        service.markSubmitting(o);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.SUBMITTING);
        assertThat(o.getSubmitStartedAt()).isNotNull();

        OrderResult filled = new OrderResult(true, "b1", o.getClientOrderId(), "FILLED",
            BigDecimal.valueOf(1.0), BigDecimal.valueOf(100.5), "{}", null, List.of());
        service.recordBrokerResult(o, filled);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(o.getFilledQuantity()).isEqualByComparingTo("1.0");
        assertThat(o.getAverageFillPrice()).isEqualByComparingTo("100.5");
        assertThat(o.getBrokerOrderId()).isEqualTo("b1");
        assertThat(o.getBrokerAckAt()).isNotNull();
        assertThat(o.getFirstFillAt()).isNotNull();
        assertThat(o.getFilledAt()).isNotNull();
    }

    @Test
    @DisplayName("recordBrokerResult: a partial fill transitions to PARTIALLY_FILLED, not FILLED, with the correct remaining quantity")
    void partialFill_transitionsToPartiallyFilled() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);

        OrderResult partial = new OrderResult(true, "b1", o.getClientOrderId(), "PARTIALLY_FILLED",
            BigDecimal.valueOf(0.4), BigDecimal.valueOf(100), "{}", null, List.of());
        service.recordBrokerResult(o, partial);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(o.getFilledQuantity()).isEqualByComparingTo("0.4");
        assertThat(o.getRemainingQuantity()).isEqualByComparingTo("0.6");
        assertThat(o.getFilledAt()).isNull(); // not fully filled — this must stay unset
    }

    @Test
    @DisplayName("recordBrokerResult: success=true but no confirmed executed quantity is ACKNOWLEDGED, never fabricated as FILLED — the 'phantom position' guard")
    void successWithNoConfirmedQuantity_isAcknowledgedNotFilled() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);

        OrderResult ambiguous = new OrderResult(true, "b1", o.getClientOrderId(), "NEW",
            null, null, "{}", null, List.of());
        service.recordBrokerResult(o, ambiguous);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.ACKNOWLEDGED);
        assertThat(o.getFilledQuantity()).isEqualByComparingTo("0");
    }

    // ── P1-14: EXPIRED/EXPIRED_IN_MATCH classification ──────────

    /**
     * P1-14 fix ("MARKET order EXPIRED/EXPIRED_IN_MATCH misclassified"): confirmed real --
     * classification used to look ONLY at executedQty, so a broker-confirmed terminal status
     * of EXPIRED with zero fill landed on ACKNOWLEDGED (non-terminal -- reconcileEntryOrders
     * would poll it forever, since Binance will never send a further update for an order it
     * already considers finished) and EXPIRED_IN_MATCH with a partial fill landed on
     * PARTIALLY_FILLED (equally non-terminal, implying more fills may still arrive, which they
     * never will). Table test over exactly the audit's own required matrix: NEW/PARTIALLY_FILLED
     * /FILLED behave as before (untouched by this fix); EXPIRED/EXPIRED_IN_MATCH now become the
     * OMS's own terminal OrderStatus.EXPIRED regardless of qty, with whatever quantity genuinely
     * filled before expiry still recorded rather than lost.
     */
    @Test
    @DisplayName("recordBrokerResult: broker status EXPIRED with zero fill -> terminal EXPIRED, not ACKNOWLEDGED")
    void expiredWithZeroFill_movesToTerminalExpired() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);

        OrderResult expired = new OrderResult(true, "b1", o.getClientOrderId(), "EXPIRED",
            BigDecimal.ZERO, null, "{}", null, List.of());
        service.recordBrokerResult(o, expired);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(o.getFilledQuantity()).isEqualByComparingTo("0");
        assertThat(o.getRemainingQuantity()).isEqualByComparingTo(o.getRequestedQuantity());
        assertThat(o.getFilledAt()).isNull();
        assertThat(o.getFirstFillAt()).isNull();
    }

    @Test
    @DisplayName("recordBrokerResult: broker status EXPIRED with null executedQty -> terminal EXPIRED, treated as zero fill")
    void expiredWithNullExecutedQty_movesToTerminalExpired() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);

        OrderResult expired = new OrderResult(true, "b1", o.getClientOrderId(), "EXPIRED",
            null, null, "{}", null, List.of());
        service.recordBrokerResult(o, expired);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(o.getFilledQuantity()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("recordBrokerResult: broker status EXPIRED_IN_MATCH with a genuine partial fill -> terminal EXPIRED, not stuck at PARTIALLY_FILLED, fill quantity still recorded")
    void expiredInMatchWithPartialFill_movesToTerminalExpiredWithFillRecorded() {
        Order o = newOrder(); // requestedQuantity = 1.0 (see newOrder())
        service.markRiskAccepted(o);
        service.markSubmitting(o);

        OrderResult expiredInMatch = new OrderResult(true, "b1", o.getClientOrderId(), "EXPIRED_IN_MATCH",
            BigDecimal.valueOf(0.3), BigDecimal.valueOf(100), "{}", null, List.of());
        service.recordBrokerResult(o, expiredInMatch);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(o.getFilledQuantity()).isEqualByComparingTo("0.3");
        assertThat(o.getRemainingQuantity()).isEqualByComparingTo("0.7");
        assertThat(o.getAverageFillPrice()).isEqualByComparingTo("100");
        assertThat(o.getFirstFillAt()).isNotNull();
        // Genuinely terminal -- an expired order never reaches FILLED, however much filled
        // before expiry.
        assertThat(o.getFilledAt()).isNull();
    }

    @Test
    @DisplayName("recordBrokerResult: EXPIRED is a legal direct transition from SUBMITTING -- the broker's very first, synchronous placement response can already report it, with no intermediate ACKNOWLEDGED state ever having existed")
    void expired_legalDirectlyFromSubmitting() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.SUBMITTING);

        OrderResult expired = new OrderResult(true, "b1", o.getClientOrderId(), "EXPIRED",
            BigDecimal.ZERO, null, "{}", null, List.of());

        // Must not throw IllegalStateException ("SUBMITTING -> EXPIRED is not a legal transition").
        service.recordBrokerResult(o, expired);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    @DisplayName("recordBrokerResult: broker status FILLED is unaffected by the EXPIRED fix -- still reaches FILLED with filledAt set")
    void filledStatus_stillReachesFilled_unaffectedByExpiredFix() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);

        OrderResult filled = new OrderResult(true, "b1", o.getClientOrderId(), "FILLED",
            BigDecimal.valueOf(1.0), BigDecimal.valueOf(100), "{}", null, List.of());
        service.recordBrokerResult(o, filled);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(o.getFilledAt()).isNotNull();
    }

    @Test
    @DisplayName("recordBrokerResult: broker status NEW with zero fill is unaffected by the EXPIRED fix -- still ACKNOWLEDGED, not EXPIRED")
    void newStatus_stillAcknowledged_unaffectedByExpiredFix() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);

        OrderResult ack = new OrderResult(true, "b1", o.getClientOrderId(), "NEW",
            BigDecimal.ZERO, null, "{}", null, List.of());
        service.recordBrokerResult(o, ack);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.ACKNOWLEDGED);
    }

    @Test
    @DisplayName("recordBrokerResult: broker status PARTIALLY_FILLED is unaffected by the EXPIRED fix -- still PARTIALLY_FILLED, not EXPIRED (more fills may genuinely still arrive)")
    void partiallyFilledStatus_stillPartiallyFilled_unaffectedByExpiredFix() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);

        OrderResult partial = new OrderResult(true, "b1", o.getClientOrderId(), "PARTIALLY_FILLED",
            BigDecimal.valueOf(0.4), BigDecimal.valueOf(100), "{}", null, List.of());
        service.recordBrokerResult(o, partial);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    }

    // ── UNKNOWN handling — the review's own explicit priority ────

    @Test
    @DisplayName("recordBrokerResult: status=UNKNOWN moves to UNKNOWN regardless of the success flag — never silently folded into REJECTED or FILLED")
    void statusUnknown_movesToUnknownRegardlessOfSuccessFlag() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);

        OrderResult unknown = new OrderResult(false, null, o.getClientOrderId(), "UNKNOWN",
            null, null, null, "connection timeout", List.of());
        service.recordBrokerResult(o, unknown);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.UNKNOWN);
        assertThat(o.getFailureReason()).isEqualTo("connection timeout");
    }

    @Test
    @DisplayName("recoverUnknown: verification itself failed — moves to RECONCILIATION_REQUIRED, not another automated retry")
    void recoverUnknown_verificationFailed_reconciliationRequired() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);
        service.markUnknown(o, "connection timeout");

        service.recoverUnknown(o, null, false);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    @DisplayName("recoverUnknown: broker positively confirms the order never existed — safe to treat as REJECTED")
    void recoverUnknown_brokerConfirmsNeverExisted_rejected() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);
        service.markUnknown(o, "connection timeout");

        service.recoverUnknown(o, null, true);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    @DisplayName("recoverUnknown: broker confirms the order actually filled — routes through the normal interpretation, ending in FILLED")
    void recoverUnknown_brokerConfirmsFilled_routesToFilled() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);
        service.markUnknown(o, "connection timeout");

        OrderResult recovered = new OrderResult(true, "b1", o.getClientOrderId(), "FILLED",
            BigDecimal.valueOf(1.0), BigDecimal.valueOf(100), "{}", null, List.of());
        service.recoverUnknown(o, recovered, true);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(o.getFilledQuantity()).isEqualByComparingTo("1.0");
    }

    // ── Rejection ─────────────────────────────────────────────────

    @Test
    @DisplayName("recordBrokerResult: a confirmed broker rejection (success=false, status != UNKNOWN) moves to REJECTED")
    void confirmedRejection_movesToRejected() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);

        OrderResult rejected = OrderResult.failure("insufficient balance", "{}");
        service.recordBrokerResult(o, rejected);

        assertThat(o.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(o.getFailureReason()).isEqualTo("insufficient balance");
    }

    @Test
    @DisplayName("markRiskRejected: a risk-rejected order records the reason and never proceeds to submission")
    void markRiskRejected_recordsReason() {
        Order o = newOrder();
        service.markRiskRejected(o, "daily loss limit reached");

        assertThat(o.getStatus()).isEqualTo(OrderStatus.RISK_REJECTED);
        assertThat(o.getFailureReason()).isEqualTo("daily loss limit reached");
    }

    // ── Cancellation ──────────────────────────────────────────────

    @Test
    @DisplayName("markCancelRequested then markCancelled: both timestamps set correctly")
    void cancelFlow_setsTimestamps() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);
        OrderResult ack = new OrderResult(true, "b1", o.getClientOrderId(), "NEW", null, null, "{}", null, List.of());
        service.recordBrokerResult(o, ack);

        service.markCancelRequested(o);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCEL_PENDING);
        assertThat(o.getCancelRequestedAt()).isNotNull();

        service.markCancelled(o);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(o.getCancelledAt()).isNotNull();
    }

    // ── Illegal transitions — the state machine's own invariant enforcement ────

    @Test
    @DisplayName("illegal transition: submitting a CREATED order (skipping RISK_ACCEPTED) throws rather than silently proceeding")
    void illegalTransition_skippingRiskAccepted_throws() {
        Order o = newOrder();
        assertThatThrownBy(() -> service.markSubmitting(o)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("illegal transition: a FILLED order (terminal) cannot transition anywhere else")
    void illegalTransition_fromTerminalState_throws() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);
        OrderResult filled = new OrderResult(true, "b1", o.getClientOrderId(), "FILLED",
            BigDecimal.valueOf(1.0), BigDecimal.valueOf(100), "{}", null, List.of());
        service.recordBrokerResult(o, filled);

        assertThatThrownBy(() -> service.markCancelRequested(o)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("illegal transition: recording a broker result on a CREATED order (never submitted) throws")
    void illegalTransition_recordResultBeforeSubmitting_throws() {
        Order o = newOrder();
        OrderResult result = new OrderResult(true, "b1", o.getClientOrderId(), "FILLED",
            BigDecimal.valueOf(1.0), BigDecimal.valueOf(100), "{}", null, List.of());
        assertThatThrownBy(() -> service.recordBrokerResult(o, result)).isInstanceOf(IllegalStateException.class);
    }

    // ── generateClientOrderId (review items "OMS clientOrderId doesn't match the actual broker
    // clientOrderId" and "Autonomous path's sig-<UUID> client order ID may exceed Binance's
    // length limit" -- full context in the method's own javadoc) ──────────────

    @Test
    @DisplayName("generateClientOrderId: stays within Binance's own documented 36-character limit even for a full-length UUID basis, unlike the old \"sig-\" + UUID format it replaces (4 + 36 = 40, over the limit)")
    void generateClientOrderId_staysWithinBinanceLengthLimit() {
        String uuidBasis = java.util.UUID.randomUUID().toString();
        assertThat(uuidBasis.length()).isEqualTo(36); // confirms the premise: a real signal id is genuinely this long

        String result = OrderService.generateClientOrderId("tv-s", uuidBasis);

        assertThat(result.length()).isLessThanOrEqualTo(36);
    }

    @Test
    @DisplayName("generateClientOrderId: is deterministic -- the same basis always produces the same id, which is what makes it usable as a genuine idempotency key rather than a fresh random string on every call")
    void generateClientOrderId_isDeterministic() {
        String basis = "signal-abc-123";

        String first = OrderService.generateClientOrderId("tv-s", basis);
        String second = OrderService.generateClientOrderId("tv-s", basis);

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("generateClientOrderId: different bases produce different ids -- no fixed/degenerate output regardless of input")
    void generateClientOrderId_differentBasesProduceDifferentIds() {
        String id1 = OrderService.generateClientOrderId("tv-s", "signal-1");
        String id2 = OrderService.generateClientOrderId("tv-s", "signal-2");

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    @DisplayName("generateClientOrderId: includes the given prefix, so a client order id is still human-identifiable by its origin (e.g. a signal-driven order vs. a manual one) even though the rest is an opaque hash")
    void generateClientOrderId_includesPrefix() {
        String result = OrderService.generateClientOrderId("tv-s", "some-signal-id");

        assertThat(result).startsWith("tv-s-");
    }

    @Test
    @DisplayName("generateClientOrderId: every OCO-family prefix this codebase actually uses (entry, resize, late-fill, remainder) stays within Binance's 36-character limit for a real position id plus a realistic timestamp-length suffix -- the actual review fix (\"OCO client IDs are STILL TOO LONG\"), confirmed for every real call site, not just the general case")
    void generateClientOrderId_everyOcoPrefix_staysWithinLengthLimit() {
        String positionId = java.util.UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();

        assertThat(OrderService.generateClientOrderId("tv-o", positionId + ":OCO").length()).isLessThanOrEqualTo(36);
        assertThat(OrderService.generateClientOrderId("tv-r", positionId + ":RESIZE:" + timestamp).length()).isLessThanOrEqualTo(36);
        assertThat(OrderService.generateClientOrderId("tv-l", positionId + ":LATEFILL:" + timestamp).length()).isLessThanOrEqualTo(36);
        assertThat(OrderService.generateClientOrderId("tv-rem", positionId + ":REMAINDER:" + timestamp).length()).isLessThanOrEqualTo(36);
    }

    // ── recordOcoCancelResult (review item "OMS not actually authoritative" -- full context in
    // the method's own javadoc) ──────────────────────────────────────────

    @Test
    @DisplayName("recordOcoCancelResult: a successful cancel transitions the matching OMS Order all the way to CANCELLED (via CANCEL_PENDING)")
    void recordOcoCancelResult_success_transitionsToCancelled() {
        Order ocoOrder = new Order();
        ocoOrder.setId("oco-oms-1");
        ocoOrder.setBrokerOrderId("oco-999");
        ocoOrder.setStatus(OrderStatus.ACKNOWLEDGED);
        when(orderRepo.findByCredentialIdAndSymbolAndBrokerOrderId("cred-1", "BTCUSDT", "oco-999")).thenReturn(java.util.Optional.of(ocoOrder));

        service.recordOcoCancelResult("cred-1", "BTCUSDT", "oco-999", new OcoOrderResult(true, "oco-999", "{}", null));

        assertThat(ocoOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("recordOcoCancelResult: a failed cancel attempt transitions to UNKNOWN, not CANCELLED and not left at CANCEL_PENDING -- a failed cancel request genuinely doesn't tell us whether the OCO is still live")
    void recordOcoCancelResult_failure_transitionsToUnknown() {
        Order ocoOrder = new Order();
        ocoOrder.setId("oco-oms-1");
        ocoOrder.setBrokerOrderId("oco-999");
        ocoOrder.setStatus(OrderStatus.ACKNOWLEDGED);
        when(orderRepo.findByCredentialIdAndSymbolAndBrokerOrderId("cred-1", "BTCUSDT", "oco-999")).thenReturn(java.util.Optional.of(ocoOrder));

        service.recordOcoCancelResult("cred-1", "BTCUSDT", "oco-999", new OcoOrderResult(false, "oco-999", "{}", "network timeout"));

        assertThat(ocoOrder.getStatus()).isEqualTo(OrderStatus.UNKNOWN);
    }

    @Test
    @DisplayName("recordOcoCancelResult: no matching OMS Order (an OCO placed before this session's OMS wiring existed, or simply not found) is a silent no-op, not an error")
    void recordOcoCancelResult_noMatchingOrder_silentNoOp() {
        when(orderRepo.findByCredentialIdAndSymbolAndBrokerOrderId("cred-1", "BTCUSDT", "oco-999")).thenReturn(java.util.Optional.empty());

        service.recordOcoCancelResult("cred-1", "BTCUSDT", "oco-999", new OcoOrderResult(true, "oco-999", "{}", null));

        verify(orderRepo, never()).save(any());
    }

    @Test
    @DisplayName("recordOcoCancelResult: a null ocoOrderListId is handled gracefully -- never looks anything up, never throws")
    void recordOcoCancelResult_nullOcoOrderListId_handledGracefully() {
        service.recordOcoCancelResult("cred-1", "BTCUSDT", null, new OcoOrderResult(true, null, "{}", null));

        verify(orderRepo, never()).findByCredentialIdAndSymbolAndBrokerOrderId(any(), any(), any());
    }

    // ── recordOcoFillResult (review finding "OCO OMS still doesn't become FILLED when a leg
    // fills" -- full context in the method's own javadoc) ──────────────

    @Test
    @DisplayName("recordOcoFillResult: a full fill transitions the matching OMS Order to FILLED")
    void recordOcoFillResult_fullFill_transitionsToFilled() {
        Order ocoOrder = new Order();
        ocoOrder.setId("oco-oms-1");
        ocoOrder.setBrokerOrderId("oco-999");
        ocoOrder.setStatus(OrderStatus.ACKNOWLEDGED);
        when(orderRepo.findByCredentialIdAndSymbolAndBrokerOrderId("cred-1", "BTCUSDT", "oco-999")).thenReturn(java.util.Optional.of(ocoOrder));

        service.recordOcoFillResult("cred-1", "BTCUSDT", "oco-999", true);

        assertThat(ocoOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    @DisplayName("recordOcoFillResult: a partial fill transitions the matching OMS Order to PARTIALLY_FILLED, not FILLED")
    void recordOcoFillResult_partialFill_transitionsToPartiallyFilled() {
        Order ocoOrder = new Order();
        ocoOrder.setId("oco-oms-1");
        ocoOrder.setBrokerOrderId("oco-999");
        ocoOrder.setStatus(OrderStatus.ACKNOWLEDGED);
        when(orderRepo.findByCredentialIdAndSymbolAndBrokerOrderId("cred-1", "BTCUSDT", "oco-999")).thenReturn(java.util.Optional.of(ocoOrder));

        service.recordOcoFillResult("cred-1", "BTCUSDT", "oco-999", false);

        assertThat(ocoOrder.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    }

    @Test
    @DisplayName("recordOcoFillResult: no matching OMS Order (an OCO placed before this session's OMS wiring existed) is a silent no-op, not an error")
    void recordOcoFillResult_noMatchingOrder_silentNoOp() {
        when(orderRepo.findByCredentialIdAndSymbolAndBrokerOrderId("cred-1", "BTCUSDT", "oco-999")).thenReturn(java.util.Optional.empty());

        service.recordOcoFillResult("cred-1", "BTCUSDT", "oco-999", true);

        verify(mongoTemplate, never()).updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(Order.class));
    }

    @Test
    @DisplayName("recordOcoFillResult: a null ocoOrderListId is handled gracefully -- never looks anything up, never throws")
    void recordOcoFillResult_nullOcoOrderListId_handledGracefully() {
        service.recordOcoFillResult("cred-1", "BTCUSDT", null, true);

        verify(orderRepo, never()).findByCredentialIdAndSymbolAndBrokerOrderId(any(), any(), any());
    }

    // ── transition atomicity (review finding "Order state transitions not atomic across
    // replicas" -- full context in transition's own javadoc) ────────────────

    @Test
    @DisplayName("markRiskAccepted: a successful conditional update (modifiedCount=1, the default stub) transitions normally, no exception")
    void markRiskAccepted_wonRace_transitionsNormally() {
        Order o = newOrder();

        Order result = service.markRiskAccepted(o);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.RISK_ACCEPTED);
    }

    @Test
    @DisplayName("markRiskAccepted: a lost race (another process already changed the status first -- modifiedCount=0) throws a clear, distinct exception rather than silently succeeding with a stale write -- the actual review fix")
    void markRiskAccepted_lostRace_throwsClearException() {
        Order o = newOrder();
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(Order.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null));

        assertThatThrownBy(() -> service.markRiskAccepted(o))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Lost a race");
    }

    // ── trade event ledger (review finding "There is still no authoritative event ledger" --
    // full context in TradeEvent's own javadoc) ────────────────

    @Test
    @DisplayName("create: records an ORDER_CREATED trade event -- the actual start of an order's own event timeline")
    void create_recordsOrderCreatedEvent() {
        newOrder();

        ArgumentCaptor<com.tradevision.model.TradeEvent> captor = ArgumentCaptor.forClass(com.tradevision.model.TradeEvent.class);
        verify(tradeEventRepo).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("ORDER_CREATED");
    }

    @Test
    @DisplayName("markRiskAccepted: records an ORDER_RISK_ACCEPTED trade event on a successful transition")
    void markRiskAccepted_recordsTransitionEvent() {
        Order o = newOrder();
        reset(tradeEventRepo); // ignore the ORDER_CREATED event from newOrder() itself, isolate this specific transition

        service.markRiskAccepted(o);

        ArgumentCaptor<com.tradevision.model.TradeEvent> captor = ArgumentCaptor.forClass(com.tradevision.model.TradeEvent.class);
        verify(tradeEventRepo).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("ORDER_RISK_ACCEPTED");
    }

    @Test
    @DisplayName("markRiskAccepted: a trade-event recording failure never blocks or undoes the transition that already succeeded -- non-fatal, additive, same guarantee as every other observability write this session")
    void tradeEventRecordingFailure_neverBlocksTransition() {
        Order o = newOrder();
        when(tradeEventRepo.save(any())).thenThrow(new RuntimeException("simulated infrastructure failure"));

        Order result = service.markRiskAccepted(o);

        assertThat(result.getStatus()).isEqualTo(OrderStatus.RISK_ACCEPTED);
    }

    // ── recoverStuckSubmittingOrders (review finding "Real-world order recovery needs to cover
    // process crashes, not only HTTP errors" -- P1, full context in the method's own javadoc) ──

    @Test
    @DisplayName("recoverStuckSubmittingOrders: an order stuck in SUBMITTING is marked UNKNOWN and a CRITICAL incident is raised -- the actual review fix, covering the crash case no HTTP-error-based recovery could ever see")
    void recoverStuckSubmittingOrders_marksUnknownAndRaisesIncident() {
        Order stuck = new Order();
        stuck.setId("order1");
        stuck.setUserId("user1");
        stuck.setCredentialId("cred1");
        stuck.setSymbol("BTCUSDT");
        stuck.setClientOrderId("tv-s-abc123");
        stuck.setStatus(OrderStatus.SUBMITTING);
        stuck.setCreatedAt(java.time.LocalDateTime.now().minusMinutes(10));
        when(orderRepo.findByStatusAndCreatedAtBefore(eq(OrderStatus.SUBMITTING), any())).thenReturn(List.of(stuck));
        when(orderRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        service.recoverStuckSubmittingOrders();

        assertThat(stuck.getStatus()).isEqualTo(OrderStatus.UNKNOWN);
        verify(incidentService).raiseCritical(eq("user1"), eq("cred1"), any(), eq("order1"), eq("BTCUSDT"), eq("ORDER_STUCK_IN_SUBMITTING"), any());
    }

    @Test
    @DisplayName("recoverStuckSubmittingOrders: no stuck orders found -- does nothing, no incident raised")
    void recoverStuckSubmittingOrders_noneStuck_doesNothing() {
        when(orderRepo.findByStatusAndCreatedAtBefore(eq(OrderStatus.SUBMITTING), any())).thenReturn(List.of());

        service.recoverStuckSubmittingOrders();

        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * Review finding ("Graceful shutdown does not stop @Scheduled work or WebSocket listeners
     * from starting new work" -- external review, nineteenth pass, P1, confirmed real by direct
     * inspection: this scheduled method had no shutdown-awareness at all before this fix).
     */
    @Test
    @DisplayName("recoverStuckSubmittingOrders: does nothing at all when the process is shutting down")
    void recoverStuckSubmittingOrders_shuttingDown_doesNothing() {
        when(shutdownState.isShuttingDown()).thenReturn(true);

        service.recoverStuckSubmittingOrders();

        verify(orderRepo, never()).findByStatusAndCreatedAtBefore(any(), any());
    }

    @Test
    @DisplayName("recoverStuckSubmittingOrders: a failure handling ONE stuck order does not stop the sweep from processing the rest")
    void recoverStuckSubmittingOrders_oneFailure_doesNotStopTheRest() {
        Order first = new Order();
        first.setId("order1"); first.setStatus(OrderStatus.SUBMITTING); first.setCreatedAt(java.time.LocalDateTime.now().minusMinutes(10));
        Order second = new Order();
        second.setId("order2"); second.setStatus(OrderStatus.SUBMITTING); second.setCreatedAt(java.time.LocalDateTime.now().minusMinutes(10));
        when(orderRepo.findByStatusAndCreatedAtBefore(eq(OrderStatus.SUBMITTING), any())).thenReturn(List.of(first, second));
        when(orderRepo.save(any())).thenAnswer(i -> {
            Order arg = i.getArgument(0);
            if ("order1".equals(arg.getId())) throw new RuntimeException("simulated database failure for the first order only");
            return arg;
        });

        // Must not throw -- the second order's own recovery must still be attempted.
        service.recoverStuckSubmittingOrders();

        assertThat(second.getStatus()).isEqualTo(OrderStatus.UNKNOWN);
    }

    /**
     * Review finding ("Recovery after exchange submission still needs a stronger state
     * boundary" -- external review, twentieth pass, P1, full context in
     * Order.exchangeCallStartedAt's own field javadoc): the actual tests proving the new
     * distinction. Both cases still mark UNKNOWN and escalate -- this fix never auto-resolves
     * anything -- but the incident message content genuinely differs, giving a human reviewing
     * it a materially more informative starting point.
     */
    @Test
    @DisplayName("recoverStuckSubmittingOrders: exchangeCallStartedAt is null -- the incident message states this order likely never reached the exchange at all")
    void recoverStuckSubmittingOrders_neverReachedExchange_messageReflectsThat() {
        Order stuck = new Order();
        stuck.setId("order1"); stuck.setStatus(OrderStatus.SUBMITTING);
        stuck.setCreatedAt(java.time.LocalDateTime.now().minusMinutes(10));
        stuck.setExchangeCallStartedAt(null);
        when(orderRepo.findByStatusAndCreatedAtBefore(eq(OrderStatus.SUBMITTING), any())).thenReturn(List.of(stuck));
        when(orderRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        service.recoverStuckSubmittingOrders();

        assertThat(stuck.getStatus()).isEqualTo(OrderStatus.UNKNOWN);
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), any(),
            argThat(reason -> reason.toString().contains("never reached the point of a real network call")));
    }

    @Test
    @DisplayName("recoverStuckSubmittingOrders: exchangeCallStartedAt IS set -- the incident message states the real exchange call was actually sent")
    void recoverStuckSubmittingOrders_mayHaveReachedExchange_messageReflectsThat() {
        Order stuck = new Order();
        stuck.setId("order1"); stuck.setStatus(OrderStatus.SUBMITTING);
        stuck.setCreatedAt(java.time.LocalDateTime.now().minusMinutes(10));
        stuck.setExchangeCallStartedAt(java.time.LocalDateTime.now().minusMinutes(9));
        when(orderRepo.findByStatusAndCreatedAtBefore(eq(OrderStatus.SUBMITTING), any())).thenReturn(List.of(stuck));
        when(orderRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        service.recoverStuckSubmittingOrders();

        assertThat(stuck.getStatus()).isEqualTo(OrderStatus.UNKNOWN);
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), any(),
            argThat(reason -> reason.toString().contains("was actually sent")));
    }

    // ── P1-4: actual broker verification after marking UNKNOWN ──

    private com.tradevision.model.BrokerCredential activeCredential() {
        var credential = new com.tradevision.model.BrokerCredential();
        credential.setId("cred1");
        credential.setActive(true);
        credential.setMode(com.tradevision.model.BrokerMode.TESTNET);
        return credential;
    }

    private Order stuckOrder() {
        Order stuck = new Order();
        stuck.setId("order1"); stuck.setUserId("user1"); stuck.setCredentialId("cred1");
        stuck.setSymbol("BTCUSDT"); stuck.setClientOrderId("tv-s-abc123");
        stuck.setStatus(OrderStatus.SUBMITTING); stuck.setRequestedQuantity(BigDecimal.valueOf(1.0));
        stuck.setCreatedAt(java.time.LocalDateTime.now().minusMinutes(10));
        return stuck;
    }

    @Test
    @DisplayName("P1-4: broker confirms the order actually FILLED -- transitions the order to FILLED with the real broker order id, instead of leaving it at UNKNOWN forever")
    void recoverStuckSubmittingOrders_brokerConfirmsFilled_transitionsToFilled() {
        Order stuck = stuckOrder();
        when(orderRepo.findByStatusAndCreatedAtBefore(eq(OrderStatus.SUBMITTING), any())).thenReturn(List.of(stuck));
        when(credentialRepo.findById("cred1")).thenReturn(java.util.Optional.of(activeCredential()));
        when(credentialService.adapterForCredential(any())).thenReturn(adapter);
        when(credentialService.decrypt(any(), eq(true))).thenReturn("key");
        when(credentialService.decrypt(any(), eq(false))).thenReturn("secret");
        when(adapter.getOrderStatusByClientOrderId(any(), any(), any(), any(), eq("tv-s-abc123")))
            .thenReturn(new com.tradevision.service.broker.dto.OrderStatusInfo("FILLED", BigDecimal.valueOf(1.0),
                BigDecimal.valueOf(100.0), "{\"orderId\":\"real-broker-order-99\",\"status\":\"FILLED\",\"executedQty\":\"1.0\"}"));

        service.recoverStuckSubmittingOrders();

        assertThat(stuck.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(stuck.getBrokerOrderId()).isEqualTo("real-broker-order-99");
        // No longer left at UNKNOWN -- PositionMonitorService.reconcileEntryOrders' own existing
        // "FILLED BUY order with no Position yet" sweep can now find and protect it.
    }

    @Test
    @DisplayName("P1-4: broker confirms the order genuinely never existed (-2013) -- transitions to REJECTED, safe to treat as never having happened")
    void recoverStuckSubmittingOrders_brokerConfirmsNeverExisted_transitionsToRejected() {
        Order stuck = stuckOrder();
        when(orderRepo.findByStatusAndCreatedAtBefore(eq(OrderStatus.SUBMITTING), any())).thenReturn(List.of(stuck));
        when(credentialRepo.findById("cred1")).thenReturn(java.util.Optional.of(activeCredential()));
        when(credentialService.adapterForCredential(any())).thenReturn(adapter);
        when(credentialService.decrypt(any(), eq(true))).thenReturn("key");
        when(credentialService.decrypt(any(), eq(false))).thenReturn("secret");
        when(adapter.getOrderStatusByClientOrderId(any(), any(), any(), any(), eq("tv-s-abc123")))
            .thenThrow(new RuntimeException("Binance error -2013: Order does not exist."));

        service.recoverStuckSubmittingOrders();

        assertThat(stuck.getStatus()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    @DisplayName("P1-4: broker verification itself fails (network error, not a confirmed absence) -- transitions to RECONCILIATION_REQUIRED, not silently treated as rejected")
    void recoverStuckSubmittingOrders_verificationFails_transitionsToReconciliationRequired() {
        Order stuck = stuckOrder();
        when(orderRepo.findByStatusAndCreatedAtBefore(eq(OrderStatus.SUBMITTING), any())).thenReturn(List.of(stuck));
        when(credentialRepo.findById("cred1")).thenReturn(java.util.Optional.of(activeCredential()));
        when(credentialService.adapterForCredential(any())).thenReturn(adapter);
        when(credentialService.decrypt(any(), eq(true))).thenReturn("key");
        when(credentialService.decrypt(any(), eq(false))).thenReturn("secret");
        when(adapter.getOrderStatusByClientOrderId(any(), any(), any(), any(), eq("tv-s-abc123")))
            .thenThrow(new RuntimeException("connection timeout"));

        service.recoverStuckSubmittingOrders();

        assertThat(stuck.getStatus()).isEqualTo(OrderStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    @DisplayName("P1-4: credential is missing or inactive -- leaves the order at UNKNOWN rather than throwing, same safe fallback as before this fix")
    void recoverStuckSubmittingOrders_credentialMissing_leavesAtUnknown() {
        Order stuck = stuckOrder();
        when(orderRepo.findByStatusAndCreatedAtBefore(eq(OrderStatus.SUBMITTING), any())).thenReturn(List.of(stuck));
        when(credentialRepo.findById("cred1")).thenReturn(java.util.Optional.empty());

        service.recoverStuckSubmittingOrders();

        assertThat(stuck.getStatus()).isEqualTo(OrderStatus.UNKNOWN);
        verifyNoInteractions(adapter);
    }

    /**
     * Review finding, same context as the two tests above: the actual write site -- proving
     * markExchangeCallStarted stamps the field, and does so as a plain, non-fatal field write
     * (same pattern as recordProtectionPlaced), never blocking or altering the caller's own flow.
     */
    /**
     * Review finding (P1 #12 -- "Order state written by whole-document save() with no
     * optimistic locking"): markExchangeCallStarted now writes via a targeted mongoTemplate
     * field-level $set (OrderService.fieldUpdate) instead of a whole-document orderRepo.save() --
     * see fieldUpdate's own javadoc for why. Asserted here by verifying the mongoTemplate call
     * rather than orderRepo.save().
     */
    @Test
    @DisplayName("markExchangeCallStarted: stamps exchangeCallStartedAt via a targeted field update, matching the same pure-field-stamp pattern as recordProtectionPlaced")
    void markExchangeCallStarted_stampsFieldAndSaves() {
        Order order = new Order();
        order.setId("order1");

        service.markExchangeCallStarted(order);

        assertThat(order.getExchangeCallStartedAt()).isNotNull();
        ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
            ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate).updateFirst(any(), updateCaptor.capture(), eq(Order.class));
        assertThat(updateCaptor.getValue().getUpdateObject().get("$set", org.bson.Document.class).get("exchangeCallStartedAt"))
            .isEqualTo(order.getExchangeCallStartedAt());
        verify(orderRepo, never()).save(any());
    }

    @Test
    @DisplayName("markExchangeCallStarted: a write failure is non-fatal -- never throws, since this is a recovery aid, not a precondition for the real exchange call")
    void markExchangeCallStarted_saveFails_doesNotThrow() {
        Order order = new Order();
        order.setId("order1");
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(Order.class)))
            .thenThrow(new RuntimeException("simulated database failure"));

        service.markExchangeCallStarted(order); // must not throw
    }

    // ── recordOcoPlacementResult (review finding "OrderService state transition is STILL not
    // actually atomic for broker results" -- external review, seventeenth pass, P0, full context
    // in atomicUpdate's own javadoc -- this method had zero existing test coverage before this
    // fix, despite having the exact same assertLegal()-then-plain-save() bug the review named) ──

    @Test
    @DisplayName("recordOcoPlacementResult: a successful OCO placement moves SUBMITTING -> ACKNOWLEDGED with the real broker order id recorded")
    void recordOcoPlacementResult_success_movesToAcknowledged() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);

        Order result = service.recordOcoPlacementResult(o, new OcoOrderResult(true, "oco-1", "{}", null));

        assertThat(result.getStatus()).isEqualTo(OrderStatus.ACKNOWLEDGED);
        assertThat(result.getBrokerOrderId()).isEqualTo("oco-1");
        assertThat(result.getBrokerAckAt()).isNotNull();
    }

    @Test
    @DisplayName("recordOcoPlacementResult: a failed OCO placement moves SUBMITTING -> REJECTED with the failure reason recorded")
    void recordOcoPlacementResult_failure_movesToRejected() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);

        Order result = service.recordOcoPlacementResult(o, new OcoOrderResult(false, null, "{}", "insufficient balance"));

        assertThat(result.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(result.getFailureReason()).isEqualTo("insufficient balance");
    }

    @Test
    @DisplayName("recordOcoPlacementResult: a lost race (another process already changed the status first) throws a clear exception -- the actual review fix, proving this method's own atomic update genuinely guards against a concurrent stale write")
    void recordOcoPlacementResult_lostRace_throwsClearException() {
        Order o = newOrder();
        service.markRiskAccepted(o);
        service.markSubmitting(o);
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(Order.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null));

        assertThatThrownBy(() -> service.recordOcoPlacementResult(o, new OcoOrderResult(true, "oco-1", "{}", null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Lost a race");
    }
}
