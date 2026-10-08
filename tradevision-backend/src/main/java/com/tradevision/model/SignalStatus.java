package com.tradevision.model;

/**
 * The decision-making lifecycle of a trade signal, from generation through risk evaluation to
 * order placement, tracked on TradeCallRecord.signalStatus — a separate axis from
 * TradeCallRecord.autoTradeEvalStatus, which tracks whether dispatch/evaluation has been
 * durably claimed and processed (a crash-recovery concept), not what decision was reached.
 *
 * SCOPE:
 * - There is no separate EXECUTING state distinct from ORDER_PENDING — order placement is a
 *   single synchronous call (BrokerAdapter.placeOrder), with no separate "submitted, awaiting
 *   acknowledgment" window worth its own signal-level state on top of what OrderStatus already
 *   tracks at the order level. ORDER_PENDING covers both.
 * - EXPIRED and CANCELLED are reserved values not currently set by anything in this codebase —
 *   there is no concept yet of a signal timing out unevaluated, or of cancelling a signal
 *   before it's acted on.
 * - One signal can be evaluated against multiple risk profiles (a symbol watched by more than
 *   one auto-trade-enabled profile); this status tracks the furthest stage reached by any one
 *   profile's evaluation, not a full per-profile breakdown.
 */
public enum SignalStatus {
    GENERATED,
    VALIDATING,
    RISK_REJECTED,
    APPROVED,
    ORDER_PENDING,
    EXECUTED,
    /** Reserved for future use — nothing in this codebase currently sets this. */
    EXPIRED,
    /** Reserved for future use — nothing in this codebase currently sets this. */
    CANCELLED
}
