package com.tradevision.service.broker.dto;

import java.math.BigDecimal;

/**
 * One open order as reported by the exchange. Carries orderId so a caller can actually cancel
 * the order through BrokerAdapter.cancelOrder(), which requires the broker's own orderId rather
 * than just a symbol/side/status/quantity/price description.
 *
 * Also carries orderListId: an OCO leg (a stop-loss or take-profit order placed as part of a
 * Binance OCO order list) is recorded on this application's OMS side under the OCO's
 * orderListId, via Position.ocoOrderListId, not as a standalone Order keyed by that leg's own
 * orderId. Binance's /api/v3/openOrders response includes orderListId directly on every entry
 * (-1 when the order is not part of any list), so ownership filtering that matches an open order
 * back to the position that owns it needs to check orderListId as well as orderId -- otherwise
 * another still-open position's own protective OCO legs on the same symbol would look
 * untracked and risk being cancelled along with genuine stray orders.
 */
public record OpenOrderInfo(String orderId, String orderListId, String symbol, String side, String status, BigDecimal quantity, BigDecimal price) {}
