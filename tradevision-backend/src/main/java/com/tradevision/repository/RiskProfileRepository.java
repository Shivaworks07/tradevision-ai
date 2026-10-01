package com.tradevision.repository;

import com.tradevision.model.RiskProfile;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface RiskProfileRepository extends MongoRepository<RiskProfile, String> {
    List<RiskProfile> findByUserIdAndAutoTradeEnabledTrue(String userId);
    Optional<RiskProfile> findByCredentialId(String credentialId);
    Optional<RiskProfile> findByUserIdAndCredentialId(String userId, String credentialId);
    // Review finding ("The biggest missing thing" — "TradeVision does not autonomously discover
    // trades"): backs AutonomousScannerService's global scan — needs every auto-trade-enabled,
    // not-currently-halted profile across ALL users, not one user's profiles at a time.
    List<RiskProfile> findByAutoTradeEnabledTrueAndTradingHaltedFalse();
}
