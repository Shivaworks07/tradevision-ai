package com.tradevision.service.broker.dto;

import java.math.BigDecimal;

/**
 * Result of placing a stop-loss/take-profit OCO pair.
 *
 * Review finding ("OCO recovery still returns null for some verification failures" -- external
 * review, twenty-fourth pass, P1, confirmed real by direct inspection before this fix:
 * BinanceBrokerAdapter's own recovery path (tryRecoverOcoByListClientOrderId) returned null both
 * when Binance positively confirmed no OCO exists AND when the verification query itself failed
 * -- its one caller treated both identically as "ordinary failed placement," meaning a genuinely
 * unknown outcome (a network timeout during recovery, for instance) could lead a caller further
 * up to emergency-flatten a position that may still have a real, active OCO on the exchange):
 * verificationUncertain is the actual, structural fix -- true means this result's own success=
 * false must NOT be read as "confirmed no protection exists," only as "this specific attempt
 * did not confirm success." Defaults to false via every existing constructor below, so a
 * genuinely confirmed failure/success is unaffected -- this is additive, not a redesign of the
 * existing confirmed states.
 */
public record OcoOrderResult(boolean success, String ocoOrderListId, String rawResponse, String errorMessage,
                              // Review finding ("OCO quantity can be smaller than the actual
                              // position because of base-asset fees" -- P0, full context in
                              // Position.protectedQuantity's own field javadoc): the actual
                              // exchange-rounded quantity this OCO was placed for -- rounding
                              // DOWN to the symbol's own step size can genuinely differ from the
                              // quantity requested, and the caller needs this real, honest
                              // number to know exactly how much of the position this specific
                              // OCO actually protects, not assume it covers everything asked
                              // for. Null for a failed placement (nothing was actually placed
                              // for any quantity) or for legacy callers using the 4-arg
                              // constructor below.
                              BigDecimal actualProtectedQuantity,
                              boolean verificationUncertain) {
    public static OcoOrderResult failure(String errorMessage, String rawResponse) {
        return new OcoOrderResult(false, null, rawResponse, errorMessage, null, false);
    }

    /**
     * Review finding, same context as this record's own class javadoc: the actual factory for
     * the new "genuinely don't know" state -- success is false (nothing here should be trusted
     * as an active OCO), but verificationUncertain=true tells the caller this is NOT the same as
     * a confirmed absence.
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
