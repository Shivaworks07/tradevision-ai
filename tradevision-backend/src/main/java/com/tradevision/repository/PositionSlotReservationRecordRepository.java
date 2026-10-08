package com.tradevision.repository;

import com.tradevision.model.PositionSlotReservationRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface PositionSlotReservationRecordRepository extends MongoRepository<PositionSlotReservationRecord, String> {
    /**
     * Finds PENDING slot reservation records older than the cutoff, so reconciliation can
     * clean up records abandoned by a crash between the record insert and the final ACTIVE
     * flip — the same recovery mechanism used for exposure reservations.
     */
    List<PositionSlotReservationRecord> findByKeyAndStatusAndCreatedAtBefore(String key, String status, Instant cutoff);

    /**
     * Returns any PENDING record for a key, used to tell whether a reserve() call for that
     * key is provably in progress right now.
     */
    List<PositionSlotReservationRecord> findByKeyAndStatus(String key, String status);

    /**
     * Returns ACTIVE reservations for a key that are not yet linked to any position, for
     * reconciliation.
     */
    List<PositionSlotReservationRecord> findByKeyAndStatusAndPositionIdIsNull(String key, String status);
}
