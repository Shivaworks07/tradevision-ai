package com.tradevision.repository;

import com.tradevision.model.OrphanedOco;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OrphanedOcoRepository extends MongoRepository<OrphanedOco, String> {
    // Review finding ("OCO Persistence Failure Has No Reconciliation Path" -- external review,
    // third pass, full context in OrphanedOco's own javadoc): the one query
    // recoverOrphanedOcos() actually needs -- every not-yet-resolved orphan for this credential,
    // so a resolved one from a prior pass is never re-processed.
    /**
     * Review finding ("Some repository queries return unlimited lists" -- external review,
     * thirty-eighth pass, P2, full context in ProtectionAttemptRepository's own identical fix):
     * the same bounded-batch fix, applied to orphaned OCOs.
     */
    List<OrphanedOco> findByCredentialIdAndResolvedFalse(String credentialId, org.springframework.data.domain.Pageable pageable);
    /**
     * Review finding ("automated reconciliation reports" -- external review, P3, confirmed real
     * by direct inspection before this fix: reconciliation itself already runs on a schedule,
     * already raises incidents and creates OrphanedOco records for anything genuinely wrong, but
     * nothing anywhere summarized what actually happened across those passes into a report an
     * operator could read): the actual bounded query the report needs.
     */
    List<OrphanedOco> findByCreatedAtAfter(java.time.LocalDateTime cutoff);

    /**
     * Review finding ("Recovery metrics need to be first-class" -- external review, thirty-sixth
     * pass, P2, full context in ProtectionAttemptRepository's own identical fix): the same
     * global (not per-credential), oldest-first query, for orphaned OCOs.
     */
    List<OrphanedOco> findByResolvedFalseOrderByCreatedAtAsc();
}
