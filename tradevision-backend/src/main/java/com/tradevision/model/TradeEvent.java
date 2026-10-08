package com.tradevision.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * An append-only, immutable timeline of order lifecycle events — a single queryable record of
 * every step a given order went through, distinct from the free-text, per-service audit logs
 * (BrokerCredentialService.audit) and from FillLedger (which covers fills specifically).
 *
 * SCOPE: this covers the order lifecycle specifically -- wired into OrderService's own
 * transition() method, the one chokepoint every order status change passes through regardless
 * of which calling method triggered it. It covers ORDER_CREATED through every state transition
 * (ACKNOWLEDGED, FILLED, CANCELLED, etc.) for every order this codebase places -- entry, OCO,
 * emergency-flatten. It does not cover the earlier signal-generation and risk-approval stages
 * (TradeCallRecord.signalStatus tracks that lifecycle separately, see SignalStatus's own
 * javadoc, just not as an append-only event stream), and does not cover
 * POSITION_CLOSED/PROTECTION_TRIGGERED as distinct event types (Position's own status field and
 * PositionMonitorService's own audit calls cover that).
 *
 * Immutability: @Getter (not @Data) means no field has a public setter for genuine event
 * content, with a single, explicit @Setter kept only on the id field, since Spring Data
 * MongoDB needs to populate the generated _id back onto this object after insert. Every field
 * the constructor sets is unsettable afterward, matching this being an append-only ledger.
 */
@Getter @NoArgsConstructor
@Document(collection = "trade_events")
@CompoundIndexes({
    @CompoundIndex(name = "order_events_by_time", def = "{'orderId':1,'occurredAt':1}"),
    @CompoundIndex(name = "position_events_by_time", def = "{'positionId':1,'occurredAt':1}")
})
public class TradeEvent {
    @Id
    @Setter
    private String id;
    // These fields are deliberately NOT declared `final`, even though every one of them is
    // only ever set once, in the constructor below. Spring Data MongoDB reads a document back
    // from the database by constructing via the no-args constructor and then setting each
    // field directly via reflection -- a mechanism that behaves unreliably against `final`
    // fields on newer JDK versions. @Getter alone (no @Data, no setters) already delivers what
    // this class needs -- no public mutation API for event content -- without that risk.
    private String userId;
    private String credentialId;
    private String positionId;
    private String orderId;
    private String signalId;
    private String symbol;
    /** ORDER_CREATED, ORDER_RISK_ACCEPTED, ORDER_SUBMITTING, ORDER_ACKNOWLEDGED, ORDER_FILLED,
     *  ORDER_PARTIALLY_FILLED, ORDER_REJECTED, ORDER_CANCEL_REQUESTED, ORDER_CANCELLED,
     *  ORDER_UNKNOWN, ORDER_SUBMISSION_FAILED -- see OrderStatus's own values, which this
     *  mirrors one-to-one for every transition() call. */
    private String eventType;
    private String detail;
    private LocalDateTime occurredAt = LocalDateTime.now();

    public TradeEvent(String userId, String credentialId, String positionId, String orderId, String signalId,
                       String symbol, String eventType, String detail) {
        this.userId = userId;
        this.credentialId = credentialId;
        this.positionId = positionId;
        this.orderId = orderId;
        this.signalId = signalId;
        this.symbol = symbol;
        this.eventType = eventType;
        this.detail = detail;
    }
}
