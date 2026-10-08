package com.tradevision.repository;

import com.tradevision.model.BrokerCredential;
import com.tradevision.model.BrokerMode;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface BrokerCredentialRepository extends MongoRepository<BrokerCredential, String> {
    List<BrokerCredential> findByUserIdAndActiveTrue(String userId);
    Optional<BrokerCredential> findByIdAndUserId(String id, String userId);
    // Answers "does this deployment already hold real, live broker credentials" without
    // loading every credential document just to count them.
    long countByMode(BrokerMode mode);
    // Unlike countByMode, this returns which user(s) actually hold a credential in this
    // mode, since the alert-channel guard must verify each of those specific users has
    // their own alert channel, not just that some user somewhere does. The number of LIVE
    // credentials in any real deployment is small, so loading them in full here (rather
    // than a dedicated distinct-userId aggregation) is a deliberate simplicity trade-off.
    List<BrokerCredential> findByMode(BrokerMode mode);
}
