package com.tradevision.repository;

import com.tradevision.model.ProtectionAttempt;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ProtectionAttemptRepository extends MongoRepository<ProtectionAttempt, String> {
    // Review finding ("OCO persistence still has an unavoidable crash window" -- external
    // review, nineteenth pass, P1, full context in ProtectionAttempt's own class javadoc): the
    // one query the recovery sweep needs -- every attempt still genuinely stuck in SUBMITTING
    // (never resolved to ACTIVE/FAILED) older than a reasonable "the exchange should have
    // responded by now" threshold.
    /**
     * Review finding ("Some repository queries return unlimited lists" -- external review,
     * thirty-eighth pass, P2, the review's own explicit example naming this exact query:
     * "If a credential has a large backlog, reconciliation can process a very large list in one
     * cycle... change these to Pageable / limit with bounded recovery batches"): the actual fix
     * -- a Pageable parameter, so the caller controls the batch size rather than this query ever
     * returning every stuck ProtectionAttempt across all of history in one pass.
     */
    List<ProtectionAttempt> findByStatusAndCreatedAtBefore(String status, LocalDateTime cutoff, org.springframework.data.domain.Pageable pageable);

    /**
     * Review finding ("Recovery metrics need to be first-class" -- external review, thirty-sixth
     * pass, P2, confirmed real by direct inspection before this fix: this application had no way
     * to answer "how old is the oldest unresolved ProtectionAttempt right now" without a direct
     * database query -- exactly the kind of number an ops health view needs to surface directly,
     * not leave to someone running a manual query during an incident): the actual query the new
     * recovery-health endpoint needs -- every attempt still genuinely stuck in SUBMITTING,
     * oldest first, so the single oldest one's own age is directly available.
     */
    List<ProtectionAttempt> findByStatusOrderByCreatedAtAsc(String status);
}
