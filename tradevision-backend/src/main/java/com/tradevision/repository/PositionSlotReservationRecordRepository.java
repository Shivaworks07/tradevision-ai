package com.tradevision.repository;

import com.tradevision.model.PositionSlotReservationRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface PositionSlotReservationRecordRepository extends MongoRepository<PositionSlotReservationRecord, String> {
    /**
     * Review finding ("Position slot reservations still don't have ownership IDs" -- external
     * review, twenty-eighth pass, P0, full context in PositionSlotReservationRecord's own class
     * javadoc): the query needed to find and clean up PENDING records abandoned by a crash
     * between the record insert and the final ACTIVE flip -- same recovery mechanism this pass
     * already built for exposure reservations.
     */
    List<PositionSlotReservationRecord> findByKeyAndStatusAndCreatedAtBefore(String key, String status, Instant cutoff);

    /**
     * Review finding ("PositionSlotReservationService.reconcile() has the same
     * stale-reservation issue conceptually" -- external review, twenty-eighth pass, P1, full
     * context in PositionSlotReservationService.reconcile's own updated javadoc): the actual
     * query for "is a reserve() call for this key provably in progress right now."
     */
    List<PositionSlotReservationRecord> findByKeyAndStatus(String key, String status);

    /**
     * Review finding ("reservation reconciliation is still fundamentally cache-based" --
     * external review, twenty-ninth pass, P1, full context in
     * PositionSlotReservationService.reconcile's own updated javadoc): the actual query needed.
     */
    List<PositionSlotReservationRecord> findByKeyAndStatusAndPositionIdIsNull(String key, String status);
}
