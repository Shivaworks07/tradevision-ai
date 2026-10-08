package com.tradevision.service;

import com.tradevision.model.ReconciliationLock;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Provides real inter-process mutual exclusion for reconciliation, backed by MongoDB's unique
 * {@code _id} constraint -- the same technique used by the admin bootstrap lock.
 *
 * <p>This is layered on top of, not a replacement for, {@code PositionMonitorService}'s
 * JVM-local {@code ReentrantLock} map: the local lock cheaply prevents two threads in the same
 * process from racing, while this class adds the cross-process guarantee needed once more than
 * one instance is running. The local lock avoids a database round-trip for same-process
 * contention, and this class is what makes scaling across replicas safe.
 *
 * <p>Ownership is tracked with a DB-generated monotonic fencing token ({@code generation}, see
 * {@link #tryAcquireWithDiagnosis}) rather than derived from a timestamp. Release is scoped to
 * {@code _id AND instanceId} so one holder can never drop another's lock, and {@link #renew}/
 * the generation-aware overload are atomic, expiry- and (optionally) generation-gated updates
 * that every long-running holder ({@code PositionMonitorService}, {@code PositionSafetyService},
 * {@code AutoTradeService}) calls repeatedly, aborting immediately on failure rather than
 * trusting a single lease to outlive the whole operation.
 *
 * <p>{@code expiresAt} comparisons are evaluated against each app instance's own local clock,
 * not a single Mongo-server-side clock, so meaningful clock skew between app nodes could let a
 * fast-clocked node treat another node's still-valid lease as already expired. This is accepted
 * because every lease duration below (30-90s) is comfortably larger than ordinary NTP-synced
 * drift between nodes in the same deployment; closing it fully (e.g. a Mongo-side {@code $expr}/
 * {@code $$NOW} comparison) would only trade app-node clock skew for app-vs-Mongo-server skew.
 * Operationally this requires NTP (or equivalent) time sync across app nodes; do not disable it.
 */
@Service
@RequiredArgsConstructor
public class DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);

    private final MongoTemplate mongoTemplate;

    /**
     * Pairs an acquisition outcome with the exact generation this call's own insert wrote, so a
     * caller never needs a separate follow-up query to learn its own generation -- which matters
     * because that follow-up query could otherwise read a different instance's generation if the
     * lock expired and was re-acquired in between.
     */
    public record LockLease(AcquireResult result, long generation) {
        public boolean acquired() { return result == AcquireResult.ACQUIRED; }
    }

    /**
     * Attempts to acquire the named lock for holdDuration. Returns true if this call won it,
     * false if another instance already holds a currently-unexpired lock with the same
     * credentialId. Never throws for the expected "someone else has it" case -- only a genuine
     * infrastructure failure (e.g. MongoDB unreachable) propagates, and even that is caught by
     * every caller and treated as "could not acquire", never as "acquired".
     */
    public boolean tryAcquire(String credentialId, String instanceId, java.time.Duration holdDuration) {
        return tryAcquireWithDiagnosis(credentialId, instanceId, holdDuration).acquired();
    }

    /**
     * Distinguishes "another instance genuinely holds this lock" (safe, expected) from "a real
     * infrastructure failure meant this couldn't even be determined" (e.g. MongoDB unreachable).
     * Safety-critical callers such as emergency flatten need this distinction so they can
     * escalate an infrastructure failure instead of silently assuming another instance is
     * handling the work. {@link #tryAcquire} remains a thin convenience wrapper over this,
     * collapsing {@code ACQUIRED} to {@code true} and everything else to {@code false} for
     * callers that don't need the distinction.
     */
    public enum AcquireResult { ACQUIRED, HELD_BY_OTHER, INFRASTRUCTURE_FAILURE }

    /**
     * Returns the next monotonic generation for this credential via an atomic {@code $inc}
     * against a dedicated, never-deleted counter document -- immune to clock skew, unlike a
     * timestamp-derived value. {@code upsert(true)} means the first acquisition for a credential
     * that has never had one simply creates the counter starting at 1, since Mongo's {@code $inc}
     * on a non-existent field starts from the increment amount itself.
     */
    private long nextGeneration(String credentialId) {
        var counter = mongoTemplate.findAndModify(
            Query.query(Criteria.where("_id").is(credentialId)),
            new org.springframework.data.mongodb.core.query.Update().inc("value", 1),
            org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true).upsert(true),
            com.tradevision.model.LockGenerationCounter.class);
        return counter.getValue();
    }

    public LockLease tryAcquireWithDiagnosis(String credentialId, String instanceId, java.time.Duration holdDuration) {
        try {
            mongoTemplate.remove(
                Query.query(Criteria.where("_id").is(credentialId).and("expiresAt").lt(Instant.now())),
                ReconciliationLock.class);
        } catch (Exception e) {
            log.warn("Could not sweep expired reconciliation lock for credential {} before acquire attempt (non-fatal, TTL index is the real backstop): {}", credentialId, e.getMessage());
        }

        try {
            // Obtained before the insert attempt, deliberately: even if the insert below fails
            // with HELD_BY_OTHER, a "wasted" counter increment here is harmless (the counter only
            // needs to keep moving forward, not account for exactly one increment per successful
            // acquisition), whereas obtaining it after a successful insert would let a second
            // instance's own nextGeneration() call land in between and hand out a generation to
            // the wrong acquisition.
            long generation = nextGeneration(credentialId);
            mongoTemplate.insert(new ReconciliationLock(credentialId, instanceId,
                Instant.now().plus(holdDuration.toMillis(), ChronoUnit.MILLIS), generation));
            // The exact generation this insert just wrote is returned directly, with nothing
            // re-read from the database.
            return new LockLease(AcquireResult.ACQUIRED, generation);
        } catch (DuplicateKeyException e) {
            return new LockLease(AcquireResult.HELD_BY_OTHER, -1); // another instance genuinely holds this lock right now — expected, not an error
        } catch (Exception e) {
            log.warn("Could not acquire reconciliation lock for credential {} -- a genuine infrastructure failure, not another instance "
                + "holding the lock: {}", credentialId, e.getMessage());
            return new LockLease(AcquireResult.INFRASTRUCTURE_FAILURE, -1);
        }
    }

    /**
     * Reads back the persisted generation for this credential's lock, for a caller that wants to
     * carry it through subsequent generation-aware renewal checks. Returns -1 if no lock document
     * exists for this credential at all; a missing lock must never be treated as generation 0,
     * since that could coincidentally collide with a different lock's real generation.
     */
    public long currentGeneration(String credentialId) {
        ReconciliationLock lock = mongoTemplate.findById(credentialId, ReconciliationLock.class);
        return lock == null ? -1 : lock.getGeneration();
    }

    /**
     * Releases the lock, but ONLY if it's still held by this exact instance -- if this
     * instance's own lock already expired and a different instance has since acquired a NEW one
     * for the same credential, this must not delete that other instance's genuine, active lock.
     */
    public void release(String credentialId, String instanceId) {
        try {
            mongoTemplate.remove(
                Query.query(Criteria.where("_id").is(credentialId).and("instanceId").is(instanceId)),
                ReconciliationLock.class);
        } catch (Exception e) {
            log.warn("Could not release reconciliation lock for credential {} (non-fatal -- the TTL index remains the real backstop against a permanently-stuck lock): {}", credentialId, e.getMessage());
        }
    }

    /**
     * Extends an in-progress lock's lease so a genuinely slow reconciliation pass (a slow
     * exchange response, a large number of positions, a GC pause) doesn't let its fixed lease
     * expire while still working -- which would otherwise let a second instance acquire the same
     * credential's lock and reconcile simultaneously, the exact double-processing this lock
     * exists to prevent. This renews the existing lease rather than threading a fencing token
     * through every downstream write the lock protects, which would be a substantially larger
     * change for the same correctness gain.
     *
     * <p>Like {@link #release}, this only succeeds if the lock is still owned by this instance:
     * an instance whose lease already expired (and was possibly reacquired by someone else) must
     * not resurrect or extend a lock it no longer legitimately owns. The atomic condition
     * requires {@code expiresAt > now} at the moment of the update, so a lock that has already
     * expired can only be renewed if nothing else has changed about it since -- and once the gap
     * since the last successful renewal exceeds the lock's own TTL, this returns false, the
     * correct signal that ownership was not provably continuous. The caller should treat a false
     * return exactly like a failed {@code tryAcquire}: stop its own work rather than continue
     * believing it still holds the lock.
     */
    public boolean renew(String credentialId, String instanceId, java.time.Duration extendBy) {
        try {
            var result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(credentialId).and("instanceId").is(instanceId).and("expiresAt").gt(Instant.now())),
                new org.springframework.data.mongodb.core.query.Update().set("expiresAt", Instant.now().plus(extendBy.toMillis(), ChronoUnit.MILLIS)),
                ReconciliationLock.class);
            // matchedCount, not modifiedCount, is the right signal for "do I still hold this
            // lock": modifiedCount is 0 whenever the new value equals the value already stored
            // (e.g. a same-millisecond renewal), which would misreport a still-held lock as lost.
            return result.getMatchedCount() > 0;
        } catch (Exception e) {
            log.warn("Could not renew reconciliation lock for credential {} (treated as lost, not renewed): {}", credentialId, e.getMessage());
            return false;
        }
    }

    /**
     * Generation-aware variant of {@link #renew} that also requires the given generation to
     * still be the current one, on top of the instanceId and un-expired checks. This is an
     * overload rather than a change to {@link #renew}'s signature, so existing callers that
     * don't track a generation are unaffected; callers that capture one at acquisition time (via
     * {@link #currentGeneration} right after acquiring) get the stronger guarantee by using this
     * overload instead.
     */
    public boolean renew(String credentialId, String instanceId, long generation, java.time.Duration extendBy) {
        try {
            var result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(credentialId).and("instanceId").is(instanceId)
                    .and("expiresAt").gt(Instant.now()).and("generation").is(generation)),
                new org.springframework.data.mongodb.core.query.Update().set("expiresAt", Instant.now().plus(extendBy.toMillis(), ChronoUnit.MILLIS)),
                ReconciliationLock.class);
            // matchedCount, not modifiedCount, is the right signal for "do I still hold this
            // lock" -- see the plain renew() overload above for why.
            return result.getMatchedCount() > 0;
        } catch (Exception e) {
            log.warn("Could not renew reconciliation lock for credential {} generation {} (treated as lost, not renewed): {}",
                credentialId, generation, e.getMessage());
            return false;
        }
    }
}
