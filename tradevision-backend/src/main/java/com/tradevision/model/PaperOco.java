package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Review finding ("No pure paper-trading mode with full isolation" -- external review,
 * eighteenth pass, P0, full context in PaperBrokerAdapter's own class javadoc): the durable
 * record a simulated OCO needs -- since PAPER mode never actually places a real OCO on any
 * exchange, this is the ONLY place its trigger prices/state exist at all. Deliberately as
 * minimal as this codebase's own real OrphanedOco model, since the simulation only needs to
 * answer one question on every check: has the live market price crossed either trigger yet.
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
     * Review finding ("OCO persistence still has an unavoidable crash window" -- external
     * review, nineteenth pass, P1, full context in ProtectionAttempt's own class javadoc): kept
     * here purely for interface-contract consistency with BinanceBrokerAdapter's own new
     * getOcoStatusByClientOrderId -- PAPER mode has no real exchange divergence to recover from
     * (its own database record IS the ground truth, unlike a real exchange call this
     * application's own process could crash between sending and recording), but every
     * BrokerAdapter implementation should honor the same interface contract uniformly.
     */
    private String listClientOrderId;
    /** NEW (still active, neither trigger crossed yet), TP_FILLED, SL_FILLED, or CANCELLED. */
    private String status = "NEW";
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime resolvedAt;
    /**
     * P2-4 fix ("SL fills at exact SL (no gap)" -- external review, confirmed real by direct
     * inspection: the resolved leg's own reported fill price used to be the STORED trigger price
     * (takeProfitPrice/stopLossPrice) itself, regardless of what the real market price actually
     * was at the moment PaperBrokerAdapter.getOcoStatus observed it crossing that trigger -- a
     * real stop-loss can and does gap PAST its trigger price in a fast-moving market (the exact
     * scenario a stop-loss exists to protect against in the first place), so pinning the
     * simulated fill to the exact trigger price is unrealistically optimistic in exactly the
     * direction that matters most: it always understates a real stop-loss's own worst-case
     * slippage). The actual real market price observed at the moment this OCO resolved -- null
     * until resolved, exactly like resolvedAt above.
     */
    private java.math.BigDecimal resolvedPrice;
}
