package com.tradevision.service;

import com.tradevision.model.ExposureReservation;
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
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Review finding ("P0 #3" — "Exposure limits are still not atomic"): same discipline as
 * PositionSlotReservationServiceTest — tests the actual atomic findAndModify calls sent to
 * MongoDB, and the two-step reserve-then-rollback behavior specifically, not just "does it run".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExposureReservationServiceTest {

    @Mock MongoTemplate mongoTemplate;
    @Mock com.tradevision.repository.RiskProfileRepository riskProfileRepo;
    @Mock com.tradevision.repository.ExposureReservationRecordRepository reservationRecordRepo;
    @Mock com.tradevision.service.IncidentService incidentService;
    @Mock com.tradevision.repository.ExecutionContextRepository executionContextRepo;
    @InjectMocks ExposureReservationService service;

    @BeforeEach
    void setup() {
        when(mongoTemplate.exists(any(Query.class), eq(ExposureReservation.class))).thenReturn(true);
        // Review finding ("Exposure reservation creation is still not atomic with the exposure
        // counter" -- external review, twenty-eighth pass, P0, full context in reserve()'s own
        // updated javadoc): reserve() now inserts a real record as its very first step -- every
        // existing test in this file would NPE without this stub, since @InjectMocks leaves an
        // unmocked repository field null. Mirrors a real MongoDB insert: assigns a real id and
        // returns the same object, exactly as reservationRecordRepo.insert(record) really does.
        when(reservationRecordRepo.insert(any(com.tradevision.model.ExposureReservationRecord.class))).thenAnswer(inv -> {
            com.tradevision.model.ExposureReservationRecord r = inv.getArgument(0);
            if (r.getId() == null) r.setId("test-reservation-id");
            return r;
        });
        // The final PENDING -> ACTIVE flip in reserve() is a findAndModify on
        // ExposureReservationRecord (not ExposureReservation). Unstubbed, Mockito returns null,
        // which reserve() correctly treats as "record vanished" and raises a critical incident.
        // Default to a successful flip; tests exercising the orphaned-activation path override this.
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(com.tradevision.model.ExposureReservationRecord.class)))
            .thenReturn(new com.tradevision.model.ExposureReservationRecord());
    }

    @Test
    @DisplayName("reserve: both total and symbol caps configured and satisfied — one atomic findAndModify per cap, both succeed")
    void reserve_bothCapsSatisfied_succeeds() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(new ExposureReservation()); // non-null = the conditional findAndModify matched a document

        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900));

        assertThat(result.allowed()).isTrue();
        verify(mongoTemplate, times(2)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class));
    }

    @Test
    @DisplayName("reserve: total cap would be exceeded — refused before ever attempting the symbol-level reservation")
    void reserve_totalCapExceeded_refusesWithoutTouchingSymbolField() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(null); // the conditional query matched nothing — cap would be exceeded

        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).containsIgnoringCase("total exposure cap");
        // Exactly one findAndModify — the failed total-cap step. Never reached the symbol step.
        verify(mongoTemplate, times(1)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class));
    }

    @Test
    @DisplayName("reserve: total succeeds but symbol cap would be exceeded — rolls back the total reservation via a negative $inc")
    void reserve_symbolCapExceeded_rollsBackTotal() {
        // First findAndModify call (total) succeeds; second (symbol) fails.
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(new ExposureReservation())   // total: succeeds
            .thenReturn(null);                       // symbol: fails

        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(500));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).containsIgnoringCase("per-symbol exposure cap");
        // Two updateFirst calls happen in this flow: ensureSymbolFieldExists (unconditional, at
        // the very start of reserve()) and the rollback itself — capture both and confirm the
        // rollback's negative increment is among them, rather than assuming call count/order.
        org.mockito.ArgumentCaptor<Update> updateCaptor = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(Query.class), updateCaptor.capture(), eq(ExposureReservation.class));
        boolean sawRollback = updateCaptor.getAllValues().stream().anyMatch(u -> {
            Object inc = u.getUpdateObject().get("$inc");
            return inc instanceof org.bson.Document doc && new org.bson.types.Decimal128(BigDecimal.valueOf(800).negate()).equals(doc.get("reservedTotalExposureQuote"));
        });
        assertThat(sawRollback).isTrue();
    }

    @Test
    @DisplayName("reserve: only the total cap is configured (symbol cap unset/zero) — never attempts a symbol-level reservation at all")
    void reserve_onlyTotalCapConfigured_skipsSymbolStep() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(new ExposureReservation());

        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.ZERO);

        assertThat(result.allowed()).isTrue();
        // Exactly one findAndModify — total only, symbol cap unset means no constraint to enforce.
        verify(mongoTemplate, times(1)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class));
    }

    @Test
    @DisplayName("release: decrements both total and symbol fields via negative $inc, conditioned on there being enough reserved to release")
    void release_decrementsBothFields() {
        service.release("cred1", "BTCUSDT", BigDecimal.valueOf(800));

        verify(mongoTemplate, times(2)).updateFirst(any(Query.class), any(Update.class), eq(ExposureReservation.class));
    }

    @Test
    @DisplayName("release: a non-positive amount is a no-op — never calls MongoDB at all")
    void release_nonPositiveAmount_noOp() {
        service.release("cred1", "BTCUSDT", BigDecimal.ZERO);

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(ExposureReservation.class));
    }

    @Test
    @DisplayName("reconcile: skips the overwrite when a reservation happened within the grace window — same regression guard as slot reservation")
    void reconcile_skipsOverwriteDuringGraceWindow() {
        ExposureReservation recent = new ExposureReservation();
        recent.setCredentialId("cred1");
        recent.setLastReservedAt(java.time.Instant.now().minusSeconds(5));
        when(mongoTemplate.findOne(any(Query.class), eq(ExposureReservation.class))).thenReturn(recent);

        service.reconcile("cred1", BigDecimal.ZERO, java.util.Map.of());

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(ExposureReservation.class));
    }

    @Test
    @DisplayName("reconcile: overwrites once well outside the grace window")
    void reconcile_overwritesOnceGraceWindowExpired() {
        ExposureReservation stale = new ExposureReservation();
        stale.setCredentialId("cred1");
        stale.setLastReservedAt(java.time.Instant.now().minusSeconds(600));
        when(mongoTemplate.findOne(any(Query.class), eq(ExposureReservation.class))).thenReturn(stale);

        service.reconcile("cred1", BigDecimal.valueOf(500), java.util.Map.of("BTCUSDT", BigDecimal.valueOf(500)));

        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), eq(ExposureReservation.class));
    }

    /**
     * Review finding ("reservation reconciliation is still fundamentally cache-based" --
     * external review, twenty-ninth pass, P1, the review's own explicit "biggest remaining
     * reservation concern," full context in reconcile()'s own updated javadoc): the actual test
     * proving the fix -- an ACTIVE reservation genuinely not yet linked to a position is
     * included in the reconciled total, closing the review's own named failure scenario where
     * a position-derived-only reconcile would have silently dropped it.
     */
    @Test
    @DisplayName("reconcile: an ACTIVE reservation not yet linked to any position is ADDED to the position-derived total, not silently excluded")
    void reconcile_unlinkedActiveReservation_addedToPositionDerivedTotal() {
        ExposureReservation stale = new ExposureReservation();
        stale.setCredentialId("cred1");
        stale.setLastReservedAt(java.time.Instant.now().minusSeconds(600));
        when(mongoTemplate.findOne(any(Query.class), eq(ExposureReservation.class))).thenReturn(stale);
        var unlinked = new com.tradevision.model.ExposureReservationRecord();
        unlinked.setId("unlinked1"); unlinked.setCredentialId("cred1"); unlinked.setStatus("ACTIVE");
        unlinked.setTotalAmountReserved(BigDecimal.valueOf(300)); // positionId genuinely null -- never set
        when(reservationRecordRepo.findByCredentialIdAndStatusAndPositionIdIsNull("cred1", "ACTIVE")).thenReturn(java.util.List.of(unlinked));

        // Position-derived total is 500 -- the real, corrected total must be 500 + 300 = 800.
        service.reconcile("cred1", BigDecimal.valueOf(500), java.util.Map.of("BTCUSDT", BigDecimal.valueOf(500)));

        var updateCaptor = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(ExposureReservation.class));
        var setDoc = (org.bson.Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertThat(new BigDecimal(setDoc.get("reservedTotalExposureQuote").toString())).isEqualByComparingTo(BigDecimal.valueOf(800));
    }

    @Test
    @DisplayName("reconcile: no unlinked ACTIVE reservations -- the corrected total equals the position-derived total exactly, unchanged from before this fix")
    void reconcile_noUnlinkedActiveReservations_totalUnchanged() {
        ExposureReservation stale = new ExposureReservation();
        stale.setCredentialId("cred1");
        stale.setLastReservedAt(java.time.Instant.now().minusSeconds(600));
        when(mongoTemplate.findOne(any(Query.class), eq(ExposureReservation.class))).thenReturn(stale);
        when(reservationRecordRepo.findByCredentialIdAndStatusAndPositionIdIsNull("cred1", "ACTIVE")).thenReturn(java.util.List.of());

        service.reconcile("cred1", BigDecimal.valueOf(500), java.util.Map.of("BTCUSDT", BigDecimal.valueOf(500)));

        var updateCaptor = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(ExposureReservation.class));
        var setDoc = (org.bson.Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertThat(new BigDecimal(setDoc.get("reservedTotalExposureQuote").toString())).isEqualByComparingTo(BigDecimal.valueOf(500));
    }

    // ── Correlation-group reservations ("Risk" — "atomic correlation reservations") ────

    @Test
    @DisplayName("reserve: symbol belongs to a configured correlation group with room — third atomic findAndModify for the group, alongside total and symbol")
    void reserve_correlationGroupSatisfied_addsThirdAtomicStep() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(new ExposureReservation()); // every step succeeds

        var groups = java.util.Map.of("L1-majors", java.util.Set.of("BTCUSDT", "ETHUSDT"));
        var caps = java.util.Map.of("L1-majors", BigDecimal.valueOf(2000));
        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900), groups, caps, "exec1");

        assertThat(result.allowed()).isTrue();
        // Total + symbol + group = 3 atomic findAndModify calls, not 2.
        verify(mongoTemplate, times(3)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class));
    }

    @Test
    @DisplayName("reserve: symbol not in any configured correlation group — group step is skipped entirely, same call count as before this feature existed")
    void reserve_symbolNotInAnyGroup_skipsGroupStep() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(new ExposureReservation());

        var groups = java.util.Map.of("L1-majors", java.util.Set.of("ETHUSDT")); // BTCUSDT not in this group
        var caps = java.util.Map.of("L1-majors", BigDecimal.valueOf(2000));
        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900), groups, caps, "exec1");

        assertThat(result.allowed()).isTrue();
        verify(mongoTemplate, times(2)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class));
    }

    @Test
    @DisplayName("reserve: group cap would be exceeded — rejects AND rolls back both the total and symbol reservations that already succeeded, all-or-nothing")
    void reserve_groupCapExceeded_rollsBackTotalAndSymbol() {
        // Total succeeds, symbol succeeds, group fails.
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(new ExposureReservation())
            .thenReturn(new ExposureReservation())
            .thenReturn(null);

        var groups = java.util.Map.of("L1-majors", java.util.Set.of("BTCUSDT"));
        var caps = java.util.Map.of("L1-majors", BigDecimal.valueOf(500));
        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900), groups, caps, "exec1");

        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).containsIgnoringCase("correlation-group exposure cap");

        org.mockito.ArgumentCaptor<Update> updateCaptor = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(Query.class), updateCaptor.capture(), eq(ExposureReservation.class));
        boolean sawTotalRollback = updateCaptor.getAllValues().stream().anyMatch(u -> {
            Object inc = u.getUpdateObject().get("$inc");
            return inc instanceof org.bson.Document doc && new org.bson.types.Decimal128(BigDecimal.valueOf(800).negate()).equals(doc.get("reservedTotalExposureQuote"));
        });
        boolean sawSymbolRollback = updateCaptor.getAllValues().stream().anyMatch(u -> {
            Object inc = u.getUpdateObject().get("$inc");
            return inc instanceof org.bson.Document doc && new org.bson.types.Decimal128(BigDecimal.valueOf(800).negate()).equals(doc.get("reservedSymbolExposure.BTCUSDT"));
        });
        assertThat(sawTotalRollback).isTrue();
        assertThat(sawSymbolRollback).isTrue();
    }

    /**
     * P3-5 fix ("ExposureReservationService group/symbol field paths -- user-supplied group
     * names used as Mongo field paths ('.'/'$') -- validate names" -- external review, full
     * context in isSafeGroupName's own javadoc): RiskProfileService.upsert now rejects a bad
     * group name before it can ever be saved, so this is the defense-in-depth path for a profile
     * document that already has one (written before that fix existed, or by a direct database
     * write). The actual proof: a bad name is silently skipped -- the reservation still succeeds
     * using only its safe total/symbol steps -- rather than building the dangerous
     * "reservedGroupExposure.<bad name>" field path at all.
     */
    @Test
    @DisplayName("reserve: a correlation group name containing '.' is skipped entirely -- never used to build a Mongo field path, and the reservation still succeeds on total/symbol alone")
    void reserve_correlationGroupNameWithDot_skippedNeverUsedAsFieldPath() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(new ExposureReservation());

        var groups = java.util.Map.of("Majors.sub", java.util.Set.of("BTCUSDT"));
        var caps = java.util.Map.of("Majors.sub", BigDecimal.valueOf(500));
        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900), groups, caps, "exec1");

        assertThat(result.allowed()).isTrue();
        // Exactly the total + symbol steps -- no third findAndModify for the (skipped) group step.
        verify(mongoTemplate, times(2)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class));
        org.mockito.ArgumentCaptor<Update> updateCaptor = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, atLeastOnce()).findAndModify(any(Query.class), updateCaptor.capture(), any(FindAndModifyOptions.class), eq(ExposureReservation.class));
        boolean everTouchedTheUnsafeGroupPath = updateCaptor.getAllValues().stream().anyMatch(u -> {
            Object inc = u.getUpdateObject().get("$inc");
            return inc instanceof org.bson.Document doc && doc.keySet().stream().anyMatch(k -> k.startsWith("reservedGroupExposure"));
        });
        assertThat(everTouchedTheUnsafeGroupPath).isFalse();
    }

    @Test
    @DisplayName("reserve: a correlation group name containing '$' is likewise skipped entirely")
    void reserve_correlationGroupNameWithDollarSign_skipped() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(new ExposureReservation());

        var groups = java.util.Map.of("$where", java.util.Set.of("BTCUSDT"));
        var caps = java.util.Map.of("$where", BigDecimal.valueOf(500));
        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900), groups, caps, "exec1");

        assertThat(result.allowed()).isTrue();
        verify(mongoTemplate, times(2)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class));
    }

    @Test
    @DisplayName("reserve: null correlationGroups/correlationGroupCaps behaves identically to the original overload — no NPE, no group step attempted")
    void reserve_nullGroups_behavesLikeOriginalOverload() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(new ExposureReservation());

        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900), null, null, "exec1");

        assertThat(result.allowed()).isTrue();
        verify(mongoTemplate, times(2)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class));
    }

    @Test
    @DisplayName("release: also releases a matching correlation-group reservation, looked up from the profile — without threading it through every call site")
    void release_alsoReleasesMatchingGroup() {
        var profile = new com.tradevision.model.RiskProfile();
        profile.setCorrelationGroups(java.util.Map.of("L1-majors", java.util.Set.of("BTCUSDT")));
        when(riskProfileRepo.findByCredentialId("cred1")).thenReturn(java.util.Optional.of(profile));

        service.release("cred1", "BTCUSDT", BigDecimal.valueOf(800));

        org.mockito.ArgumentCaptor<Update> updateCaptor = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate, atLeastOnce()).updateFirst(any(Query.class), updateCaptor.capture(), eq(ExposureReservation.class));
        boolean sawGroupRelease = updateCaptor.getAllValues().stream().anyMatch(u -> {
            Object inc = u.getUpdateObject().get("$inc");
            return inc instanceof org.bson.Document doc && new org.bson.types.Decimal128(BigDecimal.valueOf(800).negate()).equals(doc.get("reservedGroupExposure.L1-majors"));
        });
        assertThat(sawGroupRelease).isTrue();
    }

    @Test
    @DisplayName("release: riskProfileRepo lookup failing is non-fatal — total/symbol release still succeeds")
    void release_profileLookupFails_stillReleasesTotalAndSymbol() {
        when(riskProfileRepo.findByCredentialId("cred1")).thenThrow(new RuntimeException("simulated database error"));

        service.release("cred1", "BTCUSDT", BigDecimal.valueOf(800));

        verify(mongoTemplate, times(2)).updateFirst(any(Query.class), any(Update.class), eq(ExposureReservation.class));
    }

    @Test
    @DisplayName("reconcile: with actualGroupExposure provided, writes reservedGroupExposure too")
    void reconcile_withGroupExposure_writesGroupField() {
        ExposureReservation stale = new ExposureReservation();
        stale.setCredentialId("cred1");
        stale.setLastReservedAt(java.time.Instant.now().minusSeconds(600));
        when(mongoTemplate.findOne(any(Query.class), eq(ExposureReservation.class))).thenReturn(stale);

        service.reconcile("cred1", BigDecimal.valueOf(500), java.util.Map.of("BTCUSDT", BigDecimal.valueOf(500)),
            java.util.Map.of("L1-majors", BigDecimal.valueOf(500)));

        org.mockito.ArgumentCaptor<Update> updateCaptor = org.mockito.ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(ExposureReservation.class));
        assertThat(updateCaptor.getValue().getUpdateObject().get("$set")).isInstanceOf(org.bson.Document.class);
        org.bson.Document setDoc = (org.bson.Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertThat(setDoc.containsKey("reservedGroupExposure")).isTrue();
    }

    // ── P0-1: record created before any counter is touched ────────────────────

    /**
     * Review finding ("Exposure reservation creation is still not atomic with the exposure
     * counter" -- external review, twenty-eighth pass, P0, the review's own explicit "biggest
     * thing found in v174"): the actual tests for the reordered reserve().
     */
    @Test
    @DisplayName("reserve: the record is inserted as PENDING BEFORE any counter findAndModify is ever attempted")
    void reserve_insertsRecordBeforeAnyCounterClaim() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(new ExposureReservation());

        service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900));

        var captor = org.mockito.ArgumentCaptor.forClass(com.tradevision.model.ExposureReservationRecord.class);
        verify(reservationRecordRepo).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(captor.getValue().getTotalAmountReserved()).isEqualByComparingTo(BigDecimal.valueOf(800));
    }

    @Test
    @DisplayName("reserve: if the record insert itself fails, no counter is ever touched -- rejects immediately")
    void reserve_recordInsertFails_rejectsWithoutTouchingAnyCounter() {
        when(reservationRecordRepo.insert(any(com.tradevision.model.ExposureReservationRecord.class)))
            .thenThrow(new RuntimeException("simulated database error"));

        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900));

        assertThat(result.allowed()).isFalse();
        verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class));
    }

    @Test
    @DisplayName("reserve: every counter claim succeeds -- the record is atomically flipped from PENDING to ACTIVE as the final step")
    void reserve_allClaimsSucceed_flipsRecordToActive() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(new ExposureReservation());

        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900));

        assertThat(result.allowed()).isTrue();
        assertThat(result.reservationId()).isEqualTo("test-reservation-id");
        verify(mongoTemplate).findAndModify(
            argThat(q -> q.getQueryObject().toJson().contains("PENDING")),
            argThat(u -> u.getUpdateObject().toJson().contains("ACTIVE")),
            eq(com.tradevision.model.ExposureReservationRecord.class));
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * Review finding ("Reservation lifecycle is still not transactionally safe" -- external
     * review, thirty-eighth pass, P0, the review's own explicit final fix, full context in
     * reserveTransactionally's own updated javadoc): the actual tests proving the fix, modeled
     * on RiskProfileServiceTest's own identical pattern for the same established fallback
     * mechanism.
     */
    @Test
    @DisplayName("reserve: no ClientSession available at all -- falls back to the sequential, non-transactional approach rather than failing outright")
    void reserve_noClientSessionAvailable_fallsBackToSequential() {
        when(mongoTemplate.getMongoDatabaseFactory()).thenThrow(new RuntimeException("simulated: no session support on this deployment"));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(new ExposureReservation());

        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900));

        // The fallback genuinely ran and succeeded -- same outcome as the transactional path
        // would have produced, just via the sequential mechanism.
        assertThat(result.allowed()).isTrue();
        verify(reservationRecordRepo).insert(any(com.tradevision.model.ExposureReservationRecord.class));
    }

    /**
     * Review finding ("Make LIVE reservation transaction fallback impossible" -- external
     * review, thirty-ninth pass, the review's own explicit required flow: "transaction
     * unavailable -> DO NOT fallback -> reject reservation -> halt autonomous execution ->
     * critical incident"): the actual test proving the fix -- when live=true and no
     * ClientSession is available, the reservation is rejected, NOT silently handed to the
     * sequential fallback, and the account is halted.
     */
    @Test
    @DisplayName("reserve: live=true and no ClientSession available -- rejects and halts, NEVER falls back to the sequential path")
    void reserve_liveTrueNoClientSession_rejectsAndHalts() {
        when(mongoTemplate.getMongoDatabaseFactory()).thenThrow(new RuntimeException("simulated: no session support on this deployment"));

        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900),
            null, null, "exec1", true);

        assertThat(result.allowed()).isFalse();
        verify(reservationRecordRepo, never()).insert(any(com.tradevision.model.ExposureReservationRecord.class));
        verify(mongoTemplate).updateFirst(any(), argThat(u -> {
            var setDoc = u.getUpdateObject().get("$set", org.bson.Document.class);
            return setDoc != null && Boolean.TRUE.equals(setDoc.getBoolean("tradingHalted"));
        }), eq(com.tradevision.model.RiskProfile.class));
        verify(incidentService).raiseCritical(any(), eq("cred1"), any(), any(), eq("BTCUSDT"),
            eq("LIVE_RESERVATION_TRANSACTION_UNAVAILABLE"), any());
    }

    @Test
    @DisplayName("reserve: live=false (TESTNET/PAPER) and no ClientSession available -- still falls back to the sequential path exactly as before, the fail-closed policy does not apply outside LIVE")
    void reserve_liveFalseNoClientSession_stillFallsBack() {
        when(mongoTemplate.getMongoDatabaseFactory()).thenThrow(new RuntimeException("simulated: no session support on this deployment"));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(new ExposureReservation());

        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900),
            null, null, "exec1", false);

        assertThat(result.allowed()).isTrue();
        verify(reservationRecordRepo).insert(any(com.tradevision.model.ExposureReservationRecord.class));
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), eq("LIVE_RESERVATION_TRANSACTION_UNAVAILABLE"), any());
    }

    /**
     * Review finding ("v183 still has a dangerous 'PENDING reservation cleanup' window" --
     * external review, thirty-fifth pass, P0, full context in reserve()'s own updated comment
     * on this final transition): the actual test proving the fix -- when the final PENDING-to-
     * ACTIVE transition matches zero documents (the review's own named race: the record was
     * deleted out from under this call by a concurrent stale-PENDING cleanup pass), a critical
     * incident is raised naming the exact record and credential, rather than the failure being
     * silently discarded as it was before this fix.
     */
    @Test
    @DisplayName("reserve: the final PENDING-to-ACTIVE transition matches zero documents (record deleted concurrently) -- raises a critical incident naming the exact record and credential, rather than silently discarding the failure")
    void reserve_finalActivationTransitionFails_raisesCriticalIncident() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(new ExposureReservation());
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(com.tradevision.model.ExposureReservationRecord.class)))
            .thenReturn(null); // the transition itself matched zero documents

        var result = service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900));

        // The reservation still succeeds from the caller's own point of view -- the counters
        // genuinely were claimed, and there's no safe way to unwind them at this point without
        // risking a race with whatever already deleted the record. The incident is what makes
        // this loud rather than silent.
        assertThat(result.allowed()).isTrue();
        verify(incidentService).raiseCritical(any(), eq("cred1"), any(), any(), eq("BTCUSDT"),
            eq("EXPOSURE_RESERVATION_ORPHANED_AT_ACTIVATION"), any());
    }

    @Test
    @DisplayName("reserve: a rejected cap deletes the now-unneeded PENDING record rather than leaving it behind")
    void reserve_rejected_deletesThePendingRecord() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(ExposureReservation.class)))
            .thenReturn(null);

        service.reserve("cred1", "BTCUSDT", BigDecimal.valueOf(800), BigDecimal.valueOf(1000), BigDecimal.valueOf(900));

        verify(reservationRecordRepo).deleteById("test-reservation-id");
    }

    // ── P1-2: release(reservationId) never drives a counter negative ──────────

    /**
     * Review finding ("Exposure reservation records can remain ACTIVE after reconciliation
     * overwrites counters" -- external review, twenty-eighth pass, P1): the actual tests for
     * the floor-at-zero fix.
     */
    @Test
    @DisplayName("release(reservationId): the counter has enough to release -- a normal conditional decrement, never the clamp-to-zero fallback")
    void releaseById_counterHasEnough_normalDecrement() {
        var record = new com.tradevision.model.ExposureReservationRecord();
        record.setId("res1"); record.setCredentialId("cred1"); record.setSymbol("BTCUSDT");
        record.setTotalAmountReserved(BigDecimal.valueOf(500));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(com.tradevision.model.ExposureReservationRecord.class)))
            .thenReturn(record);
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(ExposureReservation.class)))
            .thenReturn(new ExposureReservation()); // the field genuinely had >= 500 -- normal path succeeds

        service.release("res1");

        // The clamp-to-zero fallback (a $set, not an $inc) must never fire here.
        verify(mongoTemplate, never()).updateFirst(any(Query.class),
            argThat(u -> u.getUpdateObject().containsKey("$set")), eq(ExposureReservation.class));
    }

    @Test
    @DisplayName("release(reservationId): the counter was already reset below the record's own amount (reconciliation ran while this record stayed stale ACTIVE) -- clamps to exactly zero instead of going negative")
    void releaseById_counterAlreadyBelowAmount_clampsToZeroInsteadOfNegative() {
        var record = new com.tradevision.model.ExposureReservationRecord();
        record.setId("res1"); record.setCredentialId("cred1"); record.setSymbol("BTCUSDT");
        record.setTotalAmountReserved(BigDecimal.valueOf(500));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(com.tradevision.model.ExposureReservationRecord.class)))
            .thenReturn(record);
        // The conditional decrement's own .gte(500) guard fails -- the real counter is already
        // below 500 (reconciliation reset it out from under this stale ACTIVE record).
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(ExposureReservation.class)))
            .thenReturn(null);

        service.release("res1");

        // The actual fix: falls through to an explicit $set to zero, never a further negative $inc.
        // reservedTotalExposureQuote is DECIMAL128-typed (see ExposureReservation model, and this
        // class's own toDecimal128 helper) -- the ad-hoc $set here must carry a real
        // org.bson.types.Decimal128, not a raw BigDecimal, or it would be stored as a String and
        // reproduce the exact "Cannot increment with non-numeric argument" production incident.
        verify(mongoTemplate).updateFirst(
            argThat(q -> !q.getQueryObject().toJson().contains("gte")),
            argThat(u -> {
                Object setObj = u.getUpdateObject().get("$set");
                return setObj instanceof org.bson.Document doc
                    && new org.bson.types.Decimal128(BigDecimal.ZERO).equals(doc.get("reservedTotalExposureQuote"));
            }),
            eq(ExposureReservation.class));
    }

    @Test
    @DisplayName("release(reservationId): a record already RELEASED (or a genuinely unknown id) is a real no-op -- never touches any counter")
    void releaseById_alreadyReleasedOrUnknown_noOp() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(com.tradevision.model.ExposureReservationRecord.class)))
            .thenReturn(null); // the atomic ACTIVE->RELEASED claim found nothing to claim

        service.release("res1");

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(ExposureReservation.class));
    }

    /**
     * Review finding ("The lastReservedAt grace-window design is still time-based safety" --
     * external review, twenty-eighth pass, P1, full context in reconcile()'s own updated
     * javadoc): the actual test for the new, durable signal.
     */
    @Test
    @DisplayName("reconcile: deletes stale PENDING records older than the dedicated cleanup window")
    void reconcile_deletesStalePendingRecords() {
        var stalePending = new com.tradevision.model.ExposureReservationRecord();
        stalePending.setId("stale1"); stalePending.setCredentialId("cred1"); stalePending.setStatus("PENDING");
        when(reservationRecordRepo.findByCredentialIdAndStatusAndCreatedAtBefore(eq("cred1"), eq("PENDING"), any()))
            .thenReturn(java.util.List.of(stalePending));

        service.reconcile("cred1", BigDecimal.ZERO, java.util.Map.of());

        verify(reservationRecordRepo).deleteById("stale1");
    }

    /**
     * Review finding ("v183 still has a dangerous 'PENDING reservation cleanup' window" --
     * external review, thirty-fifth/thirty-seventh passes, P0, the review's own explicit
     * required fix, full context in ExposureReservationRecord.executionId's own field
     * javadoc): the actual tests proving the fix.
     */
    @Test
    @DisplayName("reconcile: a stale PENDING record whose linked execution shows real progress (may have reached the exchange) is NOT deleted -- escalated instead, raising a critical incident naming the exact record and execution")
    void reconcile_stalePendingWithExecutionThatMayHaveReachedExchange_notDeletedEscalates() {
        var stalePending = new com.tradevision.model.ExposureReservationRecord();
        stalePending.setId("stale1"); stalePending.setCredentialId("cred1"); stalePending.setSymbol("BTCUSDT");
        stalePending.setStatus("PENDING"); stalePending.setExecutionId("exec1");
        when(reservationRecordRepo.findByCredentialIdAndStatusAndCreatedAtBefore(eq("cred1"), eq("PENDING"), any()))
            .thenReturn(java.util.List.of(stalePending));
        var execution = new com.tradevision.model.ExecutionContext();
        execution.setExecutionId("exec1"); execution.setStatus("ORDER_SUBMITTED"); // real progress -- may have reached the exchange
        when(executionContextRepo.findById("exec1")).thenReturn(java.util.Optional.of(execution));

        service.reconcile("cred1", BigDecimal.ZERO, java.util.Map.of());

        // The actual claim under test: never deleted, and a critical incident is raised instead.
        verify(reservationRecordRepo, never()).deleteById("stale1");
        verify(incidentService).raiseCritical(any(), eq("cred1"), any(), any(), eq("BTCUSDT"),
            eq("STALE_PENDING_RESERVATION_POSSIBLE_EXCHANGE_EXECUTION"), any());
    }

    @Test
    @DisplayName("reconcile: a stale PENDING record whose linked execution never progressed past risk approval (never reached the exchange) IS deleted -- genuinely safe, no incident raised")
    void reconcile_stalePendingWithExecutionThatNeverReachedExchange_deletedSafely() {
        var stalePending = new com.tradevision.model.ExposureReservationRecord();
        stalePending.setId("stale1"); stalePending.setCredentialId("cred1"); stalePending.setStatus("PENDING"); stalePending.setExecutionId("exec1");
        when(reservationRecordRepo.findByCredentialIdAndStatusAndCreatedAtBefore(eq("cred1"), eq("PENDING"), any()))
            .thenReturn(java.util.List.of(stalePending));
        var execution = new com.tradevision.model.ExecutionContext();
        execution.setExecutionId("exec1"); execution.setStatus("SLOTS_RESERVED"); // never reached the exchange
        when(executionContextRepo.findById("exec1")).thenReturn(java.util.Optional.of(execution));

        service.reconcile("cred1", BigDecimal.ZERO, java.util.Map.of());

        verify(reservationRecordRepo).deleteById("stale1");
        verify(incidentService, never()).raiseCritical(any(), any(), any(), any(), any(), eq("STALE_PENDING_RESERVATION_POSSIBLE_EXCHANGE_EXECUTION"), any());
    }

    @Test
    @DisplayName("reconcile: a stale PENDING record with an executionId, but the linked ExecutionContext itself is missing (its own insert failed) -- falls back to the prior time-based deletion, since no further evidence is available either way")
    void reconcile_stalePendingWithMissingExecutionContext_fallsBackToTimeBasedDeletion() {
        var stalePending = new com.tradevision.model.ExposureReservationRecord();
        stalePending.setId("stale1"); stalePending.setCredentialId("cred1"); stalePending.setStatus("PENDING"); stalePending.setExecutionId("exec1");
        when(reservationRecordRepo.findByCredentialIdAndStatusAndCreatedAtBefore(eq("cred1"), eq("PENDING"), any()))
            .thenReturn(java.util.List.of(stalePending));
        when(executionContextRepo.findById("exec1")).thenReturn(java.util.Optional.empty());

        service.reconcile("cred1", BigDecimal.ZERO, java.util.Map.of());

        verify(reservationRecordRepo).deleteById("stale1");
    }

    @Test
    @DisplayName("reconcile: a PENDING record still in flight (not yet stale) skips the counter overwrite entirely, even well outside the time-based grace window")
    void reconcile_pendingRecordInFlight_skipsOverwriteRegardlessOfGraceWindow() {
        // Deliberately NO recent lastReservedAt at all -- the OLD, time-based check alone would
        // have allowed the overwrite here. The new signal must still catch it.
        var stale = new ExposureReservation();
        stale.setCredentialId("cred1");
        stale.setLastReservedAt(java.time.Instant.now().minusSeconds(600));
        when(mongoTemplate.findOne(any(Query.class), eq(ExposureReservation.class))).thenReturn(stale);
        var inFlight = new com.tradevision.model.ExposureReservationRecord();
        inFlight.setId("pending1"); inFlight.setCredentialId("cred1"); inFlight.setStatus("PENDING");
        when(reservationRecordRepo.findByCredentialIdAndStatus("cred1", "PENDING")).thenReturn(java.util.List.of(inFlight));

        service.reconcile("cred1", BigDecimal.ZERO, java.util.Map.of());

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(ExposureReservation.class));
    }
}
