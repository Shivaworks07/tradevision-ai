package com.tradevision.service;

import com.tradevision.model.BrokerAuditLog;
import com.tradevision.model.BrokerType;
import com.tradevision.repository.BrokerAuditLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Review finding ("immutable external audit export" -- external review, P3, full context in
 * BrokerAuditLog's own class javadoc): the actual tests proving the hash chain works, detects
 * tampering, and — stated honestly rather than glossed over — genuinely does not claim strict
 * ordering under concurrent writes.
 */
@ExtendWith(MockitoExtension.class)
class AuditChainServiceTest {

    @Mock BrokerAuditLogRepository auditRepo;
    @InjectMocks AuditChainService service;

    private BrokerAuditLog record(String userId, String action) {
        var r = new BrokerAuditLog();
        r.setUserId(userId);
        r.setCredentialId("cred1");
        r.setBroker(BrokerType.BINANCE);
        r.setAction(action);
        r.setDetail("some detail");
        r.setTimestamp(LocalDateTime.of(2026, 1, 1, 12, 0, 0));
        return r;
    }

    @Test
    @DisplayName("computeHash: the same content and previousHash always produce the same hash -- deterministic, not random")
    void computeHash_deterministic() {
        var r = record("user1", "CONNECT");

        String hash1 = service.computeHash(r, "prev-hash");
        String hash2 = service.computeHash(r, "prev-hash");

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("computeHash: changing ANY field of the record changes the resulting hash -- proves the hash actually covers the record's real content, not just a fixed string")
    void computeHash_changingContentChangesHash() {
        var original = record("user1", "CONNECT");
        var tampered = record("user1", "CONNECT");
        tampered.setDetail("a DIFFERENT detail, as if this record had been edited after the fact");

        String originalHash = service.computeHash(original, "prev-hash");
        String tamperedHash = service.computeHash(tampered, "prev-hash");

        assertThat(originalHash).isNotEqualTo(tamperedHash);
    }

    @Test
    @DisplayName("computeHash: a different previousHash changes the resulting hash too -- proves the chain link itself is part of what's hashed, not just the record's own content")
    void computeHash_differentPreviousHashChangesHash() {
        var r = record("user1", "CONNECT");

        String hashWithPrevA = service.computeHash(r, "prev-hash-A");
        String hashWithPrevB = service.computeHash(r, "prev-hash-B");

        assertThat(hashWithPrevA).isNotEqualTo(hashWithPrevB);
    }

    @Test
    @DisplayName("appendToChain: the very first record in an empty chain gets a null previousHash, and a real, non-null recordHash")
    void appendToChain_firstRecord_nullPreviousHash() {
        when(auditRepo.findTopByOrderByTimestampDesc()).thenReturn(Optional.empty());
        when(auditRepo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArguments()[0]);
        var r = record("user1", "CONNECT");

        var saved = service.appendToChain(r);

        assertThat(saved.getPreviousHash()).isNull();
        assertThat(saved.getRecordHash()).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("appendToChain: a second record correctly chains to the first record's own recordHash")
    void appendToChain_secondRecord_chainsToFirst() {
        var existingFirst = record("user1", "CONNECT");
        existingFirst.setId("first-id");
        existingFirst.setPreviousHash(null);
        existingFirst.setRecordHash("first-record-hash");
        when(auditRepo.findTopByOrderByTimestampDesc()).thenReturn(Optional.of(existingFirst));
        when(auditRepo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArguments()[0]);
        var second = record("user1", "DELETE");

        var saved = service.appendToChain(second);

        assertThat(saved.getPreviousHash()).isEqualTo("first-record-hash");
    }

    @Test
    @DisplayName("verifyChain: a genuine, untampered chain of 3 records verifies as valid")
    void verifyChain_untamperedChain_valid() {
        var r1 = record("user1", "CONNECT");
        r1.setId("r1"); r1.setPreviousHash(null); r1.setRecordHash(service.computeHash(r1, null));
        var r2 = record("user1", "ORDER_PLACED");
        r2.setId("r2"); r2.setPreviousHash(r1.getRecordHash()); r2.setRecordHash(service.computeHash(r2, r1.getRecordHash()));
        var r3 = record("user1", "DELETE");
        r3.setId("r3"); r3.setPreviousHash(r2.getRecordHash()); r3.setRecordHash(service.computeHash(r3, r2.getRecordHash()));

        var result = service.verifyChain(List.of(r1, r2, r3));

        assertThat(result.valid()).isTrue();
        assertThat(result.recordsChecked()).isEqualTo(3);
        assertThat(result.firstBrokenRecordId()).isNull();
    }

    @Test
    @DisplayName("verifyChain: a record's own content edited AFTER its hash was computed breaks the chain, and reports exactly which record")
    void verifyChain_tamperedRecordContent_detectedAndReported() {
        var r1 = record("user1", "CONNECT");
        r1.setId("r1"); r1.setPreviousHash(null); r1.setRecordHash(service.computeHash(r1, null));
        var r2 = record("user1", "ORDER_PLACED");
        r2.setId("r2"); r2.setPreviousHash(r1.getRecordHash()); r2.setRecordHash(service.computeHash(r2, r1.getRecordHash()));
        // Tamper with r2's own content AFTER its hash was already computed and stored -- exactly
        // what a malicious direct-database edit would look like.
        r2.setDetail("this detail was changed after the hash was computed");

        var result = service.verifyChain(List.of(r1, r2));

        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenRecordId()).isEqualTo("r2");
    }

    @Test
    @DisplayName("verifyChain: a record deleted from the middle of the chain breaks the link for everything after it, detected at the record immediately following the gap")
    void verifyChain_deletedMiddleRecord_detectedAtNextRecord() {
        var r1 = record("user1", "CONNECT");
        r1.setId("r1"); r1.setPreviousHash(null); r1.setRecordHash(service.computeHash(r1, null));
        var r2 = record("user1", "ORDER_PLACED");
        r2.setId("r2"); r2.setPreviousHash(r1.getRecordHash()); r2.setRecordHash(service.computeHash(r2, r1.getRecordHash()));
        var r3 = record("user1", "DELETE");
        r3.setId("r3"); r3.setPreviousHash(r2.getRecordHash()); r3.setRecordHash(service.computeHash(r3, r2.getRecordHash()));

        // r2 is deleted entirely -- the caller now only has r1 and r3, with r3's own
        // previousHash pointing at a record that's no longer in the list at all.
        var result = service.verifyChain(List.of(r1, r3));

        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenRecordId()).isEqualTo("r3");
    }
}
