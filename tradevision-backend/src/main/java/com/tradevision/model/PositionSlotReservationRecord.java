package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Review finding ("Position slot reservations still don't have ownership IDs" -- external
 * review, twenty-eighth pass, P0, confirmed real by direct inspection before this fix:
 * PositionSlotReservationService's own reserve()/release() operated purely on a fungible
 * reservedCount counter, with no identity distinguishing one execution's own reserved slot from
 * another's. The review's own reasoning: "the safety of the system depends on every release()
 * being executed exactly once... a future duplicate close/recovery path can do: A release, A
 * release again, and consume B's slot" -- less severe than the exposure-counter bug this same
 * pass already fixed, since periodic reconciliation self-heals drift, but the review's own
 * explicit ask is the same architecture, not a different severity of patch): mirrors
 * ExposureReservationRecord's own mechanism exactly -- every successful slot reservation gets
 * its own durable record, release(reservationId) is atomic and genuinely idempotent (a record
 * can only ever be released once), and a PENDING record created before the counter is ever
 * touched means a crash between claiming the counter and marking this record ACTIVE leaves
 * something durable for reconciliation to find, not silent drift with nothing to explain it.
 *
 * `key` matches whatever PositionSlotReservationService's own reserve()/release() callers
 * already pass as `credentialId` -- a real credential id for the per-credential slot cap, or the
 * existing "plan:"+planId convention for the separate per-plan slot cap (see AutoTradeService's
 * own call sites) -- this record doesn't need to know or care which; it just needs to durably
 * track whichever key its own reserve() call actually claimed against.
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
     * Review finding ("reservation reconciliation is still fundamentally cache-based" --
     * external review, twenty-ninth pass, P1, full context in ExposureReservationRecord's own
     * identical field javadoc): the same fix, applied to slot reservations.
     */
    private String positionId;

    /**
     * Review finding ("v183 still has a dangerous 'PENDING reservation cleanup' window" --
     * external review, thirty-fifth/thirty-seventh passes, P0, full context in
     * ExposureReservationRecord.executionId's own identical field javadoc): the same fix,
     * applied to slot reservations.
     */
    private String executionId;

    private Instant createdAt = Instant.now();
    private Instant releasedAt;
}
