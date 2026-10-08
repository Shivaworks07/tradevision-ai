package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The durable record a simulated OCO (one-cancels-the-other take-profit/stop-loss pair) needs.
 * Since PAPER mode never actually places a real OCO on any exchange, this is the only place its
 * trigger prices and state exist at all. Deliberately as minimal as this codebase's real
 * OrphanedOco model, since the simulation only needs to answer one question on every check: has
 * the live market price crossed either trigger yet.
 */
@Data @NoArgsConstructor
@Document(collection = "paper_ocos")
public class PaperOco {
    @Id
    private String id;
    private String userId;
    private String credentialId;
    private String symbol;
    private String side; // the ORIGINAL position's side (LONG/SHORT) -- determines which direction counts as "triggered" for each leg
    private BigDecimal quantity;
    private BigDecimal takeProfitPrice;
    private BigDecimal stopLossPrice;
    /**
     * Kept purely for interface-contract consistency with BinanceBrokerAdapter's
     * getOcoStatusByClientOrderId -- PAPER mode has no real exchange divergence to recover from
     * (its own database record is the ground truth), but every BrokerAdapter implementation
     * should honor the same interface contract uniformly.
     */
    private String listClientOrderId;
    /** NEW (still active, neither trigger crossed yet), TP_FILLED, SL_FILLED, or CANCELLED. */
    private String status = "NEW";
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime resolvedAt;
    /**
     * The actual market price observed at the moment this OCO resolved -- null until resolved,
     * exactly like resolvedAt above. Recording the real observed price rather than the stored
     * trigger price lets the simulated fill reflect gap/slippage past the trigger, the way a
     * real stop-loss can gap past its trigger in a fast-moving market.
     */
    private java.math.BigDecimal resolvedPrice;
}
