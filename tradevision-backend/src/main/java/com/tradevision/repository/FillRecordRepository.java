package com.tradevision.repository;

import com.tradevision.model.FillRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FillRecordRepository extends MongoRepository<FillRecord, String> {
    List<FillRecord> findByOrderId(String orderId);
    List<FillRecord> findByUserIdAndSymbolOrderByExecutedAtAsc(String userId, String symbol);
    // Backs PositionLedgerService.reconstructPosition — the review's own point: a position spans
    // an entry order AND however many exit orders eventually close it, so orderId alone is too
    // narrow to reconstruct a whole position's history.
    List<FillRecord> findByPositionIdOrderByExecutedAtAsc(String positionId);
}
