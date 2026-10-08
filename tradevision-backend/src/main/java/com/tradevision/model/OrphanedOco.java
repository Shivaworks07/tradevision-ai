package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A structured, queryable record of an OCO that was successfully placed on the broker but
 * whose placement result failed to persist back onto the owning Position — a durable trace
 * that an exchange-side protective order exists without a matching local record, so
 * recoverOrphanedOcos() has something concrete to iterate over on every reconciliation pass
 * until it is actually resolved, rather than only a one-time incident log entry.
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
     * `resolved` reflects whether the actual dangerous condition — a live, unattached order on
     * the exchange — is gone, not merely whether this application has finished processing the
     * orphan once; recoverOrphanedOcos only ever queries resolved=false, so a still-active
     * orphan must stay resolved=false for every future pass to keep re-checking its real
     * exchange-side state. escalated/escalatedAt record that an incident has already been
     * raised once for this orphan, so a later pass can deduplicate re-alerting rather than
     * raising a fresh critical incident every reconciliation cycle for the same still-
     * unresolved orphan.
     */
    private boolean escalated = false;
    private LocalDateTime escalatedAt;
}
