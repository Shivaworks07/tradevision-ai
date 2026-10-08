package com.tradevision.service;

import com.tradevision.model.FillRecord;
import com.tradevision.repository.FillRecordRepository;
import com.tradevision.service.broker.dto.Fill;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists every actual broker fill as its own immutable {@code FillRecord} (see
 * {@code FillRecord}'s javadoc for the scope: nullable tradeId/executedAt, honest
 * quoteCommission, the isAggregate distinction, positionId, fillIdentity, and that Position isn't
 * yet derived from this ledger).
 *
 * <p>This is an additive side-channel, the same design as {@code OrderService}'s wiring into
 * {@code AutoTradeService}: a bug in this ledger's own bookkeeping must never block or corrupt
 * the real order/position flow it observes. Because of that, a catch-all-and-log failure mode is
 * the correct behavior here, not a shortcut -- it would not be appropriate for an authoritative
 * financial ledger that positions are actually derived from and whose write failures must halt or
 * flag that flow (see {@code PositionLedgerService}'s javadoc and Position's
 * {@code ledgerRecordingIncomplete} field for that distinction), but this class has not been
 * promoted to that role.
 */
@Service
@RequiredArgsConstructor
public class FillLedgerService {

    private static final Logger log = LoggerFactory.getLogger(FillLedgerService.class);

    private final FillRecordRepository fillRecordRepo;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    // Used to escalate a WEAK-identity duplicate-key collision in saveOne, since it might be a
    // genuine second fill silently dropped rather than a confirmed duplicate.
    private final IncidentService incidentService;

    /**
     * Records one FillRecord per genuine per-trade Fill when that data is available, or a
     * single isAggregate=true record from order-level totals when it isn't — see this class's
     * own javadoc and FillRecord's for why that distinction matters. Never called for a zero/no
     * fill — callers should only invoke this once a real fill is confirmed.
     *
     * positionId may be null (e.g. the very first entry fill, before a Position exists yet) —
     * callers should backfill it once the position is created if they have that information
     * available; this method itself doesn't require it to be non-null.
     *
     * Idempotent: a duplicate fillIdentity (see buildRecord's own logic) is caught and skipped —
     * logged as already-recorded, not an error, and never blocks the rest of the batch from
     * saving. This is what makes it safe for reconciliation to rediscover a fill that already
     * made it into the ledger before a crash.
     */
    public List<FillRecord> recordFills(String orderId, String positionId, String userId, String credentialId, String symbol, String side,
                                         String quoteAsset, List<Fill> fills, BigDecimal aggregateQty, BigDecimal aggregatePrice) {
        return recordFills(orderId, null, positionId, userId, credentialId, symbol, side, quoteAsset, fills, aggregateQty, aggregatePrice);
    }

    /**
     * Entry point for a caller that has a real broker-side order id to record alongside its own
     * orderId (which, for the entry path, holds the OMS {@code Order.id} -- see this overload's
     * single caller in {@code AutoTradeService}). This is an overload rather than a change to the
     * original 10-parameter signature, since every other call site (OCO exit, late-fill,
     * emergency-flatten) already has orderId itself holding a real broker-side id, so this second
     * parameter would be redundant for them.
     */
    public List<FillRecord> recordFills(String orderId, String brokerOrderId, String positionId, String userId, String credentialId, String symbol, String side,
                                         String quoteAsset, List<Fill> fills, BigDecimal aggregateQty, BigDecimal aggregatePrice) {
        List<FillRecord> saved = new ArrayList<>();
        try {
            if (fills != null && !fills.isEmpty()) {
                for (Fill f : fills) {
                    saveOne(saved, buildRecord(orderId, brokerOrderId, positionId, userId, credentialId, symbol, side, quoteAsset, f, false));
                }
            } else if (aggregateQty != null && aggregateQty.signum() > 0) {
                Fill synthesized = new Fill(aggregatePrice, aggregateQty, null, null, null, null);
                saveOne(saved, buildRecord(orderId, brokerOrderId, positionId, userId, credentialId, symbol, side, quoteAsset, synthesized, true));
            }
        } catch (Exception e) {
            log.error("Fill ledger recording failed for order {} (non-fatal, additive record only): {}", orderId, e.getMessage());
        }
        return saved;
    }

