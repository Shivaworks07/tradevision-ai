package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The durable simulated cash balance for a PAPER-mode credential, one document per credential
 * (id = credentialId, the same key BrokerCredentialService's own paperBrokerAdaptersByCredential
 * map uses to give each PAPER credential its own isolated simulated balance). Loaded on
 * construction and updated after every simulated fill, so a long-running paper-trading
 * rehearsal survives a deploy or restart instead of silently resetting to its starting balance.
 */
@Data @NoArgsConstructor
@Document(collection = "paper_account_balances")
public class PaperAccountBalance {
    @Id
    private String credentialId;
    private BigDecimal balanceUsdt;
    /**
     * Simulated holdings per base asset (uppercase symbol, e.g. "BTC"), persisted alongside the
     * USDT balance so a PAPER credential's holdings survive a restart exactly like cash does.
     * This is what lets PaperBrokerAdapter reject a simulated SELL as an oversell when the
     * credential doesn't actually hold enough of that asset, rather than always filling.
     */
    private java.util.Map<String, BigDecimal> assetBalances = new java.util.HashMap<>();
    private LocalDateTime updatedAt = LocalDateTime.now();
}
