package com.tradevision.integration;

import com.tradevision.model.*;
import com.tradevision.repository.*;
import com.tradevision.service.PositionMonitorService;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.Fill;
import com.tradevision.service.broker.dto.SymbolRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies position recovery against a real MongoDB (not mocked): an Order that reached FILLED
 * on the exchange, with no Position ever created for it (Binance FILLED, OMS FILLED, but
 * Position.save() failed or never ran) is correctly reconstructed by
 * createPositionForLateDiscoveredFill with the real quantity-weighted average price and total
 * commission computed from the (mocked) exchange's own fill data, a genuine atomic
 * slot/exposure reservation taken for it, and that no second BUY order is ever placed during
 * this recovery (the method only ever reads exchange state and writes to Mongo, never calls
 * placeOrder).
 *
 * Scope:
 * - The broker adapter itself is mocked, not a real Binance Testnet connection -- this proves
 *   the recovery logic's own correctness against real MongoDB writes/reads, not that Binance's
 *   real API behaves as this mock assumes.
 * - A process kill/restart is simulated by calling the recovery method directly in a fresh
 *   test, rather than literally starting and killing a JVM -- the method has no in-memory state
 *   depending on how it was reached.
 * - OCO placement itself is not exercised here -- createPositionForLateDiscoveredFill's own job
 *   ends at creating a correctly-priced, correctly-reserved, OPEN position; OCO placement is a
 *   separate method this test does not call.
 *
 * Requires Docker (Testcontainers); run
 * `mvn test -Dtest=PositionPersistenceRecoveryIntegrationTest` on a machine with Docker
 * available.
 */
