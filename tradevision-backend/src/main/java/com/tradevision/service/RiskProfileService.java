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
    // Backs the atomic findAndModify claims used to gate execution safely under concurrency.
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    // Used by resume() to check for unresolved UNKNOWN/RECONCILIATION_REQUIRED orders before
    // autonomous trading is allowed to restart.
    private final com.tradevision.repository.OrderRepository orderRepo;
    /**
     * Lets claimExecutionAtomicWithPlan span a single MongoDB transaction across both this
     * service's own execution claim and the strategy plan's claim, with a graceful
     * non-transactional fallback when the deployment doesn't support transactions.
     * StrategyPlanService depends only on RiskProfileRepository, never on this service, so this
     * stays free of a circular bean dependency.
     */
    private final StrategyPlanService strategyPlanService;
    /**
     * Used by emergencyRevokeAll to force re-authentication everywhere: bumping
     * User.tokenVersion invalidates every existing JWT for this user immediately, the same
     * mechanism AuthService.logout() and its refresh-token-reuse detection already use.
     * UserRepository is a plain Spring Data repository with no service-layer dependencies, so
     * this stays free of a circular bean dependency.
     */
    private final com.tradevision.repository.UserRepository userRepo;
    /**
     * Backs the fresh step-up OTP check authorizeLiveAutoTrade requires, reusing AuthService's
     * existing OTP infrastructure (rate limiting, hashing, expiry, single-use consumption)
     * rather than a second copy of it. AuthService depends only on UserRepository,
     * OtpRepository, JwtUtil, OtpUtil, EmailService, OtpRateLimitService, MongoTemplate and
     * WebhookAlertService -- none of which depend on RiskProfileService -- so this stays free of
     * a circular bean dependency.
     */
    private final AuthService authService;
    /**
     * Lets haltAll trigger an immediate reconciliation pass for each halted credential right
     * after the halt, rather than waiting for the next periodic cycle to discover anything that
     * slipped through. PositionMonitorService never injects RiskProfileService, so this stays
     * free of a circular bean dependency.
     */
    private final PositionMonitorService positionMonitorService;
    private final com.tradevision.repository.BrokerCredentialRepository credentialRepo;
    private final com.tradevision.repository.TradingIncidentRepository tradingIncidentRepo;
    /**
     * Lets authorizeLiveAutoTrade refuse authorization outright when the MongoDB deployment
     * doesn't support transactions, rather than silently relying on the weaker sequential
     * fallback. StartupState is a pure state holder with no dependencies, so this stays free of
     * a circular bean dependency.
     */
    private final com.tradevision.config.StartupState startupState;
    // The "has this credential ever proven itself with a real LIVE order" gate, checked
    // alongside every other LIVE-only check authorizeLiveAutoTrade already applies.
    private final LiveCanaryService liveCanaryService;

    /** The result of a successful execution claim: a traceable claim id a caller can record on
     *  whatever it submits to the exchange, plus the safety-state generation it was granted under. */
    public record ExecutionClaim(String claimId, long generation) {}

    /**
     * Grants (or refuses) authorization to submit one order to the exchange, as a single atomic
     * MongoDB conditional update evaluated immediately before the exchange call -- not earlier in
     * the evaluation sequence, where a RiskProfile might otherwise be loaded once into memory and
     * then acted on after it has gone stale. The query requires credentialId=X AND
     * autoTradeEnabled=true AND tradingHalted=false AND autoTradeHalted=false, and for LIVE
     * credentials also liveAutoTradeAuthorized=true; the accompanying $set
     * (lastExecutionClaimAt/lastExecutionClaimId) is a real field write, so Mongo evaluates the
     * filter and applies the update as one indivisible operation on a single document. If it
     * modifies zero documents, at least one condition was false at that exact moment and the
     * caller must not call the exchange -- a kill switch, a revoked LIVE authorization, or a
     * circuit-breaker halt landing at any point before this call is guaranteed to be seen.
     *
     * Deliberately does not re-check position limits, exposure caps, or similar risk parameters:
     * those are evaluated earlier against reservations that are already atomic in their own right
     * (PositionSlotReservationService / ExposureReservationService). This claim's own job is
     * narrower -- making sure the kill-switch/safety-state flags are read fresh, not from a
     * stale in-memory profile.
     *
     * Uses findAndModify rather than updateFirst so the returned document carries the fresh claim
     * id and safetyStateVersion from the exact same atomic operation that granted it, giving a
     * genuine, traceable answer to "which claim authorized this order" for later auditing.
     */
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

    /** How long a claim from claimExecutionAuthorization stays valid for markExecutionStarted to
     *  accept. Without an expiry, a claim would remain usable indefinitely as long as nothing
     *  superseded it, so an evaluator paused for an arbitrarily long time (a long GC pause, a
     *  debugger breakpoint, a thread starved under load) could resume and still submit. 15
     *  seconds is generous enough that the normal claim -> risk checks -> order construction ->
     *  submit sequence (well under a second) never spuriously expires, while still closing off a
     *  claim resumed after minutes of being paused. */
    private static final java.time.Duration CLAIM_MAX_AGE = java.time.Duration.ofSeconds(15);

    /**
     * Re-validates a claim and registers its execution as in flight in one atomic operation --
     * the same condition set as the original claim, applied as the query of a single
     * findAndModify whose update increments executionInFlightCount, so there is no gap between
     * "confirm this claim is still valid" and "register this execution as in flight" for a
     * concurrent kill switch to land in. Returns false (incrementing nothing) if the conditions
     * no longer hold or the claim has aged past CLAIM_MAX_AGE; the caller must treat false as "do
     * not call the exchange". Deliberately never upserts: a credential with no matching
     * RiskProfile must never have one silently created by this call.
     */
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
     * Claims execution against both a strategy plan and its risk profile as one genuine MongoDB
     * multi-document transaction, committing only if both claims succeed and aborting (leaving
     * neither claim in place) if either fails. Without a shared transaction, the plan claim and
     * the profile claim are two separate, sequential operations on different collections, and a
     * plan disable or version bump landing in the gap between them could otherwise still let an
     * already-claimed signal reach the exchange.
     *
     * MongoDB multi-document transactions require the deployment to be a replica set (or sharded
     * cluster); a standalone instance rejects transactions outright with a specific, recognizable
     * error. Rather than fail this method entirely on a standalone deployment, or silently skip
     * the plan check, this catches that specific error and falls back to the sequential
     * two-claim approach, logging explicitly whenever that fallback is taken so a deployment's
     * own logs make clear whether the full transactional guarantee is actually in effect there.
     */
    public boolean claimExecutionAtomicWithPlan(String credentialId, String claimId, boolean isLive,
                                                  String planId, Long expectedPlanVersion, String profileUserId) {
        if (planId == null || expectedPlanVersion == null) {
            // No plan to coordinate with -- the single-document claim below is already fully
            // atomic and sufficient on its own.
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
                // Best-effort cleanup only -- the transaction attempt already failed, so a
                // failure aborting it changes nothing about the outcome.
            }
            // Code 20 ("IllegalOperation") is MongoDB's documented signal that this deployment
            // doesn't support transactions at all (a standalone instance, not a replica set).
            // Anything else is a genuine, different MongoDB error that should propagate rather
            // than be silently swallowed into a fallback that wouldn't actually fix it.
            if (e.getCode() == 20 || (e.getMessage() != null && e.getMessage().contains("Transaction numbers"))) {
                log.warn("MongoDB transactions are not supported by this deployment (standalone, not a replica set/mongos) -- falling "
                    + "back to the sequential, non-transactional two-claim approach for credential {}. The narrow race this "
                    + "transactional path exists to close (a plan disable/version-bump landing in the gap between the two separate "
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
     * Claims plan execution and then profile execution as two separate, sequential atomic
     * claims, each fully atomic within its own single document but not atomic with each other.
     * Used as the fallback path on a MongoDB deployment that doesn't support multi-document
     * transactions.
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
     * Releases the in-flight execution count incremented by markExecutionStarted. Called
     * unconditionally in a finally block by every caller, so a submission that throws still
     * releases its own count. The decrement is guarded against going negative via the query
     * condition itself (executionInFlightCount > 0) rather than a second update operator on the
     * same field. Matched by credentialId alone, deliberately not re-checking claimId/halted/
     * etc: a release must always succeed regardless of what happened to the claim or halt state
     * in between, or a legitimate finally-block release could be skipped by a condition that no
     * longer matches, permanently leaking a count.
     */
    public void markExecutionFinished(String credentialId) {
        mongoTemplate.updateFirst(
            org.springframework.data.mongodb.core.query.Query.query(org.springframework.data.mongodb.core.query.Criteria.where("credentialId").is(credentialId)
                .and("executionInFlightCount").gt(0)),
            new org.springframework.data.mongodb.core.query.Update().inc("executionInFlightCount", -1),
            RiskProfile.class);
    }

    /**
     * OTP purpose for step-up verification before editing a LIVE credential's risk limits.
     * Kept distinct from STEPUP_OTP_PURPOSE so a stolen live-autotrade step-up code can never be
     * replayed to edit risk limits instead, or vice versa.
     */
    public static final String RISK_PROFILE_STEPUP_PURPOSE = "RISK_PROFILE_STEPUP";

    /** Request a fresh step-up verification code before calling upsert() on a LIVE credential's risk profile. */
    public void requestRiskProfileStepUpOtp(String userId) {
        var resp = authService.sendStepUpOtp(userId, RISK_PROFILE_STEPUP_PURPOSE);
        if (!resp.isSuccess()) {
            throw new IllegalArgumentException(String.valueOf(resp.getMessage()));
        }
    }

    public RiskProfile upsert(String userId, RiskProfileRequest req) {
        // Ensures the credential exists, belongs to this user, and is active — throws otherwise.
        var credential = credentialService.ownedCredential(userId, req.getCredentialId());
        // Editing the limits authorizeLiveAutoTrade/RiskEngineService rely on to bound a LIVE
        // account's real-money exposure is just as consequential as the one-time authorization
        // that first enabled LIVE trading, so it requires the same fresh step-up proof of
        // identity rather than relying on an ordinary, possibly long-lived session. Scoped to
        // LIVE only (not TESTNET/PAPER), matching hasCompleteLiveRiskLimits' own scoping -- there
        // is no real money at stake to step up for otherwise, and this profile is edited far more
        // often during ordinary TESTNET/PAPER strategy development, where a fresh OTP on every
        // save would be pure friction with no safety benefit. Checked first, before any other
        // validation below, so a stale/replayed request never reaches a state-changing write.
        if (credential.getMode() == com.tradevision.model.BrokerMode.LIVE) {
            authService.verifyStepUpOtp(userId, RISK_PROFILE_STEPUP_PURPOSE, req.getStepUpOtp());
        }
        try {
            return doUpsert(userId, req, credential.getBroker());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // A unique index on (userId, credentialId) means a genuine collision -- two
            // concurrent requests both finding no existing profile and both trying to insert one
            // -- surfaces as this catchable exception instead of silently succeeding twice. One
            // retry is enough: by the time we get here, the other request's profile is now
            // findable, so this becomes a normal update instead of a second insert.
            return doUpsert(userId, req, credential.getBroker());
        }
    }

    private static final String REQUIRED_QUOTE_ASSET = "USDT";

    private RiskProfile doUpsert(String userId, RiskProfileRequest req, com.tradevision.model.BrokerType broker) {
        // PortfolioRiskService's own exposure/equity aggregation sums market value across every
        // open position's symbol without converting to a common currency (100 USDT + 100 USDC +
        // 0.1 BTC would otherwise be summed as if they were the same number). Rather than
        // normalize every exposure to a configured base currency using fresh conversion prices,
        // this enforces a single quote asset (USDT) server-side, rejected here at the one place
        // a user's enabled-symbol list is actually written -- simpler and safer than discovering
        // a currency-mixing aggregation bug later during a real drawdown check.
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
        // ExposureReservationService builds a live Mongo update/query field path as the literal
        // string "reservedGroupExposure." + groupName for every group this profile configures,
        // and correlationGroups' keys are free-text user input with no prior validation. A group
        // named e.g. "Majors.sub" would target the nested path reservedGroupExposure.Majors.sub
        // instead of a flat field, silently corrupting or conflicting with a genuinely different
        // group named "Majors", and MongoDB rejects a field name starting with "$" outright --
        // either way a bad name breaks or corrupts exposure tracking used for real-money risk
        // limits. Rejected here, at the one place a user's group names are actually written,
        // matching the enabledSymbols validation above.
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
        // These four are already read by RiskEngineService's own checks, so they must actually
        // be settable here -- otherwise a user could never configure them regardless of what
        // this request contains.
        profile.setMaxDrawdownPercent(req.getMaxDrawdownPercent());
        profile.setMaxOrdersPerHour(req.getMaxOrdersPerHour());
        profile.setMaxConsecutiveAutoTradeLosses(req.getMaxConsecutiveAutoTradeLosses());
        profile.setCircuitBreakerThreshold(req.getCircuitBreakerThreshold());
        profile.setCorrelationGroups(req.getCorrelationGroups() != null ? req.getCorrelationGroups() : new java.util.HashMap<>());
        profile.setCorrelationGroupCaps(req.getCorrelationGroupCaps() != null ? req.getCorrelationGroupCaps() : new java.util.HashMap<>());
        profile.setUpdatedAt(LocalDateTime.now());
        // Any change to risk parameters revokes live auto-trade authorization — re-confirm explicitly.
        profile.setLiveAutoTradeAuthorized(false);

        // This method only ever sets configuration fields (autoTradeEnabled, enabledSymbols,
        // minConfidence, etc.), never safety-state fields like tradingHalted/autoTradeHalted/
        // dailyRealizedLossQuote/consecutiveOrderFailures/peakEquityQuote. A full-document save
        // would silently overwrite whatever value those safety fields held at the moment
        // `profile` was loaded above, erasing a concurrent halt()/drawdown/circuit-breaker write
        // that landed in that window. For an existing profile (has a database id already), a
        // targeted $set touching only the configuration fields this method owns avoids that --
        // a concurrent safety-state write can no longer be clobbered, since this update never
        // names those fields at all. For a genuinely new profile (no id yet), a full save is
        // correct and safe as-is, since there's no existing document to race against.
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
                // These four must be part of the targeted $set too, or an existing profile's
                // update would keep silently dropping them even though they're set on the
                // in-memory `profile` object above.
                .set("maxDrawdownPercent", profile.getMaxDrawdownPercent())
                .set("maxOrdersPerHour", profile.getMaxOrdersPerHour())
                .set("maxConsecutiveAutoTradeLosses", profile.getMaxConsecutiveAutoTradeLosses())
                .set("circuitBreakerThreshold", profile.getCircuitBreakerThreshold())
                .set("correlationGroups", profile.getCorrelationGroups())
                .set("correlationGroupCaps", profile.getCorrelationGroupCaps())
                .set("updatedAt", profile.getUpdatedAt())
                .set("liveAutoTradeAuthorized", false); // intentional, fresh write -- not a stale-value overwrite, per the targeted-$set reasoning above
            mongoTemplate.updateFirst(
                new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
                update, RiskProfile.class);
            // Re-read after the targeted update so the returned object (and the audit line
            // below) reflects the real, current document -- not the in-memory object, which
            // doesn't know about any concurrent safety-state field this update deliberately
            // left untouched.
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
        // The kill switch is the single most safety-critical place for the targeted-$set
        // pattern used throughout this class: a full riskProfileRepo.save() here would silently
        // overwrite any concurrent write to every other field on this document (a config update
        // mid-flight via doUpsert, a drawdown check writing dailyRealizedLossQuote, another
        // circuit breaker) with whatever stale values this method's own `profile` happened to
        // hold at load time. A targeted $set on exactly the two fields this method changes means
        // tradingHalted can never come back false from a race with something that never intended
        // to touch it.
        // Every tradingHalted transition -- including this one -- bumps safetyStateVersion, so
        // resume()'s own race detection has a real, incrementing signal to compare against.
        mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())),
            new org.springframework.data.mongodb.core.query.Update()
                .set("tradingHalted", true)
                .set("haltReason", haltReason)
                .set("updatedAt", LocalDateTime.now())
                .inc("safetyStateVersion", 1),
            RiskProfile.class);
        profile = riskProfileRepo.findById(profile.getId()).orElse(profile);
        // tradingHalted is already true by this point, so markExecutionStarted() blocks every
        // new execution from here on -- but this check surfaces whether a submission was
        // already past that gate and genuinely in flight to the exchange the moment this kill
        // switch engaged, rather than leaving that fact unknown. Reads directly off the re-read
        // `profile` object above, since this count lives on the document itself.
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
     * Clears a kill switch, but only once every existing position and order for this credential
     * is confirmed to be in a known-safe state -- a position can sit OPEN with a stale/cancelled
     * OCO id, a genuinely unresolved OMS order, an incomplete fill-ledger record, or an active
     * critical incident against the credential, none of which a bare entry-price check alone
     * would catch, and clearing the halt regardless would let new autonomous trades size and
     * risk-check against state that isn't actually known-safe. Checks performed, each using data
     * this codebase already tracks and trusts elsewhere (Order.status, Position.
     * ledgerRecordingIncomplete, TradingIncident):
     *   - every OPEN position's entry price is verified (avgEntryPriceUnverified)
     *   - no OPEN position has an incomplete fill-ledger record (ledgerRecordingIncomplete)
     *   - no unresolved UNKNOWN/RECONCILIATION_REQUIRED order for this credential (the OMS's
     *     first-class "we genuinely don't know what happened" state)
     *   - no unresolved CRITICAL incident against this credential
     *   - for every OPEN position, a live broker call confirms the account's actual current
     *     balance for that position's base asset (via SymbolRules, not guessed from the symbol
     *     string) still covers what this application believes it holds
     *
     * A failure to even perform that last live check (network error, adapter exception) is
     * itself treated as a failed check and blocks resume, rather than silently skipped -- the
     * same principle as PositionMonitorService's own checkDrawdown design, where inability to
     * verify equity is treated as a risk event, not a free pass.
     */
    public RiskProfile resume(String userId, String credentialId) {
        RiskProfile profile = get(userId, credentialId);
        // Captured before any of this method's own safety checks run, so the final write below
        // can require this exact version to still be current -- a concurrent halt landing
        // anywhere during this method's execution (drawdown, circuit breaker, another halt()
        // call) bumps the version too, making this captured value stale and the final write
        // correctly lose the race instead of blindly clearing a brand-new halt.
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

        // For every OPEN position, verify the account's real, current balance for that
        // position's base asset is genuinely still there -- this application's own stored
        // quantity could be stale if a position was closed or reduced through a path this
        // backend never observed (manual exchange-side action, a missed reconciliation, etc.).
        // This is a real broker call, so it only runs when there are open positions to check
        // (empty accounts pay nothing extra), and a failure to even perform the check is treated
        // as a failed check rather than silently skipped -- the same principle as
        // PositionMonitorService's own checkDrawdown design, where inability to verify equity is
        // a risk event, not a free pass.
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
        // This write requires safetyStateVersion to still equal what was captured before this
        // method's safety checks ran. If it doesn't match, something (a drawdown check, a
        // circuit breaker, another halt) changed the safety state during this method's own
        // execution, and this resume must not proceed to silently clear it.
        var raceResult = mongoTemplate.updateFirst(
            new org.springframework.data.mongodb.core.query.Query(org.springframework.data.mongodb.core.query.Criteria.where("id").is(profile.getId())
                .and("safetyStateVersion").is(capturedVersion)),
            new org.springframework.data.mongodb.core.query.Update()
                .set("tradingHalted", false)
                .set("haltReason", (Object) null)
                // RiskEngineService's own circuit breaker sets autoTradeHalted true on
                // consecutive losses/failures; resume() is this codebase's most rigorous,
                // explicitly user-initiated safety checkpoint, so a circuit-breaker halt
                // deserves exactly the same scrutiny before being cleared as a manual halt does,
                // rather than needing a second, lighter-weight reset path that would have to
                // duplicate every one of this method's own safety gates.
                .set("autoTradeHalted", false)
                .set("autoTradeHaltReason", (Object) null)
                // AutoTradeService's own circuit breaker trips once consecutiveOrderFailures
                // reaches circuitBreakerThreshold. Resetting the counter here (alongside the
                // other safety-state fields this method clears) matters: without it, the very
                // next order failure after a resume would immediately re-trip the breaker,
                // since the counter would still sit at or above the threshold.
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

    /**
     * Global kill switch: halts auto-trading across every credential this user has configured.
     * Uses the same targeted atomic $set + safetyStateVersion increment as halt(), per profile,
     * rather than a full-document save -- a stale in-memory profile (e.g. one whose
     * dailyRealizedLossQuote or consecutiveOrderFailures was updated by a concurrent trade
     * evaluation between this loop's query and the save) must never be able to silently
     * overwrite that concurrent write.
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
            // The kill switch is a database flag, not an exchange-side cancellation -- an
            // execution that already claimed authorization and started its real network call to
            // the exchange before this halt was recorded can still reach it, and the flag cannot
            // physically stop an in-flight network request. What this can do is close the
            // discovery gap immediately rather than waiting for the next periodic
            // reconciliation cycle (potentially up to a minute later): trigger a real
            // reconciliation pass for this credential right now, so anything that slipped
            // through this halt is discovered and recorded as soon as this method returns.
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
     * Single, immediate response to a suspected account compromise, combining three independent
     * actions, each using this codebase's own established mechanism:
     *  1. haltAll() -- every RiskProfile's autonomous trading stops immediately.
     *  2. BrokerCredentialService.deactivateAll() -- every broker credential is deactivated,
     *     closing the surface a compromised session/device could otherwise still place manual
     *     orders through (haltAll alone only stops autonomous trading, not manual placement).
     *  3. User.tokenVersion bumped -- the same mechanism AuthService.logout() and its
     *     refresh-token-reuse detection use to invalidate every existing JWT for this user
     *     immediately, forcing re-authentication on every device/session. The refresh token is
     *     also cleared, so a stolen refresh token can't be used to silently mint a new access
     *     token either.
     *
     * Scope: this cuts off everything the application itself controls immediately. It does not
     * rotate the application's own AES encryption key protecting credentials already at rest
     * (that is separate infrastructure work -- re-encrypting historical records under a new
     * key, deciding how the new key is distributed/stored, and an operational runbook for when
     * to rotate it), and it does not revoke the underlying API key on the broker's own side --
     * that step is unavoidably manual, since the broker has no programmatic "revoke this
     * specific key" endpoint. Deactivating the credential here stops this application from using
     * it; the user must still separately revoke the key on the broker's own site if they believe
     * it was genuinely exposed.
     *
     * Deactivating every credential also stops PositionMonitorService from monitoring any
     * position still open on them (its reconciliation loop skips inactive credentials), which is
     * the correct trade-off for a genuine suspected-compromise scenario -- "stop touching this
     * credential at all" -- as distinct from haltAll() alone, which stops new trades while still
     * keeping existing positions monitored. The exact count of open positions this action is
     * about to stop monitoring is computed before deactivation and returned to the caller so the
     * consequence is stated concretely rather than left implicit.
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
     * The gate authorizeLiveAutoTrade checks: every one of these limits must be a real, positive
     * value (not the model's 0/null "disabled" default) before autonomous LIVE trading can be
     * authorized. Checked only at LIVE authorization time, not on every trade -- see
     * authorizeLiveAutoTrade for why that scoping is correct.
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
     * OTP purpose used for the step-up check in authorizeLiveAutoTrade, kept distinct from
     * LOGIN/REGISTER so a stolen login OTP (or vice versa) can never be replayed as a
     * live-autotrade step-up code -- AuthService's per-(identifier, purpose) OTP storage keeps
     * the two completely separate.
     */
    public static final String STEPUP_OTP_PURPOSE = "LIVE_AUTOTRADE_STEPUP";

    /**
     * Request a fresh step-up verification code before calling authorizeLiveAutoTrade. Sent to
     * the caller's own on-file email/mobile -- see AuthService.sendStepUpOtp's own javadoc.
     */
    public void requestLiveAutoTradeStepUpOtp(String userId) {
        var resp = authService.sendStepUpOtp(userId, STEPUP_OTP_PURPOSE);
        if (!resp.isSuccess()) {
            throw new IllegalArgumentException(String.valueOf(resp.getMessage()));
        }
    }

    /**
     * The second, independent unlock AutoTradeService checks before it will place a LIVE order.
     * Deliberately requires the caller to send back an exact confirmation phrase rather than a
     * boolean flag -- a stray `true` in a request body is too easy to send by accident.
     *
     * Flipping a risk profile into autonomous LIVE auto-trading is a high-consequence action, so
     * beyond the confirmation phrase (a static string, not tied to proving who's actually
     * present right now) it also requires a fresh, just-issued OTP (requested via
     * requestLiveAutoTradeStepUpOtp) verified against the caller's own on-file email/mobile --
     * an ordinary, possibly long-lived JWT session alone is not enough proof of identity for
     * this specific action. The OTP is checked first so a stale/replayed request never reaches
     * any of the state-changing checks below.
     */
    public RiskProfile authorizeLiveAutoTrade(String userId, String credentialId, String confirmationPhrase, String stepUpOtpCode) {
        if (!REQUIRED_PHRASE.equals(confirmationPhrase)) {
            throw new IllegalArgumentException("Confirmation phrase did not match. Send exactly: \"" + REQUIRED_PHRASE + "\"");
        }
        // Throws IllegalArgumentException (propagated as-is) on any failure -- no code
        // requested, expired, wrong, already used, or rate limited.
        authService.verifyStepUpOtp(userId, STEPUP_OTP_PURPOSE, stepUpOtpCode);
        RiskProfile profile = get(userId, credentialId);

        // The API key's withdrawal/trading permissions are otherwise only checked once, at
        // connection time (BrokerCredentialService.requestLiveConnect). A user could connect
        // with withdrawals off, then later change that key's permissions directly on Binance, so
        // this re-queries the broker's actual current permissions -- the same check used at
        // connection time -- right before granting autonomous LIVE authority, refusing and
        // raising an incident on any drift rather than trusting a snapshot that may be stale.
        var credential = credentialService.ownedCredential(userId, credentialId);
        if (credential.getMode() == com.tradevision.model.BrokerMode.LIVE) {
            // Every one of these limits can legally sit at its own "disabled" default (0, or
            // null for maxTotalExposureQuote) on a fresh profile until a user deliberately
            // configures it. Without this check, a user could enable real-money autonomous
            // trading with no daily loss limit, no per-position cap, no account-wide exposure
            // cap, no drawdown circuit breaker, and no order-frequency limit configured at all.
            // This refuses authorization outright until every one of these is a real, non-zero
            // value, forcing a deliberate choice before real money is put at risk. Scoped to
            // LIVE only (not TESTNET): RiskEngineService's own per-trade checks treat 0/null as
            // "this specific cap is disabled" by design, which is correct and intentional for
            // TESTNET/paper trading, where an operator may deliberately want fewer limits while
            // testing. LIVE is the one gate specifically about authorizing real-money autonomous
            // trading, so it's the right place to require the full set of limits.
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
            // Checked alongside the other LIVE-specific permission checks here: refuses outright
            // rather than silently accepting a deployment that will always need the weaker,
            // sequential fallback claimExecutionAtomicWithPlan falls back to.
            if (!startupState.areMongoTransactionsSupported()) {
                credentialService.audit(userId, credentialId, credential.getBroker(), "LIVE_AUTOTRADE_AUTH_REFUSED_NO_MONGO_TRANSACTIONS",
                    "LIVE auto-trade authorization refused: this MongoDB deployment does not support transactions (confirmed at "
                        + "application startup -- standalone, not a replica set). The plan-disable-vs-execution race this application's "
                        + "own atomic claim path exists to close cannot be fully closed on this deployment.");
                throw new IllegalStateException("LIVE auto-trade authorization refused: this MongoDB deployment does not support "
                    + "transactions (confirmed at application startup). Configure a MongoDB replica set and restart the application "
                    + "before authorizing LIVE autonomous trading.");
            }
            // Every other check here is about whether this credential is allowed to trade LIVE;
            // none of them actually sends a real order through the full pipeline (adapter auth,
            // placement, OMS transitions, Position creation, real OCO protection) to confirm it
            // genuinely works for this credential before autonomous trading starts sending LIVE
            // orders unsupervised. This check requires that proof -- see LiveCanaryService.
            // startCanary for how an admin runs one, and hasRecentPassingCanary for the 24-hour
            // validity window.
            if (!liveCanaryService.hasRecentPassingCanary(credentialId)) {
                credentialService.audit(userId, credentialId, credential.getBroker(), "LIVE_AUTOTRADE_AUTH_REFUSED_NO_LIVE_CANARY",
                    "LIVE auto-trade authorization refused: this credential has no PASSED live canary order on record "
                        + "within the last 24 hours. Run a live canary order first (POST /api/broker/{id}/live-canary) and "
                        + "let it resolve to PASSED before authorizing autonomous LIVE trading.");
                throw new IllegalArgumentException("LIVE auto-trade authorization refused: no PASSED live canary order on "
                    + "record for this credential within the last 24 hours. Run a live canary order first, and wait for it "
                    + "to resolve to PASSED (it must actually fill and get real OCO protection placed), before authorizing "
                    + "autonomous LIVE trading.");
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
            // Uses the same key-level apiRestrictions check BrokerCredentialService applies at
            // connect/rotation time, rather than permissions.canWithdraw() -- an account-level
            // flag that says nothing about this specific key's own restrictions and is
            // essentially always true for a real account regardless of the key. Reusing the
            // key-level check here means a key that had its restrictions loosened after
            // connecting is caught on every re-authorization too, not just once at connect time.
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
        // Without a targeted update, a concurrent doUpsert() call (which intentionally sets
        // liveAutoTradeAuthorized=false whenever risk limits change) could be raced past: this
        // method reads the profile, the user changes risk limits, and a stale in-memory
        // `profile` here would then save liveAutoTradeAuthorized=true anyway. A $set on only the
        // two fields this method actually changes avoids that, since it never touches or
        // depends on any field doUpsert also writes.
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
        // Same targeted-update approach as elsewhere in this class. Revocation is the more
        // safety-critical direction of this pair -- a full save() here losing a race would mean
        // a LIVE authorization stays granted when it should have been revoked, not the safer
        // failure mode.
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
