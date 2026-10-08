package com.tradevision.repository;

import com.tradevision.model.PaperAccountBalance;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Backs PaperBrokerAdapter's durable paper-trading balance. The inherited findById/save
 * operations are all that's needed: load the one durable balance document for a given
 * PAPER credential on construction, and upsert it after every simulated fill.
 */
public interface PaperAccountBalanceRepository extends MongoRepository<PaperAccountBalance, String> {
}