@Testcontainers(disabledWithoutDocker = true)
// spring.profiles.active defaults to "prod" (fail-closed), which has no default secrets at all
// -- without this, this Testcontainers-backed context would fail to start outside a real
// deployment with JWT_SECRET/etc set. Explicitly opts into "local" instead, which has the
// secrets this test relies on.
@ActiveProfiles("local")
@SpringBootTest
class PositionPersistenceRecoveryIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired private PositionMonitorService positionMonitorService;
    @Autowired private OrderRepository orderRepo;
    @Autowired private PositionRepository positionRepo;
    @Autowired private ExposureReservationRecordRepository exposureReservationRecordRepo;
    @Autowired private PositionSlotReservationRecordRepository slotReservationRecordRepo;
    @Autowired private RiskProfileRepository riskProfileRepo;
    // Needed to acquire a real ReconciliationLock below, under the same instanceId the method
    // under test will itself use to renew it.
    @Autowired private com.tradevision.service.DistributedLockService distributedLockService;

    @Test
    @DisplayName("createPositionForLateDiscoveredFill: an Order that reached FILLED with genuinely no Position ever created for it (Position.save() failed, or the process crashed before it ran) is reconstructed against a real MongoDB with the correct quantity-weighted average price, total commission, a genuine atomic reservation, OPEN status -- and never places a second BUY order")
    void orderFilledWithMissingPosition_reconstructedCorrectly_noDuplicateBuy() {
        String credentialId = "integration-test-recovery-" + System.currentTimeMillis();
        String symbol = "BTCUSDT";

        var credential = new BrokerCredential();
        credential.setId(credentialId);
        credential.setUserId("user1");
        credential.setBroker(BrokerType.BINANCE);
        credential.setMode(BrokerMode.TESTNET);

        var profile = new RiskProfile();
        profile.setCredentialId(credentialId);
        profile.setUserId("user1");
        profile.setMaxConcurrentTrades(5);
        profile.setMaxTotalExposureQuote(BigDecimal.valueOf(50_000));
        profile.setMaxSymbolExposureQuote(BigDecimal.valueOf(20_000));
        riskProfileRepo.save(profile);

        // The Order genuinely reached FILLED on the (simulated) exchange -- this is the OMS's
        // durable record of that, exactly as the real system would have it after Position.save()
        // failed or never ran.
        var order = new Order();
        order.setId("order-" + System.currentTimeMillis());
        order.setClientOrderId("tv-e-test-clientid-1");
        order.setBrokerOrderId("999888777");
        order.setCredentialId(credentialId);
        order.setSymbol(symbol);
        order.setSide("BUY");
        order.setType("MARKET");
        order.setStatus(OrderStatus.FILLED);
        order.setRequestedQuantity(BigDecimal.valueOf(0.1));
        order.setFilledQuantity(BigDecimal.valueOf(0.1));
        order.setCreatedAt(LocalDateTime.now());
        // createPositionForLateDiscoveredFill has a deliberate safety branch: if the order has no
        // recorded stop-loss/take-profit, it emergency-flattens rather than leaving a naked
        // position open. Setting both prices here, as the real order this scenario models would
        // have, lets the position stay OPEN and reach the normal OCO-protection path, so the
        // quantity-weighted price, commission, and reservation logic below are the thing actually
        // exercised.
        order.setStopLossTriggerPrice(BigDecimal.valueOf(49000));
        order.setTakeProfitPrice(BigDecimal.valueOf(52000));
        orderRepo.save(order);

        // Confirm the starting condition this test is about: the Order exists, FILLED, and no
        // Position exists for it yet.
        assertThat(positionRepo.findByEntryOrderId(order.getBrokerOrderId())).isEmpty();

        // The mocked exchange's fill data -- two partial fills at different prices, the
        // realistic case that makes a naive "first fill's price" computation wrong.
        var fills = java.util.List.of(
            new Fill(BigDecimal.valueOf(50000), BigDecimal.valueOf(0.06), BigDecimal.valueOf(0.00006), "BTC"),
            new Fill(BigDecimal.valueOf(50100), BigDecimal.valueOf(0.04), BigDecimal.valueOf(0.00004), "BTC"));
        var adapter = mock(BrokerAdapter.class);
        when(adapter.getFillsForOrder(any(), any(), any(), eq(symbol), eq(order.getBrokerOrderId()))).thenReturn(fills);
        when(adapter.getSymbolRules(eq(symbol), any())).thenReturn(
            new SymbolRules(symbol, "BTC", "USDT", BigDecimal.valueOf(0.01), BigDecimal.valueOf(0.00001),
                BigDecimal.valueOf(0.0001), BigDecimal.valueOf(10), 2, 5,
                BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO));
        // Now that the order carries a real SL/TP, createPositionForLateDiscoveredFill reaches
        // its normal OCO-protection call -- stubbed to succeed, since OCO placement itself is
        // out of this test's scope; it only needs to not fail, so the position stays OPEN.
        when(adapter.placeExitOco(any(), any(), any(), eq(symbol), any(), any(), any(), any(), any()))
            .thenReturn(new com.tradevision.service.broker.dto.OcoOrderResult(
                true, "test-oco-list-id", "{}", null, BigDecimal.valueOf(0.1)));

        // createPositionForLateDiscoveredFill renews the reconciliation lock under this exact
        // instanceId/generation immediately before placing the protective OCO, and
        // emergency-flattens if that renewal fails -- so a real lock, acquired under the same
        // instanceId the method itself will renew against, must exist first.
        var lockLease = distributedLockService.tryAcquireWithDiagnosis(
            credentialId, positionMonitorService.getInstanceId(), java.time.Duration.ofSeconds(90));
        assertThat(lockLease.acquired()).isTrue();

        // The actual recovery call -- this is what a real reconciliation pass calls when it
        // finds a FILLED order with no matching Position.
        positionMonitorService.createPositionForLateDiscoveredFill(
            credential, adapter, "test-api-key", "test-api-secret", order, BigDecimal.valueOf(0.1), lockLease.generation());

        // Invariant 1: a real Position now exists, OPEN, for this exact order.
        var recovered = positionRepo.findByEntryOrderId(order.getBrokerOrderId());
        assertThat(recovered).isPresent();
        assertThat(recovered.get().getStatus()).isEqualTo("OPEN");

        // Invariant 2: the quantity-weighted average price is correct -- (0.06*50000 +
        // 0.04*50100) / 0.1 = 50040, not either individual fill's own price.
        assertThat(recovered.get().getAvgEntryPrice()).isEqualByComparingTo(BigDecimal.valueOf(50040));

        // This fixture's fills pay commission in BTC (the base asset -- see both Fill entries
        // above, commissionAsset = "BTC"), not USDT (the quote asset), and that's deliberate --
        // it's exactly what makes the quantity-weighted-average-price assertion above and the
        // exposure reservation amount below meaningful at all (both depend on
        // PositionSafetyService.computeNetQuantity deducting base-asset commission from the
        // gross fill). PositionSafetyService.sumCommissionInQuoteAsset -- the method that
        // populates entryFeeQuote -- returns null whenever a fill's commission was paid in
        // anything other than the quote asset, rather than fabricating an un-converted number.
        // For this fixture's fee data, entryFeeQuote being null is the correct, intended result.
        assertThat(recovered.get().getEntryFeeQuote()).isNull();

        // Total commission across both fills needs to be captured, not just the first -- tested
        // via the metric this fixture's fee data actually populates: netQuantity. 0.06 + 0.04 =
        // 0.10 gross, minus both fills' base-asset commission (0.00006 + 0.00004 = 0.0001 total)
        // gives 0.0999. This is the same quantity PositionLedgerService.reconcilePositionAgainstLedger
        // independently reconstructs from the fill ledger and agrees with.
        assertThat(recovered.get().getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(0.0999));

        // Invariant 4: a genuine, atomic reservation was taken for this recovered position.
        assertThat(recovered.get().getExposureReservationId()).isNotNull();
        var exposureRecord = exposureReservationRecordRepo.findById(recovered.get().getExposureReservationId());
        assertThat(exposureRecord).isPresent();
        assertThat(exposureRecord.get().getStatus()).isEqualTo("ACTIVE");

        // Invariant 5: recovery reads exchange state and writes to Mongo, but never places a new
        // order -- no duplicate BUY.
        verify(adapter, never()).placeOrder(any(), any(), any(), any());
    }
}
