package com.tradevision.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers key id/version, AAD binding and the key rotation path, plus the one thing these must
 * never break -- older ciphertext produced in the bare-base64 format must still decrypt
 * correctly (real BrokerCredential rows already exist in production under that format).
 */
class CredentialEncryptionServiceTest {

    private static final String KEY_V1 = "FVzfJYk/8RCI4V0V1Jy3vk50Ni7fJg1GspyINhozLLk=";
    private static final String KEY_V2 = Base64.getEncoder().encodeToString(new byte[32]); // deliberately different bytes from KEY_V1 (all-zero vs random) -- only needs to be a valid, distinct 32-byte AES-256 key

    private CredentialEncryptionService service;

    @BeforeEach
    void setUp() {
        service = new CredentialEncryptionService();
        ReflectionTestUtils.setField(service, "base64Key", KEY_V1);
        ReflectionTestUtils.setField(service, "activeKeyId", "v1");
        ReflectionTestUtils.setField(service, "previousKeysRaw", "");
    }

    @Test
    @DisplayName("encrypt/decrypt round-trips a plaintext value")
    void roundTrips() {
        String encrypted = service.encrypt("my-secret-value", "apiKey");
        assertThat(encrypted).isNotEqualTo("my-secret-value");
        assertThat(service.decrypt(encrypted, "apiKey")).isEqualTo("my-secret-value");
    }

    @Test
    @DisplayName("new ciphertext is versioned and carries the active keyId, not a bare base64 blob")
    void encrypt_producesVersionedEnvelopeWithKeyId() {
        String encrypted = service.encrypt("my-secret-value", "apiKey");

        assertThat(encrypted).startsWith("v2:v1:");
    }

