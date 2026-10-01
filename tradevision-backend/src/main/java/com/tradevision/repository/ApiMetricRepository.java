package com.tradevision.repository;

import com.tradevision.model.ApiMetric;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface ApiMetricRepository extends MongoRepository<ApiMetric, String> {
    List<ApiMetric> findByRecordedAtAfter(LocalDateTime since);
    long countByErrorTrueAndRecordedAtAfter(LocalDateTime since);
    long countByRecordedAtAfter(LocalDateTime since);
}
