package com.tradevision.service;

import com.tradevision.model.TradeCallRecord;
import com.tradevision.repository.TradeCallRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests the recovery mechanism: a stuck signal is atomically reset to PENDING (not just
 * re-dispatched directly, which would bypass evaluateSignal's own claim gate) and re-dispatched,
 * and a signal which completed in the race window between the query and the reset is correctly
 * left alone.
 *
 * The EVALUATING staleness query uses a real lease-expiry check
 * (findByAutoTradeEvalStatusAndEvaluationLeaseUntilBefore), with a fallback query
 * (findByAutoTradeEvalStatusAndEvaluationLeaseUntilIsNull, for records claimed by the older
 * claim logic before this field existed) also stubbed where relevant. A dedicated test verifies
 * that a record matching BOTH fallback queries is recovered once, not twice.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutoTradeRecoveryServiceTest {

    @Mock TradeCallRepository callRepo;
    @Mock MongoTemplate mongoTemplate;
    @Mock AutoTradeService autoTradeService;
    @Mock com.tradevision.config.ShutdownState shutdownState;
    @Mock com.tradevision.service.IncidentService incidentService;

    @InjectMocks AutoTradeRecoveryService service;

    private TradeCallRecord stuckSignal(String status) {
        TradeCallRecord r = new TradeCallRecord();
        r.setId("sig1");
        r.setUserId("user1");
        r.setSymbol("BTCUSDT");
        r.setAutoTradeEvalStatus(status);
        // Kept comfortably under MAX_SIGNAL_AGE (15 minutes) -- this fixture models a signal
        // stuck behind a slow/crashed WORKER (the thing every test in this file is actually
        // about), not a signal that's independently too OLD to trade at all. See the dedicated
        // stuckSignalTooOld_* tests below for that separate case.
        r.setCalledAt(LocalDateTime.now().minusMinutes(11));
        if ("EVALUATING".equals(status)) {
            r.setAutoTradeEvalStartedAt(LocalDateTime.now().minusMinutes(15));
            r.setEvaluationLeaseUntil(LocalDateTime.now().minusMinutes(10)); // lease expired 10 min ago
        }
        return r;
    }

    /** Default: every query returns empty, so a test only needs to override the one(s) it cares about. */
    private void stubAllEmpty() {
        when(callRepo.findByAutoTradeEvalStatusAndCalledAtBeforeAndSignalStatusNotIn(any(), any(), any())).thenReturn(List.of());
        when(callRepo.findByAutoTradeEvalStatusAndEvaluationLeaseUntilBeforeAndSignalStatusNotIn(any(), any(), any())).thenReturn(List.of());
        when(callRepo.findByAutoTradeEvalStatusAndAutoTradeEvalStartedAtIsNull(any())).thenReturn(List.of());
        when(callRepo.findByAutoTradeEvalStatusAndEvaluationLeaseUntilIsNull(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("recoverStuckSignals: a signal stuck in PENDING is atomically reset and re-dispatched through the normal claim gate")
    void stuckPendingSignal_resetAndRedispatched() {
        stubAllEmpty();
        TradeCallRecord stuck = stuckSignal("PENDING");
        when(callRepo.findByAutoTradeEvalStatusAndCalledAtBeforeAndSignalStatusNotIn(eq("PENDING"), any(), any())).thenReturn(List.of(stuck));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(TradeCallRecord.class)))
            .thenReturn(stuck); // reset succeeded

        service.recoverStuckSignals();

        verify(autoTradeService).evaluateSignal("user1", stuck);
    }

    /**
     * Confirms a RejectedExecutionException from a saturated autoTradeExecutor doesn't propagate
     * out of recoverStuckSignals() and abort the rest of its pass -- the signal was already reset
     * to PENDING before the dispatch, so it's already eligible for the next recovery pass
     * regardless of this one's outcome.
     */
    @Test
    @DisplayName("recoverStuckSignals: autoTradeExecutor rejecting the re-dispatch (RejectedExecutionException) does not propagate out of this method -- the signal stays reset to PENDING for the next pass")
    void stuckPendingSignal_dispatchRejected_doesNotPropagate() {
        stubAllEmpty();
        TradeCallRecord stuck = stuckSignal("PENDING");
        when(callRepo.findByAutoTradeEvalStatusAndCalledAtBeforeAndSignalStatusNotIn(eq("PENDING"), any(), any())).thenReturn(List.of(stuck));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(TradeCallRecord.class)))
            .thenReturn(stuck); // reset succeeded
        doThrow(new java.util.concurrent.RejectedExecutionException("pool saturated"))
            .when(autoTradeService).evaluateSignal(any(), any());

        assertThatCode(() -> service.recoverStuckSignals()).doesNotThrowAnyException();

        verify(autoTradeService).evaluateSignal("user1", stuck);
    }

    // ── stale signal max-age gate ──────────────────────────────────────────

    @Test
    @DisplayName("recoverStuckSignals: a stuck PENDING signal older than MAX_SIGNAL_AGE is marked EXPIRED, never re-dispatched")
    void stuckSignalTooOld_expiredNotRedispatched() {
        stubAllEmpty();
        TradeCallRecord stuck = stuckSignal("PENDING");
        stuck.setCalledAt(LocalDateTime.now().minusHours(2)); // well past the 15-minute max age
        when(callRepo.findByAutoTradeEvalStatusAndCalledAtBeforeAndSignalStatusNotIn(eq("PENDING"), any(), any())).thenReturn(List.of(stuck));

        service.recoverStuckSignals();

        verify(autoTradeService, never()).evaluateSignal(any(), any());
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(any(Query.class), updateCaptor.capture(), eq(TradeCallRecord.class));
        var updateObject = updateCaptor.getValue().getUpdateObject();
        assertThat(updateObject.get("$set", org.bson.Document.class).get("signalStatus"))
            .isEqualTo(com.tradevision.model.SignalStatus.EXPIRED);
    }

    @Test
    @DisplayName("recoverStuckSignals: a stuck EVALUATING signal older than MAX_SIGNAL_AGE is also marked EXPIRED, not reset to PENDING and re-dispatched")
    void stuckEvaluatingSignalTooOld_expiredNotRedispatched() {
        stubAllEmpty();
        TradeCallRecord stuck = stuckSignal("EVALUATING");
        stuck.setCalledAt(LocalDateTime.now().minusHours(2));
        when(callRepo.findByAutoTradeEvalStatusAndEvaluationLeaseUntilBeforeAndSignalStatusNotIn(eq("EVALUATING"), any(), any())).thenReturn(List.of(stuck));

        service.recoverStuckSignals();

        verify(autoTradeService, never()).evaluateSignal(any(), any());
    }

    @Test
    @DisplayName("recoverStuckSignals: a stuck signal within MAX_SIGNAL_AGE is still reset and re-dispatched normally, not expired")
    void stuckSignalWithinMaxAge_stillRedispatched() {
        stubAllEmpty();
        TradeCallRecord stuck = stuckSignal("PENDING"); // fixture's own default calledAt (11 min ago) is well within the 15-minute max age
        when(callRepo.findByAutoTradeEvalStatusAndCalledAtBeforeAndSignalStatusNotIn(eq("PENDING"), any(), any())).thenReturn(List.of(stuck));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(TradeCallRecord.class))).thenReturn(stuck);

        service.recoverStuckSignals();

        verify(autoTradeService).evaluateSignal("user1", stuck);
    }

    @Test
    @DisplayName("recoverStuckSignals: a signal with no calledAt at all is not expired by the age gate -- there's no timestamp to judge staleness against, so it falls through to the normal reset/re-dispatch path")
    void stuckSignalWithNullCalledAt_notExpired() {
        stubAllEmpty();
        TradeCallRecord stuck = stuckSignal("PENDING");
        stuck.setCalledAt(null);
        when(callRepo.findByAutoTradeEvalStatusAndCalledAtBeforeAndSignalStatusNotIn(eq("PENDING"), any(), any())).thenReturn(List.of(stuck));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(TradeCallRecord.class))).thenReturn(stuck);

        service.recoverStuckSignals();

        verify(autoTradeService).evaluateSignal("user1", stuck);
    }

    @Test
    @DisplayName("recoverStuckSignals: a signal that completed between the query and the reset attempt is left alone — never force-re-dispatched")
    void signalCompletedDuringRaceWindow_leftAlone() {
        stubAllEmpty();
        TradeCallRecord stuck = stuckSignal("EVALUATING");
        when(callRepo.findByAutoTradeEvalStatusAndEvaluationLeaseUntilBeforeAndSignalStatusNotIn(eq("EVALUATING"), any(), any())).thenReturn(List.of(stuck));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(TradeCallRecord.class)))
            .thenReturn(null); // the conditional reset found it already EVALUATED — no match

        service.recoverStuckSignals();

        verify(autoTradeService, never()).evaluateSignal(any(), any());
    }

    @Test
    @DisplayName("recoverStuckSignals: a signal whose lease has NOT yet expired is not reclaimed -- a worker that's simply slow but genuinely still alive")
    void unexpiredLease_notReclaimed() {
        // The repository query itself encodes "stale" server-side — a real query would never
        // return this record at all, since its lease is still valid. Stubbing it to correctly
        // return empty is what verifies the fix actually works this way.
        stubAllEmpty();

        service.recoverStuckSignals();

        verify(mongoTemplate, never()).findAndModify(any(), any(), eq(TradeCallRecord.class));
        verify(autoTradeService, never()).evaluateSignal(any(), any());
    }

    /**
     * Covers the scenario where worker A reaches ORDER_PENDING and the lease expires -- recovery
     * must NOT reset it. The actual filtering is Spring Data's own derived-query machinery
     * against a real database; what this unit test verifies is that recoverStuckSignals()
     * actually asks for that exclusion, with the right statuses, every time it queries.
     */
    @Test
    @DisplayName("recoverStuckSignals: the EVALUATING staleness query always excludes signals already at APPROVED, ORDER_PENDING, or EXECUTED -- none of these must ever be reclaimed by an expired lease, whatever the original worker is actually doing")
    void evaluatingQuery_alwaysExcludesApprovedOrderPendingAndExecuted() {
        stubAllEmpty();

        service.recoverStuckSignals();

        ArgumentCaptor<java.util.Collection<com.tradevision.model.SignalStatus>> excludedCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(callRepo).findByAutoTradeEvalStatusAndEvaluationLeaseUntilBeforeAndSignalStatusNotIn(eq("EVALUATING"), any(), excludedCaptor.capture());
        // APPROVED is included in this exclusion, since an evaluation at a late execution stage
        // must never be reclaimed just because the lease expired.
        assertThat(excludedCaptor.getValue()).containsExactlyInAnyOrder(
            com.tradevision.model.SignalStatus.APPROVED, com.tradevision.model.SignalStatus.ORDER_PENDING,
            com.tradevision.model.SignalStatus.EXECUTED);
    }

    @Test
    @DisplayName("recoverStuckSignals: an EVALUATING record with no autoTradeEvalStartedAt at all (transitional, pre-fix data) is treated as unconditionally eligible for recovery")
    void evaluatingWithNoStartTime_transitionalFallbackCatchesIt() {
        stubAllEmpty();
        TradeCallRecord legacyStuck = stuckSignal("EVALUATING");
        legacyStuck.setAutoTradeEvalStartedAt(null); // simulates a record from before this field existed
        legacyStuck.setEvaluationLeaseUntil(null); // and before this even newer field existed either
        legacyStuck.setEvaluationLeaseUntil(null);
        when(callRepo.findByAutoTradeEvalStatusAndAutoTradeEvalStartedAtIsNull(eq("EVALUATING"))).thenReturn(List.of(legacyStuck));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(TradeCallRecord.class))).thenReturn(legacyStuck);

        service.recoverStuckSignals();

        verify(autoTradeService).evaluateSignal("user1", legacyStuck);
    }

    @Test
    @DisplayName("recoverStuckSignals: an EVALUATING record claimed by the OLD claim logic (autoTradeEvalStartedAt set, evaluationLeaseUntil never set) is caught by the newer fallback query")
    void evaluatingWithStartTimeButNoLease_newerFallbackCatchesIt() {
        stubAllEmpty();
        TradeCallRecord transitional = stuckSignal("EVALUATING");
        transitional.setEvaluationLeaseUntil(null); // the specific transitional gap this fallback exists for
        when(callRepo.findByAutoTradeEvalStatusAndEvaluationLeaseUntilIsNull(eq("EVALUATING"))).thenReturn(List.of(transitional));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(TradeCallRecord.class))).thenReturn(transitional);

        service.recoverStuckSignals();

        verify(autoTradeService).evaluateSignal("user1", transitional);
    }

    @Test
    @DisplayName("recoverStuckSignals: a record matching BOTH transitional fallback queries (autoTradeEvalStartedAt AND evaluationLeaseUntil both null) is recovered exactly ONCE, not twice — the deduplication fix")
    void recordMatchingBothFallbacks_recoveredOnlyOnce() {
        stubAllEmpty();
        TradeCallRecord veryOld = stuckSignal("EVALUATING");
        veryOld.setAutoTradeEvalStartedAt(null);
        veryOld.setEvaluationLeaseUntil(null);
        // The SAME record object returned by BOTH fallback queries, exactly as a real database
        // query would (both fields are null on this one record, so it genuinely matches both).
        when(callRepo.findByAutoTradeEvalStatusAndAutoTradeEvalStartedAtIsNull(eq("EVALUATING"))).thenReturn(List.of(veryOld));
        when(callRepo.findByAutoTradeEvalStatusAndEvaluationLeaseUntilIsNull(eq("EVALUATING"))).thenReturn(List.of(veryOld));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(TradeCallRecord.class))).thenReturn(veryOld);

        service.recoverStuckSignals();

        // Exactly once, not twice — the whole point of the deduplication fix.
        verify(autoTradeService, times(1)).evaluateSignal("user1", veryOld);
        verify(mongoTemplate, times(1)).findAndModify(any(Query.class), any(Update.class), eq(TradeCallRecord.class));
    }

    @Test
    @DisplayName("recoverStuckSignals: no stuck signals — does nothing")
    void noStuckSignals_doesNothing() {
        stubAllEmpty();

        service.recoverStuckSignals();

        verify(mongoTemplate, never()).findAndModify(any(), any(), eq(TradeCallRecord.class));
        verify(autoTradeService, never()).evaluateSignal(any(), any());
    }

    @Test
    @DisplayName("recoverStuckSignals: does nothing during shutdown")
    void duringShutdown_doesNothing() {
        when(shutdownState.isShuttingDown()).thenReturn(true);

        service.recoverStuckSignals();

        verify(callRepo, never()).findByAutoTradeEvalStatusAndCalledAtBeforeAndSignalStatusNotIn(any(), any(), any());
    }

    // ── expireStaleSignals ("Signal lifecycle is still partial" — "EXPIRED still unused") ────

    @Test
    @DisplayName("expireStaleSignals: a signal unprogressed (GENERATED) for over 24 hours is atomically marked EXPIRED")
    void expireStaleSignals_marksExpired() {
        TradeCallRecord stale = new TradeCallRecord();
        stale.setId("sig1");
        stale.setSignalStatus(com.tradevision.model.SignalStatus.GENERATED);
        when(callRepo.findBySignalStatusInAndCalledAtBefore(any(), any())).thenReturn(List.of(stale));

        service.expireStaleSignals();

        org.mockito.ArgumentCaptor<Update> updateCaptor = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(any(Query.class), updateCaptor.capture(), eq(TradeCallRecord.class));
        var setDoc = (org.bson.Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertThat(setDoc.get("signalStatus")).isEqualTo(com.tradevision.model.SignalStatus.EXPIRED);
    }

    @Test
    @DisplayName("expireStaleSignals: no stale signals — does nothing")
    void expireStaleSignals_noneStale_doesNothing() {
        when(callRepo.findBySignalStatusInAndCalledAtBefore(any(), any())).thenReturn(List.of());

        service.expireStaleSignals();

        verify(mongoTemplate, never()).findAndModify(any(), any(), eq(TradeCallRecord.class));
    }

    @Test
    @DisplayName("expireStaleSignals: does nothing during shutdown")
    void expireStaleSignals_duringShutdown_doesNothing() {
        when(shutdownState.isShuttingDown()).thenReturn(true);

        service.expireStaleSignals();

        verify(callRepo, never()).findBySignalStatusInAndCalledAtBefore(any(), any());
    }

    /**
     * Covers the case where an APPROVED signal can become permanently abandoned.
     */
    @Test
    @DisplayName("detectStaleApprovedSignals: shutting down -- does nothing at all")
    void detectStaleApprovedSignals_shuttingDown_doesNothing() {
        when(shutdownState.isShuttingDown()).thenReturn(true);

        service.detectStaleApprovedSignals();

        verify(callRepo, never()).findBySignalStatusAndCalledAtBeforeAndStaleApprovedIncidentRaisedFalse(any(), any());
    }

    @Test
    @DisplayName("detectStaleApprovedSignals: a genuinely stale APPROVED signal raises a real incident, and is NOT automatically re-traded -- autoTradeService is never touched")
    void detectStaleApprovedSignals_staleSignal_raisesIncidentNeverRetrades() {
        var signal = new TradeCallRecord();
        signal.setId("sig1");
        signal.setUserId("user1");
        signal.setSymbol("BTCUSDT");
        when(callRepo.findBySignalStatusAndCalledAtBeforeAndStaleApprovedIncidentRaisedFalse(
            eq(com.tradevision.model.SignalStatus.APPROVED), any())).thenReturn(List.of(signal));

        service.detectStaleApprovedSignals();

        verify(incidentService).raiseCritical(eq("user1"), isNull(), isNull(), eq("sig1"), eq("BTCUSDT"),
            eq("EXECUTION_STALE"), any());
        verifyNoInteractions(autoTradeService);
        // Marks the flag so this same signal isn't re-raised on the next hourly run.
        verify(mongoTemplate).findAndModify(any(), argThat(update ->
            update.getUpdateObject().toJson().contains("staleApprovedIncidentRaised")), eq(TradeCallRecord.class));
    }

    @Test
    @DisplayName("detectStaleApprovedSignals: no stale signals found -- no incident raised, mongoTemplate never touched for the flag update")
    void detectStaleApprovedSignals_noneStale_doesNothing() {
        when(callRepo.findBySignalStatusAndCalledAtBeforeAndStaleApprovedIncidentRaisedFalse(any(), any()))
            .thenReturn(List.of());

        service.detectStaleApprovedSignals();

        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), any(), any());
    }
}
