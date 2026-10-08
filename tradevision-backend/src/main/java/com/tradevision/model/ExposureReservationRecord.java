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
 * A durable, immutable-once-written record of exactly what one successful exposure reservation
 * reserved — a companion to the aggregate counters on ExposureReservation. release() and
 * internal rollback operate on this record's own id, not a recomputed amount, and a record can
 * only ever be released once (see ExposureReservationService.release(String)'s own idempotency
 * check), which guards against a double-release (a retry, or a race between two code paths
 * both believing they own the same release) silently over-decrementing the shared aggregate
 * counter and letting more real exposure through than the configured cap permits.
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
     *  caller-recomputed amount, since it can legitimately differ from the original reservation
     *  (close-time quantity*avgEntryPrice vs. reserve-time quantity*livePrice are not
     *  guaranteed to be the same number). */
    private BigDecimal totalAmountReserved;
    private BigDecimal symbolAmountReserved;
    /** Correlation group name -> the exact amount reserved against that group's own cap. */
    private Map<String, BigDecimal> groupAmountsReserved;

    /** ACTIVE or RELEASED. release() is a no-op (not an error) when this is already RELEASED --
     *  genuinely idempotent, safe to call more than once for the same record by accident. */
    private String status = "ACTIVE";

    /**
     * Set once this reservation's own position is actually created and saved -- before that,
     * null means "this ACTIVE reservation exists but hasn't been linked to a real position
     * document yet," a meaningful state reconcile() checks for directly instead of inferring it
     * from timing alone (reconcile() derives the aggregate counter from actual OPEN positions,
     * and without this field an ACTIVE reservation whose position hadn't been persisted yet
     * would be silently excluded, understating real exposure). See
     * ExposureReservationService.reconcile's own javadoc for exactly how this is used.
     */
    private String positionId;

    /**
     * The id of the execution attempt that created this reservation, stored at PENDING-creation
     * time, since AutoTradeService already creates a real ExecutionContext with its own
     * executionId before ever calling reserve(). This lets reconcile()'s own stale-PENDING
     * cleanup look up that execution's own real, durable progress before deciding whether
     * deleting this record is safe: an execution that never reached the exchange can be cleaned
     * up safely, one that may have already reached it must not be silently deleted. (There is
     * no Mongo multi-document transaction across the whole reserve() sequence, since reserve()
     * also runs for TESTNET/PAPER credentials with no guaranteed replica-set support, so this
     * link is what makes that proof possible without one.)
     */
    private String executionId;

    private Instant createdAt = Instant.now();
    private Instant releasedAt;
}
