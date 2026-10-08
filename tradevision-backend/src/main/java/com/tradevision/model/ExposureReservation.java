package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks exposure already committed against a credential's risk caps — total quote-currency
 * exposure, per-symbol exposure, and per correlation-group exposure — so that concurrent order
 * placements reserve against the same cap atomically (via MongoDB's single-document
 * atomicity, one document per credential) instead of each reading current Position sums and
 * deciding independently, which would let several concurrent orders each pass a cap check that
 * only holds when checked one at a time.
 *
 * Reservation here is aggregate (running totals per credential/symbol/group), not tied to an
 * individual reservation record's identity — release() derives the amount to subtract from a
 * position's own recorded fields (avgEntryPrice * closedQuantity) rather than referencing an
 * exact reservation by id. This can drift slightly from price movement, quantity correction,
 * partial fills, and fees; periodic reconciliation self-heals that drift, which is weaker than
 * exact reservation-by-id tracking but considerably simpler, and the self-healing reconcile()
 * pass runs on the same periodic sweep as the rest of this credential's reconciliation.
 *
 * Correlation-group exposure caps (reservedGroupExposure) are reserved atomically through the
 * same findAndModify pattern as total/symbol exposure, with the same rollback-on-failure
 * discipline.
 */
@Data @NoArgsConstructor
@Document(collection = "exposure_reservations")
public class ExposureReservation {
    @Id private String id;
    @Indexed(unique = true) private String credentialId;
    // Spring Data's ad-hoc Update/Query mapping stores a BigDecimal as a String unless the
    // field is explicitly typed DECIMAL128 here -- this annotation is required in addition to
    // (not instead of) converting the raw value to org.bson.types.Decimal128 before it goes
    // into an Update/Query, or MongoDB's $inc rejects it as non-numeric.
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal reservedTotalExposureQuote = BigDecimal.ZERO;
    private Map<String, BigDecimal> reservedSymbolExposure = new HashMap<>();
    // Exposure reserved per correlation group (e.g. "L1-majors" -> amount), using the same
    // atomic findAndModify pattern as total/symbol exposure above.
    private Map<String, BigDecimal> reservedGroupExposure = new HashMap<>();
    private Instant lastReservedAt;
}
