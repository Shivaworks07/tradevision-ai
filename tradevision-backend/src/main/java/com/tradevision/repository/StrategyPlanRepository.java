package com.tradevision.repository;

import com.tradevision.model.StrategyPlan;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface StrategyPlanRepository extends MongoRepository<StrategyPlan, String> {
    List<StrategyPlan> findByCredentialId(String credentialId);
    List<StrategyPlan> findByCredentialIdAndEnabledTrue(String credentialId);
    Optional<StrategyPlan> findByCredentialIdAndDefaultPlanTrue(String credentialId);
}
