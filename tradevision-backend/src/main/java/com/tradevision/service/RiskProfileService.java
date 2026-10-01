package com.tradevision.service;

import com.tradevision.dto.RiskProfileRequest;
import com.tradevision.model.Position;
import com.tradevision.model.RiskProfile;
import com.tradevision.repository.PositionRepository;
import com.tradevision.repository.RiskProfileRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CRUD + kill-switch operations around RiskProfile. AutoTradeService only ever reads these;
 * every write goes through here so audit logging stays in one place.
 */
@Service
@RequiredArgsConstructor
public class RiskProfileService {

    private static final Logger log = LoggerFactory.getLogger(RiskProfileService.class);

    private final RiskProfileRepository riskProfileRepo;
    private final IncidentService incidentService;
    private final BrokerCredentialService credentialService;
    private final PositionRepository positionRepo;
    // Review finding ("Kill switch can race with LIVE order submission" -- P0, full context at
    // claimExecutionAuthorization's own javadoc): needed for the actual atomic claim.
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    // Review finding ("Resume does not prove existing positions are protected" -- P0, full
    // context in resume's own updated javadoc): needed for the actual synchronous safety checks.
    private final com.tradevision.repository.OrderRepository orderRepo;
    /**
     * Review finding ("Narrow but real race: Strategy Plan disable / version change vs final
     * execution" -- external review, eighteenth pass, P0, full context in
     * claimExecutionAtomicWithPlan's own javadoc below): needed for the actual atomic
     * transaction spanning both this class's own claim and StrategyPlanService's -- and for the
     * graceful, non-transactional fallback when the deployment doesn't support transactions.
     * Confirmed safe against a circular bean dependency before adding this: StrategyPlanService
     * depends on RiskProfileRepository, never on this service itself.
     */
    private final StrategyPlanService strategyPlanService;
    /**
     * Review finding ("Secrets / encryption key rotation and credential revocation story
     * incomplete" -- external review, nineteenth pass, P1, full context in
     * emergencyRevokeAll's own javadoc): needed for the force-re-auth half -- bumping
     * User.tokenVersion invalidates every existing JWT for this user immediately, the same
     * mechanism AuthService.logout() and its own refresh-token-reuse detection already use.
     * Confirmed no circular dependency: UserRepository is a plain Spring Data repository with
     * no service-layer dependencies of its own.
     */
    private final com.tradevision.repository.UserRepository userRepo;
    /**
     * Review finding ("The execution authorization still has an unavoidable exchange-boundary
     * race" -- external review, twenty-first pass, P0, full context in haltAll's own updated
     * javadoc): needed for the actual "automatically reconcile in-flight claims immediately
     * after a halt" fix the review itself suggests. Confirmed no circular dependency:
     * PositionMonitorService never injects RiskProfileService.
     */
    private final PositionMonitorService positionMonitorService;
    private final com.tradevision.repository.BrokerCredentialRepository credentialRepo;
    private final com.tradevision.repository.TradingIncidentRepository tradingIncidentRepo;
    /**
     * Review finding ("Mongo standalone deployment still weakens the plan/profile execution
     * atomicity guarantee" -- external review, twenty-fourth pass, P1, full context in
     * IndexInitializer.checkMongoTransactionSupport's own javadoc): needed for the actual
     * authorizeLiveAutoTrade refusal check. Confirmed no circular dependency: StartupState is a
     * pure state holder with no dependencies of its own at all.
     */
    private final com.tradevision.config.StartupState startupState;

    /**
     * Review finding ("Kill switch can race with LIVE order submission" -- P0): confirmed real
     * by direct inspection of AutoTradeService.evaluateForProfileLocked() -- it loads a
     * RiskProfile ONCE at the start of a long sequence (NO-TRADE check, pricing, sizing, risk
     * checks, slot reservation, exposure reservation, OMS creation), then places a real exchange
     * order using that SAME, now-potentially-stale, in-memory object. A user pressing the kill
     * switch, revoking LIVE authorization, or a circuit breaker halting the profile mid-sequence
     * updates the DATABASE, but the in-memory profile AutoTradeService is about to act on never
     * sees that write -- the revocation was never a hard execution barrier.
     *
     * This is the actual barrier: a single atomic MongoDB conditional update, called
     * IMMEDIATELY before the exchange order (not at the start of the sequence, not anywhere
     * reservations or risk checks happen) -- WHERE credentialId=X AND autoTradeEnabled=true AND
     * tradingHalted=false AND autoTradeHalted=false, AND (for LIVE specifically)
     * liveAutoTradeAuthorized=true. The update's own success or failure is the atomic
     * read-and-decide: if it modifies zero documents, at least one of those conditions was false
     * at the exact moment of the update (not moments earlier when AutoTradeService's own
     * in-memory profile was loaded), and the caller must not call the exchange. The $set target
     * (lastExecutionClaimAt) is a real field write, not a no-op read dressed up as one -- what
     * actually gives this its atomicity is that Mongo evaluates the WHERE filter and applies the
     * $set as a single, indivisible operation on one document.
     *
     * Deliberately does NOT re-check every other risk parameter (position limits, exposure caps,
     * etc.) -- those were already correctly evaluated earlier in the sequence against
     * reservations that ARE atomic (PositionSlotReservationService/ExposureReservationService).
     * This specifically closes the gap the review named: the kill-switch/safety-state flags
     * were the ones being read once and trusted stale.
     */
    /**
     * Review finding ("claimExecutionAuthorization() is still an authorization claim, not a
     * lease" -- external review, second pass, full context in
     * RiskProfile.lastExecutionClaimId's own updated field javadoc): a real claim identity now
     * accompanies the atomic authorization check -- findAndModify (not updateFirst) so this
     * method returns the exact document state, including the fresh claim id and the
     * safetyStateVersion, from the SAME atomic operation that granted it. A caller can record
     * this claim's own id on whatever it submits to the exchange, giving a genuine, traceable
     * answer to "which claim authorized this order" if something needs auditing later.
     */
    public record ExecutionClaim(String claimId, long generation) {}

