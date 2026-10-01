package com.tradevision.repository;

import com.tradevision.model.PendingLiveConnect;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * P2-5 fix, full context in PendingLiveConnect's own class javadoc: findById/save/deleteById
 * (already inherited from MongoRepository/CrudRepository) are the only operations
 * BrokerCredentialService's own requestLiveConnect/confirmLiveConnect actually need.
 */
public interface PendingLiveConnectRepository extends MongoRepository<PendingLiveConnect, String> {
}
