package com.tradevision.model;

/**
 * Review finding ("#3 — Signal engine" — "I'd introduce: Signal with: GENERATED, VALIDATING,
 * RISK_REJECTED, APPROVED, ORDER_PENDING, EXECUTING, EXECUTED, EXPIRED, CANCELLED. Then: Signal
 * -> Decision -> Order becomes clearly separated"): this is that lifecycle, tracked on
 * TradeCallRecord.signalStatus.
 *
 * HONEST SCOPE:
 * - EXECUTING (as distinct from ORDER_PENDING) is not a real state in this codebase — order
 *   placement is a single synchronous call (BrokerAdapter.placeOrder), with no separate
 *   "submitted, awaiting acknowledgment" window worth its own signal-level state on top of what
 *   OrderStatus (the OMS, #4) already tracks at the order level. ORDER_PENDING covers both.
 * - EXPIRED and CANCELLED are NOT wired to anything — this codebase has no concept of a signal
 *   timing out unevaluated (AutoTradeRecoveryService recovers a STUCK evaluation, it doesn't
 *   expire an old one) or of a user/system cancelling a signal before it's acted on. Both
 *   states exist in this enum for completeness against the review's own spec, but nothing in
 *   this pass ever sets them — a real gap, not a silent one.
 * - One signal can be evaluated against MULTIPLE risk profiles (a symbol watched by more than
 *   one auto-trade-enabled profile) — this status tracks the FURTHEST stage reached by ANY ONE
 *   profile's evaluation, not a full per-profile breakdown. For the common case (one profile per
 *   signal) this is exact; for the multi-profile case it's an honest simplification, not a
 *   fabricated precision this pass doesn't actually have.
 */
public enum SignalStatus {
    GENERATED,
    VALIDATING,
    RISK_REJECTED,
    APPROVED,
    ORDER_PENDING,
    EXECUTED,
    /** Reserved — nothing in this codebase currently sets this. See this enum's own javadoc. */
    EXPIRED,
    /** Reserved — nothing in this codebase currently sets this. See this enum's own javadoc. */
    CANCELLED
}
