package com.tradevision.repository;

import com.tradevision.model.ExposureReservationRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ExposureReservationRecordRepository extends MongoRepository<ExposureReservationRecord, String> {
    /**
     * Review finding ("Exposure reservation creation is still not atomic with the exposure
     * counter" -- external review, twenty-eighth pass, P0, full context in
     * ExposureReservationService.reconcile's own updated javadoc): the actual query needed to
     * find and clean up PENDING records abandoned by a crash between the record insert and the
     * final ACTIVE flip.
     */
    java.util.List<ExposureReservationRecord> findByCredentialIdAndStatusAndCreatedAtBefore(
        String credentialId, String status, java.time.Instant cutoff);

    /**
     * Review finding ("The lastReservedAt grace-window design is still time-based safety" --
     * external review, twenty-eighth pass, P1, full context in
     * ExposureReservationService.reconcile's own updated javadoc): the actual query for "is a
     * reserve() call for this credential provably in progress right now" -- any PENDING record
     * at all, no time bound needed (the stale ones are already cleaned up by the query above
     * before this one is ever checked).
     */
    java.util.List<ExposureReservationRecord> findByCredentialIdAndStatus(String credentialId, String status);

    /**
     * Review finding ("reservation reconciliation is still fundamentally cache-based" --
     * external review, twenty-ninth pass, P1, full context in
     * ExposureReservationService.reconcile's own updated javadoc): the actual query the fix
     * needs -- ACTIVE reservations genuinely not yet linked to any position.
     */
    java.util.List<ExposureReservationRecord> findByCredentialIdAndStatusAndPositionIdIsNull(String credentialId, String status);
}
