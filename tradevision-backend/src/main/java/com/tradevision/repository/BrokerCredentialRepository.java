package com.tradevision.repository;

import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface BrokerCredentialRepository extends MongoRepository<BrokerCredential, String> {
    List<BrokerCredential> findByUserIdAndActiveTrue(String userId);
    Optional<BrokerCredential> findByIdAndUserId(String id, String userId);
    // P1-16 fix ("Committed secrets + 'local' as default profile" -- full context in
    // DevSecretStartupGuard's own class javadoc): needed to answer "does this deployment
    // already hold real, live broker credentials" without loading every credential document
    // just to count them.
    long countByMode(BrokerMode mode);
}
