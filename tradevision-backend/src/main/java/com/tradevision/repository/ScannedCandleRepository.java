package com.tradevision.repository;

import com.tradevision.model.ScannedCandle;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ScannedCandleRepository extends MongoRepository<ScannedCandle, String> {
}
