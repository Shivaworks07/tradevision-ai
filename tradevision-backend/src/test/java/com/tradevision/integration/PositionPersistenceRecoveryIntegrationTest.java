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
 * Review finding ("Position persistence after a real fill still needs stronger recovery
 * testing" -- external review, twenty-ninth pass, P1, the review's own explicit ask: "You need
 * a real integration test... Force Mongo Position.save() failure... Position reconstructed...
 * correct quantity... correct avg price... correct commission... correct reservation... OCO
 * placed... no duplicate BUY"): this is that test, scoped honestly.
 *
 * What this test actually proves, against a real MongoDB (not mocked): an Order that reached
 * FILLED on the exchange, with genuinely no Position ever created for it (simulating exactly
 * the review's own failure window -- Binance FILLED, OMS FILLED, Position.save() itself failed
 * or never ran) is correctly reconstructed by createPositionForLateDiscoveredFill with the real
 * quantity-weighted average price and total commission computed from the (mocked) exchange's own
 * fill data, a genuine atomic slot/exposure reservation taken for it, and -- critically -- that
 * no second BUY order is ever placed during this recovery (the method only ever reads exchange
 * state and writes to Mongo, never calls placeOrder).
 *
 * HONEST SCOPING, stated plainly rather than left implicit:
 * - The broker adapter itself is mocked, not a real Binance Testnet connection -- this proves
 *   the RECOVERY LOGIC's own correctness against real MongoDB writes/reads, not that Binance's
 *   real API behaves as this mock assumes. Real Testnet validation is a separate, larger gap
 *   this same review names, and is not something this test can honestly claim to close.
 * - "Kill process, restart" from the review's own step list is simulated by simply calling the
 *   recovery method directly in a fresh test, rather than literally starting and killing a JVM
 *   -- the method's own behavior is identical either way (it has no in-memory state depending on
 *   how it was reached), but a literal process-kill test would be a meaningfully different,
 *   larger undertaking this test does not claim to be.
 * - OCO placement itself is not exercised here -- createPositionForLateDiscoveredFill's own job
 *   ends at creating a correctly-priced, correctly-reserved, OPEN position; OCO placement is a
 *   separate method this test does not call, so "OCO placed" from the review's own step list is
 *   NOT proven by this test specifically.
 *
 * HONEST LIMITATION shared with every other integration test in this package: `docker ps` fails
 * outright in this sandbox -- no Docker daemon is available here, so I have not executed this
 * test and cannot confirm it passes. Run
 * `mvn test -Dtest=PositionPersistenceRecoveryIntegrationTest` on a machine with Docker
 * available to actually confirm this before trusting it.
 */
