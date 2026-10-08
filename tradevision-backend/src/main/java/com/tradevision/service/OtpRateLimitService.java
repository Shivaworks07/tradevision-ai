package com.tradevision.service;

import org.springframework.dao.DuplicateKeyException;
import com.tradevision.model.OtpRateLimit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Enforces a cooldown between consecutive OTP sends and a cap on sends within a rolling
 * window, keyed by identifier+purpose — regardless of whether the identifier belongs to an
 * existing user. Uses the same atomic-findAndModify pattern as PositionSlotReservationService:
 * a plain read-then-write here would leave a window for concurrent requests to race past the
 * cooldown/cap checks, so every state transition is conditioned on the state actually read.
 */
@Service
@RequiredArgsConstructor
public class OtpRateLimitService {

    private static final Duration COOLDOWN = Duration.ofSeconds(45);
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_PER_WINDOW = 5;

    private final MongoTemplate mongoTemplate;

    public record RateLimitResult(boolean allowed, String reason) {
        public static RateLimitResult ok() { return new RateLimitResult(true, null); }
        public static RateLimitResult reject(String reason) { return new RateLimitResult(false, reason); }
    }

    /** Call before actually sending. If allowed, the attempt is already recorded — no separate "record" call needed. */
    public RateLimitResult checkAndRecord(String identifier, String purpose) {
        return checkAndRecord(identifier, purpose, 0);
    }

    // On a DuplicateKeyException for the first-insert path, re-check against whichever document
    // actually won the insert race rather than assuming the losing request is fine — two
    // concurrent first-time requests must not both be allowed through. The window-reset path
    // below applies the same conditioned-retry approach for its own read-then-write step.
    // Retry depth is bounded so a pathological repeated-collision case fails safe instead of
    // recursing forever.
    private RateLimitResult checkAndRecord(String identifier, String purpose, int attempt) {
        if (attempt > 3) {
            return RateLimitResult.reject("Could not process this request right now — try again in a moment.");
        }
        String key = identifier + "|" + purpose;
        Query byKey = new Query(where("key").is(key));
        OtpRateLimit doc = mongoTemplate.findOne(byKey, OtpRateLimit.class);

        LocalDateTime now = LocalDateTime.now();

        if (doc == null) {
            OtpRateLimit fresh = new OtpRateLimit();
            fresh.setKey(key);
            fresh.setWindowStart(now);
            fresh.setSendCount(1);
            fresh.setLastSentAt(now);
            try {
                mongoTemplate.insert(fresh);
                return RateLimitResult.ok();
            } catch (DuplicateKeyException e) {
                // Another concurrent request created the document first — re-check against
                // what's actually there now (cooldown/window/cap), don't assume we're allowed.
                return checkAndRecord(identifier, purpose, attempt + 1);
            }
        }

        if (doc.getLastSentAt() != null && doc.getLastSentAt().isAfter(now.minus(COOLDOWN))) {
            long secondsLeft = Duration.between(now, doc.getLastSentAt().plus(COOLDOWN)).getSeconds();
            return RateLimitResult.reject("Please wait " + Math.max(1, secondsLeft) + " seconds before requesting another OTP.");
        }

        boolean windowExpired = doc.getWindowStart().isBefore(now.minus(WINDOW));
        if (windowExpired) {
            // Conditioned on windowStart still matching what we just read — if another request
            // already reset it, this no-ops and we retry against the fresh state instead of
            // blindly declaring success.
            Query stillStaleWindow = new Query(where("key").is(key).and("windowStart").is(doc.getWindowStart()));
            Update reset = new Update().set("windowStart", now).set("sendCount", 1).set("lastSentAt", now);
            OtpRateLimit resetDoc = mongoTemplate.findAndModify(stillStaleWindow, reset, OtpRateLimit.class);
            if (resetDoc == null) {
                return checkAndRecord(identifier, purpose, attempt + 1);
            }
            return RateLimitResult.ok();
        }

        if (doc.getSendCount() >= MAX_PER_WINDOW) {
            return RateLimitResult.reject("Too many OTP requests for this "
                + (identifier.contains("@") ? "email" : "number") + " — try again later.");
        }

        // Atomic increment, conditioned on still being under the cap and in the same window —
        // a concurrent request that already pushed the count to the max loses this race safely.
        Query stillUnderCap = new Query(where("key").is(key)
            .and("sendCount").lt(MAX_PER_WINDOW)
            .and("windowStart").is(doc.getWindowStart()));
        Update inc = new Update().inc("sendCount", 1).set("lastSentAt", now);
        OtpRateLimit updated = mongoTemplate.findAndModify(stillUnderCap, inc, OtpRateLimit.class);
        if (updated == null) {
            // Could be "genuinely at cap" or "lost the race to a concurrent update" — re-check
            // against current state rather than assuming which one it was.
            return checkAndRecord(identifier, purpose, attempt + 1);
        }
        return RateLimitResult.ok();
    }
}
