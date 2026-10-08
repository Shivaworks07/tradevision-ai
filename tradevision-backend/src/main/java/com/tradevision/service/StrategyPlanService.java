package com.tradevision.service;

import com.tradevision.model.BrokerCredential;
import com.tradevision.dto.StrategyPlanRequest;
import com.tradevision.model.RiskProfile;
import com.tradevision.model.StrategyPlan;
import com.tradevision.model.TradeDirection;
import com.tradevision.repository.RiskProfileRepository;
import com.tradevision.repository.StrategyPlanRepository;
import com.tradevision.service.broker.BrokerAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Supports a multi-strategy-plan platform: a user can create N strategy plans per credential,
 * each independently owning its own timeframe, direction, coin universe, risk, and exit rules —
 * with the account-level RiskProfile ceiling always winning over any plan's own, narrower
 * setting.
 *
 * This class provides: the CRUD + ownership-checked API layer (create/update/setEnabled/
 * delete/list), direction-vs-market-type validation, session config validation and evaluation
 * (isSessionConfigValid/isWithinSession, including overnight sessions), the migration path
 * (getOrCreateDefaultPlan), multi-plan fetch for the scanner (getEnabledPlans, correctly
 * returning empty rather than a disabled default when a user has turned every plan off), and
 * the actual execution-time authorization gate (authorizeExecution) that AutoTradeService calls
 * twice per signal — verifying plan ownership, credential match, enabled state, session,
 * direction, and the plan's own real coin universe (including a live re-check of dynamic-
 * universe candidates) before any order reaches the exchange.
 */
@Service
@RequiredArgsConstructor
public class StrategyPlanService {

    private final StrategyPlanRepository strategyPlanRepo;
    private final RiskProfileRepository riskProfileRepo;
    private final com.tradevision.repository.BrokerCredentialRepository credentialRepo;
    private final DynamicUniverseService dynamicUniverseService;
    private final com.tradevision.repository.PositionRepository positionRepo;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    /**
     * Lazily migrates a credential that predates the multi-plan system into having exactly one
     * strategy plan, mirroring the old single-plan behavior field-for-field: same
     * enabledSymbols, same dynamicUniverseEnabled/MaxSymbols, same riskPerTradePercent/
     * maxConcurrentTrades, same scan timeframe convention (RiskProfile's own "1h" default),
     * LONG-only (matching this codebase's spot-only, long-only architecture — see
     * TradeDirection's own enum javadoc). Runs on first access to a credential's plans rather
     * than via a bulk migration job. Idempotent: if a default plan already exists for this
     * credential, returns it unchanged rather than creating a duplicate.
     */
    public StrategyPlan getOrCreateDefaultPlan(String credentialId) {
        return strategyPlanRepo.findByCredentialIdAndDefaultPlanTrue(credentialId)
            .orElseGet(() -> {
                RiskProfile profile = riskProfileRepo.findByCredentialId(credentialId).orElse(null);
                StrategyPlan plan = new StrategyPlan();
                plan.setCredentialId(credentialId);
                plan.setUserId(profile != null ? profile.getUserId() : null);
                plan.setName("Default");
                plan.setDefaultPlan(true);
                plan.setEnabled(true);
                plan.setDirection(TradeDirection.LONG);
                if (profile != null) {
                    plan.setTimeframe(profile.getScanTimeframe());
                    plan.setEnabledSymbols(new java.util.HashSet<>(profile.getEnabledSymbols()));
                    plan.setDynamicUniverseEnabled(profile.isDynamicUniverseEnabled());
                    plan.setDynamicUniverseMaxSymbols(profile.getDynamicUniverseMaxSymbols());
                    plan.setRiskPerTradePercent(profile.getRiskPerTradePercent());
                    plan.setMaxConcurrentTrades(profile.getMaxConcurrentTrades());
                    plan.setMinConfidence(profile.getMinConfidence());
                }
                // No max-hold, no signal-reversal exit -- matches today's actual behavior
                // exactly: positions currently exit only via TP/SL/manual/emergency-flatten,
                // never a time-based or signal-based rule, so a migrated default plan must not
                // silently introduce one.
                plan.setMaxHoldMinutes(null);
                plan.setExitOnSignalReversal(false);
                return strategyPlanRepo.save(plan);
            });
    }

