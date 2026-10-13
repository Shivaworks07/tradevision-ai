package com.tradevision.service.broker.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Status of an OCO order list queried from the exchange. Carries orderListId -- the
 * exchange-assigned id, distinct from the client-generated id a caller may have queried by --
 * since attaching a recovered OCO to a position (atomicSetOcoPlaced) needs this exchange-assigned
 * id specifically: GET OCO by origClientOrderId -> OCO found -> get this id -> attach.
 */
public record OcoStatusInfo(String orderListId, String listStatus, List<Leg> legs, String rawResponse) {
    /**
     * One leg of an OCO order list. Carries origQty, the leg's real original order quantity --
     * what the exchange actually protects, which can differ from Position.quantity due to
     * base-asset fees, step-size rounding, or exchange quantity normalization -- distinct from
     * executedQty (how much of that has filled so far). Recovery auto-attach needs origQty, not
     * Position.quantity, to know how much this leg actually protects.
     *
     * Also carries symbol -- this leg's own exchange-confirmed trading pair, read directly from
     * the per-leg order-detail response (not merely assumed from the query parameter used to
     * fetch it). Orphaned-OCO auto-attach (PositionMonitorService.recoverOrphanedOcos) needs this
     * to verify the OCO it's about to durably bind to a position actually protects that position's
     * own symbol, rather than trusting an identity match that was never actually checked.
     */
    public record Leg(String orderId, String side, String type, String status, BigDecimal price, BigDecimal executedQty, BigDecimal origQty, String symbol) {
        /**
         * Backward-compatible 7-arg constructor for every call site built before this leg carried
         * its own symbol (PaperBrokerAdapter's legacy construction sites, and the full existing
         * test suite) -- defaults symbol to null, which auto-attach verification correctly treats
         * as "incomplete leg data" and refuses to trust, exactly like a genuinely missing field in
         * a real exchange response. Same pattern as ServerSignalEngine.Signal's own convenience
         * constructor for an identical reason.
         */
        public Leg(String orderId, String side, String type, String status, BigDecimal price, BigDecimal executedQty, BigDecimal origQty) {
            this(orderId, side, type, status, price, executedQty, origQty, null);
        }
    }
}
