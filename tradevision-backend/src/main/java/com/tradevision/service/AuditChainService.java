package com.tradevision.service;

import com.tradevision.model.AuditChainCheckpoint;
import com.tradevision.model.BrokerAuditLog;
import com.tradevision.repository.AuditChainCheckpointRepository;
import com.tradevision.repository.BrokerAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Builds and verifies a hash chain over broker audit log records, so tampering with a stored
 * record becomes detectable. See BrokerAuditLog's own class javadoc for the full reasoning, the
 * honest scope (detection, not prevention -- not a substitute for real external WORM storage),
 * and the stated concurrent-write limitation.
 */
@Service
@RequiredArgsConstructor
public class AuditChainService {

    private static final Logger log = LoggerFactory.getLogger(AuditChainService.class);
    /**
     * How long before a record's 730-day TTL expiry (see IndexInitializer's BrokerAuditLog TTL
     * index) this service tries to have durably checkpointed it. Deliberately generous relative
     * to how often appendToChain actually runs (every audit-log write) -- this is a safety
     * margin against an unusually quiet stretch, not a tight deadline.
     */
    private static final long CHECKPOINT_SAFETY_BUFFER_DAYS = 30;
    private static final long BROKER_AUDIT_LOG_TTL_DAYS = 730;

    private final BrokerAuditLogRepository auditRepo;
    private final AuditChainCheckpointRepository checkpointRepo;

    /**
     * Computes this record's own content hash combined with the given previous hash. Pure and
     * directly testable -- does not read or write anything itself.
     */
    public String computeHash(BrokerAuditLog record, String previousHash) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            String content = String.valueOf(record.getUserId()) + '|'
                + record.getCredentialId() + '|'
                + record.getBroker() + '|'
                + record.getAction() + '|'
                + record.getDetail() + '|'
                + record.getTimestamp() + '|'
                + (previousHash != null ? previousHash : "");
            byte[] hashBytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (byte b : hashBytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory algorithm every JVM implementation must provide (JLS/JCA
            // specification) -- this is genuinely unreachable, not a real failure path to design
            // graceful degradation for.
            throw new IllegalStateException("SHA-256 unavailable -- should be impossible on any real JVM.", e);
        }
    }

    /**
     * Stamps and saves a new audit log record as the next link in the chain -- reads the
     * current most-recent record's own hash (if any), computes this record's hash against it,
     * and saves. See this class's own class javadoc for the honest concurrent-write limitation.
     */
    public BrokerAuditLog appendToChain(BrokerAuditLog record) {
        String previousHash = auditRepo.findTopByOrderByTimestampDesc().map(BrokerAuditLog::getRecordHash).orElse(null);
        record.setPreviousHash(previousHash);
        record.setRecordHash(computeHash(record, previousHash));
        BrokerAuditLog saved = auditRepo.save(record);
        // Piggybacks on every append (cheap -- one bounded query, only when the checkpoint
        // actually needs advancing) rather than needing its own separate scheduled job.
        checkpointRecordsNearingExpiry();
        return saved;
    }

    /**
     * Durably anchors the newest record that is getting close to its own TTL expiry, so
     * verifyChain can still detect tampering in a range whose true earliest records have since
     * been deleted by TTL, instead of silently treating "the chain starts wherever TTL happened
     * to leave it" as always valid. Advances the single checkpoint document forward over time as
     * older data ages out -- never moves it backward (a checkpoint older than one already
     * recorded is never overwritten with something less current).
     */
    void checkpointRecordsNearingExpiry() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(BROKER_AUDIT_LOG_TTL_DAYS - CHECKPOINT_SAFETY_BUFFER_DAYS);
        var candidate = auditRepo.findTopByTimestampBeforeOrderByTimestampDesc(cutoff);
        if (candidate.isEmpty()) return; // nothing old enough yet to need checkpointing
        BrokerAuditLog record = candidate.get();

        var existing = checkpointRepo.findById(AuditChainCheckpoint.SINGLETON_ID);
        if (existing.isPresent() && existing.get().getRecordTimestamp() != null
                && !existing.get().getRecordTimestamp().isBefore(record.getTimestamp())) {
            return; // already checkpointed this record or a newer one -- never move the checkpoint backward
        }

        var checkpoint = existing.orElseGet(AuditChainCheckpoint::new);
        checkpoint.setId(AuditChainCheckpoint.SINGLETON_ID);
        checkpoint.setRecordId(record.getId());
        checkpoint.setRecordHash(record.getRecordHash());
        checkpoint.setRecordTimestamp(record.getTimestamp());
        checkpoint.setUpdatedAt(LocalDateTime.now());
        checkpointRepo.save(checkpoint);
        log.info("Advanced audit-chain checkpoint to record {} (timestamp {}), {} days before its own TTL expiry.",
            record.getId(), record.getTimestamp(), CHECKPOINT_SAFETY_BUFFER_DAYS);
    }

    public record ChainVerificationResult(boolean valid, int recordsChecked, String firstBrokenRecordId) {}

    /**
     * Walks the given records (already ordered oldest-to-newest by the caller) and confirms
     * each one's own stored hash still matches what computeHash produces from its current
     * content, AND that each record's previousHash matches the record before it's own
     * recordHash. Either mismatch means something in that record -- or an earlier one -- has
     * been altered since it was written.
     *
     * With no checkpoint to verify the very first record's own previousHash against, that first
     * record is trusted as a legitimate chain start. Callers that have a checkpoint available
     * (AdminController.verifyAuditChain does) should use the checkpoint-aware overload below
     * instead -- this overload remains for callers/tests that genuinely have no checkpoint
     * context.
     */
    public ChainVerificationResult verifyChain(List<BrokerAuditLog> orderedOldestFirst) {
        return verifyChain(orderedOldestFirst, null);
    }

    /**
     * Checkpoint-aware verification: when a checkpoint is given and its recorded timestamp is
     * before the first record in this list, that first record's previousHash is no longer given
     * a free pass -- it must equal the checkpoint's recordHash, exactly like every other
     * record's previousHash is checked against the record before it. This is what makes a range
     * whose true earliest records were legitimately trimmed by TTL distinguishable from one
     * where a record was deleted from the middle by something else -- the former still matches
     * the checkpoint, the latter does not.
     */
    public ChainVerificationResult verifyChain(List<BrokerAuditLog> orderedOldestFirst, AuditChainCheckpoint checkpoint) {
        String expectedPreviousHash = null;
        boolean checkpointAppliesToFirstRecord = checkpoint != null && checkpoint.getRecordTimestamp() != null
            && !orderedOldestFirst.isEmpty() && checkpoint.getRecordTimestamp().isBefore(orderedOldestFirst.get(0).getTimestamp());
        if (checkpointAppliesToFirstRecord) {
            expectedPreviousHash = checkpoint.getRecordHash();
        }
        for (int i = 0; i < orderedOldestFirst.size(); i++) {
            var record = orderedOldestFirst.get(i);
            boolean mustCheckPreviousHash = i > 0 || checkpointAppliesToFirstRecord;
            if (mustCheckPreviousHash && !java.util.Objects.equals(record.getPreviousHash(), expectedPreviousHash)) {
                return new ChainVerificationResult(false, i, record.getId());
            }
            String actualHash = computeHash(record, record.getPreviousHash());
            if (!java.util.Objects.equals(actualHash, record.getRecordHash())) {
                return new ChainVerificationResult(false, i, record.getId());
            }
            expectedPreviousHash = record.getRecordHash();
        }
        return new ChainVerificationResult(true, orderedOldestFirst.size(), null);
    }
}
