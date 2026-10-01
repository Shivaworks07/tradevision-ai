package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Review finding ("Emergency flatten still allows an exchange sell without durable
 * pre-submission intent" -- external review, twenty-sixth pass, P1, confirmed real by direct
 * inspection before this fix: when the full OMS Order setup (orderService.create) fails for an
 * emergency flatten, the existing code deliberately proceeds to the real market SELL anyway --
 * correct, since a naked position left unflattened is more dangerous than an unrecorded
 * emergency sell -- but that means if the JVM then crashes between the sell succeeding and any
 * local result being recorded, recoverStuckFlattening has no Order document to look up by the
 * exact flattenClientOrderId, and falls back to the weaker account-balance heuristic (see
 * PositionMonitorService.recoverStuckFlattening's own P1-2 finding for why that's weaker)):
 * a minimal, durable, deliberately dependency-light record of flatten intent, written directly
 * via mongoTemplate.insert BEFORE the real sell, independent of whether the full OMS Order setup
 * succeeds or fails. Its only job is to durably record "this specific clientOrderId was about to
 * be submitted for this position" -- recovery can query Binance directly by this exact id even
 * when the OMS Order record itself never got created.
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
