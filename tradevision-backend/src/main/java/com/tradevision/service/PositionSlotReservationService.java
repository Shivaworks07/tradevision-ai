package com.tradevision.service;

import com.tradevision.model.PositionSlotReservation;
import com.tradevision.model.PositionSlotReservationRecord;
import org.springframework.dao.DuplicateKeyException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Enforces a per-credential cap on concurrently reserved position slots across multiple
 * application instances. AutoTradeService's own ConcurrentHashMap<ReentrantLock> is a fast,
 * zero-latency first line of defense within one JVM, but cannot coordinate across instances;
 * this service is the second line that also works across JVMs.
 *
 * MongoDB guarantees that operations on a SINGLE document are atomic, even under concurrent
 * writes from multiple application instances — the database itself serializes them. reserve()
 * is a single findAndModify that only succeeds if reservedCount is still under the cap,
 * evaluated and incremented as one atomic step, never as a separate read followed by a
 * separate write.
 *
 * reserve() creates a durable PositionSlotReservationRecord (PENDING, then ACTIVE once the
 * counter claim itself succeeds) and returns its id; release(reservationId) is the safe,
 * idempotent release path every caller should use. The bare release(key) overload remains for
 * the narrower cases (reconciliation, and any caller with no reservationId available).
 */
@Service
@RequiredArgsConstructor
public class PositionSlotReservationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PositionSlotReservationService.class);
    private final MongoTemplate mongoTemplate;
    /** Used to raise critical incidents when a stale PENDING reservation may still reflect real exchange activity, or when a LIVE reservation cannot be made transactionally safe. */
    private final IncidentService incidentService;
    /**
     * Used by reconcile()'s stale-PENDING cleanup to check whether a PENDING reservation's
     * linked execution shows real progress that may have reached the exchange, before deleting
     * the reservation record as stale.
     */
    private final com.tradevision.repository.ExecutionContextRepository executionContextRepo;
    private final com.tradevision.repository.PositionSlotReservationRecordRepository reservationRecordRepo;

    public record SlotReserveResult(boolean reserved, String reservationId) {
        public static SlotReserveResult reserved(String reservationId) { return new SlotReserveResult(true, reservationId); }
        public static SlotReserveResult rejected() { return new SlotReserveResult(false, null); }
    }

    /**
     * Reserves a slot for the given key. The reservation record is created FIRST, as PENDING,
     * before the counter is ever touched -- if that insert fails, the reservation is rejected
     * immediately with nothing claimed. Only once the counter claim itself succeeds is the
     * record marked ACTIVE.
     */
    public SlotReserveResult reserve(String key, int maxAllowed) {
        return reserve(key, maxAllowed, null, false);
    }

    /**
     * Same as reserve(key, maxAllowed), but stores the given executionId on the PENDING record
     * so reconcile() can check, before deleting a stale PENDING reservation, whether its
     * linked execution shows real progress. executionId may legitimately be null.
     */
    public SlotReserveResult reserve(String key, int maxAllowed, String executionId) {
        return reserve(key, maxAllowed, executionId, false);
    }

    /** Reserves a slot, applying a fail-closed policy for LIVE trading: if the reservation cannot be made transactionally safe, it is rejected rather than silently falling back to a weaker path. */
    public SlotReserveResult reserve(String key, int maxAllowed, String executionId, boolean live) {
        return reserveTransactionally(key, maxAllowed, executionId, live);
    }

    /**
     * Attempts the reservation inside a real MongoDB transaction first, falling back to the
     * prior sequential approach (which keeps its own complete, independent safety nets) only on
     * the specific, recognizable "this deployment doesn't support transactions" error.
     */
    /**
     * The maximum number of times a single reserve() call retries its own transaction after a
     * genuine MongoDB WriteConflict/TransientTransactionError. This is not an infrastructure
     * failure -- it's two transactions racing for the SAME per-credential counter document,
     * which MongoDB resolves by aborting the loser rather than queuing it. The retry count is
     * bounded so a pathological, permanently-overloaded credential can't spin forever; a tight
     * cap on reserved slots (almost always single digits) keeps genuine contention bounded too,
     * so a handful of retries gives every caller that can legally succeed a real chance to.
     */
    private static final int MAX_TRANSACTION_RETRIES = 10;

    private SlotReserveResult reserveTransactionally(String key, int maxAllowed, String executionId, boolean live) {
        com.mongodb.client.ClientSession session;
        try {
            session = mongoTemplate.getMongoDatabaseFactory().getSession(com.mongodb.ClientSessionOptions.builder().build());
        } catch (Exception e) {
            if (live) return rejectAndHaltForLive(key, "obtain a MongoDB ClientSession at all", e.getMessage());
            log.warn("Could not obtain a MongoDB ClientSession at all ({}) -- falling back to the sequential, non-transactional "
                + "reserve approach for key {}.", e.getMessage(), key);
            return reserveSequentially(key, maxAllowed, executionId);
        }
        try {
            // Every concurrent reserve() transaction does several writes (ensureDocumentExists,
            // insert the PENDING record, the findAndModify counter claim, the ACTIVE flip)
            // against the SAME single per-credential counter document. Under real concurrent
            // load, MongoDB resolves that document-level contention by aborting the loser's
            // transaction with a WriteConflict (error code 112), surfaced to the driver as a
            // MongoException carrying the "TransientTransactionError" label. This is distinct
            // from isStandaloneMongoTransactionError's own, much narrower signature below (error
            // code 20, "transactions require a replica set") -- it's a normal, expected,
            // RETRYABLE condition under genuine write contention on one document, exactly the
            // case MongoDB's own documented transaction-retry pattern exists for. Retrying the
            // WHOLE transaction body (on the same session, per MongoDB's own documented pattern
            // -- a ClientSession remains valid across a startTransaction()/abortTransaction()
            // cycle) lets every caller that can legally fit under the cap actually get the
            // chance to, instead of losing the race permanently on its first and only attempt.
            for (int attempt = 1; ; attempt++) {
                try {
                    session.startTransaction();
                    var sessionTemplate = mongoTemplate.withSession(session);
                    // Called INSIDE the transaction, on the session-bound template -- the
                    // create-if-missing step and the reservation claim below are part of the
                    // same transactional snapshot, so a fresh credential's counter document is
                    // always visible to the claim that follows (see ensureDocumentExists).
                    ensureDocumentExists(key, sessionTemplate);

                    var record = new PositionSlotReservationRecord();
                    record.setKey(key);
                    record.setStatus("PENDING");
                    record.setExecutionId(executionId);
                    record = sessionTemplate.insert(record);

                    Query query = new Query(where("credentialId").is(key).and("reservedCount").lt(maxAllowed));
                    Update update = new Update().inc("reservedCount", 1).set("lastReservedAt", java.time.Instant.now());
                    PositionSlotReservation result = sessionTemplate.findAndModify(
                        query, update, FindAndModifyOptions.options().returnNew(true), PositionSlotReservation.class);
                    if (result == null) {
                        session.abortTransaction();
                        return SlotReserveResult.rejected();
                    }

                    // No separate activation crash window at all on this path -- see
                    // ExposureReservationService.reserveTransactionally's own identical comment for why.
                    sessionTemplate.updateFirst(
                        new Query(where("id").is(record.getId())),
                        new Update().set("status", "ACTIVE"),
                        PositionSlotReservationRecord.class);

                    session.commitTransaction();
                    return SlotReserveResult.reserved(record.getId());
                } catch (RuntimeException e) {
                    try {
                        if (session.hasActiveTransaction()) session.abortTransaction();
                    } catch (Exception ignore) {
                        // Best-effort cleanup only -- the transaction attempt already failed.
                    }
                    if (isTransientTransactionError(e) && attempt < MAX_TRANSACTION_RETRIES) {
                        log.debug("Transient MongoDB transaction error on reserve() attempt {} for key {} ({}) -- retrying.",
                            attempt, key, e.getMessage());
                        continue;
                    }
                    // Catches RuntimeException broadly and walks the full cause chain below,
                    // since Spring Data's MongoTemplate wraps the raw driver exception in its
                    // own DataAccessException hierarchy -- catching com.mongodb.MongoException
                    // directly would miss it.
                    if (isStandaloneMongoTransactionError(e)) {
                        if (live) return rejectAndHaltForLive(key, "run the reservation inside a real MongoDB transaction (standalone "
                            + "deployment, not a replica set/mongos)", e.getMessage());
                        log.warn("MongoDB transactions are not supported by this deployment (standalone, not a replica set/mongos) -- falling "
                            + "back to the sequential, non-transactional reserve approach for key {}. Configure a MongoDB replica set to close "
                            + "the crash window fully.", key);
                        return reserveSequentially(key, maxAllowed, executionId);
                    }
                    throw e;
                }
            }
        } finally {
            session.close();
        }
    }

    /**
     * Rejects a LIVE reservation and halts trading rather than falling back to a weaker,
     * non-transactional path. key is either a real credentialId (the account-level reserve()
     * call) or a "plan:<id>" scoped key (the per-plan call) -- the tradingHalted update only
     * applies for a real credentialId, since a plan-scoped key has no single matching
     * RiskProfile document to halt; the critical incident is raised either way, since that
     * doesn't depend on a RiskProfile match.
     */
    private SlotReserveResult rejectAndHaltForLive(String key, String failedTo, String detail) {
        log.error("LIVE slot reservation for key {} could not {}: {}. Per this deployment's own fail-closed LIVE policy, NOT "
            + "falling back to the non-transactional path -- rejecting this reservation{}.",
            key, failedTo, detail, key.startsWith("plan:") ? "" : " and halting autonomous trading for this credential");
        if (!key.startsWith("plan:")) {
            mongoTemplate.updateFirst(
                new Query(where("credentialId").is(key)),
                new Update().set("tradingHalted", true).set("haltReason", "LIVE slot reservation could not " + failedTo + ": " + detail),
                com.tradevision.model.RiskProfile.class);
        }
        incidentService.raiseCritical(null, key.startsWith("plan:") ? null : key, null, null, null, "LIVE_RESERVATION_TRANSACTION_UNAVAILABLE",
            "A LIVE-mode slot reservation for key " + key + " could not " + failedTo + ": " + detail + ". "
                + (key.startsWith("plan:")
                    ? "This is a plan-scoped reservation -- trading was not automatically halted for a specific credential, but this "
                        + "is still a genuine MongoDB transaction infrastructure problem requiring manual investigation."
                    : "Trading has been automatically halted for this credential rather than falling back to a non-transactional "
                        + "reservation path, per this deployment's own fail-closed LIVE policy.")
                + " Manual investigation of MongoDB transaction support/session availability is required.");
        return SlotReserveResult.rejected();
    }

    private SlotReserveResult reserveSequentially(String key, int maxAllowed, String executionId) {
        ensureDocumentExists(key, mongoTemplate);

        var record = new PositionSlotReservationRecord();
        record.setKey(key);
        record.setStatus("PENDING");
        record.setExecutionId(executionId);
        try {
            record = reservationRecordRepo.insert(record);
        } catch (Exception e) {
            log.warn("Could not create the PENDING slot reservation record for key {} -- refusing to touch the counter without a "
                + "durable record to track it: {}", key, e.getMessage());
            return SlotReserveResult.rejected();
        }

        Query query = new Query(where("credentialId").is(key).and("reservedCount").lt(maxAllowed));
        Update update = new Update().inc("reservedCount", 1).set("lastReservedAt", java.time.Instant.now());
        PositionSlotReservation result = mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(true), PositionSlotReservation.class);
        if (result == null) {
            deletePendingRecord(record.getId());
            return SlotReserveResult.rejected();
        }

        // A failed PENDING-to-ACTIVE transition here means the counter above was already
        // durably incremented, but the record that could prove why is gone. Logged loudly
        // (and escalated below) rather than silently swallowed.
        var activated = mongoTemplate.findAndModify(
            new Query(where("id").is(record.getId()).and("status").is("PENDING")),
            new Update().set("status", "ACTIVE"),
            PositionSlotReservationRecord.class);
        if (activated == null) {
            log.error("Slot reservation {} for key {} claimed real counter capacity but its own final PENDING-to-ACTIVE transition "
                + "matched zero documents -- the record was likely deleted by a concurrent process between being created and this point.",
                record.getId(), key);
            incidentService.raiseCritical(null, key, null, null, null, "SLOT_RESERVATION_ORPHANED_AT_ACTIVATION",
                "Slot reservation " + record.getId() + " for key " + key + " claimed real aggregate slot-counter capacity, but its own "
                    + "final PENDING-to-ACTIVE database transition matched zero documents -- almost certainly because the record was "
                    + "deleted by a concurrent stale-PENDING cleanup pass racing this exact reserve() call. The counter remains durably "
                    + "incremented with no ACTIVE reservation record left to explain or eventually release it. Manual investigation "
                    + "required: verify whether a real position was actually created for this reservation, and reconcile the slot "
                    + "counter against real exchange/position state if not.");
        }
        return SlotReserveResult.reserved(record.getId());
    }

    /**
     * Links a reservation to the position it ultimately created, so reconcile() can count
     * ACTIVE reservations not yet linked to a position as real committed slots. Best-effort
     * like every other write in this class.
     */
    public void linkToPosition(String reservationId, String positionId) {
        if (reservationId == null || positionId == null) return;
        try {
            mongoTemplate.updateFirst(
                new Query(where("id").is(reservationId)),
                new Update().set("positionId", positionId),
                PositionSlotReservationRecord.class);
        } catch (Exception e) {
            log.warn("Could not link slot reservation {} to position {} (non-fatal, purely observational): {}", reservationId, positionId, e.getMessage());
        }
    }

    /**
     * Releases a reservation by id. Atomically claims the record (ACTIVE -> RELEASED) BEFORE
     * decrementing the counter, so a double-release is a no-op rather than a double-decrement.
     * This is the method callers should prefer; see releaseByKey below for the narrower cases
     * where no reservationId is available.
     */
    public void release(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) return;
        var claimed = mongoTemplate.findAndModify(
            new Query(where("id").is(reservationId).and("status").is("ACTIVE")),
            new Update().set("status", "RELEASED").set("releasedAt", java.time.Instant.now()),
            PositionSlotReservationRecord.class);
        if (claimed == null) return; // already released, or never existed — safe either way

        // Floor at zero — same reasoning as ExposureReservationService.releaseFloored: a stale
        // ACTIVE record released after reconcile() has already reset the real counter must
        // never drive it negative.
        var decremented = mongoTemplate.findAndModify(
            new Query(where("credentialId").is(claimed.getKey()).and("reservedCount").gt(0)),
            new Update().inc("reservedCount", -1),
            FindAndModifyOptions.options(), PositionSlotReservation.class);
        if (decremented == null) {
            mongoTemplate.updateFirst(
                new Query(where("credentialId").is(claimed.getKey())),
                new Update().set("reservedCount", 0),
                PositionSlotReservation.class);
        }
    }

    private void deletePendingRecord(String recordId) {
        try {
            reservationRecordRepo.deleteById(recordId);
        } catch (Exception e) {
            log.warn("Could not delete the now-unneeded PENDING slot reservation record {} (non-fatal -- reconciliation's own PENDING "
                + "recovery path will resolve it eventually): {}", recordId, e.getMessage());
        }
    }

    /**
     * The narrower, key-based release, kept for callers that have no reservationId to work
     * with. Always safe to call, including when nothing was actually reserved — floors at 0,
     * never goes negative. Named distinctly from release(String reservationId) above since
     * both take a single String and so cannot be overloads of the same method name.
     */
    public void releaseByKey(String key) {
        Query query = new Query(where("credentialId").is(key).and("reservedCount").gt(0));
        Update update = new Update().inc("reservedCount", -1);
        mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options(), PositionSlotReservation.class);
    }

    /**
     * How long a reservation's lastReservedAt keeps reconcile() from overwriting the counter,
     * giving a just-placed order real room to either complete (Position saved, correctly
     * counted next cycle) or fail (existing release() calls handle that) before the counter
     * is forcibly resynced. Set well above the 60s scheduled reconciliation interval.
     */
    private static final java.time.Duration GRACE_WINDOW = java.time.Duration.ofSeconds(120);
    /**
     * How long a PENDING reservation record can sit unresolved before it's treated as
     * abandoned (its reserve() call likely crashed before completing) and eligible for cleanup.
     */
    private static final java.time.Duration PENDING_CLEANUP_WINDOW = java.time.Duration.ofMinutes(10);

    /**
     * Self-healing resync of the reserved-slot counter against reality. reserve()/release()
     * are best-effort increments/decrements, not derived from a query — if any release call is
     * ever missed (an exception between reserve and the eventual close, a bug, anything), the
     * counter could drift permanently away from reality and either wrongly block trading
     * forever or wrongly allow over the real cap. Called periodically by PositionMonitorService
     * with the actual count of OPEN Position documents for the credential, which is the real
     * source of truth — this counter is a fast-path cache of that, not an independent ledger.
     *
     * Skips overwriting the counter entirely when a reservation happened recently enough that
     * the corresponding order/Position may still be in flight (see GRACE_WINDOW): without that
     * check, a reserve() landing between an order being placed and its Position being saved
     * could be overwritten by this method reading "0 OPEN positions" and blowing the counter
     * back down mid-flight, undoing the exact protection the reservation exists to provide,
     * right when it's needed.
     */
    public void reconcile(String credentialId, int actualOpenPositionCount) {
        ensureDocumentExists(credentialId, mongoTemplate);

        // Recovers stale PENDING reservation records whose reserve() call likely crashed
        // before completing.
        var staleCutoff = java.time.Instant.now().minus(PENDING_CLEANUP_WINDOW);
        for (var pending : reservationRecordRepo.findByKeyAndStatusAndCreatedAtBefore(credentialId, "PENDING", staleCutoff)) {
            // Requires real proof that the linked execution hasn't reached the exchange
            // before deleting a stale PENDING record -- see the check below.
            boolean safeToDelete = true;
            if (pending.getExecutionId() != null) {
                var context = executionContextRepo.findById(pending.getExecutionId()).orElse(null);
                if (context != null) {
                    String status = context.getStatus();
                    boolean mayHaveReachedExchange = status != null && !status.equals("STARTED") && !status.equals("RISK_APPROVED")
                        && !status.startsWith("REJECTED_") && !status.equals("SLOTS_RESERVED") && !status.equals("EXPOSURE_RESERVED");
                    if (mayHaveReachedExchange) {
                        safeToDelete = false;
                        log.error("Refusing to delete stale PENDING slot reservation {} for key {} -- its own linked execution {} "
                            + "shows real progress ({}) that may have reached the exchange. Escalating for manual investigation "
                            + "instead of silently deleting a record that could be the only trace of a real position.",
                            pending.getId(), credentialId, pending.getExecutionId(), status);
                        incidentService.raiseCritical(null, credentialId, null, null, null,
                            "STALE_PENDING_RESERVATION_POSSIBLE_EXCHANGE_EXECUTION",
                            "A PENDING slot reservation (" + pending.getId() + ", key " + credentialId + ") has been stale past the "
                                + "cleanup window, but its own linked execution (" + pending.getExecutionId() + ") shows real "
                                + "progress (" + status + ") that may have reached the exchange. NOT deleted -- deleting it would "
                                + "erase the only durable link between this reserved slot and a possibly-real position. Manual "
                                + "investigation required: check this execution's own position/order state directly against the exchange.");
                    }
                }
            }
            if (safeToDelete) {
                log.warn("Deleting stale PENDING slot reservation record {} for key {} (created {}, older than the {}-second grace "
                    + "window with no resolution) -- the reserve() call that created it appears to have crashed before completing.",
                    pending.getId(), credentialId, pending.getCreatedAt(), PENDING_CLEANUP_WINDOW.toSeconds());
                deletePendingRecord(pending.getId());
            }
        }

        // Any PENDING record still there means a reserve() call for this exact key is
        // provably in progress right now -- a durable signal, not one guessed from elapsed time.
        boolean pendingReservationInFlight = !reservationRecordRepo
            .findByKeyAndStatus(credentialId, "PENDING")
            .isEmpty();
        if (pendingReservationInFlight) {
            return; // a reserve() call for this key is provably still in progress right now
        }

        PositionSlotReservation existing = mongoTemplate.findOne(
            new Query(where("credentialId").is(credentialId)), PositionSlotReservation.class);
        if (existing != null && existing.getLastReservedAt() != null
                && existing.getLastReservedAt().isAfter(java.time.Instant.now().minus(GRACE_WINDOW))) {
            return; // a reservation happened recently enough that it may still be in flight — don't touch the counter yet
        }

        // Counts ACTIVE reservations not yet linked to a position as real committed slots,
        // since the position-derived actualOpenPositionCount alone cannot see them.
        long unlinkedActiveCount = reservationRecordRepo.findByKeyAndStatusAndPositionIdIsNull(credentialId, "ACTIVE").size();
        int correctedCount = actualOpenPositionCount + (int) unlinkedActiveCount;
        if (unlinkedActiveCount > 0) {
            log.info("reconcile() for key {}: including {} ACTIVE reservation(s) not yet linked to a position -- these represent "
                + "real, committed slots the position-derived count alone cannot see yet.", credentialId, unlinkedActiveCount);
        }

        mongoTemplate.updateFirst(
            new Query(where("credentialId").is(credentialId)),
            new Update().set("reservedCount", correctedCount),
            PositionSlotReservation.class);
    }

    /**
     * Recognizes the specific, narrow signature of "this MongoDB deployment cannot run
     * transactions at all" (a standalone instance, not a replica set/mongos) -- as opposed to
     * isTransientTransactionError below, which recognizes an ordinary, retryable write
     * conflict between transactions that both work fine. Walks the full cause chain since the
     * raw driver exception may be wrapped along the way.
     */
    private boolean isStandaloneMongoTransactionError(Throwable e) {
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof com.mongodb.MongoException mongoEx && mongoEx.getCode() == 20) return true;
            if (current.getMessage() != null && current.getMessage().contains("Transaction numbers are only allowed")) return true;
            current = current.getCause();
            depth++;
        }
        return false;
    }

    /**
     * Recognizes a genuine, expected, RETRYABLE write-conflict under real concurrent
     * transactions against the same document -- distinct from isStandaloneMongoTransactionError
     * above, which recognizes the opposite case (this deployment can't run transactions at all,
     * never retryable). Checks the official MongoDB driver "TransientTransactionError" error
     * label first (the documented, forward-compatible way to detect this -- covers
     * WriteConflict, NoSuchTransaction after a stepdown, and other transient conditions the
     * driver already classifies), then falls back to the raw WriteConflict error code (112)
     * directly in case a wrapped/translated exception lost the label along the way.
     */
    private boolean isTransientTransactionError(Throwable e) {
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof com.mongodb.MongoException mongoEx) {
                if (mongoEx.hasErrorLabel("TransientTransactionError")) return true;
                if (mongoEx.getCode() == 112) return true; // WriteConflict
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    /**
     * Best-effort creation of the per-credential counter document. A tiny window exists where
     * two instances could both attempt creation simultaneously — MongoDB's unique index on
     * credentialId (see the model) turns the loser into a DuplicateKeyException, which is
     * caught and ignored here: the document exists either way, which is all this method
     * promises.
     *
     * Takes the MongoOperations to use as a parameter so reserveTransactionally can pass its
     * own session-bound instance: on a brand-new credential, if this insert ran on a separate,
     * non-transactional connection, MongoDB's transaction snapshot for the findAndModify that
     * follows could fail to see a write that happened moments earlier on a different session,
     * wrongly rejecting a reservation with zero real competition. Running this inside the same
     * transaction keeps the create-if-missing step and the reservation claim in one
     * transactional snapshot. reserveSequentially's call still passes the plain mongoTemplate.
     */
    private void ensureDocumentExists(String key, org.springframework.data.mongodb.core.MongoOperations ops) {
        Query query = new Query(where("credentialId").is(key));
        if (ops.exists(query, PositionSlotReservation.class)) return;
        try {
            PositionSlotReservation doc = new PositionSlotReservation();
            doc.setCredentialId(key);
            doc.setReservedCount(0);
            ops.insert(doc);
        } catch (DuplicateKeyException e) {
            // Another instance created it first between our exists() check and this insert — fine, it exists now.
        }
    }
}