    private void saveOne(List<FillRecord> saved, FillRecord record) {
        try {
            saved.add(fillRecordRepo.save(record));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // fillRecordRepo.save() goes through Spring Data's repository layer, which applies
            // Spring's exception translation, wrapping the raw MongoDB driver exception into this
            // type -- not com.mongodb.DuplicateKeyException, which is what a direct
            // mongoTemplate.insert() call throws untranslated (see PositionSlotReservationService's
            // ensureDocumentExists for that other case, and RiskProfileService's doUpsert for the
            // same repository-save pattern this method uses).
            //
            // This exact fill (same fillIdentity) is already in the ledger -- e.g. a crash after
            // a broker fill but before the position update completed, then reconciliation
            // rediscovers the same fill. Not an error, and must not abort the rest of this
            // batch's fills from saving.
            //
            // A WEAK-identity collision (see identityConfidence's javadoc: price+qty+commission+
            // timestamp, no real broker trade id) is not necessarily this same, safe "rediscovered
            // a known fill" case -- it could genuinely be two different fills that happen to
            // collide on every field this fallback identity is built from, in which case this
            // catch block would silently drop a real second fill, understating the position's
            // true quantity. A STRONG-identity duplicate (the overwhelming majority) stays a
            // routine, quiet log line, since a real broker trade id makes a genuine collision
            // essentially impossible. A WEAK one raises a critical incident instead, so a human
            // actually looks at whether this was really a duplicate, rather than trusting an
            // identity that is honestly weaker.
            if ("WEAK".equals(record.getIdentityConfidence())) {
                log.warn("Fill ledger: WEAK-identity collision for fillIdentity={} -- could be a genuine duplicate, "
                    + "or could be a real second fill silently dropped. Raising an incident rather than assuming either.",
                    record.getFillIdentity());
                incidentService.raiseCritical(record.getUserId(), record.getCredentialId(), record.getPositionId(),
                    record.getOrderId(), record.getSymbol(), "WEAK_FILL_IDENTITY_COLLISION",
                    "A fill with a WEAK identity (no real broker trade id -- price/qty/commission/timestamp fallback) "
                        + "collided with an existing ledger record (fillIdentity=" + record.getFillIdentity() + "). This "
                        + "may be a genuine duplicate correctly skipped, or a real second fill silently understating this "
                        + "position's true quantity. Manual review of the raw fills for this order is needed to tell "
                        + "which.");
            } else {
                log.info("Fill ledger: duplicate fill skipped (already recorded), fillIdentity={}", record.getFillIdentity());
            }
        }
    }

    private FillRecord buildRecord(String orderId, String brokerOrderId, String positionId, String userId, String credentialId, String symbol, String side,
                                    String quoteAsset, Fill f, boolean isAggregate) {
        // Built via FillRecord's @AllArgsConstructor rather than a setter chain, since no setter
        // exists for these fields -- the record is immutable once constructed. quoteCommission
        // is only set when genuinely known, the same rule PositionSafetyService's
        // sumCommissionInQuoteAsset uses -- never fabricated.
        BigDecimal quoteCommission = (quoteAsset != null && f.commissionAsset() != null && f.commissionAsset().equalsIgnoreCase(quoteAsset))
            ? f.commission() : null;
        return new FillRecord(
            null, // id — populated by MongoDB on insert via the one remaining setter, never set here
            orderId, brokerOrderId, positionId, f.tradeId(), computeFillIdentity(credentialId, orderId, f, isAggregate),
            identityConfidence(f, isAggregate),
            userId, credentialId, symbol, side,
            f.price(), f.qty(),
            f.commission(), f.commissionAsset(), quoteCommission,
            f.executedAt(), LocalDateTime.now(),
            isAggregate
        );
    }

