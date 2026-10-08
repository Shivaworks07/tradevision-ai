package com.tradevision.service;

import com.tradevision.config.ShutdownState;
import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import com.tradevision.model.BrokerType;
import com.tradevision.model.LiveCanaryRecord;
import com.tradevision.model.Order;
import com.tradevision.model.OrderStatus;
import com.tradevision.model.Position;
import com.tradevision.repository.LiveCanaryRecordRepository;
import com.tradevision.repository.OrderRepository;
import com.tradevision.repository.PositionRepository;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.OrderResult;
import com.tradevision.service.broker.dto.SymbolRules;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Audit item P0-1, full context in LiveCanaryRecord's own class javadoc.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LiveCanaryServiceTest {

    private static final String PHRASE = "I CONFIRM THIS PLACES A REAL LIVE ORDER WITH REAL MONEY";

    @Mock BrokerCredentialService credentialService;
    @Mock OrderService orderService;
    @Mock OrderRepository orderRepo;
    @Mock PositionRepository positionRepo;
    @Mock PositionSafetyService positionSafetyService;
    @Mock LiveCanaryRecordRepository canaryRepo;
    @Mock IncidentService incidentService;
    @Mock ShutdownState shutdownState;
    @Mock BrokerAdapter adapter;

    @InjectMocks LiveCanaryService service;

    private BrokerCredential liveCredential;
    private SymbolRules rules;

    @BeforeEach
    void setup() {
        liveCredential = new BrokerCredential();
        liveCredential.setId("cred1");
        liveCredential.setBroker(BrokerType.BINANCE);
        liveCredential.setMode(BrokerMode.LIVE);

        rules = new SymbolRules("BTCUSDT", "BTC", "USDT",
            new BigDecimal("0.01"), new BigDecimal("0.0001"), new BigDecimal("0.0001"),
            new BigDecimal("10"), 2, 4, BigDecimal.ZERO, true, false, BigDecimal.ZERO, BigDecimal.ZERO);

        when(credentialService.ownedCredential("user1", "cred1")).thenReturn(liveCredential);
        when(credentialService.adapterForCredential(liveCredential)).thenReturn(adapter);
        when(credentialService.decrypt(eq(liveCredential), eq(true))).thenReturn("api-key");
        when(credentialService.decrypt(eq(liveCredential), eq(false))).thenReturn("api-secret");
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.LIVE)).thenReturn(rules);
        // $50,000/BTC -- the 0.0001 minQty/stepSize floor combined with this price keeps the
        // computed notional comfortably inside MAX_CANARY_NOTIONAL_USD for the happy-path tests.
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(new BigDecimal("50000"));

        when(canaryRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);
    }

    private Order orderInState(OrderStatus status) {
        Order order = new Order();
        order.setId("order1");
        order.setCredentialId("cred1");
        order.setSymbol("BTCUSDT");
        order.setStatus(status);
        return order;
    }

    @Test
    @DisplayName("startCanary: wrong confirmation phrase — refuses before ever touching the broker")
    void wrongPhrase_refuses() {
        assertThatThrownBy(() -> service.startCanary("user1", "cred1", "BTCUSDT", "wrong phrase"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Confirmation phrase");

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("startCanary: credential is not LIVE — refuses, this gate only applies to LIVE credentials")
    void notLiveCredential_refuses() {
        liveCredential.setMode(BrokerMode.TESTNET);

        assertThatThrownBy(() -> service.startCanary("user1", "cred1", "BTCUSDT", PHRASE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("LIVE credential");

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("startCanary: withdrawal-enabled credential — refused defensively even though this should be unreachable")
    void withdrawalEnabled_refuses() {
        liveCredential.setWithdrawalEnabled(true);

        assertThatThrownBy(() -> service.startCanary("user1", "cred1", "BTCUSDT", PHRASE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("withdrawal-enabled");
    }

    @Test
    @DisplayName("startCanary: computed notional would exceed the safety ceiling — refuses rather than placing an oversized order")
    void notionalTooLarge_refuses() {
        // minNotional 1,000 * 1.15 buffer = 1,150, far past MAX_CANARY_NOTIONAL_USD (25).
        SymbolRules expensiveRules = new SymbolRules("BTCUSDT", "BTC", "USDT",
            new BigDecimal("0.01"), new BigDecimal("0.0001"), new BigDecimal("0.0001"),
            new BigDecimal("1000"), 2, 4, BigDecimal.ZERO, true, false, BigDecimal.ZERO, BigDecimal.ZERO);
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.LIVE)).thenReturn(expensiveRules);

        assertThatThrownBy(() -> service.startCanary("user1", "cred1", "BTCUSDT", PHRASE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exceeds");

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("startCanary: current price unavailable — refuses rather than sizing an order against nothing")
    void noCurrentPrice_refuses() {
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(null);

        assertThatThrownBy(() -> service.startCanary("user1", "cred1", "BTCUSDT", PHRASE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("current price");
    }

    @Test
    @DisplayName("startCanary: order placed and acknowledged — creates a PENDING record carrying the order/entry id")
    void placedSuccessfully_createsPendingRecord() {
        Order created = orderInState(OrderStatus.CREATED);
        when(orderService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);
        when(orderService.markRiskAccepted(created)).thenReturn(created);
        when(orderService.markSubmitting(created)).thenReturn(created);

        OrderResult result = new OrderResult(true, "broker-order-1", "client1", "FILLED",
            new BigDecimal("0.001"), new BigDecimal("50000"), "{}", null);
        when(adapter.placeOrder(eq("api-key"), eq("api-secret"), eq(BrokerMode.LIVE), any())).thenReturn(result);

        Order filled = orderInState(OrderStatus.FILLED);
        filled.setBrokerOrderId("broker-order-1");
        when(orderService.recordBrokerResult(created, result)).thenReturn(filled);
        when(orderService.recordEntryMetadata(eq(filled), any(), any(), eq("LIVE_CANARY"), any(), any(), any())).thenReturn(filled);

        LiveCanaryRecord record = service.startCanary("user1", "cred1", "BTCUSDT", PHRASE);

        assertThat(record.getStatus()).isEqualTo("PENDING");
        assertThat(record.getOrderId()).isEqualTo("order1");
        assertThat(record.getEntryOrderId()).isEqualTo("broker-order-1");
        assertThat(record.getCredentialId()).isEqualTo("cred1");
        assertThat(record.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(record.getQuantity()).isNotNull();
        verify(canaryRepo).save(any());
    }

    @Test
    @DisplayName("startCanary: broker rejects the order outright — the record is FAILED immediately, not left PENDING")
    void brokerRejects_recordFailedImmediately() {
        Order created = orderInState(OrderStatus.CREATED);
        when(orderService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);
        when(orderService.markRiskAccepted(created)).thenReturn(created);
        when(orderService.markSubmitting(created)).thenReturn(created);

        OrderResult failure = OrderResult.failure("insufficient balance", "{}");
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(failure);

        Order rejected = orderInState(OrderStatus.REJECTED);
        rejected.setFailureReason("insufficient balance");
        when(orderService.recordBrokerResult(created, failure)).thenReturn(rejected);
        when(orderService.recordEntryMetadata(eq(rejected), any(), any(), eq("LIVE_CANARY"), any(), any(), any())).thenReturn(rejected);

        LiveCanaryRecord record = service.startCanary("user1", "cred1", "BTCUSDT", PHRASE);

        assertThat(record.getStatus()).isEqualTo("FAILED");
        assertThat(record.getFailureReason()).isEqualTo("insufficient balance");
        assertThat(record.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("hasRecentPassingCanary: a PASSED record within the lookback window — true")
    void hasRecentPassingCanary_true() {
        when(canaryRepo.findByCredentialIdAndStatusAndCompletedAtAfterOrderByCompletedAtDesc(eq("cred1"), eq("PASSED"), any()))
            .thenReturn(List.of(new LiveCanaryRecord()));

        assertThat(service.hasRecentPassingCanary("cred1")).isTrue();
    }

    @Test
    @DisplayName("hasRecentPassingCanary: no PASSED record at all — false")
    void hasRecentPassingCanary_false() {
        when(canaryRepo.findByCredentialIdAndStatusAndCompletedAtAfterOrderByCompletedAtDesc(eq("cred1"), eq("PASSED"), any()))
            .thenReturn(List.of());

        assertThat(service.hasRecentPassingCanary("cred1")).isFalse();
    }

    private LiveCanaryRecord pendingRecord() {
        LiveCanaryRecord record = new LiveCanaryRecord();
        record.setId("canary1");
        record.setUserId("user1");
        record.setCredentialId("cred1");
        record.setBroker(BrokerType.BINANCE);
        record.setSymbol("BTCUSDT");
        record.setOrderId("order1");
        record.setEntryOrderId("broker-order-1");
        record.setStatus("PENDING");
        record.setStartedAt(LocalDateTime.now());
        return record;
    }

    @Test
    @DisplayName("reconcilePendingCanaries: the OMS order itself ended in a terminal failure — FAILED, and an incident is raised")
    void reconcile_orderTerminalFailure_marksFailed() {
        LiveCanaryRecord record = pendingRecord();
        when(canaryRepo.findByStatus("PENDING")).thenReturn(List.of(record));
        Order rejected = orderInState(OrderStatus.REJECTED);
        rejected.setFailureReason("exchange rejected the order");
        when(orderRepo.findById("order1")).thenReturn(Optional.of(rejected));

        service.reconcilePendingCanaries();

        ArgumentCaptor<LiveCanaryRecord> captor = ArgumentCaptor.forClass(LiveCanaryRecord.class);
        verify(canaryRepo, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reconcilePendingCanaries: order filled, Position found with real OCO protection — PASSED, and the test position is closed")
    void reconcile_filledWithOco_marksPassedAndCloses() {
        LiveCanaryRecord record = pendingRecord();
        when(canaryRepo.findByStatus("PENDING")).thenReturn(List.of(record));
        Order filled = orderInState(OrderStatus.FILLED);
        filled.setBrokerOrderId("broker-order-1");
        when(orderRepo.findById("order1")).thenReturn(Optional.of(filled));

        Position position = new Position();
        position.setId("pos1");
        position.setStatus("OPEN");
        position.setOcoOrderListId("oco-list-1");
        when(positionRepo.findByCredentialIdAndSymbolAndEntryOrderId("cred1", "BTCUSDT", "broker-order-1"))
            .thenReturn(Optional.of(position));
        when(credentialService.ownedCredential("user1", "cred1")).thenReturn(liveCredential);

        service.reconcilePendingCanaries();

        ArgumentCaptor<LiveCanaryRecord> captor = ArgumentCaptor.forClass(LiveCanaryRecord.class);
        verify(canaryRepo, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().stream().anyMatch(r -> "PASSED".equals(r.getStatus()))).isTrue();
        verify(positionSafetyService).exitPosition(eq(liveCredential), eq(adapter), eq("api-key"), eq("api-secret"),
            eq(position), eq("LIVE_CANARY_CLEANUP"));
    }

    @Test
    @DisplayName("reconcilePendingCanaries: order filled, no Position yet, still within the timeout window — left PENDING, no incident raised")
    void reconcile_filledNoPositionYet_staysPending() {
        LiveCanaryRecord record = pendingRecord();
        when(canaryRepo.findByStatus("PENDING")).thenReturn(List.of(record));
        Order filled = orderInState(OrderStatus.FILLED);
        filled.setBrokerOrderId("broker-order-1");
        when(orderRepo.findById("order1")).thenReturn(Optional.of(filled));
        when(positionRepo.findByCredentialIdAndSymbolAndEntryOrderId("cred1", "BTCUSDT", "broker-order-1"))
            .thenReturn(Optional.empty());

        service.reconcilePendingCanaries();

        ArgumentCaptor<LiveCanaryRecord> captor = ArgumentCaptor.forClass(LiveCanaryRecord.class);
        verify(canaryRepo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), any(), any());
        verify(positionSafetyService, never()).exitPosition(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reconcilePendingCanaries: timed out waiting for a protected Position — FAILED")
    void reconcile_timedOut_marksFailed() {
        LiveCanaryRecord record = pendingRecord();
        record.setStartedAt(LocalDateTime.now().minusMinutes(20)); // past the 15-minute timeout
        when(canaryRepo.findByStatus("PENDING")).thenReturn(List.of(record));
        Order filled = orderInState(OrderStatus.FILLED);
        filled.setBrokerOrderId("broker-order-1");
        when(orderRepo.findById("order1")).thenReturn(Optional.of(filled));
        when(positionRepo.findByCredentialIdAndSymbolAndEntryOrderId("cred1", "BTCUSDT", "broker-order-1"))
            .thenReturn(Optional.empty());

        service.reconcilePendingCanaries();

        ArgumentCaptor<LiveCanaryRecord> captor = ArgumentCaptor.forClass(LiveCanaryRecord.class);
        verify(canaryRepo, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().stream().anyMatch(r -> "FAILED".equals(r.getStatus()))).isTrue();
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reconcilePendingCanaries: shutdown in progress — the sweep does nothing at all")
    void reconcile_shuttingDown_doesNothing() {
        when(shutdownState.isShuttingDown()).thenReturn(true);

        service.reconcilePendingCanaries();

        verify(canaryRepo, never()).findByStatus(any());
    }
}
