package com.tradevision.repository;

import com.tradevision.model.AuditChainCheckpoint;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Backs AuditChainService, which reads and writes a single, well-known checkpoint
 * document (identified by SINGLETON_ID). The inherited findById/save operations are all
 * that's needed, so no custom query methods are declared here.
 */
public interface AuditChainCheckpointRepository extends MongoRepository<AuditChainCheckpoint, String> {
}
