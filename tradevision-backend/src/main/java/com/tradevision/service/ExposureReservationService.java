package com.tradevision.service;

import org.springframework.dao.DuplicateKeyException;
import com.tradevision.model.ExposureReservation;
import com.tradevision.model.ExposureReservationRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Review finding ("P0 #3" — full context in ExposureReservation's own javadoc): the atomic
 * exposure-cap enforcement PositionSlotReservationService only ever provided for trade COUNT.
 * Same reserve()/release()/reconcile() shape, same MongoDB single-document-atomicity guarantee,
 * generalized to a dollar amount and (optionally) a per-symbol breakdown.
 *
 * reserve() is two sequential atomic steps, not one: (1) atomically reserve into the TOTAL
 * exposure field, conditioned on staying under the total cap; (2) if that succeeds, atomically
 * reserve into the SYMBOL exposure field, conditioned on staying under the symbol cap — and if
 * step 2 fails, roll back step 1. This isn't a single indivisible transaction across both fields,
 * but it doesn't need to be: each field's own cap is enforced correctly and atomically by its
 * own step regardless of what the other field is doing concurrently. The only imperfection is a
 * brief window where a rolled-back total reservation could make a CONCURRENT request's total-cap
 * check slightly more conservative than strictly necessary — a safe failure mode (a false
 * rejection), not an unsafe one (never a false approval of either cap).
 *
 * Review finding ("Exposure reservation rollback can steal another trade's reservation" /
 * "Generic exposure release() has the same ownership problem" — external review, twenty-sixth
 * pass, P0, the review's own explicit "biggest thing found in v172"): every successful
 * reservation now creates its own durable ExposureReservationRecord (see that class's own
 * javadoc for the full mechanism) capturing exactly what was reserved. release(String
 * reservationId) reads the amounts back from that record rather than trusting a caller-
 * recomputed value, and atomically claims the record before decrementing anything, making a
 * double-release a genuine no-op instead of a double-decrement. The intra-call rollback inside
 * reserve() itself (a later step failing, undoing an earlier step within the SAME call) remains
 * a direct counter decrement — that specific operation is mathematically safe on its own terms
 * (it undoes this same execution's own just-made increment, by the identical amount, before any
 * reservation record for it has ever been created or handed to a caller) — but every reservation
 * that actually succeeds and is handed back to a caller is now tracked by record, closing the
 * real risk: a caller releasing a DIFFERENT amount than it actually reserved, or releasing the
 * same logical reservation more than once.
 */
@Service
@RequiredArgsConstructor
public class ExposureReservationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExposureReservationService.class);
    private static final Duration GRACE_WINDOW = Duration.ofSeconds(120);
    /**
     * P3-5 fix ("ExposureReservationService group/symbol field paths -- user-supplied group
     * names used as Mongo field paths ('.'/'$') -- validate names" -- external review, confirmed
     * real by direct inspection: "reservedGroupExposure." + groupName is used as a literal Mongo
     * field path in both reserve() overloads and release() below. RiskProfileService.upsert now
     * rejects a bad group name at the one place it's actually written (see that method's own new
     * validation, right where enabledSymbols is validated the same way), so this should never see
     * a bad name going forward -- this is defense in depth for a profile document written before
     * that fix existed, or by a direct database write bypassing the API entirely. Skipping (never
     * throwing) here is deliberate: this method is on the hot path of real order placement, and a
     * malformed correlation-group name on an old document must degrade to "this one group isn't
     * enforced this call" rather than block a legitimate order or crash the reservation entirely.
     */
    private static boolean isSafeGroupName(String groupName) {
        return groupName != null && !groupName.isBlank() && !groupName.contains(".") && !groupName.contains("$");
    }
    /**
     * Review finding ("stale PENDING reservation can race a slow reservation" -- external
     * review, thirtieth pass, P1, confirmed real by direct inspection before this fix: a
     * reserve() call that creates its own PENDING record and then pauses for longer than
     * GRACE_WINDOW (an extreme GC pause, a genuinely stuck thread, or similar) could have that
     * PENDING record deleted as "abandoned" by a concurrent reconcile() pass, and then resume
     * and claim the counter anyway -- leaving a real, counted reservation with no durable
     * record explaining it, for the remainder of this process's own lifetime): the review's own
     * fully correct fix is a Mongo transaction wrapping the whole reserve() sequence, which this
     * codebase already requires for LIVE authorization -- not attempted here because reserve()
     * is also called for TESTNET/PAPER credentials, where transaction support (a replica set) is
     * genuinely not guaranteed, and reserve() has no BrokerMode parameter to gate on today;
     * adding one is a real, separate, larger change than this specific fix warrants doing
     * alongside everything else in this pass. Narrowed pragmatically instead: a PENDING record
     * only reaches this window's own reconcile()-driven cleanup after being PENDING for this
     * much longer than any real reserve() call should ever take (a normal call resolves in
     * milliseconds) -- reducing, though not eliminating, the probability of the exact pause
     * this race requires actually exceeding the cleanup threshold before reserve() itself
     * resumes and finishes.
     */
    private static final Duration PENDING_CLEANUP_WINDOW = Duration.ofMinutes(10);

    private final MongoTemplate mongoTemplate;
    /**
     * Review finding ("v183 still has a dangerous 'PENDING reservation cleanup' window" --
     * external review, thirty-fifth pass, P0, full context in reserve()'s own updated comment
     * on the final PENDING-to-ACTIVE transition): needed to surface the exact dangerous state
     * the review names -- counters already claimed, but the durable record proving why failed
     * its own final transition -- loudly, rather than silently.
     */
    private final IncidentService incidentService;
    /**
     * Review finding ("v183 still has a dangerous 'PENDING reservation cleanup' window" --
     * external review, thirty-fifth/thirty-seventh passes, P0, full context in
     * ExposureReservationRecord.executionId's own field javadoc): needed for the real safety
     * check in reconcile()'s own stale-PENDING cleanup.
     */
    private final com.tradevision.repository.ExecutionContextRepository executionContextRepo;
    private final com.tradevision.repository.RiskProfileRepository riskProfileRepo;
    private final com.tradevision.repository.ExposureReservationRecordRepository reservationRecordRepo;

    public record ExposureReserveResult(boolean allowed, String reason, String reservationId) {
        public static ExposureReserveResult ok(String reservationId) { return new ExposureReserveResult(true, null, reservationId); }
        public static ExposureReserveResult reject(String reason) { return new ExposureReserveResult(false, reason, null); }
    }

    public ExposureReserveResult reserve(String credentialId, String symbol, BigDecimal orderQuoteValue,
                                          BigDecimal maxTotal, BigDecimal maxSymbol) {
        return reserve(credentialId, symbol, orderQuoteValue, maxTotal, maxSymbol, null, null, null, false);
    }

    /**
     * Review finding ("Risk" — "atomic correlation reservations"): the same overload above,
     * extended with an optional third atomic step for correlation-group caps — see
     * ExposureReservation's own updated javadoc for the full context. correlationGroups/
     * correlationGroupCaps may be null or empty (no correlation groups configured for this
     * profile) — in which case this behaves identically to the two-step overload above. A symbol
     * can belong to more than one group; each matching group is reserved as its own atomic step,
     * and if ANY step (total, symbol, or any group) fails, every step that already succeeded is
     * rolled back — the whole reservation is all-or-nothing, not partially applied.
     *
     * Review finding ("v183 still has a dangerous 'PENDING reservation cleanup' window" --
     * external review, thirty-fifth/thirty-seventh passes, P0, full context in
     * ExposureReservationRecord.executionId's own field javadoc): executionId, when the caller
     * has one (AutoTradeService always does, by the time it calls this -- ExecutionContext is
     * created before any reservation is attempted), is stored on the PENDING record before any
     * counter is touched, giving reconcile()'s own stale-PENDING cleanup a real, durable thing
     * to check before deciding it's safe to delete. Genuinely nullable -- a caller with no
     * executionId available (PositionMonitorService's own late-fill-discovery recovery path,
     * for instance, which runs after the original evaluation and its own ExecutionContext are
     * both long finished) passes null, and reconcile() falls back to the prior, time-based-only
     * safety check for those specific records.
     */
    public ExposureReserveResult reserve(String credentialId, String symbol, BigDecimal orderQuoteValue,
                                          BigDecimal maxTotal, BigDecimal maxSymbol,
                                          java.util.Map<String, java.util.Set<String>> correlationGroups,
                                          java.util.Map<String, BigDecimal> correlationGroupCaps,
                                          String executionId) {
        return reserve(credentialId, symbol, orderQuoteValue, maxTotal, maxSymbol, correlationGroups, correlationGroupCaps, executionId, false);
    }

    /**
     * Review finding ("Make LIVE reservation transaction fallback impossible" -- external
     * review, thirty-ninth pass, the review's own explicit distinction: "Mongo supports
     * transactions + runtime/session acquisition problem" is a DIFFERENT scenario from the
     * startup check that already gates LIVE on a deployment with no replica-set support at all
     * -- this is a live, transient failure DURING an otherwise-transactional deployment, and the
     * review's own point stands: "the system can theoretically lose the atomic reservation
     * lifecycle guarantee during a runtime failure that causes session acquisition to fail," with
     * nothing before this fix distinguishing that case from "deployment genuinely doesn't support
     * transactions" at all -- both silently took the exact same non-transactional fallback path):
     * the actual fix -- live, when true, means this reservation is for a genuine LIVE-mode
     * autonomous trade, and the review's own required policy applies: "transaction unavailable ->
     * DO NOT fallback -> reject reservation -> halt autonomous execution -> critical incident."
     * TESTNET/PAPER (live=false) keep the existing sequential-fallback behavior unchanged --
     * this session's own earlier, deliberate choice not to require a replica set for those modes.
     */
    public ExposureReserveResult reserve(String credentialId, String symbol, BigDecimal orderQuoteValue,
                                          BigDecimal maxTotal, BigDecimal maxSymbol,
                                          java.util.Map<String, java.util.Set<String>> correlationGroups,
                                          java.util.Map<String, BigDecimal> correlationGroupCaps,
                                          String executionId, boolean live) {
        return reserveTransactionally(credentialId, symbol, orderQuoteValue, maxTotal, maxSymbol,
            correlationGroups, correlationGroupCaps, executionId, live);
    }

    /**
     * Review finding ("Reservation lifecycle is still not transactionally safe" -- external
     * review, thirty-eighth pass, P0, the review's own explicit final fix: "Mongo transaction:
     * insert reservation PENDING + increment all relevant counters + set ACTIVE, all in one
     * transaction. If transaction fails: ROLLBACK EVERYTHING... You already require Mongo
     * transactions for critical LIVE execution authorization, so I would extend that requirement
     * to these reservation operations"): the actual fix, following the EXACT same proven pattern
     * RiskProfileService.claimExecutionAtomicWithPlan already uses for LIVE execution
     * authorization -- try a real Mongo ClientSession/transaction first; on the SPECIFIC,
     * recognizable "this deployment doesn't support transactions" error (a standalone instance,
     * not a replica set -- exactly the TESTNET/PAPER concern this session had previously declined
     * a transactional rewrite over), fall back to the prior sequential approach below, which
     * already has its own complete, independently-tested rollback-on-failure and
     * loud-not-silent-activation-failure safety nets. Never silently swallow a genuine, different
     * MongoDB error into a fallback that wouldn't actually address it.
     */
    /**
     * CI-review fix ("Multi-plan combined exposure reservation" -- external review, fifth pass,
     * failure 2, full context in reserveTransactionally's own updated javadoc): same bounded
     * retry budget as PositionSlotReservationService's own identical constant, for the same
     * reasoning -- a genuine WriteConflict under real concurrent transactions against the same
     * document is expected and retryable, not an infrastructure failure.
     */
    private static final int MAX_TRANSACTION_RETRIES = 10;

    /**
     * CI-review fix, same context as the retry-backoff comment at this method's own WriteConflict
     * retry call site: a short, randomized (jittered) delay before each retry, growing slightly
     * with the attempt number but capped low -- this is a real database transaction on the hot
     * order-placement path, not a background job, so even the worst case (attempt 10) adds at
     * most tens of milliseconds, not seconds. Randomized specifically so that when multiple
     * threads collide and all back off, they don't then retry in lockstep and collide again on
     * the very next attempt -- a fixed, identical delay would just shift the same collision
     * forward in time rather than actually reducing it.
     */
    private static long sleepMillisBeforeRetry(int attempt) {
        int baseMillis = Math.min(attempt * 2, 20);
        return baseMillis + java.util.concurrent.ThreadLocalRandom.current().nextInt(10);
    }

    private ExposureReserveResult reserveTransactionally(String credentialId, String symbol, BigDecimal orderQuoteValue,
                                          BigDecimal maxTotal, BigDecimal maxSymbol,
                                          java.util.Map<String, java.util.Set<String>> correlationGroups,
                                          java.util.Map<String, BigDecimal> correlationGroupCaps,
                                          String executionId, boolean live) {
        boolean totalCapConfigured = maxTotal != null && maxTotal.signum() > 0;
        boolean symbolCapConfigured = maxSymbol != null && maxSymbol.signum() > 0;
        java.util.List<String> groupsToReserve = new java.util.ArrayList<>();
        if (correlationGroups != null && correlationGroupCaps != null) {
            for (var entry : correlationGroups.entrySet()) {
                String groupName = entry.getKey();
                if (!isSafeGroupName(groupName)) {
                    log.error("Skipping correlation group \"{}\" for credential {} -- not a safe MongoDB field-path segment "
                        + "(contains '.' or '$', or blank). This should never happen for a group saved after the P3-5 fix; "
                        + "if you're seeing this, the profile document itself needs correcting.", groupName, credentialId);
                    continue;
                }
                if (entry.getValue() == null || !entry.getValue().contains(symbol)) continue;
                BigDecimal groupCap = correlationGroupCaps.get(groupName);
                if (groupCap == null || groupCap.signum() <= 0) continue;
                groupsToReserve.add(groupName);
            }
        }

        com.mongodb.client.ClientSession session;
        try {
            session = mongoTemplate.getMongoDatabaseFactory().getSession(com.mongodb.ClientSessionOptions.builder().build());
        } catch (Exception e) {
            if (live) return rejectAndHaltForLive(credentialId, symbol, "obtain a MongoDB ClientSession at all", e.getMessage());
            log.warn("Could not obtain a MongoDB ClientSession at all ({}) -- falling back to the sequential, non-transactional "
                + "reserve approach for credential {}.", e.getMessage(), credentialId);
            return reserveSequentially(credentialId, symbol, orderQuoteValue, maxTotal, maxSymbol,
                correlationGroups, correlationGroupCaps, executionId);
        }
        try {
            // CI-review fix ("Multi-plan combined exposure reservation" -- external review, fifth
            // pass, failure 2, confirmed real by direct inspection: threeConcurrentPlansSameSymbol_
            // combinedExposureNeverExceedsAccountCap expected 2 successes out of 3 concurrent
            // callers, but got 0): this method's own transaction touches the SAME per-credential
            // ExposureReservation document even more times than PositionSlotReservationService's
            // equivalent (ensureDocumentExists, ensureSymbolFieldExists, optional group-field
            // setup, insert, then up to one findAndModify per total/symbol/group cap) -- more
            // writes per transaction against one shared document means more exposure to a
            // WriteConflict (MongoDB error code 112, a "TransientTransactionError") under real
            // concurrent load, and with only 3 racing callers in that test, all 3 collided before
            // any of them reached a clean commit. Before this fix there was no retry logic for
            // that specific, expected, retryable condition anywhere in this method -- it fell
            // straight through to `throw e` below and escaped reserve() as an uncaught exception,
            // which the test's own `executor.submit(...)` (never checking the returned Future)
            // silently swallowed, so `successCount` never got incremented for any of the 3.
            // Retrying the whole transaction body on the same session (MongoDB's own documented
            // retry pattern -- a ClientSession stays valid across a startTransaction()/
            // abortTransaction() cycle) lets every caller that can legally fit under the cap
            // actually get the chance to, instead of losing its only attempt to contention.
            for (int attempt = 1; ; attempt++) {
                try {
                    session.startTransaction();
                    var sessionTemplate = mongoTemplate.withSession(session);
                    // Review finding (self-diagnosed, live production bug, full context in
                    // ensureDocumentExists's own updated javadoc): all three setup calls now run INSIDE
                    // the transaction, on the session-bound template -- genuinely part of the same
                    // transactional snapshot as the counter claims that follow, closing the
                    // fresh-credential visibility gap that could wrongly reject a first-ever reservation
                    // with zero real competition.
                    ensureDocumentExists(credentialId, sessionTemplate);
                    ensureSymbolFieldExists(credentialId, symbol, sessionTemplate);
                    for (String groupName : groupsToReserve) ensureGroupFieldExists(credentialId, groupName, sessionTemplate);

                    var record = new ExposureReservationRecord();
                    record.setCredentialId(credentialId);
                    record.setSymbol(symbol);
                    record.setStatus("PENDING");
                    record.setExecutionId(executionId);
                    if (totalCapConfigured) record.setTotalAmountReserved(orderQuoteValue);
                    if (symbolCapConfigured) record.setSymbolAmountReserved(orderQuoteValue);
                    if (!groupsToReserve.isEmpty()) {
                        var groupAmounts = new java.util.HashMap<String, BigDecimal>();
                        for (String g : groupsToReserve) groupAmounts.put(g, orderQuoteValue);
                        record.setGroupAmountsReserved(groupAmounts);
                    }
                    record = sessionTemplate.insert(record);

                    if (totalCapConfigured) {
                        Query totalQuery = new Query(where("credentialId").is(credentialId)
                            .and("reservedTotalExposureQuote").lte(toDecimal128(maxTotal.subtract(orderQuoteValue))));
                        Update totalInc = new Update().inc("reservedTotalExposureQuote", toDecimal128(orderQuoteValue)).set("lastReservedAt", Instant.now());
                        var totalResult = sessionTemplate.findAndModify(
                            totalQuery, totalInc, FindAndModifyOptions.options().returnNew(true), ExposureReservation.class);
                        if (totalResult == null) {
                            // CI-review fix ("same 2 test failures again" -- MultiPlanExposureIntegrationTest
                            // still expects 2 successes out of 3 concurrent attempts but gets 0, even after the
                            // retry/backoff fix above landed and was confirmed running in real CI): every reject()
                            // return in this method was completely silent before this fix -- there was no way to
                            // tell, from a CI log alone, whether a given attempt lost legitimately to the cap or
                            // was rejected by some other bug entirely. Logging the actual document state this
                            // decision was based on (not just "rejected") so the next real CI run finally reveals
                            // which one this is, instead of another round of guessing.
                            log.warn("reserve() REJECT (total cap) for credential {} symbol {} attempt {}: requested {}, cap {} "
                                + "(would need existing reservedTotalExposureQuote <= {} for this to have been allowed).",
                                credentialId, symbol, attempt, orderQuoteValue, maxTotal, maxTotal.subtract(orderQuoteValue));
                            session.abortTransaction();
                            return ExposureReserveResult.reject("Would exceed total exposure cap of " + maxTotal);
                        }
                    }

                    if (symbolCapConfigured) {
                        String symbolField = "reservedSymbolExposure." + symbol;
                        // CI-review fix ("same 2 test failures again" -- real root cause, found after
                        // adding the reject()-visibility logging above and re-running real CI: the
                        // very FIRST-EVER reservation for a brand-new credential/symbol was being
                        // rejected, with no concurrency involved at all -- not a race, a structural
                        // bug. reservedTotalExposureQuote is a literal, @Field(targetType=DECIMAL128)
                        // -annotated property, so Spring Data's QueryMapper knows to convert a raw
                        // BigDecimal criteria value to Decimal128 for it automatically -- that's why
                        // the total-cap check above has always worked. reservedSymbolExposure.<symbol>
                        // (and reservedGroupExposure.<group>) is a DYNAMIC key inside a
                        // Map<String,BigDecimal> -- there is no way to annotate a per-key target type
                        // for a map Spring doesn't know the keys of ahead of time, so QueryMapper has
                        // no metadata to convert by and falls back to this codebase's own default
                        // BigDecimal handling, which ExposureReservation's own javadoc already
                        // documents elsewhere: stored/compared as a STRING, not a number, unless
                        // explicitly converted first. ensureSymbolFieldExists (and ensureGroupFieldExists)
                        // already store the zeroed starting value as a real Decimal128 (via this same
                        // toDecimal128 helper) -- so the stored value was Decimal128(0), the query's
                        // own comparison value was being sent as a string, and a MongoDB $lte between
                        // two different BSON types never matches, REGARDLESS of the actual numbers --
                        // explaining both this test's single-threaded, zero-contention rejection and
                        // MultiPlanExposureIntegrationTest's successCount-always-0 (every single
                        // attempt there was failing at this exact, same symbol-cap check, which just
                        // happened to look concurrency-shaped because three threads were racing to
                        // reach the SAME broken comparison, not because the comparison itself was ever
                        // close). Fixed the same way the Update side already converts: wrap the
                        // comparison value in toDecimal128(...) explicitly rather than relying on
                        // Spring to infer a type it structurally cannot infer for a dynamic map key.
                        Query symbolQuery = new Query(where("credentialId").is(credentialId)
                            .and(symbolField).lte(toDecimal128(maxSymbol.subtract(orderQuoteValue))));
                        Update symbolInc = new Update().inc(symbolField, toDecimal128(orderQuoteValue)).set("lastReservedAt", Instant.now());
                        var symbolResult = sessionTemplate.findAndModify(
                            symbolQuery, symbolInc, FindAndModifyOptions.options().returnNew(true), ExposureReservation.class);
                        if (symbolResult == null) {
                            // Same diagnostic-visibility fix as the total-cap reject just above.
                            log.warn("reserve() REJECT (symbol cap) for credential {} symbol {} attempt {}: requested {}, cap {} "
                                + "(would need existing reservedSymbolExposure.{} <= {} for this to have been allowed).",
                                credentialId, symbol, attempt, orderQuoteValue, maxSymbol, symbol, maxSymbol.subtract(orderQuoteValue));
                            session.abortTransaction(); // undoes the total-cap increment above too -- the whole transaction, atomically
                            return ExposureReserveResult.reject("Would exceed per-symbol exposure cap of " + maxSymbol + " for " + symbol);
                        }
                    }

                    for (String groupName : groupsToReserve) {
                        BigDecimal groupCap = correlationGroupCaps.get(groupName);
                        String groupField = "reservedGroupExposure." + groupName;
                        // Same dynamic-map-key Decimal128 fix as the symbol-cap query just above.
                        Query groupQuery = new Query(where("credentialId").is(credentialId)
                            .and(groupField).lte(toDecimal128(groupCap.subtract(orderQuoteValue))));
                        Update groupInc = new Update().inc(groupField, toDecimal128(orderQuoteValue)).set("lastReservedAt", Instant.now());
                        var groupResult = sessionTemplate.findAndModify(
                            groupQuery, groupInc, FindAndModifyOptions.options().returnNew(true), ExposureReservation.class);
                        if (groupResult == null) {
                            session.abortTransaction(); // undoes total, symbol, AND every group claim already made in this same transaction
                            return ExposureReserveResult.reject("Would exceed correlation-group exposure cap of " + groupCap + " for group " + groupName);
                        }
                    }

                    // No separate "activate" step needed at all -- unlike the sequential fallback, which
                    // must handle a real window between "counters claimed" and "record marked ACTIVE"
                    // because those are genuinely separate operations there, a transaction makes this
                    // moot: the record is inserted as PENDING and every counter claim happens together,
                    // atomically, so flipping it to ACTIVE in the SAME transaction, right here, commits
                    // or rolls back as one indivisible unit with everything above it. There is no crash
                    // window between them at all on a deployment where this transactional path runs.
                    sessionTemplate.updateFirst(
                        new Query(where("id").is(record.getId())),
                        new Update().set("status", "ACTIVE"),
                        ExposureReservationRecord.class);

                    session.commitTransaction();
                    // Same diagnostic-visibility fix as the reject() logging above -- a successful
                    // commit was also completely silent before this fix.
                    log.debug("reserve() OK for credential {} symbol {} attempt {}: reserved {}, record {}.",
                        credentialId, symbol, attempt, orderQuoteValue, record.getId());
                    return ExposureReserveResult.ok(record.getId());
                } catch (RuntimeException e) {
                    try {
                        if (session.hasActiveTransaction()) session.abortTransaction();
                    } catch (Exception ignore) {
                        // Best-effort cleanup only -- the transaction attempt already failed.
                    }
                    if (isTransientTransactionError(e) && attempt < MAX_TRANSACTION_RETRIES) {
                        log.debug("Transient MongoDB transaction error on reserve() attempt {} for credential {} symbol {} ({}) -- retrying.",
                            attempt, credentialId, symbol, e.getMessage());
                        // CI-review fix ("Multi-plan combined exposure reservation" -- real CI run,
                        // GitHub Actions log archive downloaded and inspected directly: with real
                        // concurrent threads racing this credential's SAME document, successCount
                        // came back 0 of 3, not the 2 the account-level cap should allow, even
                        // though this retry loop already existed and real WriteConflict retries
                        // were visibly happening in the log): this specific document sees MORE
                        // writes per transaction than PositionSlotReservationService's own
                        // equivalent (ensureDocumentExists, ensureSymbolFieldExists, insert, then
                        // up to two findAndModify calls -- total AND symbol, both configured in
                        // this exact test), so it has more surface for a WriteConflict under real
                        // concurrent load, and retrying every attempt back-to-back with ZERO delay
                        // (as this loop did before this fix) maximizes the odds of the SAME threads
                        // immediately re-colliding on their very next attempt instead of letting
                        // whichever one is already ahead actually commit. A short, randomized sleep
                        // before each retry -- MongoDB's own documented guidance for exactly this
                        // scenario -- breaks that lockstep without meaningfully slowing down the
                        // common, uncontended case (most reserve() calls never retry at all).
                        try {
                            Thread.sleep(sleepMillisBeforeRetry(attempt));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        continue;
                    }
                    // CI-review fix, same context as the retry-backoff comment just above: if every
                    // retry is exhausted, or this isn't a transient error at all, this exception
                    // used to fall straight through to `throw e` below with no log line of its
                    // own -- and since this method's only real caller in the failing test submits
                    // to an ExecutorService without ever checking the returned Future, a thrown
                    // exception here is silently swallowed with NO trace anywhere in the log. That
                    // made the real CI failure genuinely undiagnosable from the log alone (confirmed
                    // directly: a downloaded GitHub Actions log archive for this exact failure shows
                    // only the first few retry attempts and then nothing -- no stack trace, no
                    // further detail, for any of the 3 threads). Logging here, unconditionally,
                    // before this method's own control flow decides what to do next, means the next
                    // real CI run that hits this path leaves an actual diagnosable trace instead of
                    // silence.
                    log.warn("reserve() for credential {} symbol {} giving up after attempt {} ({}): {}",
                        credentialId, symbol, attempt, e.getClass().getName(), e.getMessage(), e);
                    // Review finding (self-diagnosed, live production bug: this exact catch clause was
                    // catching ONLY com.mongodb.MongoException directly -- but confirmed real by direct
                    // inspection of a real production log, that's not actually what escapes here.
                    // sessionTemplate.insert/findAndModify/updateFirst all go through Spring Data's own
                    // MongoTemplate, which applies Spring's own exception translation -- wrapping the raw
                    // driver exception (com.mongodb.MongoCommandException, error code 20 on a standalone
                    // instance) into org.springframework.dao.DataAccessException (specifically
                    // UncategorizedMongoDbException for an error Spring doesn't have a specific
                    // translation for), a completely different type hierarchy that a catch on
                    // com.mongodb.MongoException never matches at all. That meant this fallback branch
                    // never actually ran on a standalone Mongo instance -- the real exception escaped
                    // this method entirely, propagated up through several unrelated call frames, and was
                    // ultimately caught and badly mislabeled by a much higher, generic catch block in
                    // PositionMonitorService.reconcileEntryOrders as "Could not fetch order status," which
                    // repeated on every single reconciliation pass since the position this reservation was
                    // for could then never actually get created. The actual fix: catch RuntimeException
                    // broadly (this session/transaction code's own thrown types are all unchecked, so this
                    // narrows to real failures here, not a silent catch-all), and walk the FULL cause
                    // chain -- not just the top-level exception -- for the actual standalone-instance
                    // signature, since that raw signature can be buried one or more levels down inside
                    // Spring's own wrapper.
                    if (isStandaloneMongoTransactionError(e)) {
                        if (live) return rejectAndHaltForLive(credentialId, symbol, "run the reservation inside a real MongoDB transaction "
                            + "(standalone deployment, not a replica set/mongos)", e.getMessage());
                        log.warn("MongoDB transactions are not supported by this deployment (standalone, not a replica set/mongos) -- falling "
                            + "back to the sequential, non-transactional reserve approach for credential {}. The full atomic-lifecycle guarantee "
                            + "this transactional path provides is not in effect on this deployment -- configure a MongoDB replica set to close "
                            + "it. The sequential fallback's own independent safety nets (rollback-on-failure, loud-not-silent activation "
                            + "failure) still apply.", credentialId);
                        return reserveSequentially(credentialId, symbol, orderQuoteValue, maxTotal, maxSymbol,
                            correlationGroups, correlationGroupCaps, executionId);
                    }
                    throw e;
                }
            }
        } finally {
            session.close();
        }
    }

    /**
     * Review finding ("Make LIVE reservation transaction fallback impossible" -- external
     * review, thirty-ninth pass, the review's own explicit required flow: "transaction
     * unavailable -> DO NOT fallback -> reject reservation -> halt autonomous execution ->
     * critical incident"): the actual fail-closed action, shared by both fallback points above.
     * Setting tradingHalted here (not merely rejecting this one reservation) is deliberate: a
     * transaction-capable deployment that has stopped being able to acquire sessions or run
     * transactions is a genuine infrastructure problem that will affect every subsequent LIVE
     * reservation attempt identically, not a one-off this specific signal happened to hit --
     * halting stops the account from repeatedly hitting the same failure on every future signal
     * until an operator has actually looked at it, the same reasoning this codebase's own other
     * tradingHalted call sites already apply.
     */
    private ExposureReserveResult rejectAndHaltForLive(String credentialId, String symbol, String failedTo, String detail) {
        log.error("LIVE reservation for credential {} ({}) could not {}: {}. Per this deployment's own fail-closed LIVE policy, "
            + "NOT falling back to the non-transactional path -- rejecting this reservation and halting autonomous trading for this "
            + "credential.", credentialId, symbol, failedTo, detail);
        String reason = "LIVE reservation could not " + failedTo + ": " + detail;
        mongoTemplate.updateFirst(
            new Query(where("credentialId").is(credentialId)),
            new Update().set("tradingHalted", true).set("haltReason", reason),
            com.tradevision.model.RiskProfile.class);
        incidentService.raiseCritical(null, credentialId, null, null, symbol, "LIVE_RESERVATION_TRANSACTION_UNAVAILABLE",
            "A LIVE-mode reservation for credential " + credentialId + " (" + symbol + ") could not " + failedTo + ": " + detail
                + ". Trading has been automatically halted for this credential rather than falling back to a non-transactional "
                + "reservation path, per this deployment's own fail-closed LIVE policy. Manual investigation of MongoDB transaction "
                + "support/session availability is required before re-enabling trading.");
        return ExposureReserveResult.reject(reason);
    }

    private ExposureReserveResult reserveSequentially(String credentialId, String symbol, BigDecimal orderQuoteValue,
                                          BigDecimal maxTotal, BigDecimal maxSymbol,
                                          java.util.Map<String, java.util.Set<String>> correlationGroups,
                                          java.util.Map<String, BigDecimal> correlationGroupCaps,
                                          String executionId) {
        ensureDocumentExists(credentialId, mongoTemplate);
        ensureSymbolFieldExists(credentialId, symbol, mongoTemplate);

        boolean totalCapConfigured = maxTotal != null && maxTotal.signum() > 0;
        boolean symbolCapConfigured = maxSymbol != null && maxSymbol.signum() > 0;
        // Which groups this reservation will actually touch -- computed up front, before any
        // counter is claimed, purely from the caller's own configuration (no database call
        // needed to know this part).
        java.util.List<String> groupsToReserve = new java.util.ArrayList<>();
        if (correlationGroups != null && correlationGroupCaps != null) {
            for (var entry : correlationGroups.entrySet()) {
                String groupName = entry.getKey();
                if (!isSafeGroupName(groupName)) {
                    log.error("Skipping correlation group \"{}\" for credential {} -- not a safe MongoDB field-path segment "
                        + "(contains '.' or '$', or blank). This should never happen for a group saved after the P3-5 fix; "
                        + "if you're seeing this, the profile document itself needs correcting.", groupName, credentialId);
                    continue;
                }
                if (entry.getValue() == null || !entry.getValue().contains(symbol)) continue;
                BigDecimal groupCap = correlationGroupCaps.get(groupName);
                if (groupCap == null || groupCap.signum() <= 0) continue;
                groupsToReserve.add(groupName);
            }
        }

        // Review finding ("Exposure reservation creation is still not atomic with the exposure
        // counter" -- external review, twenty-eighth pass, P0, the review's own explicit
        // "biggest thing found in v174" finding: reservationRecordRepo.save(record) used to
        // happen AFTER every counter claim already succeeded -- if that save failed, the
        // counters were already incremented with no record ever created to track or release
        // them, leaving a permanently over-reserved account correctable only by reconcile()'s
        // own 120-second grace window, not immediately): the actual fix -- the record is
        // created FIRST, as PENDING, with the exact amounts this call intends to claim, before
        // any counter is touched at all. If this insert itself fails, nothing has been claimed
        // yet -- reject immediately, no rollback needed. Only once every counter claim below has
        // actually succeeded is the record marked ACTIVE; if this application crashes at any
        // point after this insert, a PENDING record referencing exactly this credential/symbol/
        // amounts durably exists for reconciliation to find, verify against the real counters,
        // and resolve -- never a counter increment with nothing at all to explain it.
        var record = new ExposureReservationRecord();
        record.setCredentialId(credentialId);
        record.setSymbol(symbol);
        record.setStatus("PENDING");
        record.setExecutionId(executionId);
        if (totalCapConfigured) record.setTotalAmountReserved(orderQuoteValue);
        if (symbolCapConfigured) record.setSymbolAmountReserved(orderQuoteValue);
        if (!groupsToReserve.isEmpty()) {
            var groupAmounts = new java.util.HashMap<String, BigDecimal>();
            for (String g : groupsToReserve) groupAmounts.put(g, orderQuoteValue);
            record.setGroupAmountsReserved(groupAmounts);
        }
        try {
            record = reservationRecordRepo.insert(record);
        } catch (Exception e) {
            log.warn("Could not create the PENDING exposure reservation record for credential {} -- refusing to touch any counter "
                + "without a durable record to track it: {}", credentialId, e.getMessage());
            return ExposureReserveResult.reject("Could not create a durable reservation record -- refusing to reserve exposure "
                + "without one. Real error: " + e.getMessage());
        }

        if (totalCapConfigured) {
            Query totalQuery = new Query(where("credentialId").is(credentialId)
                .and("reservedTotalExposureQuote").lte(toDecimal128(maxTotal.subtract(orderQuoteValue))));
            Update totalInc = new Update().inc("reservedTotalExposureQuote", toDecimal128(orderQuoteValue)).set("lastReservedAt", Instant.now());
            ExposureReservation totalResult = mongoTemplate.findAndModify(
                totalQuery, totalInc, FindAndModifyOptions.options().returnNew(true), ExposureReservation.class);
            if (totalResult == null) {
                deletePendingRecord(record.getId());
                return ExposureReserveResult.reject("Would exceed total exposure cap of " + maxTotal);
            }
        }

        if (symbolCapConfigured) {
            String symbolField = "reservedSymbolExposure." + symbol;
            // Same dynamic-map-key Decimal128 fix as reserveTransactionally's own identical query
            // (full context in that method's own updated comment) -- this sequential fallback path
            // has the exact same bug for the exact same reason.
            Query symbolQuery = new Query(where("credentialId").is(credentialId)
                .and(symbolField).lte(toDecimal128(maxSymbol.subtract(orderQuoteValue))));
            Update symbolInc = new Update().inc(symbolField, toDecimal128(orderQuoteValue)).set("lastReservedAt", Instant.now());
            ExposureReservation symbolResult = mongoTemplate.findAndModify(
                symbolQuery, symbolInc, FindAndModifyOptions.options().returnNew(true), ExposureReservation.class);
            if (symbolResult == null) {
                // Roll back the total reservation from step 1 — it was provisional on this step
                // also succeeding. Safe as a bare decrement: this undoes THIS SAME call's own
                // just-made increment, by the identical orderQuoteValue, before the record has
                // ever been marked ACTIVE or handed to a caller — see this class's own class
                // javadoc.
                if (totalCapConfigured) {
                    mongoTemplate.updateFirst(
                        new Query(where("credentialId").is(credentialId)),
                        new Update().inc("reservedTotalExposureQuote", toDecimal128(orderQuoteValue.negate())),
                        ExposureReservation.class);
                }
                deletePendingRecord(record.getId());
                return ExposureReserveResult.reject("Would exceed per-symbol exposure cap of " + maxSymbol + " for " + symbol);
            }
        }

        java.util.List<String> groupsReserved = new java.util.ArrayList<>();
        for (String groupName : groupsToReserve) {
            BigDecimal groupCap = correlationGroupCaps.get(groupName);
            ensureGroupFieldExists(credentialId, groupName, mongoTemplate);
            String groupField = "reservedGroupExposure." + groupName;
            // Same dynamic-map-key Decimal128 fix as above.
            Query groupQuery = new Query(where("credentialId").is(credentialId)
                .and(groupField).lte(toDecimal128(groupCap.subtract(orderQuoteValue))));
            Update groupInc = new Update().inc(groupField, toDecimal128(orderQuoteValue)).set("lastReservedAt", Instant.now());
            ExposureReservation groupResult = mongoTemplate.findAndModify(
                groupQuery, groupInc, FindAndModifyOptions.options().returnNew(true), ExposureReservation.class);
            if (groupResult == null) {
                // Roll back total, symbol, and every group reservation that already
                // succeeded in this same call — all-or-nothing, per this method's own
                // javadoc. Same "undo this call's own just-made increment" safety as above.
                if (totalCapConfigured) {
                    mongoTemplate.updateFirst(new Query(where("credentialId").is(credentialId)),
                        new Update().inc("reservedTotalExposureQuote", toDecimal128(orderQuoteValue.negate())), ExposureReservation.class);
                }
                if (symbolCapConfigured) {
                    mongoTemplate.updateFirst(new Query(where("credentialId").is(credentialId)),
                        new Update().inc("reservedSymbolExposure." + symbol, toDecimal128(orderQuoteValue.negate())), ExposureReservation.class);
                }
                for (String reservedGroup : groupsReserved) {
                    mongoTemplate.updateFirst(new Query(where("credentialId").is(credentialId)),
                        new Update().inc("reservedGroupExposure." + reservedGroup, toDecimal128(orderQuoteValue.negate())), ExposureReservation.class);
                }
                deletePendingRecord(record.getId());
                return ExposureReserveResult.reject("Would exceed correlation-group exposure cap of " + groupCap + " for group " + groupName);
            }
            groupsReserved.add(groupName);
        }

        // Every counter claim succeeded -- atomically flip this record from PENDING to ACTIVE.
        // Conditional on still being PENDING so a concurrent recovery pass that already resolved
        // this same record (a genuinely rare race, but not an impossible one) can't be
        // overwritten by this call finishing late.
        //
        // Review finding ("v183 still has a dangerous 'PENDING reservation cleanup' window" --
        // external review, thirty-fifth pass, P0, confirmed real by direct inspection before
        // this fix: this update's own return value used to be silently discarded. The review's
        // own exact dangerous scenario: if this specific update fails to match (the record was
        // deleted out from under this call -- e.g. a concurrent reconcile pass's own stale-
        // PENDING cleanup racing this exact reserve() call, which is precisely the crash-window
        // race the review describes), the aggregate counters above have ALREADY been durably
        // incremented, but the one record that could prove why now either never existed as
        // ACTIVE or was deleted -- exactly the "counter incremented, durable record gone" state
        // the review's own dangerous scenario walks through step by step): the actual fix, not
        // a full Mongo-transaction rewrite of this whole method (a materially larger, separate
        // change -- see reconcile()'s own PENDING_CLEANUP_WINDOW comment for why that wasn't
        // attempted wholesale earlier this session, given this method also runs for TESTNET/
        // PAPER credentials with no guaranteed replica-set support) -- this specific, narrow gap
        // is now at least LOUD rather than silent: a failed transition here raises a critical
        // incident naming the exact record and credential, since an operator needs to know this
        // exposure is durably counted with no ACTIVE record to explain or eventually release it,
        // even though the counters themselves cannot be safely unwound at this point without
        // risking a race with whatever already claimed this record.
        var activated = mongoTemplate.findAndModify(
            new Query(where("id").is(record.getId()).and("status").is("PENDING")),
            new Update().set("status", "ACTIVE"),
            ExposureReservationRecord.class);
        if (activated == null) {
            log.error("Exposure reservation {} for credential {} claimed real counter capacity (total {}"
                + (symbolCapConfigured ? ", symbol " + orderQuoteValue : "") + ") but its own final PENDING-to-ACTIVE transition matched "
                + "zero documents -- the record was likely deleted by a concurrent process between being created and this point. The "
                + "counters are now durably incremented with no ACTIVE record to explain or eventually release them.",
                record.getId(), credentialId, toDecimal128(orderQuoteValue));
            incidentService.raiseCritical(null, credentialId, null, null, symbol, "EXPOSURE_RESERVATION_ORPHANED_AT_ACTIVATION",
                "Exposure reservation " + record.getId() + " for credential " + credentialId + " on " + symbol + " claimed real "
                    + "aggregate counter capacity (" + orderQuoteValue + "), but its own final PENDING-to-ACTIVE database transition "
                    + "matched zero documents -- almost certainly because the record was deleted by a concurrent stale-PENDING cleanup "
                    + "pass racing this exact reserve() call. The counters remain durably incremented with no ACTIVE reservation record "
                    + "left to explain or eventually release them. Manual investigation required: verify whether a real position/order "
                    + "was actually created for this reservation, and reconcile the aggregate counters against real exchange state if not.");
        }

        return ExposureReserveResult.ok(record.getId());
    }

    /**
     * Review finding, same context as reserve()'s own updated javadoc: best-effort cleanup for
     * a reservation that never actually claimed any counter (the cap check rejected it, or a
     * later step in the same call did). Deliberately non-fatal -- a PENDING record that fails to
     * delete here is still correctly handled by reconciliation (see this class's own PENDING
     * recovery path in reconcile()), it just takes longer to disappear.
     */
    private void deletePendingRecord(String recordId) {
        try {
            reservationRecordRepo.deleteById(recordId);
        } catch (Exception e) {
            log.warn("Could not delete the now-unneeded PENDING reservation record {} (non-fatal -- reconciliation's own PENDING "
                + "recovery path will resolve it eventually): {}", recordId, e.getMessage());
        }
    }

    /**
     * Review finding ("Exposure reservation rollback can steal another trade's reservation" /
     * "Generic exposure release() has the same ownership problem" — external review, twenty-
     * sixth pass, P0, full context in this class's own class javadoc): the actual, safe release
     * — reads the exact amounts back from the reservation's own durable record rather than
     * trusting a caller-recomputed value, and atomically claims the record (ACTIVE -> RELEASED)
     * BEFORE decrementing anything, so a genuine double-release (a retry, two code paths racing
     * to release the same logical reservation) is a real no-op, not a double-decrement. This is
     * the method every real caller should now use — see release(String, String, BigDecimal)
     * below for the narrower cases where a reservationId genuinely isn't available.
     */
    /**
     * Review finding ("reservation reconciliation is still fundamentally cache-based" --
     * external review, twenty-ninth pass, P1, full context in
     * ExposureReservationRecord.positionId's own field javadoc): called once the position this
     * reservation was for is actually created and saved -- best-effort like every other write
     * in this class, since a failure here must never block the real position-creation flow it's
     * merely recording metadata about.
     */
    public void linkToPosition(String reservationId, String positionId) {
        if (reservationId == null || positionId == null) return;
        try {
            mongoTemplate.updateFirst(
                new Query(where("id").is(reservationId)),
                new Update().set("positionId", positionId),
                ExposureReservationRecord.class);
        } catch (Exception e) {
            log.warn("Could not link exposure reservation {} to position {} (non-fatal, purely observational for reconcile()'s own "
                + "improved accuracy -- the core reservation guarantee is unaffected): {}", reservationId, positionId, e.getMessage());
        }
    }

    public void release(String reservationId) {
        if (reservationId == null || reservationId.isBlank()) return;
        // Atomic claim: only a caller that wins this findAndModify actually proceeds to
        // decrement anything — a concurrent second call for the same id finds status no longer
        // "ACTIVE" and gets null back, exactly the idempotency guarantee this fix exists for.
        var claimed = mongoTemplate.findAndModify(
            new Query(where("id").is(reservationId).and("status").is("ACTIVE")),
            new Update().set("status", "RELEASED").set("releasedAt", Instant.now()),
            ExposureReservationRecord.class);
        if (claimed == null) return; // already released, or never existed — safe either way

        // Review finding ("Exposure reservation records can remain ACTIVE after reconciliation
        // overwrites counters" -- external review, twenty-eighth pass, P1, confirmed real by
        // direct inspection before this fix: this used to be a bare $inc with no lower-bound
        // condition at all -- if reconcile() had already reset the aggregate counter to its own
        // real, actual value (e.g. 0) while this record stayed ACTIVE because the position it
        // belonged to crashed before ever being persisted, releasing this stale record would
        // decrement the counter below zero. A negative reservation counter is a genuine risk-cap
        // failure -- it makes the NEXT real reservation's own cap check pass when it shouldn't):
        // the actual fix -- releaseFloored below only decrements down to zero, never below it,
        // using the exact same conditional-then-clamp pattern for every field this record
        // touched.
        if (claimed.getTotalAmountReserved() != null) {
            releaseFloored(claimed.getCredentialId(), "reservedTotalExposureQuote", claimed.getTotalAmountReserved());
        }
        if (claimed.getSymbolAmountReserved() != null && claimed.getSymbol() != null) {
            releaseFloored(claimed.getCredentialId(), "reservedSymbolExposure." + claimed.getSymbol(), claimed.getSymbolAmountReserved());
        }
        if (claimed.getGroupAmountsReserved() != null) {
            for (var entry : claimed.getGroupAmountsReserved().entrySet()) {
                releaseFloored(claimed.getCredentialId(), "reservedGroupExposure." + entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Review finding, same context as release(String)'s own updated comment: decrements the
     * given field by exactly `amount` when the field's own current value is genuinely at least
     * that much (the common, correct case) -- and when it isn't (a stale ACTIVE record being
     * released after reconciliation already reset the counter out from under it), sets the
     * field to exactly zero instead of letting it go negative. Never a no-op: either the
     * conditional decrement succeeds, or the explicit clamp-to-zero does.
     */
    private void releaseFloored(String credentialId, String field, BigDecimal amount) {
        // Same dynamic-map-key Decimal128 fix as reserveTransactionally's own symbol/group cap
        // queries (full context there) -- this is called for "reservedTotalExposureQuote" (a
        // literal, annotated field, where this was never broken) AND for
        // "reservedSymbolExposure.<symbol>" / "reservedGroupExposure.<group>" (dynamic map keys,
        // where it was) -- converting unconditionally here is correct and safe for both.
        var decremented = mongoTemplate.findAndModify(
            new Query(where("credentialId").is(credentialId).and(field).gte(toDecimal128(amount))),
            new Update().inc(field, toDecimal128(amount.negate())),
            ExposureReservation.class);
        if (decremented == null) {
            mongoTemplate.updateFirst(
                new Query(where("credentialId").is(credentialId)),
                new Update().set(field, toDecimal128(BigDecimal.ZERO)),
                ExposureReservation.class);
        }
    }

    /**
     * Review finding, same context as release(String)'s own javadoc: the narrower, amount-based
     * release, kept ONLY for callers that genuinely have no reservationId to work with — self-
     * healing reconciliation paths that compute a release amount from actual open positions
     * directly, not from a specific reservation record. New call sites should prefer
     * release(String reservationId) above; this remains for that narrower case, not as the
     * general-purpose release path it used to be.
     */
    public void release(String credentialId, String symbol, BigDecimal orderQuoteValue) {
        if (orderQuoteValue == null || orderQuoteValue.signum() <= 0) return;
        // Same dynamic-map-key Decimal128 fix as reserveTransactionally's own symbol/group cap
        // queries (full context there) -- the total-field comparison below was never broken
        // (it's a literal, annotated field), but every symbol/group comparison in this method was.
        mongoTemplate.updateFirst(
            new Query(where("credentialId").is(credentialId).and("reservedTotalExposureQuote").gte(toDecimal128(orderQuoteValue))),
            new Update().inc("reservedTotalExposureQuote", toDecimal128(orderQuoteValue.negate())),
            ExposureReservation.class);
        if (symbol != null) {
            String symbolField = "reservedSymbolExposure." + symbol;
            mongoTemplate.updateFirst(
                new Query(where("credentialId").is(credentialId).and(symbolField).gte(toDecimal128(orderQuoteValue))),
                new Update().inc(symbolField, toDecimal128(orderQuoteValue.negate())),
                ExposureReservation.class);
        }
        if (symbol != null) {
            try {
                riskProfileRepo.findByCredentialId(credentialId).ifPresent(profile -> {
                    if (profile.getCorrelationGroups() == null) return;
                    for (var entry : profile.getCorrelationGroups().entrySet()) {
                        if (entry.getValue() != null && entry.getValue().contains(symbol)) {
                            String groupField = "reservedGroupExposure." + entry.getKey();
                            mongoTemplate.updateFirst(
                                new Query(where("credentialId").is(credentialId).and(groupField).gte(toDecimal128(orderQuoteValue))),
                                new Update().inc(groupField, toDecimal128(orderQuoteValue.negate())),
                                ExposureReservation.class);
                        }
                    }
                });
            } catch (Exception e) {
                // Non-fatal: total/symbol release above already succeeded and is what matters
                // most — a failure here just means a group reservation stays slightly
                // over-reserved until the next reconcile() self-heals it, not an unsafe state.
            }
        }
    }

    /** Self-heals against the real, actual exposure computed from OPEN positions — same
     *  reasoning and grace-window as PositionSlotReservationService.reconcile(). */
    public void reconcile(String credentialId, BigDecimal actualTotalExposure, java.util.Map<String, BigDecimal> actualSymbolExposure) {
        reconcile(credentialId, actualTotalExposure, actualSymbolExposure, null);
    }

    /**
     * Review finding ("Risk" — "atomic correlation reservations"): the overload above, extended
     * with an optional actualGroupExposure — see this class's own reserve()/release() javadoc
     * for the full context. Null or empty means no correlation groups configured, in which case
     * this behaves identically to the overload above.
     */
    public void reconcile(String credentialId, BigDecimal actualTotalExposure, java.util.Map<String, BigDecimal> actualSymbolExposure,
                           java.util.Map<String, BigDecimal> actualGroupExposure) {
        ensureDocumentExists(credentialId, mongoTemplate);
        // Review finding ("Exposure reservation creation is still not atomic with the exposure
        // counter" -- external review, twenty-eighth pass, P0, full context in reserve()'s own
        // updated javadoc): the actual recovery for a PENDING record left behind by a crash
        // between the record insert and the final ACTIVE flip. A PENDING record older than the
        // same grace window already used for the aggregate counter below is genuinely
        // abandoned -- the reserve() call that created it either completed (flipped to ACTIVE
        // long ago) or crashed; deleting it here is safe regardless of which counters it may or
        // may not have actually claimed, since the aggregate-counter reconcile below
        // unconditionally overwrites those counters from the real, actual open-position/
        // reservation state anyway.
        var staleCutoff = Instant.now().minus(PENDING_CLEANUP_WINDOW);
        for (var pending : reservationRecordRepo.findByCredentialIdAndStatusAndCreatedAtBefore(credentialId, "PENDING", staleCutoff)) {
            // Review finding ("v183 still has a dangerous 'PENDING reservation cleanup' window"
            // -- external review, thirty-fifth/thirty-seventh passes, P0, the review's own
            // explicit required fix: "Never simply delete a stale PENDING reservation without
            // first proving that no exchange execution can be associated with it"): the actual
            // proof, when this record has an executionId to check -- its own linked
            // ExecutionContext's own real, durable status. STARTED/RISK_APPROVED means this
            // reservation attempt itself never even got past risk checks -- genuinely safe to
            // delete, nothing downstream could possibly reference it. Anything from CLAIMED
            // onward (an atomic execution-authorization claim was won, meaning the code was
            // about to call, or had already called, adapter.placeOrder()) means a real exchange
            // order MAY exist that this application has simply lost track of recording --
            // exactly the review's own named danger scenario. Deleting this record in that case
            // would erase the one clue connecting a possibly-real position to its own reserved
            // exposure; escalating instead leaves both the record and the trail intact for a
            // human to actually investigate.
            boolean safeToDelete = true;
            if (pending.getExecutionId() != null) {
                var context = executionContextRepo.findById(pending.getExecutionId()).orElse(null);
                if (context != null) {
                    String status = context.getStatus();
                    boolean mayHaveReachedExchange = status != null && !status.equals("STARTED") && !status.equals("RISK_APPROVED")
                        && !status.startsWith("REJECTED_") && !status.equals("SLOTS_RESERVED") && !status.equals("EXPOSURE_RESERVED");
                    if (mayHaveReachedExchange) {
                        safeToDelete = false;
                        log.error("Refusing to delete stale PENDING exposure reservation {} for credential {} -- its own linked "
                            + "execution {} shows real progress ({}) that may have reached the exchange. Escalating for manual "
                            + "investigation instead of silently deleting a record that could be the only trace of a real position.",
                            pending.getId(), credentialId, pending.getExecutionId(), status);
                        incidentService.raiseCritical(null, credentialId, null, null, pending.getSymbol(),
                            "STALE_PENDING_RESERVATION_POSSIBLE_EXCHANGE_EXECUTION",
                            "A PENDING exposure reservation (" + pending.getId() + ", credential " + credentialId + ", symbol "
                                + pending.getSymbol() + ") has been stale past the cleanup window, but its own linked execution ("
                                + pending.getExecutionId() + ") shows real progress (" + status + ") that may have reached the "
                                + "exchange. NOT deleted -- deleting it would erase the only durable link between this reserved "
                                + "exposure and a possibly-real position. Manual investigation required: check this execution's own "
                                + "position/order state directly against the exchange.");
                    }
                }
                // context == null (the ExecutionContext record itself is missing, e.g. its own
                // insert failed): falls through to the prior, time-based-only deletion below --
                // there's no further evidence available either way, and this record is already
                // well past the same grace window the aggregate counter reconcile below trusts.
            }
            if (safeToDelete) {
                log.warn("Deleting stale PENDING exposure reservation record {} for credential {} (created {}, older than the {}-second "
                    + "grace window with no resolution) -- the reserve() call that created it appears to have crashed before completing.",
                    pending.getId(), credentialId, pending.getCreatedAt(), PENDING_CLEANUP_WINDOW.toSeconds());
                deletePendingRecord(pending.getId());
            }
        }

        // Review finding ("The lastReservedAt grace-window design is still time-based safety" --
        // external review, twenty-eighth pass, P1, confirmed real by direct inspection before
        // this fix: the ONLY signal for "is a reservation genuinely still in flight" was a fixed
        // 120-second assumption, dangerous for a real exchange system with real latency
        // variance): a real, durable signal alongside the time-based one -- any PENDING record
        // still younger than the grace window (the stale ones above were just cleaned up) means
        // a reserve() call for this exact credential is provably in progress RIGHT NOW, not
        // guessed from elapsed time. Doesn't replace the time-based check (a reservation that
        // already flipped to ACTIVE has no PENDING record left to find, and still needs the
        // grace window's own protection for the position-persistence race that follows it) --
        // adds to it.
        boolean pendingReservationInFlight = !reservationRecordRepo
            .findByCredentialIdAndStatus(credentialId, "PENDING")
            .isEmpty();
        if (pendingReservationInFlight) {
            return; // a reserve() call for this credential is provably still in progress right now
        }

        ExposureReservation existing = mongoTemplate.findOne(
            new Query(where("credentialId").is(credentialId)), ExposureReservation.class);
        if (existing != null && existing.getLastReservedAt() != null
                && existing.getLastReservedAt().isAfter(Instant.now().minus(GRACE_WINDOW))) {
            return; // a reservation happened recently enough that it may still be in flight
        }

        // Review finding ("reservation reconciliation is still fundamentally cache-based" --
        // external review, twenty-ninth pass, P1, the review's own explicit "biggest remaining
        // reservation concern," full context in ExposureReservationRecord.positionId's own
        // field javadoc): the actual fix. actualTotalExposure/actualSymbolExposure above are
        // computed purely from real OPEN positions -- an ACTIVE reservation record whose own
        // position genuinely hasn't been created and saved yet (the review's own named failure
        // window) would be entirely invisible to that computation. Found here directly, by
        // querying for exactly that state (ACTIVE, positionId still null), rather than inferred
        // from timing -- these amounts are ADDED to the position-derived totals, since they
        // represent real exposure this credential has genuinely committed to that the position
        // query simply cannot see yet. A stale ACTIVE record (one that never got linked because
        // the whole execution actually failed, not because it's still in flight) is NOT a
        // concern here -- the PENDING-cleanup and time-based checks above already run first,
        // and any ACTIVE record old enough to be genuinely stale rather than in-flight will
        // have its own position eventually either appear (releasing the double-count risk this
        // addition might otherwise create) or the record itself ages past what any real
        // execution should ever take, at which point it's a genuine operational anomaly worth
        // surfacing, not silently smoothing over by excluding it here.
        java.math.BigDecimal unlinkedActiveTotal = java.math.BigDecimal.ZERO;
        var unlinkedActiveGroupTotals = new java.util.HashMap<String, java.math.BigDecimal>();
        for (var unlinked : reservationRecordRepo.findByCredentialIdAndStatusAndPositionIdIsNull(credentialId, "ACTIVE")) {
            if (unlinked.getTotalAmountReserved() != null) {
                unlinkedActiveTotal = unlinkedActiveTotal.add(unlinked.getTotalAmountReserved());
            }
            if (unlinked.getGroupAmountsReserved() != null) {
                for (var groupEntry : unlinked.getGroupAmountsReserved().entrySet()) {
                    unlinkedActiveGroupTotals.merge(groupEntry.getKey(), groupEntry.getValue(), java.math.BigDecimal::add);
                }
            }
        }
        java.math.BigDecimal correctedTotal = actualTotalExposure.add(unlinkedActiveTotal);
        if (unlinkedActiveTotal.signum() > 0) {
            log.info("reconcile() for credential {}: including {} of exposure from {} ACTIVE reservation(s) not yet linked to a "
                + "position -- these represent real, committed exposure the position-derived total alone cannot see yet.",
                credentialId, unlinkedActiveTotal, unlinkedActiveGroupTotals.size());
        }

        Update update = new Update().set("reservedTotalExposureQuote", correctedTotal).set("reservedSymbolExposure", actualSymbolExposure);
        if (actualGroupExposure != null) {
            var correctedGroupExposure = new java.util.HashMap<>(actualGroupExposure);
            unlinkedActiveGroupTotals.forEach((group, amount) -> correctedGroupExposure.merge(group, amount, java.math.BigDecimal::add));
            update = update.set("reservedGroupExposure", correctedGroupExposure);
        }
        mongoTemplate.updateFirst(
            new Query(where("credentialId").is(credentialId)),
            update,
            ExposureReservation.class);
    }

    /**
     * Review finding (self-diagnosed, live production bug, full context in
     * PositionSlotReservationService.ensureDocumentExists's own identical fix): the same real
     * bug, and the same fix, applied to exposure reservations. Takes the MongoOperations to use
     * as a parameter so reserveTransactionally can pass its own session-bound instance.
     */
    /**
     * Review finding (self-diagnosed, live production bug, full context in
     * reserveTransactionally's own updated catch-clause comment above): walks the FULL cause
     * chain of the given exception -- not just its own top level -- looking for the real,
     * standalone-Mongo-instance signature (error code 20, or the exact "Transaction numbers are
     * only allowed on a replica set member or mongos" message Mongo itself returns), since
     * Spring Data's own exception translation can wrap the raw driver exception carrying that
     * signature one or more levels down inside org.springframework.dao.DataAccessException.
     */
    /**
     * Review finding (self-diagnosed, live production bug, confirmed real via direct production
     * log evidence: a genuinely fresh-computed BigDecimal -- qty.multiply(avgEntryPrice), never
     * read back from storage -- still failed MongoDB's own $inc with "Cannot increment with
     * non-numeric argument" even AFTER spring.data.mongodb.big-decimal-representation=decimal128
     * was added and the database was dropped and recreated from scratch. That ruled out "stale
     * string-typed data already in the database" as the cause -- the real gap is narrower and
     * more specific: that Spring Boot 3.2+ property configures how BigDecimal fields are
     * serialized when Spring Data maps a full ENTITY (an object saved or read via
     * MongoTemplate/MongoRepository's own document-mapping pipeline) -- it does not reliably
     * apply to the raw value passed into Update.inc(String, Object), which is a lower-level
     * query-builder API that can bypass that same entity-mapping conversion path in this Spring
     * Data MongoDB version. The actual, direct fix that sidesteps this ambiguity entirely rather
     * than depending on it: convert to org.bson.types.Decimal128 -- MongoDB's own native BSON
     * decimal type -- explicitly, right here, before the value is ever handed to .inc(...). A
     * Decimal128 value can never be misinterpreted as a string by the driver, regardless of
     * which Spring Data conversion path does or doesn't apply to it.
     */
    private org.bson.types.Decimal128 toDecimal128(BigDecimal value) {
        return value == null ? null : new org.bson.types.Decimal128(value);
    }

    private boolean isStandaloneMongoTransactionError(Throwable e) {
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 10) { // bounded -- a real cause chain is never this deep; just a defensive stop against a malformed cyclic chain
            if (current instanceof com.mongodb.MongoException mongoEx && mongoEx.getCode() == 20) return true;
            if (current.getMessage() != null && current.getMessage().contains("Transaction numbers are only allowed")) return true;
            current = current.getCause();
            depth++;
        }
        return false;
    }

    /**
     * CI-review fix ("Multi-plan combined exposure reservation" -- external review, fifth pass,
     * failure 2, full context in reserveTransactionally's own updated javadoc): the same helper
     * as PositionSlotReservationService's own identical fix -- recognizes a genuine, expected,
     * RETRYABLE write-conflict under real concurrent transactions against the same document,
     * distinct from isStandaloneMongoTransactionError above (which means transactions aren't
     * supported AT ALL, never retryable). Checks the driver's own "TransientTransactionError"
     * label first, then falls back to the raw WriteConflict code (112) directly.
     */
    private boolean isTransientTransactionError(Throwable e) {
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof com.mongodb.MongoException mongoEx) {
                if (mongoEx.hasErrorLabel("TransientTransactionError")) return true;
                if (mongoEx.getCode() == 112) return true; // WriteConflict
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private void ensureDocumentExists(String credentialId, org.springframework.data.mongodb.core.MongoOperations ops) {
        Query query = new Query(where("credentialId").is(credentialId));
        if (ops.exists(query, ExposureReservation.class)) return;
        try {
            ExposureReservation doc = new ExposureReservation();
            doc.setCredentialId(credentialId);
            ops.insert(doc);
        } catch (DuplicateKeyException e) {
            // Another instance created it first between our exists() check and this insert — fine, it exists now.
        }
    }

    private void ensureSymbolFieldExists(String credentialId, String symbol, org.springframework.data.mongodb.core.MongoOperations ops) {
        String symbolField = "reservedSymbolExposure." + symbol;
        Query notYetPresent = new Query(where("credentialId").is(credentialId).and(symbolField).exists(false));
        ops.updateFirst(notYetPresent, new Update().set(symbolField, toDecimal128(BigDecimal.ZERO)), ExposureReservation.class);
    }

    private void ensureGroupFieldExists(String credentialId, String groupName, org.springframework.data.mongodb.core.MongoOperations ops) {
        String groupField = "reservedGroupExposure." + groupName;
        Query notYetPresent = new Query(where("credentialId").is(credentialId).and(groupField).exists(false));
        ops.updateFirst(notYetPresent, new Update().set(groupField, toDecimal128(BigDecimal.ZERO)), ExposureReservation.class);
    }
}
