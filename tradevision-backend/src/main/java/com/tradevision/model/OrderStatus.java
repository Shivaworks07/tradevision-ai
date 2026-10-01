package com.tradevision.model;

/**
 * Formal order lifecycle, per the review's own spec — "OMS #4" of the autonomous-engine work.
 *
 * UNKNOWN is deliberately first-class, not a subtype of FAILED: a network failure between
 * TradeVision and Binance means we genuinely don't know whether the exchange accepted the order.
 * Treating that as FAILED and moving on risks a silent double-order on retry; treating it as
 * UNKNOWN forces an explicit recovery step (query the broker, then transition to whatever's
 * actually true) before anything else touches this order.
 */
public enum OrderStatus {
    CREATED,
    RISK_ACCEPTED,
    RISK_REJECTED,
    SUBMITTING,
    SUBMISSION_FAILED,
    /** Genuinely unknown broker-side outcome — see this enum's own javadoc. Never means "treat as failed". */
    UNKNOWN,
    ACKNOWLEDGED,
    PARTIALLY_FILLED,
    FILLED,
    CANCEL_PENDING,
    CANCELLED,
    EXPIRED,
    REJECTED,
    /** UNKNOWN resolved to "still can't tell" after a recovery attempt — needs a human, not another automated retry. */
    RECONCILIATION_REQUIRED
}
