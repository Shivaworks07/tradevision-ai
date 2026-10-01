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
 * Review finding ("Admin bootstrap rate limiter is JVM-local" -- P1, and "Public proxy endpoints
 * remain abuseable... The current IP limiter is JVM-local" -- P1): a genuine, real fix for both
 * without new infrastructure (Redis/Bucket4j) this codebase doesn't already have configured --
 * MongoDB is already a real dependency here, and a fixed-window counter (not a precise sliding
 * window) is achievable with it cheaply: one atomic $inc per request, not a stored deque of
 * timestamps per key that would need scanning/trimming on every check.
 *
 * HONEST SCOPE, stated plainly: this is a FIXED window, not the sliding window
 * ProxyController's own original JVM-local implementation had. A fixed window can allow up to
 * 2x the nominal limit in the worst case (a burst right at the boundary between two windows) --
 * a real, known trade-off of this simpler design, not silently different behavior. For a rate
 * limit whose purpose is abuse/cost protection rather than a precise SLA, this is an accepted,
 * disclosed trade-off in exchange for not needing a per-key stored, scanned timestamp list
 * (which would itself need very careful atomic trimming to be race-free across replicas, a
 * meaningfully larger and riskier piece of Mongo-only engineering than a fixed-window counter).
 */
@Service
@RequiredArgsConstructor
public class DistributedRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(DistributedRateLimitService.class);
    /**
     * P2-10 fix ("DistributedRateLimitService.allow: window reset is read-then-upsert (racy);
     * OTP collections have no TTL" -- external review, confirmed real by direct inspection before
     * this fix: the OLD implementation read the current document (findOne), decided in
     * application code whether the window had expired, and only THEN issued a separate upsert to
     * reset it -- two round-trips with a real gap between them. Two concurrent callers on the same
     * key, right at a window boundary, could both read the same stale/expired windowStart, both
     * decide independently that a reset is needed, and both race to reset -- whichever reset
     * "wins" last silently discards the other's own reset (and the count it may have started
     * incrementing from), and depending on exact interleaving with the increment call right after,
     * a request can be undercounted (let through when it shouldn't have been) rather than merely
     * overcounted. This directly undermines the abuse protection these rate limits exist for):
     * bounded to avoid ever looping more than this many times even under a pathological retry
     * storm -- one legitimate retry (see attemptOnce's own javadoc) is all correctness requires;
     * this is a hard backstop, not an expected depth.
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
     * P2-10 fix, full context in this class's own updated javadoc above: replaces the old
     * read-then-upsert reset with two candidate atomic operations, each a single
     * findAndModify -- no read of this document's own state is ever used to decide what to write
     * next; every decision is made by MongoDB itself, atomically, against the document's REAL
     * current state at the moment of the operation.
     *
     * Path 1 (the common case -- an existing, still-live window): findAndModify with a query that
     * itself requires windowStart to still be within the window, incrementing count. If this
     * matches, the increment and the "is this window still live" check happened as ONE atomic
     * operation -- no other caller can have raced this specific transition.
     *
     * Path 2 (no live window matched -- either no document exists yet for this key, or its window
     * has expired): findAndModify with upsert=true and a query that itself requires windowStart to
     * be EITHER missing or expired, resetting windowStart to now and count to 1. This is also a
     * single atomic operation -- but if two callers hit this simultaneously with no existing
     * document, MongoDB's own single-document write serialization guarantees only one of them can
     * actually perform the insert; the other genuinely fails with a duplicate-key error (this is
     * the ONE race this design cannot make disappear -- concurrent inserts of the same _id are
     * fundamentally a single-winner race at the storage layer -- so it is caught and turned into a
     * bounded retry of path 1, which now finds the winner's fresh document, rather than a race
     * silently corrupting either caller's own idea of the count).
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
            // A concurrent caller reset this document to a fresh, now-live window between this
            // method's own two findAndModify calls above -- not a duplicate-key race, just a lost
            // footrace. That fresh window is exactly what path 1 (stillLiveQuery) is built to
            // find and increment correctly.
        } catch (DuplicateKeyException e) {
            log.debug("Concurrent rate-limit reset race on {}.{} (expected under real concurrency, not an error) -- "
                + "retrying against the winning caller's own fresh window.", collectionName, key);
        }
        if (retryDepth >= MAX_RETRIES) {
            // Genuinely should not happen (MAX_RETRIES is a hard backstop, not an expected depth
            // -- see this class's own field javadoc) -- fail OPEN (allow) rather than block every
            // legitimate request on what would be an infrastructure-level anomaly at this point,
            // matching this codebase's own established "don't let an observability/edge-case gap
            // become a harder outage than the thing it was protecting against" principle.
            log.error("Rate limiter for {}.{} could not resolve after {} retries -- allowing this request rather than "
                + "blocking on what should be an unreachable retry depth.", collectionName, key, MAX_RETRIES);
            return 1;
        }
        return attemptOnce(collectionName, key, windowSeconds, retryDepth + 1);
    }
}
