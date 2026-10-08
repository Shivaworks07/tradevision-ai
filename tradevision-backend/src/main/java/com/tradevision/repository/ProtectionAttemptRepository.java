package com.tradevision.repository;

import com.tradevision.model.ProtectionAttempt;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ProtectionAttemptRepository extends MongoRepository<ProtectionAttempt, String> {
    // Backs the recovery sweep: every attempt still genuinely stuck in SUBMITTING (never
    // resolved to ACTIVE/FAILED) older than a reasonable "the exchange should have
    // responded by now" threshold.
    /**
     * Bounded, paginated batch of stuck ProtectionAttempts, so the caller controls the
     * batch size rather than ever pulling every stuck attempt across all of history in
     * one pass.
     */
    List<ProtectionAttempt> findByStatusAndCreatedAtBefore(String status, LocalDateTime cutoff, org.springframework.data.domain.Pageable pageable);

    /**
     * Returns every attempt still stuck in SUBMITTING, oldest first, so the recovery-health
     * endpoint can directly surface the age of the single oldest unresolved attempt.
     */
    List<ProtectionAttempt> findByStatusOrderByCreatedAtAsc(String status);
}
