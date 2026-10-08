package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A single document with a fixed, well-known id that guarantees at most one user can ever be
 * promoted to the first admin, across any number of concurrent requests or application
 * instances. MongoDB's own _id field carries a natural unique index, so inserting a second
 * document with the same _id fails atomically at the database level, not in application code
 * — only one concurrent promotion attempt can ever win this insert, every other one gets a
 * DuplicateKeyException and is refused.
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
