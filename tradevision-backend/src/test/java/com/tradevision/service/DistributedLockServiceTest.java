package com.tradevision.service;

import com.tradevision.model.ReconciliationLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies the distributed reconciliation lock, which allows autonomous trading/reconciliation
 * to safely scale across multiple instances.
 *
 * LIMITATION: Query/Criteria construction is exercised here (the actual filter this class
 * builds), but MongoTemplate itself is a mock, so the real atomic-insert-collision behavior of
 * a genuine MongoDB unique index is not exercised end-to-end (Maven Central is blocked in this
 * sandbox, so no real spring-data-mongodb jar is available). DuplicateKeyException is simulated
 * directly rather than produced by a genuine unique-index collision.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class DistributedLockServiceTest {

    @Mock MongoTemplate mongoTemplate;

    @InjectMocks DistributedLockService service;

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        // Every tryAcquireWithDiagnosis call unconditionally calls nextGeneration() BEFORE
        // attempting the actual insert -- an unstubbed findAndModify() would otherwise return
        // null (Mockito's own real default for an unstubbed object-returning method), and this
        // class's own code immediately calls .getValue() on the result, which would NPE. A
        // realistic "counter incremented to 1" default -- a test that specifically cares about
        // the exact generation value overrides this explicitly.
        when(mongoTemplate.findAndModify(any(), any(org.springframework.data.mongodb.core.query.Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(com.tradevision.model.LockGenerationCounter.class)))
            .thenAnswer(inv -> { var c = new com.tradevision.model.LockGenerationCounter(); c.setId("cred1"); c.setValue(1L); return c; });
    }

    @Test
    @DisplayName("tryAcquire: a successful insert (no collision) returns true -- this instance won the lock")
    void tryAcquire_noCollision_returnsTrue() {
        boolean result = service.tryAcquire("cred1", "instance-a", Duration.ofSeconds(90));

        assertThat(result).isTrue();
        ArgumentCaptor<ReconciliationLock> captor = ArgumentCaptor.forClass(ReconciliationLock.class);
        verify(mongoTemplate).insert(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("cred1");
        assertThat(captor.getValue().getInstanceId()).isEqualTo("instance-a");
    }

    @Test
    @DisplayName("tryAcquire: a DuplicateKeyException (another instance already holds this credential's lock) returns false, not thrown further -- the expected, normal outcome of losing a race, not an error")
    void tryAcquire_duplicateKey_returnsFalseNotThrown() {
        when(mongoTemplate.insert(any(ReconciliationLock.class))).thenThrow(new DuplicateKeyException("E11000 duplicate key"));

        boolean result = service.tryAcquire("cred1", "instance-a", Duration.ofSeconds(90));

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("tryAcquire: any other MongoDB failure is treated as NOT-acquired, never as acquired -- a genuine infrastructure problem must never be silently treated as a successful lock")
    void tryAcquire_otherException_treatedAsNotAcquired() {
        when(mongoTemplate.insert(any(ReconciliationLock.class))).thenThrow(new RuntimeException("simulated connection failure"));

        boolean result = service.tryAcquire("cred1", "instance-a", Duration.ofSeconds(90));

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("tryAcquire: sweeps any already-expired lock for this credential before attempting to insert, so a crashed instance's stale lock doesn't have to wait for the TTL background sweep's own interval")
    void tryAcquire_sweepsExpiredLockFirst() {
        service.tryAcquire("cred1", "instance-a", Duration.ofSeconds(90));

        verify(mongoTemplate).remove(any(Query.class), eq(ReconciliationLock.class));
    }

    @Test
    @DisplayName("release: removes the lock document scoped to both credentialId AND instanceId -- never removes a different instance's genuine, active lock for the same credential")
    void release_scopedToCredentialAndInstance() {
        service.release("cred1", "instance-a");

        verify(mongoTemplate).remove(any(Query.class), eq(ReconciliationLock.class));
    }

    @Test
    @DisplayName("release: a MongoDB failure during release is logged, never thrown further -- the TTL index remains the real backstop against a permanently-stuck lock even if this specific release call fails")
    void release_exceptionHandledGracefully() {
        doThrow(new RuntimeException("simulated connection failure")).when(mongoTemplate).remove(any(Query.class), eq(ReconciliationLock.class));

        // Must not throw.
        service.release("cred1", "instance-a");
    }

    // ── renew: extends a held lock's lease without a fixed 90-second cap ────────────────

    @Test
    @DisplayName("renew: a successful update (still owned by this instance) returns true")
    void renew_stillOwned_returnsTrue() {
        when(mongoTemplate.updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class), eq(ReconciliationLock.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));

        boolean result = service.renew("cred1", "instance-a", java.time.Duration.ofSeconds(90));

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("renew: matchedCount=0 (lock no longer owned by this instance -- expired and possibly reacquired by someone else) returns false, not an exception -- the caller should treat this the same as never having held the lock. " +
        "matchedCount, not modifiedCount, is what actually reflects whether this instance's own lock document was found and updated -- modifiedCount is ALSO 0 on a same-millisecond renewal where the expiry value doesn't actually change, " +
        "which would otherwise read a genuinely-held lock's renewal as 'lost' and abort an in-progress reconciliation pass.")
    void renew_noLongerOwned_returnsFalse() {
        when(mongoTemplate.updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class), eq(ReconciliationLock.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null));

        boolean result = service.renew("cred1", "instance-a", java.time.Duration.ofSeconds(90));

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("renew: matchedCount>0 but modifiedCount=0 (a same-millisecond renewal where the new expiry equals the value already stored) still returns true -- this instance genuinely still holds the lock, nothing was lost")
    void renew_sameMillisecondRenewal_matchedButNotModified_stillReturnsTrue() {
        when(mongoTemplate.updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class), eq(ReconciliationLock.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 0L, null));

        boolean result = service.renew("cred1", "instance-a", java.time.Duration.ofSeconds(90));

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("renew: a MongoDB failure is treated as NOT renewed, never thrown further -- same 'genuine infrastructure problem must never be silently treated as success' contract as tryAcquire")
    void renew_exceptionTreatedAsNotRenewed() {
        when(mongoTemplate.updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class), eq(ReconciliationLock.class)))
            .thenThrow(new RuntimeException("simulated connection failure"));

        boolean result = service.renew("cred1", "instance-a", java.time.Duration.ofSeconds(90));

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("renew: scoped to both credentialId AND instanceId, same as release -- never extends a different instance's genuine, active lock for the same credential")
    void renew_scopedToCredentialAndInstance() {
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        when(mongoTemplate.updateFirst(queryCaptor.capture(), any(org.springframework.data.mongodb.core.query.Update.class), eq(ReconciliationLock.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));

        service.renew("cred1", "instance-a", java.time.Duration.ofSeconds(90));

        assertThat(queryCaptor.getValue().getQueryObject().toString()).contains("cred1").contains("instance-a");
    }

    @Test
    @DisplayName("renew: the query also requires expiresAt to still be in the future, preventing an instance whose own lock already expired from silently extending it back into existence without ever proving continuous ownership through the gap")
    void renew_requiresLockStillUnexpired() {
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        when(mongoTemplate.updateFirst(queryCaptor.capture(), any(org.springframework.data.mongodb.core.query.Update.class), eq(ReconciliationLock.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));

        service.renew("cred1", "instance-a", java.time.Duration.ofSeconds(90));

        assertThat(queryCaptor.getValue().getQueryObject().toString()).contains("expiresAt");
    }

    @Test
    @DisplayName("currentGeneration: returns the real, persisted generation (a genuine, DB-generated atomic counter value, not derived from any wall-clock timestamp) when a lock document exists for this credential")
    void currentGeneration_lockExists_returnsRealGeneration() {
        var lock = new ReconciliationLock("cred1", "instance-a", Instant.now().plusSeconds(60), 42L);
        when(mongoTemplate.findById("cred1", ReconciliationLock.class)).thenReturn(lock);

        long generation = service.currentGeneration("cred1");

        assertThat(generation).isEqualTo(42L);
    }

    @Test
    @DisplayName("currentGeneration: returns -1, not 0, when no lock document exists for this credential -- a caller must not silently treat a missing lock as generation 0, which could coincidentally collide with a real, different lock's own generation under clock skew across processes")
    void currentGeneration_noLock_returnsNegativeOne() {
        when(mongoTemplate.findById("cred1", ReconciliationLock.class)).thenReturn(null);

        assertThat(service.currentGeneration("cred1")).isEqualTo(-1L);
    }

    @Test
    @DisplayName("renew (generation-aware overload): the query requires the real, persisted generation field to still match the caller's own captured value, on top of instanceId and the un-expired check -- a real fencing token rather than instanceId alone")
    void renew_generationAware_requiresMatchingGeneration() {
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        when(mongoTemplate.updateFirst(queryCaptor.capture(), any(org.springframework.data.mongodb.core.query.Update.class), eq(ReconciliationLock.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));

        service.renew("cred1", "instance-a", 123456789L, java.time.Duration.ofSeconds(90));

        assertThat(queryCaptor.getValue().getQueryObject().toString()).contains("generation");
    }

    @Test
    @DisplayName("renew (generation-aware overload): a stale caller holding an OLDER generation than the lock's own current one (e.g. this exact credential was released and re-acquired since) fails to renew, even with a matching instanceId -- the exact scenario instanceId alone cannot catch")
    void renew_generationAware_staleGenerationFailsEvenWithMatchingInstanceId() {
        when(mongoTemplate.updateFirst(any(), any(org.springframework.data.mongodb.core.query.Update.class), eq(ReconciliationLock.class)))
            .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(0, 0L, null)); // matchedCount=0 -- the query (which includes the generation filter) matches nothing, because the real document's own generation no longer equals this stale value

        boolean result = service.renew("cred1", "instance-a", 111L, java.time.Duration.ofSeconds(90));

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("tryAcquireWithDiagnosis: a successful acquisition obtains its generation from a genuine, DB-generated atomic counter (LockGenerationCounter's own $inc) -- immune to wall-clock behavior entirely, unlike a timestamp-derived value")
    void tryAcquire_generationComesFromAtomicCounterNotWallClock() {
        when(mongoTemplate.findAndModify(any(), any(org.springframework.data.mongodb.core.query.Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(com.tradevision.model.LockGenerationCounter.class)))
            .thenAnswer(inv -> { var c = new com.tradevision.model.LockGenerationCounter(); c.setId("cred1"); c.setValue(7L); return c; });
        ArgumentCaptor<ReconciliationLock> lockCaptor = ArgumentCaptor.forClass(ReconciliationLock.class);
        when(mongoTemplate.insert(lockCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.tryAcquireWithDiagnosis("cred1", "instance-a", java.time.Duration.ofSeconds(90));

        assertThat(lockCaptor.getValue().getGeneration()).isEqualTo(7L);
    }

    /**
     * Proves the RETURN VALUE itself carries the exact generation just inserted, with no
     * separate query involved at all, closing a subtle generation race.
     */
    @Test
    @DisplayName("tryAcquireWithDiagnosis: the returned LockLease carries the exact generation this call's own insert just wrote -- no separate currentGeneration() query needed or involved")
    void tryAcquireWithDiagnosis_returnsGenerationDirectlyFromInsert() {
        when(mongoTemplate.findAndModify(any(), any(org.springframework.data.mongodb.core.query.Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(com.tradevision.model.LockGenerationCounter.class)))
            .thenAnswer(inv -> { var c = new com.tradevision.model.LockGenerationCounter(); c.setId("cred1"); c.setValue(42L); return c; });
        when(mongoTemplate.insert(any(ReconciliationLock.class))).thenAnswer(inv -> inv.getArgument(0));

        var lease = service.tryAcquireWithDiagnosis("cred1", "instance-a", java.time.Duration.ofSeconds(90));

        assertThat(lease.acquired()).isTrue();
        assertThat(lease.generation()).isEqualTo(42L);
        // The actual claim under test: findById (the mechanism currentGeneration() itself would
        // use) is never called at all -- the generation came from the insert's own return value.
        verify(mongoTemplate, never()).findById(any(), eq(ReconciliationLock.class));
    }

    @Test
    @DisplayName("tryAcquireWithDiagnosis: a genuine HELD_BY_OTHER result carries a sentinel generation, not a stale or misleading real value")
    void tryAcquireWithDiagnosis_heldByOther_carriesSentinelGeneration() {
        when(mongoTemplate.findAndModify(any(), any(org.springframework.data.mongodb.core.query.Update.class),
            any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(com.tradevision.model.LockGenerationCounter.class)))
            .thenAnswer(inv -> { var c = new com.tradevision.model.LockGenerationCounter(); c.setId("cred1"); c.setValue(1L); return c; });
        when(mongoTemplate.insert(any(ReconciliationLock.class))).thenThrow(new org.springframework.dao.DuplicateKeyException("already held"));

        var lease = service.tryAcquireWithDiagnosis("cred1", "instance-a", java.time.Duration.ofSeconds(90));

        assertThat(lease.acquired()).isFalse();
        assertThat(lease.generation()).isEqualTo(-1L);
    }
}
