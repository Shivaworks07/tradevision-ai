package com.tradevision.repository;

import com.tradevision.model.BrokerAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;

public interface BrokerAuditLogRepository extends MongoRepository<BrokerAuditLog, String> {
    /**
     * Bounded, paginated query backing the audit log export endpoint, so every record
     * within a time range is genuinely extractable before the TTL index
     * (BrokerAuditLog.timestamp, 730 days — see IndexInitializer) deletes it.
     */
    Page<BrokerAuditLog> findByTimestampBetweenOrderByTimestampDesc(LocalDateTime from, LocalDateTime to, Pageable pageable);
    /**
     * Returns the most recent audit log record, used to extend the hash chain onto the
     * newest existing record — see BrokerAuditLog.recordHash's field javadoc for the chain
     * mechanism and its limitations under concurrent writes.
     */
    java.util.Optional<BrokerAuditLog> findTopByOrderByTimestampDesc();

    /**
     * Returns the newest record older than the given cutoff — i.e. the record closest to
     * being deleted by the TTL index — which is exactly the one
     * AuditChainService.checkpointRecordsNearingExpiry needs to durably anchor before it
     * expires.
     */
    java.util.Optional<BrokerAuditLog> findTopByTimestampBeforeOrderByTimestampDesc(LocalDateTime cutoff);
}
