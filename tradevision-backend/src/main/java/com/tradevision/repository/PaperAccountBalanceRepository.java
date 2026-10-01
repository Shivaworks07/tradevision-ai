package com.tradevision.repository;

import com.tradevision.model.PaperAccountBalance;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * P2-4 fix, full context in PaperAccountBalance's own class javadoc: findById/save (already
 * inherited from MongoRepository/CrudRepository) are the only operations PaperBrokerAdapter
 * actually needs -- load the one durable balance document for a given PAPER credential on
 * construction, and upsert it after every simulated fill.
 */
public interface PaperAccountBalanceRepository extends MongoRepository<PaperAccountBalance, String> {
}
