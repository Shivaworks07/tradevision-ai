package com.tradevision.repository;

import com.tradevision.model.PaperOco;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Backs PaperBrokerAdapter's simulated OCO handling. getOcoStatus/cancelOco look up the
 * simulated OCO by the fake orderListId this application generated for it using the
 * inherited findById, so that is the only lookup declared on top of it besides the one
 * below.
 */
public interface PaperOcoRepository extends MongoRepository<PaperOco, String> {
    // Backs PaperBrokerAdapter's getOcoStatusByClientOrderId lookup.
    java.util.Optional<PaperOco> findByListClientOrderId(String listClientOrderId);
}
