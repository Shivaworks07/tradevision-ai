package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A durable anchor that lets BrokerAuditLog's hash chain stay tamper-evident even though
 * BrokerAuditLog itself has a TTL index that eventually deletes its oldest records. Without an
 * anchor, a chain that has quietly lost its earliest records to TTL and a chain someone
 * tampered with by deleting records from the middle would be indistinguishable to
 * AuditChainService.verifyChain -- both would simply look like "the chain starts here".
 *
 * Deliberately not subject to any TTL itself (see IndexInitializer's own comment: this
 * collection is intentionally excluded from the TTL sweep that applies to BrokerAuditLog). A
 * single, well-known document (id = SINGLETON_ID) records the hash and timestamp of the newest
 * record AuditChainService has observed getting close to its own TTL expiry. verifyChain then
 * treats a queried range's own first record's previousHash as an actual claim to check -- when
 * this checkpoint's own timestamp precedes that record, its previousHash must equal this
 * checkpoint's recordHash, or the chain is reported broken. See
 * AuditChainService.checkpointRecordsNearingExpiry's own javadoc for how this is kept advancing.
 *
 * This is a best-effort mitigation, not an absolute guarantee -- it depends on
 * AuditChainService.checkpointRecordsNearingExpiry actually running (from appendToChain, on
 * ordinary audit-log activity) somewhat more often than the 30-day safety buffer this class
 * uses before a record's real 730-day TTL expiry. An application with no audit-log-writing
 * activity for a stretch approaching that buffer could still develop a small gap between this
 * checkpoint and TTL's actual deletion -- this chain provides detection, not prevention.
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
