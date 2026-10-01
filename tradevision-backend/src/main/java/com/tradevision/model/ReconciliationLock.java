package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Review finding ("Single-instance assumption for autonomous trading/reconciliation -- no
 * distributed lock, unsafe to scale replicas" -- P0): confirmed real. PositionMonitorService's
 * own reconciliationLocks field (a ConcurrentHashMap of JVM-local ReentrantLocks) only prevents
 * two threads WITHIN THE SAME PROCESS from reconciling the same credential concurrently -- it
 * does nothing at all across two application instances, which could both reconcile (and
 * potentially double-act on) the same credential simultaneously if ever scaled beyond
 * replicas: 1.
 *
 * Same technique as BootstrapLock's own proven design (see its own javadoc): a single document
 * per credential, with the credential id as MongoDB's own _id field. Only one document with a
 * given _id can ever exist -- inserting a second one fails atomically at the database level with
 * a DuplicateKeyException, not via application-level coordination that itself would need to be
 * distributed. This is real inter-process mutual exclusion, not a JVM-local approximation of it.
 *
 * expiresAt is a real, absolute point in time (not a relative duration from creation) -- backed
 * by a TTL index configured to expire documents once their OWN expiresAt value is in the past
 * (see IndexInitializer's own ensureExpireAtIndex), so a lock from a crashed or hung instance
 * that never released it doesn't block reconciliation for this credential forever.
 */
@Data @NoArgsConstructor
@Document(collection = "reconciliation_locks")
public class ReconciliationLock {
    @Id
    private String id; // the credentialId being locked -- the whole point is that only ONE document with this exact id can ever exist
    private String instanceId; // which application instance currently holds this lock, for diagnostics and safe release
    private Instant acquiredAt = Instant.now();
    private Instant expiresAt;
    /**
     * Review finding ("ReconciliationLock.generation() is not actually a monotonic generation"
     * -- external review, third pass, confirmed real by direct inspection before any fix was
     * attempted, full context in LockGenerationCounter's own javadoc): this used to be a
     * computed value derived from acquiredAt's own epoch-millis. The prior design's own
     * reasoning (equality matching, not ordering comparison, so strict monotonicity wasn't
     * needed for THAT mechanism's own correctness) is still true on its own narrow terms, but
     * the review's underlying point stands regardless: something that calls itself a fencing
     * token should actually be one by the term's own accepted meaning, not something that
     * happens to work today under an unstated assumption about wall-clock behavior (NTP
     * adjustment, VM clock correction, and clock skew between containers can all move a
     * timestamp backward or, in principle, produce a collision). Now a real value, assigned
     * once at acquisition time from LockGenerationCounter's own atomic, DB-generated, monotonic
     * counter -- immune to wall-clock behavior entirely, since obtaining it never reads a clock.
     * Fixed at insert time and never changed for the lifetime of this specific acquisition (a
     * renewal only extends expiresAt, never re-derives this value) -- this is what lets renew()
     * detect a stale holder from an earlier acquisition period, not a live, still-changing
     * counter that a stale holder's read could coincidentally still match.
     */
    private long generation;

    public ReconciliationLock(String credentialId, String instanceId, Instant expiresAt, long generation) {
        this.id = credentialId;
        this.instanceId = instanceId;
        this.expiresAt = expiresAt;
        this.generation = generation;
    }
}
