package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * P2-4 fix ("PaperBrokerAdapter: in-memory balance resets to 100k on restart" -- external
 * review, confirmed real by direct inspection: PaperBrokerAdapter.simulatedUsdtBalance was an
 * AtomicReference<BigDecimal> ONLY, with no durable backing at all -- a genuinely long-running
 * paper-trading rehearsal (the entire point of PAPER mode, per PaperBrokerAdapter's own class
 * javadoc: "a meaningful rehearsal of real strategy behavior") would silently and repeatedly
 * reset to the same starting 100,000 USDT on every deploy/restart, with no record that this ever
 * happened -- exactly the kind of quiet data loss that makes a paper-trading track record
 * meaningless. This is the durable record: one document per PAPER credential (id = credentialId,
 * the same key BrokerCredentialService's own paperBrokerAdaptersByCredential map already uses to
 * give each PAPER credential its own isolated simulated balance -- see that field's own updated
 * javadoc), loaded on construction and updated after every simulated fill.
 */
@Data @NoArgsConstructor
@Document(collection = "paper_account_balances")
public class PaperAccountBalance {
    @Id
    private String credentialId;
    private BigDecimal balanceUsdt;
    private LocalDateTime updatedAt = LocalDateTime.now();
}
