package com.tradevision.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2-6 fix ("CredentialEncryptionService: no key id/version, no AAD, no rotation path" --
 * external review, full context in that class's own updated header javadoc): the actual tests
 * proving the three things this fix adds, PLUS the one thing it must never break -- every
 * ciphertext this class produced before this fix must still decrypt correctly (real
 * BrokerCredential rows already exist in production under the old, bare-base64 format).
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
    @DisplayName("P2-6 fix: new ciphertext is versioned and carries the active keyId, not a bare base64 blob")
    void encrypt_producesVersionedEnvelopeWithKeyId() {
        String encrypted = service.encrypt("my-secret-value", "apiKey");

        assertThat(encrypted).startsWith("v2:v1:");
    }

    @Test
    @DisplayName("P2-6 fix (\"no AAD\"): decrypting with the WRONG context fails outright, never silently returns the wrong plaintext or succeeds")
    void decrypt_wrongContext_fails() {
        String encrypted = service.encrypt("my-api-key-value", "apiKey");

        assertThatThrownBy(() -> service.decrypt(encrypted, "apiSecret"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("P2-6 fix (\"no AAD\"): a ciphertext encrypted under one context cannot be swapped into another field's own decrypt call and succeed -- the actual cross-field-swap protection this finding asks for")
    void decrypt_ciphertextSwappedBetweenFields_fails() {
        String apiKeyCiphertext = service.encrypt("real-api-key", "apiKey");
        String apiSecretCiphertext = service.encrypt("real-api-secret", "apiSecret");

        // Simulates exactly the bug this fix defends against: an apiKey's own ciphertext ending
        // up read back as if it were the apiSecret column (a swapped assignment, a migration
        // bug, or a database row copied incorrectly).
        assertThatThrownBy(() -> service.decrypt(apiKeyCiphertext, "apiSecret")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.decrypt(apiSecretCiphertext, "apiKey")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Two encryptions of the same plaintext produce different ciphertext (random IV per call), same behavior as before this fix")
    void encrypt_sameInputTwice_producesDifferentCiphertext() {
        String first = service.encrypt("same-value", "apiKey");
        String second = service.encrypt("same-value", "apiKey");

        assertThat(first).isNotEqualTo(second);
        assertThat(service.decrypt(first, "apiKey")).isEqualTo("same-value");
        assertThat(service.decrypt(second, "apiKey")).isEqualTo("same-value");
    }

    @Test
    @DisplayName("P2-6 fix: a ciphertext in the OLD, pre-fix bare-base64 format (no version, no keyId, no AAD) still decrypts correctly -- real production BrokerCredential rows already exist under this exact format and must never be stranded by this fix")
    void decrypt_legacyFormatCiphertext_stillDecryptsCorrectly() throws Exception {
        String legacyCiphertext = encryptUsingOldFormat("legacy-plaintext-value", KEY_V1);
        assertThat(legacyCiphertext).doesNotContain(":"); // confirms this really is the old, bare-base64 shape

        String decrypted = service.decrypt(legacyCiphertext);

        assertThat(decrypted).isEqualTo("legacy-plaintext-value");
    }

    @Test
    @DisplayName("P2-6 fix (\"no rotation path\"): after rotating to a new active key, a ciphertext still under the OLD (now \"previous\") key decrypts correctly when that key is listed in app.encryption.previous-keys")
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
    @DisplayName("P2-6 fix (\"no rotation path\"): reencryptWithCurrentKey migrates a ciphertext from a retired key to the current active key, and the result decrypts to the same plaintext")
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
    @DisplayName("P2-6 fix: decrypting a ciphertext under a keyId this instance has no key configured for (neither active nor previous) fails clearly, without leaking the keyId to the caller")
    void decrypt_unknownKeyId_failsClearly() {
        String encrypted = service.encrypt("some-value", "apiKey");
        // Rotate away from v1 entirely, without ever listing it in previous-keys -- simulates an
        // operator retiring a key too early, before every row has been migrated off it.
        ReflectionTestUtils.setField(service, "base64Key", KEY_V2);
        ReflectionTestUtils.setField(service, "activeKeyId", "v2");
        ReflectionTestUtils.setField(service, "previousKeysRaw", "");
        ReflectionTestUtils.setField(service, "previousKeysById", null);

        // P2-16 fix ("GlobalExceptionHandler.handleBadState returns IllegalStateException
        // messages to clients" -- external review, full context in CredentialEncryptionService's
        // own updated resolveKeyForDecryption comment): this test previously asserted the thrown
        // message CONTAINED the missing keyId ("v1") -- exactly the kind of internal detail that
        // finding flagged, since this decrypt() call runs in real request paths (order placement,
        // risk-profile resume) that end in a handler returning IllegalStateException's message to
        // the client verbatim. The "clear, actionable error" this test's own name promises is now
        // delivered via the server-side log line resolveKeyForDecryption emits instead of via the
        // client-visible exception message, which is intentionally generic.
        assertThatThrownBy(() -> service.decrypt(encrypted, "apiKey"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Failed to decrypt credential.")
            .hasMessageNotContaining("v1");
    }

    /** Reproduces exactly what CredentialEncryptionService.encrypt() produced before this fix, to prove the old format is still decryptable. */
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
