package com.tradevision.repository;

import com.tradevision.model.AuditChainCheckpoint;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * P2-7 fix, full context in AuditChainCheckpoint's own class javadoc: findById(SINGLETON_ID)/save
 * (already inherited from MongoRepository/CrudRepository) are the only operations
 * AuditChainService actually needs against this single, well-known document.
 */
public interface AuditChainCheckpointRepository extends MongoRepository<AuditChainCheckpoint, String> {
}
