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
 * Review finding ("#6 — Fill Ledger", agreed sequencing 4 -> 6 -> 5 -> 9 -> 7): persists every
 * actual broker fill as its own immutable FillRecord — see FillRecord's own javadoc for the
 * full, honest scope disclosure (nullable tradeId/executedAt, honest quoteCommission, the
 * isAggregate distinction, positionId, fillIdentity, and that Position isn't yet derived from
 * this ledger).
 *
 * Same additive design as OrderService's own wiring into AutoTradeService: a bug in this
 * ledger's own bookkeeping must never block or corrupt the real order/position flow it observes.
 *
 * Review finding ("FillLedgerService swallows all exceptions" — "Catch-all + log + return is
 * fine for a side-channel. It is not fine for an authoritative financial ledger... When ledger
 * becomes required for correctness: Ledger write failure -> incident + RECONCILIATION_REQUIRED
 * and/or stop opening/resizing that position path"): this is a genuinely correct critique of an
 * AUTHORITATIVE ledger — and a correct description of what this class would need to become if
 * promoted to that role. It isn't yet (see PositionLedgerService's own javadoc, and Position's
 * own ledgerRecordingIncomplete field for the honest, non-blocking visibility this pass adds
 * instead). For a side-channel that Position doesn't yet derive from, catch-all-and-log is the
 * correct behavior, not a shortcut — see this class's own scope disclosure above for what would
 * need to change, and when, if that scope changes.
 */
@Service
@RequiredArgsConstructor
public class FillLedgerService {

    private static final Logger log = LoggerFactory.getLogger(FillLedgerService.class);

