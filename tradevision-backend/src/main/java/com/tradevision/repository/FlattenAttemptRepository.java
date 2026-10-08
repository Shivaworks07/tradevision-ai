package com.tradevision.repository;

import com.tradevision.model.FlattenAttempt;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FlattenAttemptRepository extends MongoRepository<FlattenAttempt, String> {
    /**
     * Returns every flatten attempt recorded for a position, most recent first, so recovery
     * can find the exact clientOrderId of the latest attempt to query the exchange directly
     * by, even when no OMS Order record exists for it at all.
     */
    List<FlattenAttempt> findByPositionIdOrderByCreatedAtDesc(String positionId);
}
