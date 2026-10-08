package com.tradevision.service;

import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;

/**
 * Enforces per-key rate limits across all app instances using MongoDB as the shared store,
 * rather than JVM-local state that only limits requests within a single instance. A fixed-window
 * counter is used instead of a sliding window: it only needs one atomic {@code $inc} per request,
 * rather than a stored, per-key timestamp list that would need careful atomic trimming to stay
 * race-free across replicas.
 *
 * <p>Scope: this is a fixed window, so it can allow up to 2x the nominal limit in the worst case
 * (a burst right at the boundary between two windows). For a rate limit whose purpose is abuse
 * and cost protection rather than a precise SLA, that trade-off is acceptable in exchange for the
 * much simpler, cheaper implementation.
 */
@Service
@RequiredArgsConstructor
public class DistributedRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(DistributedRateLimitService.class);
    /**
     * Hard ceiling on retries inside {@link #attemptOnce}, so a pathological concurrent-reset
     * storm can never loop unbounded. Correctness only ever requires one legitimate retry (see
     * {@link #attemptOnce}); this exists purely as a backstop.
     */
    private static final int MAX_RETRIES = 5;

    private final MongoTemplate mongoTemplate;

    /**
     * Atomically increments the counter for {@code key} within its current {@code windowSeconds}
     * window (resetting first if the previous window has expired), then returns whether this
     * request should be allowed (the resulting count is still within {@code maxPerWindow}).
     */
    public boolean allow(String collectionName, String key, int maxPerWindow, long windowSeconds) {
        int count = attemptOnce(collectionName, key, windowSeconds, 0);
        return count <= maxPerWindow;
    }

    /**
     * Increments and returns the counter for {@code key}, resolving the whole decision -- "is the
     * current window still live" and "what to write" -- inside MongoDB itself via atomic
     * {@code findAndModify} calls, never by reading state into application code first.
     *
     * <p>Path 1 (the common case, an existing still-live window): a single {@code findAndModify}
     * whose query itself requires {@code windowStart} to still be within the window, incrementing
     * {@code count}. The liveness check and the increment happen as one atomic operation.
     *
     * <p>Path 2 (no live window matched -- no document yet, or the window expired): a single
     * {@code findAndModify} with {@code upsert=true} whose query requires {@code windowStart} to
     * be either missing or expired, resetting it to now with {@code count=1}. If two callers hit
     * this simultaneously with no existing document, MongoDB's single-document write
     * serialization guarantees only one can actually insert; the other gets a duplicate-key
     * error, which is caught and turned into a bounded retry of path 1 against the winner's fresh
     * document.
     */
    private int attemptOnce(String collectionName, String key, long windowSeconds, int retryDepth) {
        Instant now = Instant.now();
        Date cutoff = Date.from(now.minusSeconds(windowSeconds));

        Query stillLiveQuery = new Query(Criteria.where("_id").is(key).and("windowStart").gt(cutoff));
        Document incremented = mongoTemplate.findAndModify(stillLiveQuery,
            new Update().inc("count", 1),
            FindAndModifyOptions.options().upsert(false).returnNew(true),
            Document.class, collectionName);
        if (incremented != null) {
            Integer count = incremented.getInteger("count");
            return count != null ? count : 1;
        }

        // No live window matched -- either this key has never been seen, or its previous window
        // has genuinely expired. Reset atomically, but ONLY against a document that is itself
        // still missing/expired (never against one another caller may have already reset).
        Query expiredOrMissingQuery = new Query(new Criteria().andOperator(
            Criteria.where("_id").is(key),
            new Criteria().orOperator(Criteria.where("windowStart").exists(false), Criteria.where("windowStart").lte(cutoff))));
        try {
            Document reset = mongoTemplate.findAndModify(expiredOrMissingQuery,
                new Update().set("windowStart", Date.from(now)).set("count", 1),
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                Document.class, collectionName);
            if (reset != null) {
                Integer count = reset.getInteger("count");
                return count != null ? count : 1;
            }
            // A concurrent caller reset this document to a fresh, now-live window between the two
            // findAndModify calls above -- a lost footrace, not a duplicate-key error. That fresh
            // window is exactly what path 1 (stillLiveQuery) is built to find and increment.
        } catch (DuplicateKeyException e) {
            log.debug("Concurrent rate-limit reset race on {}.{} (expected under real concurrency, not an error) -- "
                + "retrying against the winning caller's own fresh window.", collectionName, key);
        }
        if (retryDepth >= MAX_RETRIES) {
            // Should not happen in practice; fail open (allow) rather than block legitimate
            // requests on what would be an infrastructure-level anomaly at this retry depth.
            log.error("Rate limiter for {}.{} could not resolve after {} retries -- allowing this request rather than "
                + "blocking on what should be an unreachable retry depth.", collectionName, key, MAX_RETRIES);
            return 1;
        }
        return attemptOnce(collectionName, key, windowSeconds, retryDepth + 1);
    }
}
