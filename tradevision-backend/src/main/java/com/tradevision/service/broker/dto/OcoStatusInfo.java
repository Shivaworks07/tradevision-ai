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
     */
    public record Leg(String orderId, String side, String type, String status, BigDecimal price, BigDecimal executedQty, BigDecimal origQty) {}
}
