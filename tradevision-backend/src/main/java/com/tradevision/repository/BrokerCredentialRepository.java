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
    // Audit fix (P1-1 follow-up -- external review, second pass: "Require the alert channel to
    // cover the live user" -- full context in AlertChannelStartupGuard's own class javadoc):
    // countByMode above only answers "do any exist"; checking coverage needs to know WHICH
    // user(s) actually hold one, since the guard must verify each one of those specific users
    // has their own alert channel, not just that some user somewhere in the system does. The
    // number of LIVE credentials in any real deployment is small, so loading them in full here
    // (rather than a dedicated distinct-userId aggregation) is a deliberate simplicity trade-off.
    List<BrokerCredential> findByMode(BrokerMode mode);
}
