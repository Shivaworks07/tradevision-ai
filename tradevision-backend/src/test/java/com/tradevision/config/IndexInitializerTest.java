package com.tradevision.config;

import com.tradevision.model.Order;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Review finding ("Order.clientOrderId needs a unique DB constraint" -- P0, full context in
 * this class's own javadoc on why @Indexed annotations alone don't create anything in this
 * codebase's deliberate explicit-index-creation design): no test file existed for this class at
 * all before this fix, despite its own javadoc stating plainly that this application's
 * distributed correctness depends on it actually running. Covers the two new indexes added for
 * this fix, and confirms the sparse/non-sparse distinction is genuinely wired through to the
 * real Index object, not just present in a method call that silently drops the flag.
 *
 * Correction (P3-11 pass, external review, second pass, re-audit): this class's own javadoc used
 * to carry an "HONEST LIMITATION" claiming Maven Central was blocked in this sandbox and this file
 * could never actually be compiled or run here, only reasoned about against Index's documented
 * API. That claim is now confirmed stale, not aspirational -- the real spring-data-mongodb-4.2.5
 * jar is present in this environment's local Maven repository and this file runs against it for
 * real (`mvn -o test -Dtest=IndexInitializerTest`), which is exactly how the P3-11 regression in
 * this file's own two compound-index tests was caught: they asserted `sparse=true` on the real
 * Index object, and continuing to pass with that assertion is what let the underlying
 * sparse-vs-partial bug go undetected as long as it did. Left here as a pointed reminder rather
 * than silently deleted: an "I can't verify this against the real framework" disclaimer is only
 * honest for as long as it stays true, and this file's own history is the concrete example of why
 * it's worth re-checking rather than copy-pasted forward once the constraint that justified it no
 * longer holds.
 */
@ExtendWith(MockitoExtension.class)
class IndexInitializerTest {

    @Mock MongoTemplate mongoTemplate;
    @Mock IndexOperations indexOps;
    @Mock StartupState startupState;

    @InjectMocks IndexInitializer initializer;

    @Test
    @DisplayName("ensureCriticalIndexes: creates a plain (non-sparse) unique index on Order.clientOrderId -- always set at create() time, so no sparse exception is needed")
    void ensuresUniqueIndexOnOrderClientOrderId() {
        when(mongoTemplate.indexOps(Order.class)).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("ok");

        initializer.ensureCriticalIndexes();

        ArgumentCaptor<Index> captor = ArgumentCaptor.forClass(Index.class);
        verify(indexOps, atLeastOnce()).ensureIndex(captor.capture());
        boolean foundClientOrderIdIndex = captor.getAllValues().stream()
            .anyMatch(idx -> idx.getIndexKeys().containsKey("clientOrderId")
                && Boolean.TRUE.equals(idx.getIndexOptions().get("unique"))
                && !Boolean.TRUE.equals(idx.getIndexOptions().get("sparse")));
        assertThat(foundClientOrderIdIndex).isTrue();
    }

    @Test
    @DisplayName("P3-11 fix (\"The new order-ID index will block trading on a symbol after one rejected order\" -- external review, second pass, re-audit): creates a PARTIAL (not sparse) unique COMPOUND index on Order.{credentialId,symbol,brokerOrderId} -- sparse=true on a compound index does NOT exclude brokerOrderId=null documents (credentialId/symbol are always set), so only a genuine partialFilterExpression actually gives this the intended \"unique once known\" semantics")
    void ensuresPartialUniqueCompoundIndexOnOrderBrokerOrderId() {
        when(mongoTemplate.indexOps(Order.class)).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("ok");

        initializer.ensureCriticalIndexes();

        ArgumentCaptor<Index> captor = ArgumentCaptor.forClass(Index.class);
        verify(indexOps, atLeastOnce()).ensureIndex(captor.capture());
        boolean foundPartialCompoundBrokerOrderIdIndex = captor.getAllValues().stream()
            .anyMatch(idx -> idx.getIndexKeys().containsKey("brokerOrderId")
                && idx.getIndexKeys().containsKey("credentialId")
                && idx.getIndexKeys().containsKey("symbol")
                && Boolean.TRUE.equals(idx.getIndexOptions().get("unique"))
                && !Boolean.TRUE.equals(idx.getIndexOptions().get("sparse"))
                && idx.getIndexOptions().get("partialFilterExpression") != null
                && idx.getIndexOptions().get("partialFilterExpression").toString().contains("brokerOrderId"));
        assertThat(foundPartialCompoundBrokerOrderIdIndex).isTrue();
    }

    /**
     * Second re-audit fix ("Old unique indexes are never removed... Don't drop and recreate on
     * every start" -- external review, third pass, full context in
     * IndexInitializer.ensureUniquePartialCompoundIndex's own updated javadoc): replaces the
     * former unconditional-drop-every-startup test below -- that behavior no longer exists.
     * Proves the actual new contract: an existing same-named index with the WRONG definition
     * (here, still sparse rather than a genuine partial filter -- exactly what the old, buggy
     * index actually looked like on a real database) IS dropped before being recreated.
     */
    @Test
    @DisplayName("ensureUniquePartialCompoundIndex: an existing same-named index with the WRONG definition (still sparse, no partial filter) is dropped before being recreated")
    void mismatchedExistingBrokerOrderIdCompoundIndex_isDroppedBeforeRecreating() {
        when(mongoTemplate.indexOps(Order.class)).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("ok");
        Document staleIndexDoc = new Document("key", new Document("credentialId", 1).append("symbol", 1).append("brokerOrderId", 1))
            .append("name", "credential_symbol_brokerOrderId_unique")
            .append("unique", true)
            .append("sparse", true); // the OLD, actually-broken definition -- no partialFilterExpression at all
        when(indexOps.getIndexInfo()).thenReturn(java.util.List.of(IndexInfo.indexInfoOf(staleIndexDoc)));

        initializer.ensureCriticalIndexes();

        verify(indexOps).dropIndex("credential_symbol_brokerOrderId_unique");
        // ...and still recreated correctly afterward.
        ArgumentCaptor<Index> captor = ArgumentCaptor.forClass(Index.class);
        verify(indexOps, atLeastOnce()).ensureIndex(captor.capture());
        assertThat(captor.getAllValues().stream().anyMatch(idx -> idx.getIndexKeys().containsKey("brokerOrderId")
            && idx.getIndexOptions().get("partialFilterExpression") != null)).isTrue();
    }

    /**
     * The other half of the same new contract: an existing same-named index that ALREADY has the
     * correct definition (unique, matching keys, matching partial filter) is left completely
     * alone -- no drop, no pointless recreate-with-identical-options churn on every startup.
     */
    @Test
    @DisplayName("ensureUniquePartialCompoundIndex: an existing same-named index that already has the CORRECT definition is left untouched -- no drop, no per-startup churn")
    void alreadyCorrectBrokerOrderIdCompoundIndex_isNeverDropped() {
        when(mongoTemplate.indexOps(Order.class)).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("ok");
        Document correctIndexDoc = new Document("key", new Document("credentialId", 1).append("symbol", 1).append("brokerOrderId", 1))
            .append("name", "credential_symbol_brokerOrderId_unique")
            .append("unique", true)
            .append("sparse", false)
            .append("partialFilterExpression", new Document("brokerOrderId", new Document("$type", "string")));
        when(indexOps.getIndexInfo()).thenReturn(java.util.List.of(IndexInfo.indexInfoOf(correctIndexDoc)));

        initializer.ensureCriticalIndexes();

        verify(indexOps, never()).dropIndex("credential_symbol_brokerOrderId_unique");
    }

    @Test
    @DisplayName("P0-5 fix: also creates a plain (non-unique) index on Order.brokerOrderId alone, for the few callers that intentionally can't supply credentialId/symbol")
    void ensuresPlainIndexOnOrderBrokerOrderIdAlone() {
        when(mongoTemplate.indexOps(Order.class)).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("ok");

        initializer.ensureCriticalIndexes();

        ArgumentCaptor<Index> captor = ArgumentCaptor.forClass(Index.class);
        verify(indexOps, atLeastOnce()).ensureIndex(captor.capture());
        boolean foundPlainBrokerOrderIdIndex = captor.getAllValues().stream()
            .anyMatch(idx -> idx.getIndexKeys().keySet().equals(java.util.Set.of("brokerOrderId"))
                && !Boolean.TRUE.equals(idx.getIndexOptions().get("unique")));
        assertThat(foundPlainBrokerOrderIdIndex).isTrue();
    }

    @Test
    @DisplayName("P3-11 fix: creates a PARTIAL (not sparse) unique COMPOUND index on Position.{credentialId,symbol,entryOrderId} -- same reasoning and same fix as Order.brokerOrderId above")
    void ensuresPartialUniqueCompoundIndexOnPositionEntryOrderId() {
        when(mongoTemplate.indexOps(com.tradevision.model.Position.class)).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("ok");

        initializer.ensureCriticalIndexes();

        ArgumentCaptor<Index> captor = ArgumentCaptor.forClass(Index.class);
        verify(indexOps, atLeastOnce()).ensureIndex(captor.capture());
        boolean foundPartialCompoundEntryOrderIdIndex = captor.getAllValues().stream()
            .anyMatch(idx -> idx.getIndexKeys().containsKey("entryOrderId")
                && idx.getIndexKeys().containsKey("credentialId")
                && idx.getIndexKeys().containsKey("symbol")
                && Boolean.TRUE.equals(idx.getIndexOptions().get("unique"))
                && !Boolean.TRUE.equals(idx.getIndexOptions().get("sparse"))
                && idx.getIndexOptions().get("partialFilterExpression") != null
                && idx.getIndexOptions().get("partialFilterExpression").toString().contains("entryOrderId"));
        assertThat(foundPartialCompoundEntryOrderIdIndex).isTrue();
    }

    @Test
    @DisplayName("ensureUniquePartialCompoundIndex: same inspect-before-migrate contract on Position.entryOrderId -- a wrong existing definition is dropped and recreated")
    void mismatchedExistingEntryOrderIdCompoundIndex_isDroppedBeforeRecreating() {
        when(mongoTemplate.indexOps(com.tradevision.model.Position.class)).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("ok");
        Document staleIndexDoc = new Document("key", new Document("credentialId", 1).append("symbol", 1).append("entryOrderId", 1))
            .append("name", "credential_symbol_entryOrderId_unique")
            .append("unique", true)
            .append("sparse", true);
        when(indexOps.getIndexInfo()).thenReturn(java.util.List.of(IndexInfo.indexInfoOf(staleIndexDoc)));

        initializer.ensureCriticalIndexes();

        verify(indexOps).dropIndex("credential_symbol_entryOrderId_unique");
    }

    /**
     * Second re-audit fix ("Old unique indexes are never removed" -- external review, third
     * pass, item #1 of its own "before real money" list, full context in
     * IndexInitializer.migrateLegacySingleFieldUniqueIndex's own javadoc): the actual regression
     * this fixes -- a genuinely OLD single-field unique index this codebase's own earlier
     * revision created directly on brokerOrderId (before the compound-index fix existed at all),
     * which enforces GLOBAL uniqueness on its own regardless of the newer, correctly-scoped
     * compound index sitting alongside it. Proves it's found by inspecting real index metadata
     * (a single-field, unique=true index on exactly this field -- not name-guessed) and dropped.
     */
    @Test
    @DisplayName("migrateLegacySingleFieldUniqueIndex: an OLD single-field unique index on Order.brokerOrderId (from before the compound-index fix existed) is found and dropped")
    void legacySingleFieldUniqueBrokerOrderIdIndex_isDropped() {
        when(mongoTemplate.indexOps(Order.class)).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("ok");
        Document legacyIndexDoc = new Document("key", new Document("brokerOrderId", 1))
            .append("name", "brokerOrderId_1")
            .append("unique", true)
            .append("sparse", true);
        when(indexOps.getIndexInfo()).thenReturn(java.util.List.of(IndexInfo.indexInfoOf(legacyIndexDoc)));

        initializer.ensureCriticalIndexes();

        verify(indexOps).dropIndex("brokerOrderId_1");
    }

    @Test
    @DisplayName("migrateLegacySingleFieldUniqueIndex: no legacy single-field unique index exists (fresh database, or already migrated) -- nothing is dropped for it")
    void noLegacySingleFieldUniqueIndex_nothingDropped() {
        when(mongoTemplate.indexOps(any(Class.class))).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("ok");
        // getIndexInfo() left unstubbed -- Mockito's own default for an unstubbed List-returning
        // method is an empty list (not null), the realistic "no existing indexes visible" case.

        initializer.ensureCriticalIndexes();

        verify(indexOps, never()).dropIndex("brokerOrderId_1");
        verify(indexOps, never()).dropIndex("entryOrderId_1");
    }

    @Test
    @DisplayName("migrateLegacySingleFieldUniqueIndex: a NON-unique single-field index on brokerOrderId (the correct, already-migrated plain lookup index) is left alone -- only a unique one is a legacy migration target")
    void nonUniqueSingleFieldBrokerOrderIdIndex_isNotTreatedAsLegacy() {
        when(mongoTemplate.indexOps(Order.class)).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("ok");
        Document plainIndexDoc = new Document("key", new Document("brokerOrderId", 1))
            .append("name", "brokerOrderId_1")
            .append("unique", false);
        when(indexOps.getIndexInfo()).thenReturn(java.util.List.of(IndexInfo.indexInfoOf(plainIndexDoc)));

        initializer.ensureCriticalIndexes();

        verify(indexOps, never()).dropIndex("brokerOrderId_1");
    }

    @Test
    @DisplayName("ensureCriticalIndexes: a single index creation failing (e.g. pre-existing duplicate data) is logged loudly but never thrown -- one bad index must not prevent every OTHER critical index from being confirmed")
    void indexCreationFailure_isLoggedNotThrown_andOtherIndexesStillAttempted() {
        when(mongoTemplate.indexOps(any(Class.class))).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenThrow(new RuntimeException("simulated duplicate key data"));

        // Must not throw -- a failed index confirmation is a loud log, not a startup crash.
        initializer.ensureCriticalIndexes();

        // Every entity type this method is responsible for still got at least one attempt,
        // despite every single one throwing.
        verify(mongoTemplate, atLeastOnce()).indexOps(Order.class);
        verify(mongoTemplate, atLeastOnce()).indexOps(com.tradevision.model.User.class);
    }

    /**
     * Review finding ("Critical Mongo unique-index failures do not stop the application" --
     * external review, twenty-first pass, P0, full context in this class's own updated
     * ensureCriticalIndexes comment): the actual tests proving StartupState is correctly told
     * about a safety-critical index failure -- same HONEST LIMITATION as every other test in
     * this file, stated in this file's own top-level javadoc.
     */
    @Test
    @DisplayName("ensureCriticalIndexes: when every index succeeds, reports success to StartupState")
    void allIndexesSucceed_reportsSuccessToStartupState() {
        when(mongoTemplate.indexOps(any(Class.class))).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("ok");

        initializer.ensureCriticalIndexes();

        verify(startupState).markCriticalIndexesResult(true);
    }

    @Test
    @DisplayName("ensureCriticalIndexes: when even ONE safety-critical index fails, reports failure to StartupState -- autonomous trading must not start regardless of every other index succeeding")
    void oneIndexFails_reportsFailureToStartupState() {
        when(mongoTemplate.indexOps(any(Class.class))).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("ok");
        // Isolate exactly one safety-critical index's own failure -- every other entity type
        // this method attempts still succeeds via the generic stub above.
        var failingIndexOps = mock(IndexOperations.class);
        when(failingIndexOps.ensureIndex(any())).thenThrow(new RuntimeException("simulated duplicate key data"));
        when(mongoTemplate.indexOps(com.tradevision.model.RiskProfile.class)).thenReturn(failingIndexOps);

        initializer.ensureCriticalIndexes();

        verify(startupState).markCriticalIndexesResult(false);
    }

    /**
     * Review finding ("Mongo standalone deployment still weakens the plan/profile execution
     * atomicity guarantee" -- external review, twenty-fourth pass, P1, full context in
     * checkMongoTransactionSupport's own javadoc): the actual test proving the new wiring --
     * same HONEST LIMITATION as every other test in this file, stated in this file's own
     * top-level javadoc. Does not attempt to mock the real ClientSession transaction
     * success/failure paths (a deep, framework-specific area) -- confirms the wiring itself
     * calls markMongoTransactionsResult with SOME real boolean result, not that it crashes or
     * is silently skipped.
     */
    @Test
    @DisplayName("ensureCriticalIndexes: also reports Mongo transaction support to StartupState, independent of the index results")
    void ensureCriticalIndexes_alsoReportsMongoTransactionSupport() {
        when(mongoTemplate.indexOps(any(Class.class))).thenReturn(indexOps);
        when(indexOps.ensureIndex(any())).thenReturn("ok");

        initializer.ensureCriticalIndexes();

        verify(startupState).markMongoTransactionsResult(anyBoolean());
    }
}
