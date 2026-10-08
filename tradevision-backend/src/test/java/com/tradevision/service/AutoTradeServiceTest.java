package com.tradevision.service;

import com.tradevision.model.*;
import com.tradevision.repository.PositionRepository;
import com.tradevision.repository.RiskProfileRepository;
import com.tradevision.repository.TradeCallRepository;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.*;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers AutoTradeService, the file most central to actual money-moving decisions in this
 * codebase, including server confidence threshold handling, base-asset commission, and the
 * ENTRY_FILLED_UNVERIFIED state (a market order later becoming FILLED is covered separately in
 * PositionMonitorService). A shared happy-path baseline gets every gate to pass; each test then
 * perturbs exactly the one thing it's testing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoTradeServiceTest {

    @Mock RiskProfileRepository riskProfileRepo;
    @Mock BrokerCredentialService credentialService;
    @Mock RiskEngineService riskEngine;
    @Mock NoTradeFilterService noTradeFilter;
    @Mock PositionRepository positionRepo;
    @Mock com.tradevision.repository.StrategyPlanRepository strategyPlanRepo;
    @Mock StrategyPlanService strategyPlanService;
    @Mock com.tradevision.repository.OrphanedOcoRepository orphanedOcoRepo;
    @Mock PositionMonitorService positionMonitorService;
    @Mock PositionSlotReservationService slotReservationService;
    @Mock ExposureReservationService exposureReservationService;
    @Mock PositionSafetyService positionSafetyService;
    @Mock TradeCallRepository callRepo;
    @Mock BrokerAdapter adapter;
    @Mock org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    @Mock com.tradevision.config.ShutdownState shutdownState;
    @Mock com.tradevision.config.StartupState startupState;
    @Mock OrderService orderService;
    // Needed because every real order placement requires a successful atomic authorization
    // claim first, via RiskProfileService.claimExecutionAuthorization.
    @Mock RiskProfileService riskProfileService;
    @Mock FillLedgerService fillLedgerService;
    @Mock PositionLedgerService positionLedgerService;
    @Mock IncidentService incidentService;
    @Mock ExecutionContextService executionContextService;
    // Needed because the entry flow acquires this same distributed lock (under the
    // credentialId key) before running its own critical section.
    @Mock DistributedLockService distributedLockService;

    @InjectMocks AutoTradeService service;

    private RiskProfile profile;
    private BrokerCredential credential;
    private TradeCallRecord signal;
    private ServerSignalEngine.Signal serverSignal;

    private static final SymbolRules RULES =
        new SymbolRules("BTCUSDT", "BTC", "USDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);

    @BeforeEach
    void setup() {
        // A realistic "no reconciliation pass is currently running for this credential, lock
        // acquired cleanly" default -- an unstubbed tryAcquireWithDiagnosis() would otherwise
        // return null, NPEing on .acquired() at the very first call in every existing test. A
        // test that specifically wants to exercise the lock-contention refusal path overrides
        // this.
        when(distributedLockService.tryAcquireWithDiagnosis(any(), any(), any()))
            .thenReturn(new DistributedLockService.LockLease(DistributedLockService.AcquireResult.ACQUIRED, 1L));
        // A realistic "renewal succeeded" default for the lease renewals made immediately before
        // adapter.placeOrder()/placeExitOco() -- an unstubbed renew() would otherwise return
        // Mockito's default false, which is harmless (both call sites treat a failed renewal as
        // non-fatal, just a warning log) but would make every existing test's logs noisy with a
        // warning about something this file never intended to exercise. Tests that specifically
        // want to exercise the renewal failure path override this explicitly.
        when(distributedLockService.renew(any(), any(), any())).thenReturn(true);
        // A realistic "the claim succeeded" default for the execution authorization claim --
        // an unstubbed call would otherwise return null, silently aborting every existing test
        // right before order placement. A test that specifically wants to exercise the
        // lost-the-claim path overrides this.
        when(riskProfileService.claimExecutionAuthorization(any(), anyBoolean()))
            .thenReturn(new RiskProfileService.ExecutionClaim("claim-default", 0L));
        // markExecutionStarted performs a final re-verification atomically combined with
        // in-flight registration. AutoTradeService calls claimExecutionAtomicWithPlan, which
        // combines that same final re-check with the strategy plan's own authorization state --
        // both default to "succeeded" here, same reasoning as every other claim default in this
        // file.
        when(riskProfileService.markExecutionStarted(any(), any(), anyBoolean())).thenReturn(true);
        when(riskProfileService.claimExecutionAtomicWithPlan(any(), any(), anyBoolean(), any(), any(), any())).thenReturn(true);
        // AutoTradeService calls this TWICE per successful execution -- an unstubbed mock would
        // otherwise return null, and this class's code calls .authorized() on the result
        // unconditionally, which would NPE every single existing test at the very first call.
        // Defaults to "allowed" so every existing test's own assumptions (a signal with no
        // plan, or a plan this session hasn't specifically configured to fail, proceeds normally)
        // stay true; a test that specifically wants to exercise a denial overrides this.
        when(strategyPlanService.authorizeExecution(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(StrategyPlanService.PlanAuthorizationResult.allow());
        // The lease re-check before the exchange call is an atomic findAndModify against
        // TradeCallRecord (extending the lease), not a plain exists() read -- the existing
        // generic `mongoTemplate.findAndModify(any(), any(), any(), eq(TradeCallRecord.class))`
        // stub further below (added for the PENDING -> EVALUATING claim) already covers this
        // call too, since it matches on class alone -- no separate stub needed here.
        // A realistic "the conditional advance succeeded" default for advanceSignalStatus --
        // an unstubbed updateFirst() targeting TradeCallRecord would otherwise NPE on
        // .getModifiedCount(), breaking essentially every existing test in this file, since
        // advanceSignalStatus runs throughout the evaluation flow.
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(TradeCallRecord.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
        // atomicSetOcoPlaced()'s own success check calls result.getModifiedCount() on the
        // return value -- an unstubbed call here would return Mockito's null default, and
        // .getModifiedCount() on that null would NPE for real, uncaught, in every existing test
        // that reaches this OCO-placement code path.
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(Position.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
        // A realistic "order creation succeeded" default, same reasoning as the updateFirst
        // default immediately above -- the entry-order write path DEPENDS on
        // orderService.create() succeeding (its own fallback-creation branch calls it again if
        // the FIRST attempt returned null, and an unstubbed second attempt would also return
        // null, NPE on the next line, get caught by this method's own try/catch, and return
        // EARLY -- never reaching position creation at all). This default is what keeps every
        // existing test in this file that doesn't explicitly stub create() still reaching the
        // position-creation logic it actually means to test. A test that specifically wants to
        // exercise OMS-creation failure overrides this explicitly (see OMS-setup-specific tests
        // elsewhere in this file for that pattern).
        when(orderService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> { var o = new com.tradevision.model.Order(); o.setId("default-oms-order"); return o; });
        when(orderService.recordEntryMetadata(any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> inv.getArgument(0));
        when(orderService.recordBrokerResult(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderService.recordOcoOrderId(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderService.persistCurrentSlTp(any())).thenAnswer(inv -> inv.getArgument(0));
        profile = new RiskProfile();
        profile.setCredentialId("cred1");
        profile.setUserId("user1");
        profile.setMinConfidence(75.0);
        profile.setMaxPriceDeviationPercent(50.0); // generous — not the thing under test in most cases
        profile.setMaxConcurrentTrades(5);
        profile.setRiskPerTradePercent(1.0);
        profile.setAutoTradeEnabled(true);
        profile.getEnabledSymbols().add("BTCUSDT");

        credential = new BrokerCredential();
        credential.setId("cred1");
        credential.setUserId("user1");
        credential.setBroker(BrokerType.BINANCE);
        credential.setMode(BrokerMode.TESTNET);

        signal = new TradeCallRecord();
        signal.setId("sig1");
        signal.setSymbol("BTCUSDT");
        signal.setDirection("LONG");
        signal.setConfidence(90); // client confidence — deliberately high so any rejection in
                                   // tests is attributable to the SERVER confidence, not the client's
        // These 4 fields are BigDecimal.
        signal.setEntryPrice(BigDecimal.valueOf(100.0));
        signal.setStopLoss(BigDecimal.valueOf(95.0));
        signal.setTarget1(BigDecimal.valueOf(110.0));
        signal.setRrRatio(BigDecimal.valueOf(2.0));

        serverSignal = new ServerSignalEngine.Signal("LONG", "BUY", 80.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);

        when(riskProfileRepo.findByUserIdAndAutoTradeEnabledTrue("user1")).thenReturn(List.of(profile));
        when(credentialService.ownedCredential("user1", "cred1")).thenReturn(credential);
        when(credentialService.adapterForCredential(credential)).thenReturn(adapter);
        when(credentialService.decrypt(credential, true)).thenReturn("key");
        when(credentialService.decrypt(credential, false)).thenReturn("secret");
        when(noTradeFilter.check(eq("user1"), eq("cred1"), eq(signal), eq(adapter), eq("key"), eq(BrokerMode.TESTNET)))
            .thenReturn(new NoTradeFilterService.FilterResult(true, null, serverSignal));
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BigDecimal.valueOf(100.0));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(RULES);
        when(adapter.getBalance("key", "secret", BrokerMode.TESTNET)).thenReturn(
            List.of(new AssetBalance("USDT", BigDecimal.valueOf(10000), BigDecimal.ZERO)));
        // sizePosition/evaluateForProfile check real order-book depth before submitting -- a
        // realistic, deep book default so every existing test in this file (none of which are
        // about this specific check) reaches order placement as before; the dedicated
        // insufficientDepth_* tests below override this explicitly.
        when(adapter.getOrderBookDepth(eq("BTCUSDT"), eq(BrokerMode.TESTNET), anyInt())).thenReturn(
            new OrderBookDepth(
                List.of(new OrderBookDepth.PriceLevel(BigDecimal.valueOf(99.99), BigDecimal.valueOf(1000))),
                List.of(new OrderBookDepth.PriceLevel(BigDecimal.valueOf(100.0), BigDecimal.valueOf(1000)))));
        when(riskEngine.check(any(), any(), any())).thenReturn(RiskEngineService.RiskCheckResult.ok());
        when(slotReservationService.reserve(eq("cred1"), anyInt(), any(), anyBoolean())).thenReturn(PositionSlotReservationService.SlotReserveResult.reserved("acct-slot-id"));
        // A realistic "genuine match" default, same reasoning as this file's other @BeforeEach
        // defaults -- reconcileAgainstLedger returns a ReconcileResult object, and Mockito's
        // default for an unstubbed object-returning method is null, not false -- every existing
        // test reaching this escalation code would otherwise NPE on reconcileResult.matches().
        // A test that wants to exercise the mismatch path overrides this explicitly.
        when(positionLedgerService.reconcileAgainstLedger(any(), any()))
            .thenAnswer(invocation -> new PositionLedgerService.ReconcileResult(PositionLedgerService.ReconcileStatus.MATCH, invocation.getArgument(1), invocation.getArgument(1)));
        // evaluateSignal refuses to run anything until startupState.isTradingEnabled() --
        // without this, every existing test would short-circuit at that gate on Mockito's
        // default false, never reaching any of the actual logic under test.
        when(startupState.isTradingEnabled()).thenReturn(true);
        // evaluateForProfile ALSO gates on this per-credential check (full context in
        // StartupState.isCredentialTradingEnabled's own javadoc) -- same reasoning as the mock
        // just above, defaulted to "enabled" so every existing test's own assumptions stay
        // true; a test that specifically wants to exercise the per-credential block overrides
        // this.
        when(startupState.isCredentialTradingEnabled(any())).thenReturn(true);
        // Needed because AutoTradeService calls this immediately after slot reservation
        // succeeds -- without this, every test reaching order placement would NPE on an
        // unstubbed mock's null return.
        when(exposureReservationService.reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(ExposureReservationService.ExposureReserveResult.ok("test-reservation-id"));
        // Needed because evaluateSignal atomically claims the signal (PENDING -> EVALUATING) via
        // mongoTemplate before doing anything else — without this, every test would return
        // early on the unstubbed mock's default null, never reaching any evaluation logic at
        // all. Scoped specifically to TradeCallRecord.class so it never interferes with the
        // circuit-breaker's own, separate findAndModify calls against RiskProfile.class.
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(TradeCallRecord.class)))
            .thenAnswer(inv -> { TradeCallRecord r = new TradeCallRecord(); r.setId(signal.getId()); return r; });
        when(positionRepo.save(any())).thenAnswer(i -> i.getArguments()[0]);
        // Without a default here, Mockito's default answer for an unstubbed method returning
        // List<T> is an EMPTY list, not null -- meaning every test that reaches the
        // entry-fill-recording code in AutoTradeService without its own explicit stub would
        // silently trip the genuine "ledger write lost data" halt path
        // (Position.ledgerRecordingIncomplete=true, atomicHaltProfile, a CRITICAL incident)
        // before ever reaching whatever it actually means to test. This default makes the
        // ledger write return one record per fill it was given, built dynamically from the
        // fills argument actually passed (index 8 of the 11-arg overload) rather than a fixed
        // size, so it stays correct for both the per-fill list case and the aggregate-fallback
        // (empty fills, aggregateQty>0 -> exactly one record) case. Tests that specifically want
        // to exercise the ledger-write-failure path override this explicitly, same as every
        // other default here.
        when(fillLedgerService.recordFills(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                List<Fill> fillsArg = (List<Fill>) inv.getArgument(8);
                java.math.BigDecimal aggregateQtyArg = inv.getArgument(9);
                int count = (fillsArg != null && !fillsArg.isEmpty()) ? fillsArg.size()
                    : (aggregateQtyArg != null && aggregateQtyArg.signum() > 0 ? 1 : 0);
                List<com.tradevision.model.FillRecord> records = new java.util.ArrayList<>();
                for (int i = 0; i < count; i++) records.add(new com.tradevision.model.FillRecord());
                return records;
            });
        when(positionSafetyService.sumCommissionInQuoteAsset(any(), any())).thenReturn(null);
        // AutoTradeService calls the shared computeNetQuantity — this mock replicates the real
        // method's actual logic (based on whatever arguments the code under test actually
        // passes) rather than a fixed canned response, so every existing commission test still
        // exercises the intended behavior faithfully instead of breaking on an unstubbed null.
        when(positionSafetyService.computeNetQuantity(any(), any(), any())).thenAnswer(inv -> {
            java.math.BigDecimal gross = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            List<Fill> fillList = (List<Fill>) inv.getArgument(1);
            String baseAsset = inv.getArgument(2);
            if (fillList == null || fillList.isEmpty() || baseAsset == null) {
                return new PositionSafetyService.FillAccountingResult(gross, java.math.BigDecimal.ZERO);
            }
            java.math.BigDecimal baseCommission = fillList.stream()
                .filter(f -> f.commissionAsset() != null && f.commissionAsset().equalsIgnoreCase(baseAsset))
                .map(Fill::commission)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            java.math.BigDecimal net = baseCommission.signum() > 0 ? gross.subtract(baseCommission) : gross;
            return new PositionSafetyService.FillAccountingResult(net, baseCommission);
        });
        // Needed for the commission tests, which have a verified entry price and so proceed all
        // the way through to OCO placement — without this, Mockito's default null return for an
        // unstubbed method returning an object type would NPE on .success().
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "oco-1", "{}", null));
        // @Value fields aren't populated by @InjectMocks, so this must be set explicitly or
        // every OCO-placement test NPEs on BigDecimal.ONE.subtract(null). Matches this
        // project's own established default (0.5%).
        org.springframework.test.util.ReflectionTestUtils.setField(service, "stopLossLimitGapPercent", new java.math.BigDecimal("0.005"));
    }

    /**
     * Switches this file's shared TESTNET fixture over to a LIVE credential: the profile must carry
     * its own separate LIVE authorization, and every mode-keyed default stub in setup() (no-trade
     * filter, price, symbol rules, balance) is registered against TESTNET only, so each needs a
     * LIVE twin or the evaluation aborts before it ever reaches the code under test.
     */
    private void switchToLive() {
        credential.setMode(BrokerMode.LIVE);
        profile.setLiveAutoTradeAuthorized(true);
        when(noTradeFilter.check(eq("user1"), eq("cred1"), eq(signal), eq(adapter), eq("key"), eq(BrokerMode.LIVE)))
            .thenReturn(new NoTradeFilterService.FilterResult(true, null, serverSignal));
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.LIVE)).thenReturn(BigDecimal.valueOf(100.0));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.LIVE)).thenReturn(RULES);
        when(adapter.getBalance("key", "secret", BrokerMode.LIVE)).thenReturn(
            List.of(new AssetBalance("USDT", BigDecimal.valueOf(10000), BigDecimal.ZERO)));
        // Same reasoning as setup()'s own TESTNET default above -- a deep-book LIVE twin.
        when(adapter.getOrderBookDepth(eq("BTCUSDT"), eq(BrokerMode.LIVE), anyInt())).thenReturn(
            new OrderBookDepth(
                List.of(new OrderBookDepth.PriceLevel(BigDecimal.valueOf(99.99), BigDecimal.valueOf(1000))),
                List.of(new OrderBookDepth.PriceLevel(BigDecimal.valueOf(100.0), BigDecimal.valueOf(1000)))));
    }

    /**
     * Switches this file's shared TESTNET fixture over to a PAPER credential, deliberately
     * WITHOUT setting
     * profile.setLiveAutoTradeAuthorized(true) -- unlike switchToLive() above, this is the whole
     * point of the test this helper supports: a PAPER credential must be able to trade with no
     * live authorization at all, since PaperBrokerAdapter never touches Binance with real
     * authenticated calls in the first place. Same reasoning as switchToLive() for why every
     * mode-keyed default stub needs its own PAPER twin.
     */
    private void switchToPaper() {
        credential.setMode(BrokerMode.PAPER);
        when(noTradeFilter.check(eq("user1"), eq("cred1"), eq(signal), eq(adapter), eq("key"), eq(BrokerMode.PAPER)))
            .thenReturn(new NoTradeFilterService.FilterResult(true, null, serverSignal));
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.PAPER)).thenReturn(BigDecimal.valueOf(100.0));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.PAPER)).thenReturn(RULES);
        when(adapter.getBalance("key", "secret", BrokerMode.PAPER)).thenReturn(
            List.of(new AssetBalance("USDT", BigDecimal.valueOf(10000), BigDecimal.ZERO)));
        when(adapter.getOrderBookDepth(eq("BTCUSDT"), eq(BrokerMode.PAPER), anyInt())).thenReturn(
            new OrderBookDepth(
                List.of(new OrderBookDepth.PriceLevel(BigDecimal.valueOf(99.99), BigDecimal.valueOf(1000))),
                List.of(new OrderBookDepth.PriceLevel(BigDecimal.valueOf(100.0), BigDecimal.valueOf(1000)))));
    }

    private OrderResult successfulFill(double executedQty, double fillPrice, List<Fill> fills) {
        return new OrderResult(true, "b1", "sig-sig1", "FILLED", BigDecimal.valueOf(executedQty),
            BigDecimal.valueOf(fillPrice), "{}", null, fills);
    }

    // ── Server confidence threshold ───────────────────────────────────────────

    @Test
    @DisplayName("evaluateSignal: refuses the trade when SERVER confidence is below minConfidence, even though the client's claimed confidence is well above it")
    void serverConfidenceBelowMinimum_refusesTrade() {
        // Client claims 90% (set in setup) but the server only computed 40% — the review's exact scenario.
        ServerSignalEngine.Signal lowConfidenceServerSignal =
            new ServerSignalEngine.Signal("LONG", "BUY", 40.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(noTradeFilter.check(any(), any(), any(), any(), any(), any()))
            .thenReturn(new NoTradeFilterService.FilterResult(true, null, lowConfidenceServerSignal));

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(credentialService).audit(eq("user1"), eq("cred1"), any(), eq("SIGNAL_NO_TRADE"), detailCaptor.capture());
        assertThat(detailCaptor.getValue()).containsIgnoringCase("server-computed confidence");
    }

    @Test
    @DisplayName("evaluateSignal: proceeds when server confidence meets the minimum")
    void serverConfidenceAtMinimum_proceeds() {
        // serverSignal in the shared baseline is 80%, minConfidence is 75% — should pass this gate.
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        verify(adapter).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("evaluateSignal: the OMS order is durably stamped with the execution claim id that actually authorized it, so recovery/audit can answer \"which claim produced this exact order\" directly from the order record")
    void evaluateSignal_stampsOmsOrderWithExecutionClaimId() {
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        ArgumentCaptor<com.tradevision.model.Order> orderCaptor = ArgumentCaptor.forClass(com.tradevision.model.Order.class);
        verify(orderService).recordExecutionClaim(orderCaptor.capture(), eq("claim-default"));
    }

    @Test
    @DisplayName("evaluateSignal: a plan-level max-concurrent-trades slot unavailable rolls back the already-reserved account-level slot and never contacts the exchange -- the user's own explicit two-tier risk design (\"Plan-level risk... Account-level risk... The account-level limits always win\")")
    void evaluateSignal_planLevelSlotUnavailable_rollsBackAccountSlotAndNeverPlacesOrder() {
        signal.setPlanId("plan1");
        var plan = new com.tradevision.model.StrategyPlan();
        plan.setId("plan1"); plan.setName("Scalper"); plan.setMaxConcurrentTrades(2);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));
        when(slotReservationService.reserve(eq("plan:plan1"), anyInt(), any(), anyBoolean())).thenReturn(PositionSlotReservationService.SlotReserveResult.rejected()); // plan's own slots are full

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        verify(slotReservationService).release("acct-slot-id"); // the account-level slot reserved above must be rolled back
    }

    @Test
    @DisplayName("evaluateSignal: a plan's own TIGHTER riskPerTradePercent narrows the actual position size below what the account-level percent alone would produce -- the user's own explicit design (\"Plan-level risk: Risk per trade = 0.5%\"), verified end-to-end through the real sizing math rather than asserting a hand-computed number")
    void evaluateSignal_planRiskTighterThanAccount_narrowsPositionSize() {
        // Baseline: no plan at all, account-level riskPerTradePercent (1.0, from the shared
        // fixture) alone drives sizing.
        ArgumentCaptor<OrderRequest> baselineCaptor = ArgumentCaptor.forClass(OrderRequest.class);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));
        service.evaluateSignal("user1", signal);
        verify(adapter).placeOrder(any(), any(), any(), baselineCaptor.capture());
        BigDecimal baselineQuantity = baselineCaptor.getValue().quantity();

        // Same signal, but now with a plan whose own riskPerTradePercent (0.5) is tighter than
        // the account-level 1.0 -- Math.min must make this the effective, smaller risk.
        reset(adapter);
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BigDecimal.valueOf(100.0));
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "oco-1", "{}", null));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));
        when(adapter.getSymbolRules(any(), any())).thenReturn(new com.tradevision.service.broker.dto.SymbolRules(
            "BTCUSDT", "BTC", "USDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(0.0001), BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO));
        when(adapter.getBalance("key", "secret", BrokerMode.TESTNET)).thenReturn(
            List.of(new com.tradevision.service.broker.dto.AssetBalance("USDT", BigDecimal.valueOf(10000), BigDecimal.ZERO)));
        // reset(adapter) above wiped setup()'s own default depth stub -- re-stub it here.
        when(adapter.getOrderBookDepth(eq("BTCUSDT"), eq(BrokerMode.TESTNET), anyInt())).thenReturn(
            new OrderBookDepth(
                List.of(new OrderBookDepth.PriceLevel(BigDecimal.valueOf(99.99), BigDecimal.valueOf(1000))),
                List.of(new OrderBookDepth.PriceLevel(BigDecimal.valueOf(100.0), BigDecimal.valueOf(1000)))));
        signal.setPlanId("plan1");
        var plan = new com.tradevision.model.StrategyPlan();
        plan.setId("plan1"); plan.setMaxConcurrentTrades(2); plan.setRiskPerTradePercent(0.5);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));
        when(slotReservationService.reserve(eq("plan:plan1"), anyInt(), any(), anyBoolean())).thenReturn(PositionSlotReservationService.SlotReserveResult.reserved("plan-slot-id"));
        ArgumentCaptor<OrderRequest> planCaptor = ArgumentCaptor.forClass(OrderRequest.class);

        service.evaluateSignal("user1", signal);

        verify(adapter).placeOrder(any(), any(), any(), planCaptor.capture());
        BigDecimal planQuantity = planCaptor.getValue().quantity();
        assertThat(planQuantity).isLessThan(baselineQuantity);
    }

    // ── Sizing must never demand more than the account can actually afford, and must
    // refuse to submit into an order book too thin to absorb the sized quantity ──────────────

    @Test
    @DisplayName("an extremely tight stop-loss would otherwise size a quantity whose notional vastly exceeds the account's free balance -- capped to what the account can actually afford instead of submitted oversized")
    void tightStopLoss_capsQuantityToFreeBalance() {
        // entry 100, stopLoss 99.99 -- a stopDistance of 0.01 against this fixture's own 1%
        // riskPerTradePercent and $10,000 balance would otherwise demand a quantity worth roughly
        // $1,000,000 (riskAmount $100 / stopDistance 0.01 = 10,000 BTC @ $100 = $1,000,000).
        ServerSignalEngine.Signal tightStopSignal = new ServerSignalEngine.Signal("LONG", "BUY", 80.0, 100.0, 99.99, 110.0, 120.0, 130.0, 60, 20, 40);
        when(noTradeFilter.check(any(), any(), any(), any(), any(), any()))
            .thenReturn(new NoTradeFilterService.FilterResult(true, null, tightStopSignal));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        ArgumentCaptor<OrderRequest> captor = ArgumentCaptor.forClass(OrderRequest.class);
        verify(adapter).placeOrder(any(), any(), any(), captor.capture());
        BigDecimal notional = captor.getValue().quantity().multiply(BigDecimal.valueOf(100.0));
        // Free balance is $10,000 (shared fixture) -- the 2% safety buffer means the cap allows
        // at most $9,800 of notional, with a tiny epsilon for BigDecimal rounding (DOWN, 8dp).
        assertThat(notional.doubleValue()).isLessThanOrEqualTo(9800.01);
    }

    @Test
    @DisplayName("a thin order book (not enough ask-side liquidity within the allowed price-impact band to fill the sized quantity) refuses the trade rather than submitting a MARKET order that would walk deep into the book")
    void thinOrderBook_refusesTrade() {
        // Only 0.001 BTC available at/near the best ask -- the sized quantity (well above that,
        // given this fixture's own $10,000 balance and 1% risk) cannot be filled within the
        // allowed impact band.
        when(adapter.getOrderBookDepth(eq("BTCUSDT"), eq(BrokerMode.TESTNET), anyInt())).thenReturn(
            new OrderBookDepth(
                List.of(new OrderBookDepth.PriceLevel(BigDecimal.valueOf(99.99), BigDecimal.valueOf(1000))),
                List.of(new OrderBookDepth.PriceLevel(BigDecimal.valueOf(100.0), BigDecimal.valueOf(0.001)))));

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(credentialService).audit(eq("user1"), eq("cred1"), any(), eq("SIGNAL_NO_TRADE"), detailCaptor.capture());
        assertThat(detailCaptor.getValue()).containsIgnoringCase("depth");
    }

    @Test
    @DisplayName("an empty order book (no asks at all) refuses the trade -- fails closed, never treated as \"no liquidity constraint\"")
    void emptyOrderBook_refusesTrade() {
        when(adapter.getOrderBookDepth(eq("BTCUSDT"), eq(BrokerMode.TESTNET), anyInt())).thenReturn(
            new OrderBookDepth(List.of(), List.of()));

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an error fetching order book depth refuses the trade -- fails closed rather than trading blind")
    void orderBookDepthFetchFails_refusesTrade() {
        when(adapter.getOrderBookDepth(eq("BTCUSDT"), eq(BrokerMode.TESTNET), anyInt()))
            .thenThrow(new RuntimeException("simulated connection failure"));

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("evaluateSignal: the server-computed confidence check also respects a plan's own STRICTER minConfidence, not just the account-level value: a plan configured for 85% must not have its own threshold silently ignored if the account-level setting is looser (60%, this fixture's own default)")
    void evaluateSignal_planMinConfidenceStricterThanAccount_rejectsBelowPlanThreshold() {
        signal.setPlanId("plan1");
        var plan = new com.tradevision.model.StrategyPlan();
        plan.setId("plan1"); plan.setMaxConcurrentTrades(2); plan.setMinConfidence(85.0); // stricter than the account's own 60.0
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));
        when(slotReservationService.reserve(eq("plan:plan1"), anyInt(), any(), anyBoolean())).thenReturn(PositionSlotReservationService.SlotReserveResult.reserved("plan-slot-id"));
        // Server-computed confidence of 70 -- passes the account's own 60.0, but must still be
        // rejected against this plan's own stricter 85.0.
        var weakerServerSignal = new ServerSignalEngine.Signal("LONG", "BUY", 70.0, 100.0, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(noTradeFilter.check(eq("user1"), eq("cred1"), eq(signal), eq(adapter), eq("key"), eq(BrokerMode.TESTNET)))
            .thenReturn(new NoTradeFilterService.FilterResult(true, null, weakerServerSignal));

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        // User's own explicit architectural request, full context in ExecutionContext's own
        // class javadoc.
        verify(executionContextService).recordTerminal(any(), eq("REJECTED_MIN_CONFIDENCE"), any());
    }

    /**
     * A cancelled signal's atomic claim (which requires signalStatus IN GENERATED/VALIDATING)
     * genuinely fails to match in MongoDB -- simulated here the same way every other
     * failed-claim scenario in this file is: the claim returns null, exactly what a real
     * signalStatus=CANCELLED document would produce against the query criteria.
     */
    @Test
    @DisplayName("evaluateSignal: when signal.getPlanId() is set but the plan record itself can no longer be found, a LATER failure releases the account-level slot but never the plan-level one -- this execution never actually held it, and releasing it anyway could decrement a different, genuinely active execution's own reservation")
    void evaluateSignal_planRecordMissing_laterFailureNeverReleasesPlanSlotItNeverHeld() {
        signal.setPlanId("plan1");
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.empty()); // the plan itself can no longer be found
        when(exposureReservationService.reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(ExposureReservationService.ExposureReserveResult.reject("simulated: exposure cap exceeded"));

        service.evaluateSignal("user1", signal);

        verify(slotReservationService).release("acct-slot-id"); // the account-level slot this execution DID reserve
        verify(slotReservationService, never()).release("plan:plan1"); // never actually reserved -- must never be released
    }

    /**
     * The other side of the scenario above: when the plan record genuinely IS found and its
     * own reservation genuinely succeeds, a later failure correctly DOES release it, since this
     * execution actually held it.
     */
    @Test
    @DisplayName("evaluateSignal: when the plan record IS found and its own slot IS reserved, a later failure correctly releases both the account-level AND plan-level slots")
    void evaluateSignal_planRecordFound_laterFailureReleasesBothSlots() {
        signal.setPlanId("plan1");
        var plan = new com.tradevision.model.StrategyPlan();
        plan.setId("plan1"); plan.setMaxConcurrentTrades(2);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));
        when(slotReservationService.reserve(eq("plan:plan1"), anyInt(), any(), anyBoolean())).thenReturn(PositionSlotReservationService.SlotReserveResult.reserved("plan-slot-id"));
        when(exposureReservationService.reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(ExposureReservationService.ExposureReserveResult.reject("simulated: exposure cap exceeded"));

        service.evaluateSignal("user1", signal);

        verify(slotReservationService).release("acct-slot-id");
        verify(slotReservationService).release("plan-slot-id");
    }

    /**
     * A signal whose atomic claim fails because it is no longer in GENERATED/VALIDATING status
     * (cancelled or expired) must never reach the exchange, regardless of what
     * autoTradeEvalStatus still shows.
     */
    @Test
    @DisplayName("evaluateSignal: a signal whose atomic claim fails because it's no longer GENERATED/VALIDATING (cancelled or expired) never reaches the exchange, regardless of autoTradeEvalStatus still showing PENDING")
    void evaluateSignal_signalNoLongerEligible_neverContactsExchange() {
        // Overrides this file's own generic "claim always succeeds" default -- a cancelled or
        // expired signal's real query (signalStatus IN GENERATED, VALIDATING) would not match.
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(TradeCallRecord.class))).thenReturn(null);

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("evaluateSignal: a plan whose atomic claimPlanExecution fails (the plan was disabled/edited since this signal was generated) aborts before the exchange call")
    void evaluateSignal_claimPlanExecutionFails_neverContactsExchange() {
        signal.setPlanId("plan1");
        signal.setPlanVersion(5L);
        var plan = new com.tradevision.model.StrategyPlan();
        plan.setId("plan1"); plan.setMaxConcurrentTrades(2);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));
        when(slotReservationService.reserve(eq("plan:plan1"), anyInt(), any(), anyBoolean())).thenReturn(PositionSlotReservationService.SlotReserveResult.reserved("plan-slot-id"));
        // The plan's own version no longer matches what this signal was generated under --
        // atomically rejected, exactly as if a disable/edit landed in the narrow window.
        when(riskProfileService.claimExecutionAtomicWithPlan(eq("cred1"), any(), anyBoolean(), eq("plan1"), eq(5L), eq("user1"))).thenReturn(false);

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        verify(slotReservationService).release("acct-slot-id");
        // This abort also runs after orderService.markSubmitting(omsOrder), so it must resolve
        // omsOrder too, not just release the reservations.
        verify(orderService).markSubmissionFailed(any(), any());
    }

    @Test
    @DisplayName("evaluateSignal: a plan whose atomic claimPlanExecution succeeds proceeds normally to the exchange")
    void evaluateSignal_claimPlanExecutionSucceeds_proceedsNormally() {
        signal.setPlanId("plan1");
        signal.setPlanVersion(5L);
        var plan = new com.tradevision.model.StrategyPlan();
        plan.setId("plan1"); plan.setMaxConcurrentTrades(2);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));
        when(slotReservationService.reserve(eq("plan:plan1"), anyInt(), any(), anyBoolean())).thenReturn(PositionSlotReservationService.SlotReserveResult.reserved("plan-slot-id"));
        when(riskProfileService.claimExecutionAtomicWithPlan(eq("cred1"), any(), anyBoolean(), eq("plan1"), eq(5L), eq("user1"))).thenReturn(true);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        verify(adapter).placeOrder(any(), any(), any(), any());
        verify(strategyPlanService).releasePlanExecution("plan1"); // released in the finally block regardless of outcome
    }

    @Test
    @DisplayName("evaluateSignal: a plan-level slot that IS available lets the order proceed normally, same as before multi-plan support existed for a signal with no plan at all")
    void evaluateSignal_planLevelSlotAvailable_proceedsNormally() {
        signal.setPlanId("plan1");
        var plan = new com.tradevision.model.StrategyPlan();
        plan.setId("plan1"); plan.setMaxConcurrentTrades(2);
        when(strategyPlanRepo.findById("plan1")).thenReturn(Optional.of(plan));
        when(slotReservationService.reserve(eq("plan:plan1"), anyInt(), any(), anyBoolean())).thenReturn(PositionSlotReservationService.SlotReserveResult.reserved("plan-slot-id"));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        verify(adapter).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("evaluateSignal: losing the execution-authorization claim (auto-trade/halt/LIVE-authorization state changed during evaluation) means the exchange is NEVER contacted, and both reservations are released, verified end-to-end through the real public entry point rather than the internal claim method in isolation")
    void executionAuthorizationClaimLost_neverContactsExchange_releasesReservations() {
        when(riskProfileService.claimExecutionAuthorization(any(), anyBoolean())).thenReturn(null);

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        verify(slotReservationService).release("acct-slot-id");
        verify(exposureReservationService).release(anyString());
        // This abort also runs after orderService.markSubmitting(omsOrder), so it must resolve
        // omsOrder too, not just release the reservations.
        verify(orderService).markSubmissionFailed(any(), any());
    }

    @Test
    @DisplayName("evaluateSignal: the final, immediate-pre-exchange strategy plan authorization re-check fails (plan OFF/session change landed during evaluation) -- NEVER contacts the exchange")
    void planAuthorizationLostBeforeFinalCheck_neverContactsExchange() {
        // authorizeExecution is genuinely called TWICE per evaluation -- once early (before slot
        // reservation even happens) and once as the final, immediate-pre-exchange re-check this
        // test actually means to exercise (see this method's own comment in AutoTradeService.java
        // at the finalPlanAuth call site). Sequential stubbing here lets the FIRST call still
        // succeed (so this test reaches slot reservation and the rest of the flow, same as real
        // "plan was fine at the start, then changed mid-evaluation" scenario), while only the
        // SECOND call fails -- an unconditional deny() stub instead would abort at the FIRST call,
        // before any slots were ever reserved, and never actually reach or exercise this method's
        // own final-check abort branch at all.
        when(strategyPlanService.authorizeExecution(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(StrategyPlanService.PlanAuthorizationResult.allow(),
                StrategyPlanService.PlanAuthorizationResult.deny("Strategy plan is disabled."));

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        verify(credentialService).audit(any(), any(), any(), eq("SIGNAL_BLOCKED_PLAN_AUTHORIZATION"), any());
        verify(slotReservationService).release(any());
        verify(exposureReservationService).release(anyString());
        // This abort also runs after orderService.markSubmitting(omsOrder), so it must resolve
        // omsOrder too, not just release the reservations.
        verify(orderService).markSubmissionFailed(any(), any());
    }

    /**
     * Simulates the execution claim being superseded by stubbing claimExecutionAtomicWithPlan
     * to return false, which is the method's real "final claim was superseded" case.
     */
    @Test
    @DisplayName("evaluateSignal: the execution claim was superseded before the final, immediate-pre-exchange re-check (a concurrent halt/resume issued a newer claim in the gap) -- NEVER contacts the exchange")
    void executionClaimSuperseded_beforeFinalCheck_neverContactsExchange() {
        when(riskProfileService.claimExecutionAuthorization(any(), anyBoolean()))
            .thenReturn(new RiskProfileService.ExecutionClaim("claim-1", 5L));
        when(riskProfileService.claimExecutionAtomicWithPlan(any(), eq("claim-1"), anyBoolean(), any(), any(), any()))
            .thenReturn(false);

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        verify(slotReservationService).release("acct-slot-id");
        verify(exposureReservationService).release(anyString());
        // This abort also runs after orderService.markSubmitting(omsOrder), so it must resolve
        // omsOrder too, not just release the reservations.
        verify(orderService).markSubmissionFailed(any(), any());
    }

    @Test
    @DisplayName("evaluateSignal: a non-USDT-quoted symbol is rejected even if somehow present in enabledSymbols (an existing profile predating the config-time check), as a second, defense-in-depth enforcement at the actual money-moving gate")
    void nonUsdtSymbol_rejectedAtExecutionGate_evenIfEnabled() {
        profile.getEnabledSymbols().add("ETHBTC");
        signal.setSymbol("ETHBTC");

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
    }

    // ── Entry/reconcile shared distributed lock ───────────────────────────

    @Test
    @DisplayName("evaluateSignal: a reconciliation pass currently holds the distributed lock for this credential -- refuses to place a new entry order rather than race it")
    void reconciliationLockHeldByOther_refusesEntry() {
        when(distributedLockService.tryAcquireWithDiagnosis(eq("cred1"), any(), any()))
            .thenReturn(new DistributedLockService.LockLease(DistributedLockService.AcquireResult.HELD_BY_OTHER, 0L));

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        verify(distributedLockService, never()).release(any(), any());
        verify(executionContextService).recordTerminal(any(), eq("REJECTED_RECONCILIATION_IN_PROGRESS"), any());
    }

    @Test
    @DisplayName("evaluateSignal: the distributed lock cannot even be verified (genuine infrastructure failure) -- refuses rather than guessing it's safe to proceed")
    void reconciliationLockInfrastructureFailure_refusesEntry() {
        when(distributedLockService.tryAcquireWithDiagnosis(eq("cred1"), any(), any()))
            .thenReturn(new DistributedLockService.LockLease(DistributedLockService.AcquireResult.INFRASTRUCTURE_FAILURE, 0L));

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        verify(executionContextService).recordTerminal(any(), eq("REJECTED_RECONCILIATION_IN_PROGRESS"), any());
    }

    @Test
    @DisplayName("evaluateSignal: the distributed lock is acquired cleanly and released after the entry flow completes -- the ordinary, successful case")
    void reconciliationLockAcquired_releasedAfterEntryCompletes() {
        service.evaluateSignal("user1", signal);

        verify(distributedLockService).tryAcquireWithDiagnosis(eq("cred1"), any(), any());
        verify(distributedLockService).release(eq("cred1"), any());
    }

    // ── Credential lock lease renewed through the order and OCO calls ──────

    /**
     * The 30s lease acquired above must be renewed in the entry flow, since a slow Binance
     * response (up to ~39s across retries) can outlast it, reopening the entry/reconciliation
     * race the lock exists to prevent. These tests verify renew() is genuinely called, with the
     * right credential and instance id, immediately before EACH of the two real network calls
     * this flow makes.
     */
    @Test
    @DisplayName("evaluateSignal: renews the credential lock lease immediately before BOTH the entry order call and the protective OCO call")
    void entryFlow_renewsCredentialLockLease_beforeBothExchangeCalls() {
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        verify(distributedLockService, times(2)).renew(eq("cred1"), any(), eq(java.time.Duration.ofSeconds(30)));
        verify(adapter).placeOrder(any(), any(), any(), any());
        verify(adapter).placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * A failed lease renewal immediately before the entry order is a hard abort, not a
     * non-fatal warning, since nothing exchange-facing has happened yet at that exact point
     * (unlike the OCO renewal, covered separately just below, where an exchange call may
     * already be in flight or an already-open position needs protecting).
     */
    @Test
    @DisplayName("evaluateSignal: a failed lease renewal immediately before the ENTRY order aborts BEFORE contacting the exchange -- nothing irreversible has happened yet at that point, unlike the OCO renewal")
    void entryFlow_leaseRenewalFailsBeforeEntryOrder_abortsBeforeContactingExchange() {
        when(distributedLockService.renew(any(), any(), any())).thenReturn(false);

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        verify(adapter, never()).placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(credentialService).audit(any(), any(), any(), eq("SIGNAL_BLOCKED_LOCK_RENEWAL_FAILED"), any());
        verify(slotReservationService).release(any());
        verify(exposureReservationService).release(anyString());
        // omsOrder was already stamped SUBMITTING (orderService.markSubmitting) well before this
        // abort point is ever reached -- this proves it is resolved right here, not left for the
        // 5-minute stuck-in-SUBMITTING sweep to find and raise a CRITICAL incident over.
        verify(orderService).markSubmissionFailed(any(), any());
    }

    @Test
    @DisplayName("evaluateSignal: the ENTRY lease renewal succeeds but the later OCO lease renewal fails -- the OCO is still placed (non-fatal there), since the entry order already went out and the position already needs protecting")
    void entryFlow_leaseRenewalSucceedsForEntryButFailsForOco_ocoStillPlaced() {
        when(distributedLockService.renew(any(), any(), any())).thenReturn(true, false); // entry renew ok, OCO renew fails
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        verify(adapter).placeOrder(any(), any(), any(), any());
        verify(adapter).placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── Base-asset commission ─────────────────────────────────────────────────

    @Test
    @DisplayName("evaluateSignal: position quantity is net of base-asset commission, not the gross executedQty")
    void baseAssetCommission_deductedFromPositionQuantity() {
        // BUY 1.0 BTC, but 0.001 BTC charged as commission (Binance's default when no BNB discount) —
        // the wallet only actually received 0.999 BTC.
        List<Fill> fills = List.of(new Fill(BigDecimal.valueOf(100.0), BigDecimal.valueOf(1.0),
            BigDecimal.valueOf(0.001), "BTC"));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, fills));

        service.evaluateSignal("user1", signal);

        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        // atLeastOnce, not times(1): entryPriceVerified=true here, so the flow reaches OCO
        // placement, which calls positionRepo.save() a second time to set the OCO id — same
        // object reference both times, so its FINAL state (inspected below) is correct
        // regardless of which specific invocation ArgumentCaptor happens to keep.
        verify(positionRepo, atLeastOnce()).save(positionCaptor.capture());
        assertThat(positionCaptor.getValue().getQuantity()).isEqualByComparingTo("0.999");
        // User's own explicit architectural request, full context in ExecutionContext's own
        // class javadoc: a genuinely successful fill records the FILLED stage with a real
        // position id.
        verify(executionContextService).recordFilled(any(), any());
    }

    @Test
    @DisplayName("evaluateSignal: when the OCO's own step-size rounding protects LESS than the position's real quantity, Position.protectedQuantity records the actual, honest amount covered")
    void ocoRoundedBelowRealQuantity_recordsHonestProtectedQuantity() {
        List<Fill> fills = List.of(new Fill(BigDecimal.valueOf(100.0), BigDecimal.valueOf(1.0), BigDecimal.ZERO, "USDT"));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, fills));
        // The residual (0.1) must be treated as dust for this test's own purpose -- overriding
        // the shared RULES fixture's own minQty=0 (unrealistic for a real exchange, but that
        // shared fixture is also used by other tests that don't care about this distinction),
        // so this test stays focused on protectedQuantity recording, not the escalation
        // behavior exercised below.
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(
            new SymbolRules("BTCUSDT", "BTC", "USDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(0.5), BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO));
        // The OCO placement only actually protects 0.9 of the position's real 1.0 -- step-size
        // rounding genuinely produced a smaller quantity than requested.
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "oco-1", "{}", null, BigDecimal.valueOf(0.9)));

        service.evaluateSignal("user1", signal);

        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepo, atLeastOnce()).save(positionCaptor.capture());
        Position saved = positionCaptor.getValue();
        assertThat(saved.getQuantity()).isEqualByComparingTo("1.0"); // the position's real, full size
        assertThat(saved.getProtectedQuantity()).isEqualByComparingTo("0.9"); // what the OCO actually covers -- honestly less
        // The 0.1 residual is genuinely below this test's own 0.5 minQty -- correctly classified
        // as dust, so this must NOT escalate to emergency flatten.
        verify(positionSafetyService, never()).emergencyFlatten(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("evaluateSignal: an OCO protection residual AT OR ABOVE the exchange's own minQty is a real, meaningful gap, not dust -- cancels the just-placed OCO and emergency-flattens the WHOLE position rather than accepting a known, partially-unprotected state")
    void ocoProtectionGap_meaningfulResidual_emergencyFlattensWholePosition() {
        List<Fill> fills = List.of(new Fill(BigDecimal.valueOf(100.0), BigDecimal.valueOf(1.0), BigDecimal.ZERO, "USDT"));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, fills));
        // minQty=0.05 -- the 0.1 residual is genuinely AT OR ABOVE this, a real tradable amount.
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(
            new SymbolRules("BTCUSDT", "BTC", "USDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(0.05), BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO));
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "oco-1", "{}", null, BigDecimal.valueOf(0.9)));

        service.evaluateSignal("user1", signal);

        verify(positionSafetyService).emergencyFlatten(any(), any(), any(), any(), any(),
            contains("meaningful unprotected residual"));
        // Must NOT have proceeded to the normal "OCO successfully placed" audit trail -- a
        // meaningful gap must never be accepted as a success.
        verify(credentialService, never()).audit(any(), any(), any(), eq("SLTP_OCO_PLACED"), any());
    }

    /**
     * AutoTradeService delegates OCO placement recording to
     * positionMonitorService.atomicSetOcoPlaced. The atomic Mongo update itself (including the
     * OCO_PLACED_BUT_NOT_RECORDED incident on a zero-match update) is covered separately in
     * PositionMonitorServiceTest, which owns that call; this test verifies only that
     * AutoTradeService delegates to it with the correct protectedQuantity argument.
     */
    @Test
    @DisplayName("evaluateSignal: delegates OCO placement recording to positionMonitorService.atomicSetOcoPlaced")
    void ocoPlacementRecording_delegatedToPositionMonitorService() {
        List<Fill> fills = List.of(new Fill(BigDecimal.valueOf(100.0), BigDecimal.valueOf(1.0), BigDecimal.ZERO, "USDT"));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, fills));
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "oco-1", "{}", null, BigDecimal.valueOf(1.0)));

        service.evaluateSignal("user1", signal);

        verify(positionMonitorService).atomicSetOcoPlaced(any(), eq("oco-1"), eq(BigDecimal.valueOf(1.0)), any());
    }

    @Test
    @DisplayName("evaluateSignal: protectedQuantity is passed through to positionMonitorService.atomicSetOcoPlaced exactly as the broker reported it (actualProtectedQuantity), not the position's own full, unprotected quantity")
    void protectedQuantity_passedThroughToPositionMonitorService() {
        List<Fill> fills = List.of(new Fill(BigDecimal.valueOf(100.0), BigDecimal.valueOf(1.0), BigDecimal.ZERO, "USDT"));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, fills));
        // Same dust-not-meaningful override as ocoRoundedBelowRealQuantity_recordsHonestProtectedQuantity's own
        // identical reasoning -- this test needs the normal success path to actually run, not the newer escalation branch.
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(
            new SymbolRules("BTCUSDT", "BTC", "USDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(0.5), BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO));
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "oco-1", "{}", null, BigDecimal.valueOf(0.9)));

        service.evaluateSignal("user1", signal);

        ArgumentCaptor<BigDecimal> protectedQuantityCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(positionMonitorService).atomicSetOcoPlaced(any(), eq("oco-1"), protectedQuantityCaptor.capture(), any());
        boolean anyUpdatePersistsProtectedQuantity = protectedQuantityCaptor.getAllValues().stream()
            .anyMatch(q -> q != null && q.compareTo(BigDecimal.valueOf(0.9)) == 0);
        assertThat(anyUpdatePersistsProtectedQuantity).isTrue();
    }

    @Test
    @DisplayName("evaluateSignal: commission paid in the QUOTE asset does NOT reduce position quantity — only base-asset commission does")
    void quoteAssetCommission_doesNotReduceQuantity() {
        List<Fill> fills = List.of(new Fill(BigDecimal.valueOf(100.0), BigDecimal.valueOf(1.0),
            BigDecimal.valueOf(0.5), "USDT"));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, fills));

        service.evaluateSignal("user1", signal);

        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepo, atLeastOnce()).save(positionCaptor.capture());
        assertThat(positionCaptor.getValue().getQuantity()).isEqualByComparingTo("1.0"); // unchanged — gross qty is what the wallet actually received
    }

    // ── ENTRY_FILLED_UNVERIFIED ────────────────────────────────────────────────

    @Test
    @DisplayName("evaluateSignal: fill confirmed but entry price unverifiable — position marked OPEN+unverified (not a fabricated price), and emergency-flatten is attempted")
    void entryPriceUnverifiable_flattensRatherThanFabricatingPrice() {
        // fillPrice is null/zero AND the fills-lookup fallback also comes back empty — genuinely
        // no way to know what was paid.
        OrderResult noPriceResult = new OrderResult(true, "b1", "sig-sig1", "FILLED",
            BigDecimal.valueOf(1.0), null, "{}", null, List.of());
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(noPriceResult);
        when(adapter.getFillsForOrder(any(), any(), any(), any(), any())).thenReturn(List.of());

        service.evaluateSignal("user1", signal);

        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepo).save(positionCaptor.capture());
        Position saved = positionCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo("OPEN"); // not a separate invisible status
        assertThat(saved.isAvgEntryPriceUnverified()).isTrue();
        assertThat(saved.getAvgEntryPrice()).isNull(); // never fabricated from the client's claimed entry price
        verify(positionSafetyService).emergencyFlatten(any(), any(), any(), any(), eq(saved), any());
    }

    @Test
    @DisplayName("evaluateSignal: the evaluation lease was lost (expired, or reclaimed by another worker) before reaching the exchange -- aborts WITHOUT placing a real order, and releases the slot/exposure reservations")
    void evaluationLeaseLost_abortsWithoutContactingExchange() {
        // The @BeforeEach's own generic findAndModify(TradeCallRecord.class) stub covers BOTH
        // the initial PENDING -> EVALUATING claim (must succeed, or this test never reaches
        // evaluation at all) AND the atomic lease-extension check right before
        // the exchange call -- sequential stubbing here overrides it so the FIRST call (the
        // claim) still succeeds, but the SECOND (the lease-extension re-check this test actually
        // exercises) returns null, simulating a lost/reclaimed lease.
        TradeCallRecord claimed = new TradeCallRecord();
        claimed.setId(signal.getId());
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(TradeCallRecord.class)))
            .thenReturn(claimed, (TradeCallRecord) null);

        service.evaluateSignal("user1", signal);

        // Pricing (getCurrentPrice), sizing (sizePosition), order-book depth
        // (hasSufficientOrderBookDepth) and exchange filter validation (getSymbolRules) are all
        // read-only market-data calls against `adapter` that happen BEFORE this lease re-check,
        // by design, since sizing and risk checks need real numbers to evaluate against. The
        // lease re-check sits as close to the one call that actually matters --
        // adapter.placeOrder(), the real money-moving write -- as this method's structure
        // allows. What this test verifies is that a lost lease never results in a real order
        // being placed.
        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        verify(adapter, never()).placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(slotReservationService).release(any());
        verify(exposureReservationService).release(anyString());
        // This abort also runs after orderService.markSubmitting(omsOrder), so it must resolve
        // omsOrder too, not just release the reservations.
        verify(orderService).markSubmissionFailed(any(), any());
    }

    @Test
    @DisplayName("evaluateSignal: a kill-switch race actually detected -- trading halted DURING this evaluation, after the atomic claim already succeeded -- immediately reverses the just-opened position via emergency flatten rather than proceeding to protect and hold it, as a bounded safety net for the one irreducible window that a purely pre-submission check can never fully close")
    void killSwitchRaceDetected_emergencyFlattensJustOpenedPosition() {
        RiskProfile haltedFresh = new RiskProfile();
        haltedFresh.setId("profile1");
        haltedFresh.setTradingHalted(true);
        when(riskProfileRepo.findById("profile1")).thenReturn(java.util.Optional.of(haltedFresh));
        profile.setId("profile1");
        // adapter.placeOrder must be stubbed to succeed so evaluation actually reaches the
        // kill-switch race re-check this test exercises.
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        verify(positionSafetyService).emergencyFlatten(any(), any(), any(), any(), any(), contains("Kill-switch race"));
        verify(credentialService).audit(any(), any(), any(), eq("KILL_SWITCH_RACE_DETECTED"), any());
        // Must NOT have proceeded to place protective OCO orders on a position that shouldn't exist.
        verify(adapter, never()).placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("evaluateSignal: the executionVersion check finds trading is NOT halted (the normal case) -- proceeds normally, no false-positive emergency flatten")
    void noKillSwitchRace_proceedsNormally() {
        RiskProfile freshNotHalted = new RiskProfile();
        freshNotHalted.setId("profile1");
        freshNotHalted.setTradingHalted(false);
        when(riskProfileRepo.findById("profile1")).thenReturn(java.util.Optional.of(freshNotHalted));
        profile.setId("profile1");

        service.evaluateSignal("user1", signal);

        verify(positionSafetyService, never()).emergencyFlatten(any(), any(), any(), any(), any(),
            contains("Kill-switch race"));
    }

    @Test
    @DisplayName("evaluateSignal: the entry order's own broker/mode/triggerSource/SL-TP metadata is recorded on the OMS Order via recordEntryMetadata, a single record instead of two silently parallel ones")
    void entryOrderMetadata_recordedOnSingleOmsOrder() {
        com.tradevision.model.Order realOmsOrder = new com.tradevision.model.Order();
        realOmsOrder.setId("oms-order-1");
        when(orderService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(realOmsOrder);
        // adapter.placeOrder must be stubbed to succeed so evaluation actually reaches the
        // recordEntryMetadata call this test asserts on.
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        verify(orderService).recordEntryMetadata(eq(realOmsOrder), eq(BrokerType.BINANCE), eq(BrokerMode.TESTNET),
            eq("SIGNAL"), any(), any(), any());
    }

    @Test
    @DisplayName("evaluateSignal: BOTH the initial OMS setup AND its own fallback creation failing still results in a normal, successful position creation -- the real, money-moving trade already happened by this point, and this codebase's own explicit safety principle (see omsServiceThrowsEverywhere_realOrderFlowStillSucceeds) is that a record-keeping failure must never abandon it")
    void bothOmsCreationAttemptsFail_positionStillCreatedSuccessfully() {
        when(orderService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("simulated OMS bug -- both the initial attempt and the fallback hit this same stub"));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        // The order/position flow must complete normally despite BOTH OMS creation attempts
        // blowing up.
        verify(adapter).placeOrder(any(), any(), any(), any());
        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepo, atLeastOnce()).save(positionCaptor.capture());
        assertThat(positionCaptor.getValue().isAvgEntryPriceUnverified()).isFalse();
        // A critical incident must still be raised, since a real order now genuinely has no
        // local metadata record -- this isn't silently swallowed, just non-blocking.
        verify(incidentService, atLeastOnce()).raiseCritical(any(), any(), any(), any(), any(), eq("ORDER_STATE_UNKNOWN"), any());
    }

    /**
     * Proves the LIVE-specific halt -- the exact opposite outcome from
     * bothOmsCreationAttemptsFail_positionStillCreatedSuccessfully just above, which
     * deliberately stays on the default TESTNET credential and is correctly unaffected by this
     * halt behavior.
     */
    @Test
    @DisplayName("evaluateSignal: for a LIVE credential specifically, OMS setup failing HALTS this execution entirely -- the exchange is never contacted, unlike the TESTNET/PAPER case just above")
    void omsSetupFails_liveCredential_haltsBeforeContactingExchange() {
        switchToLive();
        when(orderService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("simulated OMS failure"));

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        verify(slotReservationService).release("acct-slot-id");
        verify(exposureReservationService).release(anyString());
        verify(incidentService).raiseCritical(eq("user1"), eq("cred1"), isNull(), isNull(), any(), eq("OMS_SETUP_FAILED_LIVE_HALT"), any());
    }

    /**
     * evaluateForProfileLocked's LIVE-authorization gate must not block PAPER the same way it
     * blocks LIVE, since profile.isLiveAutoTradeAuthorized() is never set for a PAPER
     * credential (there is no UI or reason to authorize "live" trading on a mode that never
     * touches Binance). This test proves a PAPER credential with liveAutoTradeAuthorized left
     * at its default (false) still reaches order placement, and is never rejected with the
     * LIVE-specific reason.
     */
    @Test
    @DisplayName("evaluateSignal: a PAPER credential trades normally with NO live authorization, since PaperBrokerAdapter never touches Binance and has nothing for that gate to protect")
    void paperMode_noLiveAuthorization_stillTradesNormally() {
        switchToPaper();
        assertThat(profile.isLiveAutoTradeAuthorized()).isFalse(); // deliberately never set -- the whole point
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        verify(adapter).placeOrder(any(), any(), any(), any());
        verify(executionContextService, never()).recordTerminal(any(), eq("REJECTED_LIVE_NOT_AUTHORIZED"), any());
        verify(credentialService, never()).audit(any(), any(), any(), eq("SIGNAL_BLOCKED_LIVE_NOT_AUTHORIZED"), any());
    }

    @Test
    @DisplayName("evaluateSignal: entry price recovered via the fills fallback when fillPrice itself is missing")
    void entryPriceRecoveredViaFillsLookup() {
        OrderResult noPriceResult = new OrderResult(true, "b1", "sig-sig1", "FILLED",
            BigDecimal.valueOf(1.0), null, "{}", null, List.of());
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(noPriceResult);
        when(adapter.getFillsForOrder(any(), any(), any(), any(), any())).thenReturn(
            List.of(new Fill(BigDecimal.valueOf(101.0), BigDecimal.valueOf(1.0), BigDecimal.ZERO, "USDT")));

        service.evaluateSignal("user1", signal);

        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepo, atLeastOnce()).save(positionCaptor.capture());
        Position saved = positionCaptor.getValue();
        assertThat(saved.isAvgEntryPriceUnverified()).isFalse();
        assertThat(saved.getAvgEntryPrice()).isEqualByComparingTo("101.0");
        verify(positionSafetyService, never()).emergencyFlatten(any(), any(), any(), any(), any(), any());
    }

    // ── Exposure reservation ────────────────────────────────────────

    @Test
    @DisplayName("evaluateSignal: exposure reservation rejection releases the slot and never places the order")
    void exposureReservationRejected_releasesSlotAndSkips() {
        when(exposureReservationService.reserve(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(ExposureReservationService.ExposureReserveResult.reject("Would exceed total exposure cap of 1000"));

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        verify(slotReservationService).release("acct-slot-id"); // the slot reserved just before this must be given back
        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(credentialService).audit(eq("user1"), eq("cred1"), any(), eq("SIGNAL_SKIPPED_RISK"), detailCaptor.capture());
        assertThat(detailCaptor.getValue()).containsIgnoringCase("exceed total exposure cap");
        // User's own explicit architectural request, full context in ExecutionContext's own
        // class javadoc: a rejected signal still gets a real, findable terminal record
        // explaining exactly why.
        verify(executionContextService).recordTerminal(any(), eq("REJECTED_EXPOSURE_CAP"), org.mockito.ArgumentMatchers.contains("exceed total exposure cap"));
    }

    // ── Circuit breaker atomicity ───────────────────────────────────

    @Test
    @DisplayName("evaluateSignal: a failed order atomically increments consecutiveOrderFailures via Mongo, trips the circuit breaker at the real post-increment value")
    void failedOrder_atomicallyIncrementsAndTripsCircuitBreaker() {
        profile.setCircuitBreakerThreshold(3);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            new OrderResult(false, null, "sig-sig1", "REJECTED", null, null, "{}", "Insufficient balance", List.of()));

        RiskProfile postIncrement = new RiskProfile();
        postIncrement.setConsecutiveOrderFailures(3); // simulates the database's real atomic post-increment value
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(RiskProfile.class))).thenReturn(postIncrement);

        service.evaluateSignal("user1", signal);

        // Verifies the trip decision used the ACTUAL value MongoDB returned (3, matching the
        // stubbed post-increment), not some locally-recomputed value that could be stale.
        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(credentialService).audit(eq("user1"), eq("cred1"), any(), eq("CIRCUIT_BREAKER_TRIPPED"), detailCaptor.capture());
        assertThat(detailCaptor.getValue()).contains("3 consecutive order failures");
    }

    @Test
    @DisplayName("evaluateSignal: a failed order below the circuit-breaker threshold does not halt")
    void failedOrder_belowThreshold_doesNotHalt() {
        profile.setCircuitBreakerThreshold(5);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            new OrderResult(false, null, "sig-sig1", "REJECTED", null, null, "{}", "Insufficient balance", List.of()));

        RiskProfile postIncrement = new RiskProfile();
        postIncrement.setConsecutiveOrderFailures(1);
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(RiskProfile.class))).thenReturn(postIncrement);

        service.evaluateSignal("user1", signal);

        verify(credentialService, never()).audit(any(), any(), any(), eq("CIRCUIT_BREAKER_TRIPPED"), any());
    }

    // ── Durable evaluation claim ────────────────────────────────────

    @Test
    @DisplayName("evaluateSignal: a signal not in PENDING state (already claimed by another dispatch, or already evaluated) is skipped entirely — never re-evaluated")
    void nonPendingSignal_skipsEvaluationEntirely() {
        when(mongoTemplate.findAndModify(any(), any(), any(), eq(TradeCallRecord.class))).thenReturn(null); // claim fails — not PENDING

        service.evaluateSignal("user1", signal);

        verify(riskProfileRepo, never()).findByUserIdAndAutoTradeEnabledTrue(any());
        verify(adapter, never()).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("evaluateSignal: marks the signal EVALUATED after a completed evaluation, even when no trade was placed")
    void completedEvaluation_marksEvaluatedEvenWithoutATrade() {
        profile.getEnabledSymbols().clear(); // symbol not enabled -> evaluateForProfile returns immediately, no trade

        service.evaluateSignal("user1", signal);

        // This scenario also triggers a VALIDATING signalStatus update (advanceSignalStatus
        // runs before the enabled-symbols check) — meaning more than one
        // mongoTemplate.updateFirst(..., TradeCallRecord.class) call can legitimately happen
        // here. Capturing all of them and finding the specific autoTradeEvalStatus=EVALUATED
        // one is the correct assertion, not assuming exactly one call total.
        org.mockito.ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
            org.mockito.ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(), updateCaptor.capture(), eq(TradeCallRecord.class));
        boolean sawEvaluated = updateCaptor.getAllValues().stream().anyMatch(u -> {
            Object setDoc = u.getUpdateObject().get("$set");
            return setDoc instanceof org.bson.Document doc && "EVALUATED".equals(doc.getString("autoTradeEvalStatus"));
        });
        assertThat(sawEvaluated).isTrue();
    }

    @Test
    @DisplayName("evaluateSignal: an exception during evaluation marks the signal EVALUATION_FAILED, not EVALUATED — visibly distinct from a genuine completion")
    void exceptionDuringEvaluation_marksEvaluationFailed() {
        when(riskProfileRepo.findByUserIdAndAutoTradeEnabledTrue(any())).thenThrow(new RuntimeException("simulated infrastructure failure"));

        service.evaluateSignal("user1", signal);

        org.mockito.ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
            org.mockito.ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(), updateCaptor.capture(), eq(TradeCallRecord.class));
        boolean sawEvaluationFailed = updateCaptor.getAllValues().stream().anyMatch(u -> {
            Object setDoc = u.getUpdateObject().get("$set");
            return setDoc instanceof org.bson.Document doc && "EVALUATION_FAILED".equals(doc.getString("autoTradeEvalStatus"));
        });
        boolean sawEvaluated = updateCaptor.getAllValues().stream().anyMatch(u -> {
            Object setDoc = u.getUpdateObject().get("$set");
            return setDoc instanceof org.bson.Document doc && "EVALUATED".equals(doc.getString("autoTradeEvalStatus"));
        });
        assertThat(sawEvaluationFailed).isTrue();
        assertThat(sawEvaluated).isFalse(); // never marked as a genuine completion
    }

    /**
     * Proves the finalization query includes the ownership condition, not just
     * signal.getId() alone.
     */
    @Test
    @DisplayName("evaluateSignal: the final autoTradeEvalStatus update is conditional on evaluationOwner, not just signal.getId() -- a worker whose lease has since been reclaimed by a different worker cannot overwrite that worker's own state")
    void finalStateUpdate_isConditionalOnEvaluationOwner() {
        service.evaluateSignal("user1", signal);

        org.mockito.ArgumentCaptor<org.springframework.data.mongodb.core.query.Query> queryCaptor =
            org.mockito.ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        verify(mongoTemplate, atLeastOnce()).updateFirst(queryCaptor.capture(), any(), eq(TradeCallRecord.class));
        boolean sawOwnerConditionalQuery = queryCaptor.getAllValues().stream()
            .anyMatch(q -> q.getQueryObject().toJson().contains("evaluationOwner"));
        assertThat(sawOwnerConditionalQuery).isTrue();
    }

    @Test
    @DisplayName("evaluateSignal: the claim gate sets a real evaluationOwner and a future evaluationLeaseUntil, not just autoTradeEvalStartedAt")
    void claimGate_setsRealLease() {
        service.evaluateSignal("user1", signal);

        org.mockito.ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
            org.mockito.ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        // atLeastOnce(), not once() -- the lease-extension re-check also calls findAndModify
        // against TradeCallRecord.class, so getAllValues().get(0) -- the FIRST call, the
        // original PENDING -> EVALUATING claim this test actually means to inspect -- is used
        // instead of getValue() (which would return the LAST call, the lease-extension update
        // that only sets evaluationLeaseUntil, not evaluationOwner).
        verify(mongoTemplate, atLeastOnce()).findAndModify(any(), updateCaptor.capture(), any(), eq(TradeCallRecord.class));
        var setDoc = (org.bson.Document) updateCaptor.getAllValues().get(0).getUpdateObject().get("$set");
        assertThat(setDoc.getString("evaluationOwner")).isNotBlank();
        Object leaseUntil = setDoc.get("evaluationLeaseUntil");
        assertThat(leaseUntil).isInstanceOf(java.time.LocalDateTime.class);
        assertThat((java.time.LocalDateTime) leaseUntil).isAfter(java.time.LocalDateTime.now());
    }

    // ── Server signal pre-flight validation ─────

    @Test
    @DisplayName("evaluateSignal: refuses the trade when the server signal's stop-loss is zero — never opens a position that can't be protected")
    void serverSignalInvalidStopLoss_refusesBeforeOrderPlacement() {
        ServerSignalEngine.Signal noStopLoss = new ServerSignalEngine.Signal("LONG", "BUY", 80.0, 100.0, 0.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(noTradeFilter.check(any(), any(), any(), any(), any(), any()))
            .thenReturn(new NoTradeFilterService.FilterResult(true, null, noStopLoss));

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(credentialService).audit(eq("user1"), eq("cred1"), any(), eq("SIGNAL_NO_TRADE"), detailCaptor.capture());
        assertThat(detailCaptor.getValue()).containsIgnoringCase("must all be positive");
    }

    @Test
    @DisplayName("evaluateSignal: refuses the trade when the server signal contains a non-finite value (NaN)")
    void serverSignalNonFinite_refusesBeforeOrderPlacement() {
        ServerSignalEngine.Signal nanEntry = new ServerSignalEngine.Signal("LONG", "BUY", 80.0, Double.NaN, 95.0, 110.0, 120.0, 130.0, 60, 20, 40);
        when(noTradeFilter.check(any(), any(), any(), any(), any(), any()))
            .thenReturn(new NoTradeFilterService.FilterResult(true, null, nanEntry));

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(credentialService).audit(eq("user1"), eq("cred1"), any(), eq("SIGNAL_NO_TRADE"), detailCaptor.capture());
        assertThat(detailCaptor.getValue()).containsIgnoringCase("non-finite");
    }

    // ── OCO recovery state ────────────────────────────────────────────

    @Test
    @DisplayName("placeExitOcoOrEmergencyFlatten: a recovered-but-not-active OCO (e.g. ALL_DONE) is recorded on the position BEFORE flattening — emergencyFlatten's own state machine then verifies it, rather than this path discarding the known OCO ID")
    void ocoRecoveredButNotActive_recordsIdBeforeFlattening() {
        // Simulates recovery finding a real OCO record whose listOrderStatus wasn't
        // EXEC_STARTED — success=false, but a real ocoOrderListId is still present.
        // OCO protection is only ever attempted after a successful ENTRY — without this stub
        // the entry itself would NPE first, and emergencyFlatten (this test's own verification
        // target) would never be reached at all.
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(false, "recovered-oco-999", "{}",
                "OCO recovered but listOrderStatus=ALL_DONE, not active protection — needs verification before any flatten."));

        service.evaluateSignal("user1", signal);

        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionSafetyService).emergencyFlatten(any(), any(), any(), any(), positionCaptor.capture(), any());
        // atomicSetOcoPlaced is PositionMonitorService's own hardened method, reused rather
        // than duplicated — the real position.setOcoOrderListId(...) mutation happens inside
        // THAT class's own method body, which this test's own mocked positionMonitorService
        // does not execute. The correct thing for this unit test to verify is that the call
        // was made with the right recovered OCO id, BEFORE emergencyFlatten -- the mutation
        // itself is PositionMonitorService's own test responsibility (already covered there).
        verify(positionMonitorService).atomicSetOcoPlaced(any(), eq("recovered-oco-999"), any(), any());
    }

    @Test
    @DisplayName("placeExitOcoOrEmergencyFlatten: a successful OCO placement gets its own real OMS Order record (side=SELL, type=OCO), separate from the entry order's own record")
    void ocoPlacementSuccess_getsOwnOmsOrderRecord() {
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "oco-999", "{}", null));
        com.tradevision.model.Order ocoOrder = new com.tradevision.model.Order();
        ocoOrder.setId("oco-oms-1");
        when(orderService.create(any(), any(), any(), any(), any(), eq("SELL"), eq("OCO"), any(), any(), any()))
            .thenReturn(ocoOrder);

        service.evaluateSignal("user1", signal);

        verify(orderService).create(any(), any(), any(), any(), any(), eq("SELL"), eq("OCO"), any(), any(), any());
        verify(orderService).markRiskAccepted(ocoOrder);
        verify(orderService).markSubmitting(ocoOrder);
        ArgumentCaptor<OcoOrderResult> resultCaptor = ArgumentCaptor.forClass(OcoOrderResult.class);
        verify(orderService).recordOcoPlacementResult(eq(ocoOrder), resultCaptor.capture());
        assertThat(resultCaptor.getValue().success()).isTrue();
        assertThat(resultCaptor.getValue().ocoOrderListId()).isEqualTo("oco-999");
    }

    @Test
    @DisplayName("placeExitOcoOrEmergencyFlatten: OMS setup failing for the OCO placement never blocks or delays the real OCO placement call itself -- the same non-fatal, additive guarantee the entry order's own OMS wiring already has")
    void ocoOmsSetupFailure_realOcoPlacementStillSucceeds() {
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "oco-999", "{}", null));
        when(orderService.create(any(), any(), any(), any(), any(), eq("SELL"), eq("OCO"), any(), any(), any()))
            .thenThrow(new RuntimeException("simulated OMS bug"));

        service.evaluateSignal("user1", signal);

        verify(adapter).placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any());
        // Same as ocoRecoveredButNotActive_recordsIdBeforeFlattening above: the real position
        // mutation happens inside PositionMonitorService's own atomicSetOcoPlaced, mocked here
        // -- verify the call itself, not a mutation the mock does not perform.
        verify(positionMonitorService).atomicSetOcoPlaced(any(), eq("oco-999"), any(), any());
    }

    /**
     * Proves the LIVE-specific halt for the initial entry OCO, the same pattern already proved
     * for PositionMonitorService's own 3 OCO call sites.
     */
    @Test
    @DisplayName("placeExitOcoOrEmergencyFlatten: for a LIVE credential, a failed pre-submission ProtectionAttempt HALTS before the initial entry OCO exchange call is ever made")
    void initialEntryOco_protectionAttemptFails_liveCredential_haltsBeforeOcoCall() {
        switchToLive();
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));
        when(positionMonitorService.createProtectionAttempt(any(), any(), any(), any(), any())).thenReturn(null);

        service.evaluateSignal("user1", signal);

        verify(adapter, never()).placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(positionMonitorService).haltForProtectionAttemptPersistenceFailure(eq(credential), any());
        verify(incidentService).raiseCritical(any(), eq("cred1"), any(), isNull(), any(),
            eq("PROTECTION_ATTEMPT_PERSISTENCE_FAILED_LIVE_HALT"), any());
    }

    @Test
    @DisplayName("placeExitOcoOrEmergencyFlatten: a genuine placement failure with NO recovered OCO ID leaves the position's ocoOrderListId null — nothing fabricated")
    void ocoGenuineFailureNoRecovery_leavesOcoIdNull() {
        // Same entry-order stub as ocoRecoveredButNotActive_recordsIdBeforeFlattening above.
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(OcoOrderResult.failure("insufficient balance", "{}"));

        service.evaluateSignal("user1", signal);

        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionSafetyService).emergencyFlatten(any(), any(), any(), any(), positionCaptor.capture(), any());
        assertThat(positionCaptor.getValue().getOcoOrderListId()).isNull();
    }

    /**
     * A verification-uncertain result must NEVER trigger emergencyFlatten, since there's
     * nothing to verify a real OCO against in that state.
     */
    @Test
    @DisplayName("placeExitOcoOrEmergencyFlatten: a verification-uncertain OCO result NEVER triggers emergencyFlatten -- halts and escalates instead of guessing")
    void ocoVerificationUncertain_neverFlattens_escalatesInstead() {
        switchToLive();
        // LIVE now refuses to call the exchange unless the pre-submission ProtectionAttempt persisted
        // (a null id is the halt path, tested separately) -- this test targets the post-call branch.
        when(positionMonitorService.createProtectionAttempt(any(), any(), any(), any(), any())).thenReturn("attempt-1");
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(OcoOrderResult.uncertain("network timeout during recovery", null));

        service.evaluateSignal("user1", signal);

        verify(positionSafetyService, never()).emergencyFlatten(any(), any(), any(), any(), any(), any());
        verify(positionMonitorService).haltForProtectionAttemptPersistenceFailure(eq(credential), any());
        verify(incidentService).raiseCritical(any(), eq("cred1"), any(), isNull(), any(),
            eq("OCO_STATE_UNKNOWN_AFTER_PLACEMENT_ERROR"), any());
    }


    @Test
    @DisplayName("evaluateSignal: a successful entry order is driven through the OMS's real state machine — create -> RISK_ACCEPTED -> SUBMITTING -> recordBrokerResult, ending FILLED")
    void successfulEntry_drivesRealOmsStateMachine() {
        com.tradevision.model.Order realOmsOrder = new com.tradevision.model.Order();
        realOmsOrder.setId("oms-order-1");
        when(orderService.create(eq("user1"), eq("cred1"), any(), eq("sig1"), eq("BTCUSDT"), eq("BUY"), eq("MARKET"), any(), any(), any()))
            .thenReturn(realOmsOrder);

        service.evaluateSignal("user1", signal);

        // Setup calls happened in the correct order, against the SAME order object.
        var inOrder = org.mockito.Mockito.inOrder(orderService);
        inOrder.verify(orderService).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        inOrder.verify(orderService).markRiskAccepted(realOmsOrder);
        inOrder.verify(orderService).markSubmitting(realOmsOrder);
        inOrder.verify(orderService).recordBrokerResult(eq(realOmsOrder), any());
    }

    @Test
    @DisplayName("evaluateSignal: when the OMS order was created, the fill ledger is populated with that order's id (Fill Ledger references the OMS Order)")
    void successfulEntry_populatesFillLedgerWithOmsOrderId() {
        com.tradevision.model.Order realOmsOrder = new com.tradevision.model.Order();
        realOmsOrder.setId("oms-order-1");
        when(orderService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(realOmsOrder);
        // adapter.placeOrder must be stubbed to succeed, or AutoTradeService's
        // `if (!result.success())` check NPEs on a null result, caught by evaluateSignal's own
        // outer catch (EVALUATION_FAILED) before the recordFills() call this test verifies is
        // ever reached.
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        verify(fillLedgerService).recordFills(eq("oms-order-1"), any(), any(), eq("user1"), eq("cred1"), eq("BTCUSDT"), eq("BUY"), any(), any(), any(), any());
    }

    /**
     * Proves the incident is raised immediately, rather than relying silently on
     * PositionMonitorService's own later reconciliation sweep.
     */
    @Test
    @DisplayName("evaluateSignal: positionRepo.save() throwing after a confirmed fill raises a CRITICAL POSITION_SAVE_FAILED incident immediately, naming the broker order")
    void positionSaveFailure_raisesCriticalIncidentImmediately() {
        com.tradevision.model.Order realOmsOrder = new com.tradevision.model.Order();
        realOmsOrder.setId("oms-order-1");
        when(orderService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(realOmsOrder);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));
        when(positionRepo.save(any())).thenThrow(new RuntimeException("simulated Mongo write failure"));

        service.evaluateSignal("user1", signal);

        verify(incidentService).raiseCritical(eq("user1"), eq("cred1"), isNull(), eq("oms-order-1"), eq("BTCUSDT"),
            eq("POSITION_SAVE_FAILED"), contains("simulated Mongo write failure"));
    }

    // ── Position.ledgerRecordingIncomplete ──

    @Test
    @DisplayName("evaluateSignal: when recordFills returns as many records as fills provided, ledgerRecordingIncomplete stays false")
    void ledgerFullyRecorded_flagStaysFalse() {
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));
        FillRecord fr = mock(FillRecord.class);
        when(fillLedgerService.recordFills(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(java.util.List.of(fr)); // one record — matches a single aggregate fill (no per-fill data in this test's setup)

        service.evaluateSignal("user1", signal);

        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepo, atLeastOnce()).save(positionCaptor.capture());
        assertThat(positionCaptor.getValue().isLedgerRecordingIncomplete()).isFalse();
    }

    @Test
    @DisplayName("evaluateSignal: when recordFills returns FEWER records than expected (a real gap), ledgerRecordingIncomplete is set true, making a silent ledger failure visible")
    void ledgerRecordingFailed_flagSetTrue() {
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));
        when(fillLedgerService.recordFills(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(java.util.List.of()); // zero records despite a real fill having happened

        service.evaluateSignal("user1", signal);

        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepo, atLeastOnce()).save(positionCaptor.capture());
        assertThat(positionCaptor.getValue().isLedgerRecordingIncomplete()).isTrue();
    }

    @Test
    @DisplayName("evaluateSignal: when recordFills returns fewer records than expected on a NEW position's entry fill, the profile is halted and a CRITICAL incident is raised, turning a silent gap into a loud, investigatable one")
    void ledgerRecordingFailedOnEntry_haltsProfileAndRaisesIncident() {
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));
        when(fillLedgerService.recordFills(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(java.util.List.of()); // zero records despite a real fill having happened

        service.evaluateSignal("user1", signal);

        assertThat(profile.isTradingHalted()).isTrue();
        assertThat(profile.getHaltReason()).contains("Fill ledger recording failed");
        verify(riskProfileRepo, never()).save(any());
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && Boolean.TRUE.equals(setDoc.getBoolean("tradingHalted"));
        }), eq(RiskProfile.class));
        verify(incidentService).raiseCritical(eq("user1"), any(), any(), any(), any(), eq("FILL_LEDGER_INCOMPLETE"), any());
        // The position itself is STILL created and saved despite the halt -- refusing to record
        // a fill that genuinely happened on the exchange would be worse than a flagged gap.
        verify(positionRepo, atLeastOnce()).save(any());
    }

    @Test
    @DisplayName("evaluateSignal: a genuine position-ledger mismatch at entry halts the profile and raises a CRITICAL incident, elevating a cross-check from pure observability to a real consequence")
    void positionLedgerMismatchAtEntry_haltsProfileAndRaisesIncident() {
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));
        when(positionLedgerService.reconcileAgainstLedger(any(), any()))
            .thenReturn(new PositionLedgerService.ReconcileResult(PositionLedgerService.ReconcileStatus.MISMATCH, BigDecimal.valueOf(0.5), BigDecimal.valueOf(1.0)));

        service.evaluateSignal("user1", signal);

        assertThat(profile.isTradingHalted()).isTrue();
        assertThat(profile.getHaltReason()).contains("Position ledger mismatch");
        verify(riskProfileRepo, never()).save(any());
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && Boolean.TRUE.equals(setDoc.getBoolean("tradingHalted"));
        }), eq(RiskProfile.class));
        verify(incidentService).raiseCritical(eq("user1"), any(), any(), any(), any(), eq("POSITION_LEDGER_MISMATCH"), any());
        // The position itself is still created despite the halt -- same principle as the
        // fill-ledger-failure escalation: a genuine order/fill already happened.
        verify(positionRepo, atLeastOnce()).save(any());
    }

    @Test
    @DisplayName("evaluateSignal: the same client order id is used for both the OMS record and the actual broker request, and it stays within Binance's own 36-character limit")
    void clientOrderId_consistentBetweenOmsAndBrokerRequest_andWithinLengthLimit() {
        com.tradevision.model.Order realOmsOrder = new com.tradevision.model.Order();
        realOmsOrder.setId("oms-order-1");
        when(orderService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(realOmsOrder);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        ArgumentCaptor<String> omsClientOrderIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(orderService, atLeastOnce()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), omsClientOrderIdCaptor.capture());
        String omsClientOrderId = omsClientOrderIdCaptor.getAllValues().get(0); // the ENTRY record is created first; the OCO exit record follows

        ArgumentCaptor<OrderRequest> orderReqCaptor = ArgumentCaptor.forClass(OrderRequest.class);
        verify(adapter).placeOrder(any(), any(), any(), orderReqCaptor.capture());
        String brokerClientOrderId = orderReqCaptor.getValue().clientOrderId();

        assertThat(omsClientOrderId).isEqualTo(brokerClientOrderId); // one id, not two independently-generated strings
        assertThat(brokerClientOrderId.length()).isLessThanOrEqualTo(36); // Binance's own documented limit
        assertThat(brokerClientOrderId).startsWith("tv-s-");
    }

    @Test
    @DisplayName("the same signal evaluated against two different auto-trade credentials produces two DIFFERENT clientOrderIds — a signal-id-only basis would produce the SAME id for both, which the unique index would then reject as a duplicate for the second credential")
    void sameSignal_twoCredentials_producesDistinctClientOrderIds() {
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        // First credential ("cred1", the shared @BeforeEach fixture).
        service.evaluateSignal("user1", signal);
        ArgumentCaptor<OrderRequest> firstCaptor = ArgumentCaptor.forClass(OrderRequest.class);
        verify(adapter, atLeastOnce()).placeOrder(any(), any(), any(), firstCaptor.capture());
        String cred1ClientOrderId = firstCaptor.getAllValues().get(0).clientOrderId();

        // Second credential ("cred2") evaluating the exact same signal object (same signal.getId()).
        // Deliberately NOT resetting the "adapter" mock -- its @BeforeEach default stubs (like
        // placeExitOco) must stay in place for this second evaluation to reach placeOrder too.
        profile.setCredentialId("cred2");
        credential.setId("cred2");
        when(credentialService.ownedCredential("user1", "cred2")).thenReturn(credential);
        when(credentialService.adapterForCredential(credential)).thenReturn(adapter);
        when(credentialService.decrypt(credential, true)).thenReturn("key");
        when(credentialService.decrypt(credential, false)).thenReturn("secret");
        when(noTradeFilter.check(eq("user1"), eq("cred2"), eq(signal), eq(adapter), eq("key"), eq(BrokerMode.TESTNET)))
            .thenReturn(new NoTradeFilterService.FilterResult(true, null, serverSignal));
        when(adapter.getCurrentPrice("BTCUSDT", BrokerMode.TESTNET)).thenReturn(BigDecimal.valueOf(100.0));
        when(adapter.getSymbolRules("BTCUSDT", BrokerMode.TESTNET)).thenReturn(RULES);
        when(adapter.getBalance("key", "secret", BrokerMode.TESTNET)).thenReturn(
            List.of(new AssetBalance("USDT", BigDecimal.valueOf(10000), BigDecimal.ZERO)));
        when(slotReservationService.reserve(eq("cred2"), anyInt(), any(), anyBoolean())).thenReturn(PositionSlotReservationService.SlotReserveResult.reserved("acct-slot-id-2"));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);
        ArgumentCaptor<OrderRequest> secondCaptor = ArgumentCaptor.forClass(OrderRequest.class);
        verify(adapter, atLeast(2)).placeOrder(any(), any(), any(), secondCaptor.capture());
        // The captor now holds both calls (first + second evaluation) -- the LAST one is this
        // second credential's own entry order.
        String cred2ClientOrderId = secondCaptor.getAllValues().get(secondCaptor.getAllValues().size() - 1).clientOrderId();

        assertThat(cred2ClientOrderId).isNotEqualTo(cred1ClientOrderId);
    }

    @Test
    @DisplayName("evaluateSignal: OrderService throwing at every OMS call still results in a normal, successful order placement — a bug in the additive OMS record must never block or corrupt the real, money-moving order flow")
    void omsServiceThrowsEverywhere_realOrderFlowStillSucceeds() {
        when(orderService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("simulated OMS bug"));
        // This test's own assertions (positionRepo.save, isAvgEntryPriceUnverified) require
        // passing the `if (!result.success())` check in AutoTradeService.java, which
        // dereferences result with no null guard — without this stub, Mockito's default null
        // return for this unstubbed record-returning method would NPE before ever reaching
        // positionRepo.save(), which the OMS-throws-first framing of this test could otherwise
        // hide (the OMS failure happens first and is also caught, making it easy to mistake the
        // TRUE point of failure).
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        // The order/position flow must complete normally despite the OMS blowing up at the
        // very first call -- that is the point of this additive design.
        verify(adapter).placeOrder(any(), any(), any(), any());
        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepo, atLeastOnce()).save(positionCaptor.capture());
        assertThat(positionCaptor.getValue().isAvgEntryPriceUnverified()).isFalse();
        // OMS setup failed, so recordBrokerResult must never be attempted against a
        // possibly-half-initialized (here: entirely absent) order record.
        verify(orderService, never()).recordBrokerResult(any(), any());
    }

    // ── Signal lifecycle ─────────────────────────────────────────

    @Test
    @DisplayName("evaluateSignal: a fully successful entry progresses the in-memory signal all the way to EXECUTED")
    void successfulEntry_signalReachesExecuted() {
        // EXECUTED is only ever set on the success path PAST the `if (!result.success())`
        // null-dereference — without this stub the signal would never reach EXECUTED at all,
        // it would land on EVALUATION_FAILED instead.
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        assertThat(signal.getSignalStatus()).isEqualTo(com.tradevision.model.SignalStatus.EXECUTED);
    }

    @Test
    @DisplayName("evaluateSignal: the profile's own correlationGroups/correlationGroupCaps are actually passed through to the atomic exposure reservation, not silently dropped")
    void successfulEntry_passesCorrelationGroupsToReservation() {
        var groups = java.util.Map.of("L1-majors", java.util.Set.of("BTCUSDT"));
        var caps = java.util.Map.of("L1-majors", BigDecimal.valueOf(5000));
        profile.setCorrelationGroups(groups);
        profile.setCorrelationGroupCaps(caps);

        service.evaluateSignal("user1", signal);

        verify(exposureReservationService).reserve(any(), any(), any(), any(), any(), eq(groups), eq(caps), any(), anyBoolean());
    }

    @Test
    @DisplayName("evaluateSignal: a risk-engine rejection sets RISK_REJECTED, never reaching APPROVED or beyond")
    void riskRejection_setsRiskRejected() {
        when(riskEngine.check(any(), any(), any())).thenReturn(RiskEngineService.RiskCheckResult.reject("daily loss limit reached"));

        service.evaluateSignal("user1", signal);

        assertThat(signal.getSignalStatus()).isEqualTo(com.tradevision.model.SignalStatus.RISK_REJECTED);
        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        // User's own explicit architectural request, full context in ExecutionContext's own
        // class javadoc: a real, findable terminal record with the actual rejection reason.
        verify(executionContextService).recordTerminal(any(), eq("REJECTED_RISK_ENGINE"), eq("daily loss limit reached"));
    }

    @Test
    @DisplayName("evaluateSignal: symbol not enabled for this profile stops at VALIDATING — never advances further, and never regresses if a later profile already got further")
    void symbolNotEnabled_stopsAtValidating() {
        profile.getEnabledSymbols().clear();

        service.evaluateSignal("user1", signal);

        assertThat(signal.getSignalStatus()).isEqualTo(com.tradevision.model.SignalStatus.VALIDATING);
        // This rejection path must leave a real, findable trace via ExecutionContext, not none
        // at all.
        verify(executionContextService).recordTerminal(any(), eq("REJECTED_SYMBOL_NOT_ENABLED"), any());
    }

    @Test
    @DisplayName("this credential's own reconciliation having failed rejects the signal with REJECTED_CREDENTIAL_RECONCILIATION_PENDING -- confirming the block is scoped to evaluateForProfile (per credential), not a blanket evaluateSignal-level gate")
    void credentialReconciliationPending_rejectedAtCredentialLevel() {
        when(startupState.isCredentialTradingEnabled(profile.getCredentialId())).thenReturn(false);

        service.evaluateSignal("user1", signal);

        verify(executionContextService).recordTerminal(any(), eq("REJECTED_CREDENTIAL_RECONCILIATION_PENDING"), any());
        verify(adapter, never()).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("advanceSignalStatus (via evaluateSignal): a bug in the signal-status update itself never blocks the real order flow — mongoTemplate throwing on this specific update still results in a real, successful trade")
    void signalStatusUpdateThrows_realOrderFlowStillSucceeds() {
        when(mongoTemplate.updateFirst(any(), argThat(u -> {
            Object setDoc = u.getUpdateObject().get("$set");
            return setDoc instanceof org.bson.Document doc && doc.containsKey("signalStatus");
        }), eq(TradeCallRecord.class))).thenThrow(new RuntimeException("simulated database error"));
        // This test's own assertions require passing the `if (!result.success())`
        // null-dereference point.
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(successfulFill(1.0, 100.0, List.of()));

        service.evaluateSignal("user1", signal);

        // The real order flow completed normally despite the signal-status bookkeeping failing.
        verify(adapter).placeOrder(any(), any(), any(), any());
        verify(positionRepo, atLeastOnce()).save(any());
    }

    // ── A market order that reports success but executed zero quantity must not leave an
    // exchange order unmanaged ──────────────────────────────────────────────────

    @Test
    @DisplayName("evaluateSignal: success=true with zero executedQty, verified genuinely terminal with no fill (CANCELED) -- releases the slot/exposure reservation")
    void zeroExecutedQty_verifiedTerminalNoFill_releasesReservation() {
        when(adapter.placeOrder(any(), any(), any(), any()))
            .thenReturn(new OrderResult(true, "b1", "sig-sig1", "NEW", BigDecimal.ZERO, BigDecimal.valueOf(100), "{}", null, List.of()));
        when(adapter.getOrderStatusByClientOrderId(any(), any(), any(), any(), any()))
            .thenReturn(new com.tradevision.service.broker.dto.OrderStatusInfo("CANCELED", BigDecimal.ZERO, null, "{}"));

        service.evaluateSignal("user1", signal);

        verify(exposureReservationService).release(anyString());
        verify(positionRepo, never()).save(any());
    }

    @Test
    @DisplayName("evaluateSignal: success=true with zero executedQty, but the REAL order is non-terminal (NEW) on the exchange -- does NOT release the reservation, since it could still fill, and raises a critical incident instead")
    void zeroExecutedQty_verifiedNonTerminal_doesNotReleaseAndRaisesIncident() {
        when(adapter.placeOrder(any(), any(), any(), any()))
            .thenReturn(new OrderResult(true, "b1", "sig-sig1", "NEW", BigDecimal.ZERO, BigDecimal.valueOf(100), "{}", null, List.of()));
        when(adapter.getOrderStatusByClientOrderId(any(), any(), any(), any(), any()))
            .thenReturn(new com.tradevision.service.broker.dto.OrderStatusInfo("NEW", BigDecimal.ZERO, null, "{}"));

        service.evaluateSignal("user1", signal);

        verify(exposureReservationService, never()).release(anyString());
        verify(incidentService).raiseCritical(any(), eq("cred1"), isNull(), any(), any(),
            eq("ENTRY_ORDER_UNCONFIRMED_NON_TERMINAL"), any());
    }

    @Test
    @DisplayName("evaluateSignal: success=true with zero executedQty, and the verification query itself throws -- treated as genuinely unknown, NOT released, same incident raised as the non-terminal case")
    void zeroExecutedQty_verificationQueryThrows_doesNotReleaseAndRaisesIncident() {
        when(adapter.placeOrder(any(), any(), any(), any()))
            .thenReturn(new OrderResult(true, "b1", "sig-sig1", "NEW", BigDecimal.ZERO, BigDecimal.valueOf(100), "{}", null, List.of()));
        when(adapter.getOrderStatusByClientOrderId(any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("simulated network timeout"));

        service.evaluateSignal("user1", signal);

        verify(exposureReservationService, never()).release(anyString());
        verify(incidentService).raiseCritical(any(), eq("cred1"), isNull(), any(), any(),
            eq("ENTRY_ORDER_UNCONFIRMED_NON_TERMINAL"), any());
    }
}
