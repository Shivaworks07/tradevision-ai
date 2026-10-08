package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A distributed mutual-exclusion lock, one document per credential, that prevents two
 * application instances from reconciling the same credential concurrently. The credential id
 * is used as MongoDB's own _id field, so only one document with a given id can ever exist —
 * inserting a second one fails atomically at the database level with a DuplicateKeyException,
 * giving real inter-process mutual exclusion rather than an in-process-only lock.
 *
 * expiresAt is a real, absolute point in time (not a relative duration from creation), backed
 * by a TTL index that expires documents once their own expiresAt value is in the past (see
 * IndexInitializer's own ensureExpireAtIndex), so a lock from a crashed or hung instance that
 * never released it doesn't block reconciliation for this credential forever.
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
     * A fencing token identifying this specific lock acquisition, assigned once at acquisition
     * time from LockGenerationCounter's atomic, DB-generated, monotonic counter — never derived
     * from a timestamp, since wall-clock time (subject to NTP adjustment, VM clock correction,
     * and clock skew between containers) cannot safely guarantee uniqueness or ordering. Fixed
     * at insert time and never changed for the lifetime of this acquisition (a renewal only
     * extends expiresAt, never re-derives this value), which is what lets renew() detect a
     * stale holder from an earlier acquisition period rather than matching against a still-
     * changing value.
     */
    private long generation;

    public ReconciliationLock(String credentialId, String instanceId, Instant expiresAt, long generation) {
        this.id = credentialId;
        this.instanceId = instanceId;
        this.expiresAt = expiresAt;
        this.generation = generation;
    }
}
