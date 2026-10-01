package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Review finding ("Exposure reservation rollback can steal another trade's reservation" /
 * "Generic exposure release() has the same ownership problem" -- external review, twenty-sixth
 * pass, P0, confirmed real by direct inspection before this fix: ExposureReservationService's
 * own reserve()/release() operated purely on aggregate counters (ExposureReservation's own
 * total/per-symbol/per-group fields), with no way to verify a given release/rollback amount
 * genuinely belonged to the execution calling it. A double-release (a retry, a race between two
 * code paths both believing they own the same release) would silently over-decrement the shared
 * counter, letting more real exposure through than the configured cap actually permits -- the
 * review's own explicit "biggest thing found in v172" finding): the actual fix -- every
 * successful reservation now gets its own durable, immutable-once-written record of EXACTLY
 * what it reserved. release() and internal rollback now operate on this record's own id, not a
 * recomputed amount, and a record can only ever be released once (see
 * ExposureReservationService.release(String)'s own idempotency check).
 */
@Data @NoArgsConstructor
@Document(collection = "exposure_reservation_records")
public class ExposureReservationRecord {
    @Id
    private String id;

    @Indexed
    private String credentialId;
    private String symbol;

    /** Exactly what this record reserved -- release() reads these back rather than trusting a
     *  caller-recomputed amount, which the review's own P0-2 finding shows can legitimately
     *  differ from the original reservation (close-time quantity*avgEntryPrice vs.
     *  reserve-time quantity*livePrice are NOT guaranteed to be the same number). */
    private BigDecimal totalAmountReserved;
    private BigDecimal symbolAmountReserved;
    /** Correlation group name -> the exact amount reserved against that group's own cap. */
    private Map<String, BigDecimal> groupAmountsReserved;

    /** ACTIVE or RELEASED. release() is a no-op (not an error) when this is already RELEASED --
     *  genuinely idempotent, safe to call more than once for the same record by accident. */
    private String status = "ACTIVE";

    /**
     * Review finding ("reservation reconciliation is still fundamentally cache-based" --
     * external review, twenty-ninth pass, P1, the review's own explicit "biggest remaining
     * reservation concern": reconcile() used to overwrite the aggregate counter purely from
     * actual OPEN positions -- an ACTIVE reservation record whose own position hadn't been
     * persisted yet (a real, even if narrow, window the review's own grace-window/PENDING
     * protections narrow but don't eliminate) would be silently excluded, understating real
     * exposure): set once this reservation's own position is actually created and saved --
     * before that, null means "this ACTIVE reservation exists but hasn't been linked to a real
     * position document yet," a real, meaningful state reconcile() now checks for directly
     * instead of inferring it from timing alone. See ExposureReservationService.reconcile's own
     * updated javadoc for exactly how this is used.
     */
    private String positionId;

    /**
     * Review finding ("v183 still has a dangerous 'PENDING reservation cleanup' window" --
     * external review, thirty-fifth/thirty-seventh passes, P0, the review's own explicit
     * required fix: "recovery tied to: reservationId, executionId, clientOrderId, positionId...
     * Never simply delete a stale PENDING reservation without first proving that no exchange
     * execution can be associated with it"): this is the actual link that makes that proof
     * possible without a Mongo transaction across this whole reserve() sequence (which this
     * session already reasoned through and declined -- reserve() also runs for TESTNET/PAPER
     * credentials with no guaranteed replica-set support). AutoTradeService already creates a
     * real ExecutionContext, with its own executionId, BEFORE ever calling reserve() -- stored
     * here at PENDING-creation time, this lets reconcile()'s own stale-PENDING cleanup look up
     * that execution's own real, durable progress before deciding whether deleting this record
     * is actually safe: an execution that never reached the exchange can be cleaned up safely;
     * one that may have already reached it must not be silently deleted.
     */
    private String executionId;

    private Instant createdAt = Instant.now();
    private Instant releasedAt;
}
