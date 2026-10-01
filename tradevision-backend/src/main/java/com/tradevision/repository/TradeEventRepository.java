package com.tradevision.repository;

import com.tradevision.model.TradeEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TradeEventRepository extends MongoRepository<TradeEvent, String> {
    List<TradeEvent> findByOrderIdOrderByOccurredAtAsc(String orderId);
    List<TradeEvent> findByPositionIdOrderByOccurredAtAsc(String positionId);
}
