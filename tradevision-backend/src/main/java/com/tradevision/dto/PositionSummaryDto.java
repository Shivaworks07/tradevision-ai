package com.tradevision.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read model for the position dashboard, so a user can see what the bot currently holds without
 * checking Binance directly. Assembled server-side from a Position plus its linked entry
 * order's recorded SL/TP, rather than making the frontend piece together two fetches and a join.
 *
 * protectedByOco requires protectedQuantity to cover the position's full real quantity, not
 * merely that an OCO id is present -- an OCO can cover only part of a position after exchange
 * step-size rounding, so a looser check could show "protected" on a position with a real,
 * meaningful naked residual. The raw protectedQuantity is exposed alongside it so the frontend
 * can show "partially protected" instead of a blunt yes/no.
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
    boolean protectedByOco,       // true only when protectedQuantity actually covers the position's full real quantity
    BigDecimal protectedQuantity, // how much of `quantity` the OCO actually covers; null or less than quantity means a real, meaningful gap
    // "FULL"/"DUST_RESIDUAL"/"PARTIAL"/"UNPROTECTED"/"N/A" (closed positions) -- a server-computed
    // classification using the exchange's real minQty, so the frontend doesn't have to infer
    // whether an unprotected residual is dust or meaningful without knowing that minQty itself.
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
