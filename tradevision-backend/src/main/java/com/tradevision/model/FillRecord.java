package com.tradevision.model;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * An immutable, append-only ledger record of one actual exchange trade — the authoritative
 * source for reconstructing where a position's quantity came from, fill by fill, without
 * relying on inference from order-level state alone.
 *
 * SCOPE:
 * - tradeId/executedAt are genuinely nullable, not always available -- see Fill DTO's own
 *   javadoc for exactly what Binance does and doesn't reliably return.
 * - quoteCommission is set only when the commission was actually paid in the quote asset -- a
 *   BNB-fee-discount trade leaves this null rather than guessed.
 * - isAggregate distinguishes a genuine per-trade record (one real fill, from myTrades or an
 *   order response's own fills array) from a synthesized single record built from order-level
 *   executedQty/fillPrice when no per-fill data was available at all -- pretending an aggregate
 *   is a real single fill would misrepresent what's actually known.
 * - Position is not yet derived FROM this ledger -- Position is still updated directly
 *   elsewhere in this codebase. This ledger exists and is populated, but isn't yet the source
 *   of truth for Position quantity.
 *
 * positionId is the one identifier every fill type shares, since a position spans an entry
 * order and however many exit orders eventually close it -- orderId alone is not safe for
 * reconstructing a whole position's fill history, since it's inconsistent across paths (the
 * entry path uses the OMS Order.id, exit paths use the broker's own order id, since
 * OCO/emergency-flatten remain outside OMS). orderId is kept regardless, as a still-meaningful
 * reference on its own.
 *
 * Immutability: @Getter/@ToString/@EqualsAndHashCode (not @Data) means no field has a public
 * setter, except id, which keeps one hand-written setter (setId, below) because Spring Data
 * MongoDB's auto-generated-id mechanism needs it. This is Java-level immutability only (no way
 * to call fillRecord.setSymbol(...) on an already-built object) -- it does not by itself stop a
 * caller from calling fillRecordRepo.save() again on the same id to overwrite it, or calling
 * delete(); no REST controller in this codebase exposes an update or delete endpoint for
 * FillRecord, which is what makes the ledger effectively append-only in practice. A genuine
 * database-level append-only guarantee (a Mongo collection validator rejecting update/delete
 * outright) would be further infrastructure, not a code change.
 */
@Getter @ToString @EqualsAndHashCode @NoArgsConstructor @AllArgsConstructor
@Document(collection = "fill_records")
public class FillRecord {
    @Id private String id;
    /** Spring Data MongoDB needs this to populate the generated id after insert -- see this
     *  class's javadoc for why every other field has no setter at all. */
    public void setId(String id) { this.id = id; }

    @Indexed private String orderId;
    // The OMS Order.id for the entry path, where it must stay exactly that since
    // PositionLedgerService.reconcileAgainstLedger looks fills up by it. For every exit path
    // (OCO, late-fill, emergency-flatten, which remain outside OMS) this already holds a real
    // broker-side order id. brokerOrderId below fills the one gap this leaves: a real
    // broker-side order id for the entry path specifically, where orderId can't carry it
    // without breaking that reconciliation lookup. It is left null on exit-path records, where
    // orderId already holds the broker-side id and a duplicate would be redundant.
    @Indexed private String brokerOrderId;
    @Indexed private String positionId;
    private String brokerTradeId;
    /** Unique -- see this class's javadoc for how it's derived. */
    private String fillIdentity;
    /**
     * "STRONG" when a genuine brokerTradeId backs fillIdentity, "WEAK" when fillIdentity had to
     * fall back to an approximate composite (see FillLedgerService.computeFillIdentity for what
     * that fallback includes) because the broker omitted a trade id -- a fallback composite can
     * in principle collide across two identical partial fills. This class doesn't act
     * differently based on this value itself; it's a signal for reconciliation logic reading
     * the ledger to treat WEAK-identity records more conservatively.
     */
    private String identityConfidence; // "STRONG" or "WEAK" — see above

    private String userId;
    private String credentialId;
    private String symbol;
    private String side;

    private BigDecimal price;
    private BigDecimal quantity;

    private BigDecimal commissionAmount;
    private String commissionAsset;
    /** Only set when commissionAsset matches the symbol's quote asset — null otherwise, never guessed. */
    private BigDecimal quoteCommission;

    /** When the broker reports the trade actually happened — null if the broker's response didn't include it. */
    private LocalDateTime executedAt;
    /** When TradeVision itself processed and persisted this record. */
    private LocalDateTime receivedAt = LocalDateTime.now();

    /** True if this is a synthesized single record from order-level data, not a genuine per-trade fill. */
    private boolean aggregate;
}
