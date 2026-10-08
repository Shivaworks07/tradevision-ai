package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A durable, atomically incrementing counter — one document per credential — that supplies
 * ReconciliationLock.generation with a real fencing token for each lock acquisition, immune to
 * wall-clock behavior (NTP adjustment, VM clock correction, clock skew between containers)
 * since it never reads a clock at all; it is incremented via a genuine atomic $inc + upsert.
 *
 * Deliberately a separate collection/document from ReconciliationLock itself, and never
 * deleted: ReconciliationLock's own documents are removed and freshly reinserted on every
 * acquisition (relying on a DuplicateKeyException on the credentialId-keyed _id for mutual
 * exclusion — see DistributedLockService), so a counter living as a field on that same
 * document would reset on every acquisition instead of actually accumulating. This counter
 * persists across every acquisition/release cycle for a credential's entire lifetime instead.
 */
@Data @NoArgsConstructor
@Document(collection = "lock_generation_counters")
public class LockGenerationCounter {
    @Id
    private String id; // the credentialId this counter is for
    private long value = 0;
}
