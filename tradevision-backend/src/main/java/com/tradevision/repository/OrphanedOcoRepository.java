package com.tradevision.repository;

import com.tradevision.model.OrphanedOco;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OrphanedOcoRepository extends MongoRepository<OrphanedOco, String> {
    // Returns every not-yet-resolved orphan for this credential, so a resolved one from a
    // prior reconciliation pass is never re-processed.
    /**
     * Bounded, paginated batch of unresolved orphaned OCOs for a credential, so a single
     * reconciliation pass can't pull an unbounded result set.
     */
    List<OrphanedOco> findByCredentialIdAndResolvedFalse(String credentialId, org.springframework.data.domain.Pageable pageable);
    /**
     * Bounded query backing the operator-facing reconciliation report, returning orphaned
     * OCO records created since the given cutoff.
     */
    List<OrphanedOco> findByCreatedAtAfter(java.time.LocalDateTime cutoff);

    /**
     * Returns all unresolved orphaned OCOs across every credential, oldest first, for
     * recovery metrics.
     */
    List<OrphanedOco> findByResolvedFalseOrderByCreatedAtAsc();
}
