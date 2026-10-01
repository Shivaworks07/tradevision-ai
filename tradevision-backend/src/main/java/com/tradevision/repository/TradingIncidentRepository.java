package com.tradevision.repository;

import com.tradevision.model.TradingIncident;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TradingIncidentRepository extends MongoRepository<TradingIncident, String> {
    List<TradingIncident> findByUserIdAndResolvedAtIsNullOrderByCreatedAtDesc(String userId);
    List<TradingIncident> findByCredentialIdAndResolvedAtIsNullOrderByCreatedAtDesc(String credentialId);
    long countByUserIdAndSeverityAndResolvedAtIsNull(String userId, String severity);
    /**
     * Review finding ("exchange rejection taxonomy dashboards" / "automatic broker incident
     * dashboards" -- external review, P3, confirmed real by direct inspection before this fix:
     * TradingIncident already carries a real type/severity taxonomy on every record, but no
     * controller anywhere exposed an aggregate view of it): the actual bounded query the
     * taxonomy dashboard needs -- same date-bounded pattern this session already established
     * for every other "large collection read" fix (see P2-7's own reasoning).
     */
    java.util.List<TradingIncident> findByCreatedAtAfter(java.time.LocalDateTime cutoff);

    /**
     * Review finding ("Critical alerting is still best-effort" -- external review, thirty-sixth
     * pass, P1, full context in TradingIncident.notificationStatus's own field javadoc): the
     * actual query IncidentRetryService's own scheduled pass needs -- every incident whose
     * delivery hasn't yet succeeded or been exhausted.
     */
    java.util.List<TradingIncident> findByNotificationStatusIn(java.util.List<String> statuses);
}
