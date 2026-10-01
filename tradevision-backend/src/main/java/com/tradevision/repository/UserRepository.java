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
}
