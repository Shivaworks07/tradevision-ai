package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Review finding (P1 #7 — "Admin bootstrap has a race"): confirmed real — countByRole("ADMIN")
 * was a plain read, checked before ANY synchronization, and the synchronized(this) block that
 * did exist only guarded the rate-limit counter, not the count-check-then-save sequence itself.
 * Two concurrent requests could both read count=0 and both successfully promote a different
 * user, producing two first admins in a single-instance deployment (and the JVM-local
 * synchronized block wouldn't even help across multiple instances regardless).
 *
 * This is the review's own suggested fix: "a dedicated bootstrap lock/document. Not a JVM-only
 * guard." A single document with a FIXED, well-known ID — MongoDB's own _id field carries a
 * natural unique index, so inserting a second document with the same _id fails atomically at
 * the database level, not in application code. Only one concurrent request can ever win this
 * insert; every other one gets a DuplicateKeyException and is refused, regardless of how many
 * application instances are racing for it.
 */
@Data @NoArgsConstructor
@Document(collection = "bootstrap_locks")
public class BootstrapLock {
    @Id
    private String id = "admin-bootstrap"; // fixed, well-known value — the whole point is that only ONE document with this exact id can ever exist
    private String promotedUserId;
    private LocalDateTime createdAt = LocalDateTime.now();

    public BootstrapLock(String promotedUserId) {
        this.promotedUserId = promotedUserId;
    }
}
