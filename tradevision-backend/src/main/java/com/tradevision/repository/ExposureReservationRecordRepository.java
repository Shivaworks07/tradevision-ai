package com.tradevision.repository;

import com.tradevision.model.ExposureReservationRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ExposureReservationRecordRepository extends MongoRepository<ExposureReservationRecord, String> {
    /**
     * Finds PENDING reservation records older than the cutoff, so reconciliation can clean
     * up records abandoned by a crash between the record insert and the final ACTIVE flip.
     */
    java.util.List<ExposureReservationRecord> findByCredentialIdAndStatusAndCreatedAtBefore(
        String credentialId, String status, java.time.Instant cutoff);

    /**
     * Returns any PENDING record for a credential, used to tell whether a reserve() call
     * for that credential is provably in progress right now. No time bound is needed here
     * since stale PENDING records are already cleaned up by the cutoff-bounded query above
     * before this one is checked.
     */
    java.util.List<ExposureReservationRecord> findByCredentialIdAndStatus(String credentialId, String status);

    /**
     * Returns ACTIVE reservations for a credential that are not yet linked to any
     * position, for reconciliation.
     */
    java.util.List<ExposureReservationRecord> findByCredentialIdAndStatusAndPositionIdIsNull(String credentialId, String status);
}
