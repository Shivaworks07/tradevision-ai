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
 * Review finding ("Single-instance assumption for autonomous trading/reconciliation -- no
 * distributed lock, unsafe to scale replicas" -- P0, full context in ReconciliationLock's own
 * javadoc): real inter-process mutual exclusion for reconciliation, backed by MongoDB's own
 * unique _id constraint -- the same proven technique as AdminController's own bootstrap lock.
 *
 * Deliberately does NOT replace PositionMonitorService's existing JVM-local ReentrantLock map --
 * that still correctly prevents two THREADS in the same process from racing (a cheap, fast,
 * uncontended check in the overwhelmingly common single-instance-today deployment), while this
 * adds the missing cross-process guarantee on top for whenever this scales beyond replicas: 1.
 * Layering both is deliberate: the local lock avoids paying a database round-trip for
 * same-process contention, and this class is what actually makes scaling safe.
 */
@Service
@RequiredArgsConstructor
public class DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);

    private final MongoTemplate mongoTemplate;

    /**
     * Review finding ("DistributedLockService has a subtle generation race" -- external review,
     * twenty-ninth pass, P1, confirmed real by direct inspection before this fix:
     * tryAcquireWithDiagnosis already computes its own generation via nextGeneration() before
     * inserting the lock -- the exact value is known right there -- but the old return type
     * (AcquireResult, a bare enum) couldn't carry it, forcing every caller into a SEPARATE
     * currentGeneration() query afterward. That query reads whatever generation is CURRENTLY
     * persisted for this credential, not necessarily the one THIS call's own insert just wrote --
     * if the lock had already expired and been re-acquired by another instance in the window
     * between this call's own insert and its currentGeneration() read, the old caller would
     * silently receive the NEW instance's own generation instead of its own): the actual fix --
     * the generation this call's own insert just wrote, carried directly on the return value,
     * with no separate query and no window for it to have changed underneath the caller.
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
     * Review finding ("Emergency flatten lock failure is ambiguous" -- external review,
     * confirmed real by direct inspection before any fix was attempted): the plain boolean
     * tryAcquire above collapses two genuinely different outcomes into the same `false` --
     * "another instance genuinely holds this lock right now" (safe, expected, exactly what a
     * lock is for) and "a real infrastructure failure meant this couldn't even be determined"
     * (e.g. MongoDB itself unreachable). PositionSafetyService.emergencyFlatten's own callers
     * used to treat both identically -- silently returning as if another instance were already
     * handling the flatten, when an infrastructure failure actually means NO instance is
     * flattening this naked position at all. This richer result lets that specific,
     * safety-critical caller distinguish the two and escalate the infrastructure-failure case
     * instead of silently doing nothing. The plain tryAcquire() above is kept as a thin
     * convenience wrapper collapsing ACQUIRED to true and everything else to false, preserving
     * this method's own exact prior behavior for PositionMonitorService's own reconciliation
     * lock, which doesn't need this distinction with the same urgency.
     */
    public enum AcquireResult { ACQUIRED, HELD_BY_OTHER, INFRASTRUCTURE_FAILURE }

    /**
     * Review finding ("ReconciliationLock.generation() is not actually a monotonic generation"
     * -- external review, third pass, full context in LockGenerationCounter's own javadoc): a
     * genuine atomic $inc against a dedicated, never-deleted counter document for this
     * credential -- immune to wall-clock behavior entirely, unlike the timestamp this replaces.
     * upsert(true) means the very first acquisition for a credential that's never had one
     * creates the counter starting from 1 (Mongo's own $inc on a non-existent field starts from
     * the increment amount itself), not a separate explicit "create if missing" branch.
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
            // Review finding ("ReconciliationLock.generation() is not actually a monotonic
            // generation" -- external review, third pass): obtained BEFORE the insert attempt,
            // deliberately -- even if the insert below then fails with HELD_BY_OTHER (another
            // instance genuinely won the race), a "wasted" counter increment here is completely
            // harmless (the counter only needs to keep moving forward, not account for exactly
            // one increment per successful acquisition), and obtaining it after a successful
            // insert would reopen a real gap: a second instance's own nextGeneration() call
            // landing in between this instance's insert and its own generation fetch could hand
            // out a generation to the WRONG acquisition.
            long generation = nextGeneration(credentialId);
            mongoTemplate.insert(new ReconciliationLock(credentialId, instanceId,
                Instant.now().plus(holdDuration.toMillis(), ChronoUnit.MILLIS), generation));
            // Review finding ("DistributedLockService has a subtle generation race" -- external
            // review, twenty-ninth pass, P1, full context in LockLease's own javadoc): this
            // exact generation value -- the one this call's own insert just wrote, nothing
            // re-read from the database -- is what the caller actually gets back now.
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
     * Review finding ("DistributedLockService.renew() does not verify ownership generation" --
     * external review, second pass, full context in ReconciliationLock.generation's own updated
     * javadoc): reads back this lock's own real, persisted generation immediately after a
     * successful acquisition, for a caller that wants to carry it through subsequent renewal
     * checks. A separate query rather than changing tryAcquire/tryAcquireWithDiagnosis's own
     * existing return types -- both already have real, established callers this session
     * deliberately didn't want to force through a breaking signature change for this addition.
     * Returns -1 if no lock document exists for this credential at all (should not happen
     * immediately after a successful acquire, but a caller must not silently treat a missing
     * lock as generation 0 -- that could coincidentally collide with a real, different lock's
     * own generation under clock skew across processes).
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
     * Review finding ("Distributed reconciliation lock has a fixed 90-second lease with no
     * renewal" -- P1): confirmed real -- a genuinely slow reconciliation pass (a slow Binance
     * response, a large number of positions, GC pause) could exceed the original fixed lease
     * duration entirely, at which point a SECOND instance could acquire what it believes is a
     * fresh lock on the same credential while the first instance is still actively working —
     * both reconciling simultaneously, the exact double-processing this lock exists to prevent.
     *
     * A renewable lease, not a fencing-token scheme -- the review itself offers both as
     * options ("renewable lease, heartbeat/lock extension, or lock duration based on worst-case
     * bounded operation time... also use fencing tokens if strict correctness is required").
     * Fencing tokens would mean threading a token through every single downstream write this
     * lock protects (every position/order mutation across PositionMonitorService's own
     * reconciliation pass) so each one can itself reject a stale token — a substantially larger,
     * more invasive change than extending a lease. A renewable lease closes the actual gap named
     * (a lease that can silently expire mid-operation) without that larger surface area.
     *
     * Same "conditional on still being owned by this instance" safety as release() above -- an
     * instance whose lease already expired (and was possibly reacquired by someone else) must
     * not resurrect or extend a lock it no longer legitimately owns. Returns false (not an
     * exception) if the renewal loses that race, exactly like tryAcquire's own "not acquired"
     * contract -- the caller should treat this the same way as never having held the lock,
     * i.e. stop its own work rather than continue believing it's still exclusive.
     */
    /**
     * Review finding ("Distributed lock renewal can resurrect an expired lock" -- external
     * review, confirmed real by direct inspection before any fix was attempted): this query used
     * to check only _id and instanceId, never expiresAt itself. If the lock had already expired
     * but no other instance had yet called tryAcquire() to sweep and replace the stale document
     * (tryAcquire's own expired-lock removal only runs when someone ELSE attempts to acquire --
     * nothing proactively sweeps a lock the original owner is still quietly calling renew()
     * on), the original owner could successfully "renew" a lock that had already lapsed --
     * extending its own expiry back into the future without ever having proven it was still the
     * continuous, legitimate owner through the gap. Now requires expiresAt > now as part of the
     * SAME atomic condition, so a genuinely expired lock can only be renewed if nothing else has
     * changed about it since it expired -- and even then, the caller learns the truth (this
     * still returns false once the window without any renewal exceeds this lock's own TTL,
     * which is the correct, honest signal: ownership was NOT provably continuous).
     */
    public boolean renew(String credentialId, String instanceId, java.time.Duration extendBy) {
        try {
            var result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(credentialId).and("instanceId").is(instanceId).and("expiresAt").gt(Instant.now())),
                new org.springframework.data.mongodb.core.query.Update().set("expiresAt", Instant.now().plus(extendBy.toMillis(), ChronoUnit.MILLIS)),
                ReconciliationLock.class);
            // Real bug: modifiedCount is 0 whenever the new value equals the value already
            // stored (e.g. a same-millisecond lease renewal) -- that was being misread as "lock
            // lost" and could abort an entire in-progress reconciliation pass even though the
            // lock was, in fact, still held. matchedCount reflects whether the document (this
            // instance's own lock row) was actually found and matched, which is what "do I still
            // hold this lock" really means here.
            return result.getMatchedCount() > 0;
        } catch (Exception e) {
            log.warn("Could not renew reconciliation lock for credential {} (treated as lost, not renewed): {}", credentialId, e.getMessage());
            return false;
        }
    }

    /**
     * Review finding ("DistributedLockService.renew() does not verify ownership generation" --
     * external review, second pass, full context in ReconciliationLock.generation's own updated
     * javadoc): the actual generation-aware renewal -- also requires this exact generation to
     * still be current, on top of instanceId and the un-expired check the plain renew() above
     * already has. A NEW overload, not a change to the existing method's own signature -- this
     * codebase's own existing renew() callers that don't carry a generation still work
     * unmodified; callers that DO capture one at acquisition time (via currentGeneration()
     * immediately after tryAcquire) get the stronger guarantee by using this overload instead.
     */
    public boolean renew(String credentialId, String instanceId, long generation, java.time.Duration extendBy) {
        try {
            var result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(credentialId).and("instanceId").is(instanceId)
                    .and("expiresAt").gt(Instant.now()).and("generation").is(generation)),
                new org.springframework.data.mongodb.core.query.Update().set("expiresAt", Instant.now().plus(extendBy.toMillis(), ChronoUnit.MILLIS)),
                ReconciliationLock.class);
            // Real bug: modifiedCount is 0 whenever the new value equals the value already
            // stored (e.g. a same-millisecond lease renewal) -- that was being misread as "lock
            // lost" and could abort an entire in-progress reconciliation pass even though the
            // lock was, in fact, still held. matchedCount reflects whether the document (this
            // instance's own lock row) was actually found and matched, which is what "do I still
            // hold this lock" really means here.
            return result.getMatchedCount() > 0;
        } catch (Exception e) {
            log.warn("Could not renew reconciliation lock for credential {} generation {} (treated as lost, not renewed): {}",
                credentialId, generation, e.getMessage());
            return false;
        }
    }
}
