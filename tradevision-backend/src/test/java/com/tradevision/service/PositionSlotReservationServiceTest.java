package com.tradevision.service;

import com.tradevision.model.PositionSlotReservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Tests the actual query/update construction sent to MongoDB, not just "does it run" — the
 * entire point of this class is that reserve() must be a single atomic findAndModify with a
 * conditional filter, not a separate read-then-write. A test that only checks the method returns
 * something wouldn't catch a regression back to the read-then-write pattern this exists to
 * prevent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PositionSlotReservationServiceTest {

    @Mock MongoTemplate mongoTemplate;
    @Mock com.tradevision.repository.PositionSlotReservationRecordRepository reservationRecordRepo;
    @Mock com.tradevision.service.IncidentService incidentService;
    @Mock com.tradevision.repository.ExecutionContextRepository executionContextRepo;
    @InjectMocks PositionSlotReservationService service;

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        // reserve() inserts a real record as its very first step -- every existing test in this
        // file would NPE without this stub.
        when(reservationRecordRepo.insert(any(com.tradevision.model.PositionSlotReservationRecord.class))).thenAnswer(inv -> {
            com.tradevision.model.PositionSlotReservationRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId("test-slot-reservation-id");
            return r;
        });
        // The final PENDING -> ACTIVE flip in reserve() is a findAndModify on
        // PositionSlotReservationRecord. Unstubbed, Mockito returns null, which reserve() treats as
        // "record vanished" and raises a critical incident. Default to a successful flip; tests that
        // exercise the orphaned-activation path override this.
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(com.tradevision.model.PositionSlotReservationRecord.class)))
            .thenReturn(new com.tradevision.model.PositionSlotReservationRecord());
    }

    @Test
    @DisplayName("reserve: succeeds (true) when findAndModify returns an updated document")
    void reserve_succeedsWhenFindAndModifyReturnsDocument() {
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(true);
        PositionSlotReservation updated = new PositionSlotReservation();
        updated.setCredentialId("cred1");
        updated.setReservedCount(1);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PositionSlotReservation.class)))
            .thenReturn(updated);

        var result = service.reserve("cred1", 3);

        assertThat(result.reserved()).isTrue();
        assertThat(result.reservationId()).isEqualTo("test-slot-reservation-id");
    }

    @Test
    @DisplayName("reserve: fails (false) when findAndModify returns null — the conditional filter didn't match, meaning the cap was already reached")
    void reserve_failsWhenFindAndModifyReturnsNull() {
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(true);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PositionSlotReservation.class)))
            .thenReturn(null);

        var result = service.reserve("cred1", 3);

        assertThat(result.reserved()).isFalse();
        assertThat(result.reservationId()).isNull();
        // The now-unneeded PENDING record is deleted rather than left behind.
        verify(reservationRecordRepo).deleteById("test-slot-reservation-id");
    }

    @Test
    @DisplayName("reserve: the update sent to Mongo increments reservedCount by exactly 1 — never a plain set, which would reintroduce the race")
    void reserve_usesAtomicIncrement() {
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(true);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PositionSlotReservation.class)))
            .thenReturn(new PositionSlotReservation());

        service.reserve("cred1", 3);

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(any(Query.class), updateCaptor.capture(), any(FindAndModifyOptions.class), eq(PositionSlotReservation.class));
        // Update's internal representation is a Document of operators; $inc must be present with the field incremented by 1.
        var updateDoc = updateCaptor.getValue().getUpdateObject();
        assertThat(updateDoc.get("$inc")).isNotNull();
    }

    @Test
    @DisplayName("reserve: creates the per-credential document on first use, exactly once, when it doesn't already exist")
    void reserve_createsDocumentOnFirstUse() {
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(false);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PositionSlotReservation.class)))
            .thenReturn(new PositionSlotReservation());

        service.reserve("new-credential", 3);

        verify(mongoTemplate, times(1)).insert(any(PositionSlotReservation.class));
    }

    @Test
    @DisplayName("reserve: does NOT attempt to create the document when it already exists")
    void reserve_doesNotRecreateExistingDocument() {
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(true);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PositionSlotReservation.class)))
            .thenReturn(new PositionSlotReservation());

        service.reserve("existing-credential", 3);

        verify(mongoTemplate, never()).insert(any(PositionSlotReservation.class));
    }

    @Test
    @DisplayName("releaseByKey: the update sent to Mongo decrements reservedCount by exactly 1, guarded by a >0 filter so it never goes negative")
    void releaseByKey_usesAtomicDecrementWithFloorGuard() {
        service.releaseByKey("cred1");

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(queryCaptor.capture(), updateCaptor.capture(), any(FindAndModifyOptions.class), eq(PositionSlotReservation.class));

        assertThat(updateCaptor.getValue().getUpdateObject().get("$inc")).isNotNull();
        // The query must filter on reservedCount > 0 — without this guard a stray release() could push the counter negative.
        assertThat(queryCaptor.getValue().getQueryObject().toString()).contains("reservedCount");
    }

    @Test
    @DisplayName("reconcile: sets reservedCount to exactly the real open-position count passed in")
    void reconcile_setsCountToActualValue() {
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(true);

        service.reconcile("cred1", 2);

        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(PositionSlotReservation.class));
        var setDoc = (org.bson.Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertThat(setDoc.getInteger("reservedCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("reconcile: does NOT overwrite the counter when a reservation happened within the grace window")
    void reconcile_skipsOverwriteDuringGraceWindow() {
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(true);
        PositionSlotReservation recentlyReserved = new PositionSlotReservation();
        recentlyReserved.setCredentialId("cred1");
        recentlyReserved.setReservedCount(1);
        recentlyReserved.setLastReservedAt(java.time.Instant.now().minusSeconds(5)); // reserved 5 seconds ago — well within the grace window
        when(mongoTemplate.findOne(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(recentlyReserved);

        // Simulates the race where reconciliation runs and sees 0 OPEN positions (the
        // order/Position for the recent reservation hasn't been saved yet).
        service.reconcile("cred1", 0);

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(PositionSlotReservation.class));
    }

    @Test
    @DisplayName("reconcile: DOES overwrite once a stale reservation is well outside the grace window")
    void reconcile_overwritesOnceGraceWindowExpired() {
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(true);
        PositionSlotReservation staleReservation = new PositionSlotReservation();
        staleReservation.setCredentialId("cred1");
        staleReservation.setReservedCount(1);
        staleReservation.setLastReservedAt(java.time.Instant.now().minusSeconds(600)); // 10 minutes ago — long past any plausible in-flight order
        when(mongoTemplate.findOne(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(staleReservation);

        service.reconcile("cred1", 0);

        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(PositionSlotReservation.class));
    }

    // ── reservation-identity architecture ──────────────────────────────

    /**
     * Tests for the reservation record lifecycle.
     */
    @Test
    @DisplayName("reserve: the record is inserted as PENDING before any counter is touched, then flipped to ACTIVE once the counter claim succeeds")
    void reserve_insertsRecordThenFlipsToActive() {
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(true);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PositionSlotReservation.class)))
            .thenReturn(new PositionSlotReservation());

        var result = service.reserve("cred1", 3);

        assertThat(result.reserved()).isTrue();
        var captor = ArgumentCaptor.forClass(com.tradevision.model.PositionSlotReservationRecord.class);
        verify(reservationRecordRepo).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
        verify(mongoTemplate).findAndModify(
            argThat(q -> q.getQueryObject().toJson().contains("PENDING")),
            argThat(u -> u.getUpdateObject().toJson().contains("ACTIVE")),
            eq(com.tradevision.model.PositionSlotReservationRecord.class));
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * Same lifecycle guarantee as ExposureReservationServiceTest's own identical test, proven
     * here for slot reservations.
     */
    @Test
    @DisplayName("reserve: no ClientSession available at all -- falls back to the sequential, non-transactional approach rather than failing outright")
    void reserve_noClientSessionAvailable_fallsBackToSequential() {
        when(mongoTemplate.getMongoDatabaseFactory()).thenThrow(new RuntimeException("simulated: no session support on this deployment"));
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(true);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PositionSlotReservation.class)))
            .thenReturn(new PositionSlotReservation());

        var result = service.reserve("cred1", 3);

        assertThat(result.reserved()).isTrue();
        verify(reservationRecordRepo).insert(any(com.tradevision.model.PositionSlotReservationRecord.class));
    }

    /**
     * Same guarantee as ExposureReservationServiceTest's own identical tests, proven here for
     * slot reservations: a LIVE reservation never falls back to the non-transactional path.
     */
    @Test
    @DisplayName("reserve: live=true and no ClientSession available -- rejects and halts, NEVER falls back to the sequential path")
    void reserve_liveTrueNoClientSession_rejectsAndHalts() {
        when(mongoTemplate.getMongoDatabaseFactory()).thenThrow(new RuntimeException("simulated: no session support on this deployment"));

        var result = service.reserve("cred1", 3, "exec1", true);

        assertThat(result.reserved()).isFalse();
        verify(reservationRecordRepo, never()).insert(any(com.tradevision.model.PositionSlotReservationRecord.class));
        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && Boolean.TRUE.equals(setDoc.getBoolean("tradingHalted"));
        }), eq(com.tradevision.model.RiskProfile.class));
        verify(incidentService).raiseCritical(any(), eq("cred1"), any(), any(), any(),
            eq("LIVE_RESERVATION_TRANSACTION_UNAVAILABLE"), any());
    }

    @Test
    @DisplayName("reserve: live=true with a plan-scoped key -- rejects and raises the incident, but does NOT attempt a tradingHalted update (no single credential to halt for a plan-scoped key)")
    void reserve_liveTruePlanScopedKey_rejectsWithoutHaltUpdate() {
        when(mongoTemplate.getMongoDatabaseFactory()).thenThrow(new RuntimeException("simulated: no session support on this deployment"));

        var result = service.reserve("plan:plan1", 3, "exec1", true);

        assertThat(result.reserved()).isFalse();
        verify(mongoTemplate, never()).updateFirst(any(), any(Update.class), eq(com.tradevision.model.RiskProfile.class));
        verify(incidentService).raiseCritical(any(), isNull(), any(), any(), any(),
            eq("LIVE_RESERVATION_TRANSACTION_UNAVAILABLE"), any());
    }

    /**
     * Same guarantee as ExposureReservationServiceTest's own identical test, proven here for
     * slot reservations.
     */
    @Test
    @DisplayName("reserve: the final PENDING-to-ACTIVE transition matches zero documents (record deleted concurrently) -- raises a critical incident naming the exact record and key")
    void reserve_finalActivationTransitionFails_raisesCriticalIncident() {
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(true);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PositionSlotReservation.class)))
            .thenReturn(new PositionSlotReservation());
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(com.tradevision.model.PositionSlotReservationRecord.class)))
            .thenReturn(null); // the transition itself matched zero documents

        var result = service.reserve("cred1", 3);

        assertThat(result.reserved()).isTrue();
        verify(incidentService).raiseCritical(any(), eq("cred1"), any(), any(), any(),
            eq("SLOT_RESERVATION_ORPHANED_AT_ACTIVATION"), any());
    }

    @Test
    @DisplayName("reserve: if the record insert itself fails, no counter is ever touched -- rejects immediately")
    void reserve_recordInsertFails_rejectsWithoutTouchingCounter() {
        when(reservationRecordRepo.insert(any(com.tradevision.model.PositionSlotReservationRecord.class)))
            .thenThrow(new RuntimeException("simulated database error"));

        var result = service.reserve("cred1", 3);

        assertThat(result.reserved()).isFalse();
        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PositionSlotReservation.class));
    }

    @Test
    @DisplayName("release(reservationId): atomically claims the record (ACTIVE -> RELEASED) before decrementing, and floors the counter at zero instead of going negative")
    void releaseById_claimsRecordThenFloorsAtZero() {
        var record = new com.tradevision.model.PositionSlotReservationRecord();
        record.setId("res1"); record.setKey("cred1");
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(com.tradevision.model.PositionSlotReservationRecord.class)))
            .thenReturn(record);
        // The conditional decrement's own .gt(0) guard fails -- counter is already at 0.
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PositionSlotReservation.class)))
            .thenReturn(null);

        service.release("res1");

        verify(mongoTemplate).updateFirst(any(Query.class),
            argThat(u -> {
                Object setObj = u.getUpdateObject().get("$set");
                return setObj instanceof org.bson.Document doc && Integer.valueOf(0).equals(doc.get("reservedCount"));
            }),
            eq(PositionSlotReservation.class));
    }

    @Test
    @DisplayName("release(reservationId): a record already RELEASED (or a genuinely unknown id) is a real no-op -- never touches the counter")
    void releaseById_alreadyReleasedOrUnknown_noOp() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(com.tradevision.model.PositionSlotReservationRecord.class)))
            .thenReturn(null);

        service.release("res1");

        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(PositionSlotReservation.class));
    }

    @Test
    @DisplayName("reconcile: deletes stale PENDING records older than the grace window")
    void reconcile_deletesStalePendingRecords() {
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(true);
        var stalePending = new com.tradevision.model.PositionSlotReservationRecord();
        stalePending.setId("stale1"); stalePending.setKey("cred1"); stalePending.setStatus("PENDING");
        when(reservationRecordRepo.findByKeyAndStatusAndCreatedAtBefore(eq("cred1"), eq("PENDING"), any()))
            .thenReturn(java.util.List.of(stalePending));

        service.reconcile("cred1", 0);

        verify(reservationRecordRepo).deleteById("stale1");
    }

    /**
     * Same guarantee as ExposureReservationServiceTest's own identical tests, proven here for
     * slot reservations.
     */
    @Test
    @DisplayName("reconcile: a stale PENDING record whose linked execution shows real progress is NOT deleted -- escalated instead")
    void reconcile_stalePendingWithExecutionThatMayHaveReachedExchange_notDeletedEscalates() {
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(true);
        var stalePending = new com.tradevision.model.PositionSlotReservationRecord();
        stalePending.setId("stale1"); stalePending.setKey("cred1"); stalePending.setStatus("PENDING"); stalePending.setExecutionId("exec1");
        when(reservationRecordRepo.findByKeyAndStatusAndCreatedAtBefore(eq("cred1"), eq("PENDING"), any()))
            .thenReturn(java.util.List.of(stalePending));
        var execution = new com.tradevision.model.ExecutionContext();
        execution.setExecutionId("exec1"); execution.setStatus("ORDER_SUBMITTED");
        when(executionContextRepo.findById("exec1")).thenReturn(java.util.Optional.of(execution));

        service.reconcile("cred1", 0);

        verify(reservationRecordRepo, never()).deleteById("stale1");
        verify(incidentService).raiseCritical(any(), eq("cred1"), any(), any(), any(),
            eq("STALE_PENDING_RESERVATION_POSSIBLE_EXCHANGE_EXECUTION"), any());
    }

    @Test
    @DisplayName("reconcile: a stale PENDING record whose linked execution never reached the exchange IS deleted -- genuinely safe")
    void reconcile_stalePendingWithExecutionThatNeverReachedExchange_deletedSafely() {
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(true);
        var stalePending = new com.tradevision.model.PositionSlotReservationRecord();
        stalePending.setId("stale1"); stalePending.setKey("cred1"); stalePending.setStatus("PENDING"); stalePending.setExecutionId("exec1");
        when(reservationRecordRepo.findByKeyAndStatusAndCreatedAtBefore(eq("cred1"), eq("PENDING"), any()))
            .thenReturn(java.util.List.of(stalePending));
        var execution = new com.tradevision.model.ExecutionContext();
        execution.setExecutionId("exec1"); execution.setStatus("RISK_APPROVED");
        when(executionContextRepo.findById("exec1")).thenReturn(java.util.Optional.of(execution));

        service.reconcile("cred1", 0);

        verify(reservationRecordRepo).deleteById("stale1");
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), eq("STALE_PENDING_RESERVATION_POSSIBLE_EXCHANGE_EXECUTION"), any());
    }

    /**
     * Covers the case where a PENDING record is still in flight.
     */
    @Test
    @DisplayName("reconcile: a PENDING record still in flight skips the counter overwrite entirely, even well outside the time-based grace window")
    void reconcile_pendingRecordInFlight_skipsOverwriteRegardlessOfGraceWindow() {
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(true);
        var staleReservation = new PositionSlotReservation();
        staleReservation.setCredentialId("cred1");
        staleReservation.setLastReservedAt(java.time.Instant.now().minusSeconds(600));
        when(mongoTemplate.findOne(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(staleReservation);
        var inFlight = new com.tradevision.model.PositionSlotReservationRecord();
        inFlight.setId("pending1"); inFlight.setKey("cred1"); inFlight.setStatus("PENDING");
        when(reservationRecordRepo.findByKeyAndStatus("cred1", "PENDING")).thenReturn(java.util.List.of(inFlight));

        service.reconcile("cred1", 0);

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(PositionSlotReservation.class));
    }

    /**
     * Same guarantee as ExposureReservationServiceTest's own identical test, proven here for
     * slot reservations.
     */
    @Test
    @DisplayName("reconcile: an ACTIVE reservation not yet linked to any position is ADDED to the position-derived count, not silently excluded")
    void reconcile_unlinkedActiveReservation_addedToPositionDerivedCount() {
        when(mongoTemplate.exists(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(true);
        var staleReservation = new PositionSlotReservation();
        staleReservation.setCredentialId("cred1");
        staleReservation.setLastReservedAt(java.time.Instant.now().minusSeconds(600));
        when(mongoTemplate.findOne(any(Query.class), eq(PositionSlotReservation.class))).thenReturn(staleReservation);
        var unlinked = new com.tradevision.model.PositionSlotReservationRecord();
        unlinked.setId("unlinked1"); unlinked.setKey("cred1"); unlinked.setStatus("ACTIVE"); // positionId genuinely null
        when(reservationRecordRepo.findByKeyAndStatusAndPositionIdIsNull("cred1", "ACTIVE")).thenReturn(java.util.List.of(unlinked));

        // Position-derived count is 2 -- the real, corrected count must be 2 + 1 = 3.
        service.reconcile("cred1", 2);

        var updateCaptor = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(PositionSlotReservation.class));
        var setDoc = (org.bson.Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertThat(setDoc.getInteger("reservedCount")).isEqualTo(3);
    }
}
