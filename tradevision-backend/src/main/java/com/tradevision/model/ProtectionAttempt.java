package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Review finding ("OCO persistence still has an unavoidable crash window" -- external review,
 * nineteenth pass, P1, confirmed real by direct inspection before this was built: the earlier
 * fix this session (createOrphanForOco) closed the gap between the exchange OCO call succeeding
 * and this application's own first durable trace of it -- but the orphan is still created AFTER
 * that exchange call, not before. A crash strictly between "about to call placeExitOco" and "the
 * exchange responded" leaves this application with genuinely no record at all, and more
 * importantly no way to even know a real OCO might exist on the exchange under a
 * client-generated id this process never persisted anywhere).
 *
 * The actual fix: this record is created and saved BEFORE the exchange call is ever made, with
 * status=SUBMITTING and the SAME deterministic listClientOrderId that will be sent to the
 * exchange. If the process crashes at any point from here through the exchange call itself, this
 * record survives with that client id -- the one piece of information that lets a recovery pass
 * ask Binance directly "did an OCO with this exact client id ever get accepted?" via
 * BrokerAdapter.getOcoStatusByClientOrderId, regardless of whether this process ever received or
 * recorded a response.
 *
 * Status values: SUBMITTING (exchange call not yet confirmed either way), ACTIVE (exchange
 * confirmed success -- this record becomes redundant once the position/OrphanedOco durably
 * reflect it, same "delete the now-redundant safety net" principle as OrphanedOco itself),
 * FAILED (exchange confirmed the call did not result in an active OCO).
 */
@Data @NoArgsConstructor
@Document(collection = "protection_attempts")
public class ProtectionAttempt {
    @Id
    private String id;
    private String userId;
    private String credentialId;
    private String positionId;
    private String symbol;
    private String listClientOrderId;
    private BigDecimal quantity;
    private BigDecimal takeProfitPrice;
    private BigDecimal stopLossPrice;
    private String status = "SUBMITTING";
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime resolvedAt;
}
