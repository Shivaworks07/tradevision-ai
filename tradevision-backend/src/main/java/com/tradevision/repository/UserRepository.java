package com.tradevision.repository;

import com.tradevision.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmail(String email);
    Optional<User> findByMobile(String mobile);
    Optional<User> findByEmailOrMobile(String email, String mobile);
    boolean existsByEmail(String email);
    boolean existsByMobile(String mobile);
    long countByCreatedAtAfter(java.time.LocalDateTime since);
    long countByLastLoginAfter(java.time.LocalDateTime since);
    long countByRole(String role);
    // P1-1 fix ("No startup check that an alert channel is configured before LIVE trading is
    // possible" -- full context in AlertChannelStartupGuard's own class javadoc): AuthService.
    // setAlertWebhookUrl always normalizes a blank/whitespace-only URL to null before saving
    // (see that method's own javadoc), so "not null" here is already exactly "a real, non-blank
    // webhook URL is configured" -- no separate blank-string filtering is needed.
    boolean existsByAlertWebhookUrlIsNotNull();
}