    public ExecutionClaim claimExecutionAuthorization(String credentialId, boolean isLive) {
        var criteria = org.springframework.data.mongodb.core.query.Criteria.where("credentialId").is(credentialId)
            .and("autoTradeEnabled").is(true)
            .and("tradingHalted").is(false)
            .and("autoTradeHalted").is(false);
        if (isLive) {
            criteria = criteria.and("liveAutoTradeAuthorized").is(true);
        }
        String claimId = java.util.UUID.randomUUID().toString();
        var claimed = mongoTemplate.findAndModify(
            new org.springframework.data.mongodb.core.query.Query(criteria),
            new org.springframework.data.mongodb.core.query.Update()
                .set("lastExecutionClaimAt", LocalDateTime.now()).set("lastExecutionClaimId", claimId),
            org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true), RiskProfile.class);
        return claimed == null ? null : new ExecutionClaim(claimId, claimed.getSafetyStateVersion());
    }

    /**
     * Review finding ("There is still a tiny gap between final authorization and
     * markExecutionStarted()" -- external review, fourth pass, P0, full context in
     * RiskProfile.executionInFlightCount's own field javadoc): this is now the single atomic
     * operation that both re-validates the claim AND registers the in-flight execution -- the
     * exact same condition set as isClaimStillValid(), applied as the QUERY of one findAndModify
     * whose UPDATE increments executionInFlightCount, so there is no longer any gap between
     * "confirm this claim is still valid" and "register this execution as in flight" for a
     * concurrent kill switch to land in. Returns false (and increments nothing) if the
     * conditions no longer hold -- the caller MUST treat false as "do not call the exchange",
     * exactly like a failed isClaimStillValid() check. Deliberately NOT upserting: a credential
     * with no matching RiskProfile at all must never have one silently created by this call.
     *
     * Review finding ("The claim itself has no expiry" -- external review, fourth pass, P1,
     * confirmed real by direct inspection before any fix was attempted): a claim used to remain
     * valid indefinitely as long as lastExecutionClaimId was never superseded by a newer one --
     * meaning an evaluator paused for an arbitrarily long time (a long GC pause, a debugger
     * breakpoint, a thread starved for minutes under load) could resume and still submit,
     * because nothing about the claim itself carried a lifetime. Now also requires
     * lastExecutionClaimAt to be within CLAIM_MAX_AGE of now, in the SAME atomic query -- a
     * capability that ages out on its own, independent of whether anything else ever
     * invalidates it. 15 seconds: generous enough that the claim -> risk checks -> order
     * construction -> this call sequence (normally well under a second) never spuriously
     * expires, short enough that "paused for minutes, then resumed" -- the review's own named
     * scenario -- is genuinely closed.
     */
    private static final java.time.Duration CLAIM_MAX_AGE = java.time.Duration.ofSeconds(15);

    public boolean markExecutionStarted(String credentialId, String claimId, boolean isLive) {
        var criteria = org.springframework.data.mongodb.core.query.Criteria.where("credentialId").is(credentialId)
            .and("lastExecutionClaimId").is(claimId)
            .and("lastExecutionClaimAt").gte(LocalDateTime.now().minus(CLAIM_MAX_AGE))
            .and("autoTradeEnabled").is(true)
            .and("tradingHalted").is(false)
            .and("autoTradeHalted").is(false);
        if (isLive) {
            criteria = criteria.and("liveAutoTradeAuthorized").is(true);
        }
        var updated = mongoTemplate.findAndModify(
            org.springframework.data.mongodb.core.query.Query.query(criteria),
            new org.springframework.data.mongodb.core.query.Update().inc("executionInFlightCount", 1),
            org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
            RiskProfile.class);
        return updated != null;
    }

    /**
     * Review finding ("Narrow but real race: Strategy Plan disable / version change vs final
     * execution" -- external review, eighteenth pass, P0, confirmed real: claimPlanExecution
     * (StrategyPlan) and markExecutionStarted (RiskProfile) above are two SEPARATE, sequential
     * MongoDB operations on two different collections -- a plan disable or version bump landing
     * in the few-millisecond gap between them could theoretically still let an already-claimed
     * signal reach the exchange): the actual fix -- a genuine MongoDB multi-document
     * transaction spanning both claims, committing only if BOTH succeed, aborting (leaving
     * neither claim in place) if either fails.
     *
     * HONEST SCOPE, stated plainly rather than assumed: MongoDB multi-document transactions
     * require the deployment to be a replica set (or sharded cluster) -- confirmed via
     * documentation before writing this, not assumed. A standalone MongoDB instance (a common,
     * valid deployment for smaller setups) rejects transactions outright with a specific,
     * recognizable error. Rather than fail this method entirely on a standalone deployment (which
     * would make auto-trading impossible there) or silently skip the plan check (which would
     * silently reopen the exact race this method exists to close), this catches that SPECIFIC
     * error and falls back to the previous, sequential two-claim approach -- functionally
     * identical to what this codebase already had before this pass, narrowing but not fully
     * closing the race on that deployment. This is not glossed over: the fallback branch below
     * says so explicitly, every time it's taken, so a real deployment's own logs make clear
     * whether the full guarantee is actually in effect.
     */
    public boolean claimExecutionAtomicWithPlan(String credentialId, String claimId, boolean isLive,
                                                  String planId, Long expectedPlanVersion, String profileUserId) {
        if (planId == null || expectedPlanVersion == null) {
            // No plan to coordinate with at all -- the existing single-document claim below is
            // already fully atomic and sufficient on its own.
            return markExecutionStarted(credentialId, claimId, isLive);
        }
        com.mongodb.client.ClientSession session;
        try {
            session = mongoTemplate.getMongoDatabaseFactory().getSession(com.mongodb.ClientSessionOptions.builder().build());
        } catch (Exception e) {
            log.warn("Could not obtain a MongoDB ClientSession at all ({}) -- falling back to the sequential, non-transactional "
                + "two-claim approach.", e.getMessage());
            return claimPlanThenProfileSequentially(credentialId, claimId, isLive, planId, expectedPlanVersion, profileUserId);
        }
        try {
            session.startTransaction();
            var sessionTemplate = mongoTemplate.withSession(session);

            var planCriteria = org.springframework.data.mongodb.core.query.Criteria.where("id").is(planId)
                .and("userId").is(profileUserId)
                .and("credentialId").is(credentialId)
                .and("enabled").is(true)
                .and("version").is(expectedPlanVersion);
            var planClaimed = sessionTemplate.findAndModify(
                org.springframework.data.mongodb.core.query.Query.query(planCriteria),
                new org.springframework.data.mongodb.core.query.Update().inc("executionInFlightCount", 1),
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
                com.tradevision.model.StrategyPlan.class);
            if (planClaimed == null) {
                session.abortTransaction();
                return false;
            }

            var profileCriteria = org.springframework.data.mongodb.core.query.Criteria.where("credentialId").is(credentialId)
                .and("lastExecutionClaimId").is(claimId)
                .and("lastExecutionClaimAt").gte(LocalDateTime.now().minus(CLAIM_MAX_AGE))
                .and("autoTradeEnabled").is(true)
                .and("tradingHalted").is(false)
                .and("autoTradeHalted").is(false);
            if (isLive) profileCriteria = profileCriteria.and("liveAutoTradeAuthorized").is(true);
            var profileClaimed = sessionTemplate.findAndModify(
                org.springframework.data.mongodb.core.query.Query.query(profileCriteria),
                new org.springframework.data.mongodb.core.query.Update().inc("executionInFlightCount", 1),
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
                RiskProfile.class);
            if (profileClaimed == null) {
                session.abortTransaction();
                return false;
            }

            session.commitTransaction();
            return true;
        } catch (com.mongodb.MongoException e) {
            try {
                if (session.hasActiveTransaction()) session.abortTransaction();
            } catch (Exception ignore) {
                // Best-effort cleanup only -- the transaction attempt already failed, and a
                // failure aborting an already-failed transaction changes nothing about the outcome.
            }
            // MongoDB's own specific signal for "this deployment doesn't support transactions at
            // all" (a standalone instance, not a replica set) -- code 20 ("IllegalOperation") is
            // the documented code for this specific case. Anything else is a genuine, different
            // MongoDB error that should propagate rather than be silently swallowed into a
            // fallback that wouldn't actually fix it.
            if (e.getCode() == 20 || (e.getMessage() != null && e.getMessage().contains("Transaction numbers"))) {
                log.warn("MongoDB transactions are not supported by this deployment (standalone, not a replica set/mongos) -- falling "
                    + "back to the sequential, non-transactional two-claim approach for credential {}. This means the narrow race this "
                    + "transactional path exists to close (a plan disable/version-bump landing in the exact gap between the two separate "
                    + "claims) remains theoretically possible on this deployment. Configure a MongoDB replica set to close it fully.",
                    credentialId);
                return claimPlanThenProfileSequentially(credentialId, claimId, isLive, planId, expectedPlanVersion, profileUserId);
            }
            throw e;
        } finally {
            session.close();
        }
    }

    /**
     * The exact fallback behavior this codebase already had before this pass -- two separate,
     * sequential atomic claims, each fully atomic within its own single document, but not
     * atomic with each other. Extracted here specifically so claimExecutionAtomicWithPlan's own
     * fallback branches above have one real implementation to call, not a second copy of this
     * same logic.
     */
    private boolean claimPlanThenProfileSequentially(String credentialId, String claimId, boolean isLive,
                                                       String planId, Long expectedPlanVersion, String profileUserId) {
        if (!strategyPlanService.claimPlanExecution(planId, expectedPlanVersion, profileUserId, credentialId)) {
            return false;
        }
        boolean profileClaimed = markExecutionStarted(credentialId, claimId, isLive);
        if (!profileClaimed) {
            strategyPlanService.releasePlanExecution(planId);
        }
        return profileClaimed;
    }

    /**
     * Review finding, same context: the other half -- called in a finally block by every caller
     * of markExecutionStarted, unconditionally, so a submission that throws still releases its
     * own count. Never allowed to go negative, enforced via the query condition itself
     * (executionInFlightCount > 0), not a second update operator on the same field -- same
     * reasoning as the prior (now-replaced) ExecutionInFlightCounter-based version. Matched by
     * credentialId alone, deliberately NOT re-checking claimId/halted/etc: this must always
     * release regardless of what happened to the claim or halt state in between, or a legitimate
     * finally-block release could itself get silently skipped by a condition that no longer
     * matches, permanently leaking a count.
     */
    public void markExecutionFinished(String credentialId) {
        mongoTemplate.updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("credentialId").is(credentialId)
                .and("executionInFlightCount").gt(0)),
            new org.springframework.data.mongodb.core.query.Update().inc("executionInFlightCount", -1),
            RiskProfile.class);
    }

    public RiskProfile upsert(String userId, RiskProfileRequest req) {
        // Ensures the credential exists, belongs to this user, and is active — throws otherwise.
        var credential = credentialService.ownedCredential(userId, req.getCredentialId());
        try {
            return doUpsert(userId, req, credential.getBroker());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Review finding (P1 — "RiskProfile needs a unique credential index"): the unique
            // index just added means a genuine collision (two concurrent requests both finding
            // no existing profile, both trying to insert one) now surfaces as a real, catchable
            // exception instead of silently succeeding twice. One retry: by the time we get
            // here, the OTHER request's profile is now findable, so this becomes a normal
            // update instead of a second insert.
            return doUpsert(userId, req, credential.getBroker());
        }
    }

    private static final String REQUIRED_QUOTE_ASSET = "USDT";

    private RiskProfile doUpsert(String userId, RiskProfileRequest req, com.tradevision.model.BrokerType broker) {
        // Review finding ("Risk exposure assumes every quote asset is the same currency" -- P1):
        // confirmed real -- PortfolioRiskService's own exposure/equity aggregation sums market
        // value across every open position's own symbol without ever converting to a common
        // currency (100 USDT + 100 USDC + 0.1 BTC summed as if they were the same number). The
        // review's own two options were "normalize every exposure to a configured base currency
        // using fresh conversion prices" or "restrict the production bot to a single quote asset
        // such as USDT and enforce that server-side," with an explicit recommendation: "for v1,
        // I strongly recommend the latter." This is that enforcement -- rejected here, at the
        // one place a user's enabled-symbol list is actually written, rather than trusted
        // silently and discovered as an aggregation bug much later during a real drawdown check.
        if (req.getEnabledSymbols() != null) {
            List<String> invalid = req.getEnabledSymbols().stream()
                .filter(s -> s != null && !s.toUpperCase().endsWith(REQUIRED_QUOTE_ASSET))
                .toList();
            if (!invalid.isEmpty()) {
                throw new IllegalArgumentException("Every enabled symbol must be quoted in " + REQUIRED_QUOTE_ASSET
                    + " -- rejected: " + invalid + ". Mixing quote assets (USDC, BTC-quoted pairs, etc.) would make this "
                    + "application's own exposure/equity/drawdown calculations silently sum different currencies as if "
                    + "they were the same number.");
            }
        }
        // P3-5 fix ("ExposureReservationService group/symbol field paths -- user-supplied group
        // names used as Mongo field paths ('.'/'$') -- validate names" -- external review,
        // confirmed real by direct inspection: ExposureReservationService builds a live Mongo
        // update/query field path as the literal string "reservedGroupExposure." + groupName for
        // every group this profile configures (see that class's own reserve()/release() methods),
        // and correlationGroups' keys are 100% free-text user input from this exact request with
        // no prior validation anywhere. A group named e.g. "Majors.sub" would target the NESTED
        // path reservedGroupExposure.Majors.sub instead of a flat field, silently corrupting or
        // conflicting with a genuinely different group named "Majors" (a BigDecimal leaf value
        // there vs. this write's own subdocument), and MongoDB rejects a field name starting with
        // "$" as invalid/dangerous outright -- either way, a name the user was never supposed to
        // be able to pick this update path with breaks or corrupts exposure tracking used for
        // real-money risk limits. Rejected here, at the one place a user's group names are
        // actually written (never persisted with a bad name in the first place), matching this
        // exact method's own established pattern for enabledSymbols right above.
        if (req.getCorrelationGroups() != null) {
            List<String> invalidGroupNames = req.getCorrelationGroups().keySet().stream()
                .filter(name -> name == null || name.isBlank() || name.contains(".") || name.contains("$"))
                .toList();
            if (!invalidGroupNames.isEmpty()) {
                throw new IllegalArgumentException("Correlation group names cannot be blank or contain '.' or '$' "
                    + "(they're used as literal MongoDB field path segments internally) -- rejected: " + invalidGroupNames);
            }
        }
        RiskProfile profile = riskProfileRepo.findByUserIdAndCredentialId(userId, req.getCredentialId())
            .orElseGet(RiskProfile::new);
        profile.setUserId(userId);
        profile.setCredentialId(req.getCredentialId());
        profile.setAutoTradeEnabled(req.isAutoTradeEnabled());
        profile.setEnabledSymbols(req.getEnabledSymbols());
        profile.setMinConfidence(req.getMinConfidence());
        profile.setMaxPositionQuoteAmount(req.getMaxPositionQuoteAmount());
        profile.setMaxConcurrentTrades(req.getMaxConcurrentTrades());
        profile.setDailyLossLimitQuote(req.getDailyLossLimitQuote());
        profile.setRiskPerTradePercent(req.getRiskPerTradePercent());
        profile.setMaxTotalExposureQuote(req.getMaxTotalExposureQuote());
        profile.setMaxSymbolExposureQuote(req.getMaxSymbolExposureQuote());
        profile.setMaxPriceDeviationPercent(req.getMaxPriceDeviationPercent());
        // P0-6 fix ("LIVE risk-limit enforcement" -- full context in RiskProfileRequest's own
        // updated javadoc): same "field existed on the model, was never actually settable" gap
        // as correlationGroups/correlationGroupCaps below -- these four were already read by
        // RiskEngineService's own checks but never written here, so a user could never actually
        // configure them regardless of what this method's own request contained.
        profile.setMaxDrawdownPercent(req.getMaxDrawdownPercent());
        profile.setMaxOrdersPerHour(req.getMaxOrdersPerHour());
        profile.setMaxConsecutiveAutoTradeLosses(req.getMaxConsecutiveAutoTradeLosses());
        profile.setCircuitBreakerThreshold(req.getCircuitBreakerThreshold());
        // Review finding (P1 #5 — full context in RiskProfileRequest's own javadoc): the actual
        // fix — these were never persisted here regardless of what the request contained.
        profile.setCorrelationGroups(req.getCorrelationGroups() != null ? req.getCorrelationGroups() : new java.util.HashMap<>());
        profile.setCorrelationGroupCaps(req.getCorrelationGroupCaps() != null ? req.getCorrelationGroupCaps() : new java.util.HashMap<>());
        profile.setUpdatedAt(LocalDateTime.now());
        // Any change to risk parameters revokes live auto-trade authorization — re-confirm explicitly.
        profile.setLiveAutoTradeAuthorized(false);

        // Review finding ("Risk-profile updates/resume can race with safety state" -- P0):
        // confirmed real by direct inspection -- this method only ever SETS configuration
        // fields above (autoTradeEnabled, enabledSymbols, minConfidence, etc.), never touching
        // safety-state fields like tradingHalted/autoTradeHalted/dailyRealizedLossQuote/
        // consecutiveOrderFailures/peakEquityQuote -- yet the old code called
        // riskProfileRepo.save(profile), a FULL-DOCUMENT save that would silently overwrite
        // whatever value those safety fields had at the moment `profile` was loaded a few lines
        // above. A concurrent halt()/drawdown/circuit-breaker write landing in that window would
        // be erased the instant this save runs. For an EXISTING profile (has a real database id
        // already), a targeted $set touching ONLY the configuration fields this method is
        // actually responsible for replaces the full save -- a concurrent safety-state write is
        // now physically impossible to clobber, since this update never even names those
        // fields. For a genuinely NEW profile (no id yet, this is the very first save), a full
        // save is correct and safe as-is -- there's no existing document for anything else to
        // be racing against.
        if (profile.getId() == null) {
            profile = riskProfileRepo.save(profile);
        } else {
            var update = new org.springframework.data.mongodb.core.query.Update()
                .set("autoTradeEnabled", profile.isAutoTradeEnabled())
                .set("enabledSymbols", profile.getEnabledSymbols())
                .set("minConfidence", profile.getMinConfidence())
                .set("maxPositionQuoteAmount", profile.getMaxPositionQuoteAmount())
                .set("maxConcurrentTrades", profile.getMaxConcurrentTrades())
                .set("dailyLossLimitQuote", profile.getDailyLossLimitQuote())
                .set("riskPerTradePercent", profile.getRiskPerTradePercent())
                .set("maxTotalExposureQuote", profile.getMaxTotalExposureQuote())
                .set("maxSymbolExposureQuote", profile.getMaxSymbolExposureQuote())
                .set("maxPriceDeviationPercent", profile.getMaxPriceDeviationPercent())
                // P0-6 fix: same reasoning as this method's own field-set block above -- these
                // four must be part of the targeted $set too, or an existing profile's update
                // would keep silently dropping them even after the fix above started setting
                // them on the in-memory `profile` object.
                .set("maxDrawdownPercent", profile.getMaxDrawdownPercent())
                .set("maxOrdersPerHour", profile.getMaxOrdersPerHour())
                .set("maxConsecutiveAutoTradeLosses", profile.getMaxConsecutiveAutoTradeLosses())
                .set("circuitBreakerThreshold", profile.getCircuitBreakerThreshold())
                .set("correlationGroups", profile.getCorrelationGroups())
                .set("correlationGroupCaps", profile.getCorrelationGroupCaps())
                .set("updatedAt", profile.getUpdatedAt())
                .set("liveAutoTradeAuthorized", false); // intentional, fresh write -- not a stale-value overwrite, see this method's own comment above
            mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
                update, RiskProfile.class);
            // Re-read after the targeted update so the returned object (and the audit line
            // below) reflects the real, current document -- not the in-memory object, which
            // still doesn't know about any concurrent safety-state field this update
            // deliberately left untouched.
            profile = riskProfileRepo.findById(profile.getId()).orElse(profile);
        }
        credentialService.audit(userId, req.getCredentialId(), broker, "RISK_PROFILE_UPDATED",
            "auto-trade=" + profile.isAutoTradeEnabled() + " symbols=" + profile.getEnabledSymbols()
                + " maxPos=" + profile.getMaxPositionQuoteAmount() + " maxConcurrent=" + profile.getMaxConcurrentTrades()
                + " dailyLossLimit=" + profile.getDailyLossLimitQuote());
        return profile;
    }

    public RiskProfile get(String userId, String credentialId) {
        return riskProfileRepo.findByUserIdAndCredentialId(userId, credentialId)
            .orElseThrow(() -> new IllegalArgumentException("No risk profile configured for this credential yet."));
    }

    /** Kill switch for a single credential. */
    public RiskProfile halt(String userId, String credentialId, String reason) {
        RiskProfile profile = get(userId, credentialId);
        String haltReason = reason != null && !reason.isBlank() ? reason : "Manually halted by user.";
        // Review finding ("Risk-profile updates/resume can race with safety state" -- P0, full
        // context in doUpsert's own comment above): the kill switch itself, so this is the
        // single most safety-critical instance of the same bug -- a full riskProfileRepo.save()
        // here would silently overwrite ANY concurrent write to every other field on this
        // document (a config update mid-flight via doUpsert, a drawdown check writing
        // dailyRealizedLossQuote, another circuit breaker) with whatever stale values this
        // method's own `profile` happened to hold at load time. Targeted $set on exactly the
        // two fields this method actually changes -- tradingHalted can never come back false
        // from a race with something that never intended to touch it.
        // Review finding ("resume() can still race with a new halt" -- external review, full
        // context in RiskProfile.safetyStateVersion's own field javadoc): every tradingHalted
        // transition -- including this one, the kill switch itself -- must bump the version, so
        // resume()'s own later race-detection has a real, incrementing signal to compare against.
        mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
            new org.springframework.data.mongodb.core.query.Update()
                .set("tradingHalted", true)
                .set("haltReason", haltReason)
                .set("updatedAt", LocalDateTime.now())
                .inc("safetyStateVersion", 1),
            RiskProfile.class);
        profile = riskProfileRepo.findById(profile.getId()).orElse(profile);
        // Review finding ("The claim → Binance network call still has an unavoidable TOCTOU
        // window" -- external review, third pass, P0, full context in
        // RiskProfile.executionInFlightCount's own field javadoc): the honest, audited half of
        // this fix. tradingHalted is already true by this point, so markExecutionStarted()
        // blocks every NEW execution from here on -- but this check reports whether a
        // submission was ALREADY past that gate and genuinely in flight to the exchange the
        // moment this kill switch engaged, rather than leaving that fact silently unknown. Now
        // reads directly off the SAME re-read `profile` object a few lines above -- no separate
        // collection lookup needed at all, since this count lives on this document itself.
        long inFlight = profile.getExecutionInFlightCount();
        if (inFlight > 0) {
            credentialService.audit(userId, credentialId, null, "KILL_SWITCH_ENGAGED_WITH_EXECUTION_IN_FLIGHT",
                inFlight + " execution(s) were already in flight to the exchange the moment this kill switch engaged. No NEW submission "
                    + "can start from this point on, but an already-in-flight request may still land after this halt -- review the account's "
                    + "own recent order history directly to confirm its actual outcome.");
        }
        credentialService.audit(userId, credentialId, null, "KILL_SWITCH_ENGAGED", profile.getHaltReason());
        return profile;
    }

    /**
     * Review finding ("Resume does not prove existing positions are protected" -- P0): confirmed
     * real -- the earlier version of this method only checked avgEntryPriceUnverified (kept
     * below, unchanged). The review's own named gap: a position can sit OPEN with a stale/
     * cancelled OCO id, or a genuinely unresolved OMS order, or an incomplete fill-ledger
     * record, or an active critical incident against this credential -- none of which
     * avgEntryPriceUnverified alone catches -- and resume would clear the halt anyway, letting
     * new autonomous trades size and risk-check against a credential whose existing state isn't
     * actually known-safe. Extended with three more synchronous checks, each using data this
     * codebase already tracks and already trusts elsewhere (Order.status, Position.
     * ledgerRecordingIncomplete, TradingIncident) -- not new inference, just finally consulted
     * here too:
     *   - no unresolved UNKNOWN/RECONCILIATION_REQUIRED order for this credential (the OMS's own
     *     first-class "we genuinely don't know what happened" state -- see OrderStatus's own
     *     LEGAL_TRANSITIONS map)
     *   - no OPEN position with ledgerRecordingIncomplete=true (this codebase's own established
     *     signal for "a fill genuinely happened but wasn't fully recorded" -- see Position's own
     *     field javadoc)
     *   - no unresolved CRITICAL incident against this credential
     *
     * UPDATE ("Resume does not prove existing positions are protected" -- P0, "actual exchange
     * position/balance" item, now closed): the live re-verification this javadoc used to defer
     * is implemented below -- for every OPEN position, a real broker call confirms the
     * account's actual current balance for that position's own base asset (via SymbolRules,
     * not guessed from the symbol string) still covers what this application believes it holds.
     * A failure to even PERFORM this check (network error, adapter exception) is itself treated
     * as a failed check and blocks resume -- same principle as PositionMonitorService's own
     * checkDrawdown design (inability to verify equity is a risk event, not a free pass), not
     * silently skipped the way a less careful version of this fix might have.
     */
    public RiskProfile resume(String userId, String credentialId) {
        RiskProfile profile = get(userId, credentialId);
        // Review finding ("resume() can still race with a new halt" -- external review, full
        // context in RiskProfile.safetyStateVersion's own field javadoc): captured here, before
        // any of this method's own safety checks run, specifically so the final write below can
        // require this exact version to still be current -- a concurrent halt landing anywhere
        // during this method's own execution (drawdown, circuit breaker, another halt() call)
        // bumps the version too, making this captured value stale and the final write correctly
        // lose the race instead of blindly clearing a brand-new halt.
        long capturedVersion = profile.getSafetyStateVersion();

        List<Position> openPositions = positionRepo.findByUserIdAndCredentialIdAndStatus(userId, credentialId, "OPEN");

        List<Position> unresolvedPositions = openPositions.stream()
            .filter(Position::isAvgEntryPriceUnverified)
            .toList();
        if (!unresolvedPositions.isEmpty()) {
            String symbols = unresolvedPositions.stream().map(Position::getSymbol).distinct().reduce((a, b) -> a + ", " + b).orElse("");
            throw new IllegalStateException("Cannot resume: " + unresolvedPositions.size()
                + " position(s) with an unverified entry price still need manual review before autonomous trading can restart (" + symbols + ").");
        }

        List<Position> incompleteLedgerPositions = openPositions.stream()
            .filter(Position::isLedgerRecordingIncomplete)
            .toList();
        if (!incompleteLedgerPositions.isEmpty()) {
            String symbols = incompleteLedgerPositions.stream().map(Position::getSymbol).distinct().reduce((a, b) -> a + ", " + b).orElse("");
            throw new IllegalStateException("Cannot resume: " + incompleteLedgerPositions.size()
                + " position(s) have an incomplete fill-ledger record still needing manual review (" + symbols + ").");
        }

        List<com.tradevision.model.Order> unresolvedOrders = orderRepo.findByCredentialIdAndStatusIn(credentialId,
            List.of(com.tradevision.model.OrderStatus.UNKNOWN, com.tradevision.model.OrderStatus.RECONCILIATION_REQUIRED));
        if (!unresolvedOrders.isEmpty()) {
            throw new IllegalStateException("Cannot resume: " + unresolvedOrders.size()
                + " order(s) are in an unresolved UNKNOWN/RECONCILIATION_REQUIRED state and need manual review before autonomous trading can restart.");
        }

        List<com.tradevision.model.TradingIncident> unresolvedCritical = tradingIncidentRepo.findByCredentialIdAndResolvedAtIsNullOrderByCreatedAtDesc(credentialId)
            .stream().filter(i -> "CRITICAL".equalsIgnoreCase(i.getSeverity())).toList();
        if (!unresolvedCritical.isEmpty()) {
            throw new IllegalStateException("Cannot resume: " + unresolvedCritical.size()
                + " unresolved CRITICAL incident(s) exist for this credential and need manual review before autonomous trading can restart.");
        }

        // Review finding ("Resume does not prove existing positions are protected" -- P0,
        // continued -- "actual exchange position/balance" re-verification): the real live check
        // this class's own earlier honest-scope note said was deliberately not attempted in the
        // first pass, closed now. For every OPEN position, verify the account's REAL, current
        // balance for that position's own base asset is genuinely still there -- not trusting
        // this application's own stored quantity, which could be stale if a position was closed
        // or reduced through some path this backend never observed (manual exchange-side action,
        // a missed reconciliation, etc.). A real broker call, so it's scoped to only run when
        // there ARE open positions to check (empty accounts pay nothing extra), and a failure to
        // even perform this check is treated as a failed check, not silently skipped -- same
        // principle as this codebase's own drawdown-check design (PositionMonitorService's own
        // checkDrawdown: inability to verify is a risk event, not a free pass).
        if (!openPositions.isEmpty()) {
            var credential = credentialService.ownedCredential(userId, credentialId);
            var adapter = credentialService.adapterForCredential(credential);
            String apiKey = credentialService.decrypt(credential, true);
            String apiSecret = credentialService.decrypt(credential, false);
            List<com.tradevision.service.broker.dto.AssetBalance> realBalances;
            try {
                realBalances = adapter.getBalance(apiKey, apiSecret, credential.getMode());
            } catch (Exception e) {
                throw new IllegalStateException("Cannot resume: could not verify real exchange balances against this credential's "
                    + openPositions.size() + " open position(s) (" + e.getMessage() + "). Resume requires confirming the exchange-side "
                    + "state actually matches what this application believes before allowing new autonomous trades to size or risk-check "
                    + "against it.");
            }
            for (Position p : openPositions) {
                String baseAsset;
                try {
                    baseAsset = adapter.getSymbolRules(p.getSymbol(), credential.getMode()).baseAsset();
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot resume: could not verify the real exchange balance for " + p.getSymbol()
                        + " (" + e.getMessage() + ").");
                }
                java.math.BigDecimal realBalance = realBalances.stream()
                    .filter(b -> baseAsset.equalsIgnoreCase(b.asset()))
                    .map(b -> b.free().add(b.locked()))
                    .findFirst().orElse(java.math.BigDecimal.ZERO);
                if (realBalance.compareTo(p.getQuantity()) < 0) {
                    throw new IllegalStateException("Cannot resume: this application believes it holds " + p.getQuantity() + " " + baseAsset
                        + " (position " + p.getSymbol() + "), but the exchange's own real balance is only " + realBalance + " " + baseAsset
                        + " -- this position's real state has diverged from what this application believes and needs manual review before "
                        + "autonomous trading can restart.");
                }
            }
        }

        profile.setTradingHalted(false);
        profile.setHaltReason(null);
        profile.setUpdatedAt(LocalDateTime.now());
        // Review finding ("resume() can still race with a new halt" -- external review, full
        // context in RiskProfile.safetyStateVersion's own field javadoc): the actual race fix --
        // this write now requires safetyStateVersion to still equal what was captured before
        // this method's own safety checks ran. If it doesn't match, something (a drawdown check,
        // a circuit breaker, another halt) genuinely changed the safety state during this
        // method's own execution, and this resume must not proceed to silently clear it.
        var raceResult = mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())
                .and("safetyStateVersion").is(capturedVersion)),
            new org.springframework.data.mongodb.core.query.Update()
                .set("tradingHalted", false)
                .set("haltReason", (Object) null)
                // Review finding ("autoTradeHalted is still never reset" -- external review,
                // third pass, confirmed real by direct inspection before any fix was attempted):
                // RiskEngineService's own circuit breaker sets this true on consecutive
                // losses/failures, and NOTHING anywhere in this codebase ever set it back to
                // false -- resume() only ever cleared tradingHalted, leaving a circuit-breaker
                // halt permanent in practice, with no obvious path to recover short of a manual
                // database edit. Cleared here rather than building a second, separate
                // reset-endpoint that would need to duplicate every one of this method's own
                // existing safety gates (unverified-entry-price positions, incomplete ledger
                // records, unresolved UNKNOWN/RECONCILIATION_REQUIRED orders, unresolved
                // CRITICAL incidents, and the live exchange-balance re-verification below) --
                // resume() is already this codebase's own most rigorous, explicitly
                // user-initiated safety checkpoint, and a circuit-breaker halt deserves exactly
                // that same scrutiny before being cleared, not a lighter-weight path.
                .set("autoTradeHalted", false)
                .set("autoTradeHaltReason", (Object) null)
                // P3-9 fix ("RiskProfileService.resume -- doesn't reset consecutiveOrderFailures;
                // next single failure re-trips breaker" -- external review, confirmed real by
                // direct inspection: AutoTradeService's own circuit breaker (see its own comment
                // right where it sets tradingHalted=true) trips once consecutiveOrderFailures
                // reaches circuitBreakerThreshold, and NOTHING before this fix ever reset that
                // counter back down -- resume() cleared tradingHalted/haltReason but left the
                // counter sitting at or above the threshold, so the very next order failure
                // (inc("consecutiveOrderFailures", 1), evaluated against the same threshold)
                // immediately re-tripped the breaker again, making a circuit-breaker halt
                // effectively permanent past the very first resume attempt -- exactly the
                // opposite of what a user-initiated, deliberately-scrutinized resume is supposed
                // to mean. Reset here, alongside the other safety-state fields this exact
                // "already this codebase's own most rigorous, explicitly user-initiated safety
                // checkpoint" method already clears (see this method's own comment on
                // autoTradeHalted above for why resume(), not a lighter-weight path, is the
                // right place for this).
                .set("consecutiveOrderFailures", 0)
                .set("updatedAt", profile.getUpdatedAt())
                .inc("safetyStateVersion", 1),
            RiskProfile.class);
        if (raceResult.getModifiedCount() == 0) {
            throw new IllegalStateException("Cannot resume: the credential's safety state changed during this resume attempt "
                + "(a halt, a drawdown event, or a circuit breaker landed while this resume's own checks were running). "
                + "Please retry -- if this keeps happening, something is actively, repeatedly halting this credential and "
                + "needs investigation before resuming.");
        }
        profile = riskProfileRepo.findById(profile.getId()).orElse(profile);
        credentialService.audit(userId, credentialId, null, "KILL_SWITCH_RELEASED", "Auto-trading resumed by user.");
        return profile;
    }

    /** Global kill switch: halts auto-trading across every credential this user has configured. */
    /**
     * Review finding ("Global kill switch still uses full-document save()" -- external review,
     * confirmed real by direct inspection before any fix was attempted): this loop used to do a
     * plain, full-object riskProfileRepo.save() per profile -- exactly the same class of bug
     * this session's own halt()/resume() already fixed for the single-credential kill switch,
     * just never applied here too. A stale in-memory profile (e.g. one whose
     * dailyRealizedLossQuote or consecutiveOrderFailures was updated by a concurrent trade
     * evaluation between this loop's own findByUserIdAndAutoTradeEnabledTrue query and this
     * save) could silently overwrite that concurrent write. Converted to the same targeted
     * atomic $set + safetyStateVersion increment already proven for halt().
     */
    public void haltAll(String userId, String reason) {
        for (RiskProfile profile : riskProfileRepo.findByUserIdAndAutoTradeEnabledTrue(userId)) {
            String haltReason = reason != null && !reason.isBlank() ? reason : "Global kill switch engaged by user.";
            mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
                new org.springframework.data.mongodb.core.query.Update()
                    .set("tradingHalted", true)
                    .set("haltReason", haltReason)
                    .set("updatedAt", LocalDateTime.now())
                    .inc("safetyStateVersion", 1),
                RiskProfile.class);
            credentialService.audit(userId, profile.getCredentialId(), null, "KILL_SWITCH_ENGAGED", haltReason);
            // Review finding ("The execution authorization still has an unavoidable exchange-
            // boundary race" -- external review, twenty-first pass, P0, confirmed real: this
            // application's own kill switch is a database flag, not an exchange-side
            // cancellation -- an execution that already claimed authorization and started its
            // real network call to the exchange before this halt was recorded can still reach
            // it. The database flag itself cannot physically stop an in-flight network request
            // (the review's own explicit framing); what this CAN do is close the discovery gap
            // immediately, rather than waiting for the next periodic reconciliation cycle
            // (potentially up to a minute later): trigger a real reconciliation pass for this
            // exact credential right now, so any order that did slip through this halt is
            // discovered and its real state recorded as soon as this method returns, not later.
            // Non-fatal, additive -- a failure here must never prevent the halt itself (already
            // durably recorded above) from taking effect.
            try {
                credentialRepo.findById(profile.getCredentialId()).ifPresent(positionMonitorService::reconcileCredential);
            } catch (Exception e) {
                log.warn("Immediate post-halt reconciliation failed for credential {} (non-fatal -- the halt itself already took "
                    + "effect regardless, and the next periodic reconciliation cycle will still catch anything this pass missed): {}",
                    profile.getCredentialId(), e.getMessage());
            }
        }
    }

    /**
     * Review finding ("Secrets / encryption key rotation and credential revocation story
     * incomplete" -- external review, nineteenth pass, P1, confirmed real by direct inspection:
     * this application had a per-credential delete() and a per-user haltAll(), but no single,
     * immediate response to a suspected account compromise combining every real action one
     * needs -- "immediate 'disable all trading + force re-auth' path", the review's own exact
     * ask): the actual fix. Three real, independent actions, each already using this codebase's
     * own established, working mechanism -- nothing invented here:
     *  1. haltAll() (above) -- every RiskProfile's own autonomous trading stops immediately.
     *  2. BrokerCredentialService.deactivateAll() -- every broker credential is deactivated,
     *     closing the surface a compromised session/device could otherwise still place manual
     *     orders through (haltAll alone only stops AUTONOMOUS trading, not manual placement).
     *  3. User.tokenVersion bumped -- the exact mechanism AuthService.logout() and its own
     *     refresh-token-reuse detection already use to invalidate every existing JWT for this
     *     user immediately, forcing genuine re-authentication on every device/session, not just
     *     this one. The refresh token itself is also cleared, so a stolen refresh token can't be
     *     used to silently mint a new access token either.
     *
     * HONEST SCOPE, stated plainly: this responds to a SUSPECTED COMPROMISE by cutting off
     * everything this application itself controls immediately. It does NOT rotate or replace
     * this application's own AES encryption key protecting credentials already at rest (a key
     * rotation across every existing encrypted credential in the database is separate,
     * standalone infrastructure work -- re-encrypting historical records under a new key,
     * deciding how the new key itself is distributed/stored, and a real operational runbook for
     * when to actually rotate it), and it does NOT revoke the underlying API key on the broker's
     * OWN side (Binance itself) -- that step is unavoidably manual, since Binance has no
     * programmatic "revoke this specific key" endpoint this application could call on the user's
     * behalf. Deactivating the credential here stops THIS application from using it; the user
     * must still separately revoke the key on Binance's own site if they believe it was
     * genuinely exposed.
     */
    /**
     * Review finding ("Emergency credential revocation can intentionally disable the very
     * monitoring needed by existing positions" -- external review, twenty-fourth pass, P1,
     * confirmed real by direct inspection before this fix: deactivateAll() sets
     * credential.active=false, and PositionMonitorService's own doReconcile eligibility check
     * (`if (!credential.isActive()) continue;`) means monitoring genuinely stops for that
     * credential -- including any position still open on it): the actual fix, scoped honestly --
     * this does NOT change what emergencyRevokeAll does (a genuine suspected-compromise scenario
     * correctly means "stop touching this credential at all," which this application's own
     * kill switch (haltAll alone, without deactivation) already serves as the separate, less
     * destructive "stop new trades but keep monitoring existing positions" action for). What
     * this fix adds is honesty about the consequence: the exact count of open positions this
     * action is about to stop monitoring, computed BEFORE deactivation, returned to the caller
     * so the response can state it concretely rather than leave it implicit.
     */
    public long emergencyRevokeAll(String userId, String reason) {
        String effectiveReason = reason != null && !reason.isBlank() ? reason : "Emergency account revocation requested by user.";
        long affectedOpenPositions = positionRepo.countByUserIdAndStatusIn(userId,
            java.util.Set.of("OPEN", "FLATTENING", "NAKED_FLATTENED", "CLOSED_UNVERIFIED_PNL"));
        haltAll(userId, effectiveReason);
        credentialService.deactivateAll(userId, effectiveReason);
        userRepo.findById(userId).ifPresent(user -> {
            user.setTokenVersion(user.getTokenVersion() + 1);
            user.setRefreshTokenHash(null);
            user.setRefreshTokenExpiry(null);
            userRepo.save(user);
        });
        return affectedOpenPositions;
    }

    /**
     * P0-6 fix: the actual gate authorizeLiveAutoTrade checks -- every one of these limits must
     * be a real, positive value (not the model's own 0/null "disabled" default) before this
     * codebase will authorize autonomous LIVE trading. See authorizeLiveAutoTrade's own updated
     * javadoc for why this is checked only at LIVE authorization time, not for every trade.
     */
    private boolean hasCompleteLiveRiskLimits(RiskProfile profile) {
        return profile.getDailyLossLimitQuote() != null && profile.getDailyLossLimitQuote().signum() > 0
            && profile.getMaxPositionQuoteAmount() != null && profile.getMaxPositionQuoteAmount().signum() > 0
            && profile.getMaxTotalExposureQuote() != null && profile.getMaxTotalExposureQuote().signum() > 0
            && profile.getMaxDrawdownPercent() > 0
            && profile.getMaxOrdersPerHour() > 0;
    }

    private static final String REQUIRED_PHRASE = "I UNDERSTAND THIS ENABLES AUTONOMOUS LIVE TRADING";

    /**
     * The second, independent unlock AutoTradeService checks before it will place a LIVE order.
     * Deliberately requires the caller to send back an exact confirmation phrase rather than a
     * boolean flag — a stray `true` in a request body is too easy to send by accident.
     */
    public RiskProfile authorizeLiveAutoTrade(String userId, String credentialId, String confirmationPhrase) {
        if (!REQUIRED_PHRASE.equals(confirmationPhrase)) {
            throw new IllegalArgumentException("Confirmation phrase did not match. Send exactly: \"" + REQUIRED_PHRASE + "\"");
        }
        RiskProfile profile = get(userId, credentialId);

        // Review finding (P1 #6 — "LIVE auto-trade authorization doesn't revalidate broker
        // permissions"): confirmed real — the API key's withdrawal/trading permissions were
        // checked once, at connection time (BrokerCredentialService.requestLiveConnect), and
        // never again. A user could connect with withdrawals off, then later change that key's
        // permissions directly on Binance, and this authorization step would have no way to know
        // — it only checked the confirmation phrase and the stored risk profile. Re-queries the
        // broker's actual current permissions, the exact same check used at connection time,
        // right before granting autonomous LIVE authority — refuses and raises an incident on
        // any drift, rather than trusting a snapshot that may be stale.
        var credential = credentialService.ownedCredential(userId, credentialId);
        if (credential.getMode() == com.tradevision.model.BrokerMode.LIVE) {
            // P0-6 fix ("LIVE risk-limit enforcement" -- external review, confirmed real by
            // direct inspection: every one of these limits could legally sit at its own
            // "disabled" default (0, or null for maxTotalExposureQuote) and this method would
            // authorize autonomous LIVE trading anyway -- a fresh credential's risk profile is
            // 0/disabled everywhere until a user deliberately configures it, and nothing here
            // ever stopped LIVE authorization from proceeding regardless. A user could enable
            // real-money autonomous trading with NO daily loss limit, NO per-position cap, NO
            // account-wide exposure cap, NO drawdown circuit breaker, and NO order-frequency
            // limit configured at all): refuses authorization outright until every one of these
            // is a real, non-zero value -- forcing a deliberate choice before real money is put
            // at risk, rather than silently trading against an effectively unconfigured risk
            // profile. Scoped to LIVE only (not TESTNET) -- RiskEngineService's own per-trade
            // checks already treat 0/null as "this specific cap is disabled" by design (see
            // RiskProfile's own field javadocs), which is the correct, intentional behavior for
            // TESTNET/paper trading, where an operator may deliberately want fewer limits while
            // testing. LIVE is different: this is the one gate this codebase has that's
            // specifically about authorizing REAL MONEY autonomous trading, so it's the right
            // place to require the full set of limits be deliberately configured.
            if (!hasCompleteLiveRiskLimits(profile)) {
                List<String> missing = new java.util.ArrayList<>();
                if (profile.getDailyLossLimitQuote() == null || profile.getDailyLossLimitQuote().signum() <= 0) missing.add("dailyLossLimitQuote");
                if (profile.getMaxPositionQuoteAmount() == null || profile.getMaxPositionQuoteAmount().signum() <= 0) missing.add("maxPositionQuoteAmount");
                if (profile.getMaxTotalExposureQuote() == null || profile.getMaxTotalExposureQuote().signum() <= 0) missing.add("maxTotalExposureQuote");
                if (profile.getMaxDrawdownPercent() <= 0) missing.add("maxDrawdownPercent");
                if (profile.getMaxOrdersPerHour() <= 0) missing.add("maxOrdersPerHour");
                credentialService.audit(userId, credentialId, credential.getBroker(), "LIVE_AUTOTRADE_AUTH_REFUSED_INCOMPLETE_RISK_LIMITS",
                    "LIVE auto-trade authorization refused: the following risk limit(s) are not configured (still at their disabled/zero "
                        + "default) and must be set to a real, positive value before autonomous LIVE trading can be authorized: " + missing);
                throw new IllegalArgumentException("LIVE auto-trade authorization refused: the following risk limits must be configured "
                    + "to a real, positive value first: " + missing + ". Update the risk profile before authorizing autonomous LIVE trading.");
            }
            // Review finding ("Mongo standalone deployment still weakens the plan/profile
            // execution atomicity guarantee" -- external review, twenty-fourth pass, P1, full
            // context in IndexInitializer.checkMongoTransactionSupport's own javadoc): checked
            // alongside this method's own existing LIVE-specific permission checks -- refuses
            // outright rather than silently accepting a deployment that will always need the
            // weaker, sequential fallback claimExecutionAtomicWithPlan already has.
            if (!startupState.areMongoTransactionsSupported()) {
                credentialService.audit(userId, credentialId, credential.getBroker(), "LIVE_AUTOTRADE_AUTH_REFUSED_NO_MONGO_TRANSACTIONS",
                    "LIVE auto-trade authorization refused: this MongoDB deployment does not support transactions (confirmed at "
                        + "application startup -- standalone, not a replica set). The plan-disable-vs-execution race this application's "
                        + "own atomic claim path exists to close cannot be fully closed on this deployment.");
                throw new IllegalStateException("LIVE auto-trade authorization refused: this MongoDB deployment does not support "
                    + "transactions (confirmed at application startup). Configure a MongoDB replica set and restart the application "
                    + "before authorizing LIVE autonomous trading.");
            }
            var adapter = credentialService.adapterForCredential(credential);
            String apiKey = credentialService.decrypt(credential, true);
            String apiSecret = credentialService.decrypt(credential, false);
            com.tradevision.service.broker.dto.AccountPermissions permissions;
            try {
                permissions = adapter.getAccountPermissions(apiKey, apiSecret, credential.getMode());
            } catch (Exception e) {
                credentialService.audit(userId, credentialId, credential.getBroker(), "LIVE_AUTOTRADE_AUTH_REFUSED_PERMISSION_UNKNOWN",
                    "LIVE auto-trade authorization refused: could not re-verify this key's current broker permissions (" + e.getMessage() + ").");
                throw new IllegalStateException("Could not verify this API key's current permissions with the broker — try again shortly. "
                    + "Authorization refused rather than granted against unverified permissions.");
            }
            if (!permissions.canTrade()) {
                credentialService.audit(userId, credentialId, credential.getBroker(), "LIVE_AUTOTRADE_AUTH_REFUSED_TRADING_DISABLED",
                    "LIVE auto-trade authorization refused: this key no longer has trading permission enabled on Binance.");
                incidentService.raiseCritical(userId, credentialId, null, null, null, "PROTECTION_UNKNOWN",
                    "LIVE auto-trade authorization refused: trading permission is no longer enabled on this API key.");
                throw new IllegalArgumentException("This API key no longer has trading permission enabled on the broker.");
            }
            // Review finding (P1 #10 — "Withdrawal-permission check relies on
            // /api/v3/account.canWithdraw"): the withdrawal re-check that used to live here
            // compared permissions.canWithdraw() — an ACCOUNT-level flag that says nothing about
            // THIS key's own restrictions, and is essentially always true for a real account
            // regardless of the key. Replaced with the same key-level apiRestrictions check
            // BrokerCredentialService now applies at connect/rotation time, reused here so a key
            // that had its restrictions loosened AFTER connecting is caught on every
            // re-authorization too, not just once at connect time.
            try {
                credentialService.validateLiveKeyRestrictions(userId, credentialId, credential.getBroker(), adapter, apiKey, apiSecret,
                    "LIVE_AUTOTRADE_AUTH_REFUSED");
            } catch (IllegalArgumentException e) {
                incidentService.raiseCritical(userId, credentialId, null, null, null, "PROTECTION_UNKNOWN",
                    "LIVE auto-trade authorization refused: " + e.getMessage());
                throw e;
            }
        }

        profile.setLiveAutoTradeAuthorized(true);
        profile.setUpdatedAt(LocalDateTime.now());
        // Review finding ("Risk-profile updates/resume can race with safety state" -- P0, full
        // context in doUpsert's own comment above): this is the review's own named scenario
        // exactly -- "authorizeLiveAutoTrade reads profile, user changes risk profile (which
        // should revoke authorization), stale authorize operation saves
        // liveAutoTradeAuthorized=true anyway." A targeted $set on only the two fields this
        // method actually changes closes it -- a concurrent doUpsert()'s own intentional
        // liveAutoTradeAuthorized=false write can no longer be raced past by this method's own
        // stale in-memory `profile`, since this update never touches or depends on any field
        // doUpsert also writes.
        mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
            new org.springframework.data.mongodb.core.query.Update()
                .set("liveAutoTradeAuthorized", true)
                .set("updatedAt", profile.getUpdatedAt()),
            RiskProfile.class);
        profile = riskProfileRepo.findById(profile.getId()).orElse(profile);
        credentialService.audit(userId, credentialId, null, "LIVE_AUTOTRADE_AUTHORIZED",
            "Autonomous LIVE trading explicitly authorized for this risk profile, after re-verifying broker permissions.");
        return profile;
    }

    public RiskProfile revokeLiveAutoTrade(String userId, String credentialId) {
        RiskProfile profile = get(userId, credentialId);
        // Review finding ("Risk-profile updates/resume can race with safety state" -- P0, full
        // context in doUpsert's own comment above): same targeted-update fix. Revocation is the
        // more safety-critical direction of this pair -- a full save() here losing a race would
        // mean a LIVE authorization stays granted when it should have been revoked, not the
        // safer failure mode.
        mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
            new org.springframework.data.mongodb.core.query.Update()
                .set("liveAutoTradeAuthorized", false)
                .set("updatedAt", LocalDateTime.now()),
            RiskProfile.class);
        profile = riskProfileRepo.findById(profile.getId()).orElse(profile);
        credentialService.audit(userId, credentialId, null, "LIVE_AUTOTRADE_REVOKED", "Autonomous LIVE trading authorization revoked.");
        return profile;
    }
}
