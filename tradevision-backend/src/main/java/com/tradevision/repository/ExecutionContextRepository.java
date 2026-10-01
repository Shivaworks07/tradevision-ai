package com.tradevision.repository;

import com.tradevision.model.ExecutionContext;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ExecutionContextRepository extends MongoRepository<ExecutionContext, String> {
    /** Given a signalId, find every execution attempt made for it -- a signal can, in principle,
     *  be evaluated more than once (a retry, a recovery pass), and each attempt gets its own
     *  distinct executionId, all traceable back to the same signal. */
    List<ExecutionContext> findBySignalIdOrderByCreatedAtDesc(String signalId);

    List<ExecutionContext> findByPositionId(String positionId);
}
