package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Review finding ("ReconciliationLock.generation() is not actually a monotonic generation" --
 * external review, third pass, confirmed real by direct inspection before any fix was
 * attempted): the previous design used ReconciliationLock.acquiredAt's own epoch-millis value as
 * the "generation" -- reasoned at the time that equality matching (not ordering comparison)
 * meant true monotonicity wasn't strictly required for that specific mechanism's own
 * correctness, only value uniqueness across acquisitions. The review's own point stands on its
 * own terms regardless: wall-clock time is not a real fencing token by the term's own accepted
 * meaning (NTP adjustment, VM clock correction, and clock skew between containers can all move a
 * timestamp backward or produce a collision), and a mechanism that calls itself a fencing token
 * should actually be one, not something that happens to work today under an unstated assumption
 * about clock behavior. This is a genuine, DB-generated, atomically incrementing counter instead
 * -- immune to wall-clock behavior entirely, since it never reads a clock at all.
 *
 * Deliberately a SEPARATE collection/document from ReconciliationLock itself, never deleted --
 * ReconciliationLock's own documents are removed and freshly reinserted on every acquisition
 * (insert(), relying on a DuplicateKeyException on the credentialId-keyed _id for real mutual
 * exclusion -- see DistributedLockService's own javadoc), which is why the counter can't simply
 * live as a field on that same document: it would reset to whatever a fresh object's own default
 * is on every single acquisition, never actually accumulating. A single small counter document
 * per credential, incremented via a genuine atomic $inc + upsert, persists across every
 * acquisition/release cycle for that credential's entire lifetime instead.
 */
@Data @NoArgsConstructor
@Document(collection = "lock_generation_counters")
public class LockGenerationCounter {
    @Id
    private String id; // the credentialId this counter is for
    private long value = 0;
}
