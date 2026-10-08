package com.tradevision.repository;

import com.tradevision.model.TradingIncident;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TradingIncidentRepository extends MongoRepository<TradingIncident, String> {
    List<TradingIncident> findByUserIdAndResolvedAtIsNullOrderByCreatedAtDesc(String userId);
    List<TradingIncident> findByCredentialIdAndResolvedAtIsNullOrderByCreatedAtDesc(String credentialId);
    long countByUserIdAndSeverityAndResolvedAtIsNull(String userId, String severity);
    /**
     * Bounded query backing the incident taxonomy dashboard, returning incidents created
     * since the given cutoff rather than the entire collection.
     */
    java.util.List<TradingIncident> findByCreatedAtAfter(java.time.LocalDateTime cutoff);

    /**
     * Backs IncidentRetryService's scheduled pass: returns every incident whose
     * notification delivery hasn't yet succeeded or been exhausted.
     */
    java.util.List<TradingIncident> findByNotificationStatusIn(java.util.List<String> statuses);
}