    @Test
    @DisplayName("decrypting with the WRONG context fails outright, never silently returns the wrong plaintext or succeeds")
    void decrypt_wrongContext_fails() {
        String encrypted = service.encrypt("my-api-key-value", "apiKey");

        assertThatThrownBy(() -> service.decrypt(encrypted, "apiSecret"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a ciphertext encrypted under one context cannot be swapped into another field's own decrypt call and succeed")
    void decrypt_ciphertextSwappedBetweenFields_fails() {
        String apiKeyCiphertext = service.encrypt("real-api-key", "apiKey");
        String apiSecretCiphertext = service.encrypt("real-api-secret", "apiSecret");

        // Simulates an apiKey's own ciphertext ending up read back as if it were the apiSecret
        // column (a swapped assignment, a migration bug, or a database row copied incorrectly).
        assertThatThrownBy(() -> service.decrypt(apiKeyCiphertext, "apiSecret")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.decrypt(apiSecretCiphertext, "apiKey")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Two encryptions of the same plaintext produce different ciphertext (random IV per call)")
    void encrypt_sameInputTwice_producesDifferentCiphertext() {
        String first = service.encrypt("same-value", "apiKey");
        String second = service.encrypt("same-value", "apiKey");

        assertThat(first).isNotEqualTo(second);
        assertThat(service.decrypt(first, "apiKey")).isEqualTo("same-value");
        assertThat(service.decrypt(second, "apiKey")).isEqualTo("same-value");
    }

    @Test
    @DisplayName("a ciphertext in the OLD, bare-base64 format (no version, no keyId, no AAD) still decrypts correctly -- real production BrokerCredential rows already exist under this exact format and must never be stranded")
    void decrypt_legacyFormatCiphertext_stillDecryptsCorrectly() throws Exception {
        String legacyCiphertext = encryptUsingOldFormat("legacy-plaintext-value", KEY_V1);
        assertThat(legacyCiphertext).doesNotContain(":"); // confirms this really is the old, bare-base64 shape

        String decrypted = service.decrypt(legacyCiphertext);

        assertThat(decrypted).isEqualTo("legacy-plaintext-value");
    }

    @Test
    @DisplayName("after rotating to a new active key, a ciphertext still under the OLD (now \"previous\") key decrypts correctly when that key is listed in app.encryption.previous-keys")
    void decrypt_afterKeyRotation_stillDecryptsCiphertextUnderPreviousKey() {
        String encryptedUnderV1 = service.encrypt("value-from-before-rotation", "apiKey");

        // Simulates an actual key rotation: v1 retired to "previous", v2 becomes active.
        ReflectionTestUtils.setField(service, "base64Key", KEY_V2);
        ReflectionTestUtils.setField(service, "activeKeyId", "v2");
        ReflectionTestUtils.setField(service, "previousKeysRaw", "v1=" + KEY_V1);
        ReflectionTestUtils.setField(service, "previousKeysById", null); // force the lazily-cached map to re-parse

        String decrypted = service.decrypt(encryptedUnderV1, "apiKey");

        assertThat(decrypted).isEqualTo("value-from-before-rotation");
    }

    @Test
    @DisplayName("reencryptWithCurrentKey migrates a ciphertext from a retired key to the current active key, and the result decrypts to the same plaintext")
    void reencryptWithCurrentKey_migratesToActiveKey() {
        String encryptedUnderV1 = service.encrypt("value-to-migrate", "apiKey");

        ReflectionTestUtils.setField(service, "base64Key", KEY_V2);
        ReflectionTestUtils.setField(service, "activeKeyId", "v2");
        ReflectionTestUtils.setField(service, "previousKeysRaw", "v1=" + KEY_V1);
        ReflectionTestUtils.setField(service, "previousKeysById", null);

        String reencrypted = service.reencryptWithCurrentKey(encryptedUnderV1, "apiKey");

        assertThat(reencrypted).startsWith("v2:v2:"); // now under the CURRENT key, not the retired one
        assertThat(service.decrypt(reencrypted, "apiKey")).isEqualTo("value-to-migrate");
    }

    @Test
    @DisplayName("decrypting a ciphertext under a keyId this instance has no key configured for (neither active nor previous) fails clearly, without leaking the keyId to the caller")
    void decrypt_unknownKeyId_failsClearly() {
        String encrypted = service.encrypt("some-value", "apiKey");
        // Rotate away from v1 entirely, without ever listing it in previous-keys -- simulates an
        // operator retiring a key too early, before every row has been migrated off it.
        ReflectionTestUtils.setField(service, "base64Key", KEY_V2);
        ReflectionTestUtils.setField(service, "activeKeyId", "v2");
        ReflectionTestUtils.setField(service, "previousKeysRaw", "");
        ReflectionTestUtils.setField(service, "previousKeysById", null);

        // This decrypt() call runs in real request paths (order placement, risk-profile resume)
        // that end in a handler returning IllegalStateException's message to the client
        // verbatim, so the message must stay generic. The "clear, actionable error" this test's
        // own name promises is delivered via the server-side log line resolveKeyForDecryption
        // emits instead of via the client-visible exception message.
        assertThatThrownBy(() -> service.decrypt(encrypted, "apiKey"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Failed to decrypt credential.")
            .hasMessageNotContaining("v1");
    }

    // ---------------------------------------------------------------------------------------
    // The AAD binding must be row-scoped, not just bound to a field name ("apiKey") the same
    // for every row -- these tests prove the row-scoped binding actually closes the
    // row-substitution gap, and that decryptWithLegacyFallback bridges already-persisted
    // ciphertext (old, generic context) without weakening the new binding for anything else.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a ciphertext encrypted under one row's context cannot be read back under a DIFFERENT row's context, even for the identical field name")
    void decrypt_rowScopedContext_rejectsCiphertextFromADifferentRow() {
        String credentialAApiKey = service.encrypt("real-api-key-for-credential-A", "credA:apiKey");

        // Simulates exactly the bug/attack this fix defends against: credential A's own apiKey
        // ciphertext ending up stored on, or read back as, credential B's row.
        assertThatThrownBy(() -> service.decrypt(credentialAApiKey, "credB:apiKey"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("decryptWithLegacyFallback: when the ciphertext was encrypted under the new, row-scoped context, the strong attempt alone succeeds -- no fallback needed or attempted")
    void decryptWithLegacyFallback_strongContextSucceeds_neverFallsBack() {
        String encrypted = service.encrypt("value", "credA:apiKey");

        String decrypted = service.decryptWithLegacyFallback(encrypted, "credA:apiKey", "apiKey");

        assertThat(decrypted).isEqualTo("value");
    }

    @Test
    @DisplayName("decryptWithLegacyFallback: a row encrypted under the old, generic field-only context still decrypts via the legacy fallback, without a forced bulk migration")
    void decryptWithLegacyFallback_legacyCiphertext_fallsBackSuccessfully() {
        // Simulates a real, already-persisted BrokerCredential row from before the row-scoped
        // AAD binding existed -- encrypted under the bare field name, not a row-scoped context.
        String legacyEncrypted = service.encrypt("value-from-before-the-fix", "apiKey");

        String decrypted = service.decryptWithLegacyFallback(legacyEncrypted, "credA:apiKey", "apiKey");

        assertThat(decrypted).isEqualTo("value-from-before-the-fix");
    }

    @Test
    @DisplayName("decryptWithLegacyFallback: a genuine row-substitution (ciphertext from a DIFFERENT row, also under the legacy generic context) still fails -- the legacy fallback only bridges the old-vs-new CONTEXT SHAPE, it does not reopen the row-substitution hole for rows that are already migrated")
    void decryptWithLegacyFallback_doesNotMaskRealCrossRowSubstitution() {
        // credential B's own ciphertext, encrypted under the NEW, row-scoped context for ITS row.
        String credentialBApiKey = service.encrypt("credential-B-own-value", "credB:apiKey");

        // credential A attempts to decrypt it as its own apiKey -- must fail under both the
        // strong (credA) context AND the legacy fallback (bare "apiKey"), since this ciphertext
        // was never encrypted under either of those two contexts, only credB's own.
        assertThatThrownBy(() -> service.decryptWithLegacyFallback(credentialBApiKey, "credA:apiKey", "apiKey"))
            .isInstanceOf(IllegalStateException.class);
    }

    /** Reproduces the old, bare-base64 ciphertext format, to prove it is still decryptable. */
    private static String encryptUsingOldFormat(String plaintext, String base64Key) throws Exception {
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
            new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(iv.length + ciphertext.length);
        buffer.put(iv).put(ciphertext);
        return Base64.getEncoder().encodeToString(buffer.array());
    }
}