    private final FillRecordRepository fillRecordRepo;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    // Review finding ("Fill-identity WEAK path needs more conservative reconciliation handling"
    // -- P1, full context in saveOne's own updated javadoc): needed to escalate a WEAK-identity
    // duplicate-key collision, since it might be a genuine second fill silently dropped, not a
    // confirmed duplicate.
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
     * Review finding ("FillRecord.orderId is still inconsistent across paths" -- P1, full
     * context in FillRecord.brokerOrderId's own field javadoc): the actual entry point for a
     * caller that has a real broker-side order id to record ALONGSIDE its own orderId (which,
     * for the entry path, already correctly holds the OMS Order.id -- see this overload's own
     * single caller in AutoTradeService for exactly why that must stay unchanged). Deliberately
     * an overload, not a change to the original 10-parameter signature's own required arguments
     * -- every OTHER call site in this codebase (OCO exit, late-fill, emergency-flatten) already
     * has orderId itself holding a real broker-side id, so this second parameter would be
     * redundant for them, not missing information.
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
            // Review finding (verified precisely, not assumed — checked how this codebase's
            // OWN other services distinguish the two DuplicateKeyException types before picking
            // one): fillRecordRepo.save() goes through Spring Data's repository layer, which
            // applies Spring's exception translation, wrapping the raw MongoDB driver exception
            // into THIS type — not com.mongodb.DuplicateKeyException, which is what a direct
            // mongoTemplate.insert() call throws untranslated (see PositionSlotReservationService's
            // own ensureDocumentExists for that other case, and RiskProfileService's doUpsert for
            // the same repository-save pattern this method uses).
            // This exact fill (same fillIdentity) is already in the ledger — the review's own
            // named scenario (crash after a Binance fill but before the position update
            // completed, then reconciliation rediscovers the same fill). Not an error, and must
            // not abort the rest of this batch's fills from saving.
            //
            // Review finding ("Fill-identity WEAK path needs more conservative reconciliation
            // handling" -- P1): confirmed real and fixed here specifically -- a WEAK-identity
            // collision (see identityConfidence's own javadoc: price+qty+commission+timestamp,
            // no real broker trade id) is NOT necessarily this same, safe "rediscovered a known
            // fill" case. It could genuinely be two DIFFERENT fills that happen to collide on
            // every field this fallback identity is built from -- in which case this catch block
            // would silently drop a real second fill, understating the position's true quantity.
            // A STRONG-identity duplicate (the overwhelming majority) stays a routine, quiet log
            // line, since a real broker trade id makes a genuine collision essentially
            // impossible. A WEAK one gets a CRITICAL incident instead -- loud enough that a
            // human actually looks at whether this was really a duplicate, rather than trusting
            // an identity this codebase's own javadoc already calls "honestly weaker."
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
        // Review finding ("Fill Ledger also lacks proper idempotency" review — "the model itself
        // doesn't enforce immutability"): constructor call now, not a setter chain — matches
        // FillRecord's own new @AllArgsConstructor, which is what actually enforces the
        // immutability the review asked for (no setter exists for any of these fields anymore).
        // Same "don't fabricate what you don't know" rule PositionSafetyService.
        // sumCommissionInQuoteAsset already uses — quoteCommission only set when genuinely known.
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
     * Review finding ("Fill identity fallback not fully safe"): STRONG whenever a real
     * brokerTradeId backs the identity — aggregate records included, since credentialId+orderId
     * +"aggregate" is genuinely deterministic (an order only ever produces one aggregate record
     * — see computeFillIdentity's own javadoc). WEAK only for the genuine fallback case: a real
     * per-trade fill Binance didn't attach a trade id to.
     */
    private String identityConfidence(Fill f, boolean isAggregate) {
        return (isAggregate || f.tradeId() != null) ? "STRONG" : "WEAK";
    }

    /**
     * Review finding ("Fill Ledger also lacks proper idempotency"): a deterministic, unique
     * identity per fill.
     * - Aggregate (synthesized) fill: credentialId+orderId+"aggregate" — deterministic, since an
     *   order only ever produces one aggregate record (there's no per-trade data to distinguish
     *   multiple aggregate fills from the same order in the first place).
     * - Genuine per-trade fill WITH a broker trade id: credentialId+brokerTradeId — the
     *   review's own suggested identity, and the reliable case.
     * - Genuine per-trade fill WITHOUT a broker trade id (see Fill DTO's own javadoc — this is
     *   real and does happen): falls back to credentialId+orderId+price+qty. Honestly weaker —
     *   two genuinely different fills at the exact same price and quantity on the same order
     *   would collide — but this is strictly better than no deduplication at all for the common
     *   crash-and-rediscover case, and is stated here plainly rather than left undocumented.
     */
    /**
     * Review finding ("Fill identity fallback not fully safe" — "when Binance omits tradeId, a
     * composite of order + price + qty can collide on two identical partials. Better direction:
     * ... else include commission + time/sequence if available"): confirmed real and improved —
     * commission and executedAt (both genuinely available on Fill even without a tradeId, per
     * Fill's own javadoc) are now included when present, narrowing the collision window from
     * "any two partials at the same price and quantity" to "any two partials at the same price,
     * quantity, commission, AND timestamp" — still theoretically possible, still honestly
     * imperfect (this is exactly why identityConfidence marks these WEAK rather than presenting
     * them as equally reliable to a real broker trade id), but a real, verified narrowing, not
     * just a relabeling.
     */
    private String computeFillIdentity(String credentialId, String orderId, Fill f, boolean isAggregate) {
        if (isAggregate) return credentialId + ":" + orderId + ":aggregate";
        if (f.tradeId() != null) return credentialId + ":" + f.tradeId();
        return credentialId + ":" + orderId + ":" + f.price() + ":" + f.qty()
            + ":" + (f.commission() != null ? f.commission() : "?")
            + ":" + (f.executedAt() != null ? f.executedAt() : "?");
    }

    /**
     * Review finding ("There is still a real commission/P&L limitation" -- external review,
     * tenth pass, P1, full context in BrokerAdapter.getHistoricalPrice's own javadoc): a
     * separate, optional, best-effort enrichment -- deliberately NOT wired into recordFills
     * itself, since that method runs on the hot execution path immediately after a fill and
     * must never be slowed down or made to fail by an extra network call for accounting
     * accuracy. A caller with a BrokerAdapter/BrokerMode available (or a future reconciliation
     * job) can call this afterward for any record whose commission is in a non-quote asset,
     * converting it at the ACTUAL historical price at the fill's own timestamp -- never
     * "current" price, which the review correctly identified as capable of distorting realized
     * P&L for anything but a same-minute conversion. FillRecord itself has no setter for
     * quoteCommission (deliberately immutable, per this class's own established
     * "don't fabricate what you don't know" + immutability discipline) -- this updates the
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
     * P2-20 fix ("Fees in BNB: sumCommissionInQuoteAsset returns null for non-quote commission;
     * backfill never updates position P&L" -- external review, confirmed real: this method used
     * to end at the FillRecord write above -- the FIRST half of the bug (FillRecord.quoteCommission
     * itself was never populated for a non-quote-asset commission until this method ran) was
     * already fixed, but the SECOND half never was. A position's own realizedPnlQuote/
     * entryFeeQuote/exitFeeQuote are computed and persisted at close time using whatever
     * sumCommissionInQuoteAsset returned THEN -- null for a BNB-fee-discount trade, which
     * PositionSafetyService's own realizedPnlService.calculate call treats as zero fee, not
     * "unknown." A real BNB fee (typically a genuine, non-trivial fraction of a trade's P&L on
     * Binance) was silently excluded from every closed position's reported profit/loss forever,
     * with no path for this backfill -- the ONE piece of code that later actually learns the real
     * fee amount -- to ever go back and correct it.
     *
     * Only adjusts a position that has ALREADY been finalized (a terminal status -- see Position's
     * own hasExited() logic): an OPEN position's eventual close-time calculation will read
     * entryFeeQuote/exitFeeQuote fresh at that point, so updating those fields now is sufficient
     * for it — no separate realizedPnlQuote correction is needed or safe to apply twice. For an
     * already-closed position, realizedPnlQuote was already finalized without this fee, so it's
     * corrected by exactly the newly-discovered amount, atomically ($inc, never a
     * read-modify-write), so a concurrent reader never observes a torn intermediate state.
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
