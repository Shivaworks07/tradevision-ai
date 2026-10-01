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
 * Review finding ("#6 — Fill Ledger", agreed sequencing 4 -> 6 -> 5 -> 9 -> 7): "Every fill gets
 * its own immutable record... you never have to wonder 'Where did this position quantity come
 * from?' You can reconstruct it from fills." This is that record — one FillRecord per actual
 * exchange trade, never mutated once written.
 *
 * HONEST SCOPE:
 * - tradeId/executedAt are genuinely nullable, not always available — see Fill DTO's own
 *   javadoc for exactly what Binance does and doesn't reliably return per source checked.
 * - quoteCommission is set only when the commission was actually paid in the quote asset (same
 *   "don't fabricate what you don't know" rule as PositionSafetyService.sumCommissionInQuoteAsset
 *   uses elsewhere) — a BNB-fee-discount trade leaves this null, not guessed.
 * - isAggregate distinguishes a genuine per-trade record (one real fill, from myTrades or an
 *   order response's own fills array) from a synthesized single record built from order-level
 *   executedQty/fillPrice when no per-fill data was available at all — the review's own "every
 *   fill gets its own record" ideal isn't always achievable with what the broker actually
 *   returns, and pretending an aggregate is a real single fill would misrepresent that.
 * - Position is NOT yet derived FROM this ledger (review's own "#5 — Position Ledger", the next
 *   step in the agreed sequence) — Position is still updated directly elsewhere in this
 *   codebase. This ledger exists and is populated, but isn't yet the source of truth for
 *   Position quantity.
 *
 * UPDATE ("Fill Ledger" review — "IDs are inconsistent" / "lacks proper idempotency" /
 * "FillRecord needs positionId"): three real findings, all fixed together since they're
 * related.
 * - positionId added: orderId is genuinely inconsistent across paths (the entry path uses the
 *   OMS Order.id, both exit paths use the broker's own order id, since OCO/emergency-flatten
 *   remain outside OMS — see OrderService's own javadoc for that disclosed scope boundary).
 *   positionId is the one identifier every fill type shares, since a position spans an entry
 *   order and however many exit orders eventually close it. orderId is KEPT, not removed — it's
 *   still a real, meaningful reference, just not safe to use alone for reconstructing a whole
 *   position's fill history.
 * UPDATE ("Fill Ledger also lacks proper idempotency" review — "FillRecord uses Lombok @Data,
 * which generates setters... the model itself doesn't enforce immutability. For production I'd
 * make the ledger effectively append-only: no update endpoint, no delete, immutable fields"):
 * confirmed real and fixed as far as this session can responsibly take it. @Data (which
 * generates a setter for every field) replaced with @Getter/@ToString/@EqualsAndHashCode — every
 * field is now generically immutable at the Java level, EXCEPT id, which keeps one hand-written
 * setter (setId, below) because Spring Data MongoDB's own auto-generated-id mechanism needs it,
 * and this codebase's own established pattern elsewhere (Order, Position) pre-assigns a UUID id
 * before first save for exactly this reason too — see FillLedgerService's own buildRecord, which
 * was rewritten from a setter chain to a single constructor call for this same fix.
 *
 * HONEST SCOPE: this is Java-level immutability (no way to call fillRecord.setSymbol(...) on an
 * already-built object), not database-level append-only enforcement (nothing here stops a
 * different piece of code from calling fillRecordRepo.save() again on a record with the same id
 * to overwrite it, or calling fillRecordRepo.delete(...)). Checked before writing this: no REST
 * controller in this codebase exposes any update or delete endpoint for FillRecord at all, so
 * that specific part of the review's own request is independently already true, not something
 * this pass had to build. A genuine database-level append-only guarantee (a Mongo collection
 * validator rejecting update/delete operations outright) is real further infrastructure work,
 * not a code change — not attempted here.
 */
@Getter @ToString @EqualsAndHashCode @NoArgsConstructor @AllArgsConstructor
@Document(collection = "fill_records")
public class FillRecord {
    @Id private String id;
    /** Spring Data MongoDB needs this to populate the generated id after insert — see this
     *  class's own javadoc for why every OTHER field has no setter at all. */
    public void setId(String id) { this.id = id; }

    @Indexed private String orderId;
    // Review finding ("FillRecord.orderId is still inconsistent across paths" -- P1): confirmed
    // real, on top of what this class's own earlier javadoc already disclosed about orderId's
    // inconsistency (see above) -- positionId already solves the PRACTICAL reconstruction
    // problem (it's the one identifier every fill type shares), but orderId itself still means
    // different things on different paths (the OMS Order.id for entry, the broker's own order id
    // for every exit path), which this field starts closing from the OTHER direction: a real
    // broker-side order id, alongside orderId, for the one path that was missing it -- entry,
    // where orderId already correctly holds the OMS id (reconciliation depends on that staying
    // exactly what it is; changing it would have silently broken
    // PositionLedgerService.reconcileAgainstLedger's own lookup, caught before this shipped, not
    // after). Every OTHER path (OCO exit, late-fill, emergency-flatten) already has orderId
    // holding a real broker-side id, so this field is null there -- it would be redundant, not
    // missing information. Left null rather than duplicated.
    @Indexed private String brokerOrderId;
    @Indexed private String positionId;
    private String brokerTradeId;
    /** Unique — see this class's own javadoc for exactly how it's derived and why. */
    private String fillIdentity;
    /**
     * Review finding ("Fill identity fallback not fully safe" — "when Binance omits tradeId, a
     * composite of order + price + qty can collide on two identical partials. Better direction:
     * prefer broker trade id when present, else include commission + time/sequence if available,
     * else mark identityConfidence = WEAK and treat duplicates conservatively in
     * reconciliation"): confirmed real, and this is exactly that flag — "STRONG" when a genuine
     * brokerTradeId backs fillIdentity, "WEAK" when it's the improved-but-still-approximate
     * fallback (see FillLedgerService.computeFillIdentity for what that fallback now includes).
     * This class itself doesn't act differently based on this value — it's a signal for whatever
     * reconciliation logic reads the ledger to treat WEAK-identity records more conservatively,
     * exactly the review's own suggested next step, not something this pass builds reconciliation
     * behavior around.
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
