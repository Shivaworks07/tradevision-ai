package com.tradevision.service;

import com.tradevision.model.*;
import com.tradevision.repository.PositionRepository;
import com.tradevision.repository.RiskProfileRepository;
import com.tradevision.repository.TradeCallRepository;
import com.tradevision.service.broker.BrokerAdapter;
import com.tradevision.service.broker.dto.AssetBalance;
import com.tradevision.service.broker.dto.Fill;
import com.tradevision.service.broker.dto.OcoOrderResult;
import com.tradevision.service.broker.dto.OcoStatusInfo;
import com.tradevision.service.broker.dto.OpenOrderInfo;
import com.tradevision.service.broker.dto.OrderRequest;
import com.tradevision.service.broker.dto.OrderResult;
import com.tradevision.service.broker.dto.SymbolRules;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Review finding ("Emergency-flatten automated tests: Missing"): the naked-position safety net
 * (review's own words: "the single most dangerous state an auto-trader can be in") had no
 * dedicated test coverage until this file. Every test here traces its expected numbers by hand
 * against the actual PositionSafetyService source before being written — not just asserted to
 * "look right" — the same discipline used throughout this session, since a wrong test is worse
 * than no test (it looks like coverage while proving nothing).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PositionSafetyServiceTest {

    @Mock BrokerCredentialService credentialService;
    @Mock PositionRepository positionRepo;
    @Mock RiskEngineService riskEngine;
    @Mock RiskProfileRepository riskProfileRepo;
    @Mock PositionSlotReservationService slotReservationService;
    @Mock IncidentService incidentService;
    @Mock ExecutionContextService executionContextService;
    @Mock ExposureReservationService exposureReservationService;
    @Mock TradeCallRepository callRepo;
    @Mock FillLedgerService fillLedgerService;
    @Mock BrokerAdapter adapter;
    // Review finding ("Position P&L architecture is still scattered" -- full context in
    // RealizedPnlService's own javadoc): @Spy (a REAL instance) rather than @Mock, since this
    // service has no dependencies of its own -- a bare @Mock would return null from
    // calculate(), NPEing on pnlResult.realizedPnl() in every existing test that reaches these
    // P&L paths, which never needed to stub anything before this consolidation existed.

    @Spy RealizedPnlService realizedPnlService = new RealizedPnlService();
    // Review finding ("OMS not actually authoritative" -- P0, full context in
    // PositionSafetyService's own new dependency comment): needed now that the emergency-flatten
    // market order gets its own real OMS Order record.
    @Mock OrderService orderService;
    // Review finding ("Position Ledger is still not authoritative" -- full context in
    // PositionLedgerService's own javadoc): @Mock with a default "genuine match" stub added in
    // @BeforeEach below -- same reasoning as PositionMonitorServiceTest's own identical addition.
    @Mock PositionLedgerService positionLedgerService;
    // Review finding ("Position close has atomic protection; not every position mutation does"
    // -- P1, full context in PositionMonitorServiceTest's own identical addition): needed now
    // that emergency-flatten's own full close uses a real conditional update.
    @Mock org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    // Review finding ("Emergency flatten can execute twice concurrently" -- P0, full context at
    // the actual lock acquisition in PositionSafetyService.emergencyFlatten): needed now that
    // every emergencyFlatten() call requires a successful lock acquisition before doing
    // anything at all.
    @Mock DistributedLockService distributedLockService;
    @Mock com.tradevision.repository.FlattenAttemptRepository flattenAttemptRepo;
    // P3-11 second re-audit fix ("only cancel orders that belong to the position being flattened
    // or that aren't tracked at all" -- full context in PositionSafetyService's own updated
    // cancelOtherOpenOrdersForSymbol javadoc): needed now that stray-order cancellation looks up
    // real OMS ownership before cancelling anything.
    @Mock com.tradevision.repository.OrderRepository orderRepository;

    @InjectMocks PositionSafetyService service;

    private BrokerCredential credential;
    private RiskProfile profile;

    private static final SymbolRules USDT_RULES =
        new SymbolRules("BTCUSDT", "BTC", "USDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 2, 6, BigDecimal.ZERO, false, false, BigDecimal.ZERO, BigDecimal.ZERO);

    @BeforeEach
    void setup() {
        // Review finding ("Emergency flatten can execute twice concurrently" -- P0, full context
        // at the actual lock acquisition in PositionSafetyService.emergencyFlatten): a realistic
        // "this call won the lock" default -- Mockito's own real default for an unstubbed
        // boolean-returning method is false, which would otherwise make EVERY existing test in
        // this file silently no-op (the lock acquisition would fail, emergencyFlatten would
        // return immediately, and every assertion checking real side effects would fail for the
        // wrong reason). A test that specifically wants to exercise the lost-the-race path
        // overrides this explicitly.
        // Review finding ("Emergency flatten lock failure is ambiguous" -- external review, full
        // context at DistributedLockService.tryAcquireWithDiagnosis's own javadoc): this
        // method's own call site was converted to the new, richer result type -- this default
        // must match, or every existing test in this file would silently fall through to the
        // "acquired" branch by accident (null != either failure enum value) rather than through
        // an explicit, intentional default.
        //
        // Review finding ("DistributedLockService has a subtle generation race" -- external
        // review, twenty-ninth pass, P1, full context in DistributedLockService.LockLease's own
        // javadoc): production code now calls tryAcquireWithDiagnosis() exclusively (the plain
        // boolean tryAcquire() is never called by anything this file exercises), and reads the
        // generation directly off the returned LockLease -- no separate currentGeneration()
        // stub needed or used anymore.
        when(distributedLockService.tryAcquireWithDiagnosis(any(), any(), any()))
            .thenReturn(new DistributedLockService.LockLease(DistributedLockService.AcquireResult.ACQUIRED, 1L));
        // Review finding ("DistributedLockService.renew() does not verify ownership generation"
        // -- external review, second pass): a genuine, long-standing gap in this file's own
        // test setup, only now surfacing -- there was NEVER a default stub for renew() at all in
        // this file, at any point. This went unnoticed because, before this session's own P0-1
        // fix ("Emergency-flatten lock can still be lost while the operation continues"), the
        // production code logged a warning on renewal failure and proceeded regardless -- the
        // return value was genuinely irrelevant to every test's own outcome. Once that fix made
        // renewal failure a hard stop, Mockito's own real default (false, for an unstubbed
        // boolean-returning call) should have silently broken every other test in this file
        // right then -- caught now, while migrating renew()'s own call sites to the new,
        // generation-aware 4-arg overload this pass adds, not before. Fixed properly here rather
        // than left to coincidentally keep working.
        when(distributedLockService.renew(any(), any(), anyLong(), any())).thenReturn(true);
        // Review finding ("Position close has atomic protection; not every position mutation
        // does" -- P1, full context at the actual new atomic update in finalizeFlatten): a
        // realistic "the conditional update succeeded" default, same reasoning as
        // PositionMonitorServiceTest's own identical addition -- an unstubbed updateFirst()
        // would otherwise NPE every existing test that reaches the full-close path.
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(Position.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
        // P2-8 fix, full context in Position.flattenEpisode's own field javadoc: the OPEN ->
        // FLATTENING transition now uses findAndModify (to read back the atomically-incremented
        // flattenEpisode) instead of updateFirst -- a realistic "the conditional transition
        // succeeded, this is now episode 1" default, matching this file's own existing
        // updateFirst default's reasoning. An unstubbed findAndModify returns null, which
        // production code treats as "transition failed" and aborts before any real sell --
        // exactly the kind of silent, wrong-reason test failure this file's own existing
        // defaults are already written to avoid. A test that wants a SPECIFIC episode number
        // (e.g. proving a second episode gets a different one) overrides this explicitly.
        when(mongoTemplate.findAndModify(any(), any(org.springframework.data.mongodb.core.query.Update.class),
                any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(Position.class)))
            .thenAnswer(invocation -> {
                Position updated = new Position();
                updated.setStatus("FLATTENING");
                updated.setFlattenEpisode(1);
                return updated;
            });
        credential = new BrokerCredential();
        credential.setId("cred1");
        credential.setBroker(BrokerType.BINANCE);
        credential.setMode(BrokerMode.TESTNET);

        profile = new RiskProfile();
        profile.setCredentialId("cred1");

        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(Optional.of(profile));
        when(adapter.getSymbolRules(any(), any())).thenReturn(USDT_RULES);
        // Review finding (🟠 #15 — "Emergency flatten still needs a true exchange-quantity
        // source"): attemptFlatten now checks actual free balance before selling — needed here
        // so every EXISTING test (which never cared about this balance check before) isn't
        // silently capped to zero by Mockito's default empty list for an unstubbed getBalance(),
        // which would otherwise skip the market sell entirely in every one of them. Generous
        // (1000 BTC) — comfortably above any quantity used in this file's existing tests, so
        // this default never actually caps anything unless a test explicitly overrides it.
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(1000), BigDecimal.ZERO)));

        // Review finding ("Fill Ledger can still fail without stopping financial state changes"
        // -- full context in recordPartialFlattenPnl's own javadoc): a realistic "successful
        // recording" default, matching the review's own reasoning for the getBalance() default
        // just above -- every EXISTING test in this file that never cared about fill-ledger
        // recording specifically (most of them) now gets a genuine, size-matched success rather
        // than Mockito's default empty list, which would otherwise silently exercise this
        // pass's own new halt-on-ledger-failure escalation in tests that never intended to test
        // it. A test that DOES want to exercise the failure path overrides this explicitly.
        when(fillLedgerService.recordFills(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(invocation -> {
                List<?> fills = invocation.getArgument(7);
                BigDecimal aggregateQty = invocation.getArgument(8);
                int count = (fills != null && !fills.isEmpty()) ? fills.size()
                    : (aggregateQty != null && aggregateQty.signum() > 0 ? 1 : 0);
                List<FillRecord> result = new java.util.ArrayList<>();
                for (int i = 0; i < count; i++) result.add(mock(FillRecord.class));
                return result;
            });
        // Review finding ("Position Ledger is still not authoritative" -- full context in
        // PositionLedgerService's own javadoc): a realistic "genuine match" default, same
        // reasoning as the recordFills default just above -- an unstubbed
        // reconcilePositionAgainstLedger would otherwise return null, NPEing every existing
        // test that reaches the new position-close reconciliation check.
        when(positionLedgerService.reconcilePositionAgainstLedger(any(), any(), any()))
            .thenAnswer(invocation -> new PositionLedgerService.ReconcileResult(PositionLedgerService.ReconcileStatus.MATCH, invocation.getArgument(1), invocation.getArgument(1)));
    }

    private Position openPosition(double qty, double avgEntry, Double entryFee) {
        Position p = new Position();
        p.setId("pos1");
        p.setUserId("user1");
        p.setCredentialId("cred1");
        p.setSymbol("BTCUSDT");
        p.setQuantity(BigDecimal.valueOf(qty));
        p.setAvgEntryPrice(BigDecimal.valueOf(avgEntry));
        if (entryFee != null) p.setEntryFeeQuote(BigDecimal.valueOf(entryFee));
        p.setStatus("OPEN");
        return p;
    }

    private OrderResult fullSuccess(double executedQty, double fillPrice, List<Fill> fills) {
        return new OrderResult(true, "b1", "c1", "FILLED", BigDecimal.valueOf(executedQty),
            BigDecimal.valueOf(fillPrice), "{}", null, fills);
    }

    @Test
    @DisplayName("emergencyFlatten: the distributed lock is renewed before the exchange-facing sell, not just acquired once for a fixed 60 seconds -- the actual review fix (\"Emergency-flatten distributed lock can still expire\")")
    void emergencyFlatten_renewsLockBeforeExchangeFacingSell() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 90, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(distributedLockService, times(2)).renew(eq("flatten:" + position.getId()), any(), anyLong(), any());
    }

    @Test
    @DisplayName("emergencyFlatten: renewal failing before the exchange-facing sell is a hard stop -- NO order is submitted, unlike the previous behavior of logging a warning and proceeding anyway -- the actual review fix (\"Emergency-flatten lock can still be lost while the operation continues\")")
    void emergencyFlatten_renewalFails_hardStopsBeforeAnySell() {
        Position position = openPosition(1.0, 100, 10.0);
        when(distributedLockService.renew(any(), any(), anyLong(), any())).thenReturn(false);

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verifyNoInteractions(adapter);
        verify(credentialService).audit(any(), any(), any(), eq("FLATTEN_LOCK_LOST"), any());
    }

    @Test
    @DisplayName("attemptFlatten: the order fails outright (no confirmed fill quantity at all) -- position's real quantity is still persisted via the atomic conditional update, not silently lost -- the actual review fix (\"Position close has atomic protection; not every position mutation does\")")
    void orderFailsOutright_persistsRealQuantityAtomically() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any()))
            .thenReturn(new OrderResult(false, null, "c1", "REJECTED", null, null, "{}", "insufficient balance", List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        assertThat(position.getStatus()).isEqualTo("FLATTENING"); // Review finding ("Position still has no FLATTENING state" -- external review, second pass): never closed since nothing was confirmed sold, but no longer reverts to OPEN either -- a real flatten attempt happened and failed, which OPEN would misleadingly suggest never occurred. Stays FLATTENING for the recovery mechanism to find.
        ArgumentCaptor<org.springframework.data.mongodb.core.query.Update> updateCaptor =
            ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Update.class);
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(), updateCaptor.capture(), eq(Position.class));
        boolean anyUpdateSetsQuantity = updateCaptor.getAllValues().stream()
            .anyMatch(u -> u.getUpdateObject().get("$set", org.bson.Document.class) != null
                && u.getUpdateObject().get("$set", org.bson.Document.class).get("quantity") != null);
        assertThat(anyUpdateSetsQuantity).isTrue(); // the real quantity was actually written, not lost to a save() that never happened
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_FAILED"), contains("MANUAL INTERVENTION REQUIRED"));
        assertThat(profile.isTradingHalted()).isTrue();
    }

    /**
     * Audit fix (P0-2, "Failed emergency flatten leaves a naked position with no automatic
     * retry" -- full context in attemptFlatten's own updated comment just above its new retry
     * branch). The outright failure above (orderFailsOutright_persistsRealQuantityAtomically)
     * already exercises this retry implicitly (placeOrder is stubbed to fail every time, so the
     * retry fires and also fails) -- these two tests isolate the NEW behavior specifically:
     * attempt 0 failing outright now retries once, and a retry that SUCCEEDS actually saves the
     * naked position rather than halting it.
     */
    @Test
    @DisplayName("attemptFlatten: an outright failure on the first attempt is retried once with a fresh clientOrderId — and a retry that succeeds closes the position instead of halting it")
    void orderFailsOutrightThenRetrySucceeds_closesPositionWithoutHalting() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            new OrderResult(false, null, "c1", "REJECTED", null, null, "{}", "insufficient balance", List.of()),
            fullSuccess(1.0, 105, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter, times(2)).placeOrder(any(), any(), any(), any());
        ArgumentCaptor<OrderRequest> orderCaptor = ArgumentCaptor.forClass(OrderRequest.class);
        verify(adapter, times(2)).placeOrder(any(), any(), any(), orderCaptor.capture());
        assertThat(orderCaptor.getAllValues().get(0).clientOrderId())
            .isNotEqualTo(orderCaptor.getAllValues().get(1).clientOrderId()); // attempt 0 vs attempt 1 — genuinely different ids, not a resubmitted duplicate
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_RETRYING"), any());
        // emergencyFlatten()'s own 6-arg overload always passes haltOnSuccess=true (an emergency
        // flatten is only ever triggered after a genuine protection failure, regardless of
        // whether this specific attempt — or its retry — ultimately succeeds; see this class's
        // own P1-2 javadoc on the haltOnSuccess parameter), so the position closing via the
        // retry does not itself mean the profile stays untouched.
        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED"); // the retry's own success closed it, not an open/unprotected halt
        assertThat(profile.isTradingHalted()).isTrue();
    }

    @Test
    @DisplayName("attemptFlatten: the retry itself also fails outright — now escalates (halted, critical audit), with the retry reflected in the audit message")
    void orderFailsOutrightTwice_retriesOnceThenHalts() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any()))
            .thenReturn(new OrderResult(false, null, "c1", "REJECTED", null, null, "{}", "insufficient balance", List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter, times(2)).placeOrder(any(), any(), any(), any()); // exactly one retry, not an unbounded loop
        assertThat(position.getStatus()).isEqualTo("FLATTENING");
        assertThat(profile.isTradingHalted()).isTrue();
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_RETRYING"), any());
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_FAILED"), contains("retry also failed"));
    }

    /**
     * Audit fix (P0-2 follow-up — external re-review: "The OCO is still cancelled before the
     * sell, so the position can still end up unprotected" — full context in
     * tryReprotectAfterFailedFlatten's own class javadoc). Both tests below exercise its two
     * call sites: an outright failure exhausting its retry, and a partial fill whose own retry
     * also comes back partial.
     */
    @Test
    @DisplayName("attemptFlatten: outright failure exhausts its retry, but a prior OCO_EXIT record lets this re-place protection at the old TP/SL — halted for review, but no longer naked")
    void orderFailsOutrightTwice_reprotectSucceeds_haltsButNoLongerNaked() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any()))
            .thenReturn(new OrderResult(false, null, "c1", "REJECTED", null, null, "{}", "insufficient balance", List.of()));
        com.tradevision.model.Order priorOco = new com.tradevision.model.Order();
        priorOco.setId("oco-order-1");
        priorOco.setTakeProfitPrice(BigDecimal.valueOf(120));
        priorOco.setStopLossTriggerPrice(BigDecimal.valueOf(90));
        priorOco.setStopLossLimitPrice(BigDecimal.valueOf(89.5));
        when(orderRepository.findByPositionIdAndOrderRoleOrderByCreatedAtDesc(position.getId(), "OCO_EXIT"))
            .thenReturn(List.of(priorOco));
        when(adapter.getCurrentPrice(position.getSymbol(), credential.getMode())).thenReturn(BigDecimal.valueOf(100));
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "new-oco-99", "{}", null));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter).placeExitOco(eq("key"), eq("secret"), any(), eq("BTCUSDT"),
            eq(BigDecimal.valueOf(1.0)), eq(BigDecimal.valueOf(120)), eq(BigDecimal.valueOf(90)), eq(BigDecimal.valueOf(89.5)), any());
        assertThat(position.getOcoOrderListId()).isEqualTo("new-oco-99");
        assertThat(position.getStatus()).isEqualTo("OPEN"); // re-protected, not left FLATTENING/naked
        assertThat(profile.isTradingHalted()).isTrue(); // still halts for manual review of why the flatten itself failed
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_FAILED_REPROTECTED"), any());
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_FAILED"), contains("RE-PROTECTED"));
    }

    /**
     * Audit fix (P0-2 follow-up #2 — external re-review of the first follow-up, confirmed real:
     * "it only re-protects if the old levels still bracket the current price... If the price has
     * already fallen through the stop, which is the most urgent case, it returns false and the
     * position stays naked" — full context in tryReprotectAfterFailedFlatten's own updated
     * javadoc). Unlike the reprotect test immediately above (price 100, stop 90 — the old OCO
     * still brackets it), here the fresh price (85) has already fallen THROUGH the old stop
     * trigger (90) — the exact gap the reviewer named. Re-placing the old OCO is unsafe here, so
     * this must escalate to one more market-sell attempt instead of a bare naked halt.
     */
    @Test
    @DisplayName("attemptFlatten: outright failure exhausts its retry, and price has already fallen through the old stop (re-protect OCO unsafe) -- escalates to one more backed-off market sell, which succeeds and closes the position")
    void orderFailsOutrightTwice_priceThroughStop_escalatesAndSellSucceeds() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            new OrderResult(false, null, "c1", "REJECTED", null, null, "{}", "insufficient balance", List.of()), // attempt 0
            new OrderResult(false, null, "c2", "REJECTED", null, null, "{}", "insufficient balance", List.of()), // attempt 1 retry
            fullSuccess(1.0, 84, List.of())); // escalation attempt — succeeds
        com.tradevision.model.Order priorOco = new com.tradevision.model.Order();
        priorOco.setId("oco-order-1");
        priorOco.setTakeProfitPrice(BigDecimal.valueOf(120));
        priorOco.setStopLossTriggerPrice(BigDecimal.valueOf(90));
        priorOco.setStopLossLimitPrice(BigDecimal.valueOf(89.5));
        when(orderRepository.findByPositionIdAndOrderRoleOrderByCreatedAtDesc(position.getId(), "OCO_EXIT"))
            .thenReturn(List.of(priorOco));
        // Price has fallen to 85 -- AT/THROUGH the old stop trigger of 90, not still bracketed by it.
        when(adapter.getCurrentPrice(position.getSymbol(), credential.getMode())).thenReturn(BigDecimal.valueOf(85));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter, times(3)).placeOrder(any(), any(), any(), any()); // attempt 0, its one retry, then exactly one escalation attempt -- not an unbounded loop
        verify(adapter, org.mockito.Mockito.never()).placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()); // never tried to re-place the old, now-unsafe OCO
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_THROUGH_STOP_ESCALATING"), any());
        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED"); // the escalation sell closed it
        assertThat(profile.isTradingHalted()).isTrue();
    }

    @Test
    @DisplayName("attemptFlatten: outright failure exhausts its retry, price has fallen through the old stop, AND the escalation sell also fails -- halts naked with no further recursion, not an infinite loop")
    void orderFailsOutrightTwice_priceThroughStop_escalationAlsoFails_haltsNakedNoRecursion() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any()))
            .thenReturn(new OrderResult(false, null, "c1", "REJECTED", null, null, "{}", "insufficient balance", List.of()));
        com.tradevision.model.Order priorOco = new com.tradevision.model.Order();
        priorOco.setId("oco-order-1");
        priorOco.setTakeProfitPrice(BigDecimal.valueOf(120));
        priorOco.setStopLossTriggerPrice(BigDecimal.valueOf(90));
        priorOco.setStopLossLimitPrice(BigDecimal.valueOf(89.5));
        when(orderRepository.findByPositionIdAndOrderRoleOrderByCreatedAtDesc(position.getId(), "OCO_EXIT"))
            .thenReturn(List.of(priorOco));
        when(adapter.getCurrentPrice(position.getSymbol(), credential.getMode())).thenReturn(BigDecimal.valueOf(85));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        // attempt 0, its one retry, and exactly ONE escalation attempt -- the escalation's own
        // failure must not loop back into another re-protect-or-escalate decision.
        verify(adapter, times(3)).placeOrder(any(), any(), any(), any());
        assertThat(position.getStatus()).isEqualTo("FLATTENING"); // naked, never falsely marked closed
        assertThat(profile.isTradingHalted()).isTrue();
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_THROUGH_STOP_ESCALATING"), any());
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_FAILED"), contains("re-protect escalation retry"));
    }

    /**
     * Audit fix (P0-2 follow-up #3 — user-flagged, confirmed real: "if the price is above the
     * old take-profit when re-protection runs, it returns FAILED and the position stays
     * unprotected. That means the position is in profit, so a plain market sell is the right
     * move" — full context in tryReprotectAfterFailedFlatten's own updated javadoc). Mirrors the
     * through-stop escalation tests above, but for the OPPOSITE, favorable direction: price has
     * moved past the old take-profit, not through the old stop.
     */
    @Test
    @DisplayName("attemptFlatten: outright failure exhausts its retry, and price has already moved past the old take-profit (re-protect OCO unsafe) -- escalates to a market sell that locks in the gain")
    void orderFailsOutrightTwice_priceThroughTakeProfit_escalatesAndSellSucceeds() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            new OrderResult(false, null, "c1", "REJECTED", null, null, "{}", "insufficient balance", List.of()), // attempt 0
            new OrderResult(false, null, "c2", "REJECTED", null, null, "{}", "insufficient balance", List.of()), // attempt 1 retry
            fullSuccess(1.0, 121, List.of())); // escalation attempt — succeeds, locking in the gain
        com.tradevision.model.Order priorOco = new com.tradevision.model.Order();
        priorOco.setId("oco-order-1");
        priorOco.setTakeProfitPrice(BigDecimal.valueOf(120));
        priorOco.setStopLossTriggerPrice(BigDecimal.valueOf(90));
        priorOco.setStopLossLimitPrice(BigDecimal.valueOf(89.5));
        when(orderRepository.findByPositionIdAndOrderRoleOrderByCreatedAtDesc(position.getId(), "OCO_EXIT"))
            .thenReturn(List.of(priorOco));
        // Price has risen to 121 -- PAST the old take-profit of 120, not still bracketed by it.
        when(adapter.getCurrentPrice(position.getSymbol(), credential.getMode())).thenReturn(BigDecimal.valueOf(121));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter, times(3)).placeOrder(any(), any(), any(), any()); // attempt 0, its one retry, then exactly one escalation attempt
        verify(adapter, org.mockito.Mockito.never()).placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()); // never tried to re-place the old, now-unsafe OCO
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_THROUGH_TAKEPROFIT_ESCALATING"), any());
        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED"); // the escalation sell closed it
        assertThat(profile.isTradingHalted()).isTrue();
    }

    @Test
    @DisplayName("attemptFlatten: outright failure exhausts its retry, price has moved past the old take-profit, AND the escalation sell also fails -- halts naked with no further recursion")
    void orderFailsOutrightTwice_priceThroughTakeProfit_escalationAlsoFails_haltsNakedNoRecursion() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any()))
            .thenReturn(new OrderResult(false, null, "c1", "REJECTED", null, null, "{}", "insufficient balance", List.of()));
        com.tradevision.model.Order priorOco = new com.tradevision.model.Order();
        priorOco.setId("oco-order-1");
        priorOco.setTakeProfitPrice(BigDecimal.valueOf(120));
        priorOco.setStopLossTriggerPrice(BigDecimal.valueOf(90));
        priorOco.setStopLossLimitPrice(BigDecimal.valueOf(89.5));
        when(orderRepository.findByPositionIdAndOrderRoleOrderByCreatedAtDesc(position.getId(), "OCO_EXIT"))
            .thenReturn(List.of(priorOco));
        when(adapter.getCurrentPrice(position.getSymbol(), credential.getMode())).thenReturn(BigDecimal.valueOf(121));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter, times(3)).placeOrder(any(), any(), any(), any());
        assertThat(position.getStatus()).isEqualTo("FLATTENING");
        assertThat(profile.isTradingHalted()).isTrue();
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_THROUGH_TAKEPROFIT_ESCALATING"), any());
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_FAILED"), contains("re-protect escalation retry"));
    }

    @Test
    @DisplayName("attemptFlatten: a partial fill whose own retry is also partial — re-protects the remaining, still-open quantity at the old TP/SL rather than leaving it naked")
    void partialThenPartialAgain_reprotectSucceeds_haltsButRemainderNoLongerNaked() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            fullSuccess(0.4, 105, List.of()),
            fullSuccess(0.3, 103, List.of())); // retry only fills 0.3 of the remaining 0.6 — still incomplete, 0.3 remains
        com.tradevision.model.Order priorOco = new com.tradevision.model.Order();
        priorOco.setId("oco-order-1");
        priorOco.setTakeProfitPrice(BigDecimal.valueOf(120));
        priorOco.setStopLossTriggerPrice(BigDecimal.valueOf(90));
        priorOco.setStopLossLimitPrice(BigDecimal.valueOf(89.5));
        when(orderRepository.findByPositionIdAndOrderRoleOrderByCreatedAtDesc(position.getId(), "OCO_EXIT"))
            .thenReturn(List.of(priorOco));
        when(adapter.getCurrentPrice(position.getSymbol(), credential.getMode())).thenReturn(BigDecimal.valueOf(100));
        when(adapter.placeExitOco(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new OcoOrderResult(true, "new-oco-77", "{}", null));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        // re-protects the REMAINING 0.3 still open, not the original 1.0
        verify(adapter).placeExitOco(eq("key"), eq("secret"), any(), eq("BTCUSDT"),
            eq(BigDecimal.valueOf(0.3)), eq(BigDecimal.valueOf(120)), eq(BigDecimal.valueOf(90)), eq(BigDecimal.valueOf(89.5)), any());
        assertThat(position.getOcoOrderListId()).isEqualTo("new-oco-77");
        assertThat(position.getStatus()).isEqualTo("OPEN");
        assertThat(profile.isTradingHalted()).isTrue();
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_FAILED_REPROTECTED"), any());
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_FAILED"), contains("RE-PROTECTED"));
    }

    @Test
    @DisplayName("attemptFlatten: an outright failure on the first attempt, but the retry's own lock renewal is lost — stops before retrying rather than risking a concurrent double-submit, and does NOT fall through to the generic halt path")
    void orderFailsOutright_retryLockRenewalLost_stopsWithoutDoubleSubmitting() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any()))
            .thenReturn(new OrderResult(false, null, "c1", "REJECTED", null, null, "{}", "insufficient balance", List.of()));
        // Renew is called twice before the first placeOrder ever fires (once in
        // emergencyFlattenLocked before calling attemptFlatten, once more in attemptFlatten
        // immediately before the real market sell — see emergencyFlatten_renewsLockBeforeExchangeFacingSell
        // above, which proves that exact count for one attempt). The THIRD call is this fix's
        // own, right before the retry — that is the one that fails here.
        when(distributedLockService.renew(any(), any(), anyLong(), any())).thenReturn(true, true, false);

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter, times(1)).placeOrder(any(), any(), any(), any()); // retry never actually fired
        verify(credentialService).audit(any(), any(), any(), eq("FLATTEN_LOCK_LOST"), any());
        verify(credentialService, never()).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_FAILED"), any());
    }

    @Test
    @DisplayName("Test 1: full flatten in one shot — position closed, slot released, P&L includes both fees")
    void fullFlatten_closesAndReleasesSlot() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            fullSuccess(1.0, 105, List.of(new Fill(BigDecimal.valueOf(105), BigDecimal.ONE, BigDecimal.valueOf(1.5), "USDT"))));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        // pnl = (105-100)*1.0 - entryFee(10) - exitFee(1.5) = 5 - 10 - 1.5 = -6.5
        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED");
        assertThat(position.getRealizedPnlQuote()).isEqualByComparingTo("-6.5");
        assertThat(position.getExitFeeQuote()).isEqualByComparingTo("1.5");
        // Review finding ("NAKED_FLATTENED retains the old quantity"): a closed position must
        // not still read as having a nonzero quantity — that's what a future "quantity > 0"
        // query could misinterpret as still open.
        assertThat(position.getQuantity()).isEqualByComparingTo("0");
        assertThat(position.getClosedQuantity()).isEqualByComparingTo("1.0");
        verify(slotReservationService).releaseByKey("cred1");
        verify(riskEngine).recordRealizedLoss(eq(profile), org.mockito.ArgumentMatchers.argThat((BigDecimal v) -> v != null && v.compareTo(BigDecimal.valueOf(6.5)) == 0));
    }

    /**
     * Review finding ("Emergency flatten still allows an exchange sell without durable
     * pre-submission intent" -- external review, twenty-sixth pass, P1, full context in
     * FlattenAttempt's own class javadoc): the actual test proving the durable pre-submission
     * record is written before the real sell.
     */
    @Test
    @DisplayName("emergencyFlatten: writes a durable FlattenAttempt record with this attempt's exact clientOrderId before the real exchange sell")
    void emergencyFlatten_writesDurableFlattenAttemptRecord() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            fullSuccess(1.0, 105, List.of(new Fill(BigDecimal.valueOf(105), BigDecimal.ONE, BigDecimal.valueOf(1.5), "USDT"))));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        var captor = org.mockito.ArgumentCaptor.forClass(com.tradevision.model.FlattenAttempt.class);
        verify(flattenAttemptRepo).insert(captor.capture());
        assertThat(captor.getValue().getPositionId()).isEqualTo(position.getId());
        assertThat(captor.getValue().getClientOrderId()).isNotBlank();
        assertThat(captor.getValue().getQuantity()).isEqualByComparingTo(BigDecimal.ONE);
    }

    /**
     * P2-8 fix ("PositionSafetyService.attemptFlatten: flatten clientOrderId deterministic per
     * positionId:attempt; a later flatten episode on the same position reuses it" -- external
     * review, full context in Position.flattenEpisode's own field javadoc): this position can
     * legitimately return to OPEN after a recovered partial flatten and go through a genuinely
     * SEPARATE flatten episode later. Before this fix, that second episode's own attempt-0
     * clientOrderId was a pure function of positionId + attempt, so it exactly reproduced the
     * FIRST episode's own attempt-0 id -- OrderService.create's own unique-clientOrderId
     * constraint would then reject the second episode's OMS Order as a duplicate, leaving that
     * episode's real exchange sell with no OMS record at all. This is the actual review-required
     * test: "Two flatten episodes -> two OMS orders" -- proven here by asserting the two
     * episodes' clientOrderIds passed to orderService.create are genuinely different, not by
     * asserting on OMS Order objects directly (this mock's own create() isn't stubbed to persist
     * anything — every other test in this file already relies on that same non-persisting
     * default), which is exactly what a real duplicate-key rejection versus two independent
     * inserts would hinge on.
     */
    @Test
    @DisplayName("emergencyFlatten: two separate flatten episodes on the same position produce two genuinely different clientOrderIds — the actual review fix (\"flatten clientOrderId deterministic per positionId:attempt; a later flatten episode reuses it\")")
    void twoFlattenEpisodesOnSamePosition_produceDifferentClientOrderIds() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 105, List.of()));

        // Episode 1: the OPEN -> FLATTENING transition durably stamps flattenEpisode=1.
        Position episode1Result = new Position();
        episode1Result.setStatus("FLATTENING");
        episode1Result.setFlattenEpisode(1);
        // Episode 2 (a later, separate flatten episode on this exact same position -- e.g. after
        // a recovered partial flatten put it back to OPEN): durably stamps flattenEpisode=2, a
        // genuinely different value from episode 1's, never overwritten backward.
        Position episode2Result = new Position();
        episode2Result.setStatus("FLATTENING");
        episode2Result.setFlattenEpisode(2);
        when(mongoTemplate.findAndModify(any(), any(org.springframework.data.mongodb.core.query.Update.class),
                any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(Position.class)))
            .thenReturn(episode1Result, episode2Result);

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "episode 1 reason");

        // Simulates exactly the real-world trigger for a second episode: recovery (or a later,
        // independent protection failure) found this position genuinely still holding coins and
        // put it back to OPEN with a real remaining quantity -- not the zeroed-out, already-closed
        // in-memory state finalizeFlatten left it in after episode 1.
        position.setStatus("OPEN");
        position.setQuantity(BigDecimal.ONE);
        service.emergencyFlatten(credential, adapter, "key", "secret", position, "episode 2 reason");

        // A single captor used across both verify() calls double-counts the earlier invocation
        // (Mockito re-evaluates capture() against every matching call each time it's used) -- one
        // fresh capture after both episodes avoids that, and is also the more direct way to prove
        // the actual claim: exactly two distinct clientOrderIds were ever sent to orderService.create.
        var clientOrderIdCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(orderService, times(2)).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), clientOrderIdCaptor.capture());
        List<String> allClientOrderIds = clientOrderIdCaptor.getAllValues();
        assertThat(allClientOrderIds).hasSize(2);
        assertThat(allClientOrderIds.get(0)).isNotBlank();
        assertThat(allClientOrderIds.get(1)).isNotBlank();
        assertThat(allClientOrderIds.get(1)).isNotEqualTo(allClientOrderIds.get(0)); // the actual fix: episode 2 != episode 1
    }

    @Test
    @DisplayName("Test 2/3: partial flatten then full close on retry — fees and P&L correctly split across both legs, slot released only once")
    void partialThenFullFlatten_splitsFeesAcrossLegs() {
        Position position = openPosition(1.0, 100, 10.0);
        // Leg 1: 0.4 of 1.0 fills at 105, fee 0.5 USDT. Leg 2 (retry): remaining 0.6 fills fully at 103, fee 0.7 USDT.
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            fullSuccess(0.4, 105, List.of(new Fill(BigDecimal.valueOf(105), BigDecimal.valueOf(0.4), BigDecimal.valueOf(0.5), "USDT"))),
            fullSuccess(0.6, 103, List.of(new Fill(BigDecimal.valueOf(103), BigDecimal.valueOf(0.6), BigDecimal.valueOf(0.7), "USDT"))));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        // Leg 1 (partial): entryFeeShare = 10*0.4/1.0 = 4. pnl1 = (105-100)*0.4 - 4 - 0.5 = 2 - 4 - 0.5 = -2.5
        //   -> position.entryFeeQuote reduced to 10-4=6, exitFeeQuote = 0.5, quantity -> 0.6
        // Leg 2 (final, fully closes remaining 0.6): pnl2 = (103-100)*0.6 - entryFee(6, the REMAINING share) - exitFee(0.7)
        //   = 1.8 - 6 - 0.7 = -4.9
        //   -> exitFeeQuote accumulates: 0.5 + 0.7 = 1.2 (review's "Issue B" — must NOT overwrite to just 0.7)
        // Total realizedPnl = -2.5 + -4.9 = -7.4
        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED");
        // entryFeeQuote is only ever WRITTEN by recordPartialFlattenPnl (leg 1 reduced it from
        // 10 to 6); finalizeFlatten only READS it for the pnl subtraction and never zeroes it —
        // so it correctly stays at 6 on the now-closed position, not reset to 0. What matters is
        // that 4 (leg 1) + 6 (leg 2) = 10 was actually deducted across the two pnl calculations,
        // which the total realizedPnlQuote assertion below confirms.
        assertThat(position.getEntryFeeQuote()).isEqualByComparingTo("6");
        assertThat(position.getExitFeeQuote()).isEqualByComparingTo("1.2"); // accumulated, not overwritten
        assertThat(position.getRealizedPnlQuote()).isEqualByComparingTo("-7.4");
        // Review finding ("One remaining accounting inconsistency" -- external review,
        // thirty-third pass, P2): closedQuantity is now cumulative across every confirmed exit
        // leg on this position (0.4 from leg 1 + 0.6 from leg 2 = 1.0, the real total closed),
        // matching the crash-recovery path's own already-established semantics -- not just this
        // final leg's own amount alone, which used to leave the two paths inconsistent.
        assertThat(position.getQuantity()).isEqualByComparingTo("0");
        assertThat(position.getClosedQuantity()).isEqualByComparingTo("1.0");
        verify(slotReservationService, times(1)).releaseByKey("cred1"); // only once, on the final close
        verify(riskEngine).recordRealizedLoss(eq(profile), org.mockito.ArgumentMatchers.argThat((BigDecimal v) -> v != null && v.compareTo(BigDecimal.valueOf(2.5)) == 0)); // leg 1
        verify(riskEngine).recordRealizedLoss(eq(profile), org.mockito.ArgumentMatchers.argThat((BigDecimal v) -> v != null && v.compareTo(BigDecimal.valueOf(4.9)) == 0)); // leg 2
    }

    @Test
    @DisplayName("attemptFlatten: the clientOrderId sent to Binance for BOTH the first attempt and the retry stays within the 36-character limit, and the two attempts produce genuinely DIFFERENT ids -- the actual review fix (\"Emergency-flatten clientOrderId is still invalid\"), since \"flat-\" + a 36-char UUID + \"-\" + attempt was 43+ characters, over Binance's own limit, on the exact safety path meant to save a naked position")
    void flattenClientOrderId_staysWithinLengthLimit_andDiffersAcrossRetries() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            fullSuccess(0.4, 105, List.of(new Fill(BigDecimal.valueOf(105), BigDecimal.valueOf(0.4), BigDecimal.valueOf(0.5), "USDT"))),
            fullSuccess(0.6, 103, List.of(new Fill(BigDecimal.valueOf(103), BigDecimal.valueOf(0.6), BigDecimal.valueOf(0.7), "USDT"))));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        ArgumentCaptor<OrderRequest> orderCaptor = ArgumentCaptor.forClass(OrderRequest.class);
        verify(adapter, times(2)).placeOrder(any(), any(), any(), orderCaptor.capture());
        List<OrderRequest> requests = orderCaptor.getAllValues();
        String attempt0Id = requests.get(0).clientOrderId();
        String attempt1Id = requests.get(1).clientOrderId();

        assertThat(attempt0Id).hasSizeLessThanOrEqualTo(36);
        assertThat(attempt1Id).hasSizeLessThanOrEqualTo(36);
        assertThat(attempt0Id).isNotEqualTo(attempt1Id); // genuinely unique per attempt, not a collision
    }

    @Test
    @DisplayName("emergencyFlatten: losing the distributed-lock race means NOTHING happens -- no OCO status check, no cancel, no market sell, not even a status read -- the actual review fix (\"Emergency flatten can execute twice concurrently\"), since by definition another process already owns this exact position's flatten")
    void emergencyFlatten_lostLockRace_doesNothing() {
        Position position = openPosition(1.0, 100, 10.0);
        when(distributedLockService.tryAcquireWithDiagnosis(any(), any(), any()))
            .thenReturn(new DistributedLockService.LockLease(DistributedLockService.AcquireResult.HELD_BY_OTHER, -1));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verifyNoInteractions(adapter);
        verify(distributedLockService, never()).release(any(), any());
        assertThat(position.getStatus()).isEqualTo("OPEN"); // completely untouched
    }

    @Test
    @DisplayName("emergencyFlatten: a genuine INFRASTRUCTURE_FAILURE acquiring the lock (not another instance holding it) escalates -- halts trading and raises a critical incident, rather than silently doing nothing while a real, unprotected position sits there -- the actual review fix (\"Emergency flatten lock failure is ambiguous\")")
    void emergencyFlatten_lockInfrastructureFailure_haltsAndRaisesIncident() {
        Position position = openPosition(1.0, 100, 10.0);
        when(distributedLockService.tryAcquireWithDiagnosis(any(), any(), any()))
            .thenReturn(new DistributedLockService.LockLease(DistributedLockService.AcquireResult.INFRASTRUCTURE_FAILURE, -1));
        RiskProfile profile = new RiskProfile();
        profile.setId("profile1");
        when(riskProfileRepo.findByCredentialId(credential.getId())).thenReturn(java.util.Optional.of(profile));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verifyNoInteractions(adapter);
        assertThat(profile.isTradingHalted()).isTrue();
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(),
            eq("EMERGENCY_FLATTEN_LOCK_INFRASTRUCTURE_FAILURE"), any());
    }

    @Test
    @DisplayName("Test 4: first leg partial, retry also partial/incomplete — position stays open and unprotected, halted, slot NEVER released")
    void partialThenPartialAgain_haltsWithoutClosingOrReleasingSlot() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            fullSuccess(0.4, 105, List.of()),
            fullSuccess(0.3, 103, List.of())); // retry only fills 0.3 of the remaining 0.6 — still incomplete

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        assertThat(position.getStatus()).isEqualTo("FLATTENING"); // Review finding ("Position still has no FLATTENING state" -- external review, second pass): never set to NAKED_FLATTENED, but no longer reverts to OPEN either -- see this file's own identical fix above.
        assertThat(position.getQuantity()).isEqualByComparingTo("0.3"); // 1.0 - 0.4 - 0.3
        assertThat(profile.isTradingHalted()).isTrue();
        verify(slotReservationService, never()).release(any());
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_FAILED"), contains("MANUAL INTERVENTION REQUIRED"));
    }

    @Test
    @DisplayName("Test 5: order placement fails outright — halted, slot not released, no P&L fabricated")
    void placementFailsOutright_haltsWithoutFabricatingState() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any()))
            .thenReturn(OrderResult.failure("insufficient balance", null));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        assertThat(position.getStatus()).isEqualTo("FLATTENING"); // Review finding ("Position still has no FLATTENING state" -- external review, second pass): no longer reverts to OPEN -- see this file's own identical fix above.
        assertThat(position.getRealizedPnlQuote()).isNull();
        assertThat(profile.isTradingHalted()).isTrue();
        verify(slotReservationService, never()).release(any());
    }

    @Test
    @DisplayName("Test 6: null avgEntryPrice (ENTRY_FILLED_UNVERIFIED case) — closes safely with no P&L fabricated, still releases the slot")
    void nullAvgEntryPrice_closesWithoutFabricatingPnl() {
        Position position = openPosition(1.0, 0, null);
        position.setAvgEntryPrice(null); // simulates AutoTradeService's ENTRY_FILLED_UNVERIFIED path
        position.setStatus("ENTRY_FILLED_UNVERIFIED");
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 105, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "entry price unverified");

        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED");
        assertThat(position.getRealizedPnlQuote()).isNull(); // no cost basis existed — correctly never fabricated
        verify(slotReservationService).releaseByKey("cred1"); // still released — the real position IS genuinely gone now
        verify(riskEngine, never()).recordRealizedLoss(any(), any()); // nothing to record without a real P&L
    }

    @Test
    @DisplayName("Test 7: exit commission in a non-quote asset (unknown fee) does not erase the entry-fee deduction — 'Issue A' regression guard")
    void unknownExitFee_stillDeductsKnownEntryFee() {
        Position position = openPosition(1.0, 100, 10.0);
        // Fee paid in BNB, not USDT — sumCommissionInQuoteAsset returns null for this leg.
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            fullSuccess(1.0, 105, List.of(new Fill(BigDecimal.valueOf(105), BigDecimal.ONE, BigDecimal.valueOf(0.001), "BNB"))));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        // pnl = (105-100)*1.0 - entryFee(10) - exitFee(null, skipped) = 5 - 10 = -5
        assertThat(position.getRealizedPnlQuote()).isEqualByComparingTo("-5");
        assertThat(position.getExitFeeQuote()).isNull(); // genuinely unknown — not fabricated as zero
    }

    // ── OCO collision avoidance ("P0 #1") ──────────────────────────────────────
    // Review's own explicitly requested tests #1 and #2, plus the adjacent branches of the new
    // state machine.

    @Test
    @DisplayName("Test 7 (review's own test #1 — 'OCO unknown'): OCO status cannot be verified — HALTS, never sends a market sell")
    void ocoStatusUnknown_haltsWithoutFlattening() {
        Position position = openPosition(1.0, 100, 10.0);
        position.setOcoOrderListId("oco-123");
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco-123"))).thenThrow(new RuntimeException("connection timeout"));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        assertThat(profile.isTradingHalted()).isTrue();
        assertThat(profile.getHaltReason()).containsIgnoringCase("could not verify existing oco");
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), eq("PROTECTION_UNKNOWN"), any());
    }

    @Test
    @DisplayName("Test 8 (review's own test #2 — 'active OCO'): an active OCO is cancelled, verified, then the position is safely sold")
    void activeOco_cancelledThenFlattened() {
        Position position = openPosition(1.0, 100, 10.0);
        position.setOcoOrderListId("oco-123");
        OcoStatusInfo activeStatus = new OcoStatusInfo("oco-123", "EXEC_STARTED", List.of(
            new OcoStatusInfo.Leg("l1", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(110), BigDecimal.ZERO, BigDecimal.valueOf(1.0)),
            new OcoStatusInfo.Leg("l2", "SELL", "STOP_LOSS_LIMIT", "NEW", BigDecimal.valueOf(95), BigDecimal.ZERO, BigDecimal.valueOf(1.0))
        ), "{}");
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco-123"))).thenReturn(activeStatus);
        when(adapter.cancelOco(any(), any(), any(), any(), eq("oco-123")))
            .thenReturn(new OcoOrderResult(true, "oco-123", "{}", null));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 105, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter).cancelOco(any(), any(), any(), any(), eq("oco-123"));
        verify(adapter).placeOrder(any(), any(), any(), any()); // the sell actually happened, only after cancel succeeded
        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED");
        assertThat(profile.isTradingHalted()).isTrue(); // every emergency flatten halts trading unconditionally, successful or not -- see finalizeFlatten's own comment
    }

    @Test
    @DisplayName("P1-2: exitPosition (routine planned exit) closes cleanly and does NOT halt the profile or raise a CRITICAL incident")
    void exitPosition_cleanClose_doesNotHaltProfile() {
        Position position = openPosition(1.0, 100, 10.0);
        position.setOcoOrderListId("oco-123");
        OcoStatusInfo activeStatus = new OcoStatusInfo("oco-123", "EXEC_STARTED", List.of(
            new OcoStatusInfo.Leg("l1", "SELL", "LIMIT_MAKER", "NEW", BigDecimal.valueOf(110), BigDecimal.ZERO, BigDecimal.valueOf(1.0)),
            new OcoStatusInfo.Leg("l2", "SELL", "STOP_LOSS_LIMIT", "NEW", BigDecimal.valueOf(95), BigDecimal.ZERO, BigDecimal.valueOf(1.0))
        ), "{}");
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco-123"))).thenReturn(activeStatus);
        when(adapter.cancelOco(any(), any(), any(), any(), eq("oco-123")))
            .thenReturn(new OcoOrderResult(true, "oco-123", "{}", null));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 105, List.of()));

        service.exitPosition(credential, adapter, "key", "secret", position, "MAX_HOLD_TIME: test reason");

        verify(adapter).placeOrder(any(), any(), any(), any()); // the sell still actually happens
        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED"); // closure mechanics are identical
        assertThat(profile.isTradingHalted()).isFalse(); // but a clean, routine exit does NOT halt the profile
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), eq("PROTECTION_FAILED"), any());
    }

    @Test
    @DisplayName("P1-2: exitPosition still halts on a genuine protection failure (could not verify OCO) — the flag only suppresses the SUCCESS-path halt")
    void exitPosition_genuineFailure_stillHalts() {
        Position position = openPosition(1.0, 100, 10.0);
        position.setOcoOrderListId("oco-123");
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco-123"))).thenThrow(new RuntimeException("connection timeout"));

        service.exitPosition(credential, adapter, "key", "secret", position, "MAX_HOLD_TIME: test reason");

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        assertThat(profile.isTradingHalted()).isTrue(); // a real protection failure still halts, regardless of how the exit was triggered
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), eq("PROTECTION_UNKNOWN"), any());
    }

    @Test
    @DisplayName("cancelOco fails and the OCO is confirmed still active on re-check — HALTS, never sends a market sell")
    void cancelFailsAndStillActive_halts() {
        Position position = openPosition(1.0, 100, 10.0);
        position.setOcoOrderListId("oco-123");
        OcoStatusInfo activeStatus = new OcoStatusInfo("oco-123", "EXEC_STARTED", List.of(), "{}");
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco-123"))).thenReturn(activeStatus); // both the initial check AND the re-check return the same still-active status
        when(adapter.cancelOco(any(), any(), any(), any(), eq("oco-123")))
            .thenReturn(OcoOrderResult.failure("order list does not exist", "{}"));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        assertThat(profile.isTradingHalted()).isTrue();
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), eq("PROTECTION_UNKNOWN"), any());
    }

    @Test
    @DisplayName("OCO is ALL_DONE with a filled leg — the position may already be closed via the OCO; HALTS rather than risking a double-sell")
    void ocoAllDoneWithFilledLeg_haltsRatherThanDoubleSell() {
        Position position = openPosition(1.0, 100, 10.0);
        position.setOcoOrderListId("oco-123");
        OcoStatusInfo doneWithFill = new OcoStatusInfo("oco-123", "ALL_DONE", List.of(
            new OcoStatusInfo.Leg("l1", "SELL", "STOP_LOSS_LIMIT", "FILLED", BigDecimal.valueOf(95), BigDecimal.ONE, BigDecimal.valueOf(1.0)),
            new OcoStatusInfo.Leg("l2", "SELL", "LIMIT_MAKER", "CANCELED", BigDecimal.valueOf(110), BigDecimal.ZERO, BigDecimal.valueOf(1.0))
        ), "{}");
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco-123"))).thenReturn(doneWithFill);

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        assertThat(profile.isTradingHalted()).isTrue();
        verify(incidentService).raiseCritical(any(), any(), any(), any(), any(), eq("RECONCILIATION_MISMATCH"), any());
    }

    @Test
    @DisplayName("OCO is ALL_DONE with no filled leg — genuinely safe, clears the OCO reference and proceeds with the flatten normally")
    void ocoAllDoneNoFilledLeg_clearsAndProceedsNormally() {
        Position position = openPosition(1.0, 100, 10.0);
        position.setOcoOrderListId("oco-123");
        OcoStatusInfo doneNoFill = new OcoStatusInfo("oco-123", "ALL_DONE", List.of(
            new OcoStatusInfo.Leg("l1", "SELL", "STOP_LOSS_LIMIT", "CANCELED", BigDecimal.valueOf(95), BigDecimal.ZERO, BigDecimal.valueOf(1.0)),
            new OcoStatusInfo.Leg("l2", "SELL", "LIMIT_MAKER", "CANCELED", BigDecimal.valueOf(110), BigDecimal.ZERO, BigDecimal.valueOf(1.0))
        ), "{}");
        when(adapter.getOcoStatus(any(), any(), any(), eq("oco-123"))).thenReturn(doneNoFill);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 105, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter, never()).cancelOco(any(), any(), any(), any(), any()); // already ALL_DONE — nothing to cancel
        verify(adapter).placeOrder(any(), any(), any(), any());
        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED");
        assertThat(profile.isTradingHalted()).isTrue(); // every emergency flatten halts trading unconditionally, successful or not -- see finalizeFlatten's own comment
    }

    @Test
    @DisplayName("no OCO on the position at all — the new check is a complete no-op, behaves exactly as before")
    void noOco_behavesExactlyAsBefore() {
        Position position = openPosition(1.0, 100, 10.0); // ocoOrderListId is null by default
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 105, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter, never()).getOcoStatus(any(), any(), any(), any());
        verify(adapter, never()).cancelOco(any(), any(), any(), any(), any());
        verify(adapter).placeOrder(any(), any(), any(), any());
        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED");
    }

    // ── Exchange-quantity source ("🟠 #15") ──────────────────────────────────

    /**
     * Review finding ("Emergency flatten can incorrectly close a partially-held position" --
     * external review, thirtieth pass, P0, the review's own explicit "biggest issue in v178,"
     * full context in attemptFlatten's own updated comment): the review's own review of THIS
     * EXACT TEST, before this fix, made the point directly -- it asserted closedQuantity==0.4
     * AND status==NAKED_FLATTENED together, meaning it validated the dangerous behavior instead
     * of catching it. Rewritten to assert the actual, safe behavior instead: a capped sell that
     * fully fills its own (smaller) target must NEVER be read as the whole position being gone.
     */
    @Test
    @DisplayName("attemptFlatten: internal quantity exceeds actual free balance — caps the sell at what's actually free, but a full fill of the CAPPED target is correctly read as only a PARTIAL flatten of the real position, never NAKED_FLATTENED")
    void internalQuantityExceedsBalance_cappedSellFullyFilled_isPartialNotFullClosure() {
        Position position = openPosition(1.0, 100, 10.0); // internal record says 1.0 -- the REAL position size
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(0.4), BigDecimal.ZERO))); // exchange actually only has 0.4 free, 0 locked
        // Every placeOrder call (the initial attempt AND the one retry) fills exactly 0.4 --
        // the mock can't distinguish calls, and that's fine: it proves the fix holds across
        // both attempts, not just the first.
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(0.4, 105, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        // The sell itself is still correctly capped at the real free balance.
        ArgumentCaptor<OrderRequest> orderCaptor = ArgumentCaptor.forClass(OrderRequest.class);
        verify(adapter, atLeastOnce()).placeOrder(any(), any(), any(), orderCaptor.capture());
        assertThat(orderCaptor.getAllValues().get(0).quantity()).isEqualByComparingTo("0.4"); // capped, not the stale 1.0

        // The actual fix under test: filling the CAPPED 0.4 target completely must NEVER be
        // read as the real 1.0 position being fully closed.
        assertThat(position.getStatus()).isNotEqualTo("NAKED_FLATTENED");
        // One retry attempted the remainder (1.0 - 0.4 = 0.6), which also filled only 0.4 of
        // ITS OWN target (0.6), leaving 0.2 genuinely unprotected after both attempts --
        // matching the review's own required "remaining = internalQuantity - totalExecuted"
        // formula, not the old "quantityToFlatten - executedQty" one.
        verify(adapter, times(2)).placeOrder(any(), any(), any(), any());
        assertThat(position.getQuantity()).isEqualByComparingTo("0.2");
    }

    /**
     * Review finding, same context as the test above: the review's own first required test --
     * a single capped sell that fully fills its own target, verified against the intermediate
     * PARTIAL state directly (isolated from the retry this file's own other test already
     * covers), with the review's own exact numbers.
     */
    @Test
    @DisplayName("attemptFlatten: capped sell (0.4 of a 1.0 real position) fully fills — remaining is internalQuantity minus executedQty (0.6), not quantityToFlatten minus executedQty (0.0)")
    void cappedSellFullyFilled_remainingUsesRealInternalQuantityNotCappedTarget() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(0.4), BigDecimal.ZERO)));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(0.4, 105, List.of()));
        // Force the renewal check right before the retry to fail, so this test observes the
        // state immediately after the FIRST attempt's own partial-flatten branch, isolated from
        // the retry's own further mutation -- proving this specific branch's own math directly.
        when(distributedLockService.renew(any(), any(), anyLong(), any()))
            .thenReturn(true)  // emergencyFlattenLocked's own renewal, before calling attemptFlatten
            .thenReturn(true)  // attemptFlatten's own renewal, right before the actual sell
            .thenReturn(false); // the renewal immediately before the retry — fails, stopping here

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        // The review's own exact expected value: 1.0 (real internal) - 0.4 (actually sold) =
        // 0.6 -- not 0.4 (capped target) - 0.4 (sold) = 0.0, which is what the bug computed.
        assertThat(position.getQuantity()).isEqualByComparingTo("0.6");
        assertThat(position.getStatus()).isNotEqualTo("NAKED_FLATTENED");
        // Review finding ("One remaining accounting inconsistency" -- external review,
        // thirty-third pass, P2): this leg's own confirmed sale (0.4) is recorded in
        // closedQuantity even though the position itself stays open -- so a later leg that
        // finally closes it has something real to accumulate onto.
        assertThat(position.getClosedQuantity()).isEqualByComparingTo("0.4");
        verify(adapter, times(1)).placeOrder(any(), any(), any(), any()); // the retry never actually fired
    }

    /**
     * Review finding, same context as the tests above: the review's own second required test --
     * a partial fill of an already-capped target must still compute the real remaining position
     * correctly.
     */
    @Test
    @DisplayName("attemptFlatten: capped sell (0.4 of a 1.0 real position) partially fills (0.2) — remaining is 0.8 (internalQuantity - executedQty), not 0.2 (quantityToFlatten - executedQty)")
    void cappedSellPartiallyFilled_remainingUsesRealInternalQuantityNotCappedTarget() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(0.4), BigDecimal.ZERO)));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(0.2, 105, List.of()));
        when(distributedLockService.renew(any(), any(), anyLong(), any())).thenReturn(true).thenReturn(true).thenReturn(false);

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        // The review's own exact expected value: 1.0 - 0.2 = 0.8 -- not 0.4 - 0.2 = 0.2.
        assertThat(position.getQuantity()).isEqualByComparingTo("0.8");
        assertThat(position.getStatus()).isNotEqualTo("NAKED_FLATTENED");
        assertThat(position.getClosedQuantity()).isEqualByComparingTo("0.2");
    }

    /**
     * Review finding, same context as the tests above: confirms the normal, uncapped case
     * (free balance covers the whole position) is completely unaffected by this fix -- full
     * closure still correctly fires when the entire real position is actually sold.
     */
    @Test
    @DisplayName("attemptFlatten: free balance covers the whole position (no capping) — a full fill is still correctly read as NAKED_FLATTENED")
    void uncappedFullFill_stillCorrectlyReadsAsFullyFlattened() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO))); // free covers the whole 1.0
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 105, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED");
        assertThat(position.getClosedQuantity()).isEqualByComparingTo("1.0");
        verify(adapter, times(1)).placeOrder(any(), any(), any(), any()); // no retry needed
    }

    /**
     * Review finding ("zero FREE balance can falsely mean 'position is gone'" -- external
     * review, thirtieth pass, P1, full context in attemptFlatten's own updated comment): the
     * actual test for the fix -- free==0 with locked>0 must halt, never falsely close.
     */
    @Test
    @DisplayName("attemptFlatten: free balance is zero but locked balance is positive — coins are genuinely still held (locked by another order), never marked closed, never attempts a zero-quantity sell")
    void zeroFreeBalanceWithLockedBalance_haltsInsteadOfFalselyClosing() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.ZERO, BigDecimal.valueOf(1.0)))); // free=0, but 1.0 genuinely still locked

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        assertThat(position.getStatus()).isNotEqualTo("CLOSED_UNVERIFIED_PNL");
        assertThat(position.getStatus()).isNotEqualTo("NAKED_FLATTENED");
        verify(adapter, never()).placeOrder(any(), any(), any(), any()); // never a meaningless zero-quantity sell
        verify(credentialService).audit(any(), any(), any(), eq("EMERGENCY_FLATTEN_HELD_BALANCE_AMBIGUOUS"), any());
    }

    /**
     * P3-11 fix ("Cancel all open orders for the symbol before any flatten" -- external review,
     * second pass, re-audit, P0, full context in PositionSafetyService.cancelOtherOpenOrdersForSymbol's
     * own javadoc): the actual regression this fixes -- a stop leg (or any other order) resting
     * on this exact symbol that this application's own records don't track (no
     * position.ocoOrderListId match) would otherwise lock real coins and drive straight into
     * zeroFreeBalanceWithLockedBalance_haltsInsteadOfFalselyClosing's own halt above, even though
     * the actual fix is simple: cancel it first. Proves the stray order is actually identified
     * and cancelled, by orderId, before the sell.
     */
    @Test
    @DisplayName("emergencyFlatten: a stray open order on this position's symbol (not tracked as this position's OCO) is cancelled before the sell is attempted")
    void strayOpenOrderForSymbol_isCancelledBeforeTheSell() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.getOpenOrders(any(), any(), any(), any())).thenReturn(
            List.of(new OpenOrderInfo("999", null, "BTCUSDT", "SELL", "NEW", BigDecimal.valueOf(1.0), BigDecimal.valueOf(95))));
        when(adapter.cancelOrder(any(), any(), any(), eq("BTCUSDT"), eq("999")))
            .thenReturn(new OrderResult(true, "999", "c999", "CANCELED", null, null, "{}", null));
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 105, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter).cancelOrder(any(), any(), any(), eq("BTCUSDT"), eq("999"));
        verify(credentialService).audit(any(), any(), any(), eq("FLATTEN_STRAY_ORDER_CANCELLED"), any());
        // The actual sell still went out normally afterward -- cancelling the stray order isn't
        // a substitute for the real flatten, just a precondition for it to succeed cleanly.
        verify(adapter).placeOrder(any(), any(), any(), any());
        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED");
    }

    @Test
    @DisplayName("emergencyFlatten: an open order on a DIFFERENT symbol is left alone -- only orders on this position's own symbol are cancelled")
    void openOrderForDifferentSymbol_isNotCancelled() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.getOpenOrders(any(), any(), any(), any())).thenReturn(
            List.of(new OpenOrderInfo("777", null, "ETHUSDT", "SELL", "NEW", BigDecimal.valueOf(2.0), BigDecimal.valueOf(3000))));
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 105, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter, never()).cancelOrder(any(), any(), any(), eq("ETHUSDT"), any());
        verify(credentialService, never()).audit(any(), any(), any(), eq("FLATTEN_STRAY_ORDER_CANCELLED"), any());
        verify(adapter).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("emergencyFlatten: cancelling a stray open order fails -- best-effort, does NOT block the actual flatten sell from being attempted")
    void cancellingStrayOrderFails_stillProceedsWithFlatten() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.getOpenOrders(any(), any(), any(), any())).thenReturn(
            List.of(new OpenOrderInfo("999", null, "BTCUSDT", "SELL", "NEW", BigDecimal.valueOf(1.0), BigDecimal.valueOf(95))));
        when(adapter.cancelOrder(any(), any(), any(), eq("BTCUSDT"), eq("999")))
            .thenThrow(new RuntimeException("simulated cancel failure"));
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 105, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        // A cancel failure on a stray order is logged, not fatal -- the real sell still fires
        // using whatever balance is actually free (the balance check itself is the authoritative
        // safety net if the failed cancel left coins genuinely locked).
        verify(adapter).placeOrder(any(), any(), any(), any());
    }

    /**
     * P3-11 second re-audit fix ("The emergency flatten cancels too much... only cancel orders
     * that belong to the position being flattened or that aren't tracked at all" -- external
     * review, third pass, item #3 of its own "before real money" list, full context in
     * cancelOtherOpenOrdersForSymbol's own updated javadoc): the actual regression this fixes --
     * an open order this application's own OMS records show belongs to a DIFFERENT, still-open
     * position on the same symbol must be left completely alone, not swept up and cancelled just
     * because it happens to share this position's symbol -- that position would otherwise go
     * naked until the next reconciliation pass, roughly 60s later.
     */
    @Test
    @DisplayName("emergencyFlatten: an open order on this symbol that belongs to a DIFFERENT, still-open position (per this application's own OMS records) is left alone")
    void openOrderTrackedToADifferentPosition_isNotCancelled() {
        Position position = openPosition(1.0, 100, 10.0);
        position.setId("pos1");
        when(adapter.getOpenOrders(any(), any(), any(), any())).thenReturn(
            List.of(new OpenOrderInfo("888", null, "BTCUSDT", "SELL", "NEW", BigDecimal.valueOf(0.5), BigDecimal.valueOf(90))));
        com.tradevision.model.Order trackedToOtherPosition = new com.tradevision.model.Order();
        trackedToOtherPosition.setPositionId("some-other-position");
        when(orderRepository.findByCredentialIdAndSymbolAndBrokerOrderId(eq("cred1"), eq("BTCUSDT"), eq("888")))
            .thenReturn(java.util.Optional.of(trackedToOtherPosition));
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 105, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter, never()).cancelOrder(any(), any(), any(), eq("BTCUSDT"), eq("888"));
        verify(credentialService, never()).audit(any(), any(), any(), eq("FLATTEN_STRAY_ORDER_CANCELLED"), any());
        // The flatten itself still proceeds normally -- leaving that other order alone isn't a
        // reason to stop this one.
        verify(adapter).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("emergencyFlatten: an open order on this symbol tracked to THIS SAME position (not a different one) is still cancelled")
    void openOrderTrackedToThisSamePosition_isStillCancelled() {
        Position position = openPosition(1.0, 100, 10.0);
        position.setId("pos1");
        when(adapter.getOpenOrders(any(), any(), any(), any())).thenReturn(
            List.of(new OpenOrderInfo("999", null, "BTCUSDT", "SELL", "NEW", BigDecimal.valueOf(1.0), BigDecimal.valueOf(95))));
        com.tradevision.model.Order trackedToThisPosition = new com.tradevision.model.Order();
        trackedToThisPosition.setPositionId("pos1");
        when(orderRepository.findByCredentialIdAndSymbolAndBrokerOrderId(eq("cred1"), eq("BTCUSDT"), eq("999")))
            .thenReturn(java.util.Optional.of(trackedToThisPosition));
        when(adapter.cancelOrder(any(), any(), any(), eq("BTCUSDT"), eq("999")))
            .thenReturn(new OrderResult(true, "999", "c999", "CANCELED", null, null, "{}", null));
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 105, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter).cancelOrder(any(), any(), any(), eq("BTCUSDT"), eq("999"));
        verify(credentialService).audit(any(), any(), any(), eq("FLATTEN_STRAY_ORDER_CANCELLED"), any());
    }

    /**
     * Third re-audit fix ("The flatten can still cancel another position's stop-loss" -- external
     * review, fourth pass, item #1 of its own list, full context in cancelOtherOpenOrdersForSymbol's
     * and OpenOrderInfo's own updated javadocs): the exact gap the previous ownership check
     * (openOrderTrackedToADifferentPosition_isNotCancelled, above) could NOT catch -- an OCO leg
     * belonging to another still-OPEN position is never recorded as a standalone Order keyed by
     * its own orderId (orderRepository.findByCredentialIdAndSymbolAndBrokerOrderId legitimately
     * finds nothing for it), only via that other position's own Position.ocoOrderListId. Proves
     * the new orderListId-based check catches exactly this case, which the orderId-only lookup
     * alone cannot.
     */
    @Test
    @DisplayName("emergencyFlatten: an open order that is a leg of a DIFFERENT, still-open position's own OCO (matched by orderListId, not orderId) is left alone")
    void openOrderIsOcoLegOfDifferentOpenPosition_isNotCancelled() {
        Position position = openPosition(1.0, 100, 10.0);
        position.setId("pos1");
        Position otherOpenPosition = openPosition(0.5, 200, 5.0);
        otherOpenPosition.setId("pos2");
        otherOpenPosition.setOcoOrderListId("list-42");
        when(positionRepo.findByCredentialIdAndStatus(eq("cred1"), eq("OPEN")))
            .thenReturn(List.of(position, otherOpenPosition));
        when(adapter.getOpenOrders(any(), any(), any(), any())).thenReturn(
            List.of(new OpenOrderInfo("555", "list-42", "BTCUSDT", "SELL", "NEW", BigDecimal.valueOf(0.5), BigDecimal.valueOf(210))));
        // No Order record exists for "555" at all (default Mockito answer: Optional.empty()) --
        // proving this is caught by the orderListId check, not the orderId-based ownership check.
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 105, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter, never()).cancelOrder(any(), any(), any(), eq("BTCUSDT"), eq("555"));
        verify(credentialService, never()).audit(any(), any(), any(), eq("FLATTEN_STRAY_ORDER_CANCELLED"), any());
        // The flatten itself still proceeds normally -- leaving the other position's OCO leg
        // alone isn't a reason to stop this one.
        verify(adapter).placeOrder(any(), any(), any(), any());
    }

    @Test
    @DisplayName("emergencyFlatten: an open order whose orderListId happens to match another position's ocoOrderListId, but that other position is itself the one being flattened (not an OTHER open position), is still cancelled as a stray")
    void openOrderMatchesOwnPositionsOldListId_stillCancelledSinceOnlyOtherOpenPositionsAreExcluded() {
        // Deliberately does NOT set position.setOcoOrderListId(...) here: doing so would route
        // emergencyFlatten through its own separate "cancel my existing OCO first" flow (a real,
        // already-covered network call to getOcoStatus/cancelOco), which is not what this test
        // is about. This test only needs to prove that positionRepo.findByCredentialIdAndStatus
        // results are filtered to exclude the position being flattened itself (by id) from the
        // "other open positions" comparison set used by cancelOtherOpenOrdersForSymbol -- so a
        // stray order that happens to carry an orderListId is still cancelled when the only
        // position record with that ocoOrderListId IS this same position.
        Position position = openPosition(1.0, 100, 10.0);
        position.setId("pos1");
        when(positionRepo.findByCredentialIdAndStatus(eq("cred1"), eq("OPEN")))
            .thenReturn(List.of(position));
        when(adapter.getOpenOrders(any(), any(), any(), any())).thenReturn(
            List.of(new OpenOrderInfo("666", "list-99", "BTCUSDT", "SELL", "NEW", BigDecimal.valueOf(1.0), BigDecimal.valueOf(95))));
        when(adapter.cancelOrder(any(), any(), any(), eq("BTCUSDT"), eq("666")))
            .thenReturn(new OrderResult(true, "666", "c666", "CANCELED", null, null, "{}", null));
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.valueOf(1.0), BigDecimal.ZERO)));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 105, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter).cancelOrder(any(), any(), any(), eq("BTCUSDT"), eq("666"));
        verify(credentialService).audit(any(), any(), any(), eq("FLATTEN_STRAY_ORDER_CANCELLED"), any());
    }

    @Test
    @DisplayName("attemptFlatten: actual free balance is zero — marks the position closed without ever attempting a zero-quantity order")
    void zeroFreeBalance_marksClosedWithoutOrderAttempt() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.ZERO, BigDecimal.ZERO)));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter, never()).placeOrder(any(), any(), any(), any());
        assertThat(position.getStatus()).isEqualTo("CLOSED_UNVERIFIED_PNL");
        assertThat(position.getQuantity()).isEqualByComparingTo("0");
    }

    /**
     * Review finding ("zero-total-balance after a previous partial" -- external review,
     * thirty-first pass, full context in Position.unverifiedClosedQuantity's own field
     * javadoc): the actual test proving the fix -- the remaining quantity is recorded as
     * unverified, not silently manufactured as a confirmed sale by this specific operation.
     */
    @Test
    @DisplayName("attemptFlatten: zero total balance -- the remaining quantity is recorded as unverifiedClosedQuantity, never silently manufactured as a confirmed closedQuantity sale")
    void zeroTotalBalance_recordsUnverifiedNotConfirmedClosedQuantity() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.ZERO, BigDecimal.ZERO)));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        assertThat(position.getUnverifiedClosedQuantity()).isEqualByComparingTo("1.0");
        // The actual claim under test: this specific attempt confirmed no sale of its own, so
        // closedQuantity must NOT be set to the full remaining quantity as if it had been.
        assertThat(position.getClosedQuantity()).isNull();
    }

    @Test
    @DisplayName("attemptFlatten: zero total balance AFTER an earlier confirmed partial sell -- the earlier confirmed closedQuantity is preserved untouched, only the genuinely-unaccounted remainder goes into unverifiedClosedQuantity")
    void zeroTotalBalanceAfterEarlierPartial_preservesConfirmedClosedQuantity() {
        Position position = openPosition(0.6, 100, 10.0); // internal quantity already reduced by an earlier confirmed partial sell
        position.setClosedQuantity(BigDecimal.valueOf(0.4)); // that earlier confirmed sale, already on record
        when(adapter.getBalance(any(), any(), any())).thenReturn(
            List.of(new AssetBalance("BTC", BigDecimal.ZERO, BigDecimal.ZERO)));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        // The earlier confirmed sale must remain exactly as it was -- never overwritten.
        assertThat(position.getClosedQuantity()).isEqualByComparingTo("0.4");
        // The genuinely unaccounted remainder (0.6) is recorded separately, as unverified.
        assertThat(position.getUnverifiedClosedQuantity()).isEqualByComparingTo("0.6");
    }

    @Test
    @DisplayName("attemptFlatten: balance check itself fails — attempts the original internal quantity rather than refusing to flatten at all")
    void balanceCheckFails_attemptsInternalQuantityAnyway() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.getBalance(any(), any(), any())).thenThrow(new RuntimeException("connection timeout"));
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(fullSuccess(1.0, 105, List.of()));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        ArgumentCaptor<OrderRequest> orderCaptor = ArgumentCaptor.forClass(OrderRequest.class);
        verify(adapter).placeOrder(any(), any(), any(), orderCaptor.capture());
        assertThat(orderCaptor.getValue().quantity()).isEqualByComparingTo("1.0"); // failed open, not closed
        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED");
    }

    @Test
    @DisplayName("emergencyFlatten: when recordFills returns fewer records than expected, the profile is halted and a CRITICAL incident is raised, but the position's own closure still proceeds normally -- the actual review fix (\"Fill Ledger can still fail without stopping financial state changes\"), extended to the emergency-flatten path too")
    void emergencyFlattenLedgerRecordingFailed_haltsProfileButStillClosesPosition() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            fullSuccess(1.0, 105, List.of(new Fill(BigDecimal.valueOf(105), BigDecimal.ONE, BigDecimal.valueOf(1.5), "USDT"))));
        when(fillLedgerService.recordFills(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(java.util.List.of()); // zero records despite a real flatten fill having happened

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        assertThat(profile.isTradingHalted()).isTrue();
        assertThat(profile.getHaltReason()).contains("Auto-halted after a protection failure");
        verify(riskProfileRepo, never()).save(any());
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && Boolean.TRUE.equals(setDoc.getBoolean("tradingHalted"));
        }), eq(RiskProfile.class));
        verify(incidentService).raiseCritical(eq("user1"), any(), any(), any(), any(), eq("FILL_LEDGER_INCOMPLETE"), any());
        // The position's own closure still proceeds normally despite the halt -- the flatten
        // genuinely happened on the exchange regardless of whether our ledger recorded it.
        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED");
        assertThat(position.isLedgerRecordingIncomplete()).isTrue();
    }

    @Test
    @DisplayName("emergencyFlatten: a genuine position-ledger mismatch on close raises a SPECIFIC additional CRITICAL incident naming the actual discrepancy -- haltProfile() already halts unconditionally for every emergency flatten regardless (see finalizeFlatten's own comment on why this isn't a second, redundant halt), so this test verifies the diagnostic incident specifically, not the halt itself")
    void emergencyFlattenPositionLedgerMismatch_raisesSpecificDiagnosticIncident() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            fullSuccess(1.0, 105, List.of(new Fill(BigDecimal.valueOf(105), BigDecimal.ONE, BigDecimal.valueOf(1.5), "USDT"))));
        when(positionLedgerService.reconcilePositionAgainstLedger(any(), any(), any()))
            .thenReturn(new PositionLedgerService.ReconcileResult(PositionLedgerService.ReconcileStatus.MISMATCH, BigDecimal.valueOf(0.2), BigDecimal.ZERO));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(incidentService).raiseCritical(eq("user1"), any(), any(), any(), any(), eq("POSITION_LEDGER_MISMATCH"), contains("0.2"));
        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED");
        assertThat(profile.isTradingHalted()).isTrue(); // via the existing, unconditional haltProfile() -- not this check's own doing
    }

    @Test
    @DisplayName("emergencyFlatten: getSymbolRules() itself failing (previously completely unprotected -- no try/catch at all) no longer prevents the position from being saved as closed -- the actual review fix (\"Full emergency flatten has another similar catch\"), and raises the same halt+incident escalation as the sibling gap in the partial-flatten path")
    void emergencyFlattenSymbolRulesFailure_stillClosesPositionAndEscalates() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            fullSuccess(1.0, 105, List.of(new Fill(BigDecimal.valueOf(105), BigDecimal.ONE, BigDecimal.valueOf(1.5), "USDT"))));
        when(adapter.getSymbolRules(any(), any())).thenThrow(new RuntimeException("simulated exchange-info lookup failure"));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        // The position still closes -- the actual sell already succeeded on the exchange,
        // regardless of whether this unrelated lookup succeeded afterward.
        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED");
        assertThat(position.getQuantity()).isEqualByComparingTo("0");
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && "NAKED_FLATTENED".equals(setDoc.getString("status"));
        }), eq(Position.class));
        // And it's escalated, not silently absorbed -- zero fee/P&L/ledger data was recorded for
        // this leg at all, which is worse than a recording that was attempted and failed.
        assertThat(position.isLedgerRecordingIncomplete()).isTrue();
        verify(incidentService).raiseCritical(eq("user1"), any(), any(), any(), any(), eq("FILL_LEDGER_INCOMPLETE"), any());
    }

    @Test
    @DisplayName("emergencyFlatten: the market SELL order gets its own real OMS Order record (side=SELL, type=MARKET) -- the actual review fix (\"OMS not actually authoritative\"), extended from entry/OCO placement to the emergency-flatten market order too")
    void emergencyFlattenOrder_getsOwnOmsOrderRecord() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            fullSuccess(1.0, 105, List.of(new Fill(BigDecimal.valueOf(105), BigDecimal.ONE, BigDecimal.valueOf(1.5), "USDT"))));
        com.tradevision.model.Order flattenOrder = new com.tradevision.model.Order();
        flattenOrder.setId("flatten-oms-1");
        when(orderService.create(any(), any(), any(), any(), any(), eq("SELL"), eq("MARKET"), any(), any(), any()))
            .thenReturn(flattenOrder);

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(orderService).create(any(), any(), any(), any(), any(), eq("SELL"), eq("MARKET"), any(), any(), any());
        verify(orderService).markRiskAccepted(flattenOrder);
        verify(orderService).markSubmitting(flattenOrder);
        verify(orderService).recordBrokerResult(eq(flattenOrder), any());
    }

    @Test
    @DisplayName("emergencyFlatten: OMS setup failing for the flatten order never blocks or delays the real market SELL placement itself -- the same non-fatal, additive guarantee entry/OCO placement already have")
    void emergencyFlattenOmsSetupFailure_realOrderStillSucceeds() {
        Position position = openPosition(1.0, 100, 10.0);
        when(adapter.placeOrder(any(), any(), any(), any())).thenReturn(
            fullSuccess(1.0, 105, List.of(new Fill(BigDecimal.valueOf(105), BigDecimal.ONE, BigDecimal.valueOf(1.5), "USDT"))));
        when(orderService.create(any(), any(), any(), any(), any(), eq("SELL"), eq("MARKET"), any(), any(), any()))
            .thenThrow(new RuntimeException("simulated OMS bug"));

        service.emergencyFlatten(credential, adapter, "key", "secret", position, "test reason");

        verify(adapter).placeOrder(any(), any(), any(), any());
        assertThat(position.getStatus()).isEqualTo("NAKED_FLATTENED");
    }
}