    /**
     * STRONG whenever a real brokerTradeId backs the identity -- aggregate records included,
     * since credentialId+orderId+"aggregate" is deterministic (an order only ever produces one
     * aggregate record, see {@link #computeFillIdentity}). WEAK only for the fallback case: a
     * real per-trade fill the broker didn't attach a trade id to.
     */
    private String identityConfidence(Fill f, boolean isAggregate) {
        return (isAggregate || f.tradeId() != null) ? "STRONG" : "WEAK";
    }

    /**
     * Builds a deterministic, unique identity per fill.
     * - Aggregate (synthesized) fill: credentialId+orderId+"aggregate" -- deterministic, since an
     *   order only ever produces one aggregate record (there's no per-trade data to distinguish
     *   multiple aggregate fills from the same order).
     * - Genuine per-trade fill with a broker trade id: credentialId+brokerTradeId -- the
     *   reliable case.
     * - Genuine per-trade fill without a broker trade id (see Fill DTO's javadoc -- this does
     *   happen): falls back to credentialId+orderId+price+qty+commission+executedAt. This is
     *   honestly weaker -- two genuinely different fills at the exact same price, quantity,
     *   commission and timestamp on the same order would still collide -- but it's strictly
     *   better than no deduplication at all for the common crash-and-rediscover case. Including
     *   commission and executedAt (both available on Fill even without a tradeId) narrows the
     *   collision window well past a bare price+quantity composite; this is exactly why
     *   {@link #identityConfidence} marks these WEAK rather than presenting them as equally
     *   reliable to a real broker trade id.
     */
    private String computeFillIdentity(String credentialId, String orderId, Fill f, boolean isAggregate) {
        if (isAggregate) return credentialId + ":" + orderId + ":aggregate";
        if (f.tradeId() != null) return credentialId + ":" + f.tradeId();
        return credentialId + ":" + orderId + ":" + f.price() + ":" + f.qty()
            + ":" + (f.commission() != null ? f.commission() : "?")
            + ":" + (f.executedAt() != null ? f.executedAt() : "?");
    }

