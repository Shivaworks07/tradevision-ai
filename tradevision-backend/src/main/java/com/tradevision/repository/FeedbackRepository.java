package com.tradevision.repository;

import com.tradevision.model.Feedback;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface FeedbackRepository extends MongoRepository<Feedback, String> {
    List<Feedback> findByOrderByCreatedAtDesc(Pageable p);
    List<Feedback> findByStatusOrderByCreatedAtDesc(String status, Pageable p);
    List<Feedback> findByTypeOrderByCreatedAtDesc(String type, Pageable p);
    List<Feedback> findByUserIdOrderByCreatedAtDesc(String userId);
    long countByStatus(String status);
    long countByType(String type);
}