    /**
     * Enforces TradeDirection's own disclosed limitation at plan creation/update time: rejects
     * SHORT/BOTH for every current BrokerType, not just BINANCE. MUDREX has no working
     * BrokerAdapter implementation at all yet (see BrokerType's own javadoc), so there is no
     * evidence it would support shorting either — the safe default is rejecting until a real
     * adapter proves otherwise, not assuming support for a connection type that doesn't
     * functionally exist.
     */
    public void validateDirectionForMarket(TradeDirection direction, BrokerCredential credential) {
        if (direction == TradeDirection.LONG) return;
        throw new IllegalArgumentException("Direction " + direction + " is not valid for this credential (" + credential.getBroker()
            + "): no broker connection this codebase currently supports can short-sell without margin borrowing, which isn't "
            + "implemented for any of them. Only LONG is valid until a margin/futures connection type exists and is proven to support it.");
    }

    /**
     * The multi-plan fetch AutonomousScannerService's own scan loop uses — every plan the user
     * has explicitly turned on for this credential. Falls back to a single-element list
     * containing the migration-created default plan only when no plan has ever existed for this
     * credential at all; when plans exist but the user has disabled every one of them, this
     * returns an empty list rather than silently falling back to a disabled default — "no
     * enabled plans" must mean no autonomous trading, not a trust-the-method's-name assumption
     * the scanner could act on without re-verifying isEnabled() itself.
     */
    public java.util.List<StrategyPlan> getEnabledPlans(String credentialId) {
        java.util.List<StrategyPlan> allPlans = strategyPlanRepo.findByCredentialId(credentialId);
        if (allPlans.isEmpty()) {
            // No plan has ever existed for this credential at all -- the real migration case.
            return java.util.List.of(getOrCreateDefaultPlan(credentialId));
        }
        // At least one plan already exists. Whether it's enabled or not is the user's own
        // explicit, deliberate choice -- never silently substitute a plan they turned off.
        return allPlans.stream().filter(StrategyPlan::isEnabled).collect(java.util.stream.Collectors.toList());
    }

    /**
     * Creates a new strategy plan. No artificial cap on how many plans a user can create — this
     * codebase has no resource-limiting mechanism to enforce a concrete number against, so none
     * is invented here; ownership (the credential must belong to this user) is the only check
     * performed.
     */
    public StrategyPlan create(String userId, StrategyPlanRequest req) {
        var credential = credentialRepo.findById(req.getCredentialId())
            .filter(c -> c.getUserId().equals(userId))
            .orElseThrow(() -> new IllegalArgumentException("No broker credential " + req.getCredentialId() + " found for this user."));
        validateDirectionForMarket(req.getDirection(), credential);
        validateSessionConfig(req);

        StrategyPlan plan = new StrategyPlan();
        plan.setUserId(userId);
        plan.setCredentialId(req.getCredentialId());
        applyRequest(plan, req);
        return strategyPlanRepo.save(plan);
    }

    /**
     * Updates a plan's own settings after creation. Ownership-checked — a user may only update
     * their own plan. The migration-created default plan (isDefaultPlan) can be edited like any
     * other; that flag only matters for getOrCreateDefaultPlan's own lookup, not for what a user
     * is allowed to change about it.
     */
    public StrategyPlan update(String userId, String planId, StrategyPlanRequest req) {
        StrategyPlan plan = findOwnedPlan(userId, planId);
        var credential = credentialRepo.findById(plan.getCredentialId())
            .orElseThrow(() -> new IllegalStateException("This plan's own credential no longer exists."));
        validateDirectionForMarket(req.getDirection(), credential);
        validateSessionConfig(req);
        applyRequest(plan, req);
        // @Version on StrategyPlan.version makes this save genuinely optimistically locked —
        // no manual increment needed here. A concurrent modification since `plan` was read above
        // throws OptimisticLockingFailureException here, converted to a clear, actionable
        // message by the controller's own catch block.
        return strategyPlanRepo.save(plan);
    }