    /**
     * Optional, best-effort enrichment that converts a non-quote-asset commission into its
     * quote-asset equivalent, using the actual historical price at the fill's own timestamp
     * (never "current" price, which would distort realized P&amp;L for anything but a
     * same-minute conversion). Deliberately not wired into {@link #recordFills} itself, since
     * that method runs on the hot execution path immediately after a fill and must never be
     * slowed down or made to fail by an extra network call for accounting accuracy. A caller
     * with a {@code BrokerAdapter}/{@code BrokerMode} available (or a reconciliation job) can
     * call this afterward for any record whose commission is in a non-quote asset. Since
     * {@code FillRecord} is immutable and has no setter for quoteCommission, this updates the
     * persisted document directly, once, only when quoteCommission is still null and there's a
     * real commission asset different from the quote asset to convert.
     */
    @org.springframework.scheduling.annotation.Async("commissionBackfillExecutor")
    public void backfillHistoricalCommissionConversion(FillRecord record, String quoteAsset,
                                                          com.tradevision.service.broker.BrokerAdapter adapter,
                                                          com.tradevision.model.BrokerMode mode) {
        if (record.getQuoteCommission() != null) return; // already known -- never overwrite a real, already-computed value
        if (record.getCommissionAmount() == null || record.getCommissionAsset() == null || quoteAsset == null) return;
        if (record.getCommissionAsset().equalsIgnoreCase(quoteAsset)) return; // buildRecord's own path already handled this case
        if (record.getExecutedAt() == null) return; // no timestamp to convert at -- genuinely unknown, not a guess

        long timestampMillis = record.getExecutedAt().atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        java.math.BigDecimal historicalPrice;
        try {
            historicalPrice = adapter.getHistoricalPrice(record.getCommissionAsset().toUpperCase() + quoteAsset.toUpperCase(),
                timestampMillis, mode);
        } catch (Exception e) {
            log.warn("Could not backfill historical commission conversion for fill {} (non-fatal, quoteCommission stays genuinely "
                + "unknown): {}", record.getId(), e.getMessage());
            return;
        }
        if (historicalPrice == null) return; // no historical candle available -- stays genuinely unknown, never fabricated

        java.math.BigDecimal quoteCommission = record.getCommissionAmount().multiply(historicalPrice);
        var updateResult = mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(record.getId())
                    // Re-checked at write time, not just at method entry -- guards against two
                    // concurrent backfill attempts for the same record both passing the entry
                    // check above and then both applying the position-side adjustment below.
                    .and("quoteCommission").isNull()),
            new org.springframework.data.mongodb.core.query.Update().set("quoteCommission", quoteCommission),
            FillRecord.class);
        if (updateResult.getModifiedCount() == 0) {
            // Lost the race to a concurrent backfill attempt for the same record -- the position
            // adjustment below has already been (or is about to be) applied by whichever attempt
            // actually won, so applying it again here would double-count the same fee.
            return;
        }
        log.info("Backfilled historical commission conversion for fill {}: {} {} at historical price {} = {} {} (fill timestamp {}).",
            record.getId(), record.getCommissionAmount(), record.getCommissionAsset(), historicalPrice, quoteCommission, quoteAsset,
            record.getExecutedAt());

        applyBackfilledCommissionToPosition(record, quoteCommission);
    }

    /**
     * Applies a just-discovered historical commission conversion to the position's own fee and
     * P&amp;L fields, so a fee-discount trade (e.g. paid in a discount asset rather than the quote
     * asset) is reflected in the position's real profit/loss rather than silently treated as zero
     * fee because the quote-equivalent amount wasn't known at close time.
     *
     * <p>Only adjusts a position that has already been finalized (a terminal status, see
     * Position's {@code hasExited()} logic): an open position's eventual close-time calculation
     * will read entryFeeQuote/exitFeeQuote fresh at that point, so updating those fields now is
     * sufficient for it -- no separate realizedPnlQuote correction is needed or safe to apply
     * twice. For an already-closed position, realizedPnlQuote was already finalized without this
     * fee, so it's corrected by exactly the newly-discovered amount, atomically ({@code $inc},
     * never a read-modify-write), so a concurrent reader never observes a torn intermediate
     * state.
     */
    private void applyBackfilledCommissionToPosition(FillRecord record, BigDecimal quoteCommission) {
        if (record.getPositionId() == null || quoteCommission == null || quoteCommission.signum() == 0) return;
        var position = mongoTemplate.findById(record.getPositionId(), com.tradevision.model.Position.class);
        if (position == null) return; // position genuinely doesn't exist (or was purged) -- nothing to correct

        String feeField = "BUY".equalsIgnoreCase(record.getSide()) ? "entryFeeQuote" : "exitFeeQuote";
        var update = new org.springframework.data.mongodb.core.query.Update().inc(feeField, quoteCommission);
        boolean alreadyFinalized = java.util.Set.of("CLOSED", "NAKED_FLATTENED", "CLOSED_UNVERIFIED_PNL").contains(position.getStatus());
        if (alreadyFinalized) {
            // The stored realizedPnlQuote was computed treating this fee as zero (sumCommission
            // InQuoteAsset returned null at close time) -- a fee this application never knew
            // reduces realized P&L, so correcting for it discovered later means subtracting it.
            update.inc("realizedPnlQuote", quoteCommission.negate());
        }
        mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("id").is(record.getPositionId())),
            update,
            com.tradevision.model.Position.class);
        log.info("Applied backfilled {}-side commission of {} (quote-equivalent) to position {} -- {}.",
            record.getSide(), quoteCommission, record.getPositionId(),
            alreadyFinalized ? "already closed, realizedPnlQuote corrected too" : "still open, close-time calculation will pick it up");
    }
}
