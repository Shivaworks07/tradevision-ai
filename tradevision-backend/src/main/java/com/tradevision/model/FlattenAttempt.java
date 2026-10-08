package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A minimal, durable, deliberately dependency-light record of flatten intent, written directly
 * via mongoTemplate.insert BEFORE the real market sell is submitted — independent of whether
 * the full OMS Order setup succeeds or fails, since an emergency flatten must proceed with the
 * real sell even if OMS Order creation fails (a naked position left unflattened is more
 * dangerous than an unrecorded emergency sell). Its only job is to durably record "this
 * specific clientOrderId was about to be submitted for this position" — recovery can query the
 * broker directly by this exact id even when the OMS Order record itself was never created.
 */
@Data @NoArgsConstructor
@Document(collection = "flatten_attempts")
public class FlattenAttempt {
    @Id
    private String id;

    @Indexed
    private String positionId;
    private int attempt;
    /** The exact clientOrderId submitted to the exchange for this attempt -- recovery's own
     *  deterministic lookup key, independent of whether the OMS Order record exists. */
    @Indexed
    private String clientOrderId;
    private BigDecimal quantity;
    private String symbol;
    private String credentialId;
    private LocalDateTime createdAt = LocalDateTime.now();
}
