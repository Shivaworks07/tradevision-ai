package com.tradevision.service;

import com.tradevision.model.ExecutionContext;
import com.tradevision.repository.ExecutionContextRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * User's own explicit architectural request, full context in ExecutionContext's own class
 * javadoc. The actual tests: every method's own best-effort guarantee (never throws, never
 * blocks the real caller regardless of what Mongo does), the real field-by-field recording at
 * each stage, and the deliberate positionId-based lookup for the two stages that happen outside
 * AutoTradeService.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutionContextServiceTest {

    @Mock MongoTemplate mongoTemplate;
    @Mock ExecutionContextRepository repo;
    @InjectMocks ExecutionContextService service;

    @Test
    @DisplayName("start: creates a real document with every given field, returns its new executionId")
    void start_createsRealDocumentReturnsId() {
        when(repo.insert(any(ExecutionContext.class))).thenAnswer(inv -> {
            ExecutionContext c = inv.getArgument(0);
            return c; // insert() already set executionId itself, before this mock is even reached
        });

        String executionId = service.start("sig1", "cred1", "user1", "plan1");

        assertThat(executionId).isNotNull().isNotBlank();
        var captor = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(repo).insert(captor.capture());
        assertThat(captor.getValue().getSignalId()).isEqualTo("sig1");
        assertThat(captor.getValue().getCredentialId()).isEqualTo("cred1");
        assertThat(captor.getValue().getUserId()).isEqualTo("user1");
        assertThat(captor.getValue().getPlanId()).isEqualTo("plan1");
        assertThat(captor.getValue().getStatus()).isEqualTo("STARTED");
    }

    @Test
    @DisplayName("start: the insert itself fails -- returns null, never throws")
    void start_insertFails_returnsNullNeverThrows() {
        when(repo.insert(any(ExecutionContext.class))).thenThrow(new RuntimeException("simulated database error"));

        String executionId = service.start("sig1", "cred1", "user1", "plan1");

        assertThat(executionId).isNull();
    }

    @Test
    @DisplayName("recordSlotsReserved: a null executionId (start() itself already failed) is a real no-op -- mongoTemplate is never touched")
    void recordSlotsReserved_nullExecutionId_noOp() {
        service.recordSlotsReserved(null, "slot1", "planSlot1");

        verify(mongoTemplate, never()).updateFirst(any(), any(), eq(ExecutionContext.class));
    }

    @Test
    @DisplayName("recordSlotsReserved: sets status and both reservation ids correctly")
    void recordSlotsReserved_setsCorrectFields() {
        service.recordSlotsReserved("exec1", "slot1", "planSlot1");

        var updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(ExecutionContext.class));
        var setDoc = (org.bson.Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertThat(setDoc.getString("status")).isEqualTo("SLOTS_RESERVED");
        assertThat(setDoc.getString("slotReservationId")).isEqualTo("slot1");
        assertThat(setDoc.getString("planSlotReservationId")).isEqualTo("planSlot1");
    }

    @Test
    @DisplayName("recordSlotsReserved: a null planSlotReservationId (no plan involved) is simply omitted, not set to null explicitly")
    void recordSlotsReserved_nullPlanSlot_omitted() {
        service.recordSlotsReserved("exec1", "slot1", null);

        var updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(ExecutionContext.class));
        var setDoc = (org.bson.Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertThat(setDoc.containsKey("planSlotReservationId")).isFalse();
    }

    @Test
    @DisplayName("recordTerminal: sets status to the specific reason category and terminalReason to the real detail")
    void recordTerminal_setsCorrectFields() {
        service.recordTerminal("exec1", "REJECTED_EXPOSURE_CAP", "Would exceed total exposure cap of 1000");

        var updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(ExecutionContext.class));
        var setDoc = (org.bson.Document) updateCaptor.getValue().getUpdateObject().get("$set");
        assertThat(setDoc.getString("status")).isEqualTo("REJECTED_EXPOSURE_CAP");
        assertThat(setDoc.getString("terminalReason")).isEqualTo("Would exceed total exposure cap of 1000");
    }

    @Test
    @DisplayName("every recording method: mongoTemplate.updateFirst throwing is swallowed -- never propagates to the caller, matching this class's own explicit best-effort guarantee")
    void everyMethod_mongoThrows_neverPropagates() {
        when(mongoTemplate.updateFirst(any(), any(), eq(ExecutionContext.class))).thenThrow(new RuntimeException("simulated database error"));

        // None of these should throw -- if any of them do, this test itself fails with that
        // exception, which is exactly the failure mode this class's own javadoc promises never happens.
        service.recordRiskApproved("exec1");
        service.recordSlotsReserved("exec1", "s1", "p1");
        service.recordExposureReserved("exec1", "e1");
        service.recordClaimed("exec1", "c1");
        service.recordOrderSubmitted("exec1", "co1");
        service.recordFilled("exec1", "pos1");
        service.recordProtected("exec1", "pa1", "oco1");
        service.recordClosed("exec1");
        service.recordTerminal("exec1", "FAILED", "some reason");
        service.recordProtectedByPositionId("pos1", "pa1", "oco1");
        service.recordClosedByPositionId("pos1");
    }

    @Test
    @DisplayName("recordProtectedByPositionId / recordClosedByPositionId: query by positionId, not executionId -- the deliberate cross-class lookup this design relies on")
    void positionIdBasedMethods_queryByPositionIdNotExecutionId() {
        service.recordProtectedByPositionId("pos1", "pa1", "oco1");

        var queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).updateFirst(queryCaptor.capture(), any(Update.class), eq(ExecutionContext.class));
        assertThat(queryCaptor.getValue().getQueryObject().toJson()).contains("positionId");
        assertThat(queryCaptor.getValue().getQueryObject().toJson()).doesNotContain("executionId");
    }

    @Test
    @DisplayName("recordProtectedByPositionId: a null positionId is a real no-op -- mongoTemplate is never touched")
    void recordProtectedByPositionId_nullPositionId_noOp() {
        service.recordProtectedByPositionId(null, "pa1", "oco1");

        verify(mongoTemplate, never()).updateFirst(any(), any(), eq(ExecutionContext.class));
    }
}