    /**
     * API-boundary validation for a plan's session configuration: rejects a request outright
     * (before anything is ever saved) rather than allowing a broken config to reach
     * isSessionConfigValid's own fail-closed handling at evaluation time. This doesn't make that
     * runtime check redundant: a plan saved by an earlier version of this validation (or edited
     * directly in the database) can still exist with bad config, which is exactly what
     * isSessionConfigValid/isWithinSession's own fail-closed behavior still needs to handle.
     */
    private void validateSessionConfig(StrategyPlanRequest req) {
        if (req.getSessionMode() == com.tradevision.model.SessionMode.ALWAYS_ON) return;
        if (req.getSessionStart() == null || req.getSessionEnd() == null || req.getSessionTimezone() == null) {
            throw new IllegalArgumentException("sessionStart, sessionEnd, and sessionTimezone are all required when sessionMode is "
                + req.getSessionMode() + ".");
        }
        try {
            java.time.ZoneId.of(req.getSessionTimezone());
        } catch (Exception e) {
            throw new IllegalArgumentException("\"" + req.getSessionTimezone() + "\" is not a valid IANA timezone (e.g. \"Asia/Kolkata\", "
                + "\"America/New_York\", \"UTC\").");
        }
        if (req.getSessionMode() == com.tradevision.model.SessionMode.CUSTOM_DAYS
                && (req.getSessionDays() == null || req.getSessionDays().isEmpty())) {
            throw new IllegalArgumentException("sessionDays must include at least one day when sessionMode is CUSTOM_DAYS.");
        }
    }

    /** Turns a strategy plan on or off. Ownership-checked, same as update. */
    public StrategyPlan setEnabled(String userId, String planId, boolean enabled) {
        StrategyPlan plan = findOwnedPlan(userId, planId);
        plan.setEnabled(enabled);
        plan.setUpdatedAt(LocalDateTime.now());
        // @Version on save() below gives this the same real optimistic-locking guarantee as
        // update() above.
        return strategyPlanRepo.save(plan);
    }

    /**
     * Ownership-checked deletion that rejects a plan with any OPEN position still depending on
     * it (for max-hold/session/signal-reversal/exit-policy management, all of which look the
     * plan up by Position.planId) — the user must close those positions first, or simply disable
     * the plan (which already stops new entries without losing the exit-management policy an
     * open position still needs). Deliberately does NOT flatten or otherwise touch any position
     * on deletion; Position.planId remains a valid historical reference to a now-deleted plan
     * (the same "orphan a foreign key rather than cascade-delete real positions" choice this
     * codebase already makes elsewhere, e.g. OrphanedOco not cascading from Position deletion).
     */
    public void delete(String userId, String planId) {
        StrategyPlan plan = findOwnedPlan(userId, planId);
        if (positionRepo.existsByPlanIdAndStatus(planId, "OPEN")) {
            throw new IllegalStateException("Strategy plan \"" + plan.getName() + "\" still has open positions and cannot be deleted -- "
                + "close those positions first, or disable the plan instead to stop it from opening new ones.");
        }
        strategyPlanRepo.delete(plan);
    }

    public java.util.List<StrategyPlan> list(String userId, String credentialId) {
        return strategyPlanRepo.findByCredentialId(credentialId).stream()
            .filter(p -> userId.equals(p.getUserId()))
            .collect(java.util.stream.Collectors.toList());
    }

    private StrategyPlan findOwnedPlan(String userId, String planId) {
        StrategyPlan plan = strategyPlanRepo.findById(planId)
            .orElseThrow(() -> new IllegalArgumentException("No strategy plan " + planId + " found."));
        if (!userId.equals(plan.getUserId())) {
            throw new IllegalArgumentException("No strategy plan " + planId + " found."); // same message as not-found -- don't leak existence of another user's plan
        }
        return plan;
    }

