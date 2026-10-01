package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A user's connection to a broker. API key/secret are stored AES-256-GCM encrypted
 * (see CredentialEncryptionService) — never in plaintext, never returned to the client.
 *
 * withdrawalEnabled is always false by construction: BrokerCredentialService checks the
 * key's real permissions against the broker before this row is ever saved, and rejects
 * outright (no save) if the broker reports withdrawal rights on the key.
 */
@Data @NoArgsConstructor
@Document(collection = "broker_credentials")
public class BrokerCredential {
    @Id private String id;

    @Indexed private String userId;

    private BrokerType broker;

    private String encryptedApiKey;
    private String encryptedApiSecret;

    /** Last 4 chars of the plaintext API key, kept only so the user can tell credentials apart in the UI. */
    private String keyHint;

    private BrokerMode mode = BrokerMode.TESTNET;
    private boolean withdrawalEnabled = false;
    private boolean active = true;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
    private LocalDateTime lastValidatedAt;

    /**
     * Review finding (P1 #9 -- "API key rotation doesn't verify it's the same Binance account"):
     * the real account identity (Binance's own `uid`) this credential was FIRST connected under
     * -- recorded once, at connect time, and never changed by a rotation. rotateApiKey compares
     * a new key's own reported uid against this exact value before accepting it, refusing a
     * rotation that would silently move this credential (and every open position/OCO/limit tied
     * to it) onto a completely different account's key. Null for any credential connected before
     * this field existed -- rotateApiKey treats that as "not yet recorded" rather than a forced
     * mismatch, and backfills it from the very rotation it allows.
     */
    private String accountUid;
}
