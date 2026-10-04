package com.tradevision.service;

import com.tradevision.model.*;
import com.tradevision.repository.*;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.AssetBalance;
import com.tradevision.service.broker.dto.Fill;
import com.tradevision.service.broker.dto.OcoOrderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Review finding ("#17" — "drawdown still isn't mark-to-market"): tests the actual equity math
 * (free balance + open-position market value), not just that the method runs. checkDrawdown was
 * made package-private specifically so these can exercise it directly without needing to stand
 * up the whole reconcileCredential() call chain.
 *
 * Review finding ("P0 #1" — "OCO ALL_DONE with NO filled leg can falsely close a real
 * position"): handleOcoAllDoneWithNoFill gets the same treatment — made package-private,
 * tested directly, since this is exactly the kind of money-consequential branch that had zero
 * coverage before.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PositionMonitorServiceTest {

    @Mock BrokerCredentialRepository credentialRepo;
    // Review finding ("OMS/ExecutedOrder full unification" -- P1, full context in
    // PositionMonitorService's own removal of this same field): the real Order (OMS)
    // repository the service now actually calls for every read this file's own tests used to
    // stub on the now-removed ExecutedOrderRepository field.
    @Mock com.tradevision.repository.OrderRepository omsOrderRepo;
    @Mock com.tradevision.repository.FlattenAttemptRepository flattenAttemptRepo;
    @Mock com.tradevision.repository.OrphanedOcoRepository orphanedOcoRepo;
    @Mock com.tradevision.repository.ProtectionAttemptRepository protectionAttemptRepo;
    @Mock com.tradevision.repository.StrategyPlanRepository strategyPlanRepo;
    @Mock StrategyPlanService strategyPlanService;
    @Mock PositionRepository positionRepo;
    @Mock org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    @Mock com.tradevision.config.ShutdownState shutdownState;
    @Mock com.tradevision.config.StartupState startupState;
    @Mock com.tradevision.config.TradingHeartbeatService heartbeatService;
    @Mock FillLedgerService fillLedgerService;
    @Mock IncidentService incidentService;
    @Mock ExecutionContextService executionContextService;
    @Mock PositionSlotReservationService slotReservationService;
    @Mock ExposureReservationService exposureReservationService;
    @Mock PositionSafetyService positionSafetyService;
    @Mock RiskProfileRepository riskProfileRepo;
    @Mock TradeCallRepository callRepo;
    // Review finding (P1 #5 -- "Global ML weights can be poisoned by unverified, client-supplied
    // trade outcomes"): writeRealOutcomeBackToSignal now records the ML outcome for a real fill
    // directly -- without this mock, @InjectMocks would leave the field null.
    @Mock MLWeightService mlWeightService;
    @Mock BrokerCredentialService credentialService;
    @Mock RiskEngineService riskEngine;
    @Mock BrokerAdapter adapter;
    // Review finding ("Position P&L architecture is still scattered" -- full context in
    // RealizedPnlService's own javadoc): @Spy (a REAL instance), same reasoning as
    // PositionSafetyServiceTest's own identical addition.
    @Spy RealizedPnlService realizedPnlService = new RealizedPnlService();
    // Review finding ("Position Ledger is still not authoritative" -- full context in
    // PositionLedgerService's own javadoc): @Mock with a default "genuine match" stub added in
    // @BeforeEach below -- same NPE risk as RealizedPnlService's own comment above, since
    // reconcilePositionAgainstLedger returns an object type, not a boolean.
    @Mock PositionLedgerService positionLedgerService;
    // Review finding ("OMS not actually authoritative" -- P0, full context in
    // PositionSafetyServiceTest's own identical addition): needed now that every OCO placement
    // site in this class gets a real OMS Order record.
    @Mock OrderService orderService;
    // Review finding ("Reconciliation lock renewal failure currently continues anyway" --
    // external review): confirmed a genuine, pre-existing gap while investigating this claim --
    // PositionMonitorService depends on DistributedLockService (both tryAcquire, called
    // unconditionally in the public reconcileCredential() wrapper, and now renew() too), but
    // this test file never mocked it at all. @InjectMocks would leave this field null, meaning
    // any test that actually calls the public wrapper (not just reconcileCredentialLocked
    // directly) would NPE on the very first tryAcquire call -- a real gap that predates this
    // specific fix, not introduced by it, but one this fix's own new test needs regardless.
    @Mock DistributedLockService distributedLockService;

    @InjectMocks PositionMonitorService service;

    private BrokerCredential credential;
    private RiskProfile profile;

    private static final com.tradevision.service.broker.dto.SymbolRules BTC_RULES =
        new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);

    @BeforeEach
    void setup() {
        // Review finding ("Reconciliation lock renewal failure currently continues anyway" --
        // external review, full context in this file's own new @Mock field comment above): a
        // realistic "this call succeeded" default for both distributed-lock calls the public
        // reconcileCredential() wrapper and reconcileCredentialLocked() now depend on -- an
        // unstubbed boolean-returning call defaults to false in Mockito, which would otherwise
        // make every existing test in this file that reaches either call silently stop right
        // there (tryAcquire failing skips reconciliation entirely; renew failing now correctly
        // stops the pass early too, per this fix's own new behavior) instead of exercising the
        // real reconciliation logic these tests actually mean to test. A test that specifically
        // wants to exercise either lost-the-lock path overrides these explicitly.
        // Review finding ("DistributedLockService has a subtle generation race" -- external
        // review, twenty-ninth pass, P1, full context in DistributedLockService.LockLease's own
        // javadoc): production code now calls tryAcquireWithDiagnosis() directly instead of the
        // plain boolean tryAcquire() + a separate currentGeneration() query -- this single stub
        // replaces both of the old ones, carrying the generation on the same return value.
        when(distributedLockService.tryAcquireWithDiagnosis(any(), any(), any()))
            .thenReturn(new com.tradevision.service.DistributedLockService.LockLease(
                com.tradevision.service.DistributedLockService.AcquireResult.ACQUIRED, 1L));
        // Review finding ("DistributedLockService.renew() does not verify ownership generation"
        // -- external review, second pass, full context in PositionSafetyServiceTest's own
        // identical fix): migrated to the new, generation-aware 4-arg overload the production
        // code now actually calls exclusively -- the old 3-arg stub is dead against it.
        when(distributedLockService.renew(any(), any(), anyLong(), any())).thenReturn(true);
        // Review finding ("Position close has atomic protection; not every position mutation
        // does" -- P1, full context at the actual new atomic-update call in
        // reconcileOcoProtectedPosition's own partial-exit branch): a genuine, pre-existing gap
        // discovered while adding this fix, not caused by it -- mongoTemplate.updateFirst() has
        // had NO stub anywhere in this file since the full-close atomic update was first added
        // earlier this session, meaning Mockito's own confirmed default (null for an unstubbed
        // object-returning call, verified directly against Mockito's own documented behavior
        // before concluding this, not assumed) would NPE every test that reaches EITHER atomic
        // update path -- full close or this new partial exit. A realistic "the conditional
        // update succeeded" default, matching this codebase's own established pattern for every
        // other object-returning dependency this session (OrderServiceTest's own identical
        // UpdateResult default is the closest precedent).
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(Position.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
        credential = new BrokerCredential();
        credential.setId("cred1");
        credential.setUserId("user1");
        credential.setBroker(BrokerType.BINANCE);
        credential.setMode(BrokerMode.TESTNET);

        profile = new RiskProfile();
        profile.setUserId("user1");
        profile.setCredentialId("cred1");
        profile.setDrawdownQuoteAsset("USDT");
        profile.setMaxDrawdownPercent(10.0);

        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(profile));
        // checkDrawdown() issues an atomic $max update against peakEquityQuote, then re-fetches
        // the profile via mongoTemplate.findOne() to read back the winning value -- realistic
        // "the database really did apply this atomic max" simulation, mutating the same live
        // profile object updateFirst is called against so the subsequent findOne naturally sees
        // it. Without this, mongoTemplate.findOne(..., RiskProfile.class) was entirely unstubbed
        // (Mockito's own null default for an object-returning call), so checkDrawdown's own
        // "if (refreshed == null) return" guard fired on every single test that reaches it,
        // silently skipping the rest of the method before peakEquityQuote was ever actually set
        // -- confirmed directly against the real method's own sequential updateFirst-then-findOne
        // shape, not assumed.
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(RiskProfile.class)))
            .thenAnswer(inv -> {
                org.springframework.data.mongodb.core.query.Update update = inv.getArgument(1);
                Object maxValue = update.getUpdateObject().get("$max") instanceof org.bson.Document maxDoc
                    ? maxDoc.get("peakEquityQuote") : null;
                if (maxValue instanceof java.math.BigDecimal newPeak) {
                    java.math.BigDecimal current = profile.getPeakEquityQuote();
                    if (current == null || newPeak.compareTo(current) > 0) {
                        profile.setPeakEquityQuote(newPeak);
                    }
                }
                return com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null);
            });
        when(mongoTemplate.findOne(any(), eq(RiskProfile.class))).thenAnswer(inv -> profile);
        // Review finding ("Position Ledger is still not authoritative" -- full context in
        // PositionLedgerService's own javadoc): a realistic "genuine match" default, same
        // reasoning as this file's own @Spy RealizedPnlService default -- an unstubbed
        // reconcilePositionAgainstLedger would otherwise return null (an object type, not a
        // boolean), NPEing every existing test that reaches the new position-close reconciliation
        // check. A test that wants the mismatch path overrides this explicitly.
        when(positionLedgerService.reconcilePositionAgainstLedger(any(), any(), any()))
            .thenAnswer(invocation -> new PositionLedgerService.ReconcileResult(PositionLedgerService.ReconcileStatus.MATCH, invocation.getArgument(1), invocation.getArgument(1)));
        when(credentialService.decrypt(any(), org.mockito.ArgumentMatchers.eq(true))).thenReturn("key");
        when(credentialService.decrypt(any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn("secret");
        // Confirmed gap (see this file's own @Mock BrokerAdapter comment above): @InjectMocks
        // cannot populate a List<BrokerAdapter> field from a single @Mock BrokerAdapter --
        // Mockito only matches fields/params of the exact mock type, never a collection
        // containing it. Left unset, "adapters" stays null, and reconcileCredentialLocked's
        // unconditional adapters.stream() NPEs on the very first line of every test that reaches
        // it -- the vast majority of this file's tests, confirmed directly against the actual
        // test run rather than assumed. A handful of tests already worked around this locally
        // with their own setField call; this makes it the shared default so every test gets it,
        // and a test that specifically wants an empty adapter list still overrides it after
        // setup() runs.
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adapters", List.of(adapter));
        // reconcileCredentialLocked() builds adapterMap keyed by BrokerAdapter.getType() and looks the
        // adapter up by credential.getBroker() -- an unstubbed getType() returns null, the lookup
        // misses, and the whole pass silently returns before doing anything.
        when(adapter.getType()).thenReturn(BrokerType.BINANCE);
        // P1-1 fix: reconcileCredentialLocked (and every other per-credential adapter lookup in
        // this service) now resolves the adapter via credentialService.adapterForCredential(...)
        // instead of the local BrokerType-keyed adapterMap, so PAPER credentials are correctly
        // routed to the simulated adapter instead of the real one. Tests exercise a TESTNET
        // credential by default, so this just needs to return the same mocked adapter.
        when(credentialService.adapterForCredential(any())).thenReturn(adapter);
        // Production calls the 9-arg reserve(..., boolean live) overload (the 8-arg one just delegates to
        // it, which never happens on a mock). Unstubbed it returns null and NPEs on exposureResult.allowed().
        when(exposureReservationService.reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new com.tradevision.service.ExposureReservationService.ExposureReserveResult(true, null, "test-reservation-id"));
    }

    private Position openPosition(String symbol, double qty) {
        Position p = new Position();
        p.setSymbol(symbol);
        p.setQuantity(BigDecimal.valueOf(qty));
        p.setStatus("OPEN");
        return p;
    }

    @Test
    @DisplayName("checkDrawdown: equity includes open-position market value, not just free balance")
    void checkDrawdown_includesOpenPositionValue() {
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("USDT", BigDecimal.valueOf(1000), BigDecimal.ZERO)));
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("user1", "cred1", "OPEN"))
            .thenReturn(List.of(openPosition("BTCUSDT", 0.1)));
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BigDecimal.valueOf(50000));
        // equity should be 1000 (free) + 0.1 * 50000 (position value) = 6000

        service.checkDrawdown(credential, adapter);

        assertThat(profile.getPeakEquityQuote()).isEqualByComparingTo("6000");
    }

    /**
     * P1-15 fix ("Drawdown ignores locked USDT"): confirmed real -- equity used to read ONLY
     * AssetBalance.free(), so quote genuinely reserved by the exchange (a resting LIMIT order,
     * for instance) was invisible to this calculation, understating real equity and risking a
     * fabricated drawdown breach the moment funds are locked (they don't actually disappear).
     */
    @Test
    @DisplayName("checkDrawdown: equity includes LOCKED quote balance, not free only -- the P1-15 fix")
    void checkDrawdown_includesLockedQuoteBalance() {
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("USDT", BigDecimal.valueOf(1000), BigDecimal.valueOf(500))));
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("user1", "cred1", "OPEN")).thenReturn(List.of());
        // equity should be 1000 free + 500 locked + 0 open positions = 1500, not 1000.

        service.checkDrawdown(credential, adapter);

        assertThat(profile.getPeakEquityQuote()).isEqualByComparingTo("1500");
    }

    @Test
    @DisplayName("checkDrawdown: halts trading once mark-to-market equity drops below the configured threshold from peak")
    void checkDrawdown_haltsOnDrawdownIncludingOpenPositionLoss() {
        // First cycle: establish a peak of 10,000 (5,000 free + 0.1 BTC @ 50,000)
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("USDT", BigDecimal.valueOf(5000), BigDecimal.ZERO)));
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("user1", "cred1", "OPEN"))
            .thenReturn(List.of(openPosition("BTCUSDT", 0.1)));
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BigDecimal.valueOf(50000));
        service.checkDrawdown(credential, adapter);
        assertThat(profile.getPeakEquityQuote()).isEqualByComparingTo("10000");
        assertThat(profile.isTradingHalted()).isFalse();

        // Second cycle: same free balance, but the OPEN position (still open, not closed) has
        // dropped hard — this is exactly the case the old realized-only calculation would have
        // missed entirely, since nothing was ever sold.
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BigDecimal.valueOf(10000));
        // new equity = 5000 free + 0.1*10000 = 6000 -> drawdown from 10000 peak = 40%, over the 10% limit
        service.checkDrawdown(credential, adapter);

        assertThat(profile.isTradingHalted()).isTrue();
        assertThat(profile.getHaltReason()).contains("Max drawdown reached");
    }

    @Test
    @DisplayName("checkDrawdown: a pricing failure for any open position HALTS trading and raises a CRITICAL incident, rather than silently skipping the check -- the actual review fix (\"Drawdown can silently stop checking when pricing fails\")")
    void checkDrawdown_pricingFailure_haltsAndRaisesIncident() {
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("USDT", BigDecimal.valueOf(1000), BigDecimal.ZERO)));
        when(positionRepo.findByUserIdAndCredentialIdAndStatus("user1", "cred1", "OPEN"))
            .thenReturn(List.of(openPosition("BTCUSDT", 0.1)));
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.TESTNET)).thenThrow(new RuntimeException("price feed down"));

        service.checkDrawdown(credential, adapter);

        // Must NOT have computed or recorded a partial equity figure from just the free balance.
        assertThat(profile.getPeakEquityQuote()).isNull();
        // The actual fix: inability to price is a risk event, not a free pass.
        assertThat(profile.isTradingHalted()).isTrue();
        assertThat(profile.getHaltReason()).contains("BTCUSDT");
        verify(incidentService).raiseCritical(eq("user1"), any(), any(), any(), eq("BTCUSDT"), eq("DRAWDOWN_PRICING_UNAVAILABLE"), any());
    }

    @Test
    @DisplayName("checkDrawdown: disabled (maxDrawdownPercent <= 0) does nothing")
    void checkDrawdown_disabledDoesNothing() {
        profile.setMaxDrawdownPercent(0);

        service.checkDrawdown(credential, adapter);

        assertThat(profile.getPeakEquityQuote()).isNull();
    }

    // ── handleOcoAllDoneWithNoFill ("P0 #1") ──────────────────────────────────────

    private Position ocoPosition(double qty, String ocoId, String entryOrderId) {
        Position p = new Position();
        p.setSymbol("BTCUSDT");
        p.setCredentialId("cred1");
        p.setQuantity(BigDecimal.valueOf(qty));
        p.setStatus("OPEN");
        p.setOcoOrderListId(ocoId);
        p.setEntryOrderId(entryOrderId);
        return p;
    }

    @Test
    @DisplayName("handleOcoAllDoneWithNoFill: still genuinely held (balance confirms it) — must NOT be marked closed, must attempt re-protection")
    void handleOcoAllDoneWithNoFill_stillHeld_reprotectsNotCloses() {
        Position position = ocoPosition(1.0, "old-oco-123", "entry-1");
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        when(adapter.getBalance("key", "secret", BrokerMode.TESTNET)).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        when(omsOrderRepo.findByCredentialIdAndSymbolAndBrokerOrderId("cred1", "BTCUSDT", "entry-1")).thenReturn(Optional.empty()); // no recorded SL/TP to reprotect with

        service.handleOcoAllDoneWithNoFill(credential, adapter, "key", "secret", position, 1L);

        // The exact P0 #1 scenario: balance (1.0 BTC) still covers the position (1.0 BTC) —
        // nothing was actually sold, so this must never become CLOSED_UNVERIFIED_PNL.
        assertThat(position.getStatus()).isEqualTo("OPEN");
        assertThat(position.getOcoOrderListId()).isNull(); // old OCO consumed either way, needs fresh protection
        verify(slotReservationService, org.mockito.Mockito.never()).release(any());
        // reprotectRemainder ran and, finding no recorded SL/TP, fell through to emergency flatten
        // rather than leaving the position naked with a dangling reference to a consumed OCO.
        verify(omsOrderRepo).findByCredentialIdAndSymbolAndBrokerOrderId("cred1", "BTCUSDT", "entry-1");
        verify(positionSafetyService).emergencyFlatten(any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq(position), any());
    }

    @Test
    @DisplayName("handleOcoAllDoneWithNoFill: balance confirms the position is genuinely gone — closes as unverified, releases the slot")
    void handleOcoAllDoneWithNoFill_confirmedGone_closesUnverified() {
        Position position = ocoPosition(1.0, "old-oco-123", "entry-1");
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        when(adapter.getBalance("key", "secret", BrokerMode.TESTNET)).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(0.001), BigDecimal.ZERO))); // negligible dust, not the real position

        service.handleOcoAllDoneWithNoFill(credential, adapter, "key", "secret", position, 1L);

        assertThat(position.getStatus()).isEqualTo("CLOSED_UNVERIFIED_PNL");
        assertThat(position.getQuantity()).isEqualByComparingTo("0");
        assertThat(position.getClosedQuantity()).isEqualByComparingTo("1.0");
        // Review finding ("Position slot reservations still don't have ownership IDs" --
        // external review, twenty-eighth pass, P0, full context in
        // PositionSlotReservationRecord's own class javadoc): this fixture's own position
        // predates slotReservationId (never set by ocoPosition()'s own helper), so the real
        // fallback path (releaseByKey) fires here, not the new id-based release.
        verify(slotReservationService).releaseByKey(credential.getId());
        verify(positionSafetyService, org.mockito.Mockito.never()).emergencyFlatten(any(), any(), any(), any(), any(), any());
    }

    /**
     * P1-15 fix ("Risk accounting ignores unverified closes"): confirmed real -- before this
     * fix, a CLOSED_UNVERIFIED_PNL close like this one never called riskEngine.recordRealizedLoss
     * at all, meaning a real loss on this position was invisible to the daily loss total and
     * loss streak. With an entry price of 100, a stop-loss trigger of 90 recorded on this
     * position's own OCO_EXIT OMS order, and quantity 1.0, the worst-case estimate is
     * (90-100)*1.0 = -10, i.e. a loss of 10.
     */
    @Test
    @DisplayName("handleOcoAllDoneWithNoFill: balance confirms genuinely gone -- records a worst-case loss against the risk engine using the position's own OCO_EXIT stop-loss price, since no real exit price is known")
    void handleOcoAllDoneWithNoFill_confirmedGone_recordsWorstCaseLossAgainstRiskEngine() {
        Position position = ocoPosition(1.0, "old-oco-123", "entry-1");
        position.setId("pos1");
        position.setAvgEntryPrice(BigDecimal.valueOf(100));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        when(adapter.getBalance("key", "secret", BrokerMode.TESTNET)).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(0.001), BigDecimal.ZERO)));

        Order ocoExitOrder = new Order();
        ocoExitOrder.setOrderRole("OCO_EXIT");
        ocoExitOrder.setStopLossTriggerPrice(BigDecimal.valueOf(90));
        when(omsOrderRepo.findByPositionIdAndOrderRoleOrderByCreatedAtDesc("pos1", "OCO_EXIT")).thenReturn(List.of(ocoExitOrder));

        service.handleOcoAllDoneWithNoFill(credential, adapter, "key", "secret", position, 1L);

        assertThat(position.getStatus()).isEqualTo("CLOSED_UNVERIFIED_PNL");
        // Position's own authoritative P&L record is deliberately left unset -- this is a
        // risk-accounting safeguard, not a substitute for real reconciliation.
        assertThat(position.getRealizedPnlQuote()).isNull();
        org.mockito.ArgumentCaptor<BigDecimal> lossCaptor = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
        verify(riskEngine).recordRealizedLoss(eq(profile), lossCaptor.capture());
        assertThat(lossCaptor.getValue()).isEqualByComparingTo("10");
        verify(riskEngine).recordAutoTradeOutcome(eq(profile), any(), eq(true));
    }

    /**
     * P1-15 fix, same context: when no OCO_EXIT order exists for this position at all (this
     * fixture's position has no such record), the fix must NOT fabricate a number -- it simply
     * records nothing, exactly like every other genuinely-unknown case in this codebase.
     */
    @Test
    @DisplayName("handleOcoAllDoneWithNoFill: no OCO_EXIT order exists to estimate from -- records nothing against the risk engine rather than guessing")
    void handleOcoAllDoneWithNoFill_confirmedGone_noOcoExitOrder_recordsNothing() {
        Position position = ocoPosition(1.0, "old-oco-123", "entry-1");
        position.setId("pos1");
        position.setAvgEntryPrice(BigDecimal.valueOf(100));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        when(adapter.getBalance("key", "secret", BrokerMode.TESTNET)).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(0.001), BigDecimal.ZERO)));
        when(omsOrderRepo.findByPositionIdAndOrderRoleOrderByCreatedAtDesc("pos1", "OCO_EXIT")).thenReturn(List.of());

        service.handleOcoAllDoneWithNoFill(credential, adapter, "key", "secret", position, 1L);

        assertThat(position.getStatus()).isEqualTo("CLOSED_UNVERIFIED_PNL");
        verify(riskEngine, org.mockito.Mockito.never()).recordRealizedLoss(any(), any());
    }

    @Test
    @DisplayName("handleOcoAllDoneWithNoFill: locked balance counts toward total holdings, not just free — the 'reverse problem' fix from P0 #2")
    void handleOcoAllDoneWithNoFill_lockedBalanceCountsToo() {
        Position position = ocoPosition(1.0, "old-oco-123", "entry-1");
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        // Free alone (0.5) would wrongly conclude the position is gone (0.5 < 0.98) — but 0.5
        // locked in some other open order means the account actually holds 1.0 total, which
        // correctly covers the position. Before this fix, this exact case would have wrongly
        // closed a position that's genuinely still held.
        when(adapter.getBalance("key", "secret", BrokerMode.TESTNET)).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.5))));
        when(omsOrderRepo.findByCredentialIdAndSymbolAndBrokerOrderId("cred1", "BTCUSDT", "entry-1")).thenReturn(Optional.empty());

        service.handleOcoAllDoneWithNoFill(credential, adapter, "key", "secret", position, 1L);

        assertThat(position.getStatus()).isEqualTo("OPEN");
        verify(slotReservationService, org.mockito.Mockito.never()).release(any());
    }

    @Test
    @DisplayName("handleOcoAllDoneWithNoFill: another OPEN position on the same credential and symbol raises the expected minimum balance — the exact P0 #2 'own positions compete for the same pool' fix")
    void handleOcoAllDoneWithNoFill_otherOwnPositionSameSymbol_raisesExpectedMinimum() {
        Position position = ocoPosition(0.5, "old-oco-123", "entry-1");
        position.setId("pos-checking-this-one");
        Position siblingPosition = ocoPosition(0.5, "some-other-oco", "entry-2");
        siblingPosition.setId("pos-sibling");
        when(positionRepo.findByCredentialIdAndStatus(credential.getId(), "OPEN")).thenReturn(List.of(position, siblingPosition));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        // Only 0.5 BTC total — covers THIS position (0.5) alone, but NOT this position plus its
        // sibling's 0.5 (expected minimum 1.0). Before this fix, the check only ever compared
        // against THIS position's own 0.5 and would have wrongly concluded "still fully held"
        // even though the sibling's share is nowhere to be found.
        when(adapter.getBalance("key", "secret", BrokerMode.TESTNET)).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(0.5), BigDecimal.ZERO)));

        service.handleOcoAllDoneWithNoFill(credential, adapter, "key", "secret", position, 1L);

        // 0.5 total < 0.98*(0.5+0.5)=0.98 -> correctly detected as NOT fully covered anymore.
        assertThat(position.getStatus()).isEqualTo("CLOSED_UNVERIFIED_PNL");
    }

    // Review's own explicitly requested test #3 ("Unrelated wallet balance... do NOT assume the
    // position still exists") is deliberately NOT claimed as fixed here. That scenario — a
    // user's own UNRELATED holdings of the same asset, not another TradeVision position — cannot
    // be solved by any balance-comparison formula, no matter how it's computed; spot balances
    // are fungible, and there's no exchange-side way to tag which coins belong to which bot
    // position. See expectedMinimumBalanceForPosition's own javadoc for the full, honest
    // disclosure — a per-position asset ledger is the only real fix, and is out of scope here.

    @Test
    @DisplayName("handleOcoAllDoneWithNoFill: unknown base asset — refuses to guess, doesn't touch balance or position at all")
    void handleOcoAllDoneWithNoFill_unknownBaseAsset_doesNothing() {
        Position position = ocoPosition(1.0, "old-oco-123", "entry-1");
        var rulesNoBaseAsset = new com.tradevision.service.broker.dto.SymbolRules(
            "BTCUSDT", null, "USDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(rulesNoBaseAsset);

        service.handleOcoAllDoneWithNoFill(credential, adapter, "key", "secret", position, 1L);

        verify(adapter, org.mockito.Mockito.never()).getBalance(any(), any(), any());
        verify(positionRepo, org.mockito.Mockito.never()).save(any());
        assertThat(position.getStatus()).isEqualTo("OPEN"); // untouched
    }

    // ── reconcileCredential concurrency ("P0 #2") ─────────────────────────────

    /**
     * Review finding ("P0 #2" — "reconciliation is not concurrency-safe"): this had been
     * declared "genuinely hard to unit-test — needs real thread concurrency" and left as a gap.
     * That was too quick to give up — a ReentrantLock-based guard IS testable with real threads,
     * no mocked timing required, no database needed (the lock itself is pure in-JVM state).
     * Uses CountDownLatch to prove thread A has genuinely acquired the lock (not just "probably
     * started by now") before the test thread attempts its own concurrent call — no sleep-based
     * timing, no flakiness from scheduler variance.
     */
    @Test
    @DisplayName("reconcileCredential: a second concurrent call for the SAME credential is skipped entirely while the first is still running — real threads, not mocked timing")
    void reconcileCredential_concurrentCallsForSameCredentialAreSerialized() throws Exception {
        when(adapter.getType()).thenReturn(BrokerType.BINANCE);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adapters", List.of(adapter));

        CountDownLatch enteredCriticalSection = new CountDownLatch(1);
        CountDownLatch releaseFirstThread = new CountDownLatch(1);

        // The first thing reconcileEntryOrders (itself the first thing reconcileCredentialLocked
        // calls) does — the natural hook point to detect "is a reconciliation genuinely running
        // right now". Everything downstream of this (reconcileOpenPositions, checkDrawdown) is
        // deliberately left unstubbed — Mockito's defaults (empty list / Optional.empty()) make
        // both return immediately without any further setup needed.
        when(omsOrderRepo.findByCredentialIdAndStatusInOrderByCreatedAtAsc(any(), any())).thenAnswer(invocation -> {
            enteredCriticalSection.countDown();
            assertThat(releaseFirstThread.await(5, TimeUnit.SECONDS)).isTrue();
            return List.<Order>of();
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> firstCall = executor.submit(() -> service.reconcileCredential(credential));

            // Block until thread A has PROVABLY entered the locked section (the countDown only
            // fires from inside the stub, which only runs after lock.lock() already succeeded)
            // — not "probably enough time has passed", an actual guarantee.
            assertThat(enteredCriticalSection.await(5, TimeUnit.SECONDS)).isTrue();

            // While thread A holds the lock, this second call for the SAME credential must be
            // skipped entirely — tryLock() fails immediately, reconcileCredentialLocked (and
            // therefore reconcileEntryOrders) is never entered at all. This call itself must
            // return immediately (not block), since a blocked second caller here would deadlock
            // this test against releaseFirstThread below.
            service.reconcileCredential(credential);

            // Confirms the second call was genuinely skipped, not merely "also ran but harmlessly" —
            // the guarded work happened exactly once, from thread A alone.
            verify(omsOrderRepo, times(1)).findByCredentialIdAndStatusInOrderByCreatedAtAsc(any(), any());

            releaseFirstThread.countDown();
            firstCall.get(5, TimeUnit.SECONDS); // propagates any exception thread A hit, fails the test if so
        } finally {
            executor.shutdown();
        }
    }

    /**
     * P1-13 fix ("reconcileEntryOrders polls OCO list IDs as order IDs"): confirmed real --
     * findByCredentialIdAndStatusInOrderByCreatedAtAsc's own query has no role filter, and an
     * OCO_EXIT order's OMS record stores the broker's own orderListId in the SAME brokerOrderId
     * field once recordOcoPlacementResult() acknowledges it (see that method's own javadoc), so
     * it used to be polled here right alongside real ENTRY orders via
     * adapter.getOrderStatus(..., orderId=<orderListId>) -- an id that endpoint was never meant
     * to receive. Two records with the SAME ACKNOWLEDGED status come back from the stubbed
     * query: a real ENTRY order and an OCO_EXIT order sharing a broker id that, if ever queried
     * through getOrderStatus, would prove the bug reproduced. Only the ENTRY order's id may ever
     * reach that call.
     */
    @Test
    @DisplayName("reconcileEntryOrders: an OCO_EXIT order's OMS record is never polled via getOrderStatus (its brokerOrderId is a list id, not an order id) -- only ENTRY orders are")
    void reconcileEntryOrders_ocoExitOrderNeverPolledAsIfItWereAnEntryOrder() {
        var entryOrder = new Order();
        entryOrder.setId("entry-order-1");
        entryOrder.setUserId("user1");
        entryOrder.setCredentialId("cred1");
        entryOrder.setSymbol("BTCUSDT");
        entryOrder.setBrokerOrderId("real-entry-order-id");
        entryOrder.setSide("BUY");
        entryOrder.setStatus(OrderStatus.ACKNOWLEDGED);
        entryOrder.setOrderRole("ENTRY");

        var ocoExitOrder = new Order();
        ocoExitOrder.setId("oco-order-1");
        ocoExitOrder.setUserId("user1");
        ocoExitOrder.setCredentialId("cred1");
        ocoExitOrder.setSymbol("BTCUSDT");
        // Exactly what recordOcoPlacementResult() sets on an OCO's own OMS record -- the
        // broker's orderListId, not a real order id.
        ocoExitOrder.setBrokerOrderId("shared-list-id-999");
        ocoExitOrder.setSide("SELL");
        ocoExitOrder.setStatus(OrderStatus.ACKNOWLEDGED);
        ocoExitOrder.setOrderRole("OCO_EXIT");

        when(omsOrderRepo.findByCredentialIdAndStatusInOrderByCreatedAtAsc(any(), any()))
            .thenReturn(List.of(entryOrder, ocoExitOrder));
        when(adapter.getOrderStatus(any(), any(), any(), any(), eq("real-entry-order-id"))).thenReturn(
            new com.tradevision.service.broker.dto.OrderStatusInfo("NEW", BigDecimal.ZERO, null, "{}"));

        service.reconcileCredential(credential);

        // The real bug's exact fingerprint: this call must never happen for the OCO's own
        // shared-list id, under any status mapping.
        verify(adapter, never()).getOrderStatus(any(), any(), any(), any(), eq("shared-list-id-999"));
        // Confirms the ENTRY order was still genuinely reconciled -- this is a role filter, not
        // an accidental "nothing gets polled at all" regression.
        verify(adapter, times(1)).getOrderStatus(any(), any(), any(), any(), eq("real-entry-order-id"));
    }

    // ── Startup state machine ("P1 — startup trading should remain disabled") ──

    @Test
    @DisplayName("reconcileOnStartup: zero credentials to reconcile — zero failures — marks TRADING_ENABLED")
    void reconcileOnStartup_noCredentials_marksTradingEnabled() {
        // doReconcile() builds adapterMap from adapters.stream() unconditionally, before the
        // credential loop even starts — needs a non-null list here even though it's never
        // actually consulted with zero credentials, or this NPEs before reaching that loop.
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adapters", List.of());
        when(credentialRepo.findAll()).thenReturn(List.of());

        service.reconcileOnStartup();

        verify(startupState).markReconciling();
        verify(startupState).markComplete(true);
    }

    @Test
    @DisplayName("reconcileOnStartup: a credential's reconciliation throws — marks RECONCILIATION_FAILED (false), the exact P1 fix — nothing about this pass silently succeeds")
    void reconcileOnStartup_credentialReconciliationFails_marksFailed() {
        credential.setActive(true);
        when(credentialRepo.findAll()).thenReturn(List.of(credential));
        when(adapter.getType()).thenReturn(BrokerType.BINANCE);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adapters", List.of(adapter));
        // Forces an exception inside reconcileCredentialLocked's try block, caught by its own
        // catch — this is exactly what increments the failure counter reconcileOnStartup reads.
        when(omsOrderRepo.findByCredentialIdAndStatusInOrderByCreatedAtAsc(any(), any()))
            .thenThrow(new RuntimeException("simulated broker connectivity failure"));

        service.reconcileOnStartup();

        verify(startupState).markReconciling();
        verify(startupState).markComplete(false);
    }

    /**
     * P1-17 fix ("Emergency revoke / credential deactivation stops all monitoring of live
     * positions"): confirmed real -- doReconcile()'s own per-credential loop used to skip EVERY
     * inactive credential unconditionally, so a deactivated credential with a real, still-open
     * position on the exchange stopped being reconciled at all the moment it was deactivated.
     */
    @Test
    @DisplayName("reconcileOnStartup (doReconcile): an INACTIVE credential with an open position IS still reconciled -- deactivation must not abandon monitoring of what it already has open")
    void doReconcile_inactiveCredentialWithOpenPosition_stillReconciled() {
        credential.setActive(false);
        when(credentialRepo.findAll()).thenReturn(List.of(credential));
        when(adapter.getType()).thenReturn(BrokerType.BINANCE);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adapters", List.of(adapter));
        when(positionRepo.existsByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(true);

        service.reconcileOnStartup();

        // The real, observable proof reconciliation actually ran for this credential: its own
        // first mutation-capable step was reached (same fingerprint the existing
        // reconcileOnStartup_credentialReconciliationFails_marksFailed test above uses).
        verify(omsOrderRepo).findByCredentialIdAndStatusInOrderByCreatedAtAsc(eq("cred1"), any());
    }

    @Test
    @DisplayName("reconcileOnStartup (doReconcile): an INACTIVE credential with NO open positions is still skipped -- this fix must not make every deactivated credential reconcile forever")
    void doReconcile_inactiveCredentialWithNoOpenPositions_stillSkipped() {
        credential.setActive(false);
        when(credentialRepo.findAll()).thenReturn(List.of(credential));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adapters", List.of());
        when(positionRepo.existsByCredentialIdAndStatusIn(eq("cred1"), any())).thenReturn(false);

        service.reconcileOnStartup();

        verify(omsOrderRepo, org.mockito.Mockito.never()).findByCredentialIdAndStatusInOrderByCreatedAtAsc(any(), any());
        // Zero credentials genuinely needed reconciling -- still a clean, healthy startup pass.
        verify(startupState).markComplete(true);
    }

    // ── Real P&L accounting ("P0 #3" — division-by-zero bug) ────────────────────

    @Test
    @DisplayName("writeRealOutcomeBackToSignal: computes a correct, finite pnlPct using the CLOSED quantity, never position.getQuantity() (which is always zero by the time this runs) — the exact P0 #3 fix")
    void writeRealOutcomeBackToSignal_computesCorrectPnlPct() {
        Position position = new Position();
        position.setSignalId("sig1");
        position.setQuantity(BigDecimal.ZERO); // exactly matching real conditions: already zeroed before this runs

        TradeCallRecord call = new TradeCallRecord();
        call.setId("sig1");
        call.setEntryPrice(BigDecimal.valueOf(100.0));
        call.setCalledAt(java.time.LocalDateTime.now().minusMinutes(30));
        when(callRepo.findById("sig1")).thenReturn(Optional.of(call));
        when(callRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        // BUY 1.0 @ 100, exits with $10 profit -> 10% gain on a $100 notional (1.0 * 100).
        service.writeRealOutcomeBackToSignal(position, BigDecimal.valueOf(110), "TAKE_PROFIT",
            BigDecimal.valueOf(10), BigDecimal.valueOf(1.0));

        ArgumentCaptor<TradeCallRecord> captor = ArgumentCaptor.forClass(TradeCallRecord.class);
        verify(callRepo).save(captor.capture());
        Double pnlPct = captor.getValue().getOutcome().getPnlPct();
        assertThat(pnlPct).isNotNull();
        assertThat(Double.isFinite(pnlPct)).isTrue();
        assertThat(pnlPct).isEqualTo(10.0);
    }

    @Test
    @DisplayName("writeRealOutcomeBackToSignal: a null/zero closed quantity never divides by zero — pnlPct is simply left unset, not Infinity/NaN")
    void writeRealOutcomeBackToSignal_zeroClosedQuantity_neverProducesInfinity() {
        Position position = new Position();
        position.setSignalId("sig1");
        position.setQuantity(BigDecimal.ZERO);

        TradeCallRecord call = new TradeCallRecord();
        call.setId("sig1");
        call.setEntryPrice(BigDecimal.valueOf(100.0));
        when(callRepo.findById("sig1")).thenReturn(Optional.of(call));
        when(callRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        // Simulates the exact bug scenario: whatever quantity gets passed in is zero (as it
        // would have been if this method still read position.getQuantity() internally).
        service.writeRealOutcomeBackToSignal(position, BigDecimal.valueOf(110), "TAKE_PROFIT",
            BigDecimal.valueOf(10), BigDecimal.ZERO);

        ArgumentCaptor<TradeCallRecord> captor = ArgumentCaptor.forClass(TradeCallRecord.class);
        verify(callRepo).save(captor.capture());
        Double pnlPct = captor.getValue().getOutcome().getPnlPct();
        // Before the fix, this would have been Infinity (10 / 0 * 100). Now: simply never set.
        assertThat(pnlPct).isNull();
    }

    @Test
    @DisplayName("writeRealOutcomeBackToSignal: exit price and close reason are always recorded, even when pnlPct can't be computed")
    void writeRealOutcomeBackToSignal_alwaysRecordsExitPriceAndReason() {
        Position position = new Position();
        position.setSignalId("sig1");
        position.setQuantity(BigDecimal.ZERO);

        TradeCallRecord call = new TradeCallRecord();
        call.setId("sig1");
        call.setEntryPrice(BigDecimal.ZERO); // entryPrice <= 0 also skips the pnlPct branch entirely
        when(callRepo.findById("sig1")).thenReturn(Optional.of(call));
        when(callRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        service.writeRealOutcomeBackToSignal(position, BigDecimal.valueOf(110), "STOP_LOSS",
            BigDecimal.valueOf(-5), BigDecimal.valueOf(1.0));

        ArgumentCaptor<TradeCallRecord> captor = ArgumentCaptor.forClass(TradeCallRecord.class);
        verify(callRepo).save(captor.capture());
        var outcome = captor.getValue().getOutcome();
        assertThat(outcome.getExitPrice()).isEqualTo(110.0);
        assertThat(outcome.getResult()).isEqualTo("STOP_LOSS");
        assertThat(outcome.getPnlPct()).isNull();
    }

    // ── ML weight learning only from real, verified fills (P1 #5) ──────────────────

    @Test
    @DisplayName("P1-5: writeRealOutcomeBackToSignal records a WIN (\"HIT_T1\") to MLWeightService when this real, broker-confirmed close had positive pnl -- the ONLY place in the codebase that may now feed the global ML weights")
    void writeRealOutcomeBackToSignal_positivePnl_recordsMlWin() {
        Position position = new Position();
        position.setSignalId("sig1");
        position.setQuantity(BigDecimal.ZERO);

        TradeCallRecord call = new TradeCallRecord();
        call.setId("sig1");
        call.setMarket("CRYPTO");
        call.setSymbol("BTCUSDT");
        call.setDirection("LONG");
        call.setEntryPrice(BigDecimal.valueOf(100.0));
        var features = new TradeFeatures();
        features.setRsi(35.0); features.setMacdBull(true); features.setVolumeRatio(2.0);
        call.setFeatures(features);
        when(callRepo.findById("sig1")).thenReturn(Optional.of(call));
        when(callRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        service.writeRealOutcomeBackToSignal(position, BigDecimal.valueOf(110), "TAKE_PROFIT",
            BigDecimal.valueOf(10), BigDecimal.valueOf(1.0));

        verify(mlWeightService).recordOutcome(eq("CRYPTO"), eq("BTCUSDT"), eq("HIT_T1"), eq(true),
            eq(35.0), eq(true), any(), eq(2.0));
    }

    @Test
    @DisplayName("P1-5: writeRealOutcomeBackToSignal records a LOSS (\"HIT_SL\") to MLWeightService when this real close had negative pnl, regardless of the close reason string")
    void writeRealOutcomeBackToSignal_negativePnl_recordsMlLoss() {
        Position position = new Position();
        position.setSignalId("sig1");
        position.setQuantity(BigDecimal.ZERO);

        TradeCallRecord call = new TradeCallRecord();
        call.setId("sig1");
        call.setMarket("CRYPTO");
        call.setSymbol("ETHUSDT");
        call.setDirection("SHORT");
        call.setEntryPrice(BigDecimal.valueOf(100.0));
        var features = new TradeFeatures();
        features.setRsi(65.0); features.setMacdBull(false); features.setVolumeRatio(1.2);
        call.setFeatures(features);
        when(callRepo.findById("sig1")).thenReturn(Optional.of(call));
        when(callRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);

        // A MAX_HOLD_TIME exit can still be a real loss -- the close reason string is not what
        // decides win/loss, the real, verified pnl sign is.
        service.writeRealOutcomeBackToSignal(position, BigDecimal.valueOf(105), "MAX_HOLD_TIME",
            BigDecimal.valueOf(-5), BigDecimal.valueOf(1.0));

        verify(mlWeightService).recordOutcome(eq("CRYPTO"), eq("ETHUSDT"), eq("HIT_SL"), eq(false),
            eq(65.0), eq(false), any(), eq(1.2));
    }

    @Test
    @DisplayName("P1-5: writeRealOutcomeBackToSignal never lets an ML recording failure prevent the real outcome write, which already succeeded and matters far more than this additive learning signal")
    void writeRealOutcomeBackToSignal_mlRecordingThrows_doesNotPropagate() {
        Position position = new Position();
        position.setSignalId("sig1");
        position.setQuantity(BigDecimal.ZERO);

        TradeCallRecord call = new TradeCallRecord();
        call.setId("sig1");
        call.setMarket("CRYPTO");
        call.setSymbol("BTCUSDT");
        call.setDirection("LONG");
        call.setEntryPrice(BigDecimal.valueOf(100.0));
        when(callRepo.findById("sig1")).thenReturn(Optional.of(call));
        when(callRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);
        org.mockito.Mockito.doThrow(new RuntimeException("simulated ML weight write failure"))
            .when(mlWeightService).recordOutcome(any(), any(), any(), anyBoolean(), any(Double.class), anyBoolean(), any(), any(Double.class));

        // Must not throw, even though recordOutcome does.
        service.writeRealOutcomeBackToSignal(position, BigDecimal.valueOf(110), "TAKE_PROFIT",
            BigDecimal.valueOf(10), BigDecimal.valueOf(1.0));

        verify(callRepo).save(any());
    }

    // ── Base-asset commission through reconciliation ("review's own requested test #5") ────

    @Test
    @DisplayName("syncPositionQuantityIfMismatched: base-asset commission is deducted when the reconciliation path recomputes position quantity — BUY 1 BTC, 0.001 BTC commission, expected 0.999")
    void syncPositionQuantityIfMismatched_deductsBaseAssetCommission() {
        Position position = new Position();
        position.setId("pos1");
        position.setUserId("user1");
        position.setCredentialId("cred1");
        position.setSymbol("BTCUSDT");
        position.setStatus("OPEN");
        position.setQuantity(BigDecimal.valueOf(0.5)); // stale/wrong — what triggers the correction
        position.setEntryOrderId("entry-1");

        Order order = new Order();
        order.setCredentialId("cred1");
        order.setSymbol("BTCUSDT");
        order.setBrokerOrderId("entry-1");
        order.setStopLossTriggerPrice(BigDecimal.valueOf(95));
        order.setTakeProfitPrice(BigDecimal.valueOf(110));

        when(positionRepo.findByCredentialIdAndSymbolAndEntryOrderId("cred1", "BTCUSDT", "entry-1")).thenReturn(Optional.of(position));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);

        List<Fill> fills = List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.001), "BTC"));
        when(adapter.getFillsForOrder("key", "secret", BrokerMode.TESTNET, "BTCUSDT", "entry-1")).thenReturn(fills);
        // Gross confirmedExecutedQty (1.0) net of 0.001 BTC commission = 0.999 — matching the
        // review's exact scenario (BUY 1 BTC, 0.001 BTC commission, expected position = 0.999).
        when(positionSafetyService.computeNetQuantity(eq(BigDecimal.valueOf(1.0)), eq(fills), eq("BTC")))
            .thenReturn(new PositionSafetyService.FillAccountingResult(BigDecimal.valueOf(0.999), BigDecimal.valueOf(0.001)));
        when(adapter.cancelOco(any(), any(), any(), any(), any())).thenReturn(new OcoOrderResult(true, null, "{}", null));
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "oco-new", "{}", null));

        service.syncPositionQuantityIfMismatched(credential, adapter, "key", "secret", order, BigDecimal.valueOf(1.0), 1L);

        assertThat(position.getQuantity()).isEqualByComparingTo("0.999"); // net of commission, not the gross 1.0
    }

    // ── Fill ledger completion ("#6 — Complete Fill Ledger") ─────────────────

    @Test
    @DisplayName("reconcileOcoProtectedPosition: a full take-profit close records the fill to the ledger using the broker's own order id, real quote asset, and real quantity/price")
    void ocoFullTakeProfitClose_recordsToFillLedger() {
        Position position = openPosition("BTCUSDT", 1.0);
        position.setUserId("user1");
        position.setCredentialId("cred1");
        position.setAvgEntryPrice(BigDecimal.valueOf(100));
        position.setOcoOrderListId("oco1");

        var filledLeg = new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("tp1", "SELL", "TAKE_PROFIT", "FILLED", BigDecimal.valueOf(110), BigDecimal.valueOf(1.0), BigDecimal.valueOf(1.0));
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco1"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "ALL_DONE", List.of(filledLeg), "{}"));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        List<Fill> exitFills = List.of(new Fill(BigDecimal.valueOf(110), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.05), "USDT"));
        when(adapter.getFillsForOrder(any(), any(), any(), eq("BTCUSDT"), eq("tp1"))).thenReturn(exitFills);

        service.reconcileOcoProtectedPosition(credential, adapter, "key", "secret", position, java.util.Optional.empty(), 1L);

        verify(fillLedgerService).recordFills(eq("tp1"), any(), eq("user1"), eq("cred1"), eq("BTCUSDT"), eq("SELL"),
            eq("USDT"), eq(exitFills), argThat(q -> q.compareTo(BigDecimal.ONE) == 0), argThat(p -> p.compareTo(BigDecimal.valueOf(110)) == 0));
        assertThat(position.getStatus()).isEqualTo("CLOSED");
    }

    // ── P1-15: unverified closes still contribute to risk accounting ──────────

    /**
     * P1-15 fix: a leg reported FILLED but with no resolvable price used to close the position
     * as CLOSED_UNVERIFIED_PNL and record NOTHING against the risk engine. With avgEntryPrice
     * 100 and a stop-loss trigger of 85 recorded on this position's own OCO_EXIT OMS order
     * (looked up since no real exit price is available), the worst-case estimate is
     * (85-100)*1.0 = -15.
     */
    @Test
    @DisplayName("reconcileOcoProtectedPosition: OCO leg FILLED but price unresolved -- records a worst-case loss against the risk engine using the position's own OCO_EXIT stop-loss price")
    void ocoLegFilledPriceUnresolved_recordsWorstCaseLossAgainstRiskEngine() {
        Position position = openPosition("BTCUSDT", 1.0);
        position.setId("pos1");
        position.setUserId("user1");
        position.setCredentialId("cred1");
        position.setAvgEntryPrice(BigDecimal.valueOf(100));
        position.setOcoOrderListId("oco1");

        // price is null -- exactly the "reported FILLED but its price could not be resolved" case.
        var filledLeg = new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("tp1", "SELL", "TAKE_PROFIT", "FILLED", null, BigDecimal.valueOf(1.0), BigDecimal.valueOf(1.0));
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco1"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "ALL_DONE", List.of(filledLeg), "{}"));

        Order ocoExitOrder = new Order();
        ocoExitOrder.setOrderRole("OCO_EXIT");
        ocoExitOrder.setStopLossTriggerPrice(BigDecimal.valueOf(85));
        when(omsOrderRepo.findByPositionIdAndOrderRoleOrderByCreatedAtDesc("pos1", "OCO_EXIT")).thenReturn(List.of(ocoExitOrder));

        service.reconcileOcoProtectedPosition(credential, adapter, "key", "secret", position, java.util.Optional.of(profile), 1L);

        assertThat(position.getStatus()).isEqualTo("CLOSED_UNVERIFIED_PNL");
        assertThat(position.getRealizedPnlQuote()).isNull(); // still not fabricated on the position's own record
        org.mockito.ArgumentCaptor<BigDecimal> lossCaptor = org.mockito.ArgumentCaptor.forClass(BigDecimal.class);
        verify(riskEngine).recordRealizedLoss(eq(profile), lossCaptor.capture());
        assertThat(lossCaptor.getValue()).isEqualByComparingTo("15");
    }

    /**
     * P1-15 fix: same gap, the sibling branch where price IS known but quantity is not -- the
     * real, known price (110, a gain) must be used directly rather than falling back to the
     * stop-loss estimate.
     */
    @Test
    @DisplayName("reconcileOcoProtectedPosition: OCO leg FILLED but quantity unresolved -- uses the real, known exit price directly (not the stop-loss fallback) to record the risk-engine impact")
    void ocoLegFilledQuantityUnresolved_usesKnownPriceNotStopLossFallback() {
        Position position = openPosition("BTCUSDT", 1.0);
        position.setId("pos1");
        position.setUserId("user1");
        position.setCredentialId("cred1");
        position.setAvgEntryPrice(BigDecimal.valueOf(100));
        position.setOcoOrderListId("oco1");

        // price IS known (110, a gain); executedQty is null -- the "quantity unresolved" case.
        var filledLeg = new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("tp1", "SELL", "TAKE_PROFIT", "FILLED", BigDecimal.valueOf(110), null, BigDecimal.valueOf(1.0));
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco1"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "ALL_DONE", List.of(filledLeg), "{}"));

        service.reconcileOcoProtectedPosition(credential, adapter, "key", "secret", position, java.util.Optional.of(profile), 1L);

        assertThat(position.getStatus()).isEqualTo("CLOSED_UNVERIFIED_PNL");
        // A gain, not a loss -- recordRealizedLoss must never be called, but the outcome is
        // still recorded (as a non-loss) for the loss-streak counter.
        verify(riskEngine, org.mockito.Mockito.never()).recordRealizedLoss(any(), any());
        verify(riskEngine).recordAutoTradeOutcome(eq(profile), any(), eq(false));
        // The stop-loss fallback lookup must never even be attempted when a real price is known.
        verify(omsOrderRepo, org.mockito.Mockito.never()).findByPositionIdAndOrderRoleOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("reconcileOcoProtectedPosition: the exit fill's own commission backfill is called after a full OCO close -- the actual review fix (\"Exit/OCO commission backfill is still NOT wired\"), confirmed real: entry fills already got this treatment, exit fills genuinely didn't")
    void ocoFullTakeProfitClose_backfillsExitCommission() {
        Position position = openPosition("BTCUSDT", 1.0);
        position.setUserId("user1");
        position.setCredentialId("cred1");
        position.setAvgEntryPrice(BigDecimal.valueOf(100));
        position.setOcoOrderListId("oco1");

        var filledLeg = new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("tp1", "SELL", "TAKE_PROFIT", "FILLED", BigDecimal.valueOf(110), BigDecimal.valueOf(1.0), BigDecimal.valueOf(1.0));
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco1"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "ALL_DONE", List.of(filledLeg), "{}"));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        // Commission in BNB, NOT the quote asset (USDT) -- the review's own exact example.
        List<Fill> exitFills = List.of(new Fill(BigDecimal.valueOf(110), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.001), "BNB"));
        when(adapter.getFillsForOrder(any(), any(), any(), eq("BTCUSDT"), eq("tp1"))).thenReturn(exitFills);
        var exitFillRecord = mock(com.tradevision.model.FillRecord.class);
        when(fillLedgerService.recordFills(eq("tp1"), any(), eq("user1"), eq("cred1"), eq("BTCUSDT"), eq("SELL"),
            eq("USDT"), eq(exitFills), any(), any())).thenReturn(List.of(exitFillRecord));

        service.reconcileOcoProtectedPosition(credential, adapter, "key", "secret", position, java.util.Optional.empty(), 1L);

        verify(fillLedgerService).backfillHistoricalCommissionConversion(exitFillRecord, "USDT", adapter, credential.getMode());
    }

    // ── P0-3: stop leg triggered but unfilled ─────────────────────────────────

    @Test
    @DisplayName("reconcileOcoProtectedPosition: OCO list EXECUTING (not ALL_DONE), stop leg still NEW, but current price has moved to/through the recorded stop trigger -- the stop has triggered and gotten stuck unfilled, so this cancels the OCO and emergency-flattens rather than treating it as still protected")
    void ocoStuckTriggeredStop_cancelsAndFlattens() {
        Position position = openPosition("BTCUSDT", 1.0);
        position.setUserId("user1");
        position.setCredentialId("cred1");
        position.setOcoOrderListId("oco1");
        position.setEntryOrderId("entry-1");

        Order entryOrder = new Order();
        entryOrder.setStopLossTriggerPrice(BigDecimal.valueOf(95));
        when(omsOrderRepo.findByCredentialIdAndSymbolAndBrokerOrderId("cred1", "BTCUSDT", "entry-1")).thenReturn(Optional.of(entryOrder));

        var stopLeg = new com.tradevision.service.broker.dto.OcoStatusInfo.Leg(
            "sl1", "SELL", "STOP_LOSS_LIMIT", "NEW", BigDecimal.valueOf(94.5), BigDecimal.ZERO, BigDecimal.valueOf(1.0));
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco1"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "EXECUTING", List.of(stopLeg), "{}"));
        // Market has moved to 94, below the recorded stop trigger of 95 -- the stop should have
        // triggered, but the leg above is still NEW, never FILLED.
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BigDecimal.valueOf(94));

        service.reconcileOcoProtectedPosition(credential, adapter, "key", "secret", position, java.util.Optional.empty(), 1L);

        verify(positionSafetyService).emergencyFlatten(eq(credential), eq(adapter), eq("key"), eq("secret"), eq(position), any());
        verify(credentialService).audit(any(), any(), any(), eq("OCO_STOP_TRIGGERED_UNFILLED"), any());
    }

    @Test
    @DisplayName("reconcileOcoProtectedPosition: OCO list EXECUTING, stop leg NEW, but current price is STILL above the recorded stop trigger -- this is the completely ordinary resting-OCO case, nothing has triggered, and nothing should happen")
    void ocoNormalExecuting_priceAboveTrigger_doesNothing() {
        Position position = openPosition("BTCUSDT", 1.0);
        position.setUserId("user1");
        position.setCredentialId("cred1");
        position.setOcoOrderListId("oco1");
        position.setEntryOrderId("entry-1");

        Order entryOrder = new Order();
        entryOrder.setStopLossTriggerPrice(BigDecimal.valueOf(95));
        when(omsOrderRepo.findByCredentialIdAndSymbolAndBrokerOrderId("cred1", "BTCUSDT", "entry-1")).thenReturn(Optional.of(entryOrder));

        var stopLeg = new com.tradevision.service.broker.dto.OcoStatusInfo.Leg(
            "sl1", "SELL", "STOP_LOSS_LIMIT", "NEW", BigDecimal.valueOf(94.5), BigDecimal.ZERO, BigDecimal.valueOf(1.0));
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco1"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "EXECUTING", List.of(stopLeg), "{}"));
        // Market is still at 100, comfortably above the stop trigger of 95 -- normal, healthy OCO.
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BigDecimal.valueOf(100));

        service.reconcileOcoProtectedPosition(credential, adapter, "key", "secret", position, java.util.Optional.empty(), 1L);

        verify(positionSafetyService, never()).emergencyFlatten(any(), any(), any(), any(), any(), any());
        assertThat(position.getStatus()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("reconcileOcoProtectedPosition: OCO list ALL_DONE with the stop leg reported PARTIALLY_FILLED (not FILLED) -- treated as a genuine partial exit, same as the existing FILLED-leg partial-exit path, instead of falling through to the no-fill/balance-check branch as if nothing sold")
    void ocoAllDone_stopLegPartiallyFilled_treatedAsPartialExit() {
        Position position = openPosition("BTCUSDT", 1.0);
        position.setUserId("user1");
        position.setCredentialId("cred1");
        position.setAvgEntryPrice(BigDecimal.valueOf(100));
        position.setOcoOrderListId("oco1");

        var partialStopLeg = new com.tradevision.service.broker.dto.OcoStatusInfo.Leg(
            "sl1", "SELL", "STOP_LOSS_LIMIT", "PARTIALLY_FILLED", BigDecimal.valueOf(94.5), BigDecimal.valueOf(0.4), BigDecimal.valueOf(1.0));
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco1"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "ALL_DONE", List.of(partialStopLeg), "{}"));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        List<Fill> exitFills = List.of(new Fill(BigDecimal.valueOf(94.5), BigDecimal.valueOf(0.4), BigDecimal.valueOf(0.02), "USDT"));
        when(adapter.getFillsForOrder(any(), any(), any(), eq("BTCUSDT"), eq("sl1"))).thenReturn(exitFills);

        service.reconcileOcoProtectedPosition(credential, adapter, "key", "secret", position, java.util.Optional.empty(), 1L);

        // A genuine partial exit was recorded (never routed into handleOcoAllDoneWithNoFill's
        // own balance-check/no-fill path, which never calls getFillsForOrder at all).
        verify(fillLedgerService).recordFills(eq("sl1"), any(), eq("user1"), eq("cred1"), eq("BTCUSDT"), eq("SELL"),
            eq("USDT"), eq(exitFills), argThat(q -> q.compareTo(BigDecimal.valueOf(0.4)) == 0), argThat(p -> p.compareTo(BigDecimal.valueOf(94.5)) == 0));
        verify(adapter, never()).getBalance(any(), any(), any());
    }

    @Test
    @DisplayName("reconcileOcoProtectedPosition: a partial exit ALSO records to the fill ledger — the review's own explicit 'PARTIAL EXIT' requirement, not just full closes")
    void ocoPartialExit_recordsToFillLedger() {
        Position position = openPosition("BTCUSDT", 1.0); // full position is 1.0
        position.setUserId("user1");
        position.setCredentialId("cred1");
        position.setAvgEntryPrice(BigDecimal.valueOf(100));
        position.setOcoOrderListId("oco1");

        var filledLeg = new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("tp1", "SELL", "TAKE_PROFIT", "FILLED", BigDecimal.valueOf(110), BigDecimal.valueOf(0.4), BigDecimal.valueOf(1.0)); // only 0.4 of 1.0 filled
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco1"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "ALL_DONE", List.of(filledLeg), "{}"));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        List<Fill> exitFills = List.of(new Fill(BigDecimal.valueOf(110), BigDecimal.valueOf(0.4), BigDecimal.valueOf(0.02), "USDT"));
        when(adapter.getFillsForOrder(any(), any(), any(), eq("BTCUSDT"), eq("tp1"))).thenReturn(exitFills);
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "oco-new", "{}", null));

        service.reconcileOcoProtectedPosition(credential, adapter, "key", "secret", position, java.util.Optional.empty(), 1L);

        verify(fillLedgerService).recordFills(eq("tp1"), any(), eq("user1"), eq("cred1"), eq("BTCUSDT"), eq("SELL"),
            eq("USDT"), eq(exitFills), argThat(q -> q.compareTo(BigDecimal.valueOf(0.4)) == 0), any());
        assertThat(position.getStatus()).isEqualTo("OPEN"); // still open — only partially exited
        assertThat(position.getQuantity()).isEqualByComparingTo("0.6"); // remainder
    }

    @Test
    @DisplayName("reconcileOcoProtectedPosition: a partial exit that loses the atomic-update race (another process already modified this position first) skips its own side effects entirely -- never double-counts P&L, never double-audits -- the actual review fix (\"Position close has atomic protection; not every position mutation does\"), extended to the partial-exit path")
    void ocoPartialExit_lostRace_skipsSideEffects() {
        Position position = openPosition("BTCUSDT", 1.0);
        position.setUserId("user1");
        position.setCredentialId("cred1");
        position.setAvgEntryPrice(BigDecimal.valueOf(100));
        position.setOcoOrderListId("oco1");

        var filledLeg = new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("tp1", "SELL", "TAKE_PROFIT", "FILLED", BigDecimal.valueOf(110), BigDecimal.valueOf(0.4), BigDecimal.valueOf(1.0));
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco1"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "ALL_DONE", List.of(filledLeg), "{}"));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        List<Fill> exitFills = List.of(new Fill(BigDecimal.valueOf(110), BigDecimal.valueOf(0.4), BigDecimal.valueOf(0.02), "USDT"));
        when(adapter.getFillsForOrder(any(), any(), any(), eq("BTCUSDT"), eq("tp1"))).thenReturn(exitFills);
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(Position.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 0L, null)); // matched, but modifiedCount=0 -- lost the race

        service.reconcileOcoProtectedPosition(credential, adapter, "key", "secret", position, java.util.Optional.empty(), 1L);

        // The fill was still recorded to the ledger (that part is unconditional, real, and
        // already happened on the exchange) -- but the audit call and downstream risk-outcome
        // recording, which only fire AFTER the atomic update succeeds, never ran.
        verify(credentialService, never()).audit(any(), any(), any(), eq("POSITION_PARTIAL_EXIT"), any());
    }

    @Test
    @DisplayName("reconcileOcoProtectedPosition: the realized P&L value itself is correctly computed through the new RealizedPnlService integration -- entry 100, exit 110, qty 1.0, entry fee 2.0, exit fee 0.05 => (110-100)*1.0 - 2.0 - 0.05 = 7.95. None of this method's OTHER existing tests assert on the actual number, only on fill-ledger/position-state side effects -- this closes that specific gap.")
    void ocoFullTakeProfitClose_computesCorrectRealizedPnl() {
        Position position = openPosition("BTCUSDT", 1.0);
        position.setUserId("user1");
        position.setCredentialId("cred1");
        position.setAvgEntryPrice(BigDecimal.valueOf(100));
        position.setEntryFeeQuote(BigDecimal.valueOf(2.0));
        position.setOcoOrderListId("oco1");

        var filledLeg = new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("tp1", "SELL", "TAKE_PROFIT", "FILLED", BigDecimal.valueOf(110), BigDecimal.valueOf(1.0), BigDecimal.valueOf(1.0));
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco1"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "ALL_DONE", List.of(filledLeg), "{}"));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        List<Fill> exitFills = List.of(new Fill(BigDecimal.valueOf(110), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.05), "USDT"));
        when(adapter.getFillsForOrder(any(), any(), any(), eq("BTCUSDT"), eq("tp1"))).thenReturn(exitFills);
        when(positionSafetyService.sumCommissionInQuoteAsset(exitFills, "USDT")).thenReturn(BigDecimal.valueOf(0.05));

        service.reconcileOcoProtectedPosition(credential, adapter, "key", "secret", position, java.util.Optional.empty(), 1L);

        assertThat(position.getRealizedPnlQuote()).isEqualByComparingTo("7.95");
    }

    @Test
    @DisplayName("reconcileOcoProtectedPosition: when recordFills returns fewer records than expected on an OCO exit, the profile is halted and a CRITICAL incident is raised, but the position's own close still proceeds normally -- the actual review fix (\"Fill Ledger can still fail without stopping financial state changes\"), extended from the entry path to the exit path per the review's own \"entry first, then exits\" phasing")
    void ocoExitLedgerRecordingFailed_haltsProfileButStillClosesPosition() {
        Position position = openPosition("BTCUSDT", 1.0);
        position.setUserId("user1");
        position.setCredentialId("cred1");
        position.setAvgEntryPrice(BigDecimal.valueOf(100));
        position.setOcoOrderListId("oco1");

        var filledLeg = new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("tp1", "SELL", "TAKE_PROFIT", "FILLED", BigDecimal.valueOf(110), BigDecimal.valueOf(1.0), BigDecimal.valueOf(1.0));
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco1"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "ALL_DONE", List.of(filledLeg), "{}"));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        List<Fill> exitFills = List.of(new Fill(BigDecimal.valueOf(110), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.05), "USDT"));
        when(adapter.getFillsForOrder(any(), any(), any(), eq("BTCUSDT"), eq("tp1"))).thenReturn(exitFills);
        when(fillLedgerService.recordFills(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(java.util.List.of()); // zero records despite a real exit fill having happened

        service.reconcileOcoProtectedPosition(credential, adapter, "key", "secret", position, java.util.Optional.of(profile), 1L);

        assertThat(profile.isTradingHalted()).isTrue();
        assertThat(profile.getHaltReason()).contains("Fill ledger recording failed");
        verify(riskProfileRepo, never()).save(any());
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && Boolean.TRUE.equals(setDoc.getBoolean("tradingHalted"));
        }), eq(RiskProfile.class));
        verify(incidentService).raiseCritical(eq("user1"), any(), any(), any(), any(), eq("FILL_LEDGER_INCOMPLETE"), any());
        // The position's own close still proceeds normally despite the halt -- the exit
        // genuinely happened on the exchange regardless of whether our ledger recorded it.
        assertThat(position.getStatus()).isEqualTo("CLOSED");
        assertThat(position.isLedgerRecordingIncomplete()).isTrue();
    }

    @Test
    @DisplayName("reconcileOcoProtectedPosition: getFillsForOrder() itself throwing (before ever reaching recordFills) ALSO halts the profile and raises a CRITICAL incident -- the actual review fix (\"Fill Ledger can still be missing after a confirmed fill\"), closing the gap where a failure earlier in the sequence than recordFills() itself used to be silently swallowed by a bare log.warn() with no escalation at all")
    void ocoExitFillFetchFailure_haltsProfileButStillClosesPosition() {
        Position position = openPosition("BTCUSDT", 1.0);
        position.setUserId("user1");
        position.setCredentialId("cred1");
        position.setAvgEntryPrice(BigDecimal.valueOf(100));
        position.setOcoOrderListId("oco1");

        var filledLeg = new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("tp1", "SELL", "TAKE_PROFIT", "FILLED", BigDecimal.valueOf(110), BigDecimal.valueOf(1.0), BigDecimal.valueOf(1.0));
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco1"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "ALL_DONE", List.of(filledLeg), "{}"));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        when(adapter.getFillsForOrder(any(), any(), any(), eq("BTCUSDT"), eq("tp1")))
            .thenThrow(new RuntimeException("simulated Binance trade-history lookup failure"));

        service.reconcileOcoProtectedPosition(credential, adapter, "key", "secret", position, java.util.Optional.of(profile), 1L);

        assertThat(profile.isTradingHalted()).isTrue();
        assertThat(profile.getHaltReason()).contains("Could not record the fill ledger");
        verify(riskProfileRepo, never()).save(any());
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && Boolean.TRUE.equals(setDoc.getBoolean("tradingHalted"));
        }), eq(RiskProfile.class));
        verify(incidentService).raiseCritical(eq("user1"), any(), any(), any(), any(), eq("FILL_LEDGER_INCOMPLETE"), any());
        // The position's own close still proceeds normally despite the halt -- the exit
        // genuinely happened on the exchange regardless of whether the fill fetch succeeded.
        assertThat(position.getStatus()).isEqualTo("CLOSED");
        assertThat(position.isLedgerRecordingIncomplete()).isTrue();
        // recordFills() was never even reached, let alone called -- confirms this test actually
        // isolates the EARLIER failure point, not incidentally re-testing the later one.
        verify(fillLedgerService, never()).recordFills(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reconcileOcoProtectedPosition: a genuine position-ledger mismatch on close (the fill history doesn't net to ~0 for a fully-closed position) halts the profile and raises a CRITICAL incident, but the position's own close still proceeds normally -- the actual review fix (\"Position Ledger is still not authoritative\")")
    void ocoExitPositionLedgerMismatch_haltsProfileButStillClosesPosition() {
        Position position = openPosition("BTCUSDT", 1.0);
        position.setUserId("user1");
        position.setCredentialId("cred1");
        position.setAvgEntryPrice(BigDecimal.valueOf(100));
        position.setOcoOrderListId("oco1");

        var filledLeg = new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("tp1", "SELL", "TAKE_PROFIT", "FILLED", BigDecimal.valueOf(110), BigDecimal.valueOf(1.0), BigDecimal.valueOf(1.0));
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco1"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "ALL_DONE", List.of(filledLeg), "{}"));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        List<Fill> exitFills = List.of(new Fill(BigDecimal.valueOf(110), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.05), "USDT"));
        when(adapter.getFillsForOrder(any(), any(), any(), eq("BTCUSDT"), eq("tp1"))).thenReturn(exitFills);
        // Explicitly stubbed to succeed here (unlike the sibling ledger-RECORDING-failure test
        // above) -- this test isolates the LEDGER-MISMATCH scenario specifically, and an
        // unstubbed recordFills would otherwise also trigger the sibling failure path
        // (Mockito's default empty-list return), contaminating what this test actually verifies.
        when(fillLedgerService.recordFills(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of(mock(FillRecord.class)));
        when(positionLedgerService.reconcilePositionAgainstLedger(any(), any(), any()))
            .thenReturn(new PositionLedgerService.ReconcileResult(PositionLedgerService.ReconcileStatus.MISMATCH, BigDecimal.valueOf(0.3), BigDecimal.ZERO));

        service.reconcileOcoProtectedPosition(credential, adapter, "key", "secret", position, java.util.Optional.of(profile), 1L);

        assertThat(profile.isTradingHalted()).isTrue();
        assertThat(profile.getHaltReason()).contains("Position ledger mismatch");
        verify(riskProfileRepo, never()).save(any());
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && Boolean.TRUE.equals(setDoc.getBoolean("tradingHalted"));
        }), eq(RiskProfile.class));
        verify(incidentService).raiseCritical(eq("user1"), any(), any(), any(), any(), eq("POSITION_LEDGER_MISMATCH"), any());
        // The position's own close still proceeds normally despite the halt -- the exit
        // genuinely happened on the exchange regardless of what the ledger's own history says.
        assertThat(position.getStatus()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("reconcileOcoProtectedPosition: a partial exit leaving DUST (remaining quantity below the symbol's own minQty) is marked CLOSED with DUST_REMAINING rather than attempting a doomed re-protection OCO or emergency-flatten")
    void ocoPartialExit_leavesDust_marksClosedRatherThanAttemptingDoomedReprotection() {
        Position position = openPosition("BTCUSDT", 1.0);
        position.setUserId("user1");
        position.setCredentialId("cred1");
        position.setAvgEntryPrice(BigDecimal.valueOf(100));
        position.setOcoOrderListId("oco1");

        // Exits 0.999 of 1.0, leaving exactly 0.001 remaining -- below the 0.01 minQty stubbed below.
        var filledLeg = new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("tp1", "SELL", "TAKE_PROFIT", "FILLED", BigDecimal.valueOf(110), BigDecimal.valueOf(0.999), BigDecimal.valueOf(1.0));
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco1"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "ALL_DONE", List.of(filledLeg), "{}"));
        var rulesWithMinQty = new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(0.01), BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(rulesWithMinQty);
        List<Fill> exitFills = List.of(new Fill(BigDecimal.valueOf(110), BigDecimal.valueOf(0.999), BigDecimal.valueOf(0.02), "USDT"));
        when(adapter.getFillsForOrder(any(), any(), any(), eq("BTCUSDT"), eq("tp1"))).thenReturn(exitFills);

        service.reconcileOcoProtectedPosition(credential, adapter, "key", "secret", position, java.util.Optional.empty(), 1L);

        assertThat(position.getStatus()).isEqualTo("CLOSED"); // not left OPEN forever with an untradeable amount
        assertThat(position.getCloseReason()).isEqualTo("DUST_REMAINING");
        verify(adapter, org.mockito.Mockito.never()).placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()); // never attempted -- would have failed anyway
        verify(positionSafetyService, org.mockito.Mockito.never()).emergencyFlatten(any(), any(), any(), any(), any(), any()); // never attempted -- would also have failed
    }

    // ── Late-discovered fill correlation-group reservation ("Risk" — "atomic correlation reservations") ────

    @Test
    @DisplayName("createPositionForLateDiscoveredFill: the profile's correlationGroups/correlationGroupCaps are passed through to the atomic exposure reservation before any SL/TP check runs")
    void lateDiscoveredFill_passesCorrelationGroupsToReservation() {
        var groups = java.util.Map.of("L1-majors", java.util.Set.of("BTCUSDT"));
        var caps = java.util.Map.of("L1-majors", BigDecimal.valueOf(5000));
        profile.setCorrelationGroups(groups);
        profile.setCorrelationGroupCaps(caps);
        when(slotReservationService.reserve(any(), anyInt(), any(), anyBoolean())).thenReturn(com.tradevision.service.PositionSlotReservationService.SlotReserveResult.reserved("test-slot-id"));
        when(exposureReservationService.reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new com.tradevision.service.ExposureReservationService.ExposureReserveResult(true, null, "test-reservation-id"));
        when(adapter.getFillsForOrder(any(), any(), any(), any(), any())).thenReturn(
            List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.001), "BTC")));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        when(positionSafetyService.computeNetQuantity(any(), any(), any())).thenReturn(
            new PositionSafetyService.FillAccountingResult(BigDecimal.valueOf(0.999), BigDecimal.ZERO));
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "oco-new", "{}", null));

        var order = new Order();
        order.setUserId("user1");
        order.setSymbol("BTCUSDT");
        order.setBrokerOrderId("entry-1");
        order.setSide("BUY"); // P0 fix regression: this method now requires side == BUY -- these tests all test genuine BUY-entry scenarios
        order.setSignalId("sig1");
        // Deliberately no SL/TP set — this test's purpose is verifying the correlation-group
        // reservation wiring specifically, which happens earlier in the method regardless of
        // what follows; leaving SL/TP unset takes the method's own early emergency-flatten
        // return rather than continuing into OCO-placement logic this test doesn't verify.

        service.createPositionForLateDiscoveredFill(credential, adapter, "key", "secret", order, BigDecimal.valueOf(1.0), 1L);

        verify(exposureReservationService).reserve(any(), any(), any(), any(), any(), eq(groups), eq(caps), any(), anyBoolean());
        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepo, atLeastOnce()).save(positionCaptor.capture());
        assertThat(positionCaptor.getValue().getStatus()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("createPositionForLateDiscoveredFill: a genuine position-ledger mismatch on a late-discovered fill halts the profile and raises a CRITICAL incident, but the position is still created -- the actual review fix (\"Late-fill path still mutates Position directly\"), extending the same reconciliation escalation already wired at entry/OCO-close/flatten-close to this creation point too")
    void lateDiscoveredFill_positionLedgerMismatch_haltsProfileButStillCreatesPosition() {
        when(slotReservationService.reserve(any(), anyInt(), any(), anyBoolean())).thenReturn(com.tradevision.service.PositionSlotReservationService.SlotReserveResult.reserved("test-slot-id"));
        when(exposureReservationService.reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new com.tradevision.service.ExposureReservationService.ExposureReserveResult(true, null, "test-reservation-id"));
        when(adapter.getFillsForOrder(any(), any(), any(), any(), any())).thenReturn(
            List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.001), "BTC")));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        when(positionSafetyService.computeNetQuantity(any(), any(), any())).thenReturn(
            new PositionSafetyService.FillAccountingResult(BigDecimal.valueOf(0.999), BigDecimal.ZERO));
        when(positionLedgerService.reconcilePositionAgainstLedger(any(), any(), any()))
            .thenReturn(new PositionLedgerService.ReconcileResult(PositionLedgerService.ReconcileStatus.MISMATCH, BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.999)));

        var order2 = new Order();
        order2.setUserId("user1");
        order2.setSymbol("BTCUSDT");
        order2.setBrokerOrderId("entry-1");
        order2.setSide("BUY"); // P0 fix regression: this method now requires side == BUY -- these tests all test genuine BUY-entry scenarios
        order2.setSignalId("sig1");
        // Deliberately no SL/TP set — takes the method's own early emergency-flatten return,
        // same as the sibling test above; this test verifies the reconciliation check that runs
        // BEFORE that branch point, not what happens after it.

        service.createPositionForLateDiscoveredFill(credential, adapter, "key", "secret", order2, BigDecimal.valueOf(1.0), 1L);

        assertThat(profile.isTradingHalted()).isTrue();
        assertThat(profile.getHaltReason()).contains("Position ledger mismatch");
        verify(riskProfileRepo, never()).save(any());
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && Boolean.TRUE.equals(setDoc.getBoolean("tradingHalted"));
        }), eq(RiskProfile.class));
        verify(incidentService).raiseCritical(eq("user1"), any(), any(), any(), any(), eq("POSITION_LEDGER_MISMATCH"), any());
        // The position is still created despite the halt -- the fill genuinely happened on the
        // exchange regardless of what the ledger's own reconstruction says.
        ArgumentCaptor<Position> positionCaptor2 = ArgumentCaptor.forClass(Position.class);
        verify(positionRepo, atLeastOnce()).save(positionCaptor2.capture());
        assertThat(positionCaptor2.getValue().getStatus()).isEqualTo("OPEN");
    }

    /**
     * Review finding ("Graceful shutdown does not stop @Scheduled work or WebSocket listeners
     * from starting new work" -- external review, nineteenth pass, P1, full context in
     * reconcileCredential's own updated comment): the actual test -- this exact public method,
     * the one BinanceUserDataStreamService calls directly on every executionReport, must itself
     * refuse to start a new reconciliation pass during shutdown, not rely on doReconcile's own
     * check (which this call path never goes through at all).
     */
    @Test
    @DisplayName("reconcileCredential: refuses to start a new reconciliation pass at all when the process is shutting down -- closing the gap where BinanceUserDataStreamService's own onText() calls this method directly, bypassing doReconcile's own shutdown check entirely")
    void reconcileCredential_shuttingDown_neverStartsNewWork() {
        when(shutdownState.isShuttingDown()).thenReturn(true);

        service.reconcileCredential(credential);

        verify(distributedLockService, never()).tryAcquire(any(), any(), any());
        verify(positionRepo, never()).findByCredentialIdAndStatus(any(), any());
    }

    @Test
    @DisplayName("reconcileCredential: a lost lock-renewal mid-pass STOPS the pass rather than continuing to mutate shared state -- the actual review fix (\"Reconciliation lock renewal failure currently continues anyway\"), since continuing risks a second instance that now believes it owns the lock concurrently mutating the same positions")
    void reconcileCredential_lostRenewal_stopsPassRatherThanContinuing() {
        when(positionRepo.findByCredentialIdAndStatus(any(), any())).thenReturn(List.of());
        when(omsOrderRepo.findByCredentialIdAndStatusInOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());
        // tryAcquire succeeds (this instance genuinely starts the pass), but the first renewal
        // check mid-pass fails -- simulating a lease that was lost to another instance.
        when(distributedLockService.renew(any(), any(), anyLong(), any())).thenReturn(false);

        service.reconcileCredential(credential);

        // The slot-reconciliation query only runs at the very end of a full, uninterrupted pass
        // -- never reaching it is the real, observable proof the pass stopped early rather than
        // continuing through checkDrawdown and beyond.
        verify(positionRepo, never()).findByCredentialIdAndStatus(credential.getId(), "OPEN");
    }

    @Test
    @DisplayName("recoverStuckFlattening: with NO order-level truth at all, account balance below minQty does NOT auto-close the position anymore -- the actual review fix (\"Stuck FLATTENING fallback can still use balance when order-level truth is unavailable\"), whose own recommended policy is UNKNOWN + HALT + MANUAL RECONCILIATION rather than an inference-based closure, since balance alone can never mathematically prove this exact flatten order filled (could be manual trading, a transfer, another application, another worker)")
    void recoverStuckFlattening_noOrderLevelTruth_neverAutoClosesFromBalanceAlone() {
        var customRules = new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(0.0001), BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
        Position stuck = new Position();
        stuck.setId("pos-stuck-1"); stuck.setUserId("user1"); stuck.setCredentialId("cred1"); stuck.setSymbol("BTCUSDT");
        stuck.setStatus("FLATTENING"); stuck.setQuantity(BigDecimal.valueOf(1.0));
        when(positionRepo.findByCredentialIdAndStatus("cred1", "FLATTENING")).thenReturn(List.of(stuck));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(customRules);
        // Free balance is below minQty -- under the OLD policy this alone would have closed the
        // position. Under the new policy, this is supporting context only, never sufficient on
        // its own: no flatten OMS order exists at all (omsOrderRepo unstubbed -- empty list),
        // so there is genuinely no order-level truth to consult.
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new com.tradevision.service.broker.dto.AssetBalance("BTC", BigDecimal.valueOf(0.00001), BigDecimal.ZERO)));
        RiskProfile riskProfile = new RiskProfile(); riskProfile.setId("rp1"); riskProfile.setCredentialId("cred1");
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(riskProfile));

        service.reconcileCredential(credential);

        verify(mongoTemplate, never()).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && "CLOSED_UNVERIFIED_PNL".equals(setDoc.getString("status"));
        }), eq(Position.class));
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), eq("STUCK_FLATTENING_UNKNOWN"), any());
    }

    @Test
    @DisplayName("recoverStuckFlattening: with NO order-level truth, even a full, unchanged balance (which would already have suggested \"not filled\" under the old policy too) still routes through the same UNKNOWN/escalate path, not a balance-driven \"confirmed not filled\" one -- confirming balance value no longer drives this decision at all, in either direction")
    void recoverStuckFlattening_saleNotConfirmed_escalatesAndHalts() {
        var customRules = new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(0.0001), BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
        Position stuck = new Position();
        stuck.setId("pos-stuck-2"); stuck.setUserId("user1"); stuck.setCredentialId("cred1"); stuck.setSymbol("BTCUSDT");
        stuck.setStatus("FLATTENING"); stuck.setQuantity(BigDecimal.valueOf(1.0));
        when(positionRepo.findByCredentialIdAndStatus("cred1", "FLATTENING")).thenReturn(List.of(stuck));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(customRules);
        // Free balance still shows the full quantity -- the sell demonstrably did NOT go through.
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new com.tradevision.service.broker.dto.AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        RiskProfile riskProfile = new RiskProfile(); riskProfile.setId("rp1"); riskProfile.setCredentialId("cred1");
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(riskProfile));

        service.reconcileCredential(credential);

        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), eq("STUCK_FLATTENING_UNKNOWN"), any());
        verify(mongoTemplate, never()).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && "CLOSED_UNVERIFIED_PNL".equals(setDoc.getString("status"));
        }), eq(Position.class));
    }

    @Test
    @DisplayName("recoverStuckFlattening: a PARTIALLY_FILLED order status is a definitive, ground-truth fact -- the actual review fix (\"recoverStuckFlattening() can still incorrectly close a partially-filled flatten\"). A significant remainder stays OPEN with corrected quantity accounting and gets escalated, rather than the whole position being silently marked closed via the balance fallback")
    void recoverStuckFlattening_partiallyFilledSignificantRemainder_correctsQuantityAndEscalates() {
        var customRules = new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(0.0001), BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
        Position stuck = new Position();
        stuck.setId("pos-stuck-3"); stuck.setUserId("user1"); stuck.setCredentialId("cred1"); stuck.setSymbol("BTCUSDT");
        stuck.setStatus("FLATTENING"); stuck.setQuantity(BigDecimal.valueOf(1.0));
        when(positionRepo.findByCredentialIdAndStatus("cred1", "FLATTENING")).thenReturn(List.of(stuck));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(customRules);
        Order flattenOrder = new Order(); flattenOrder.setId("order1"); flattenOrder.setClientOrderId("tv-flat-1");
        when(omsOrderRepo.findByPositionIdAndOrderRoleOrderByCreatedAtDesc("pos-stuck-3", "FLATTEN")).thenReturn(List.of(flattenOrder));
        // 0.700 of 1.000 filled -- a real, definitive, ground-truth partial fill from the broker
        // itself, not an inference. Remaining 0.300 is well above minQty (0.0001) -- genuinely
        // significant, not dust.
        when(adapter.getOrderStatusByClientOrderId(any(), any(), any(), eq("BTCUSDT"), eq("tv-flat-1")))
            .thenReturn(new com.tradevision.service.broker.dto.OrderStatusInfo("PARTIALLY_FILLED", BigDecimal.valueOf(0.7), BigDecimal.valueOf(50000), "{}"));
        RiskProfile riskProfile = new RiskProfile(); riskProfile.setId("rp1"); riskProfile.setCredentialId("cred1");
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(riskProfile));

        service.reconcileCredential(credential);

        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && "OPEN".equals(setDoc.getString("status"))
                && BigDecimal.valueOf(0.3).compareTo(new BigDecimal(setDoc.get("quantity").toString())) == 0;
        }), eq(Position.class));
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), eq("STUCK_FLATTENING_PARTIAL_UNPROTECTED"), any());
        // Never fell through to the balance fallback -- the order-level status was definitive.
        verify(adapter, never()).getBalance(any(), any(), any());
    }

    /**
     * Review finding ("partial recovery overwrites previous closedQuantity" -- external review,
     * thirty-second pass, full context in recoverStuckFlattening's own updated comment on this
     * exact block): the actual test -- a position that already has a confirmed closedQuantity
     * from an EARLIER flatten attempt (0.4) must have this NEW leg's own confirmed amount (0.2)
     * ADDED to it, ending at 0.6 -- not overwritten down to 0.2.
     */
    @Test
    @DisplayName("recoverStuckFlattening: a position with an already-confirmed closedQuantity (0.4) from an earlier flatten attempt gets this NEW partial leg's amount (0.2) ADDED to it -- ends at 0.6, never overwritten down to 0.2")
    void recoverStuckFlattening_partialFillWithPriorClosedQuantity_accumulatesRatherThanOverwrites() {
        var customRules = new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(0.0001), BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
        Position stuck = new Position();
        stuck.setId("pos-stuck-9"); stuck.setUserId("user1"); stuck.setCredentialId("cred1"); stuck.setSymbol("BTCUSDT");
        stuck.setStatus("FLATTENING");
        stuck.setQuantity(BigDecimal.valueOf(0.6)); // already reduced by the earlier confirmed 0.4 sale
        stuck.setClosedQuantity(BigDecimal.valueOf(0.4)); // that earlier confirmed sale, already on record
        when(positionRepo.findByCredentialIdAndStatus("cred1", "FLATTENING")).thenReturn(List.of(stuck));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(customRules);
        Order flattenOrder = new Order(); flattenOrder.setId("order1"); flattenOrder.setClientOrderId("tv-flat-1");
        when(omsOrderRepo.findByPositionIdAndOrderRoleOrderByCreatedAtDesc("pos-stuck-9", "FLATTEN")).thenReturn(List.of(flattenOrder));
        // A second flatten attempt (after a crash) confirms another 0.2 sold, out of the 0.6 real remaining.
        when(adapter.getOrderStatusByClientOrderId(any(), any(), any(), eq("BTCUSDT"), eq("tv-flat-1")))
            .thenReturn(new com.tradevision.service.broker.dto.OrderStatusInfo("PARTIALLY_FILLED", BigDecimal.valueOf(0.2), BigDecimal.valueOf(50000), "{}"));
        RiskProfile riskProfile = new RiskProfile(); riskProfile.setId("rp1"); riskProfile.setCredentialId("cred1");
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(riskProfile));

        service.reconcileCredential(credential);

        // The actual claim under test: closedQuantity ends at 0.4 + 0.2 = 0.6, not 0.2 alone.
        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && "OPEN".equals(setDoc.getString("status"))
                && BigDecimal.valueOf(0.6).compareTo(new BigDecimal(setDoc.get("closedQuantity").toString())) == 0
                && BigDecimal.valueOf(0.4).compareTo(new BigDecimal(setDoc.get("quantity").toString())) == 0;
        }), eq(Position.class));
    }

    /**
     * Review finding ("Emergency flatten still allows an exchange sell without durable
     * pre-submission intent" -- external review, twenty-sixth pass, P1, full context in
     * FlattenAttempt's own class javadoc): the actual test proving the fallback works -- no OMS
     * Order record exists at all (as if the OMS setup itself had failed at submission time),
     * but a FlattenAttempt record does, and its own clientOrderId still gets a real,
     * order-status-level answer instead of falling straight to the weaker balance heuristic.
     */
    @Test
    @DisplayName("recoverStuckFlattening: no OMS Order record exists at all, but a durable FlattenAttempt record does -- its own clientOrderId is used instead of falling straight to the balance fallback")
    void recoverStuckFlattening_noOmsOrderButFlattenAttemptExists_usesItInsteadOfBalance() {
        profile.setMaxDrawdownPercent(0); // checkDrawdown() legitimately reads balances every pass; this test is about the recovery fallback only
        Position stuck = new Position();
        stuck.setId("pos-stuck-4"); stuck.setUserId("user1"); stuck.setCredentialId("cred1"); stuck.setSymbol("BTCUSDT");
        stuck.setStatus("FLATTENING"); stuck.setQuantity(BigDecimal.valueOf(1.0));
        when(positionRepo.findByCredentialIdAndStatus("cred1", "FLATTENING")).thenReturn(List.of(stuck));
        // No OMS Order record at all -- as if orderService.create had failed at submission time.
        when(omsOrderRepo.findByPositionIdAndOrderRoleOrderByCreatedAtDesc("pos-stuck-4", "FLATTEN")).thenReturn(List.of());
        var attempt = new com.tradevision.model.FlattenAttempt();
        attempt.setPositionId("pos-stuck-4"); attempt.setClientOrderId("tv-flat-fallback-1");
        when(flattenAttemptRepo.findByPositionIdOrderByCreatedAtDesc("pos-stuck-4")).thenReturn(List.of(attempt));
        when(adapter.getOrderStatusByClientOrderId(any(), any(), any(), eq("BTCUSDT"), eq("tv-flat-fallback-1")))
            .thenReturn(new com.tradevision.service.broker.dto.OrderStatusInfo("FILLED", BigDecimal.valueOf(1.0), BigDecimal.valueOf(50000), "{}"));

        service.reconcileCredential(credential);

        // The exact clientOrderId from the FlattenAttempt fallback record was used to query the
        // exchange directly -- never fell through to the balance heuristic.
        verify(adapter).getOrderStatusByClientOrderId(any(), any(), any(), eq("BTCUSDT"), eq("tv-flat-fallback-1"));
        verify(adapter, never()).getBalance(any(), any(), any());
    }

    @Test
    @DisplayName("recoverStuckFlattening: a PARTIALLY_FILLED order whose remainder is below the symbol's own minQty is treated as dust and closed -- the same dust-handling principle already established elsewhere in this codebase (reprotectRemainder's own dust check), applied here too")
    void recoverStuckFlattening_partiallyFilledDustRemainder_closesAsDust() {
        profile.setMaxDrawdownPercent(0); // checkDrawdown() legitimately reads balances every pass; this test is about the recovery fallback only
        var customRules = new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(0.0001), BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
        Position stuck = new Position();
        stuck.setId("pos-stuck-4"); stuck.setUserId("user1"); stuck.setCredentialId("cred1"); stuck.setSymbol("BTCUSDT");
        stuck.setStatus("FLATTENING"); stuck.setQuantity(BigDecimal.valueOf(1.0));
        when(positionRepo.findByCredentialIdAndStatus("cred1", "FLATTENING")).thenReturn(List.of(stuck));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(customRules);
        Order flattenOrder = new Order(); flattenOrder.setId("order1"); flattenOrder.setClientOrderId("tv-flat-1");
        when(omsOrderRepo.findByPositionIdAndOrderRoleOrderByCreatedAtDesc("pos-stuck-4", "FLATTEN")).thenReturn(List.of(flattenOrder));
        // 0.99995 of 1.0 filled -- remaining 0.00005 is below minQty (0.0001), genuine dust.
        when(adapter.getOrderStatusByClientOrderId(any(), any(), any(), eq("BTCUSDT"), eq("tv-flat-1")))
            .thenReturn(new com.tradevision.service.broker.dto.OrderStatusInfo("PARTIALLY_FILLED", BigDecimal.valueOf(0.99995), BigDecimal.valueOf(50000), "{}"));

        service.reconcileCredential(credential);

        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && "CLOSED_UNVERIFIED_PNL".equals(setDoc.getString("status"));
        }), eq(Position.class));
        verify(adapter, never()).getBalance(any(), any(), any());
    }

    // ── P0-4: unprotected OPEN positions are re-protected or exited, not left as-is ──────────

    @Test
    @DisplayName("reconcileUnprotectedPosition: no OCO, coins genuinely still held, price still above the recorded stop trigger -- re-places protection via a fresh OCO rather than leaving the position open with unlimited downside")
    void reconcileUnprotectedPosition_stillHeldPriceAboveStop_reProtects() {
        Position position = new Position();
        position.setId("pos1"); position.setUserId("user1"); position.setCredentialId("cred1"); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setQuantity(BigDecimal.valueOf(1.0)); position.setOcoOrderListId(null);
        position.setEntryOrderId("entry-1");
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of(position));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO))); // coins genuinely still held

        Order entryOrder = new Order();
        entryOrder.setStopLossTriggerPrice(BigDecimal.valueOf(95));
        entryOrder.setTakeProfitPrice(BigDecimal.valueOf(120));
        when(omsOrderRepo.findByCredentialIdAndSymbolAndBrokerOrderId("cred1", "BTCUSDT", "entry-1")).thenReturn(Optional.of(entryOrder));
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BigDecimal.valueOf(100)); // above the stop trigger of 95

        Order remainderOmsOrder = new Order(); remainderOmsOrder.setId("remainder-order-1");
        when(orderService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(remainderOmsOrder);
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "new-oco-1", "{}", null));

        service.reconcileCredential(credential);

        verify(adapter).placeExitOco(any(), any(), any(), eq("BTCUSDT"), any(), any(), any(), any(), any());
        verify(positionSafetyService, never()).emergencyFlatten(any(), any(), any(), any(), any(), any());
        verify(credentialService).audit(any(), any(), any(), eq("PROTECTION_MISSING_REPLACING"), any());
    }

    @Test
    @DisplayName("reconcileUnprotectedPosition: no OCO, coins genuinely still held, but current price is already AT or BELOW the recorded stop trigger -- a new stop order there would be rejected as already-triggered, so this emergency-flattens at market instead")
    void reconcileUnprotectedPosition_stillHeldPriceBelowStop_flattensInstead() {
        Position position = new Position();
        position.setId("pos1"); position.setUserId("user1"); position.setCredentialId("cred1"); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setQuantity(BigDecimal.valueOf(1.0)); position.setOcoOrderListId(null);
        position.setEntryOrderId("entry-1");
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of(position));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));

        Order entryOrder = new Order();
        entryOrder.setStopLossTriggerPrice(BigDecimal.valueOf(95));
        entryOrder.setTakeProfitPrice(BigDecimal.valueOf(120));
        when(omsOrderRepo.findByCredentialIdAndSymbolAndBrokerOrderId("cred1", "BTCUSDT", "entry-1")).thenReturn(Optional.of(entryOrder));
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BigDecimal.valueOf(90)); // already through the stop trigger of 95

        service.reconcileCredential(credential);

        verify(positionSafetyService).emergencyFlatten(eq(credential), eq(adapter), any(), any(), eq(position), any());
        verify(adapter, never()).placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(credentialService).audit(any(), any(), any(), eq("PROTECTION_MISSING_PRICE_BELOW_STOP"), any());
    }

    @Test
    @DisplayName("reconcileUnprotectedPosition: no OCO, coins genuinely still held, but no recorded SL/TP at all to re-protect with -- emergency-flattens rather than leaving it open and unprotected indefinitely")
    void reconcileUnprotectedPosition_stillHeldNoSlTp_flattens() {
        Position position = new Position();
        position.setId("pos1"); position.setUserId("user1"); position.setCredentialId("cred1"); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setQuantity(BigDecimal.valueOf(1.0)); position.setOcoOrderListId(null);
        position.setEntryOrderId("entry-1");
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of(position));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        when(omsOrderRepo.findByCredentialIdAndSymbolAndBrokerOrderId("cred1", "BTCUSDT", "entry-1")).thenReturn(Optional.empty()); // no recorded SL/TP anywhere

        service.reconcileCredential(credential);

        verify(positionSafetyService).emergencyFlatten(eq(credential), eq(adapter), any(), any(), eq(position), any());
        verify(adapter, never()).getCurrentPrice(any(), any());
        verify(credentialService).audit(any(), any(), any(), eq("PROTECTION_MISSING_NO_SLTP"), any());
    }

    @Test
    @DisplayName("reconcileOpenPositions: a renewal that succeeds for the first position but fails before the second STOPS the rest of the loop -- the actual review fix (\"Reconciliation lease can still expire during one long mutation step\"), since a credential with many open positions could otherwise exhaust the lease entirely mid-loop with no renewal at all")
    void reconcileOpenPositions_renewalFailsMidLoop_stopsRestOfLoop() {
        Position first = new Position();
        first.setId("pos-first"); first.setUserId("user1"); first.setCredentialId("cred1"); first.setSymbol("BTCUSDT");
        first.setStatus("OPEN"); first.setQuantity(BigDecimal.valueOf(1.0));
        Position second = new Position();
        second.setId("pos-second"); second.setUserId("user1"); second.setCredentialId("cred1"); second.setSymbol("ETHUSDT");
        second.setStatus("OPEN"); second.setQuantity(BigDecimal.valueOf(1.0));
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of(first, second));
        // Renewal succeeds for every top-level check (before reconcileEntryOrders,
        // recoverStuckFlattening, recoverOrphanedOcos, and reconcileOpenPositions itself) and
        // this loop's own first position, then fails before the second position's own iteration.
        // Lease stays valid until the FIRST position has actually been processed (its getSymbolRules call),
        // then is lost before the second one -- independent of how many top-level renewal checks precede the loop.
        when(distributedLockService.renew(any(), any(), anyLong(), any())).thenAnswer(inv ->
            org.mockito.Mockito.mockingDetails(adapter).getInvocations().stream()
                .noneMatch(i -> i.getMethod().getName().equals("getSymbolRules")));
        when(adapter.getSymbolRules(any(), any())).thenReturn(BTC_RULES);
        when(adapter.getBalance(any(), any(), any())).thenReturn(List.of());

        service.reconcileCredential(credential);

        // getSymbolRules is the first real call reconcileUnprotectedPosition makes for EITHER
        // position -- exactly one invocation is the real, observable proof the loop stopped
        // before ever starting the second position's own processing.
        verify(adapter, times(1)).getSymbolRules(any(), any());
    }

    @Test
    @DisplayName("recoverOrphanedOcos: an orphan whose position is still OPEN and still unprotected gets re-attached -- the actual review fix (\"OCO Persistence Failure Has No Reconciliation Path\"), confirming the original failure really was a transient race rather than a permanently lost update")
    void recoverOrphanedOcos_positionStillOpenAndUnprotected_reattaches() {
        Position position = new Position();
        position.setId("pos1"); position.setUserId("user1"); position.setCredentialId("cred1"); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setQuantity(BigDecimal.valueOf(1.0)); position.setOcoOrderListId(null);
        var orphan = new com.tradevision.model.OrphanedOco();
        orphan.setId("orphan1"); orphan.setUserId("user1"); orphan.setCredentialId("cred1");
        orphan.setPositionId("pos1"); orphan.setSymbol("BTCUSDT"); orphan.setOcoOrderListId("oco-999");
        when(orphanedOcoRepo.findByCredentialIdAndResolvedFalse(eq("cred1"), any())).thenReturn(List.of(orphan));
        when(positionRepo.findById("pos1")).thenReturn(Optional.of(position));
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of());
        // Review finding ("OrphanedOco recovery still has an identity limitation" -- external
        // review, thirty-eighth pass, P1, full context in this method's own updated comment in
        // production code): the exchange's own live OCO status is now fetched and verified
        // BEFORE auto-attach even in this branch -- this test's own OCO must genuinely pass that
        // verification (still active, both legs SELL, quantity matching the position).
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco-999"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("oco-999", "EXECUTING", List.of(
                new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("leg1", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(70000), BigDecimal.ZERO, BigDecimal.valueOf(1.0)),
                new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("leg2", "SELL", "STOP_LOSS_LIMIT", "NEW", BigDecimal.valueOf(65000), BigDecimal.ZERO, BigDecimal.valueOf(1.0))
            ), "{}"));

        service.reconcileCredential(credential);

        // atomicSetOcoPlaced's own real, observable side effect -- the position's own
        // ocoOrderListId gets set to this orphan's own id, proving the re-attach actually ran.
        assertThat(position.getOcoOrderListId()).isEqualTo("oco-999");
        // Two saves now, not one: atomicSetOcoPlaced's own new safety-net orphan-record-first
        // behavior (see its own updated javadoc -- "OCO persistence failure still creates a
        // difficult crash window") creates a SEPARATE new OrphanedOco unconditionally before
        // attempting the position update, in addition to this call's own markOrphanResolved on
        // the ORIGINAL orphan once the re-attach succeeds. Captures all saves and checks the
        // specific one that matters for this test: the original orphan ends up resolved.
        ArgumentCaptor<com.tradevision.model.OrphanedOco> savedCaptor = ArgumentCaptor.forClass(com.tradevision.model.OrphanedOco.class);
        verify(orphanedOcoRepo, times(2)).save(savedCaptor.capture());
        var savedOriginalOrphan = savedCaptor.getAllValues().stream().filter(o -> "orphan1".equals(o.getId())).findFirst().orElseThrow();
        assertThat(savedOriginalOrphan.isResolved()).isTrue();
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), eq("ORPHANED_OCO_STILL_ACTIVE"), any());
    }

    /**
     * Review finding ("Orphan recovery can mark an unresolved OCO as RESOLVED" -- external
     * review, thirty-fifth pass, P0, the review's own explicit race, full context in
     * atomicSetOcoPlaced's own updated javadoc): the actual test proving the fix -- when the
     * position update inside atomicSetOcoPlaced genuinely fails (matches zero documents, e.g.
     * because the position closed concurrently between this reconciliation pass's own read and
     * the update), the original orphan record must NOT be marked resolved -- since the real
     * exchange OCO may still genuinely be active, and resolved=true would make every future
     * recovery pass's own resolved=false query silently skip it forever.
     */
    @Test
    @DisplayName("recoverOrphanedOcos: the re-attach's own position update genuinely fails (matches zero documents -- a concurrent close race) -- the orphan is left unresolved, NOT silently marked resolved=true")
    void recoverOrphanedOcos_reattachUpdateFails_orphanStaysUnresolved() {
        Position position = new Position();
        position.setId("pos1"); position.setUserId("user1"); position.setCredentialId("cred1"); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setQuantity(BigDecimal.valueOf(1.0)); position.setOcoOrderListId(null);
        var orphan = new com.tradevision.model.OrphanedOco();
        orphan.setId("orphan1"); orphan.setUserId("user1"); orphan.setCredentialId("cred1");
        orphan.setPositionId("pos1"); orphan.setSymbol("BTCUSDT"); orphan.setOcoOrderListId("oco-999");
        when(orphanedOcoRepo.findByCredentialIdAndResolvedFalse(eq("cred1"), any())).thenReturn(List.of(orphan));
        when(positionRepo.findById("pos1")).thenReturn(Optional.of(position));
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of());
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco-999"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("oco-999", "EXECUTING", List.of(
                new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("leg1", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(70000), BigDecimal.ZERO, BigDecimal.valueOf(1.0)),
                new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("leg2", "SELL", "STOP_LOSS_LIMIT", "NEW", BigDecimal.valueOf(65000), BigDecimal.ZERO, BigDecimal.valueOf(1.0))
            ), "{}"));
        // Simulates the review's own named race: the position closed concurrently, so the real
        // update inside atomicSetOcoPlaced matches zero documents.
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(Position.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null));

        service.reconcileCredential(credential);

        // The actual claim under test: the original orphan is never marked resolved=true.
        ArgumentCaptor<com.tradevision.model.OrphanedOco> savedCaptor = ArgumentCaptor.forClass(com.tradevision.model.OrphanedOco.class);
        verify(orphanedOcoRepo, atLeastOnce()).save(savedCaptor.capture());
        boolean originalOrphanEverMarkedResolved = savedCaptor.getAllValues().stream()
            .anyMatch(o -> "orphan1".equals(o.getId()) && o.isResolved());
        assertThat(originalOrphanEverMarkedResolved).isFalse();
        // atomicSetOcoPlaced's own existing incident for exactly this failure mode still fires.
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), eq("OCO_PLACED_BUT_NOT_RECORDED"), any());
    }

    @Test
    @DisplayName("recoverOrphanedOcos: an orphan that's still active on the exchange but has no position to attach to gets escalated, not silently dropped -- the review's own named risk (\"a live, unattached order affecting this account's real balance\"). Stays resolved=false so it keeps being re-checked -- the actual review fix (\"An active orphan OCO is marked 'resolved' even though the exchange order remains active\")")
    void recoverOrphanedOcos_stillActiveOnExchangeUnattachable_escalatesButStaysUnresolved() {
        var orphan = new com.tradevision.model.OrphanedOco();
        orphan.setId("orphan1"); orphan.setUserId("user1"); orphan.setCredentialId("cred1");
        orphan.setPositionId("pos1"); orphan.setSymbol("BTCUSDT"); orphan.setOcoOrderListId("oco-999");
        when(orphanedOcoRepo.findByCredentialIdAndResolvedFalse(eq("cred1"), any())).thenReturn(List.of(orphan));
        when(positionRepo.findById("pos1")).thenReturn(Optional.empty()); // position is gone entirely
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of());
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco-999")))
            .thenReturn(new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "EXECUTING", List.of(), "{}"));

        service.reconcileCredential(credential);

        verify(incidentService).raiseCritical(eq("user1"), eq("cred1"), eq("pos1"), isNull(), eq("BTCUSDT"),
            eq("ORPHANED_OCO_STILL_ACTIVE"), any());
        ArgumentCaptor<com.tradevision.model.OrphanedOco> savedCaptor = ArgumentCaptor.forClass(com.tradevision.model.OrphanedOco.class);
        verify(orphanedOcoRepo).save(savedCaptor.capture());
        // The dangerous condition (a live, unattached order) is NOT gone -- resolved must stay
        // false so a future pass keeps finding and re-checking this exact orphan.
        assertThat(savedCaptor.getValue().isResolved()).isFalse();
        assertThat(savedCaptor.getValue().isEscalated()).isTrue();
    }

    /**
     * Review finding ("OrphanedOco recovery still has an identity limitation" -- external
     * review, thirty-eighth pass, P1, the review's own explicit required checks, full context in
     * this method's own updated comment in production code): the actual test proving the fix --
     * an orphan whose exchange-side OCO quantity is meaningfully LARGER than the position's own
     * actual quantity (far beyond the small normalization tolerance) fails verification and does
     * NOT get auto-attached, escalating instead.
     */
    @Test
    @DisplayName("recoverOrphanedOcos: the position is OPEN and unprotected, but the exchange-side OCO quantity is meaningfully larger than the position's own actual quantity -- fails verification, does NOT auto-attach, escalates instead")
    void recoverOrphanedOcos_quantityMismatchTooLarge_failsVerificationEscalates() {
        Position position = new Position();
        position.setId("pos1"); position.setUserId("user1"); position.setCredentialId("cred1"); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setQuantity(BigDecimal.valueOf(1.0)); position.setOcoOrderListId(null);
        var orphan = new com.tradevision.model.OrphanedOco();
        orphan.setId("orphan1"); orphan.setUserId("user1"); orphan.setCredentialId("cred1");
        orphan.setPositionId("pos1"); orphan.setSymbol("BTCUSDT"); orphan.setOcoOrderListId("oco-999");
        when(orphanedOcoRepo.findByCredentialIdAndResolvedFalse(eq("cred1"), any())).thenReturn(List.of(orphan));
        when(positionRepo.findById("pos1")).thenReturn(Optional.of(position));
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of());
        // The exchange OCO genuinely protects 5.0, far more than this position's own real 1.0 --
        // well beyond the small normalization tolerance, a real identity concern worth escalating.
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco-999"))).thenReturn(
            new com.tradevision.service.broker.dto.OcoStatusInfo("oco-999", "EXECUTING", List.of(
                new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("leg1", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(70000), BigDecimal.ZERO, BigDecimal.valueOf(5.0))
            ), "{}"));

        service.reconcileCredential(credential);

        // The actual claim under test: never attached, escalated instead.
        assertThat(position.getOcoOrderListId()).isNull();
        verify(incidentService).raiseCritical(eq("user1"), eq("cred1"), eq("pos1"), isNull(), eq("BTCUSDT"),
            eq("ORPHANED_OCO_AUTO_ATTACH_VERIFICATION_FAILED"), any());
        verify(orphanedOcoRepo, never()).save(argThat(o -> "orphan1".equals(o.getId()) && o.isResolved()));
    }

    @Test
    @DisplayName("recoverOrphanedOcos: an orphan already escalated recently does NOT get re-alerted with a fresh critical incident on every single reconciliation pass -- the deduplication half of the same review fix")
    void recoverOrphanedOcos_alreadyEscalatedRecently_doesNotReAlert() {
        var orphan = new com.tradevision.model.OrphanedOco();
        orphan.setId("orphan1"); orphan.setUserId("user1"); orphan.setCredentialId("cred1");
        orphan.setPositionId("pos1"); orphan.setSymbol("BTCUSDT"); orphan.setOcoOrderListId("oco-999");
        orphan.setEscalated(true);
        orphan.setEscalatedAt(LocalDateTime.now().minusMinutes(5)); // recently escalated, well within the 1-hour dedup window
        when(orphanedOcoRepo.findByCredentialIdAndResolvedFalse(eq("cred1"), any())).thenReturn(List.of(orphan));
        when(positionRepo.findById("pos1")).thenReturn(Optional.empty());
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of());
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco-999")))
            .thenReturn(new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "EXECUTING", List.of(), "{}"));

        service.reconcileCredential(credential);

        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), eq("ORPHANED_OCO_STILL_ACTIVE"), any());
        verify(orphanedOcoRepo, never()).save(any());
    }

    @Test
    @DisplayName("recoverStuckFlattening: a definitive, genuine order-level FILLED status is the ONLY thing that can still close a position from this recovery path -- confirming the review's own recommended policy (\"UNKNOWN + HALT + MANUAL RECONCILIATION rather than automatically closing\") narrowed closure to real ground truth without breaking it entirely")
    void recoverStuckFlattening_orderLevelFilled_stillClosesCorrectly() {
        profile.setMaxDrawdownPercent(0); // checkDrawdown() legitimately reads balances every pass; this test is about the recovery fallback only
        var customRules = new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(0.0001), BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
        Position stuck = new Position();
        stuck.setId("pos-stuck-5"); stuck.setUserId("user1"); stuck.setCredentialId("cred1"); stuck.setSymbol("BTCUSDT");
        stuck.setStatus("FLATTENING"); stuck.setQuantity(BigDecimal.valueOf(1.0));
        stuck.setAvgEntryPrice(BigDecimal.valueOf(48000)); // needed for releaseExposureForClosedPosition's own fallback path to actually fire
        when(positionRepo.findByCredentialIdAndStatus("cred1", "FLATTENING")).thenReturn(List.of(stuck));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(customRules);
        Order flattenOrder = new Order(); flattenOrder.setId("order1"); flattenOrder.setClientOrderId("tv-flat-1");
        when(omsOrderRepo.findByPositionIdAndOrderRoleOrderByCreatedAtDesc("pos-stuck-5", "FLATTEN")).thenReturn(List.of(flattenOrder));
        when(adapter.getOrderStatusByClientOrderId(any(), any(), any(), eq("BTCUSDT"), eq("tv-flat-1")))
            .thenReturn(new com.tradevision.service.broker.dto.OrderStatusInfo("FILLED", BigDecimal.valueOf(1.0), BigDecimal.valueOf(50000), "{}"));

        service.reconcileCredential(credential);

        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && "CLOSED_UNVERIFIED_PNL".equals(setDoc.getString("status"));
        }), eq(Position.class));
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), any(), any());
        // Never even fetched balance -- a definitive order-level FILLED needs no supporting context.
        verify(adapter, never()).getBalance(any(), any(), any());
        // Review finding ("recovered full flatten does not release its reservations" --
        // external review, thirty-fourth pass, P1, full context in this branch's own updated
        // comment in production code): the actual test proving the fix -- this position
        // predates slotReservationId/exposureReservationId (never set on this fixture), so both
        // release helpers correctly fall back to their own key-based paths; either way, they
        // must actually be called now, which they weren't before this fix.
        verify(slotReservationService).releaseByKey("cred1");
        verify(exposureReservationService).release(eq("cred1"), eq("BTCUSDT"), any(BigDecimal.class));
        verify(executionContextService).recordClosedByPositionId("pos-stuck-5");
    }

    /**
     * Review finding ("stuck-FLATTENING recovery can still falsely close a capped order" --
     * external review, thirty-first pass, P0, the review's own explicitly required test, full
     * context in the FILLED-status branch's own updated comment in recoverStuckFlattening):
     * this is that exact test. A flatten SELL capped below the real position size (free balance
     * 0.4 out of a real 1.0 position) can genuinely, fully FILL its own smaller target -- the
     * broker reports "FILLED", not "PARTIALLY_FILLED", because as far as the exchange is
     * concerned the 0.4 order it was actually given DID fully fill. The crash happens before
     * TradeVision's own position update ever runs. Recovery must still correctly conclude 0.6
     * genuinely remains, exactly matching what this session's own earlier fix already proved
     * for the live (non-crash) flatten path.
     */
    @Test
    @DisplayName("recoverStuckFlattening: a broker-reported FILLED status whose own executedQty (0.4) is less than the real internal position size (1.0) -- the order genuinely filled ITS OWN capped target, but the real position is NOT fully closed. Corrects quantity to 0.6, stays OPEN, escalates -- never falsely marks NAKED_FLATTENED/CLOSED")
    void recoverStuckFlattening_filledStatusButExecutedQtyLessThanPosition_correctsQuantityNotClosed() {
        var customRules = new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(0.0001), BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
        Position stuck = new Position();
        stuck.setId("pos-stuck-6"); stuck.setUserId("user1"); stuck.setCredentialId("cred1"); stuck.setSymbol("BTCUSDT");
        stuck.setStatus("FLATTENING"); stuck.setQuantity(BigDecimal.valueOf(1.0)); // the REAL internal position size
        when(positionRepo.findByCredentialIdAndStatus("cred1", "FLATTENING")).thenReturn(List.of(stuck));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(customRules);
        Order flattenOrder = new Order(); flattenOrder.setId("order1"); flattenOrder.setClientOrderId("tv-flat-1");
        when(omsOrderRepo.findByPositionIdAndOrderRoleOrderByCreatedAtDesc("pos-stuck-6", "FLATTEN")).thenReturn(List.of(flattenOrder));
        // The order itself genuinely, fully FILLED -- but only 0.4 of it, because that's all it
        // was ever submitted for (balance-capped). This is the review's own exact distinction:
        // "Order FILLED = the requested order filled. Position CLOSED = the entire position was
        // sold." The broker has no way to report anything else here -- it only knows about the
        // 0.4 order it was actually given, not the real 1.0 position TradeVision holds.
        when(adapter.getOrderStatusByClientOrderId(any(), any(), any(), eq("BTCUSDT"), eq("tv-flat-1")))
            .thenReturn(new com.tradevision.service.broker.dto.OrderStatusInfo("FILLED", BigDecimal.valueOf(0.4), BigDecimal.valueOf(50000), "{}"));
        RiskProfile riskProfile = new RiskProfile(); riskProfile.setId("rp1"); riskProfile.setCredentialId("cred1");
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(riskProfile));

        service.reconcileCredential(credential);

        // The actual claim under test: 1.0 - 0.4 = 0.6 remains, stays OPEN, is corrected --
        // NEVER marked NAKED_FLATTENED or CLOSED_UNVERIFIED_PNL, which is exactly what the old,
        // buggy behavior would have done (treating broker-FILLED as position-fully-closed).
        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && "OPEN".equals(setDoc.getString("status"))
                && BigDecimal.valueOf(0.6).compareTo(new BigDecimal(setDoc.get("quantity").toString())) == 0;
        }), eq(Position.class));
        // The still-unprotected 0.6 remainder must still be escalated -- this recovery corrects
        // the accounting but does not itself re-protect the position.
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), eq("STUCK_FLATTENING_PARTIAL_UNPROTECTED"), any());
    }

    /**
     * Review finding ("FILLED + zero executedQty can still close the position" -- external
     * review, thirty-second pass, P1, the review's own explicitly required test, full context
     * in the FILLED-status branch's own updated comment): this is that exact test. The review's
     * own named root cause: BinanceBrokerAdapter.getOrderStatusByClientOrderId parses
     * executedQty via asText("0") -- a malformed or field-missing response genuinely produces
     * exactly this combination (status=FILLED, executedQty=0) from this codebase's own real
     * adapter, not a hypothetical one.
     */
    @Test
    @DisplayName("recoverStuckFlattening: FILLED status with executedQty=0 (a malformed/incomplete broker response, per this codebase's own real BinanceBrokerAdapter parsing) is NEVER treated as a confirmed full close -- routes to the same UNKNOWN/halt path as no order-level truth at all")
    void recoverStuckFlattening_filledWithZeroExecutedQty_neverClosesRoutesToUnknownHalt() {
        var customRules = new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(0.0001), BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
        Position stuck = new Position();
        stuck.setId("pos-stuck-7"); stuck.setUserId("user1"); stuck.setCredentialId("cred1"); stuck.setSymbol("BTCUSDT");
        stuck.setStatus("FLATTENING"); stuck.setQuantity(BigDecimal.valueOf(1.0));
        when(positionRepo.findByCredentialIdAndStatus("cred1", "FLATTENING")).thenReturn(List.of(stuck));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(customRules);
        Order flattenOrder = new Order(); flattenOrder.setId("order1"); flattenOrder.setClientOrderId("tv-flat-1");
        when(omsOrderRepo.findByPositionIdAndOrderRoleOrderByCreatedAtDesc("pos-stuck-7", "FLATTEN")).thenReturn(List.of(flattenOrder));
        // status=FILLED with executedQty=0 -- exactly what this codebase's own real
        // BinanceBrokerAdapter produces from a malformed/field-missing response.
        when(adapter.getOrderStatusByClientOrderId(any(), any(), any(), eq("BTCUSDT"), eq("tv-flat-1")))
            .thenReturn(new com.tradevision.service.broker.dto.OrderStatusInfo("FILLED", BigDecimal.ZERO, null, "{}"));
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new com.tradevision.service.broker.dto.AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        RiskProfile riskProfile = new RiskProfile(); riskProfile.setId("rp1"); riskProfile.setCredentialId("cred1");
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(riskProfile));

        service.reconcileCredential(credential);

        // The actual claim under test: NEVER closed, regardless of status=FILLED.
        verify(mongoTemplate, never()).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && ("CLOSED_UNVERIFIED_PNL".equals(setDoc.getString("status"))
                || "NAKED_FLATTENED".equals(setDoc.getString("status")));
        }), eq(Position.class));
        // Routes to the same genuine UNKNOWN/halt path as no order-level truth at all.
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), eq("STUCK_FLATTENING_UNKNOWN"), any());
        verify(riskProfileRepo, org.mockito.Mockito.atLeastOnce()).findByCredentialId("cred1"); // confirms the halt path was actually reached (other phases of the pass read it too)
    }

    @Test
    @DisplayName("recoverStuckFlattening: FILLED status with a missing/null executedQty is NEVER treated as a confirmed full close either -- same UNKNOWN/halt routing as executedQty=0")
    void recoverStuckFlattening_filledWithNullExecutedQty_neverClosesRoutesToUnknownHalt() {
        var customRules = new com.tradevision.service.broker.dto.SymbolRules("BTCUSDT", "BTC", "USDT",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(0.0001), BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
        Position stuck = new Position();
        stuck.setId("pos-stuck-8"); stuck.setUserId("user1"); stuck.setCredentialId("cred1"); stuck.setSymbol("BTCUSDT");
        stuck.setStatus("FLATTENING"); stuck.setQuantity(BigDecimal.valueOf(1.0));
        when(positionRepo.findByCredentialIdAndStatus("cred1", "FLATTENING")).thenReturn(List.of(stuck));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(customRules);
        Order flattenOrder = new Order(); flattenOrder.setId("order1"); flattenOrder.setClientOrderId("tv-flat-1");
        when(omsOrderRepo.findByPositionIdAndOrderRoleOrderByCreatedAtDesc("pos-stuck-8", "FLATTEN")).thenReturn(List.of(flattenOrder));
        when(adapter.getOrderStatusByClientOrderId(any(), any(), any(), eq("BTCUSDT"), eq("tv-flat-1")))
            .thenReturn(new com.tradevision.service.broker.dto.OrderStatusInfo("FILLED", null, null, "{}"));
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new com.tradevision.service.broker.dto.AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        RiskProfile riskProfile = new RiskProfile(); riskProfile.setId("rp1"); riskProfile.setCredentialId("cred1");
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(riskProfile));

        service.reconcileCredential(credential);

        verify(mongoTemplate, never()).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && ("CLOSED_UNVERIFIED_PNL".equals(setDoc.getString("status"))
                || "NAKED_FLATTENED".equals(setDoc.getString("status")));
        }), eq(Position.class));
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), eq("STUCK_FLATTENING_UNKNOWN"), any());
    }

    @Test
    @DisplayName("enforceMaxHoldTime: a position whose plan's own maxHoldMinutes has been exceeded gets emergency-flattened -- the user's own explicit design (\"4-hour maximum holding reached? -> EXIT\"), the actual enforcement of \"the strategy timeframe and maximum holding time must be separate\"")
    void enforceMaxHoldTime_exceeded_emergencyFlattens() {
        Position position = new Position();
        position.setId("pos-hold-1"); position.setUserId("user1"); position.setCredentialId("cred1"); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setPlanId("plan1");
        position.setOpenedAt(LocalDateTime.now().minusMinutes(90)); // opened 90 minutes ago
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of(position));
        // Defensive: this same stub also feeds reconcileOpenPositions's own loop (same query,
        // same status) since this position has no ocoOrderListId -- unrelated to what this test
        // actually verifies, but needed to avoid an NPE from that other loop's own unstubbed
        // adapter.getSymbolRules() call, which doesn't null-check its own return value.
        when(adapter.getSymbolRules(any(), any())).thenReturn(BTC_RULES);
        when(adapter.getBalance(any(), any(), any())).thenReturn(List.of(
            new com.tradevision.service.broker.dto.AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        var plan = new com.tradevision.model.StrategyPlan();
        plan.setId("plan1"); plan.setName("Scalper"); plan.setMaxHoldMinutes(60); // max hold is only 60 minutes -- exceeded
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));

        service.reconcileCredential(credential);

        // P1-2 fix: max-hold-time is a routine, plan-configured exit, not a protection failure --
        // now goes through exitPosition() so a clean close doesn't halt the profile.
        verify(positionSafetyService).exitPosition(eq(credential), eq(adapter), any(), any(), eq(position), contains("MAX_HOLD_TIME"));
    }

    @Test
    @DisplayName("enforceMaxHoldTime: a position still within its plan's own maxHoldMinutes is left alone entirely")
    void enforceMaxHoldTime_notYetExceeded_leftAlone() {
        Position position = new Position();
        position.setId("pos-hold-2"); position.setUserId("user1"); position.setCredentialId("cred1"); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setPlanId("plan1");
        position.setOpenedAt(LocalDateTime.now().minusMinutes(10)); // opened only 10 minutes ago
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of(position));
        when(adapter.getSymbolRules(any(), any())).thenReturn(BTC_RULES);
        when(adapter.getBalance(any(), any(), any())).thenReturn(List.of(
            new com.tradevision.service.broker.dto.AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        var plan = new com.tradevision.model.StrategyPlan();
        plan.setId("plan1"); plan.setMaxHoldMinutes(60);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));

        service.reconcileCredential(credential);

        verify(positionSafetyService, never()).emergencyFlatten(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("enforceMaxHoldTime: a position with no planId at all (pre-multi-plan, or manually triggered) is left alone -- this rule is opt-in per plan, never a silent behavior change for a position that never asked for it")
    void enforceMaxHoldTime_noPlanId_leftAlone() {
        Position position = new Position();
        position.setId("pos-hold-3"); position.setUserId("user1"); position.setCredentialId("cred1"); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setPlanId(null);
        position.setOpenedAt(LocalDateTime.now().minusMinutes(90));
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of(position));
        when(adapter.getSymbolRules(any(), any())).thenReturn(BTC_RULES);
        when(adapter.getBalance(any(), any(), any())).thenReturn(List.of(
            new com.tradevision.service.broker.dto.AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));

        service.reconcileCredential(credential);

        verify(positionSafetyService, never()).emergencyFlatten(any(), any(), any(), any(), any(), any());
        verify(strategyPlanRepo, never()).findById(any());
    }

    @Test
    @DisplayName("enforceRiskEmergencyExit: when the account is halted and the position's own plan has exitOnRiskEmergency enabled, emergency-flattens it -- the user's own explicit design (\"Risk Emergency Exit\" in the plan's own Exit Policy), and genuinely new behavior since halt() itself never flattens anything on its own")
    void enforceRiskEmergencyExit_accountHaltedAndPlanOptedIn_flattens() {
        RiskProfile riskProfile = new RiskProfile();
        riskProfile.setId("rp1"); riskProfile.setCredentialId("cred1"); riskProfile.setTradingHalted(true);
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(riskProfile));
        Position position = new Position();
        position.setId("pos-risk-1"); position.setCredentialId("cred1"); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setPlanId("plan1");
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of(position));
        when(adapter.getSymbolRules(any(), any())).thenReturn(BTC_RULES);
        when(adapter.getBalance(any(), any(), any())).thenReturn(List.of(
            new com.tradevision.service.broker.dto.AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        var plan = new com.tradevision.model.StrategyPlan();
        plan.setId("plan1"); plan.setName("Scalper"); plan.setExitOnRiskEmergency(true);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));

        service.reconcileCredential(credential);

        verify(positionSafetyService).emergencyFlatten(eq(credential), eq(adapter), any(), any(), eq(position), contains("RISK_EMERGENCY_EXIT"));
    }

    @Test
    @DisplayName("enforceRiskEmergencyExit: account halted, but the plan has exitOnRiskEmergency explicitly disabled -- left alone, opt-out respected")
    void enforceRiskEmergencyExit_accountHaltedButPlanOptedOut_leftAlone() {
        RiskProfile riskProfile = new RiskProfile();
        riskProfile.setId("rp1"); riskProfile.setCredentialId("cred1"); riskProfile.setTradingHalted(true);
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(riskProfile));
        Position position = new Position();
        position.setId("pos-risk-2"); position.setCredentialId("cred1"); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setPlanId("plan1");
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of(position));
        when(adapter.getSymbolRules(any(), any())).thenReturn(BTC_RULES);
        when(adapter.getBalance(any(), any(), any())).thenReturn(List.of(
            new com.tradevision.service.broker.dto.AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        var plan = new com.tradevision.model.StrategyPlan();
        plan.setId("plan1"); plan.setExitOnRiskEmergency(false);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));

        service.reconcileCredential(credential);

        verify(positionSafetyService, never()).emergencyFlatten(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("enforceRiskEmergencyExit: account NOT halted -- never even queries for open positions, this check is a pure no-op")
    void enforceRiskEmergencyExit_accountNotHalted_neverChecks() {
        RiskProfile riskProfile = new RiskProfile();
        riskProfile.setId("rp1"); riskProfile.setCredentialId("cred1"); riskProfile.setTradingHalted(false); riskProfile.setAutoTradeHalted(false);
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(riskProfile));

        service.reconcileCredential(credential);

        verify(positionSafetyService, never()).emergencyFlatten(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("enforceEndOfSession: a position whose plan's session has ended and endOfSessionAction=CLOSE_POSITIONS gets emergency-flattened -- the user's own explicit design (\"End-of-session must be scoped to the Strategy Plan\")")
    void enforceEndOfSession_outsideSessionAndCloseConfigured_flattens() {
        Position position = new Position();
        position.setId("pos-session-1"); position.setCredentialId("cred1"); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setPlanId("plan1");
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of(position));
        when(adapter.getSymbolRules(any(), any())).thenReturn(BTC_RULES);
        when(adapter.getBalance(any(), any(), any())).thenReturn(List.of(
            new com.tradevision.service.broker.dto.AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        var plan = new com.tradevision.model.StrategyPlan();
        plan.setId("plan1"); plan.setName("Intraday"); plan.setSessionMode(com.tradevision.model.SessionMode.DAILY);
        plan.setEndOfSessionAction(com.tradevision.model.EndOfSessionAction.CLOSE_POSITIONS);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));
        when(strategyPlanService.isSessionConfigValid(plan)).thenReturn(true);
        when(strategyPlanService.isWithinSession(plan)).thenReturn(false); // session has ended

        service.reconcileCredential(credential);

        // P1-2 fix: end-of-session is a routine, plan-configured exit, not a protection failure --
        // now goes through exitPosition() so a clean close doesn't halt the profile.
        verify(positionSafetyService).exitPosition(eq(credential), eq(adapter), any(), any(), eq(position), contains("END_OF_SESSION"));
    }

    @Test
    @DisplayName("enforceEndOfSession: session has ended, but endOfSessionAction=KEEP_OPEN -- left alone entirely")
    void enforceEndOfSession_outsideSessionButKeepOpenConfigured_leftAlone() {
        Position position = new Position();
        position.setId("pos-session-2"); position.setCredentialId("cred1"); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setPlanId("plan1");
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of(position));
        when(adapter.getSymbolRules(any(), any())).thenReturn(BTC_RULES);
        when(adapter.getBalance(any(), any(), any())).thenReturn(List.of(
            new com.tradevision.service.broker.dto.AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        var plan = new com.tradevision.model.StrategyPlan();
        plan.setId("plan1"); plan.setSessionMode(com.tradevision.model.SessionMode.DAILY);
        plan.setEndOfSessionAction(com.tradevision.model.EndOfSessionAction.KEEP_OPEN);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));

        service.reconcileCredential(credential);

        verify(positionSafetyService, never()).emergencyFlatten(any(), any(), any(), any(), any(), any());
        // KEEP_OPEN is checked and short-circuits BEFORE isWithinSession is even called -- no
        // need to know the session boundary at all if the configured action wouldn't act on it anyway.
        verify(strategyPlanService, never()).isWithinSession(any());
    }

    @Test
    @DisplayName("enforceEndOfSession: still within the session -- left alone")
    void enforceEndOfSession_stillWithinSession_leftAlone() {
        Position position = new Position();
        position.setId("pos-session-3"); position.setCredentialId("cred1"); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setPlanId("plan1");
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of(position));
        when(adapter.getSymbolRules(any(), any())).thenReturn(BTC_RULES);
        when(adapter.getBalance(any(), any(), any())).thenReturn(List.of(
            new com.tradevision.service.broker.dto.AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        var plan = new com.tradevision.model.StrategyPlan();
        plan.setId("plan1"); plan.setSessionMode(com.tradevision.model.SessionMode.DAILY);
        plan.setEndOfSessionAction(com.tradevision.model.EndOfSessionAction.CLOSE_POSITIONS);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));
        when(strategyPlanService.isWithinSession(plan)).thenReturn(true); // still trading hours

        service.reconcileCredential(credential);

        verify(positionSafetyService, never()).emergencyFlatten(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("enforceEndOfSession: a plan left at the default ALWAYS_ON session mode never even calls isWithinSession -- 24/7 is a true no-op, not a session check that always happens to pass")
    void enforceEndOfSession_alwaysOnDefault_neverChecksSession() {
        Position position = new Position();
        position.setId("pos-session-4"); position.setCredentialId("cred1"); position.setSymbol("BTCUSDT");
        position.setStatus("OPEN"); position.setPlanId("plan1");
        when(positionRepo.findByCredentialIdAndStatus("cred1", "OPEN")).thenReturn(List.of(position));
        when(adapter.getSymbolRules(any(), any())).thenReturn(BTC_RULES);
        when(adapter.getBalance(any(), any(), any())).thenReturn(List.of(
            new com.tradevision.service.broker.dto.AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        var plan = new com.tradevision.model.StrategyPlan();
        plan.setId("plan1"); // sessionMode left at its own default (ALWAYS_ON)
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));

        service.reconcileCredential(credential);

        verify(positionSafetyService, never()).emergencyFlatten(any(), any(), any(), any(), any(), any());
        verify(strategyPlanService, never()).isWithinSession(any());
    }

    /**
     * Review finding ("OCO placement success + local persistence failure still has a residual
     * crash window" -- external review, eighteenth pass, P0, full context in
     * createOrphanForOco's own javadoc): the actual test proving the fix -- the safety-net
     * OrphanedOco record is now created immediately after the exchange OCO call succeeds, in a
     * real InOrder sequence BEFORE OMS recording (orderService.recordOcoPlacementResult) runs,
     * not after it. Previously, a crash or exception during that OMS step (or anything else
     * between the exchange call and the old, later orphan-creation point) would have left zero
     * durable trace of a real, active exchange OCO.
     */
    @Test
    @DisplayName("createPositionForLateDiscoveredFill: the safety-net OrphanedOco record is created IMMEDIATELY after the exchange OCO call succeeds, strictly before OMS recording -- closing the crash window between the two")
    void lateDiscoveredFill_ocoSuccess_ordersOrphanCreationBeforeOmsRecording() {
        when(slotReservationService.reserve(any(), anyInt(), any(), anyBoolean())).thenReturn(com.tradevision.service.PositionSlotReservationService.SlotReserveResult.reserved("test-slot-id"));
        when(exposureReservationService.reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new com.tradevision.service.ExposureReservationService.ExposureReserveResult(true, null, "test-reservation-id"));
        when(adapter.getFillsForOrder(any(), any(), any(), any(), any())).thenReturn(
            List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.001), "BTC")));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        when(positionSafetyService.computeNetQuantity(any(), any(), any())).thenReturn(
            new PositionSafetyService.FillAccountingResult(BigDecimal.valueOf(0.999), BigDecimal.ZERO));
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "oco-new-999", "{}", null));
        var fakeOmsOrder = new Order();
        fakeOmsOrder.setId("oms-order-1");
        when(orderService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(fakeOmsOrder);
        when(orderService.recordOcoPlacementResult(any(), any())).thenThrow(new RuntimeException("simulated OMS recording failure"));
        when(orphanedOcoRepo.save(any())).thenAnswer(inv -> { // a real repository assigns the id on save
            var o = (com.tradevision.model.OrphanedOco) inv.getArgument(0); o.setId("orphan-1"); return o; });

        var order = new Order();
        order.setUserId("user1");
        order.setSymbol("BTCUSDT");
        order.setBrokerOrderId("entry-1");
        order.setSide("BUY"); // P0 fix regression: this method now requires side == BUY -- these tests all test genuine BUY-entry scenarios
        order.setSignalId("sig1");
        order.setTakeProfitPrice(BigDecimal.valueOf(110));
        order.setStopLossTriggerPrice(BigDecimal.valueOf(90));

        service.createPositionForLateDiscoveredFill(credential, adapter, "key", "secret", order, BigDecimal.valueOf(1.0), 1L);

        // The actual guarantee under test: the orphan for this exact OCO exists, proving
        // createOrphanForOco ran and completed BEFORE recordOcoPlacementResult's own simulated
        // throw above -- if creation happened after (the old, pre-fix ordering), this save would
        // never have been reached at all once the throw propagated.
        verify(orphanedOcoRepo).save(argThat(o -> "oco-new-999".equals(o.getOcoOrderListId())));
    }

    /**
     * Review finding ("Protective OCO recovery still has a path with no durable recovery
     * record" -- external review, twenty-first pass, P0, full context in
     * haltForProtectionAttemptPersistenceFailure's own javadoc): the actual test proving the
     * LIVE-specific halt -- when the pre-submission ProtectionAttempt record can't be persisted,
     * the OCO exchange call must never be made at all, the credential must be halted, and a
     * critical incident must be raised.
     */
    @Test
    @DisplayName("createPositionForLateDiscoveredFill: for a LIVE credential, a failed ProtectionAttempt persistence HALTS before the OCO exchange call is ever made")
    void protectionAttemptPersistenceFails_liveCredential_haltsBeforeOcoCall() {
        credential.setMode(BrokerMode.LIVE);
        when(slotReservationService.reserve(any(), anyInt(), any(), anyBoolean())).thenReturn(com.tradevision.service.PositionSlotReservationService.SlotReserveResult.reserved("test-slot-id"));
        when(exposureReservationService.reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new com.tradevision.service.ExposureReservationService.ExposureReserveResult(true, null, "test-reservation-id"));
        when(adapter.getFillsForOrder(any(), any(), any(), any(), any())).thenReturn(
            List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.001), "BTC")));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.LIVE)).thenReturn(BTC_RULES);
        when(positionSafetyService.computeNetQuantity(any(), any(), any())).thenReturn(
            new PositionSafetyService.FillAccountingResult(BigDecimal.valueOf(0.999), BigDecimal.ZERO));
        when(protectionAttemptRepo.save(any())).thenThrow(new RuntimeException("simulated database failure"));
        var profile = new RiskProfile();
        profile.setId("profile1"); profile.setCredentialId("cred1"); profile.setTradingHalted(false);
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(profile));

        var order = new Order();
        order.setUserId("user1");
        order.setSymbol("BTCUSDT");
        order.setBrokerOrderId("entry-1");
        order.setSide("BUY"); // P0 fix regression: this method now requires side == BUY -- these tests all test genuine BUY-entry scenarios
        order.setSignalId("sig1");
        order.setTakeProfitPrice(BigDecimal.valueOf(110));
        order.setStopLossTriggerPrice(BigDecimal.valueOf(90));

        service.createPositionForLateDiscoveredFill(credential, adapter, "key", "secret", order, BigDecimal.valueOf(1.0), 1L);

        verify(adapter, never()).placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(incidentService).raiseCritical(any(), eq("cred1"), any(), isNull(), eq("BTCUSDT"),
            eq("PROTECTION_ATTEMPT_PERSISTENCE_FAILED_LIVE_HALT"), any());
        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && Boolean.TRUE.equals(setDoc.getBoolean("tradingHalted"));
        }), eq(RiskProfile.class));
    }

    /**
     * Review finding ("OCO persistence has a second crash window" -- external review,
     * twenty-second pass, P1, full context in createOrphanForOco's own updated javadoc): the
     * actual test proving the escalation -- unlike the P0-3 test above, the exchange OCO call
     * DOES succeed here (there is nothing left to prevent by this point), and the fix is purely
     * about making sure a human learns about it immediately.
     */
    @Test
    @DisplayName("createPositionForLateDiscoveredFill: for a LIVE credential, a failed OrphanedOco persistence (AFTER the OCO already succeeded on the exchange) escalates loudly rather than silently continuing")
    void orphanedOcoPersistenceFails_liveCredential_escalates() {
        credential.setMode(BrokerMode.LIVE);
        when(slotReservationService.reserve(any(), anyInt(), any(), anyBoolean())).thenReturn(com.tradevision.service.PositionSlotReservationService.SlotReserveResult.reserved("test-slot-id"));
        when(exposureReservationService.reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new com.tradevision.service.ExposureReservationService.ExposureReserveResult(true, null, "test-reservation-id"));
        when(adapter.getFillsForOrder(any(), any(), any(), any(), any())).thenReturn(
            List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.001), "BTC")));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.LIVE)).thenReturn(BTC_RULES);
        when(positionSafetyService.computeNetQuantity(any(), any(), any())).thenReturn(
            new PositionSafetyService.FillAccountingResult(BigDecimal.valueOf(0.999), BigDecimal.ZERO));
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "oco-live-1", "{}", null));
        when(protectionAttemptRepo.save(any())).thenAnswer(inv -> { // real repo assigns the id; LIVE treats a null id as "not persisted"
            var a = (com.tradevision.model.ProtectionAttempt) inv.getArgument(0); a.setId("attempt-1"); return a; });
        when(orphanedOcoRepo.save(any())).thenThrow(new RuntimeException("simulated database failure"));
        when(credentialRepo.findById("cred1")).thenReturn(Optional.of(credential));
        var profile = new RiskProfile();
        profile.setId("profile1"); profile.setCredentialId("cred1"); profile.setTradingHalted(false);
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(profile));

        var order = new Order();
        order.setUserId("user1");
        order.setSymbol("BTCUSDT");
        order.setBrokerOrderId("entry-1");
        order.setSide("BUY"); // P0 fix regression: this method now requires side == BUY -- these tests all test genuine BUY-entry scenarios
        order.setSignalId("sig1");
        order.setTakeProfitPrice(BigDecimal.valueOf(110));
        order.setStopLossTriggerPrice(BigDecimal.valueOf(90));

        service.createPositionForLateDiscoveredFill(credential, adapter, "key", "secret", order, BigDecimal.valueOf(1.0), 1L);

        // The exchange call DID happen -- unlike P0-3, there was nothing left to prevent.
        verify(adapter).placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(incidentService).raiseCritical(any(), eq("cred1"), any(), isNull(), eq("BTCUSDT"),
            eq("ORPHANED_OCO_PERSISTENCE_FAILED_LIVE_HALT"), any());
        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && Boolean.TRUE.equals(setDoc.getBoolean("tradingHalted"));
        }), eq(RiskProfile.class));
    }

    /**
     * Review finding ("Some repository queries return unlimited lists" -- external review,
     * thirty-eighth pass, P2, the review's own explicit example naming this exact query, full
     * context in ProtectionAttemptRepository's own updated method javadoc): the actual test
     * proving the fix -- recoverStuckProtectionAttempts genuinely queries with a bounded
     * Pageable, not an unbounded list.
     */
    @Test
    @DisplayName("recoverStuckProtectionAttempts: queries the repository with a bounded Pageable, not an unbounded list -- a large backlog on one credential cannot make one reconciliation cycle process every stuck record across all of history")
    void recoverStuckProtectionAttempts_queriesWithBoundedPageable() {
        when(protectionAttemptRepo.findByStatusAndCreatedAtBefore(eq("SUBMITTING"), any(), any())).thenReturn(List.of());

        service.reconcileCredential(credential);

        ArgumentCaptor<org.springframework.data.domain.Pageable> pageableCaptor = ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(protectionAttemptRepo).findByStatusAndCreatedAtBefore(eq("SUBMITTING"), any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    @DisplayName("recoverOrphanedOcos: queries the repository with a bounded Pageable too, same fix as recoverStuckProtectionAttempts")
    void recoverOrphanedOcos_queriesWithBoundedPageable() {
        when(orphanedOcoRepo.findByCredentialIdAndResolvedFalse(eq("cred1"), any())).thenReturn(List.of());

        service.reconcileCredential(credential);

        ArgumentCaptor<org.springframework.data.domain.Pageable> pageableCaptor = ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(orphanedOcoRepo).findByCredentialIdAndResolvedFalse(eq("cred1"), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(200);
    }

    /**
     * Review finding ("OCO persistence still has an unavoidable crash window" -- external
     * review, nineteenth pass, P1, full context in ProtectionAttempt's own class javadoc): the
     * actual tests proving recoverStuckProtectionAttempts's three real, distinct outcomes.
     */
    @Test
    @DisplayName("recoverStuckProtectionAttempts: a stuck attempt whose position already has SOME OCO recorded is resolved as ACTIVE -- the real work already completed, whatever crash (if any) happened after")
    void recoverStuckProtectionAttempts_positionAlreadyHasOco_resolvesActive() {
        var position = new Position();
        position.setId("pos1"); position.setSymbol("BTCUSDT"); position.setStatus("OPEN");
        position.setOcoOrderListId("some-real-oco-id");
        var attempt = new com.tradevision.model.ProtectionAttempt();
        attempt.setId("attempt1"); attempt.setCredentialId("cred1"); attempt.setPositionId("pos1");
        attempt.setSymbol("BTCUSDT"); attempt.setListClientOrderId("client-oco-1");
        when(protectionAttemptRepo.findByStatusAndCreatedAtBefore(eq("SUBMITTING"), any(), any())).thenReturn(List.of(attempt));
        when(positionRepo.findById("pos1")).thenReturn(Optional.of(position));
        // Review finding ("Stuck ProtectionAttempt considers 'ANY OCO' sufficient" -- external
        // review, thirty-fifth pass, P0, full context in this branch's own updated comment in
        // production code): the fix now verifies the position's own recorded OCO is genuinely
        // still active before trusting it -- this test's own name is specifically about that
        // "already has SOME OCO" case being genuinely, verifiably active, so it must stub this.
        when(adapter.getOcoStatus(any(), any(), any(), eq("some-real-oco-id")))
            .thenReturn(new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "EXECUTING", List.of(), "{}"));

        service.reconcileCredential(credential);

        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && "ACTIVE".equals(setDoc.getString("status"));
        }), eq(com.tradevision.model.ProtectionAttempt.class));
        verify(adapter, never()).getOcoStatusByClientOrderId(any(), any(), any(), any());
    }

    /**
     * Review finding ("Stuck ProtectionAttempt considers 'ANY OCO' sufficient" -- external
     * review, thirty-fifth pass, P0, the review's own explicit scenario, full context in this
     * branch's own updated comment): the actual test proving the fix -- a position's recorded
     * OCO that's genuinely stale (ALL_DONE on the exchange, from an old, already-finished OCO)
     * must NOT be trusted as proof of protection. Falls through to checking THIS attempt's own
     * client id directly instead.
     */
    @Test
    @DisplayName("recoverStuckProtectionAttempts: the position's recorded OCO is STALE (ALL_DONE on the exchange, from an old finished OCO) -- NOT trusted as proof of protection, falls through to checking this attempt's own client id directly")
    void recoverStuckProtectionAttempts_positionOcoIsStale_fallsThroughToOwnClientIdCheck() {
        var position = new Position();
        position.setId("pos1"); position.setSymbol("BTCUSDT"); position.setStatus("OPEN");
        position.setOcoOrderListId("old-finished-oco-id"); // stale -- from an OCO that already completed
        var attempt = new com.tradevision.model.ProtectionAttempt();
        attempt.setId("attempt1"); attempt.setCredentialId("cred1"); attempt.setPositionId("pos1");
        attempt.setSymbol("BTCUSDT"); attempt.setListClientOrderId("client-oco-new");
        when(protectionAttemptRepo.findByStatusAndCreatedAtBefore(eq("SUBMITTING"), any(), any())).thenReturn(List.of(attempt));
        when(positionRepo.findById("pos1")).thenReturn(Optional.of(position));
        when(adapter.getOcoStatus(any(), any(), any(), eq("old-finished-oco-id")))
            .thenReturn(new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "ALL_DONE", List.of(), "{}"));
        // This attempt's OWN client id genuinely has a real, active OCO on the exchange -- the
        // scenario the review names: a crash happened before this NEW OCO got recorded on the
        // position, which still shows the OLD, now-finished one.
        when(adapter.getOcoStatusByClientOrderId(any(), any(), any(), eq("client-oco-new")))
            .thenReturn(new com.tradevision.service.broker.dto.OcoStatusInfo("test-oco-id", "EXECUTING", List.of(
                new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("999", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(51000), BigDecimal.ZERO, BigDecimal.valueOf(1.0))), "{}"));

        service.reconcileCredential(credential);

        // The actual claim under test: getOcoStatusByClientOrderId (this attempt's OWN identity
        // check) IS reached, not skipped -- proving the stale position-level OCO was correctly
        // NOT trusted as sufficient proof on its own.
        verify(adapter).getOcoStatusByClientOrderId(any(), any(), any(), eq("client-oco-new"));
        // And since the exchange DOES have a real, active OCO for this attempt's own client id
        // but the position has no matching record of it, this escalates per the existing,
        // already-correct "crash-window" branch -- not silently resolved as if nothing were wrong.
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), eq("PROTECTION_ATTEMPT_STUCK_WITH_REAL_OCO"), any());
    }

    @Test
    @DisplayName("recoverStuckProtectionAttempts: the exchange has no record of this client id at all -- resolved as FAILED, nothing to reconcile")
    void recoverStuckProtectionAttempts_exchangeHasNoRecord_resolvesFailed() {
        var position = new Position();
        position.setId("pos1"); position.setSymbol("BTCUSDT"); position.setStatus("CLOSED");
        var attempt = new com.tradevision.model.ProtectionAttempt();
        attempt.setId("attempt1"); attempt.setCredentialId("cred1"); attempt.setPositionId("pos1");
        attempt.setSymbol("BTCUSDT"); attempt.setListClientOrderId("client-oco-1");
        when(protectionAttemptRepo.findByStatusAndCreatedAtBefore(eq("SUBMITTING"), any(), any())).thenReturn(List.of(attempt));
        when(positionRepo.findById("pos1")).thenReturn(Optional.of(position));
        when(adapter.getOcoStatusByClientOrderId(any(), any(), any(), eq("client-oco-1")))
            .thenReturn(new com.tradevision.service.broker.dto.OcoStatusInfo(null, "REJECT", List.of(), "{}"));

        service.reconcileCredential(credential);

        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && "FAILED".equals(setDoc.getString("status"));
        }), eq(com.tradevision.model.ProtectionAttempt.class));
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), eq("PROTECTION_ATTEMPT_STUCK_WITH_REAL_OCO"), any());
    }

    /**
     * Review finding ("Active orphan OCO still requires manual action" -- external review,
     * thirty-sixth pass, P0, full context in this branch's own updated comment in production
     * code): this test's own original scenario (position OPEN, unprotected, a real OCO exists
     * under this attempt's own client id) now correctly auto-attaches instead of escalating --
     * exactly the review's own required "GET OCO -> get ID -> attach -> verify ACTIVE" flow,
     * closed without needing a human. Renamed and rewritten from this test's own prior name
     * ("...escalates") to reflect that.
     */
    @Test
    @DisplayName("recoverStuckProtectionAttempts: the exchange confirms a real, active OCO exists for this client id, and the position is still OPEN and unprotected -- auto-attaches it, resolves ACTIVE, never escalates to a human")
    void recoverStuckProtectionAttempts_exchangeConfirmsRealOco_autoAttaches() {
        var position = new Position();
        position.setId("pos1"); position.setSymbol("BTCUSDT"); position.setStatus("OPEN"); position.setOcoOrderListId(null);
        position.setQuantity(BigDecimal.valueOf(1.0));
        var attempt = new com.tradevision.model.ProtectionAttempt();
        attempt.setId("attempt1"); attempt.setUserId("user1"); attempt.setCredentialId("cred1");
        attempt.setPositionId("pos1"); attempt.setSymbol("BTCUSDT"); attempt.setListClientOrderId("client-oco-1");
        when(protectionAttemptRepo.findByStatusAndCreatedAtBefore(eq("SUBMITTING"), any(), any())).thenReturn(List.of(attempt));
        when(positionRepo.findById("pos1")).thenReturn(Optional.of(position));
        when(adapter.getOcoStatusByClientOrderId(any(), any(), any(), eq("client-oco-1")))
            .thenReturn(new com.tradevision.service.broker.dto.OcoStatusInfo("real-order-list-id", "EXECUTING",
                List.of(new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("leg1", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(70000), BigDecimal.ZERO, BigDecimal.valueOf(1.0))),
                "{}"));

        service.reconcileCredential(credential);

        // The actual claim under test: auto-attach fired (the position's own ocoOrderListId is
        // now set to the real exchange id), resolved ACTIVE, and no human escalation happened.
        assertThat(position.getOcoOrderListId()).isEqualTo("real-order-list-id");
        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && "ACTIVE".equals(setDoc.getString("status"));
        }), eq(com.tradevision.model.ProtectionAttempt.class));
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), eq("PROTECTION_ATTEMPT_STUCK_WITH_REAL_OCO"), any());
    }

    /**
     * Review finding ("Recovery auto-attach needs quantity verification" -- external review,
     * thirty-eighth pass, P1, the review's own explicit example: "the actual exchange OCO may
     * protect 0.999 BTC while the Position says 1.000 BTC because of: base asset fee, step-size
     * rounding, exchange quantity normalization"): the actual test proving the fix -- when the
     * recovered OCO's own real SELL leg quantity (0.999) genuinely differs from the position's
     * own recorded quantity (1.000), the ATTACHED protectedQuantity must be the exchange's own
     * real figure (0.999), not silently substituted with the position's own recorded amount.
     */
    @Test
    @DisplayName("recoverStuckProtectionAttempts: the recovered OCO's own real SELL leg quantity (0.999) differs from the position's own recorded quantity (1.000) -- auto-attach uses the exchange's own real figure, not the position's")
    void recoverStuckProtectionAttempts_autoAttachQuantityMismatch_usesRealExchangeQuantity() {
        var position = new Position();
        position.setId("pos1"); position.setSymbol("BTCUSDT"); position.setStatus("OPEN"); position.setOcoOrderListId(null);
        position.setQuantity(BigDecimal.valueOf(1.000)); // the position's own recorded figure
        var attempt = new com.tradevision.model.ProtectionAttempt();
        attempt.setId("attempt1"); attempt.setUserId("user1"); attempt.setCredentialId("cred1");
        attempt.setPositionId("pos1"); attempt.setSymbol("BTCUSDT"); attempt.setListClientOrderId("client-oco-1");
        when(protectionAttemptRepo.findByStatusAndCreatedAtBefore(eq("SUBMITTING"), any(), any())).thenReturn(List.of(attempt));
        when(positionRepo.findById("pos1")).thenReturn(Optional.of(position));
        when(adapter.getOcoStatusByClientOrderId(any(), any(), any(), eq("client-oco-1")))
            .thenReturn(new com.tradevision.service.broker.dto.OcoStatusInfo("real-order-list-id", "EXECUTING",
                List.of(
                    // Both legs carry the exchange's own real quantity (0.999), reflecting base-asset fee/step-size normalization.
                    new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("leg1", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(70000), BigDecimal.ZERO, BigDecimal.valueOf(0.999)),
                    new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("leg2", "SELL", "STOP_LOSS_LIMIT", "NEW", BigDecimal.valueOf(65000), BigDecimal.ZERO, BigDecimal.valueOf(0.999))),
                "{}"));

        service.reconcileCredential(credential);

        // The actual claim under test: protectedQuantity persisted is 0.999 (the exchange's real
        // figure), never 1.000 (the position's own recorded, but not necessarily accurate, figure).
        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && setDoc.get("protectedQuantity") != null
                && BigDecimal.valueOf(0.999).compareTo(new BigDecimal(setDoc.get("protectedQuantity").toString())) == 0;
        }), eq(Position.class));
        assertThat(position.getProtectedQuantity()).isEqualByComparingTo("0.999");
    }

    /**
     * Review finding, same context as the test above: when no SELL leg carries a usable origQty
     * at all (a genuinely malformed/incomplete exchange response), the fallback to the
     * position's own recorded quantity still applies -- protecting SOMETHING (the position's own
     * best-known figure) rather than silently protecting zero.
     */
    @Test
    @DisplayName("recoverStuckProtectionAttempts: no SELL leg carries a usable origQty at all -- falls back to the position's own recorded quantity rather than protecting zero")
    void recoverStuckProtectionAttempts_noUsableLegOrigQty_fallsBackToPositionQuantity() {
        var position = new Position();
        position.setId("pos1"); position.setSymbol("BTCUSDT"); position.setStatus("OPEN"); position.setOcoOrderListId(null);
        position.setQuantity(BigDecimal.valueOf(1.0));
        var attempt = new com.tradevision.model.ProtectionAttempt();
        attempt.setId("attempt1"); attempt.setUserId("user1"); attempt.setCredentialId("cred1");
        attempt.setPositionId("pos1"); attempt.setSymbol("BTCUSDT"); attempt.setListClientOrderId("client-oco-1");
        when(protectionAttemptRepo.findByStatusAndCreatedAtBefore(eq("SUBMITTING"), any(), any())).thenReturn(List.of(attempt));
        when(positionRepo.findById("pos1")).thenReturn(Optional.of(position));
        when(adapter.getOcoStatusByClientOrderId(any(), any(), any(), eq("client-oco-1")))
            .thenReturn(new com.tradevision.service.broker.dto.OcoStatusInfo("real-order-list-id", "EXECUTING",
                // origQty is zero for every leg -- genuinely no usable figure from the exchange.
                List.of(new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("leg1", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(70000), BigDecimal.ZERO, BigDecimal.ZERO)),
                "{}"));

        service.reconcileCredential(credential);

        assertThat(position.getProtectedQuantity()).isEqualByComparingTo("1.0");
    }

    /**
     * Review finding, same context as the test above: the genuine escalation case -- the
     * position can no longer safely take this OCO (already closed), AND the auto-cancel attempt
     * itself fails to be confirmed. Only THEN does this escalate to a human -- the review's own
     * explicit "final branch, not the normal recovery branch."
     */
    @Test
    @DisplayName("recoverStuckProtectionAttempts: the position is already CLOSED (can't safely take the OCO), and auto-cancel itself cannot be confirmed -- only then escalates to a human, as the genuine last resort")
    void recoverStuckProtectionAttempts_positionClosedAndCancelUnconfirmed_escalatesAsLastResort() {
        var position = new Position();
        position.setId("pos1"); position.setSymbol("BTCUSDT"); position.setStatus("CLOSED"); position.setOcoOrderListId(null);
        var attempt = new com.tradevision.model.ProtectionAttempt();
        attempt.setId("attempt1"); attempt.setUserId("user1"); attempt.setCredentialId("cred1");
        attempt.setPositionId("pos1"); attempt.setSymbol("BTCUSDT"); attempt.setListClientOrderId("client-oco-1");
        when(protectionAttemptRepo.findByStatusAndCreatedAtBefore(eq("SUBMITTING"), any(), any())).thenReturn(List.of(attempt));
        when(positionRepo.findById("pos1")).thenReturn(Optional.of(position));
        when(adapter.getOcoStatusByClientOrderId(any(), any(), any(), eq("client-oco-1")))
            .thenReturn(new com.tradevision.service.broker.dto.OcoStatusInfo("real-order-list-id", "EXECUTING",
                List.of(new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("leg1", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(70000), BigDecimal.ZERO, BigDecimal.valueOf(1.0))),
                "{}"));
        // The auto-cancel attempt itself fails outright.
        when(adapter.cancelOco(any(), any(), any(), any(), eq("real-order-list-id")))
            .thenThrow(new RuntimeException("simulated exchange error"));

        service.reconcileCredential(credential);

        // The actual claim under test: escalates, but only after the position-can't-take-it
        // check and the auto-cancel attempt both genuinely failed -- not immediately.
        verify(adapter).cancelOco(any(), any(), any(), any(), eq("real-order-list-id"));
        verify(incidentService).raiseCritical(eq("user1"), eq("cred1"), eq("pos1"), isNull(), eq("BTCUSDT"),
            eq("PROTECTION_ATTEMPT_STUCK_WITH_REAL_OCO"), any());
        // Stays SUBMITTING -- never resolved, since the dangerous condition (a real, untracked
        // OCO) is not actually gone.
        verify(mongoTemplate, never()).updateFirst(any(), any(), eq(com.tradevision.model.ProtectionAttempt.class));
    }

    /**
     * Review finding, same context as the tests above: the auto-cancel success path -- the
     * position can't safely take the OCO, but the cancel itself is confirmed by the exchange.
     * Resolves as FAILED (protection not active), never escalates.
     */
    @Test
    @DisplayName("recoverStuckProtectionAttempts: the position is already CLOSED, but auto-cancel succeeds and is confirmed by the exchange -- resolves FAILED, never escalates to a human")
    void recoverStuckProtectionAttempts_positionClosedButCancelConfirmed_resolvesFailedWithoutEscalating() {
        var position = new Position();
        position.setId("pos1"); position.setSymbol("BTCUSDT"); position.setStatus("CLOSED"); position.setOcoOrderListId(null);
        var attempt = new com.tradevision.model.ProtectionAttempt();
        attempt.setId("attempt1"); attempt.setUserId("user1"); attempt.setCredentialId("cred1");
        attempt.setPositionId("pos1"); attempt.setSymbol("BTCUSDT"); attempt.setListClientOrderId("client-oco-1");
        when(protectionAttemptRepo.findByStatusAndCreatedAtBefore(eq("SUBMITTING"), any(), any())).thenReturn(List.of(attempt));
        when(positionRepo.findById("pos1")).thenReturn(Optional.of(position));
        when(adapter.getOcoStatusByClientOrderId(any(), any(), any(), eq("client-oco-1")))
            .thenReturn(new com.tradevision.service.broker.dto.OcoStatusInfo("real-order-list-id", "EXECUTING",
                List.of(new com.tradevision.service.broker.dto.OcoStatusInfo.Leg("leg1", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(70000), BigDecimal.ZERO, BigDecimal.valueOf(1.0))),
                "{}"));
        when(adapter.cancelOco(any(), any(), any(), any(), eq("real-order-list-id")))
            .thenReturn(new com.tradevision.service.broker.dto.OcoOrderResult(true, "real-order-list-id", "{}", null));
        when(adapter.getOcoStatus(any(), any(), any(), eq("real-order-list-id")))
            .thenReturn(new com.tradevision.service.broker.dto.OcoStatusInfo("real-order-list-id", "ALL_DONE", List.of(), "{}"));

        service.reconcileCredential(credential);

        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && "FAILED".equals(setDoc.getString("status"));
        }), eq(com.tradevision.model.ProtectionAttempt.class));
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), eq("PROTECTION_ATTEMPT_STUCK_WITH_REAL_OCO"), any());
    }

    // ── P0 fix: phantom-position loop from emergency-flatten SELL orders being misclassified
    // as late-discovered BUY entries (production incident, full context in
    // OrderRepository.findByCredentialIdAndSideAndStatusAndCreatedAtAfterOrderByCreatedAtAsc's
    // and createPositionForLateDiscoveredFill's own updated javadoc) ────────────────────────

    /** TEST A (required test A): a genuine FILLED BUY order with no existing Position IS
     *  eligible for late-discovered entry creation -- confirms the new side guard does not
     *  regress the legitimate case it must continue to allow. */
    @Test
    @DisplayName("createPositionForLateDiscoveredFill: a genuine FILLED BUY order with no existing position creates a late-discovered entry Position")
    void createPositionForLateDiscoveredFill_buyOrder_createsPosition() {
        when(slotReservationService.reserve(any(), anyInt(), any(), anyBoolean())).thenReturn(com.tradevision.service.PositionSlotReservationService.SlotReserveResult.reserved("test-slot-id"));
        when(exposureReservationService.reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new com.tradevision.service.ExposureReservationService.ExposureReserveResult(true, null, "test-reservation-id"));
        when(adapter.getFillsForOrder(any(), any(), any(), any(), any())).thenReturn(
            List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.001), "BTC")));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        when(positionSafetyService.computeNetQuantity(any(), any(), any())).thenReturn(
            new PositionSafetyService.FillAccountingResult(BigDecimal.valueOf(0.999), BigDecimal.ZERO));

        var order = new Order();
        order.setUserId("user1");
        order.setSymbol("BTCUSDT");
        order.setBrokerOrderId("real-buy-1");
        order.setSide("BUY");
        // No SL/TP set — takes the method's own early emergency-flatten return, same pattern as
        // this file's other createPositionForLateDiscoveredFill tests; irrelevant to what this
        // test verifies (that a BUY order reaches Position creation at all).

        service.createPositionForLateDiscoveredFill(credential, adapter, "key", "secret", order, BigDecimal.valueOf(1.0), 1L);

        verify(positionRepo, atLeastOnce()).save(any(Position.class));
    }

    /** TEST B (required test B): a genuine FILLED SELL order must NEVER create a Position --
     *  the actual, direct guard this whole fix is about. Also proves the guard fires before
     *  ANY side-effecting work (no slot reservation, no exposure reservation, no fill-ledger
     *  interaction at all) -- satisfies TEST G's own "no fill-ledger interaction either"
     *  requirement in the same assertion set, since a SELL reaching this method must be an
     *  inert no-op in every respect, not just with respect to Position creation specifically. */
    @Test
    @DisplayName("createPositionForLateDiscoveredFill: a FILLED SELL order NEVER creates a Position, and touches nothing else either -- the core P0 fix")
    void createPositionForLateDiscoveredFill_sellOrder_neverCreatesPosition() {
        var order = new Order();
        order.setUserId("user1");
        order.setSymbol("BTCUSDT");
        order.setBrokerOrderId("real-sell-1");
        order.setSide("SELL");

        service.createPositionForLateDiscoveredFill(credential, adapter, "key", "secret", order, BigDecimal.valueOf(0.001), 1L);

        verify(positionRepo, never()).save(any(Position.class));
        verify(slotReservationService, never()).reserve(any(), anyInt(), any(), anyBoolean());
        verify(exposureReservationService, never()).reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(fillLedgerService, never()).recordFills(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(adapter, never()).getFillsForOrder(any(), any(), any(), any(), any());
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), any(), any());
    }

    /** Same guard, exercised with a null side too — an Order that somehow never had its side
     *  set at all must be treated exactly like a non-BUY order (rejected), not like a BUY by
     *  default. Guards against a future refactor accidentally loosening "!= BUY" to something
     *  that treats null/unset as acceptable. */
    @Test
    @DisplayName("createPositionForLateDiscoveredFill: an order with side == null is also rejected, not defaulted to BUY")
    void createPositionForLateDiscoveredFill_nullSide_neverCreatesPosition() {
        var order = new Order();
        order.setUserId("user1");
        order.setSymbol("BTCUSDT");
        order.setBrokerOrderId("real-unknown-side-1");
        // side deliberately left unset (null)

        service.createPositionForLateDiscoveredFill(credential, adapter, "key", "secret", order, BigDecimal.valueOf(0.001), 1L);

        verify(positionRepo, never()).save(any(Position.class));
    }

    /** TEST C (required test C) + TEST D (required test D), combined: exercises the actual
     *  QUERY-level fix (not just the method-level guard) through the real, public
     *  reconcileCredential() entry point -- proving a SELL order can never even reach
     *  createPositionForLateDiscoveredFill in the first place, and that this holds true across
     *  repeated reconciliation passes (TEST D's own "discovered repeatedly, zero phantom
     *  positions" requirement), not just once. This is the closest a unit test can come to
     *  reproducing the actual production loop: the side-filtered repository query is mocked to
     *  behave exactly as the real derived query now does -- it simply never returns a SELL
     *  order, by construction, regardless of how many times reconciliation runs. */
    @Test
    @DisplayName("reconcileCredential: an emergency-flatten SELL order is never discovered as a late entry, across repeated reconciliation passes -- the actual production loop, closed at the query level")
    void reconcileCredential_flattenSellNeverRediscoveredAsEntry_repeatedPasses() {
        when(adapter.getType()).thenReturn(BrokerType.BINANCE);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adapters", List.of(adapter));
        when(omsOrderRepo.findByCredentialIdAndStatusInOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());
        // The actual, real fix under test: this query REQUIRES side == "BUY" at the database
        // level now. A real SELL order (the flatten's own exit) would never be matched by it --
        // modeled here by simply never stubbing it to return anything for this credential,
        // exactly as the real query would behave for a SELL-only order history.
        when(omsOrderRepo.findByCredentialIdAndSideAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                eq("cred1"), eq("BUY"), eq(OrderStatus.FILLED), any())).thenReturn(List.of());

        // Three separate reconciliation passes -- TEST D's own "discovered repeatedly" requirement.
        service.reconcileCredential(credential);
        service.reconcileCredential(credential);
        service.reconcileCredential(credential);

        verify(positionRepo, never()).save(any(Position.class));
        // Confirms the query was actually exercised (not skipped for an unrelated reason) on
        // every single pass -- the guarantee under test is genuinely "asked, correctly answered
        // empty," not "never asked at all."
        verify(omsOrderRepo, times(3)).findByCredentialIdAndSideAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
            eq("cred1"), eq("BUY"), eq(OrderStatus.FILLED), any());
    }

    /** TEST E (required test E): when both a BUY and a SELL genuinely exist in this
     *  credential's recent order history, only the BUY is ever eligible for late-entry
     *  discovery -- the SELL is excluded at the query level (it is never even returned), and
     *  the BUY alone reaches Position creation. */
    @Test
    @DisplayName("reconcileCredential: with both a BUY and a SELL in recent order history, only the BUY is eligible for late-entry Position creation")
    void reconcileCredential_buyAndSellBothExist_onlyBuyIsEligible() {
        when(adapter.getType()).thenReturn(BrokerType.BINANCE);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adapters", List.of(adapter));
        when(omsOrderRepo.findByCredentialIdAndStatusInOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());

        var buyOrder = new Order();
        buyOrder.setUserId("user1");
        buyOrder.setCredentialId("cred1");
        buyOrder.setSymbol("BTCUSDT");
        buyOrder.setBrokerOrderId("real-buy-2");
        buyOrder.setSide("BUY");
        buyOrder.setStatus(OrderStatus.FILLED);

        // The side-filtered query, by construction (side == "BUY" required), returns ONLY the
        // BUY order here -- a real SELL order genuinely present in this credential's history
        // (e.g. the same flatten SELL from the production incident) would never be included in
        // this result set at all, which is exactly the guarantee under test. Not separately
        // modeled as a second stubbed order the query "filters out," because the real query
        // never receives or evaluates a SELL in the first place -- it is excluded by the WHERE
        // clause itself, before any row is even considered.
        when(omsOrderRepo.findByCredentialIdAndSideAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                eq("cred1"), eq("BUY"), eq(OrderStatus.FILLED), any())).thenReturn(List.of(buyOrder));
        when(positionRepo.findByCredentialIdAndSymbolAndEntryOrderId("cred1", "BTCUSDT", "real-buy-2")).thenReturn(Optional.empty());
        when(adapter.getOrderStatus(any(), any(), any(), any(), eq("real-buy-2"))).thenReturn(
            new com.tradevision.service.broker.dto.OrderStatusInfo("FILLED", BigDecimal.valueOf(0.001), BigDecimal.valueOf(81000), "{}"));
        when(adapter.getFillsForOrder(any(), any(), any(), any(), eq("real-buy-2"))).thenReturn(
            List.of(new Fill(BigDecimal.valueOf(81000), BigDecimal.valueOf(0.001), BigDecimal.ZERO, "BTC")));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        when(positionSafetyService.computeNetQuantity(any(), any(), any())).thenReturn(
            new PositionSafetyService.FillAccountingResult(BigDecimal.valueOf(0.001), BigDecimal.ZERO));
        when(slotReservationService.reserve(any(), anyInt(), any(), anyBoolean())).thenReturn(com.tradevision.service.PositionSlotReservationService.SlotReserveResult.reserved("test-slot-id"));
        when(exposureReservationService.reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new com.tradevision.service.ExposureReservationService.ExposureReserveResult(true, null, "test-reservation-id"));

        service.reconcileCredential(credential);

        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepo, atLeastOnce()).save(positionCaptor.capture());
        assertThat(positionCaptor.getValue().getEntryOrderId()).isEqualTo("real-buy-2");
    }

    @Test
    @DisplayName("reconcileCredential: a manual test order (clientOrderId starting \"manual-\", from OrderExecutionService.placeTestOrder) is NEVER adopted as a late-discovered position by reconciliation -- the real bug: a filled manual order has no linked Position by design, but reconciliation previously couldn't tell that apart from a genuinely orphaned bot entry and flattened it")
    void reconcileCredential_manualTestOrder_isNeverAdoptedAsLateDiscoveredPosition() {
        when(adapter.getType()).thenReturn(BrokerType.BINANCE);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adapters", List.of(adapter));
        when(omsOrderRepo.findByCredentialIdAndStatusInOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());

        var manualOrder = new Order();
        manualOrder.setUserId("user1");
        manualOrder.setCredentialId("cred1");
        manualOrder.setSymbol("BTCUSDT");
        manualOrder.setBrokerOrderId("real-buy-manual-1");
        manualOrder.setClientOrderId("manual-6e623832a543f599086952d5");
        manualOrder.setSide("BUY");
        manualOrder.setStatus(OrderStatus.FILLED);

        when(omsOrderRepo.findByCredentialIdAndSideAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                eq("cred1"), eq("BUY"), eq(OrderStatus.FILLED), any())).thenReturn(List.of(manualOrder));
        when(adapter.getOrderStatus(any(), any(), any(), any(), eq("real-buy-manual-1"))).thenReturn(
            new com.tradevision.service.broker.dto.OrderStatusInfo("FILLED", BigDecimal.valueOf(0.001), BigDecimal.valueOf(81000), "{}"));

        service.reconcileCredential(credential);

        // The order row itself is still updated (status/fill price), but nothing about a
        // Position is ever touched: no lookup, no save, no slot/exposure reservation, no broker
        // call beyond the status check above.
        verify(positionRepo, never()).findByCredentialIdAndSymbolAndEntryOrderId(any(), any(), any());
        verify(positionRepo, never()).save(any(Position.class));
        verify(slotReservationService, never()).reserve(any(), anyInt(), any(), anyBoolean());
        verify(exposureReservationService, never()).reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("reconcileCredential: a genuine bot entry order (clientOrderId prefixed \"tv-s-\", never \"manual-\") is still recovered as a late-discovered position -- the manual-order guard must not over-broadly skip real bot entries too")
    void reconcileCredential_botEntryOrder_stillRecovered() {
        when(adapter.getType()).thenReturn(BrokerType.BINANCE);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adapters", List.of(adapter));
        when(omsOrderRepo.findByCredentialIdAndStatusInOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());

        var botOrder = new Order();
        botOrder.setUserId("user1");
        botOrder.setCredentialId("cred1");
        botOrder.setSymbol("BTCUSDT");
        botOrder.setBrokerOrderId("real-buy-bot-1");
        botOrder.setClientOrderId("tv-s-abc123");
        botOrder.setSide("BUY");
        botOrder.setStatus(OrderStatus.FILLED);

        when(omsOrderRepo.findByCredentialIdAndSideAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                eq("cred1"), eq("BUY"), eq(OrderStatus.FILLED), any())).thenReturn(List.of(botOrder));
        when(positionRepo.findByCredentialIdAndSymbolAndEntryOrderId("cred1", "BTCUSDT", "real-buy-bot-1")).thenReturn(Optional.empty());
        when(adapter.getOrderStatus(any(), any(), any(), any(), eq("real-buy-bot-1"))).thenReturn(
            new com.tradevision.service.broker.dto.OrderStatusInfo("FILLED", BigDecimal.valueOf(0.001), BigDecimal.valueOf(81000), "{}"));
        when(adapter.getFillsForOrder(any(), any(), any(), any(), eq("real-buy-bot-1"))).thenReturn(
            List.of(new Fill(BigDecimal.valueOf(81000), BigDecimal.valueOf(0.001), BigDecimal.ZERO, "BTC")));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        when(positionSafetyService.computeNetQuantity(any(), any(), any())).thenReturn(
            new PositionSafetyService.FillAccountingResult(BigDecimal.valueOf(0.001), BigDecimal.ZERO));
        when(slotReservationService.reserve(any(), anyInt(), any(), anyBoolean())).thenReturn(com.tradevision.service.PositionSlotReservationService.SlotReserveResult.reserved("test-slot-id"));
        when(exposureReservationService.reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new com.tradevision.service.ExposureReservationService.ExposureReserveResult(true, null, "test-reservation-id"));

        service.reconcileCredential(credential);

        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepo, atLeastOnce()).save(positionCaptor.capture());
        assertThat(positionCaptor.getValue().getEntryOrderId()).isEqualTo("real-buy-bot-1");
    }

    /** TEST F (required test F): a position created via late-fill discovery is labeled with
     *  this codebase's own existing "LATE_FILL_DISCOVERED" domain term (already used elsewhere
     *  as an audit-event type, reused here for triggerSource) -- NOT "MANUAL", and NOT a blind
     *  copy of the underlying order's own triggerSource (the actual bug: every phantom position
     *  in the production incident inherited "MANUAL" from the original order this way). The
     *  MANUAL side of this requirement (a genuine, directly-placed manual order) is set by
     *  OrderExecutionService.java at placement time -- untouched by this fix, confirmed by
     *  inspection, not re-tested here since it belongs to a different service/test file. */
    @Test
    @DisplayName("createPositionForLateDiscoveredFill: triggerSource is the existing LATE_FILL_DISCOVERED domain term, never MANUAL and never copied from the order's own triggerSource")
    void createPositionForLateDiscoveredFill_setsLateFillDiscoveredTriggerSource_notCopiedFromOrder() {
        when(slotReservationService.reserve(any(), anyInt(), any(), anyBoolean())).thenReturn(com.tradevision.service.PositionSlotReservationService.SlotReserveResult.reserved("test-slot-id"));
        when(exposureReservationService.reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new com.tradevision.service.ExposureReservationService.ExposureReserveResult(true, null, "test-reservation-id"));
        when(adapter.getFillsForOrder(any(), any(), any(), any(), any())).thenReturn(
            List.of(new Fill(BigDecimal.valueOf(100), BigDecimal.valueOf(1.0), BigDecimal.valueOf(0.001), "BTC")));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BTC_RULES);
        when(positionSafetyService.computeNetQuantity(any(), any(), any())).thenReturn(
            new PositionSafetyService.FillAccountingResult(BigDecimal.valueOf(0.999), BigDecimal.ZERO));

        var order = new Order();
        order.setUserId("user1");
        order.setSymbol("BTCUSDT");
        order.setBrokerOrderId("real-buy-3");
        order.setSide("BUY");
        // Deliberately set to something OTHER than "LATE_FILL_DISCOVERED" -- if the old,
        // buggy behavior (position.setTriggerSource(order.getTriggerSource())) were still
        // present, this exact wrong value ("MANUAL") would leak onto the Position, which is
        // precisely the production bug this test guards against.
        order.setTriggerSource("MANUAL");

        service.createPositionForLateDiscoveredFill(credential, adapter, "key", "secret", order, BigDecimal.valueOf(1.0), 1L);

        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepo, atLeastOnce()).save(positionCaptor.capture());
        assertThat(positionCaptor.getValue().getTriggerSource()).isEqualTo("LATE_FILL_DISCOVERED");
    }

    /** TEST 7 (required, the exact production reproduction): BUY fills, Position A is created
     *  and (for this test's purposes) already closed by its own emergency-flatten SELL -- that
     *  SELL order genuinely exists in this credential's order history with status FILLED, side
     *  SELL. The NEXT reconciliation pass must find zero eligible late-entry orders (the SELL
     *  is excluded at the query level) and therefore create zero further positions -- Position
     *  B, the phantom this whole incident was about, must never come into existence. */
    @Test
    @DisplayName("PRODUCTION REPRODUCTION: BUY fills -> Position A -> emergency-flatten SELL -> reconciliation sees the SELL -> Position B is NEVER created")
    void productionReproduction_buyThenFlattenSell_neverCreatesPositionB() {
        when(adapter.getType()).thenReturn(BrokerType.BINANCE);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adapters", List.of(adapter));
        when(omsOrderRepo.findByCredentialIdAndStatusInOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());

        // Position A already exists and is CLOSED -- exactly the real-world state right after
        // an emergency-flatten completes (matching the production incident's own
        // "NAKED_FLATTENED" positions). Its own entry order id is the original real BUY.
        Position positionA = new Position();
        positionA.setId("position-a");
        positionA.setEntryOrderId("real-buy-4");
        positionA.setStatus("NAKED_FLATTENED");
        when(positionRepo.findByCredentialIdAndSymbolAndEntryOrderId(any(), any(), eq("real-buy-4"))).thenReturn(Optional.of(positionA));

        // The flatten's own real SELL order -- genuinely exists, genuinely FILLED, genuinely
        // SELL. This is the exact order that, before this fix, became the next phantom
        // position's own "entry order ID" in production.
        when(positionRepo.findByCredentialIdAndSymbolAndEntryOrderId(any(), any(), eq("real-flatten-sell-4"))).thenReturn(Optional.empty());
        // The actual fix under test: the side-filtered query never returns this SELL order at
        // all, so reconcileEntryOrders' own loop never even considers it as a candidate late
        // entry -- syncPositionQuantityIfMismatched/createPositionForLateDiscoveredFill are
        // never invoked for it.
        when(omsOrderRepo.findByCredentialIdAndSideAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                eq("cred1"), eq("BUY"), eq(OrderStatus.FILLED), any())).thenReturn(List.of());

        service.reconcileCredential(credential);

        verify(positionRepo, never()).save(any(Position.class));
        verify(slotReservationService, never()).reserve(any(), anyInt(), any(), anyBoolean());
    }
}
