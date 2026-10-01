package com.tradevision.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Review finding ("UI has no real Position/Execution dashboard" — "a user should never have to
 * open Binance manually to discover: does my bot currently own something?"): the read model for
 * that dashboard. Assembled server-side (Position + its linked entry order's recorded SL/TP)
 * rather than making the frontend piece together two separate fetches and a join.
 *
 * UPDATE ("Position protection status is not yet a first-class invariant" -- external review,
 * P1): confirmed real and fixed here -- protectedByOco used to mean only "ocoOrderListId is
 * non-null and the position is still OPEN," which is true even when the OCO only covers PART of
 * the position (a known, real scenario after exchange step-size rounding -- see
 * Position.protectedQuantity's own field javadoc). A user reading this dashboard could see
 * "protected" on a position with a real, meaningful naked residual. protectedByOco now requires
 * protectedQuantity to actually cover the position's full real quantity, and the raw
 * protectedQuantity value is exposed alongside it so the frontend isn't limited to a blunt
 * yes/no when the true state is "partially protected."
 */
public record PositionSummaryDto(
    String id,
    String credentialId,
    String symbol,
    String mode,
    String status,               // OPEN, CLOSED, CLOSED_UNVERIFIED_PNL, NAKED_FLATTENED
    BigDecimal quantity,
    BigDecimal closedQuantity,
    BigDecimal avgEntryPrice,
    boolean avgEntryPriceUnverified,
    BigDecimal stopLossPrice,    // from the linked entry order, if recorded
    BigDecimal takeProfitPrice,  // from the linked entry order, if recorded
    boolean protectedByOco,      // Review finding ("Position protection status is not yet a first-class invariant" -- external review, full context in this DTO's own updated header javadoc): now REQUIRES protectedQuantity to actually cover the position's real quantity, not merely "an OCO id happens to be present" -- see toSummary's own updated comment for what changed and why.
    BigDecimal protectedQuantity, // how much of `quantity` the OCO actually covers, per this position's own last-known protectedQuantity -- null or less than quantity means a real, meaningful gap the dashboard's own boolean above would otherwise hide entirely. The frontend can show "partially protected" instead of a flat yes/no.
    // Review finding ("OCO protection logic is better, but dust classification needs one more
    // invariant" -- external review, second pass): "FULL"/"DUST_RESIDUAL"/"PARTIAL"/
    // "UNPROTECTED"/"N/A" (closed positions) -- an explicit, server-computed classification
    // using the exchange's own real minQty (see toSummary's own updated comment for the exact
    // logic and its honest failure-mode default), rather than making the frontend infer "is
    // this dust or meaningful" from a raw quantity comparison it has no minQty to judge against.
    String protectionStatus,
    BigDecimal exitPrice,
    BigDecimal realizedPnlQuote,
    BigDecimal entryFeeQuote,
    BigDecimal exitFeeQuote,
    String closeReason,
    String triggerSource,
    LocalDateTime openedAt,
    LocalDateTime closedAt
) {}
