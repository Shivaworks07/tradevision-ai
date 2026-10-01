package com.tradevision.repository;

import com.tradevision.model.FlattenAttempt;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FlattenAttemptRepository extends MongoRepository<FlattenAttempt, String> {
    /**
     * Review finding ("Emergency flatten still allows an exchange sell without durable
     * pre-submission intent" -- external review, twenty-sixth pass, P1, full context in
     * FlattenAttempt's own class javadoc): the actual query recovery needs -- every attempt
     * recorded for a position, most recent first, so recovery can find the exact clientOrderId
     * of the LATEST attempt to query Binance directly by, even when no OMS Order record exists
     * for it at all.
     */
    List<FlattenAttempt> findByPositionIdOrderByCreatedAtDesc(String positionId);
}
