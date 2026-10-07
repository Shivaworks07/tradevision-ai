package com.tradevision.repository;

import com.tradevision.model.LiveCanaryRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LiveCanaryRecordRepository extends MongoRepository<LiveCanaryRecord, String> {
    List<LiveCanaryRecord> findByStatus(String status);

    /** authorizeLiveAutoTrade's own gate: has this exact credential had a real, successful LIVE
     *  canary order within the lookback window? Most-recent-first so hasRecentPassingCanary only
     *  ever needs to look at the first result. */
    List<LiveCanaryRecord> findByCredentialIdAndStatusAndCompletedAtAfterOrderByCompletedAtDesc(
        String credentialId, String status, LocalDateTime cutoff);

    /** The latest attempt for a credential, regardless of outcome -- backs the status-check endpoint. */
    Optional<LiveCanaryRecord> findFirstByCredentialIdOrderByStartedAtDesc(String credentialId);
}