    private void applyRequest(StrategyPlan plan, StrategyPlanRequest req) {
        plan.setName(req.getName());
        plan.setTimeframe(req.getTimeframe());
        plan.setDirection(req.getDirection());
        plan.setEnabledSymbols(req.getEnabledSymbols() != null ? req.getEnabledSymbols() : new java.util.HashSet<>());
        plan.setDynamicUniverseEnabled(req.isDynamicUniverseEnabled());
        plan.setDynamicUniverseMaxSymbols(req.getDynamicUniverseMaxSymbols());
        plan.setRiskPerTradePercent(req.getRiskPerTradePercent());
        plan.setMaxConcurrentTrades(req.getMaxConcurrentTrades());
        plan.setMaxCapital(req.getMaxCapital());
        plan.setMaxHoldMinutes(req.getMaxHoldMinutes());
        plan.setExitOnSignalReversal(req.isExitOnSignalReversal());
        plan.setExitOnRiskEmergency(req.isExitOnRiskEmergency());
        plan.setSessionMode(req.getSessionMode());
        plan.setSessionStart(req.getSessionStart());
        plan.setSessionEnd(req.getSessionEnd());
        plan.setSessionTimezone(req.getSessionTimezone());
        plan.setSessionDays(req.getSessionDays() != null ? req.getSessionDays() : new java.util.HashSet<>());
        plan.setEndOfSessionAction(req.getEndOfSessionAction());
        plan.setMinConfidence(req.getMinConfidence());
        plan.setCooldownMinutes(req.getCooldownMinutes());
        plan.setEnabled(req.isEnabled());
        plan.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * The gate a caller checks before trusting isWithinSession's own answer at all. A plan can
     * only be trusted to answer "is now inside my own session" if it is ALWAYS_ON (nothing to
     * misconfigure) or its DAILY/CUSTOM_DAYS configuration is complete and parseable.
     * Deliberately does NOT decide what a caller should do about invalid config — the right
     * response differs by caller: the scanner should stop new entries, while the end-of-session
     * reconciliation step should not flatten an existing position merely because the
     * configuration is malformed.
     */
    public boolean isSessionConfigValid(StrategyPlan plan) {
        if (plan.getSessionMode() == com.tradevision.model.SessionMode.ALWAYS_ON) return true;
        if (plan.getSessionStart() == null || plan.getSessionEnd() == null || plan.getSessionTimezone() == null) return false;
        try {
            java.time.ZoneId.of(plan.getSessionTimezone());
        } catch (Exception e) {
            return false;
        }
        if (plan.getSessionMode() == com.tradevision.model.SessionMode.CUSTOM_DAYS
                && (plan.getSessionDays() == null || plan.getSessionDays().isEmpty())) {
            return false;
        }
        return true;
    }

    /**
     * The single source of truth for "is this plan currently inside its own configured trading
     * session," used identically by the scanner (stop new entries) and the reconciliation pass
     * (close at session end) — so the two can never disagree about where the boundary actually
     * is. ALWAYS_ON (the default) is always true, by definition, matching this codebase's
     * pre-session-feature behavior exactly.
     *
     * Invalid config fails CLOSED (no new entries) rather than open. Callers that need to
     * distinguish "genuinely outside a valid session" from "config itself is broken" (e.g.
     * end-of-session, which must not flatten over a config error) call isSessionConfigValid
     * first, separately — this method alone cannot express that distinction, by design, since
     * collapsing the two into one boolean would hide exactly the ambiguity that distinction
     * exists to resolve.
     *
     * A session where start > end is treated as spanning midnight — "now" is inside it if
     * now >= start OR now < end, rather than requiring both simultaneously (requiring both would
     * be exactly backwards for an overnight window like 22:00-02:00).
     */
    public boolean isWithinSession(StrategyPlan plan) {
        if (plan.getSessionMode() == com.tradevision.model.SessionMode.ALWAYS_ON) return true;
        if (!isSessionConfigValid(plan)) return false;

        java.time.ZoneId zone = java.time.ZoneId.of(plan.getSessionTimezone());
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(zone);
        if (plan.getSessionMode() == com.tradevision.model.SessionMode.CUSTOM_DAYS
                && !plan.getSessionDays().contains(now.getDayOfWeek())) {
            return false;
        }
        java.time.LocalTime nowTime = now.toLocalTime();
        java.time.LocalTime start = plan.getSessionStart();
        java.time.LocalTime end = plan.getSessionEnd();
        if (start.equals(end)) return false; // zero-width window -- never satisfiable either way, checked explicitly rather than relying on the overnight branch below to coincidentally produce false
        if (start.isBefore(end)) {
            // Ordinary same-day window, e.g. "09:00 -> 15:00".
            return !nowTime.isBefore(start) && nowTime.isBefore(end);
        }
        // start > end: an overnight window, e.g. "22:00 -> 02:00" -- inside session if now has
        // reached the start (still "today", before midnight) OR now hasn't yet reached the end
        // (already "tomorrow" relative to start, before the close).
        return !nowTime.isBefore(start) || nowTime.isBefore(end);
    }

    /**
     * The authoritative execution-time gate for a plan-backed signal: verifies ownership,
     * credential match, enabled state, session, direction, and coin universe before any order
     * reaches the exchange. Deliberately called TWICE by AutoTradeService — once early
     * (replacing a plan-unaware enabledSymbols-only check) and once again immediately before the
     * exchange call, closing the race where a plan is disabled or its session ends between
     * signal creation and asynchronous execution. A single early check alone would leave that
     * window open.
     *
     * Never trusts signal.planId as a bare security boundary: re-derives userId/credentialId
     * ownership from the actual RiskProfile row this execution is running under, not from
     * whatever the request claimed.
     */
    public PlanAuthorizationResult authorizeExecution(String planId, String profileUserId, String profileCredentialId,
                                                        String symbol, BrokerAdapter adapter, BrokerCredential credential,
                                                        java.util.Set<String> profileEnabledSymbols) {
        if (planId == null) return PlanAuthorizationResult.allow(); // no-plan signal -- caller falls back to its own legacy check
        StrategyPlan plan;
        try {
            plan = strategyPlanRepo.findById(planId).orElse(null);
        } catch (Exception e) {
            return PlanAuthorizationResult.deny("Could not look up strategy plan " + planId + ": " + e.getMessage());
        }
        if (plan == null) return PlanAuthorizationResult.deny("Strategy plan " + planId + " no longer exists.");
        if (!java.util.Objects.equals(plan.getUserId(), profileUserId)) {
            return PlanAuthorizationResult.deny("Strategy plan " + planId + " does not belong to this user.");
        }
        if (!java.util.Objects.equals(plan.getCredentialId(), profileCredentialId)) {
            return PlanAuthorizationResult.deny("Strategy plan " + planId + " does not belong to this credential.");
        }
        if (!plan.isEnabled()) return PlanAuthorizationResult.deny("Strategy plan \"" + plan.getName() + "\" is disabled.");
        if (!isSessionConfigValid(plan)) {
            return PlanAuthorizationResult.deny("Strategy plan \"" + plan.getName() + "\" has an invalid session configuration.");
        }
        if (!isWithinSession(plan)) {
            return PlanAuthorizationResult.deny("Strategy plan \"" + plan.getName() + "\" own trading session is not currently open.");
        }
        // Every signal that reaches this far is LONG -- this codebase's own scanner never
        // dispatches anything else (see AutonomousScannerService's own long-only filter). A
        // plan explicitly configured SHORT-only must not accept it.
        if (plan.getDirection() == TradeDirection.SHORT) {
            return PlanAuthorizationResult.deny("Strategy plan \"" + plan.getName() + "\" is configured SHORT-only.");
        }
        // The account's own explicit symbol whitelist must always win, even for a plan-backed
        // signal — see isSymbolInPlanUniverse's own javadoc.
        if (!isSymbolInPlanUniverse(plan, symbol, adapter, credential, profileEnabledSymbols)) {
            return PlanAuthorizationResult.deny("\"" + symbol + "\" is not in strategy plan \"" + plan.getName()
                + "\" own coin universe (intersected with this account's own enabled-symbols whitelist).");
        }
        return PlanAuthorizationResult.allow();
    }

    /**
     * The server-authoritative re-derivation of a plan's own currently-allowed symbol set,
     * rather than trusting whatever symbol a request claims. Recomputes the same core universe
     * AutonomousScannerService.scanForPlan itself builds (TIER1 + this plan's own fixed
     * enabledSymbols + this plan's own dynamic-universe candidates, if enabled), so authorization
     * can never drift from what the scanner itself was actually allowed to act on.
     *
     * Plan universe = (plan symbols + dynamic universe, if enabled) ∩ profile enabledSymbols.
     * TIER1 membership is not a free pass; it's just one more candidate source that still has to
     * survive the same intersection against the account's own explicit whitelist as everything
     * else — an account that never explicitly enabled a TIER1 symbol never trades it, plan or no
     * plan.
     */
    private boolean isSymbolInPlanUniverse(StrategyPlan plan, String symbol, BrokerAdapter adapter, BrokerCredential credential,
                                            java.util.Set<String> profileEnabledSymbols) {
        if (profileEnabledSymbols == null || !profileEnabledSymbols.contains(symbol)) return false;
        if (AutonomousScannerService.TIER1_SYMBOLS.contains(symbol)) return true;
        if (plan.getEnabledSymbols() != null && plan.getEnabledSymbols().contains(symbol)) return true;
        if (plan.isDynamicUniverseEnabled()) {
            try {
                return dynamicUniverseService.selectTopCandidates(adapter, credential, plan.getDynamicUniverseMaxSymbols()).contains(symbol);
            } catch (Exception e) {
                // Fail closed: an authorization check that cannot verify a dynamic candidate's
                // own current eligibility must not treat that failure as permission.
                return false;
            }
        }
        return false;
    }

    public record PlanAuthorizationResult(boolean authorized, String reason) {
        public static PlanAuthorizationResult allow() { return new PlanAuthorizationResult(true, null); }
        public static PlanAuthorizationResult deny(String reason) { return new PlanAuthorizationResult(false, reason); }
    }

    /**
     * Atomically claims execution against a plan: a single findAndModify verifying ownership,
     * credential, enabled, AND version all at once, in the exact same operation that registers
     * the plan-level execution claim. Unlike authorizeExecution's own plain read (still used
     * earlier, as a cheap pre-filter, and still needed for session/direction/universe checks
     * this atomic operation deliberately does NOT fold in — those are either time-based or
     * re-validated live, not the kind of concurrent-write race this claim exists to close), a
     * version mismatch here means the plan was edited or disabled after this signal was
     * generated, and the claim atomically fails rather than racing a separate read.
     */
    public boolean claimPlanExecution(String planId, long expectedVersion, String profileUserId, String profileCredentialId) {
        if (planId == null) return true; // no-plan signal -- nothing to claim, caller's own null-check already allowed it through authorizeExecution
        var criteria = org.springframework.data.mongodb.core.query.Criteria.where("id").is(planId)
            .and("userId").is(profileUserId)
            .and("credentialId").is(profileCredentialId)
            .and("enabled").is(true)
            .and("version").is(expectedVersion);
        var updated = mongoTemplate.findAndModify(
            org.springframework.data.mongodb.core.query.Query.query(criteria),
            new org.springframework.data.mongodb.core.query.Update().inc("executionInFlightCount", 1),
            org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
            StrategyPlan.class);
        return updated != null;
    }

    /**
     * The release counterpart to claimPlanExecution — called unconditionally by every caller
     * once its own execution attempt is finished (successfully or not), same "always release
     * what you claimed" discipline as RiskProfileService.markExecutionFinished.
     */
    public void releasePlanExecution(String planId) {
        if (planId == null) return;
        mongoTemplate.updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(planId)
                .and("executionInFlightCount").gt(0)),
            new org.springframework.data.mongodb.core.query.Update().inc("executionInFlightCount", -1),
            StrategyPlan.class);
    }
}
