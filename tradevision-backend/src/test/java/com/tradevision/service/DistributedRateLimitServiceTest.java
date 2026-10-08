package com.tradevision.service;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Direct tests for the two-path atomic rate-limit design, including proof that concurrent
 * requests still respect the limit under the one genuine race this design cannot avoid at the
 * storage layer (simultaneous inserts of a brand-new key).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DistributedRateLimitServiceTest {

    @Mock MongoTemplate mongoTemplate;
    @InjectMocks DistributedRateLimitService service;

    private Document doc(int count) {
        Document d = new Document();
        d.put("count", count);
        return d;
    }

    @Test
    @DisplayName("allow: an existing, still-live window is incremented in a single atomic findAndModify (path 1) -- no separate read-then-upsert reset call ever happens")
    void existingLiveWindow_incrementsViaPathOneOnly() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq("otp_initiate_by_ip")))
            .thenReturn(doc(3));

        boolean allowed = service.allow("otp_initiate_by_ip", "1.2.3.4", 20, 3600);

        assertThat(allowed).isTrue();
        // Exactly one findAndModify call -- path 1 matched, so path 2 (the reset/upsert) was never reached.
        verify(mongoTemplate, times(1)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq("otp_initiate_by_ip"));
    }

    @Test
    @DisplayName("allow: count exceeding maxPerWindow is rejected")
    void countExceedsLimit_rejected() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq("otp_initiate_by_ip")))
            .thenReturn(doc(21));

        boolean allowed = service.allow("otp_initiate_by_ip", "1.2.3.4", 20, 3600);

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("allow: no live window (fresh key) -- path 1 finds nothing, path 2 atomically resets and returns count=1, all within a single allow() call")
    void freshKey_noRaceNeeded_resetsViaPathTwo() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq("otp_resend_by_identifier")))
            .thenReturn(null, doc(1)); // path 1 (live-window increment) misses, path 2 (reset+upsert) succeeds

        boolean allowed = service.allow("otp_resend_by_identifier", "user@example.com", 1, 30);

        assertThat(allowed).isTrue();
        verify(mongoTemplate, times(2)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq("otp_resend_by_identifier"));
    }

    /**
     * The ONE race this design cannot avoid at the storage layer -- two concurrent callers both
     * hitting path 2 (the reset/upsert) for a brand-new key at the same time. Exactly one of
     * them wins the real MongoDB insert; the other gets a DuplicateKeyException back from the
     * (mocked) driver and must retry, landing on path 1 next time and finding the WINNER's own
     * fresh document -- proving the loser's request is still correctly counted against the
     * winner's window, never silently dropped or double-counted.
     */
    @Test
    @DisplayName("allow: a concurrent reset race (DuplicateKeyException on the losing caller's own upsert) retries and correctly counts against the winner's fresh window, rather than crashing or silently allowing an uncounted request")
    void concurrentResetRace_retriesAndCountsAgainstWinnersWindow() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq("proxy_rate_limit")))
            .thenReturn(null) // 1st call: path 1 (live-window increment) misses -- no document yet
            .thenThrow(new DuplicateKeyException("E11000 duplicate key error -- another caller already won this reset"))
            // 2nd call: path 2 (reset+upsert) loses the race to a concurrent winner
            .thenReturn(doc(2)); // 3rd call (retry): path 1 now finds the winner's fresh, live window and increments it to 2

        boolean allowed = service.allow("proxy_rate_limit", "203.0.113.5", 60, 60);

        assertThat(allowed).isTrue();
        verify(mongoTemplate, times(3)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq("proxy_rate_limit"));
    }

    @Test
    @DisplayName("allow: path 2 finding no document to reset (a concurrent caller already reset it between this caller's own path 1 and path 2 attempts, WITHOUT a duplicate-key error) also retries and lands correctly on path 1")
    void concurrentResetWonBeforeUpsertAttempted_retriesCleanly() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq("proxy_rate_limit")))
            .thenReturn(null) // 1st call: path 1 misses
            .thenReturn(null) // 2nd call: path 2's own query no longer matches (already reset by a concurrent winner) -- no exception, just null
            .thenReturn(doc(1)); // 3rd call (retry): path 1 now finds the winner's fresh window

        boolean allowed = service.allow("proxy_rate_limit", "203.0.113.6", 60, 60);

        assertThat(allowed).isTrue();
        verify(mongoTemplate, times(3)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq("proxy_rate_limit"));
    }

    @Test
    @DisplayName("allow: exhausting every retry (a pathological, should-be-unreachable case) fails OPEN rather than blocking every legitimate request")
    void retriesExhausted_failsOpen() {
        // Every attemptOnce() call misses path 1 (null) then hits the DuplicateKeyException race
        // on path 2, forever -- this should never genuinely happen (MAX_RETRIES is a hard
        // backstop, not an expected depth), but the fail-open behavior must still hold rather
        // than throwing all the way up. 6 total attemptOnce invocations (depth 0..5), 2 calls
        // each (path 1 miss, path 2 race) = 12 alternating responses.
        var stubbing = when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Document.class), eq("proxy_rate_limit")))
            .thenReturn(null);
        for (int i = 0; i < 6; i++) {
            stubbing = stubbing.thenThrow(new DuplicateKeyException("simulated perpetual race")).thenReturn(null);
        }

        boolean allowed = service.allow("proxy_rate_limit", "203.0.113.7", 1, 60);

        assertThat(allowed).isTrue(); // fails open
    }
}
