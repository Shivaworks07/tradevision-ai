package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Audit item P0-1 ("Nothing gates autonomous LIVE trading on a real, successful live order ever
 * having been placed" -- confirmed real by direct inspection of RiskProfileService.
 * authorizeLiveAutoTrade before this fix: every one of its existing LIVE-only checks --
 * confirmation phrase, risk-limit completeness, Mongo transaction support, re-verified
 * canTrade()/key-restriction permissions -- is about whether this credential is ALLOWED to
 * trade LIVE, none of them ever actually sends one real order to Binance's live endpoint and
 * confirms the whole pipeline -- adapter auth, order placement, OMS transitions, the
 * reconciliation pass that turns a fill into a Position, and real OCO protection -- genuinely
 * works end to end for this exact credential before autonomous trading is allowed to start
 * sending LIVE orders unsupervised for the first time).
 *
 * The user's own explicit choice (via this session's AskUserQuestion) was "build it, minimum
 * notional, admin-confirmed" -- a real LIVE order, sized at this symbol's own exchange-reported
 * minimum notional (never a user-supplied quantity -- see LiveCanaryService.startCanary's own
 * javadoc for why), gated behind the same explicit-confirmation-phrase pattern already
 * established for authorizeLiveAutoTrade itself, rather than the lighter-weight "just check a
 * precondition, don't place a real order" alternative that was also offered. This record is the
 * durable, queryable result of exactly one such attempt.
 *
 * Deliberately a separate collection/service from Position/PositionMonitorService, not a new
 * Position "type" flag: a canary attempt follows a narrower, purpose-built lifecycle (PENDING ->
 * PASSED/FAILED, nothing else) and this application's authoritative OMS/Position machinery
 * (OrderService, PositionMonitorService's reconciliation, PositionSafetyService) is reused
 * as-is, unmodified, to actually perform and verify the canary order -- this record exists only
 * to track the attempt itself and let authorizeLiveAutoTrade ask "has this credential proven
 * itself with a real order recently?" without needing to re-derive that from Position/Order
 * history every time.
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
