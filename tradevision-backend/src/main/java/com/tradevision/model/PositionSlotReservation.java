package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Review items #13/#14 (this doc's numbering): the actual, correct fix for the multi-instance
 * concurrent-trade race — a distributed atomic counter backed by MongoDB's single-document
 * atomicity, not an in-process lock (which the app already has, and which only ever protected
 * one JVM). One document per credential. reservedCount is only ever changed via
 * PositionSlotReservationService's atomic findAndModify operations — never read-then-write from
 * application code, which would reintroduce exactly the race this exists to close.
 */
@Data @NoArgsConstructor
@Document(collection = "position_slot_reservations")
public class PositionSlotReservation {
    @Id private String id;
    @Indexed(unique = true) private String credentialId;
    private int reservedCount = 0;
    /**
     * Review finding ("P0 #4" — "slot reconciliation can erase an in-flight reservation"): set
     * on every successful reserve(). reconcile()'s self-healing overwrite skips this document
     * entirely while a reservation happened within its grace window, so a reservation made
     * seconds ago (order still in flight, Position not saved yet) can't be blown away by a
     * reconciliation pass that only knows about confirmed OPEN positions.
     */
    private java.time.Instant lastReservedAt;
}
