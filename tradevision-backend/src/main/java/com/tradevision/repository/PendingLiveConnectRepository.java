package com.tradevision.repository;

import com.tradevision.model.PendingLiveConnect;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Backs BrokerCredentialService's requestLiveConnect/confirmLiveConnect flow. The
 * inherited findById/save/deleteById operations are all that's needed, so no custom
 * query methods are declared here.
 */
public interface PendingLiveConnectRepository extends MongoRepository<PendingLiveConnect, String> {
}
