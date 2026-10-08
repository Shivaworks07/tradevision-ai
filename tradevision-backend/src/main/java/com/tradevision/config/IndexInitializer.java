package com.tradevision.config;

import com.tradevision.model.ApiMetric;
import com.tradevision.model.OtpRateLimit;
import com.tradevision.model.OtpRecord;
import com.tradevision.model.PositionSlotReservation;
import com.tradevision.model.RiskProfile;
import com.tradevision.model.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Explicitly creates the MongoDB indexes this application's correctness and query performance
 * depend on. @Indexed annotations on the model classes alone don't create anything in MongoDB;
 * this is what actually does. This matters most for the unique indexes: PositionSlotReservation's
 * entire correctness as a distributed atomic counter depends on there being exactly one document
 * per credentialId, and OtpRateLimit's concurrency-safety depends on its key index existing for
 * real — without the index, two documents for the same key could silently exist.
 *
 * Explicit index creation at startup, rather than the blanket spring.data.mongodb.auto-index-
 * creation=true property, is deliberate: that property affects every @Document collection,
 * including ones that may grow large, and index creation on a large existing collection can be
 * slow or locking. This only ensures the specific indexes this application actually depends on,
 * and logs plainly if any creation fails rather than assuming it silently worked.
 */
@Component
@RequiredArgsConstructor
public class IndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(IndexInitializer.class);

    private final MongoTemplate mongoTemplate;
    /**
     * Names of performance-only indexes (compound or TTL) that failed to be created. These
     * failures are deliberately non-fatal — they do not gate startupState.markCriticalIndexesResult
     * the way a safety-critical unique-index failure does, since queries still work (just
     * slower) and TTL-less collections still function (just don't auto-expire) — but tracking
     * them here makes the failure visible operational state instead of a log line that scrolls
     * away.
     */
    private final java.util.List<String> failedPerformanceIndexes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public java.util.List<String> getFailedPerformanceIndexes() {
        return java.util.List.copyOf(failedPerformanceIndexes);
    }

    /**
     * When this one-time startup index check ran. Lets the health indicator measure elapsed
     * time since the check, to decide whether a still-failed performance index has been
     * outstanding long enough to escalate past a short startup grace period.
     */
    private volatile java.time.Instant indexCheckCompletedAt;

    public java.time.Instant getIndexCheckCompletedAt() {
        return indexCheckCompletedAt;
    }
    /**
     * Readiness gate that this class reports critical-index success/failure to, so autonomous
     * trading cannot start until the safety-critical unique indexes have been confirmed.
     */
    private final com.tradevision.config.StartupState startupState;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureCriticalIndexes() {
        // Tracks the success/failure of every safety-critical unique index (TTL and compound
        // indexes below remain deliberately non-fatal/performance-only) and reports the combined
        // result to StartupState, which gates AutoTradeService.evaluateSignal on trading
        // readiness. See StartupState.markCriticalIndexesResult's javadoc for why this is
        // tracked as an independent flag rather than folded into the reconciliation-based phase
        // directly — both fire on the same ApplicationReadyEvent with no guaranteed ordering
        // between them.
        boolean allCriticalIndexesOk = true;
        allCriticalIndexesOk &= ensureUniqueIndex(PositionSlotReservation.class, "credentialId", false);
        // User.email/mobile are both optional, so these unique indexes must be sparse —
        // otherwise every document missing the field would collide on the same implicit null
        // value, and a second mobile-only signup would fail once a first mobile-only user
        // existed. This matches User's own @Indexed(unique=true, sparse=true) annotations.
        allCriticalIndexesOk &= ensureUniqueIndex(User.class, "email", true);
        allCriticalIndexesOk &= ensureUniqueIndex(User.class, "mobile", true);
        allCriticalIndexesOk &= ensureUniqueIndex(RiskProfile.class, "credentialId", false);
        // Without this unique index, two concurrent requests for the same credential could both
        // insert an ExposureReservation document (ensureDocumentExists's exists()-then-insert
        // race), leaving two exposure-tracking rows for one credential and letting real exposure
        // caps be bypassed.
        allCriticalIndexesOk &= ensureUniqueIndex(com.tradevision.model.ExposureReservation.class, "credentialId", false);
        allCriticalIndexesOk &= ensureUniqueIndex(OtpRateLimit.class, "key", false);
        // FillLedgerService's duplicate-fill handling depends on this index actually existing —
        // see FillRecord's own javadoc for how fillIdentity is derived.
        allCriticalIndexesOk &= ensureUniqueIndex(com.tradevision.model.FillRecord.class, "fillIdentity", false);
        // Enforces exchange order-idempotency: see Order.java for why clientOrderId is
        // plain-unique and brokerOrderId is unique+sparse.
        allCriticalIndexesOk &= ensureUniqueIndex(com.tradevision.model.Order.class, "clientOrderId", false);
        // brokerOrderId is only unique per (credentialId, symbol) in the real world — Binance's
        // own order-id numbering is scoped per account and symbol, not global — so this is a
        // compound unique constraint on {credentialId, symbol, brokerOrderId} rather than a
        // single-field one. A genuine partial index (rather than sparse=true) is used to exclude
        // brokerOrderId=null documents: on a compound index, sparse only excludes a document when
        // EVERY indexed field is absent, and credentialId/symbol are always set, so sparse alone
        // would still index every null-brokerOrderId Order and collide them all on the same key.
        //
        // migrateLegacySingleFieldUniqueIndex below removes an older, single-field unique(sparse)
        // index that an earlier revision created directly on this field (MongoDB's default name
        // "brokerOrderId_1"). That old index enforces GLOBAL uniqueness of brokerOrderId across
        // every credential and symbol, independent of whether the new, correctly-scoped compound
        // index also exists, so any database that ever created it needs this one-time migration
        // to actually get the scoped guarantee. It inspects the collection's real indexes via
        // getIndexInfo() and drops only a genuine single-field unique match on this exact field,
        // so it is a no-op once already migrated.
        migrateLegacySingleFieldUniqueIndex(com.tradevision.model.Order.class, "brokerOrderId");
        // MongoDB's createIndex refuses to silently redefine an existing index of the same name
        // with different options (IndexOptionsConflict). ensureUniquePartialCompoundIndex below
        // reads this index's current definition first and only drops+recreates it when that
        // definition is wrong, so an already-correct deployment sees no churn on every startup.
        allCriticalIndexesOk &= ensureUniquePartialCompoundIndex(com.tradevision.model.Order.class,
            "credential_symbol_brokerOrderId_unique",
            new org.bson.Document("credentialId", 1).append("symbol", 1).append("brokerOrderId", 1),
            new org.bson.Document("brokerOrderId", new org.bson.Document("$type", "string")));
        // A plain (non-unique) index on brokerOrderId alone is still needed for the few callers
        // that intentionally can't supply credentialId/symbol (see OrderRepository's
        // findByBrokerOrderId javadoc) — performance-only, so non-fatal like every other plain
        // index in this file. migrateLegacySingleFieldUniqueIndex above runs before this, so any
        // old unique-index definition under this exact default name is already cleared by the
        // time this call is reached, letting this create the plain version it's meant to be.
        ensureIndex(com.tradevision.model.Order.class, "brokerOrderId");
        // Same scoped-uniqueness, partial-index, and legacy-migration reasoning as
        // Order.brokerOrderId above applies to Position.entryOrderId: it is only unique per
        // (credentialId, symbol) in the real world, not globally.
        migrateLegacySingleFieldUniqueIndex(com.tradevision.model.Position.class, "entryOrderId");
        allCriticalIndexesOk &= ensureUniquePartialCompoundIndex(com.tradevision.model.Position.class,
            "credential_symbol_entryOrderId_unique",
            new org.bson.Document("credentialId", 1).append("symbol", 1).append("entryOrderId", 1),
            new org.bson.Document("entryOrderId", new org.bson.Document("$type", "string")));
        ensureIndex(com.tradevision.model.Position.class, "entryOrderId");
        // Cross-instance, cross-restart dedup for the scanner relies on this unique index:
        // without it, ScannedCandleRepository.save() would insert duplicate claimKey documents
        // freely across replicas.
        allCriticalIndexesOk &= ensureUniqueIndex(com.tradevision.model.ScannedCandle.class, "claimKey", false);
        startupState.markCriticalIndexesResult(allCriticalIndexesOk);
        // ExecutedOrder is no longer actively written to by this codebase — AutoTradeService's
        // entry path and OrderExecutionService's manual test-order path both create real Order
        // (OMS) records instead — so no indexes are created for its omsOrderId or
        // (credentialId,status,placedAt) shapes. The equivalent reconciliation query is already
        // covered by the Order.(credentialId,status,createdAt) compound index further below.
        ensureTtlIndex(OtpRecord.class, "expiresAt", Duration.ofSeconds(300));
        // Duration.ZERO reuses ensureTtlIndex's implementation to get MongoDB's
        // expireAfterSeconds=0 behavior: each document expires at the absolute time stored in
        // its own expiresAt field, rather than a fixed duration after this index confirmation
        // ran. This is how DistributedLockService's locks expire without a background sweep.
        ensureTtlIndex(com.tradevision.model.ReconciliationLock.class, "expiresAt", Duration.ZERO);
        // Same Duration.ZERO "expire at this document's own absolute expiresAt" mechanism as
        // ReconciliationLock above — an unconfirmed LIVE-connect token is durably evicted by
        // MongoDB itself once its own confirmation window passes.
        ensureTtlIndex(com.tradevision.model.PendingLiveConnect.class, "expiresAt", Duration.ZERO);
        // 2 years is a deliberately conservative retention window for compliance-adjacent
        // financial audit data. This does not conflict with BrokerAuditLog's own "immutable
        // audit trail" guarantee, since immutability is about rejecting modification of an
        // existing record, not about deletion under an intentional, policy-driven retention
        // window.
        ensureTtlIndex(com.tradevision.model.BrokerAuditLog.class, "timestamp", Duration.ofDays(730));
        // AuditChainCheckpoint deliberately has no TTL index: it is the durable anchor that must
        // survive BrokerAuditLog's own TTL sweep so the hash chain's tamper-detection still works
        // across entries that have since expired.
        ensureIndex(com.tradevision.model.AuditChainCheckpoint.class, "recordTimestamp");
        ensureTtlIndex(ApiMetric.class, "recordedAt", Duration.ofDays(7));
        // A candle from more than a day ago is irrelevant for scanner dedup purposes; without
        // this TTL, the collection would grow by one document per credential/symbol/timeframe
        // combination on every closed candle, forever.
        ensureTtlIndex(com.tradevision.model.ScannedCandle.class, "scannedAt", Duration.ofDays(1));

        // Compound indexes backing the query shapes reconciliation, position-monitoring, and
        // dashboard code run repeatedly. A failure here is logged but non-fatal — the
        // application remains correct without these, just slower as the underlying collections
        // grow with every trade.
        ensureCompoundIndex(com.tradevision.model.Position.class, new org.bson.Document("credentialId", 1).append("status", 1));
        ensureCompoundIndex(com.tradevision.model.Position.class, new org.bson.Document("credentialId", 1).append("symbol", 1).append("status", 1));
        ensureCompoundIndex(com.tradevision.model.Order.class, new org.bson.Document("credentialId", 1).append("status", 1).append("createdAt", 1));
        ensureCompoundIndex(com.tradevision.model.FillRecord.class, new org.bson.Document("positionId", 1).append("executedAt", 1));
        ensureCompoundIndex(com.tradevision.model.FillRecord.class, new org.bson.Document("credentialId", 1).append("brokerTradeId", 1));
        // Backs TradeEvent's authoritative event-ledger query shapes.
        ensureCompoundIndex(com.tradevision.model.TradeEvent.class, new org.bson.Document("orderId", 1).append("occurredAt", 1));
        ensureCompoundIndex(com.tradevision.model.TradeEvent.class, new org.bson.Document("positionId", 1).append("occurredAt", 1));
        // Matches the query shapes actually used against ExecutionContext/reservation
        // collections: credentialId+status+createdAt (the PENDING-in-flight/stale-cleanup
        // queries), positionId (the cross-class lookup ExecutionContextService's positionId-based
        // methods use), and signalId+createdAt (findBySignalIdOrderByCreatedAtDesc).
        ensureCompoundIndex(com.tradevision.model.ExecutionContext.class, new org.bson.Document("credentialId", 1).append("createdAt", 1));
        ensureCompoundIndex(com.tradevision.model.ExecutionContext.class, new org.bson.Document("signalId", 1).append("createdAt", 1));
        ensureIndex(com.tradevision.model.ExecutionContext.class, "positionId");
        ensureCompoundIndex(com.tradevision.model.ExposureReservationRecord.class,
            new org.bson.Document("credentialId", 1).append("status", 1).append("createdAt", 1));
        ensureCompoundIndex(com.tradevision.model.PositionSlotReservationRecord.class,
            new org.bson.Document("key", 1).append("status", 1).append("createdAt", 1));
        // ProtectionAttempt and OrphanedOco are both queried on every reconciliation cycle
        // (recoverStuckProtectionAttempts / recoverOrphanedOcos). Without these indexes, that
        // hot-path query turns into a full collection scan as the collections accumulate
        // historical records. The credentialId-prefixed compound variant additionally lets the
        // database do the per-credential filtering that recoverStuckProtectionAttempts currently
        // does in application code after fetching by status+createdAt.
        ensureCompoundIndex(com.tradevision.model.ProtectionAttempt.class,
            new org.bson.Document("status", 1).append("createdAt", 1));
        ensureCompoundIndex(com.tradevision.model.ProtectionAttempt.class,
            new org.bson.Document("credentialId", 1).append("status", 1).append("createdAt", 1));
        ensureCompoundIndex(com.tradevision.model.OrphanedOco.class,
            new org.bson.Document("credentialId", 1).append("resolved", 1));
        // Bounds the growth of each rate-limit collection, keyed by raw Document on windowStart.
        // A document only grows stale if its own IP/window genuinely stops being active (an
        // actively-hit key gets its windowStart reset well before this TTL would fire), so each
        // TTL is set to 2x the collection's own window duration as a safety margin against
        // exactly-on-the-boundary timing, not a tight bound.
        ensureRawCollectionTtlIndex("proxy_rate_limit", "windowStart", Duration.ofSeconds(120));
        ensureRawCollectionTtlIndex("bootstrap_rate_limit", "windowStart", Duration.ofSeconds(7200));
        // AuthController's two OTP rate-limit collections (otp_initiate_by_ip, keyed per client
        // IP; otp_resend_by_identifier, keyed per email/mobile), same 2x-window-duration
        // convention: otp_initiate_by_ip's window is AuthController.OTP_IP_WINDOW_SECONDS
        // (3600s); otp_resend_by_identifier's is OTP_RESEND_IDENTIFIER_COOLDOWN_SECONDS (30s).
        ensureRawCollectionTtlIndex("otp_initiate_by_ip", "windowStart", Duration.ofSeconds(7200));
        ensureRawCollectionTtlIndex("otp_resend_by_identifier", "windowStart", Duration.ofSeconds(60));
        // AuthController.checkEmail/checkMobile rate-limit collection; AuthController.ACCOUNT_CHECK_IP_WINDOW_SECONDS
        // is 3600s, so 2x that here.
        ensureRawCollectionTtlIndex("account_check_by_ip", "windowStart", Duration.ofSeconds(7200));
        // FeedbackController.submit rate-limit collection; FeedbackController.FEEDBACK_SUBMIT_IP_WINDOW_SECONDS
        // is 60s, so 2x that here.
        ensureRawCollectionTtlIndex("feedback_submit_by_ip", "windowStart", Duration.ofSeconds(120));
        // Attempts a harmless test transaction (started, then immediately aborted, no write) once
        // at startup, recording whether this MongoDB deployment supports multi-document
        // transactions. RiskProfileService.claimExecutionAtomicWithPlan falls back gracefully
        // when transactions aren't supported, but for LIVE trading specifically that fallback is
        // a weaker guarantee against the plan-disable-vs-execution race, so
        // authorizeLiveAutoTrade uses this result to refuse LIVE authorization outright on a
        // deployment that will never support the stronger guarantee.
        startupState.markMongoTransactionsResult(checkMongoTransactionSupport());
        indexCheckCompletedAt = java.time.Instant.now();
    }

    private boolean checkMongoTransactionSupport() {
        com.mongodb.client.ClientSession session;
        try {
            session = mongoTemplate.getMongoDatabaseFactory().getSession(com.mongodb.ClientSessionOptions.builder().build());
        } catch (Exception e) {
            log.warn("Could not obtain a MongoDB ClientSession at all to verify transaction support: {}", e.getMessage());
            return false;
        }
        try {
            // Proves transaction support with an actual operation inside the transaction (a
            // harmless count against the small BootstrapLock collection, via the session-bound
            // MongoOperations instance), rather than just creating and immediately aborting a
            // session. A standalone deployment's real failure mode is typically the operation
            // inside the transaction failing, not session creation itself, so session creation
            // alone would not reliably detect lack of support.
            var result = new boolean[]{false};
            try {
                var sessionScoped = mongoTemplate.withSession(session);
                session.startTransaction();
                sessionScoped.count(new org.springframework.data.mongodb.core.query.Query(), com.tradevision.model.BootstrapLock.class);
                session.abortTransaction(); // genuinely harmless -- no write was ever attempted
                result[0] = true;
            } finally {
                session.close();
            }
            if (result[0]) {
                log.info("Confirmed MongoDB transaction support at startup (verified with a real transactional read, not just "
                    + "session creation) -- the stronger, fully-atomic plan+profile execution claim path is available for LIVE "
                    + "autonomous trading.");
            }
            return result[0];
        } catch (com.mongodb.MongoException e) {
            // Code 20 ("IllegalOperation") is MongoDB's documented signal that this deployment
            // doesn't support transactions at all (standalone, not a replica set) — the same
            // detection RiskProfileService.claimExecutionAtomicWithPlan uses.
            if (e.getCode() == 20 || (e.getMessage() != null && e.getMessage().contains("Transaction numbers"))) {
                log.error("MongoDB transactions are NOT supported by this deployment (standalone, not a replica set/mongos) -- LIVE "
                    + "autonomous trading authorization will be refused until this is resolved (configure a MongoDB replica set). "
                    + "TESTNET/PAPER trading is unaffected.");
                return false;
            }
            log.warn("Unexpected error while verifying MongoDB transaction support at startup (treating as unsupported, the safer "
                + "assumption): {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Unexpected error while verifying MongoDB transaction support at startup (treating as unsupported, the safer "
                + "assumption): {}", e.getMessage());
            return false;
        }
    }

    private void ensureCompoundIndex(Class<?> entityClass, org.bson.Document keys) {
        try {
            mongoTemplate.indexOps(entityClass).ensureIndex(new org.springframework.data.mongodb.core.index.CompoundIndexDefinition(keys));
            log.info("Confirmed compound index on {}.({})", entityClass.getSimpleName(), keys.keySet());
        } catch (Exception e) {
            log.warn("Could not confirm compound index on {}.({}) -- queries using this shape will still work correctly, "
                + "just without the performance benefit of this index, until this is fixed. Error: {}",
                entityClass.getSimpleName(), keys.keySet(), e.getMessage());
            failedPerformanceIndexes.add(entityClass.getSimpleName() + ".(" + keys.keySet() + ") [compound]");
        }
    }

    /** Creates a plain, non-unique index on the given field, for query performance only. */
    private void ensureIndex(Class<?> entityClass, String field) {
        try {
            // Uses an explicit, never-previously-used name rather than letting MongoDB assign
            // its default "<field>_1". For Order.brokerOrderId and Position.entryOrderId, that
            // default name is identical to the legacy single-field unique index
            // migrateLegacySingleFieldUniqueIndex removes above — creating this plain index under
            // the same default name would silently recreate that name immediately after the
            // migration drops it, even though the broken unique semantics would not come back.
            // An explicit name keeps the legacy-index removal a real, permanent migration.
            mongoTemplate.indexOps(entityClass).ensureIndex(
                new Index().on(field, org.springframework.data.domain.Sort.Direction.ASC).named(field + "_plain_idx"));
            log.info("Confirmed index on {}.{}", entityClass.getSimpleName(), field);
        } catch (Exception e) {
            log.error("FAILED to confirm index on {}.{} — queries against this field will be slow (full collection scan) "
                + "until this is fixed. Error: {}", entityClass.getSimpleName(), field, e.getMessage());
        }
    }

    /**
     * Creates (or migrates in place) a unique compound index with a genuine partial filter
     * expression rather than sparse=true — see Order.java's @CompoundIndex javadoc for why
     * sparse does not behave the way it looks like it should on a compound index. Reads the
     * collection's real current indexes first (getIndexInfo(), never an assumed state) and only
     * drops and recreates a same-named index when its definition is actually wrong (not unique,
     * wrong key fields, or a different/missing partial filter), leaving an already-correct index
     * untouched instead of dropping and recreating it on every startup.
     */
    private boolean ensureUniquePartialCompoundIndex(Class<?> entityClass, String indexName,
                                                       org.bson.Document keys, org.bson.Document partialFilterExpression) {
        try {
            var existing = findIndexByName(entityClass, indexName);
            if (existing != null) {
                if (isCompoundIndexAlreadyCorrect(existing, keys, partialFilterExpression)) {
                    log.debug("Unique partial compound index {} on {} already has the correct definition -- no "
                        + "migration needed.", indexName, entityClass.getSimpleName());
                    return true;
                }
                log.info("Existing index {} on {} has an outdated/incorrect definition ({}) -- dropping it so it "
                    + "can be recreated with the correct one, rather than leaving a stale/wrong constraint in "
                    + "place indefinitely.", indexName, entityClass.getSimpleName(), existing);
                mongoTemplate.indexOps(entityClass).dropIndex(indexName);
            }
            var indexDef = new org.springframework.data.mongodb.core.index.CompoundIndexDefinition(keys)
                .named(indexName)
                .unique()
                .partial(org.springframework.data.mongodb.core.index.PartialIndexFilter.of(partialFilterExpression));
            mongoTemplate.indexOps(entityClass).ensureIndex(indexDef);
            log.info("Confirmed unique partial compound index on {}.({}) [partialFilter: {}]",
                entityClass.getSimpleName(), keys.keySet(), partialFilterExpression.toJson());
            return true;
        } catch (Exception e) {
            log.error("FAILED to confirm unique partial compound index on {}.({}) — distributed correctness for "
                + "this field combination is NOT guaranteed until this is fixed. Likely cause: pre-existing "
                + "duplicate data for this key combination among documents that actually have every field set, "
                + "which must be resolved manually. Error: {}",
                entityClass.getSimpleName(), keys.keySet(), e.getMessage());
            return false;
        }
    }

    /** Reads this collection's real indexes and returns the one with this exact name, or null. */
    private org.springframework.data.mongodb.core.index.IndexInfo findIndexByName(Class<?> entityClass, String indexName) {
        return mongoTemplate.indexOps(entityClass).getIndexInfo().stream()
            .filter(info -> indexName.equals(info.getName()))
            .findFirst().orElse(null);
    }

    /**
     * True only when an existing same-named index already matches every property that matters for
     * the uniqueness guarantee: unique, the exact key fields in the exact order (compound index
     * key order is semantically significant in MongoDB, not cosmetic), and
     * an equivalent partial filter expression (compared as parsed documents, not raw strings --
     * MongoDB can round-trip the same filter with different key ordering/whitespace).
     */
    private boolean isCompoundIndexAlreadyCorrect(org.springframework.data.mongodb.core.index.IndexInfo existing,
                                                    org.bson.Document keys, org.bson.Document partialFilterExpression) {
        if (!existing.isUnique()) return false;
        java.util.List<org.springframework.data.mongodb.core.index.IndexField> fields = existing.getIndexFields();
        if (fields.size() != keys.size()) return false;
        int i = 0;
        for (String key : keys.keySet()) {
            var field = fields.get(i++);
            if (!key.equals(field.getKey())) return false;
        }
        String existingFilter = existing.getPartialFilterExpression();
        if (existingFilter == null || existingFilter.isBlank()) return false;
        try {
            return org.bson.Document.parse(existingFilter).equals(partialFilterExpression);
        } catch (Exception e) {
            return false; // unparseable/unexpected shape -- treat as a mismatch, never assume correct
        }
    }

    /**
     * Drops a legacy single-field unique index on `field`, if one exists, that an earlier schema
     * revision created before the scoped compound-index constraint existed. Such an index
     * enforces global uniqueness of `field` on its own, regardless of whether the new,
     * correctly-scoped compound index also exists, reproducing a cross-symbol/credential
     * collision bug for as long as it remains. Reads the collection's real current indexes
     * (never a guessed name) and drops only a genuine single-field unique match, so this is a
     * one-time, self-limiting migration: on an already-migrated or fresh database, it finds
     * nothing and does nothing.
     */
    private void migrateLegacySingleFieldUniqueIndex(Class<?> entityClass, String field) {
        try {
            var legacy = mongoTemplate.indexOps(entityClass).getIndexInfo().stream()
                .filter(info -> info.isUnique())
                .filter(info -> {
                    var fields = info.getIndexFields();
                    return fields.size() == 1 && field.equals(fields.get(0).getKey());
                })
                .findFirst().orElse(null);
            if (legacy == null) {
                log.debug("No legacy single-field unique index found on {}.{} -- already migrated, or this "
                    + "database never had one.", entityClass.getSimpleName(), field);
                return;
            }
            log.warn("Migrating away a LEGACY single-field unique index {} on {}.{} -- this index enforces "
                + "global uniqueness of {} across every credential/symbol on its own, which is exactly the "
                + "cross-symbol collision bug the newer, correctly-scoped compound index exists to close. "
                + "Dropping it now so it can no longer collide independently of that fix.",
                legacy.getName(), entityClass.getSimpleName(), field, field);
            mongoTemplate.indexOps(entityClass).dropIndex(legacy.getName());
            log.info("Migration complete: dropped legacy unique index {} on {}.{}.",
                legacy.getName(), entityClass.getSimpleName(), field);
        } catch (Exception e) {
            log.error("FAILED to check for/migrate a legacy single-field unique index on {}.{} -- if one still "
                + "exists on this database, the cross-symbol/credential order-ID collision bug it enforces "
                + "remains live regardless of the newer compound index. Error: {}",
                entityClass.getSimpleName(), field, e.getMessage());
        }
    }

    private boolean ensureUniqueIndex(Class<?> entityClass, String field, boolean sparse) {
        try {
            Index index = new Index().on(field, org.springframework.data.domain.Sort.Direction.ASC).unique();
            if (sparse) index = index.sparse();
            mongoTemplate.indexOps(entityClass).ensureIndex(index);
            log.info("Confirmed unique{} index on {}.{}", sparse ? " sparse" : "", entityClass.getSimpleName(), field);
            return true;
        } catch (Exception e) {
            // Deliberately loud: a failed unique-index creation here means the correctness
            // guarantee this application relies on (one document per key) may not actually hold.
            // A common cause is pre-existing duplicate data — that needs a human to resolve, not
            // a silent retry.
            log.error("FAILED to confirm unique index on {}.{} — distributed correctness for this "
                + "field is NOT guaranteed until this is fixed. Likely cause: duplicate existing "
                + "data for this field, which must be resolved manually. Error: {}",
                entityClass.getSimpleName(), field, e.getMessage());
            return false;
        }
    }

    private void ensureTtlIndex(Class<?> entityClass, String field, Duration ttl) {
        try {
            mongoTemplate.indexOps(entityClass).ensureIndex(new Index().on(field, org.springframework.data.domain.Sort.Direction.ASC).expire(ttl));
            log.info("Confirmed TTL index on {}.{} ({}s)", entityClass.getSimpleName(), field, ttl.getSeconds());
        } catch (Exception e) {
            log.error("FAILED to confirm TTL index on {}.{} — this collection will NOT auto-expire old documents "
                + "until this is fixed. Error: {}", entityClass.getSimpleName(), field, e.getMessage());
            failedPerformanceIndexes.add(entityClass.getSimpleName() + "." + field + " [TTL]");
        }
    }

    /**
     * TTL-index helper for rate-limit collections stored as raw org.bson.Document rather than a
     * @Document-annotated entity class, used in place of ensureTtlIndex's Class<?>-based variant.
     * Deliberately calls the single-argument indexOps(String) overload rather than the
     * two-argument indexOps(collectionName, type): the latter has a reported bug
     * (spring-projects/spring-data-mongodb#4698) where a non-null type silently overrides the
     * given collection name, and indexOps(String) avoids it by calling
     * indexOps(collectionName, null) internally.
     */
    private void ensureRawCollectionTtlIndex(String collectionName, String field, Duration ttl) {
        try {
            mongoTemplate.indexOps(collectionName).ensureIndex(new Index().on(field, org.springframework.data.domain.Sort.Direction.ASC).expire(ttl));
            log.info("Confirmed TTL index on {}.{} ({}s)", collectionName, field, ttl.getSeconds());
        } catch (Exception e) {
            log.error("FAILED to confirm TTL index on {}.{} — this collection will NOT auto-expire old documents "
                + "until this is fixed. Error: {}", collectionName, field, e.getMessage());
        }
    }
}