@Testcontainers(disabledWithoutDocker = true)
// P1-16 fix: spring.profiles.active now defaults to "prod" (fail-closed), which has no default
// secrets at all -- without this, this Testcontainers-backed context would fail to start
// outside a real deployment with JWT_SECRET/etc set. Explicitly opts into "local" instead, the
// same secrets this test always implicitly relied on before that default changed.
@ActiveProfiles("local")
@SpringBootTest
class PositionPersistenceRecoveryIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired private PositionMonitorService positionMonitorService;
    @Autowired private OrderRepository orderRepo;
    @Autowired private PositionRepository positionRepo;
    @Autowired private ExposureReservationRecordRepository exposureReservationRecordRepo;
    @Autowired private PositionSlotReservationRecordRepository slotReservationRecordRepo;
    @Autowired private RiskProfileRepository riskProfileRepo;
    // CI-review fix (full context on createPositionForLateDiscoveredFill's own instanceId/
    // getInstanceId() comments): needed to acquire a real ReconciliationLock below, under the
    // SAME instanceId the method under test will itself use to renew it.
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
        // own durable record of that, exactly as the real system would have it after
        // Position.save() itself failed or never ran.
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
        // CI-review fix ("Position recovery after a filled order" -- external review, fifth pass,
        // failure 3, confirmed real by direct inspection: this test asserted status == "OPEN" but
        // got "CLOSED_UNVERIFIED_PNL" -- not a production bug, a missing test fixture): this
        // production method (createPositionForLateDiscoveredFill) has a real, deliberate safety
        // branch -- "if (order.getStopLossTriggerPrice() == null || order.getTakeProfitPrice() ==
        // null) { ...emergency-flatten, don't leave a naked position... }" -- which is exactly
        // correct behavior: a late-discovered fill with no recorded SL/TP genuinely cannot be
        // protected, so flattening it rather than leaving it open and naked is the right, safe
        // call, not a bug to work around. This order fixture never set either price, so every run
        // of this test was unconditionally hitting that flatten branch regardless of anything else
        // under test (the quantity-weighted price, commission, and reservation logic this test
        // actually means to exercise). Setting both here, as the real order this scenario models
        // always would have, lets the position stay OPEN and reach the normal OCO-protection path
        // instead.
        order.setStopLossTriggerPrice(BigDecimal.valueOf(49000));
        order.setTakeProfitPrice(BigDecimal.valueOf(52000));
        orderRepo.save(order);

        // Confirm the actual starting condition this test is about: the Order exists, FILLED,
        // and genuinely no Position exists for it yet.
        assertThat(positionRepo.findByEntryOrderId(order.getBrokerOrderId())).isEmpty();

        // The mocked exchange's own fill data -- two partial fills at different prices, the
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
        // CI-review fix (full context on the order fixture's own updated comment above): now that
        // the order carries a real SL/TP, createPositionForLateDiscoveredFill reaches its normal
        // OCO-protection call -- stubbed to succeed, since OCO placement itself is explicitly out
        // of this test's own scope (see this class's HONEST SCOPING note); it only needs to not
        // fail, so the position this test actually means to verify stays OPEN.
        when(adapter.placeExitOco(any(), any(), any(), eq(symbol), any(), any(), any(), any(), any()))
            .thenReturn(new com.tradevision.service.broker.dto.OcoOrderResult(
                true, "test-oco-list-id", "{}", null, BigDecimal.valueOf(0.1)));

        // CI-review fix ("Position recovery after a filled order" -- real CI run, full context in
        // PositionMonitorService.getInstanceId()'s own javadoc): createPositionForLateDiscoveredFill
        // renews the reconciliation lock under this exact instanceId/generation immediately before
        // placing the protective OCO, and emergency-flattens if that renewal fails -- so a real
        // lock, acquired under the SAME instanceId the method itself will renew against, must exist
        // first. A hardcoded generation (1L) with no lock ever acquired was always going to fail
        // that renewal and silently flatten the position this test means to verify stays OPEN.
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

        // CI-review fix ("same 1 test failure now" -- real CI run, finally reached after the
        // Decimal128 exposure-reservation fix above resolved the layer that was masking this one:
        // the test got all the way to this assertion for the first time and failed with
        // "Expecting actual not to be null"): this fixture's own fills pay commission in BTC (the
        // base asset -- see both Fill entries above, commissionAsset = "BTC"), not USDT (the
        // quote asset), and that's deliberate -- it's exactly what makes Invariant 2's
        // quantity-weighted-average-price assertion and the exposure reservation's own 4998.996
        // amount meaningful at all (both depend on PositionSafetyService.computeNetQuantity
        // deducting base-asset commission from the gross fill, per that method's own javadoc).
        // PositionSafetyService.sumCommissionInQuoteAsset -- the method that actually populates
        // entryFeeQuote -- deliberately returns null (not a fabricated, un-converted number)
        // whenever a fill's commission was paid in anything other than the quote asset, per that
        // method's own documented "don't fabricate what you don't know" rule. For THIS fixture's
        // own fee data, entryFeeQuote being null is the correct, intended result, not a bug --
        // asserting isNotNull() here was simply wrong for a fixture that pays commission in the
        // base asset, and nothing before this round of fixes ever actually reached this line to
        // catch it.
        assertThat(recovered.get().getEntryFeeQuote()).isNull();

        // The review's own actual concern here ("total commission across both fills is captured,
        // not just the first") is still real and still worth testing -- just via the metric this
        // fixture's own fee data actually populates: netQuantity. 0.06 + 0.04 = 0.10 gross, minus
        // BOTH fills' own base-asset commission (0.00006 + 0.00004 = 0.0001 total -- a bug that
        // only summed the FIRST fill's commission would produce 0.099940, not 0.099900) gives
        // 0.0999. This is the same quantity PositionLedgerService.reconcilePositionAgainstLedger
        // now also independently reconstructs from the fill ledger and agrees with (see that
        // class's own updated javadoc) -- so this single assertion is corroborated by two
        // independent computations arriving at the same number, not just one.
        assertThat(recovered.get().getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(0.0999));

        // Invariant 4: a genuine, atomic reservation was taken for this recovered position --
        // not created with no reservation at all, which the review's own P1 #4 finding (already
        // fixed earlier this session, referenced in this method's own production-code comment)
        // specifically warns against.
        assertThat(recovered.get().getExposureReservationId()).isNotNull();
        var exposureRecord = exposureReservationRecordRepo.findById(recovered.get().getExposureReservationId());
        assertThat(exposureRecord).isPresent();
        assertThat(exposureRecord.get().getStatus()).isEqualTo("ACTIVE");

        // Invariant 5 -- the review's own explicit "no duplicate BUY" requirement: recovery
        // reads exchange state and writes to Mongo, but never places a new order. A genuine
        // duplicate-BUY bug in this recovery path would show up here as a real placeOrder call.
        verify(adapter, never()).placeOrder(any(), any(), any(), any());
    }
}
