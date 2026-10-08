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
 * Enforces atomic exposure caps (total dollar amount, and optionally a per-symbol and
 * per-correlation-group breakdown), the same shape as {@code PositionSlotReservationService}
 * generalizes to a dollar amount, with the same MongoDB single-document-atomicity guarantee.
 *
 * <p>{@code reserve()} is multiple sequential atomic steps, not one: (1) atomically reserve into
 * the total exposure field, conditioned on staying under the total cap; (2) if that succeeds,
 * atomically reserve into the symbol exposure field, conditioned on staying under the symbol cap,
 * rolling back step 1 if this fails; and similarly for any correlation groups. This isn't a
 * single indivisible transaction across every field, but it doesn't need to be: each field's own
 * cap is enforced correctly and atomically by its own step regardless of what the other fields
 * are doing concurrently. The only imperfection is a brief window where a rolled-back total
 * reservation could make a concurrent request's total-cap check slightly more conservative than
 * strictly necessary -- a safe failure mode (a false rejection), never an unsafe one (a false
 * approval of any cap).
 *
 * <p>Every successful reservation creates its own durable {@code ExposureReservationRecord} (see
 * that class's javadoc) capturing exactly what was reserved. {@link #release(String)} reads the
 * amounts back from that record rather than trusting a caller-recomputed value, and atomically
 * claims the record before decrementing anything, making a double-release a genuine no-op instead
 * of a double-decrement. The intra-call rollback inside {@code reserve()} itself (a later step
 * failing, undoing an earlier step within the same call) remains a direct counter decrement,
 * which is safe on its own terms: it undoes this same execution's own just-made increment, by the
 * identical amount, before any reservation record for it has ever been created or handed to a
 * caller.
 */
@Service
@RequiredArgsConstructor
public class ExposureReservationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExposureReservationService.class);
    private static final Duration GRACE_WINDOW = Duration.ofSeconds(120);
    /**
     * Checks whether a correlation-group name is safe to use as a literal MongoDB field path
     * segment ({@code "reservedGroupExposure." + groupName}), since it contains no {@code '.'} or
     * {@code '$'} and isn't blank. {@code RiskProfileService.upsert} validates group names at
     * write time, so this is defense in depth for profile documents written before that
     * validation existed, or by a direct database write bypassing the API. An unsafe name is
     * skipped rather than rejected outright: this runs on the hot path of order placement, and a
     * malformed group name on an old document should degrade to "this one group isn't enforced
     * this call" rather than block a legitimate order or crash the reservation entirely.
     */
    private static boolean isSafeGroupName(String groupName) {
        return groupName != null && !groupName.isBlank() && !groupName.contains(".") && !groupName.contains("$");
    }
    /**
     * How long a {@code PENDING} reservation record may remain unresolved before
     * {@code reconcile()}'s stale-PENDING cleanup treats it as abandoned and deletes it. This is
     * set well above how long any real {@code reserve()} call should ever take (normally
     * milliseconds), to keep the window small in which a slow but still in-progress {@code
     * reserve()} call (an extreme GC pause, a stuck thread) could have its own record cleaned up
     * from under it before it finishes claiming counters. A full Mongo transaction around the
     * whole {@code reserve()} sequence would close this gap entirely, but {@code reserve()} is
     * also called for TESTNET/PAPER credentials where transaction support (a replica set) isn't
     * guaranteed, so this threshold is a pragmatic, narrower mitigation instead.
     */
    private static final Duration PENDING_CLEANUP_WINDOW = Duration.ofMinutes(10);

    private final MongoTemplate mongoTemplate;
    // Used to raise a critical incident, loudly rather than silently, whenever counters have
    // already been claimed but the durable record proving why fails its final PENDING-to-ACTIVE
    // transition (see reserve()).
    private final IncidentService incidentService;
    // Used by reconcile()'s stale-PENDING cleanup to verify a PENDING record is actually
    // abandoned before deleting it.
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
     * Overload that also enforces an optional third atomic step for correlation-group caps.
     * {@code correlationGroups}/{@code correlationGroupCaps} may be null or empty (no correlation
     * groups configured for this profile), in which case this behaves identically to the
     * two-step overload above. A symbol can belong to more than one group; each matching group is
     * reserved as its own atomic step, and if any step (total, symbol, or any group) fails, every
     * step that already succeeded is rolled back -- the whole reservation is all-or-nothing.
     *
     * <p>{@code executionId}, when the caller has one ({@code AutoTradeService} always does, since
     * its {@code ExecutionContext} is created before any reservation is attempted), is stored on
     * the PENDING record before any counter is touched, giving {@code reconcile()}'s stale-PENDING
     * cleanup a durable thing to check before deciding it's safe to delete. It is genuinely
     * nullable: a caller with no executionId available (e.g. {@code PositionMonitorService}'s
     * late-fill-discovery recovery path, which runs after the original evaluation and its
     * {@code ExecutionContext} are both long finished) passes null, and {@code reconcile()} falls
     * back to a time-based-only safety check for those records.
     */
    public ExposureReserveResult reserve(String credentialId, String symbol, BigDecimal orderQuoteValue,
                                          BigDecimal maxTotal, BigDecimal maxSymbol,
                                          java.util.Map<String, java.util.Set<String>> correlationGroups,
                                          java.util.Map<String, BigDecimal> correlationGroupCaps,
                                          String executionId) {
        return reserve(credentialId, symbol, orderQuoteValue, maxTotal, maxSymbol, correlationGroups, correlationGroupCaps, executionId, false);
    }

    /**
     * Overload that also distinguishes LIVE-mode reservations from TESTNET/PAPER. When
     * {@code live} is true, this is a genuine LIVE-mode autonomous trade, and a runtime failure
     * to acquire a MongoDB transaction session (distinct from a deployment that doesn't support
     * transactions at all) must not silently fall back to the non-transactional path: it rejects
     * the reservation and halts autonomous execution with a critical incident instead, since LIVE
     * execution requires the atomic reservation lifecycle guarantee a transaction provides.
     * TESTNET/PAPER ({@code live=false}) keep the existing sequential-fallback behavior, since
     * those modes don't require a replica set.
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
     * Reserves exposure inside a real MongoDB transaction when the deployment supports one
     * (inserting the reservation PENDING, incrementing every relevant counter, and setting it
     * ACTIVE as one atomic unit), following the same pattern
     * {@code RiskProfileService.claimExecutionAtomicWithPlan} uses for LIVE execution
     * authorization. On the specific, recognizable "this deployment doesn't support transactions"
     * error (a standalone instance, not a replica set), this falls back to
     * {@link #reserveSequentially}, which has its own independent rollback-on-failure and
     * loud-not-silent-activation-failure safety nets. Any other MongoDB error is never silently
     * swallowed into that fallback, since it wouldn't actually address a different failure.
     */
    /**
     * Bounded retry budget for a transaction that hits a MongoDB WriteConflict, matching
     * {@code PositionSlotReservationService}'s identical constant: a WriteConflict under real
     * concurrent transactions against the same document is expected and retryable, not an
     * infrastructure failure.
     */
    private static final int MAX_TRANSACTION_RETRIES = 10;

    /**
     * Short, randomized (jittered) delay before retrying a transaction that hit a WriteConflict,
     * growing slightly with the attempt number but capped low -- this runs on the hot
     * order-placement path, not a background job, so even the worst case (attempt 10) adds at
     * most tens of milliseconds. The randomization matters: when multiple threads collide and all
     * back off, a fixed identical delay would just make them retry in lockstep and collide again
     * on the very next attempt, rather than actually reducing contention.
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
                        + "(contains '.' or '$', or blank); the profile document needs correcting.", groupName, credentialId);
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
            // Retries the whole transaction body on the same session (a ClientSession stays
            // valid across a startTransaction()/abortTransaction() cycle, MongoDB's documented
            // retry pattern) when a WriteConflict occurs. This document sees several writes per
            // transaction (ensureDocumentExists, ensureSymbolFieldExists, optional group-field
            // setup, insert, then up to one findAndModify per total/symbol/group cap), so
            // concurrent callers against the same document have real exposure to a WriteConflict
            // (MongoDB error code 112, a TransientTransactionError); retrying lets every caller
            // that can legally fit under the cap actually get the chance to, instead of losing
            // its only attempt to contention.
            for (int attempt = 1; ; attempt++) {
                try {
                    session.startTransaction();
                    var sessionTemplate = mongoTemplate.withSession(session);
                    // All three setup calls run inside the transaction, on the session-bound
                    // template, so they're part of the same transactional snapshot as the counter
                    // claims that follow -- otherwise a fresh credential's setup could be
                    // invisible to the claims in the same logical operation, wrongly rejecting a
                    // first-ever reservation with zero real competition.
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
                            // Logs the actual document state this rejection was based on (not
                            // just "rejected"), so a cap rejection is distinguishable from any
                            // other failure mode when diagnosing from logs alone.
                            log.warn("reserve() REJECT (total cap) for credential {} symbol {} attempt {}: requested {}, cap {} "
                                + "(would need existing reservedTotalExposureQuote <= {} for this to have been allowed).",
                                credentialId, symbol, attempt, orderQuoteValue, maxTotal, maxTotal.subtract(orderQuoteValue));
                            session.abortTransaction();
                            return ExposureReserveResult.reject("Would exceed total exposure cap of " + maxTotal);
                        }
                    }

                    if (symbolCapConfigured) {
                        String symbolField = "reservedSymbolExposure." + symbol;
                        // reservedTotalExposureQuote is a literal, @Field(targetType=DECIMAL128)
                        // -annotated property, so Spring Data's QueryMapper converts a raw
                        // BigDecimal criteria value to Decimal128 automatically. reservedSymbolExposure.<symbol>
                        // (and reservedGroupExposure.<group>) is a dynamic key inside a
                        // Map<String,BigDecimal>, so there is no per-key target type Spring can
                        // infer ahead of time, and it falls back to comparing as a string unless
                        // explicitly converted -- a MongoDB $lte between two different BSON types
                        // never matches regardless of the actual numeric values. The comparison
                        // value is wrapped in toDecimal128(...) explicitly here, the same way the
                        // Update side already converts, so the stored Decimal128 and the query
                        // value are the same BSON type.
                        Query symbolQuery = new Query(where("credentialId").is(credentialId)
                            .and(symbolField).lte(toDecimal128(maxSymbol.subtract(orderQuoteValue))));
                        Update symbolInc = new Update().inc(symbolField, toDecimal128(orderQuoteValue)).set("lastReservedAt", Instant.now());
                        var symbolResult = sessionTemplate.findAndModify(
                            symbolQuery, symbolInc, FindAndModifyOptions.options().returnNew(true), ExposureReservation.class);
                        if (symbolResult == null) {
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
                            session.abortTransaction(); // undoes total, symbol, and every group claim already made in this same transaction
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
                        // A short, randomized sleep before each retry -- MongoDB's documented
                        // guidance for WriteConflict retries -- avoids racing threads immediately
                        // re-colliding in lockstep on the very next attempt, without meaningfully
                        // slowing down the common, uncontended case where most reserve() calls
                        // never retry at all.
                        try {
                            Thread.sleep(sleepMillisBeforeRetry(attempt));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        continue;
                    }
                    // Logged unconditionally, before deciding what to do next, so that either the
                    // standalone-deployment fallback below or a genuinely unhandled failure always
                    // leaves a diagnosable trace, even for a caller that submits this call to an
                    // ExecutorService without checking the returned Future.
                    log.warn("reserve() for credential {} symbol {} giving up after attempt {} ({}): {}",
                        credentialId, symbol, attempt, e.getClass().getName(), e.getMessage(), e);
                    // Catches RuntimeException broadly, not just com.mongodb.MongoException:
                    // sessionTemplate.insert/findAndModify/updateFirst go through Spring Data's
                    // exception translation, which wraps the raw driver exception (e.g.
                    // MongoCommandException, error code 20 on a standalone instance) into
                    // org.springframework.dao.DataAccessException -- a different type hierarchy a
                    // catch on com.mongodb.MongoException would never match. isStandaloneMongoTransactionError
                    // walks the full cause chain, not just the top-level exception, since the raw
                    // standalone-instance signature can be buried inside Spring's wrapper.
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
     * Fail-closed action shared by both LIVE-mode transaction fallback points above: rejects this
     * reservation and halts autonomous trading for the credential, rather than merely rejecting
     * one reservation. Setting {@code tradingHalted} (not just rejecting this attempt) is
     * deliberate: a transaction-capable deployment that has stopped being able to acquire
     * sessions or run transactions is a genuine infrastructure problem that will affect every
     * subsequent LIVE reservation attempt identically, so halting stops the account from
     * repeatedly hitting the same failure on every future signal until an operator has looked at
     * it, the same reasoning this codebase's other {@code tradingHalted} call sites apply.
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
                        + "(contains '.' or '$', or blank); the profile document needs correcting.", groupName, credentialId);
                    continue;
                }
                if (entry.getValue() == null || !entry.getValue().contains(symbol)) continue;
                BigDecimal groupCap = correlationGroupCaps.get(groupName);
                if (groupCap == null || groupCap.signum() <= 0) continue;
                groupsToReserve.add(groupName);
            }
        }

        // The record is created first, as PENDING, with the exact amounts this call intends to
        // claim, before any counter is touched. If this insert fails, nothing has been claimed
        // yet, so this rejects immediately with no rollback needed. Only once every counter claim
        // below has actually succeeded is the record marked ACTIVE; if this application crashes
        // at any point after this insert, a PENDING record referencing exactly this
        // credential/symbol/amounts durably exists for reconciliation to find, verify against the
        // real counters, and resolve -- never a counter increment with nothing to explain it.
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
            // Same dynamic-map-key Decimal128 conversion as reserveTransactionally's identical
            // query -- see that method's comment for why the comparison value must be converted
            // explicitly for a dynamic map key.
            Query symbolQuery = new Query(where("credentialId").is(credentialId)
                .and(symbolField).lte(toDecimal128(maxSymbol.subtract(orderQuoteValue))));
            Update symbolInc = new Update().inc(symbolField, toDecimal128(orderQuoteValue)).set("lastReservedAt", Instant.now());
            ExposureReservation symbolResult = mongoTemplate.findAndModify(
                symbolQuery, symbolInc, FindAndModifyOptions.options().returnNew(true), ExposureReservation.class);
            if (symbolResult == null) {
                // Rolls back the total reservation from step 1, which was provisional on this
                // step also succeeding. Safe as a bare decrement: this undoes this same call's
                // own just-made increment, by the identical orderQuoteValue, before the record
                // has ever been marked ACTIVE or handed to a caller -- see the class javadoc.
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
            // Same dynamic-map-key Decimal128 conversion as above.
            Query groupQuery = new Query(where("credentialId").is(credentialId)
                .and(groupField).lte(toDecimal128(groupCap.subtract(orderQuoteValue))));
            Update groupInc = new Update().inc(groupField, toDecimal128(orderQuoteValue)).set("lastReservedAt", Instant.now());
            ExposureReservation groupResult = mongoTemplate.findAndModify(
                groupQuery, groupInc, FindAndModifyOptions.options().returnNew(true), ExposureReservation.class);
            if (groupResult == null) {
                // Rolls back total, symbol, and every group reservation that already succeeded
                // in this same call -- all-or-nothing. Same "undo this call's own just-made
                // increment" safety as above.
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
        // The return value of this update is checked rather than discarded: if it fails to match
        // (e.g. a concurrent reconcile pass's stale-PENDING cleanup deleted the record out from
        // under this call), the aggregate counters above have already been durably incremented,
        // but the record that could explain why no longer exists as ACTIVE. A full Mongo
        // transaction wrapping this whole method would close this gap entirely, but that's a
        // materially larger change given this method also runs for TESTNET/PAPER credentials
        // with no guaranteed replica-set support (see PENDING_CLEANUP_WINDOW above); instead this
        // narrow gap is made loud rather than silent: a failed transition raises a critical
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
     * Best-effort cleanup for a reservation that never actually claimed any counter (the cap
     * check rejected it, or a later step in the same call did). Deliberately non-fatal -- a
     * PENDING record that fails to delete here is still correctly handled by reconciliation (see
     * the PENDING recovery path in {@code reconcile()}), it just takes longer to disappear.
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
     * The safe way to release a reservation: reads the exact amounts back from the reservation's
     * own durable record rather than trusting a caller-recomputed value, and atomically claims
     * the record ({@code ACTIVE -> RELEASED}) before decrementing anything, so a genuine
     * double-release (a retry, two code paths racing to release the same logical reservation) is
     * a real no-op, not a double-decrement. This is the method real callers should use; see
     * {@link #release(String, String, BigDecimal)} below for the narrower cases where a
     * reservationId genuinely isn't available.
     */
    /**
     * Records which position a reservation ultimately created, once that position is actually
     * created and saved. Best-effort like every other write in this class, since a failure here
     * must never block the real position-creation flow it's merely recording metadata about.
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
        // "ACTIVE" and gets null back, making a double-release a safe no-op.
        var claimed = mongoTemplate.findAndModify(
            new Query(where("id").is(reservationId).and("status").is("ACTIVE")),
            new Update().set("status", "RELEASED").set("releasedAt", Instant.now()),
            ExposureReservationRecord.class);
        if (claimed == null) return; // already released, or never existed — safe either way

        // releaseFloored only decrements down to zero, never below it, since a bare decrement
        // could otherwise drive a counter negative -- e.g. if reconcile() already reset the
        // aggregate counter to its real value (such as 0) while this record stayed ACTIVE because
        // the position it belonged to crashed before ever being persisted. A negative reservation
        // counter is a genuine risk-cap failure: it would make the next real reservation's cap
        // check pass when it shouldn't.
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
     * Decrements the given field by exactly {@code amount} when the field's current value is at
     * least that much (the common, correct case), and when it isn't (a stale ACTIVE record being
     * released after reconciliation already reset the counter out from under it), sets the field
     * to exactly zero instead of letting it go negative. Never a no-op: either the conditional
     * decrement succeeds, or the explicit clamp-to-zero does.
     */
    private void releaseFloored(String credentialId, String field, BigDecimal amount) {
        // Same dynamic-map-key Decimal128 conversion as reserveTransactionally's symbol/group cap
        // queries -- this is called for "reservedTotalExposureQuote" (a literal, annotated field)
        // and for "reservedSymbolExposure.<symbol>" / "reservedGroupExposure.<group>" (dynamic
        // map keys); converting unconditionally here is correct and safe for both.
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
     * Narrower, amount-based release kept only for callers that genuinely have no reservationId
     * to work with -- self-healing reconciliation paths that compute a release amount from actual
     * open positions directly, not from a specific reservation record. New call sites should
     * prefer {@link #release(String)} above; this remains for that narrower case.
     */
    public void release(String credentialId, String symbol, BigDecimal orderQuoteValue) {
        if (orderQuoteValue == null || orderQuoteValue.signum() <= 0) return;
        // Same dynamic-map-key Decimal128 conversion as reserveTransactionally's symbol/group cap
        // queries -- the total-field comparison below is a literal, annotated field, but every
        // symbol/group comparison here needs the explicit conversion.
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
     * Overload that also accepts an optional {@code actualGroupExposure}. Null or empty means no
     * correlation groups are configured, in which case this behaves identically to the overload
     * above.
     */
    public void reconcile(String credentialId, BigDecimal actualTotalExposure, java.util.Map<String, BigDecimal> actualSymbolExposure,
                           java.util.Map<String, BigDecimal> actualGroupExposure) {
        ensureDocumentExists(credentialId, mongoTemplate);
        // Recovers a PENDING record left behind by a crash between the record insert and the
        // final ACTIVE flip. A PENDING record older than the same grace window used for the
        // aggregate counter below is genuinely abandoned -- the reserve() call that created it
        // either completed (flipped to ACTIVE long ago) or crashed; deleting it here is safe
        // regardless of which counters it may or may not have actually claimed, since the
        // aggregate-counter reconcile below unconditionally overwrites those counters from the
        // real, actual open-position/reservation state anyway.
        var staleCutoff = Instant.now().minus(PENDING_CLEANUP_WINDOW);
        for (var pending : reservationRecordRepo.findByCredentialIdAndStatusAndCreatedAtBefore(credentialId, "PENDING", staleCutoff)) {
            // Never deletes a stale PENDING reservation without first proving that no exchange
            // execution can be associated with it. When this record has an executionId to check,
            // its linked ExecutionContext's real, durable status is the proof: STARTED/
            // RISK_APPROVED means this reservation attempt never even got past risk checks, so
            // it's genuinely safe to delete. Anything from CLAIMED onward (an atomic
            // execution-authorization claim was won, meaning the code was about to call, or had
            // already called, adapter.placeOrder()) means a real exchange order may exist that
            // this application has simply lost track of recording. Deleting the record in that
            // case would erase the one clue connecting a possibly-real position to its own
            // reserved exposure, so this escalates instead, leaving both the record and the trail
            // intact for a human to investigate.
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
                // insert failed) falls through to the time-based-only deletion below -- there's
                // no further evidence available either way, and this record is already well past
                // the same grace window the aggregate counter reconcile below trusts.
            }
            if (safeToDelete) {
                log.warn("Deleting stale PENDING exposure reservation record {} for credential {} (created {}, older than the {}-second "
                    + "grace window with no resolution) -- the reserve() call that created it appears to have crashed before completing.",
                    pending.getId(), credentialId, pending.getCreatedAt(), PENDING_CLEANUP_WINDOW.toSeconds());
                deletePendingRecord(pending.getId());
            }
        }

        // A durable signal alongside the time-based grace-window check below: any PENDING record
        // still younger than the grace window (the stale ones above were just cleaned up) means
        // a reserve() call for this exact credential is provably in progress right now, rather
        // than inferred purely from elapsed time. This doesn't replace the time-based check below
        // -- a reservation that already flipped to ACTIVE has no PENDING record left to find, and
        // still needs the grace window's protection for the position-persistence race that
        // follows it -- it adds to it.
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

        // actualTotalExposure/actualSymbolExposure above are computed purely from real open
        // positions, so an ACTIVE reservation record whose own position genuinely hasn't been
        // created and saved yet would be entirely invisible to that computation. Such records are
        // found directly here, by querying for exactly that state (ACTIVE, positionId still
        // null), rather than inferred from timing, and their amounts are added to the
        // position-derived totals, since they represent real exposure this credential has
        // genuinely committed to that the position query simply cannot see yet. A stale ACTIVE
        // record (one that never got linked because the whole execution actually failed, not
        // because it's still in flight) is not a concern here, since the PENDING-cleanup and
        // time-based checks above already run first, and any ACTIVE record old enough to be
        // genuinely stale rather than in-flight will have its own position eventually either
        // appear, or the record itself ages past what any real execution should ever take, at
        // which point it's a genuine operational anomaly worth surfacing rather than silently
        // smoothing over by excluding it here.
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
     * Ensures an {@code ExposureReservation} document exists for this credential (same pattern
     * as {@code PositionSlotReservationService.ensureDocumentExists}). Takes the
     * {@code MongoOperations} to use as a parameter so {@code reserveTransactionally} can pass
     * its own session-bound instance.
     */
    /**
     * Walks the full cause chain of the given exception, not just its top level, looking for the
     * standalone-Mongo-instance signature (error code 20, or the exact "Transaction numbers are
     * only allowed on a replica set member or mongos" message Mongo returns), since Spring Data's
     * exception translation can wrap the raw driver exception carrying that signature one or more
     * levels down inside {@code org.springframework.dao.DataAccessException}.
     */
    /**
     * Converts to {@code org.bson.types.Decimal128}, MongoDB's native BSON decimal type, before a
     * value is handed to {@code Update.inc(...)}. This is necessary because Spring Data's
     * BigDecimal-as-Decimal128 configuration applies reliably when mapping a full entity through
     * {@code MongoTemplate}/{@code MongoRepository}'s document-mapping pipeline, but not to a raw
     * value passed into the lower-level {@code Update.inc(String, Object)} query-builder API,
     * which can bypass that same conversion path. Explicit conversion sidesteps that ambiguity
     * entirely: a Decimal128 value can never be misinterpreted as a string by the driver.
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
     * Recognizes a genuine, expected, retryable write conflict under real concurrent
     * transactions against the same document, distinct from {@link
     * #isStandaloneMongoTransactionError} above (which means transactions aren't supported at
     * all, never retryable). Checks the driver's "TransientTransactionError" label first, then
     * falls back to the raw WriteConflict code (112) directly.
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
