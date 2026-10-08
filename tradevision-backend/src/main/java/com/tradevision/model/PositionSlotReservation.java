package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A distributed atomic counter, backed by MongoDB's single-document atomicity, that caps how
 * many concurrent open positions a credential may hold — a cross-instance guard, unlike an
 * in-process lock which only ever protects a single JVM. One document per credential.
 * reservedCount is only ever changed via PositionSlotReservationService's atomic findAndModify
 * operations, never read-then-write from application code, which would reintroduce the exact
 * race this exists to close.
 */
@Data @NoArgsConstructor
@Document(collection = "position_slot_reservations")
public class PositionSlotReservation {
    @Id private String id;
    @Indexed(unique = true) private String credentialId;
    private int reservedCount = 0;
    /**
     * Set on every successful reserve(). reconcile()'s self-healing overwrite skips this
     * document entirely while a reservation happened within its grace window, so a reservation
     * made seconds ago (order still in flight, Position not saved yet) can't be blown away by
     * a reconciliation pass that only knows about confirmed OPEN positions.
     */
    private java.time.Instant lastReservedAt;
}
