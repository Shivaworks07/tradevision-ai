package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Review finding ("OCO Persistence Failure Has No Reconciliation Path" -- external review, third
 * pass, P1): atomicSetOcoPlaced() (both copies -- AutoTradeService and PositionMonitorService)
 * already correctly DETECTS a modifiedCount == 0 outcome and raises OCO_PLACED_BUT_NOT_RECORDED
 * as a critical incident -- but until this fix, that incident was the end of the story. The
 * review's own named gap: "finding and reconciling the orphaned OCO on the next pass is not
 * implemented. An OCO with a broker identifier exists on Binance but isn't durably in
 * TradeVision's database." A human-readable audit/incident message naming the ocoOrderListId
 * inline is not something a later reconciliation pass can query for -- this is the missing
 * structured record that makes it queryable, so recoverOrphanedOcos() has something concrete to
 * iterate over on every subsequent pass until each one is actually resolved, not just once
 * logged and forgotten.
 */
@Data @NoArgsConstructor
@Document(collection = "orphaned_ocos")
public class OrphanedOco {
    @Id
    private String id;
    private String userId;
    private String credentialId;
    private String positionId; // the position this OCO was originally intended to protect
    private String symbol;
    private String ocoOrderListId; // the real, broker-side identifier -- the only durable link back to the exchange's own truth
    private java.math.BigDecimal protectedQuantity; // null when the OCO covered the position's full quantity
    private LocalDateTime createdAt = LocalDateTime.now();
    private boolean resolved = false;
    private LocalDateTime resolvedAt;
    private String resolution; // human-readable outcome, set once resolved is true

    /**
     * Review finding ("An active orphan OCO is marked 'resolved' even though the exchange order
     * remains active" -- external review, fourth pass, P1, confirmed real by direct inspection
     * before any fix was attempted): "resolved" was being set to true the moment this
     * application finished PROCESSING an orphan, not the moment the actual dangerous condition
     * (a live, unattached order on the exchange) was gone -- meaning a genuinely still-active
     * orphan would never be reconsidered by any later reconciliation pass at all, since
     * recoverOrphanedOcos only ever queries resolved=false. escalated/escalatedAt are the fix:
     * a still-active orphan now stays resolved=false (so every future pass keeps re-checking its
     * real exchange-side state) while escalated=true records that this application HAS already
     * raised the incident once, so a later pass can deduplicate re-alerting rather than raising
     * a fresh critical incident every single reconciliation cycle for the same still-unresolved
     * orphan.
     */
    private boolean escalated = false;
    private LocalDateTime escalatedAt;
}
