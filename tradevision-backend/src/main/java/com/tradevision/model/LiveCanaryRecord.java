package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The durable, queryable result of a LIVE canary trade attempt — a real order, sized at this
 * symbol's own exchange-reported minimum notional (never a user-supplied quantity, see
 * LiveCanaryService.startCanary's own javadoc for why), placed to prove end to end that the
 * whole live pipeline — adapter auth, order placement, OMS transitions, the reconciliation
 * pass that turns a fill into a Position, and real OCO protection — genuinely works for this
 * exact credential before autonomous trading is allowed to start sending LIVE orders
 * unsupervised.
 *
 * Deliberately a separate collection/service from Position/PositionMonitorService, not a new
 * Position "type" flag: a canary attempt follows a narrower, purpose-built lifecycle (PENDING
 * -> PASSED/FAILED, nothing else), reusing this application's authoritative OMS/Position
 * machinery (OrderService, PositionMonitorService's reconciliation, PositionSafetyService)
 * unmodified to actually perform and verify the canary order. This record exists only to track
 * the attempt itself, letting authorizeLiveAutoTrade ask "has this credential proven itself
 * with a real order recently?" without re-deriving that from Position/Order history each time.
 */
@Data @NoArgsConstructor
@Document(collection = "live_canary_records")
public class LiveCanaryRecord {
    @Id private String id;

    @Indexed private String userId;
    @Indexed private String credentialId;
    private BrokerType broker;
    private String symbol;
    /** The minimum-notional quantity this attempt actually placed -- see startCanary's own javadoc. */
    private BigDecimal quantity;

    /** The OMS Order (see Order.java) created for this canary's real entry order -- this record's
     *  own status is derived from that order's/the resulting Position's real outcome, never
     *  tracked independently of the authoritative OMS/Position state. */
    private String orderId;
    /** The broker-assigned order id for the canary's entry order, once known -- used to find the
     *  Position PositionMonitorService's own reconciliation creates from this fill, the same
     *  lookup pattern as PositionRepository.findByCredentialIdAndSymbolAndEntryOrderId. */
    private String entryOrderId;
    /** Set once the resulting Position is found by the reconciliation sweep below. */
    private String positionId;

    /** PENDING (order placed, awaiting the existing reconciliation pipeline to create+protect a
     *  Position from its fill) -> PASSED (a Position was found, fully protected by a real OCO) or
     *  FAILED (timed out without that happening, or the order itself was rejected/failed). */
    private String status = "PENDING";

    private LocalDateTime startedAt = LocalDateTime.now();
    private LocalDateTime completedAt;
    private String failureReason;
}
