package com.tradevision.repository;

import com.tradevision.model.PaperOco;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Review finding ("No pure paper-trading mode with full isolation" -- external review,
 * eighteenth pass, full context in PaperOco's own javadoc): findById (already inherited from
 * MongoRepository/CrudRepository) is the only lookup PaperBrokerAdapter's own getOcoStatus/
 * cancelOco actually need -- finding the simulated OCO by the fake orderListId this application
 * itself generated for it. No custom query methods needed on top of that.
 */
public interface PaperOcoRepository extends MongoRepository<PaperOco, String> {
    // Review finding ("OCO persistence still has an unavoidable crash window" -- external
    // review, nineteenth pass, P1, full context in PaperOco's own updated field comment): the
    // one additional lookup PaperBrokerAdapter's own new getOcoStatusByClientOrderId needs.
    java.util.Optional<PaperOco> findByListClientOrderId(String listClientOrderId);
}
