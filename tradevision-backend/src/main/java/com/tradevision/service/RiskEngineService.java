package com.tradevision.service;

import com.tradevision.model.Position;
import com.tradevision.model.RiskProfile;
import com.tradevision.repository.PositionRepository;
import com.tradevision.repository.RiskProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Every check here runs server-side, immediately before an order is placed — never just
 * suggested in the UI. Called from AutoTradeService for signal-triggered orders; the manual
 * test-order path (OrderExecutionService, TESTNET-only — see its own javadoc) deliberately does
 * NOT go through this, since it never places a LIVE order at all.
 *
 * Concurrency is now counted from real open Position rows (review item #5) — not inferred from
 * order history, which could never tell an entry apart from its own exit.
 *
 * P2-21 fix ("Time handling: LocalDateTime.now() everywhere instead of Instant + explicit
 * trading-day zone" -- external review, confirmed real specifically for THIS class's own two
 * "what day is it" checks): resetDailyCounterIfNewDay and recordRealizedLoss both used to call
 * bare LocalDate.now(), which resolves against the JVM process's own default time zone -- never
 * stated anywhere in this codebase, and never guaranteed to be the same zone this application's
 * operator, or its users, actually think of as "today" for a daily-loss-limit reset. Crypto spot
 * markets have no single exchange trading-day boundary the way an equities exchange does, so
 * there's no one "correct" zone to hardcode -- but an UNDECLARED, ambient one is strictly worse
 * than a deliberately chosen, documented one: a container redeployed with a different TZ
 * environment variable (nothing in this codebase pins it) would silently shift exactly when a
 * live trading credential's daily loss counter resets, with no code change and no record of why.
 * app.trading.day-zone (default UTC, a neutral, DST-free choice matching how this application
 * already timestamps most other things) makes that zone an explicit, single, overridable
 * configuration value instead.
 *
 * HONEST SCOPE: this fixes the one place in this codebase where "which day" genuinely drives
 * financial-safety behavior (the daily-loss-limit reset) -- it does NOT convert this class's, or
 * this codebase's other ~140 call sites', plain event-timestamp LocalDateTime.now() usage
 * (calledAt, createdAt, updatedAt, resolvedAt, and similar) to Instant. Those aren't day-boundary
 * decisions -- they're just "when did this happen," and every one of them is both written AND
 * read back by this same single JVM (this application runs at replicas: 1, per this repository's
 * own k8s/deployment.yaml and its own reconciliation-lock disclosure elsewhere), so they stay
 * internally self-consistent regardless of which zone the JVM happens to default to. A full
 * LocalDateTime -> Instant migration across every model/DTO/repository query in this codebase
 * would be a large, invasive, high-regression-risk change for comparatively little real
 * correctness benefit over today() below -- the one call site the review's own "trading-day zone"
 * language was actually about.
 */
@Service
@RequiredArgsConstructor
public class RiskEngineService {

    private final PositionRepository positionRepo;
    private final RiskProfileRepository riskProfileRepo;
    private final MongoTemplate mongoTemplate;
    private final com.tradevision.repository.OrderRepository orderRepo;

    // P2-21 fix, full context in this class's own header comment above: explicit, overridable,
    // defaults to UTC. Not final -- @Value is field-injected after construction, same pattern
    // already established elsewhere in this codebase (OtpUtil, EmailService, ProxyController).
    @org.springframework.beans.factory.annotation.Value("${app.trading.day-zone:UTC}")
    private String tradingDayZone;

    /**
     * P2-21 fix, full context in this class's own header comment above: the one place "what day
     * is it" is actually decided, now against an explicit, configured zone instead of the JVM's
     * ambient default. Package-private (not private) so it's directly unit-testable without
     * needing to fake the system clock -- see RiskEngineServiceTest.
     */
    LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of(tradingDayZone));
    }

    public record RiskCheckResult(boolean allowed, String reason) {
        public static RiskCheckResult ok() { return new RiskCheckResult(true, null); }
        public static RiskCheckResult reject(String reason) { return new RiskCheckResult(false, reason); }
    }

    public RiskCheckResult check(RiskProfile profile, String symbol, BigDecimal orderQuoteValue) {
        if (profile.isTradingHalted()) {
            return RiskCheckResult.reject("Kill switch is active for this credential" +
                (profile.getHaltReason() != null ? ": " + profile.getHaltReason() : "."));
        }

        resetDailyCounterIfNewDay(profile);

        if (profile.getDailyLossLimitQuote() != null && profile.getDailyLossLimitQuote().signum() > 0
                && profile.getDailyRealizedLossQuote() != null
                && profile.getDailyRealizedLossQuote().compareTo(profile.getDailyLossLimitQuote()) >= 0) {
            haltForDailyLoss(profile);
            return RiskCheckResult.reject("Daily loss limit reached — auto-trading halted for today.");
        }

        if (profile.getMaxPositionQuoteAmount() != null && profile.getMaxPositionQuoteAmount().signum() > 0
                && orderQuoteValue.compareTo(profile.getMaxPositionQuoteAmount()) > 0) {
            return RiskCheckResult.reject("Order value " + orderQuoteValue + " exceeds max position size "
                + profile.getMaxPositionQuoteAmount());
        }

        // Review finding ("Other important remaining production work" — "order-frequency
        // limits"): caps new entry orders within a rolling 1-hour window — a cheap, read-based
        // check, same limitation this class's own existing concurrent-trades pre-filter already
        // discloses (not the atomicity-guaranteeing gate for a genuine multi-instance race, just
        // an early, honest rejection for an obviously-over-limit signal). Counts OrderRepository
        // rows, not TradeCallRecord signals — deliberately: a signal that never actually
        // resulted in an order placement (rejected earlier in this same check, or by a symbol/
        // confidence/direction filter before reaching here) never should have counted against
        // this limit in the first place.
        if (profile.getMaxOrdersPerHour() > 0) {
            long recentOrders = orderRepo.countByCredentialIdAndCreatedAtAfter(profile.getCredentialId(), LocalDateTime.now().minusHours(1));
            if (recentOrders >= profile.getMaxOrdersPerHour()) {
                return RiskCheckResult.reject("Order frequency limit reached: " + recentOrders + " orders in the last hour (limit "
                    + profile.getMaxOrdersPerHour() + ") — possible runaway entry behavior.");
            }
        }

        // Review items #13/#14: this is a cheap, read-based pre-filter — fast, but NOT what
        // prevents the multi-instance race (two concurrent reads here could both pass before
        // either writes). The authoritative, atomicity-guaranteeing gate is
        // PositionSlotReservationService.reserve(), called by AutoTradeService right before
        // order placement. This check exists purely to reject obviously-over-limit signals
        // early without spending a Mongo round-trip on the atomic path every time.
        long openPositions = positionRepo.findByUserIdAndCredentialIdAndStatus(
            profile.getUserId(), profile.getCredentialId(), "OPEN").size();

        if (openPositions >= profile.getMaxConcurrentTrades()) {
            return RiskCheckResult.reject("Max concurrent open positions (" + profile.getMaxConcurrentTrades() + ") already reached.");
        }

        // Review item #10: portfolio-level exposure across every open position on this credential,
        // not just the size of the one order being checked right now.
        //
        // Review finding ("P0 #3" — "ENTRY_FILLED_UNVERIFIED is not treated as an active
        // position"): part of that fix is here — a position can now legitimately be status=OPEN
        // with avgEntryPrice still null (entry filled, price unverifiable, emergency flatten
        // failed). quantity × avgEntryPrice would NPE on that position. It still correctly
        // counts toward the concurrent-trade check below (that only needs quantity to exist,
        // not price), but is excluded from the dollar-exposure sums specifically — there's no
        // honest way to value a position at an unknown price, and guessing would be worse than
        // undercounting it here (the position is already halting new trades via tradingHalted
        // regardless, so this isn't the only safety net for it).
        List<Position> openPositionRows = positionRepo.findByUserIdAndCredentialIdAndStatus(
            profile.getUserId(), profile.getCredentialId(), "OPEN");
        List<Position> pricedPositionRows = openPositionRows.stream()
            .filter(p -> !p.isAvgEntryPriceUnverified() && p.getAvgEntryPrice() != null)
            .toList();

        if (profile.getMaxTotalExposureQuote() != null && profile.getMaxTotalExposureQuote().signum() > 0) {
            BigDecimal currentExposure = pricedPositionRows.stream()
                .map(p -> p.getQuantity().multiply(p.getAvgEntryPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal projectedExposure = currentExposure.add(orderQuoteValue);
            if (projectedExposure.compareTo(profile.getMaxTotalExposureQuote()) > 0) {
                return RiskCheckResult.reject("This order would bring total open exposure to " + projectedExposure
                    + ", above the portfolio cap of " + profile.getMaxTotalExposureQuote());
            }
        }

        // Review item #10 (per-symbol): a symbol-specific cap on top of the total — stops one
        // symbol eating the whole exposure budget across several separate signals.
        if (profile.getMaxSymbolExposureQuote() != null && profile.getMaxSymbolExposureQuote().signum() > 0) {
            BigDecimal symbolExposure = pricedPositionRows.stream()
                .filter(p -> p.getSymbol().equalsIgnoreCase(symbol))
                .map(p -> p.getQuantity().multiply(p.getAvgEntryPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal projectedSymbolExposure = symbolExposure.add(orderQuoteValue);
            if (projectedSymbolExposure.compareTo(profile.getMaxSymbolExposureQuote()) > 0) {
                return RiskCheckResult.reject("This order would bring " + symbol + " exposure to " + projectedSymbolExposure
                    + ", above the per-symbol cap of " + profile.getMaxSymbolExposureQuote());
            }
        }

        // Review item #25: user-defined correlation-group caps (see RiskProfile javadoc for the
        // honest scope note — this is a manual proxy, not computed correlation). Inert unless
        // the user has actually configured a group.
        //
        // Review finding (P1 #5 — "Correlation risk controls are still dead/incomplete", full
        // context in RiskProfile's own javadoc): the API-reachability gap is now closed —
        // RiskProfileRequest carries these fields and upsert() persists them, so a configured
        // group is genuinely enforced here. What's still honestly disclosed, not fixed: this
        // remains a plain read-then-compare, not the atomic reservation ExposureReservationService
        // gives total/symbol exposure — the same two-concurrent-orders race that fix closed
        // elsewhere. Building that for an arbitrary number of user-configured, possibly-
        // overlapping groups without a live MongoDB to verify against remains real risk not
        // taken on blind in this pass.
        if (profile.getCorrelationGroups() != null) {
            for (var entry : profile.getCorrelationGroups().entrySet()) {
                String groupName = entry.getKey();
                var groupSymbols = entry.getValue();
                if (groupSymbols == null || !groupSymbols.contains(symbol)) continue;
                BigDecimal cap = profile.getCorrelationGroupCaps() != null ? profile.getCorrelationGroupCaps().get(groupName) : null;
                if (cap == null || cap.signum() <= 0) continue;

                BigDecimal groupExposure = pricedPositionRows.stream()
                    .filter(p -> groupSymbols.contains(p.getSymbol()))
                    .map(p -> p.getQuantity().multiply(p.getAvgEntryPrice()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal projectedGroupExposure = groupExposure.add(orderQuoteValue);
                if (projectedGroupExposure.compareTo(cap) > 0) {
                    return RiskCheckResult.reject("This order would bring correlation group \"" + groupName + "\" exposure to "
                        + projectedGroupExposure + ", above its cap of " + cap);
                }
            }
        }

        return RiskCheckResult.ok();
    }

    /**
     * Called by PositionMonitorService when a signal-triggered position actually closes, using
     * real realized P&L.
     *
     * Review finding ("P0 #6" — "daily-loss accounting is not atomic"): confirmed real. The old
     * version was a textbook read-modify-write race: two concurrent losing exits could both read
     * the same starting value and one increment would silently overwrite the other, understating
     * the daily-loss counter that's supposed to be the hard stop for this credential. Rewritten
     * as an atomic Mongo increment — the database itself serializes concurrent $inc operations on
     * the same document, the same guarantee PositionSlotReservationService already relies on.
     * UPDATE ("Atomic updates for remaining counters" review — checked precisely rather than
     * trusting this comment's own original claim, which had gone stale): confirmed
     * consecutiveOrderFailures (AutoTradeService, atomic $inc/$set) and peakEquityQuote
     * (PositionMonitorService.checkDrawdown, atomic $max — labeled "P1 #8" in that method's own
     * comment) were BOTH already fixed in later passes after this comment was originally
     * written, and this paragraph was simply never updated to say so. Both are genuinely atomic
     * now — this comment previously claimed otherwise and that claim was wrong by the time
     * anyone read it, not a real, currently-open gap.
     */

    // Same root cause and fix as ExposureReservationService.toDecimal128: Spring Data's ad-hoc
    // Update mapping stores a raw BigDecimal as a String unless converted to Decimal128 here,
    // even with @Field(targetType = FieldType.DECIMAL128) on the model field. Confirmed by a
    // real production incident (MongoDB audit log: "Cannot increment with non-numeric argument:
    // {dailyRealizedLossQuote: \"0.0000100000000000\"}") that auto-halted a live credential.
    private org.bson.types.Decimal128 toDecimal128(BigDecimal value) {
        return value == null ? null : new org.bson.types.Decimal128(value);
    }

    public void recordRealizedLoss(RiskProfile profile, BigDecimal lossQuoteAmount) {
        if (lossQuoteAmount.signum() <= 0) return;

        LocalDate today = today();

        // Atomic day-rollover: only resets if the stored date isn't already today — conditioned
        // in the query itself, not read-then-compare-then-write.
        mongoTemplate.updateFirst(
            new Query(where("id").is(profile.getId()).and("dailyTrackedDate").ne(today)),
            new Update().set("dailyTrackedDate", today).set("dailyRealizedLossQuote", toDecimal128(BigDecimal.ZERO)),
            RiskProfile.class);

        // Atomic increment. returnNew gives back the authoritative POST-increment value — the
        // halt decision below is evaluated against what the database actually holds after this
        // specific increment landed, not a value that might already be stale.
        RiskProfile updated = mongoTemplate.findAndModify(
            new Query(where("id").is(profile.getId())),
            new Update().inc("dailyRealizedLossQuote", toDecimal128(lossQuoteAmount)),
            FindAndModifyOptions.options().returnNew(true),
            RiskProfile.class);
        if (updated == null) return; // profile no longer exists — nothing to record against

        // Keep the caller's in-memory object consistent, since some callers keep using `profile` afterward.
        profile.setDailyTrackedDate(updated.getDailyTrackedDate());
        profile.setDailyRealizedLossQuote(updated.getDailyRealizedLossQuote());

        if (updated.getDailyLossLimitQuote() != null && updated.getDailyLossLimitQuote().signum() > 0
                && updated.getDailyRealizedLossQuote().compareTo(updated.getDailyLossLimitQuote()) >= 0) {
            haltForDailyLoss(profile);
        }
    }

    /**
     * Review finding ("Risk" — "strategy-level loss breaker" / "strategy consecutive-loss
     * breaker"): full context (including exactly what triggerSource="SIGNAL" does and doesn't
     * mean) in RiskProfile's own field comments. Call once a position's real outcome is known,
     * for every closed position regardless of trigger source — this method itself decides
     * whether triggerSource makes it relevant, so callers don't need their own SIGNAL/MANUAL
     * branching before calling it.
     */
    public void recordAutoTradeOutcome(RiskProfile profile, String triggerSource, boolean isLoss) {
        if (!"SIGNAL".equalsIgnoreCase(triggerSource)) return; // the separate manual-order path never affects or resets this counter

        if (!isLoss) {
            // A win or break-even breaks the streak — atomic, unconditional reset.
            mongoTemplate.updateFirst(
                new Query(where("id").is(profile.getId())),
                new Update().set("consecutiveAutoTradeLosses", 0),
                RiskProfile.class);
            profile.setConsecutiveAutoTradeLosses(0);
            return;
        }

        RiskProfile updated = mongoTemplate.findAndModify(
            new Query(where("id").is(profile.getId())),
            new Update().inc("consecutiveAutoTradeLosses", 1),
            FindAndModifyOptions.options().returnNew(true),
            RiskProfile.class);
        if (updated == null) return; // profile no longer exists

        profile.setConsecutiveAutoTradeLosses(updated.getConsecutiveAutoTradeLosses());

        if (updated.getMaxConsecutiveAutoTradeLosses() > 0
                && updated.getConsecutiveAutoTradeLosses() >= updated.getMaxConsecutiveAutoTradeLosses()
                && !updated.isAutoTradeHalted()) {
            haltAutoTradeForConsecutiveLosses(profile, updated.getConsecutiveAutoTradeLosses());
        }
    }

    private void haltAutoTradeForConsecutiveLosses(RiskProfile profile, int lossCount) {
        profile.setAutoTradeHalted(true);
        profile.setAutoTradeHaltReason(lossCount + " consecutive autonomous (SIGNAL-driven) trades have lost money — "
            + "autonomous trading halted for this profile. Manual trading is NOT affected.");
        // Targeted $set, same reasoning as haltForDailyLoss — never a full-document save.
        mongoTemplate.updateFirst(
            new Query(where("id").is(profile.getId())),
            new Update().set("autoTradeHalted", true).set("autoTradeHaltReason", profile.getAutoTradeHaltReason()),
            RiskProfile.class);
    }

    private void resetDailyCounterIfNewDay(RiskProfile profile) {
        if (!today().equals(profile.getDailyTrackedDate())) {
            profile.setDailyTrackedDate(today());
            profile.setDailyRealizedLossQuote(BigDecimal.ZERO);
            // Review finding (P1/🟠 #14 — "Daily-loss reset is still partly in-memory"):
            // confirmed real — this used to only mutate the in-memory object, never persisting
            // the reset itself. recordRealizedLoss()'s own atomic $inc (elsewhere in this file)
            // would eventually overwrite the stale DB value on the next loss, and
            // haltForDailyLoss()'s targeted update would too if the limit was hit — but until
            // either of those happened, the database could keep showing yesterday's loss total
            // for an arbitrary stretch of a brand new day. Targeted $set, same pattern as
            // haltForDailyLoss — never a full-document save that could stomp a concurrent change
            // to some other field on this same profile.
            mongoTemplate.updateFirst(
                new Query(where("id").is(profile.getId())),
                new Update().set("dailyTrackedDate", profile.getDailyTrackedDate()).set("dailyRealizedLossQuote", toDecimal128(BigDecimal.ZERO)),
                RiskProfile.class);
        }
    }

    private void haltForDailyLoss(RiskProfile profile) {
        profile.setTradingHalted(true);
        profile.setHaltReason("Daily loss limit reached on " + today());
        profile.setUpdatedAt(LocalDateTime.now());
        // Targeted $set on just these three fields, not a full-document save — avoids stomping
        // any other field a concurrent operation (e.g. the circuit breaker) might have changed
        // on this same profile between when it was loaded and now.
        mongoTemplate.updateFirst(
            new Query(where("id").is(profile.getId())),
            new Update().set("tradingHalted", true).set("haltReason", profile.getHaltReason()).set("updatedAt", profile.getUpdatedAt()),
            RiskProfile.class);
    }
}
