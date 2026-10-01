package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * P2-7 fix ("IndexInitializer: BrokerAuditLog TTL 730 days conflicts with AuditChainService's
 * hash chain" -- external review, confirmed real by direct inspection before this fix:
 * BrokerAuditLog's own 730-day TTL index (IndexInitializer.ensureTtlIndex) silently deletes the
 * OLDEST links of AuditChainService's hash chain, and AuditChainService.verifyChain never
 * checked a queried range's own FIRST record against anything -- meaning a chain that has quietly
 * lost its earliest records to TTL, and a chain someone tampered with by deleting records from
 * the middle, are INDISTINGUISHABLE to verifyChain: both simply look like "the chain starts here,"
 * silently defeating the entire point of a tamper-EVIDENT hash chain).
 *
 * This is the fix's durable anchor -- deliberately NOT subject to any TTL itself (see
 * IndexInitializer's own comment: this collection is intentionally excluded from the TTL sweep
 * that applies to BrokerAuditLog). A single, well-known document (id = SINGLETON_ID) recording
 * the hash and timestamp of the newest record AuditChainService has observed getting close to its
 * own TTL expiry. verifyChain can then treat a queried range's own first record's previousHash as
 * an actual claim to check -- when this checkpoint's own timestamp precedes that record, its
 * previousHash MUST equal this checkpoint's recordHash, or the chain is reported broken exactly
 * as it should be. See AuditChainService.checkpointRecordsNearingExpiry's own javadoc for how this
 * is kept advancing.
 *
 * HONEST SCOPE: this is a best-effort mitigation, not an absolute guarantee -- it depends on
 * AuditChainService.checkpointRecordsNearingExpiry actually running (from appendToChain, on
 * ordinary audit-log activity) somewhat more often than the 30-day safety buffer this class uses
 * before a record's real 730-day TTL expiry. An application with genuinely NO audit-log-writing
 * activity for a stretch approaching that buffer could still develop a small gap between this
 * checkpoint and TTL's actual deletion. Stated plainly, matching AuditChainService's own existing
 * "detection, not prevention" honesty about what a hash chain in this application can and cannot
 * promise.
 */
@Data @NoArgsConstructor
@Document(collection = "audit_chain_checkpoints")
public class AuditChainCheckpoint {
    public static final String SINGLETON_ID = "SINGLETON";

    @Id
    private String id = SINGLETON_ID;
    private String recordId;
    private String recordHash;
    private LocalDateTime recordTimestamp;
    private LocalDateTime updatedAt;
}
