package com.tradevision.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Immutable audit trail for anything touching broker credentials or order execution.
 * Never write key material (plaintext or encrypted) into `detail` — this collection
 * is meant to be safe to hand to the user or an auditor as-is.
 *
 * Review finding ("immutable external audit export" -- external review, P3, confirmed real by
 * direct inspection before this fix: this class's own doc comment already claimed "Immutable"
 * before this fix existed, but nothing actually enforced or verified it -- a plain MongoDB
 * document here could be silently edited or deleted, by anyone with database access, with no
 * way to ever detect it had happened): recordHash/previousHash below are the actual, achievable
 * fix -- a real, application-level hash chain, where each record's own hash is computed from
 * its own content plus the previous record's hash. Tampering with any one record changes its
 * own hash, which no longer matches what the NEXT record's previousHash claims, breaking the
 * chain from that point forward in a way BrokerAuditLogService.verifyChain can detect and
 * report. This is DETECTION, not prevention -- it does not stop someone with direct database
 * access from editing a record, and it is not a substitute for real external WORM storage
 * (Object Lock on S3, or similar), which this application has no way to provision itself; it
 * makes tampering with what's already here provable after the fact, which real external WORM
 * storage does not by itself either (that prevents deletion, this proves alteration).
 *
 * HONEST LIMITATION, stated plainly: this chain is NOT strictly, globally ordered under
 * genuinely concurrent writes. Two audit() calls racing at the exact same moment could both
 * read the same "most recent" record and both compute their own hash against it, producing two
 * records that both claim the same previousHash rather than a strict linear chain. For a single
 * application instance's own audit-writing rate, this is a rare edge case, not the common
 * case -- but it is a real one, not silently ignored here. A fully strict, globally-ordered
 * chain would need a single-writer queue or a database-enforced sequence counter, genuinely
 * larger scope than this fix attempts.
 */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
@Document(collection = "broker_audit_log")
public class BrokerAuditLog {
    @Id private String id;

    @Indexed private String userId;
    private String credentialId;
    private BrokerType broker;

    /** CONNECT, VALIDATE_REJECTED_WITHDRAWAL, VALIDATE_REJECTED_NO_TRADE, DELETE, ORDER_PLACED, ORDER_FAILED */
    private String action;
    private String detail;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    /** SHA-256 hash of the record immediately before this one in the chain -- null only for the
     *  very first record ever written. See this class's own javadoc for the full mechanism. */
    private String previousHash;
    /** SHA-256 hash of this record's own content (userId+credentialId+broker+action+detail+
     *  timestamp) combined with previousHash. See this class's own javadoc. */
    private String recordHash;
}
