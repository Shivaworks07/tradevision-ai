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
 * User's own explicit design (multi-strategy-plan platform): a user can create N strategy plans
 * per credential, each independently owning its own timeframe, direction, coin universe, risk,
 * and exit rules -- with the account-level RiskProfile ceiling always winning over any plan's
 * own, narrower setting.
 *
 * Review finding ("StrategyPlan model has stale documentation" -- external review, tenth pass,
 * P2, same context as StrategyPlan's own class javadoc): corrected. As of this pass, this class
 * provides: the CRUD + ownership-checked API layer (create/update/setEnabled/delete/list),
 * direction-vs-market-type validation, session config validation and evaluation
 * (isSessionConfigValid/isWithinSession, including overnight sessions), the migration path
 * (getOrCreateDefaultPlan), multi-plan fetch for the scanner (getEnabledPlans, correctly
 * returning empty rather than a disabled default when a user has turned every plan off), and
 * the actual execution-time authorization gate (authorizeExecution) that AutoTradeService calls
 * twice per signal -- verifying plan ownership, credential match, enabled state, session,
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
     * User's own explicit design: "later user should have default option as of now what our bot
     * capable that one make it default." The actual migration mechanism -- called lazily (on
     * first access to a credential's plans, not via a bulk migration job this pass doesn't
     * build) so a credential that already has a RiskProfile but has never been touched by this
     * new plan system gets exactly one plan created, mirroring today's pre-multi-plan behavior
     * field-for-field: same enabledSymbols, same dynamicUniverseEnabled/MaxSymbols, same
     * riskPerTradePercent/maxConcurrentTrades, same scan timeframe convention (RiskProfile's own
     * "1h" default), LONG-only (matching this codebase's own spot-only, long-only architecture
     * -- see TradeDirection's own enum javadoc). Idempotent: if a default plan already exists
     * for this credential, returns it unchanged rather than creating a duplicate.
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
     * User's own explicit design: "TradeVision shouldn't generate a SHORT order on an account
     * that cannot actually short." The actual enforcement point for TradeDirection's own
     * disclosed limitation -- called at plan creation/update time (by whatever future
     * controller/service accepts a plan's own direction field from a user), not left as
     * something the TradeDirection enum's mere existence implies is safe. Rejects SHORT/BOTH for
     * every current BrokerType, not just BINANCE: MUDREX has no working BrokerAdapter
     * implementation at all yet (see BrokerType's own javadoc), so there is no evidence it would
     * support shorting either -- the safe default is rejecting until a real adapter proves
     * otherwise, not assuming support for a connection type that doesn't functionally exist.
     */
    public void validateDirectionForMarket(TradeDirection direction, BrokerCredential credential) {
        if (direction == TradeDirection.LONG) return;
        throw new IllegalArgumentException("Direction " + direction + " is not valid for this credential (" + credential.getBroker()
            + "): no broker connection this codebase currently supports can short-sell without margin borrowing, which isn't "
            + "implemented for any of them. Only LONG is valid until a margin/futures connection type exists and is proven to support it.");
    }

    /**
     * User's own explicit design: "Run multiple plans simultaneously." The actual multi-plan
     * fetch AutonomousScannerService's own scan loop uses -- every plan the user has explicitly
     * turned on for this credential. Falls back to a single-element list containing the
     * migration-created default plan when none exist yet at all, so a credential is never left
     * with zero plans to scan (the same behavior this codebase had before multi-plan support
     * existed, now expressed as "exactly one plan" rather than "no plan concept at all").
     */
    /**
     * Review finding ("disabling all plans can still cause the default plan to be returned" --
     * external review, seventh pass, P0, confirmed real by direct inspection before any fix was
     * attempted: if a credential's own default plan already existed but was disabled,
     * getOrCreateDefaultPlan's own findByCredentialIdAndDefaultPlanTrue found and returned it
     * AS-IS -- never re-enabling it -- meaning this method would return a list containing a
     * DISABLED plan, and the scanner trusted this method's own name without re-verifying
     * isEnabled() itself. A user who deliberately turned every plan off would still have the
     * scanner process the disabled default): the actual fix -- distinguishes "no plan has EVER
     * existed for this credential" (the real migration case, where creating and returning a
     * fresh default is correct) from "plans exist but the user has disabled all of them" (where
     * the correct behavior is exactly what the review specified: "No enabled plans -> NO
     * AUTONOMOUS TRADING. Do not automatically fall back to a disabled default."). Only the
     * first case ever calls getOrCreateDefaultPlan; the second returns an empty list, and an
     * empty list from this method now means what its own name says.
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
     * User's own explicit design: "+ Create Strategy Plan... then the user can click + Create
     * Strategy Plan and create another." No artificial cap on how many plans a user can create,
     * matching "10, 20, 50 or more strategy plans, subject to sensible system/resource limits" --
     * this codebase currently has no resource-limiting mechanism to enforce a concrete number
     * against, so none is invented here; ownership (the credential must belong to this user) is
     * the only check performed.
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
     * User's own explicit design: a plan's own settings can be changed after creation (the
     * mockup's own editable form implies this). Ownership-checked -- a user may only update
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
        // Review finding ("StrategyPlan update() / setEnabled() can lose concurrent changes" --
        // external review, fifteenth pass, P0, full context in StrategyPlan.version's own field
        // javadoc): no manual increment here anymore -- @Version on that field means
        // strategyPlanRepo.save() below is now genuinely optimistically locked. A concurrent
        // modification since `plan` was read above throws OptimisticLockingFailureException
        // here, converted to a clear, actionable message by the controller's own catch block.
        return strategyPlanRepo.save(plan);
    }

    /**
     * Review finding ("Session configuration validation needs strengthening" -- external
     * review, sixth pass, P1, confirmed real: StrategyPlanRequest validated numeric fields via
     * @Positive/@PositiveOrZero but never checked that DAILY/CUSTOM_DAYS actually supplied a
     * usable session at all): the actual API-boundary validation -- rejects a request outright
     * (before anything is ever saved) rather than allowing a broken config to reach
     * isSessionConfigValid's own fail-closed handling at evaluation time. This doesn't make that
     * runtime check redundant: a plan saved by an EARLIER version of this validation (or edited
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

    /** User's own explicit design: "Turn individual strategy plans ON/OFF." Ownership-checked, same as update. */
    public StrategyPlan setEnabled(String userId, String planId, boolean enabled) {
        StrategyPlan plan = findOwnedPlan(userId, planId);
        plan.setEnabled(enabled);
        plan.setUpdatedAt(LocalDateTime.now());
        // Review finding ("StrategyPlan update() / setEnabled() can lose concurrent changes" --
        // external review, fifteenth pass, P0, full context in update()'s own updated comment
        // above): same fix -- no manual increment, @Version on save() below now provides the
        // real optimistic-locking guarantee.
        return strategyPlanRepo.save(plan);
    }

    /**
     * Ownership-checked deletion. Deliberately does NOT flatten or otherwise touch any position
     * this plan may still have open -- Position.planId remains a valid historical reference to a
     * now-deleted plan (the same "orphan a foreign key rather than cascade-delete real positions"
     * choice this codebase already makes elsewhere, e.g. OrphanedOco not cascading from Position
     * deletion). A plan with open positions should be disabled, not deleted, if the intent is to
     * stop it from opening NEW ones while still managing what it already holds.
     */
    /**
     * User's own explicit design: "Deleting a plan with open positions is still dangerous...
     * DELETE plan + open positions -> REJECT or: archive plan rather than physically deleting
     * it." Implements the REJECT half of that -- a plan with any OPEN position still depending
     * on it (for max-hold/session/signal-reversal/exit-policy management, all of which look the
     * plan up by Position.planId) cannot be deleted at all. The user must close those positions
     * first, or simply disable the plan (which already stops new entries without losing the
     * exit-management policy an open position still needs).
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
     * Review finding ("misconfigured session fails OPEN" -- external review, sixth pass, P1,
     * confirmed real by direct inspection before any fix was attempted -- this method used to
     * literally return true for a missing start/end/timezone or an invalid timezone string on a
     * non-ALWAYS_ON plan): the actual gate a caller checks BEFORE trusting isWithinSession's own
     * answer at all. A plan can only be trusted to answer "is now inside my own session" if it
     * is ALWAYS_ON (nothing to misconfigure) or its DAILY/CUSTOM_DAYS configuration is complete
     * and parseable. Deliberately does NOT decide what a caller should DO about invalid
     * config -- the right response differs by caller (the scanner should stop new entries; the
     * end-of-session reconciliation step should NOT flatten a position over a config error, per
     * the review's own explicit instruction: "Existing positions should not be blindly
     * flattened merely because the configuration is malformed").
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
     * User's own explicit design: "A user-configurable recurring trading window attached to
     * each Strategy Plan, with its own timezone." The single source of truth for "is this plan
     * currently inside its own configured session," used identically by the scanner (stop new
     * entries) and the reconciliation pass (close at session end) -- so the two can never
     * disagree about where the boundary actually is. ALWAYS_ON (the default) is always true, by
     * definition -- the user's own explicit instruction that 24/7 must never behave any
     * differently than this codebase's own pre-session-feature behavior.
     *
     * Review finding ("misconfigured session fails OPEN" -- external review, sixth pass, P1,
     * same context as isSessionConfigValid's own javadoc): invalid config now returns false
     * (fail CLOSED -- no new entries), the opposite of this method's own previous behavior.
     * Callers that need to distinguish "genuinely outside a valid session" from "config itself
     * is broken" (e.g. end-of-session, which must not flatten over a config error) call
     * isSessionConfigValid first, separately -- this method alone cannot express that
     * distinction, by design, since collapsing them into one boolean is exactly what caused the
     * original bug.
     *
     * Review finding ("midnight-crossing sessions" -- external review, sixth pass, P2, confirmed
     * real: a 22:00-02:00 window previously always evaluated false, since start > end made the
     * same-day range comparison impossible to satisfy): a session where start > end is now
     * correctly treated as spanning midnight -- "now" is inside it if now >= start OR now < end,
     * rather than requiring both simultaneously (which is exactly backwards for an overnight
     * window and was the actual bug).
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
     * Review finding ("Dynamic Universe is disconnected from the execution gate" / "Plan
     * OFF/session changes are not enforced at the final execution gate" / "Plan ownership must
     * be verified at execution" -- external review, eighth pass, P0, confirmed real by direct
     * inspection before any fix was attempted -- AutoTradeService.evaluateForProfile's own
     * enabledSymbols check was the ONLY symbol gate in the entire execution path, and no
     * ownership/credential/enabled/session/direction re-check of the plan existed anywhere):
     * the actual, authoritative execution-time gate this codebase was missing entirely. This is
     * deliberately called TWICE by AutoTradeService -- once early (replacing the old
     * plan-unaware enabledSymbols check) and once again immediately before the exchange call
     * (closing the review's own named race: plan disabled/session ended between signal creation
     * and asynchronous execution) -- rather than once, since the review's own point is that a
     * single early check is exactly what left the race open in the first place.
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
        // Review finding (P1 #6 -- "Plan symbol universe always includes BTC/ETH/SOL/BNB/XRP;
        // profile symbol whitelist ignored when a plan exists"): full context in
        // isSymbolInPlanUniverse's own updated javadoc -- the account's own explicit whitelist
        // must always win, even for a plan-backed signal.
        if (!isSymbolInPlanUniverse(plan, symbol, adapter, credential, profileEnabledSymbols)) {
            return PlanAuthorizationResult.deny("\"" + symbol + "\" is not in strategy plan \"" + plan.getName()
                + "\" own coin universe (intersected with this account's own enabled-symbols whitelist).");
        }
        return PlanAuthorizationResult.allow();
    }

    /**
     * Review finding ("Dynamic candidate authorization needs durable provenance" -- external
     * review, eighth pass, P1, same context as authorizeExecution's own javadoc): the server-
     * authoritative re-derivation of a plan's own currently-allowed symbol set, rather than
     * trusting whatever symbol a request claims. Recomputes the SAME core universe
     * AutonomousScannerService.scanForPlan itself builds (TIER1 + this plan's own fixed
     * enabledSymbols + this plan's own dynamic-universe candidates, if enabled) -- so
     * authorization can never drift from what the scanner itself was actually allowed to act on.
     */
    /**
     * Review finding (P1 #6 -- "Plan symbol universe always includes BTC/ETH/SOL/BNB/XRP;
     * profile symbol whitelist ignored when a plan exists"): confirmed real -- TIER1 membership
     * alone used to be an unconditional pass here, meaning a user who whitelisted only
     * "ADAUSDT" on their own risk profile could still have a plan-backed signal on BTC/ETH/SOL/
     * BNB/XRP execute with real money, since none of those five were ever actually checked
     * against what the account explicitly enabled. Fixed per the review's own stated remedy:
     * plan universe = (plan symbols + dynamic universe, if enabled) ∩ profile enabledSymbols --
     * TIER1 is no longer a free pass; it's just one more candidate source that still has to
     * survive the SAME intersection against the account's own explicit whitelist as everything
     * else. An account that never explicitly enabled a TIER1 symbol never trades it, plan or no
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
     * Review finding ("Strategy Plan disable vs execution is still technically non-atomic" --
     * external review, fourteenth pass, P1, full context in StrategyPlan.version's own field
     * javadoc): the actual atomic claim -- a single findAndModify verifying ownership,
     * credential, enabled, AND version all at once, in the exact same operation that registers
     * the plan-level execution claim. Unlike authorizeExecution's own plain read (still used
     * earlier, as a cheap pre-filter, and still needed for session/direction/universe checks
     * this atomic operation deliberately does NOT attempt to fold in -- those are either time-
     * based or re-validated live, not the kind of concurrent-write race this claim exists to
     * close), a version mismatch here means the plan was edited or disabled after this signal
     * was generated, and the claim atomically fails rather than racing a separate read.
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
     * Review finding, same context: the other half -- called unconditionally by every caller of
     * claimPlanExecution once its own execution attempt is finished (successfully or not), same
     * "always release what you claimed" discipline as RiskProfileService.markExecutionFinished.
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
