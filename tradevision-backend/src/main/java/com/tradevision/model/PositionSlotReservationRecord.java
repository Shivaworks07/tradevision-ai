package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A durable record of one specific slot reservation (rather than a fungible counter alone),
 * so release(reservationId) is atomic and genuinely idempotent — a record can only ever be
 * released once, which guards against a duplicate close/recovery path double-releasing the
 * same slot or consuming another execution's slot. Mirrors ExposureReservationRecord's own
 * mechanism: a PENDING record is created before the counter itself is touched, so a crash
 * between claiming the counter and marking this record ACTIVE leaves something durable for
 * reconciliation to find rather than silent drift.
 *
 * `key` matches whatever PositionSlotReservationService's own reserve()/release() callers pass
 * as `credentialId` -- a real credential id for the per-credential slot cap, or the "plan:" +
 * planId convention for the separate per-plan slot cap (see AutoTradeService's own call sites)
 * -- this record doesn't need to know or care which; it just durably tracks whichever key its
 * own reserve() call actually claimed against.
 */
@Data @NoArgsConstructor
@Document(collection = "position_slot_reservation_records")
public class PositionSlotReservationRecord {
    @Id
    private String id;

    @Indexed
    private String key;

    /** ACTIVE or RELEASED. release() is a no-op (not an error) when this is already RELEASED --
     *  genuinely idempotent, safe to call more than once for the same record by accident. */
    private String status = "ACTIVE";

    /**
     * Set once this reservation's own position is actually created and saved — before that,
     * null means "this ACTIVE reservation exists but hasn't been linked to a real position
     * document yet," which reconciliation checks for directly rather than inferring from
     * timing alone. Same mechanism as ExposureReservationRecord.positionId.
     */
    private String positionId;

    /**
     * The id of the execution attempt that created this reservation, recorded at PENDING-
     * creation time so a stale-PENDING cleanup pass can look up that execution's own real,
     * durable progress before deciding whether deleting this record is safe — an execution
     * that never reached the exchange can be cleaned up safely, one that may already have
     * reached it must not be silently deleted. Same mechanism as
     * ExposureReservationRecord.executionId.
     */
    private String executionId;

    private Instant createdAt = Instant.now();
    private Instant releasedAt;
}
