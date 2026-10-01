package com.tradevision.service;

import com.tradevision.model.ExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * User's own explicit architectural request -- full context in ExecutionContext's own class
 * javadoc, including the deliberate design decision that this is a traceability layer, not a
 * replacement for the underlying reservation/claim primitives.
 *
 * Every method here is deliberately best-effort: wrapped in its own try/catch, logging a warning
 * on failure but NEVER throwing or otherwise propagating a failure back to the caller. This
 * document exists purely to make an execution's lifecycle traceable after the fact -- a failure
 * to record one stage transition (a transient Mongo hiccup, for instance) must never be allowed
 * to block, fail, or roll back the real trade this method is merely observing. The real safety
 * guarantees remain exactly where this session already built them: the atomic reservation/claim
 * records themselves, not this service.
 */
@Service
@RequiredArgsConstructor
public class ExecutionContextService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExecutionContextService.class);
    private final MongoTemplate mongoTemplate;
    private final com.tradevision.repository.ExecutionContextRepository repo;

    /** Creates the document and returns its new executionId. If even this initial insert fails,
     *  returns null -- every other method here already tolerates a null executionId as a no-op,
     *  so a caller can pass it straight through without its own null check at every call site. */
    public String start(String signalId, String credentialId, String userId, String planId) {
        try {
            var context = new ExecutionContext();
            context.setExecutionId(java.util.UUID.randomUUID().toString());
            context.setSignalId(signalId);
            context.setCredentialId(credentialId);
            context.setUserId(userId);
            context.setPlanId(planId);
            context.setStatus("STARTED");
            return repo.insert(context).getExecutionId();
        } catch (Exception e) {
            log.warn("Could not create ExecutionContext for signal {} (non-fatal, purely observational -- the real evaluation "
                + "proceeds regardless): {}", signalId, e.getMessage());
            return null;
        }
    }

    public void recordRiskApproved(String executionId) {
        setFields(executionId, new Update().set("status", "RISK_APPROVED"));
    }

    public void recordSlotsReserved(String executionId, String slotReservationId, String planSlotReservationId) {
        var update = new Update().set("status", "SLOTS_RESERVED").set("slotReservationId", slotReservationId);
        if (planSlotReservationId != null) update.set("planSlotReservationId", planSlotReservationId);
        setFields(executionId, update);
    }

    public void recordExposureReserved(String executionId, String exposureReservationId) {
        setFields(executionId, new Update().set("status", "EXPOSURE_RESERVED").set("exposureReservationId", exposureReservationId));
    }

    public void recordClaimed(String executionId, String executionClaimId) {
        setFields(executionId, new Update().set("status", "CLAIMED").set("executionClaimId", executionClaimId));
    }

    public void recordOrderSubmitted(String executionId, String clientOrderId) {
        setFields(executionId, new Update().set("status", "ORDER_SUBMITTED").set("clientOrderId", clientOrderId));
    }

    public void recordFilled(String executionId, String positionId) {
        setFields(executionId, new Update().set("status", "FILLED").set("positionId", positionId));
    }

    public void recordProtected(String executionId, String protectionAttemptId, String ocoOrderListId) {
        var update = new Update().set("status", "PROTECTED");
        if (protectionAttemptId != null) update.set("protectionAttemptId", protectionAttemptId);
        if (ocoOrderListId != null) update.set("ocoOrderListId", ocoOrderListId);
        setFields(executionId, update);
    }

    public void recordClosed(String executionId) {
        setFields(executionId, new Update().set("status", "CLOSED"));
    }

    /**
     * User's own explicit architectural request, full context in ExecutionContext's own class
     * javadoc: the protection (OCO) and close stages happen in PositionMonitorService and
     * PositionSafetyService -- entirely different classes from AutoTradeService, where this
     * document's own executionId was created. Threading executionId through as a new parameter
     * on every existing method signature in both of those classes would be a far more invasive,
     * higher-risk change than looking the context up by positionId instead -- a real, already-
     * indexed field this document has carried since recordFilled was first called for it.
     * Best-effort like every other method here: if no context is found (a position that predates
     * this feature, or the context itself failed to record earlier), this is a silent no-op.
     */
    public void recordProtectedByPositionId(String positionId, String protectionAttemptId, String ocoOrderListId) {
        var update = new Update().set("status", "PROTECTED");
        if (protectionAttemptId != null) update.set("protectionAttemptId", protectionAttemptId);
        if (ocoOrderListId != null) update.set("ocoOrderListId", ocoOrderListId);
        setFieldsByPositionId(positionId, update);
    }

    public void recordClosedByPositionId(String positionId) {
        setFieldsByPositionId(positionId, new Update().set("status", "CLOSED"));
    }

    private void setFieldsByPositionId(String positionId, Update update) {
        if (positionId == null) return;
        try {
            update.set("updatedAt", LocalDateTime.now());
            mongoTemplate.updateFirst(
                new Query(where("positionId").is(positionId)),
                update,
                ExecutionContext.class);
        } catch (Exception e) {
            log.warn("Could not update ExecutionContext for position {} (non-fatal, purely observational): {}", positionId, e.getMessage());
        }
    }

    /** For a signal that never reached execution at all (a risk rejection, a cap reached, an
     *  order the exchange rejected outright) -- status is the specific reason category
     *  (REJECTED_RISK, REJECTED_SLOT_CAP, REJECTED_EXPOSURE_CAP, REJECTED_BROKER, FAILED, etc.),
     *  reason is the actual, human-readable detail. */
    public void recordTerminal(String executionId, String status, String reason) {
        setFields(executionId, new Update().set("status", status).set("terminalReason", reason));
    }

    private void setFields(String executionId, Update update) {
        if (executionId == null) return; // start() itself already failed -- nothing to update
        try {
            update.set("updatedAt", LocalDateTime.now());
            mongoTemplate.updateFirst(
                new Query(where("executionId").is(executionId)),
                update,
                ExecutionContext.class);
        } catch (Exception e) {
            log.warn("Could not update ExecutionContext {} (non-fatal, purely observational): {}", executionId, e.getMessage());
        }
    }
}
