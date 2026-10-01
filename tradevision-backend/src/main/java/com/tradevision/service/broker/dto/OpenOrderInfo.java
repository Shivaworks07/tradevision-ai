package com.tradevision.service.broker.dto;

import java.math.BigDecimal;

/**
 * P3-11 fix ("Cancel all open orders for the symbol before any flatten" -- external review,
 * second pass, re-audit, P0, full context in PositionSafetyService's own updated
 * emergencyFlatten javadoc): added orderId, the one field this record needed but never carried
 * -- without it, nothing that calls getOpenOrders() can actually cancel any of the orders it
 * returns, since BrokerAdapter.cancelOrder() requires the broker's own orderId, not just a
 * symbol/side/status/quantity/price description of the order. Purely additive (a new field on a
 * record used at exactly one construction site, BinanceBrokerAdapter.getOpenOrders), so nothing
 * that already reads the other fields is affected.
 *
 * Third re-audit fix ("The flatten can still cancel another position's stop-loss" -- external
 * review, fourth pass, item #1 of its own list, confirmed real by direct inspection: an OCO leg
 * (a stop-loss or take-profit order placed as part of a Binance OCO order list) is recorded on
 * this application's own OMS side under the OCO's orderListId, via Position.ocoOrderListId --
 * NOT as a standalone Order record keyed by that individual leg's own orderId. Binance's own
 * /api/v3/openOrders response includes orderListId directly on every entry (-1 when the order is
 * not part of any list), but this record never carried it, so PositionSafetyService's own
 * ownership check -- which only ever looked an order up BY orderId -- could never actually match
 * an OCO leg to the position that owns it, no matter which position's OMS records were checked.
 * Every other still-open position's own protective OCO legs on the same symbol looked completely
 * untracked and were cancelled right along with genuine stray orders): added so ownership
 * filtering can also match on orderListId, not just orderId.
 */
public record OpenOrderInfo(String orderId, String orderListId, String symbol, String side, String status, BigDecimal quantity, BigDecimal price) {}
