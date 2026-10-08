package com.tradevision.service.broker.dto;

import java.math.BigDecimal;

/**
 * Result of placing a stop-loss/take-profit OCO pair.
 *
 * verificationUncertain distinguishes a confirmed failure from a genuinely unknown outcome: a
 * recovery query can fail to confirm either way (a network timeout, say), and treating that the
 * same as "confirmed no OCO exists" could lead a caller to emergency-flatten a position that may
 * still have a real, active OCO on the exchange. true means this result's success=false must not
 * be read as "confirmed no protection exists," only as "this specific attempt did not confirm
 * success." Defaults to false via every existing constructor below, so a genuinely confirmed
 * failure/success is unaffected.
 */
public record OcoOrderResult(boolean success, String ocoOrderListId, String rawResponse, String errorMessage,
                              // The actual exchange-rounded quantity this OCO was placed for --
                              // rounding down to the symbol's step size can differ from the
                              // quantity requested, and the caller needs this honest number to
                              // know exactly how much of the position this specific OCO actually
                              // protects, rather than assuming it covers everything asked for.
                              // Null for a failed placement (nothing was actually placed for any
                              // quantity) or for legacy callers using the 4-arg constructor below.
                              BigDecimal actualProtectedQuantity,
                              boolean verificationUncertain) {
    public static OcoOrderResult failure(String errorMessage, String rawResponse) {
        return new OcoOrderResult(false, null, rawResponse, errorMessage, null, false);
    }

    /**
     * Factory for the "genuinely don't know" state -- success is false (nothing here should be
     * trusted as an active OCO), but verificationUncertain=true tells the caller this is not the
     * same as a confirmed absence.
     */
    public static OcoOrderResult uncertain(String errorMessage, String rawResponse) {
        return new OcoOrderResult(false, null, rawResponse, errorMessage, null, true);
    }

    /** Convenience constructor for callers that don't have the actual placed quantity in hand. */
    public OcoOrderResult(boolean success, String ocoOrderListId, String rawResponse, String errorMessage) {
        this(success, ocoOrderListId, rawResponse, errorMessage, null, false);
    }

    /** Convenience constructor for callers that have the actual placed quantity but predate
     *  verificationUncertain -- defaults to false (a confirmed result), unaffected by this fix. */
    public OcoOrderResult(boolean success, String ocoOrderListId, String rawResponse, String errorMessage,
                           BigDecimal actualProtectedQuantity) {
        this(success, ocoOrderListId, rawResponse, errorMessage, actualProtectedQuantity, false);
    }
}
