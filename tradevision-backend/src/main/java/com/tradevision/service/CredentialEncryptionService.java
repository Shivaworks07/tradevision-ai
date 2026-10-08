package com.tradevision.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * AES-256-GCM at-rest encryption for broker API keys/secrets — same "key from an env var,
 * never committed" pattern as JwtUtil's app.jwt.secret.
 *
 * Ciphertext format: {@code "v2:<keyId>:<base64(iv+ciphertext)>"}. The literal ':' delimiter is
 * never a valid character in plain base64 output (the Base64 alphabet is exactly
 * [A-Za-z0-9+/=]), so decrypt() can tell a versioned envelope apart from a legacy bare-base64
 * value unambiguously just by checking for it — no separate migration flag or schema change
 * needed. A legacy value with no ':' is decrypted via the old code path (single fixed key, no
 * AAD, no keyId); every new encrypt() call always produces the current versioned format, under
 * the current key.
 *
 * KEY ID / VERSION: app.encryption.key-id names the currently active key (defaults to "v1" if
 * unset, so a deployment that only ever set app.encryption.key keeps working unchanged).
 * app.encryption.previous-keys optionally lists retired keys as
 * "keyId1=base64key1,keyId2=base64key2" — used only for decrypting ciphertext still under an old
 * key after a rotation, never for encrypting anything new.
 *
 * AAD (Associated Additional Data): callers pass a context string (BrokerCredentialService
 * passes "<credentialId>:apiKey" or "<credentialId>:apiSecret") that GCM authenticates but never
 * encrypts. Binding the context to both the field name and the owning row's id means a
 * ciphertext authenticated for one field or row fails decryption outright
 * (AEADBadTagException) if presented under a different field or a different row's context — so
 * an apiSecret value can't be read back as an apiKey, and a ciphertext copied into a different
 * row (a restore from the wrong backup, a copy/paste mistake, a direct-database-access error)
 * cannot silently decrypt as if it belonged there.
 *
 * ROTATION PATH: reencryptWithCurrentKey() decrypts whatever key/format a ciphertext is
 * currently under and re-encrypts it with the current active key — a no-op in effect when the
 * ciphertext is already current, so it is safe to call unconditionally during a migration pass.
 * It is not wired into an automatic scheduled job; an operator (or a future scheduled task) can
 * call it once app.encryption.key/-key-id are rotated, to migrate existing rows off a retired
 * key one credential at a time.
 *
 * BACKWARD COMPATIBILITY FOR AAD CONTEXT: rows encrypted before row-scoped AAD context existed
 * carry no marker distinguishing "old context" from "new context" the way the ciphertext
 * envelope version does, so this can only be told apart by attempting decryption.
 * decryptWithLegacyFallback(...) tries the new, row-scoped context first; only if that specific
 * attempt fails GCM authentication (never for any other failure, such as a wrong key or
 * corrupted ciphertext) does it retry once under the old, generic field-only context, logging a
 * warning that the row is not yet migrated. This is safe because GCM authentication failure is
 * cheap, deterministic, and side-effect-free to attempt and retry.
 */
@Service
public class CredentialEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptionService.class);
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String FORMAT_VERSION = "v2";
    /** Used only as the AAD context for the legacy no-context encrypt(String)/decrypt(String) overloads some existing call sites still use. */
    private static final String DEFAULT_CONTEXT = "credential";

    @Value("${app.encryption.key}")
    private String base64Key;

    @Value("${app.encryption.key-id:v1}")
    private String activeKeyId;

    /**
     * Optional retired keys, used only to decrypt ciphertext still under an old key after
     * app.encryption.key/-key-id are rotated to a new value. Format:
     * "keyId1=base64key1,keyId2=base64key2". Empty/unset by default — a deployment that has
     * never rotated its key never needs this populated.
     */
    @Value("${app.encryption.previous-keys:}")
    private String previousKeysRaw;

    private final SecureRandom random = new SecureRandom();
    private Map<String, SecretKeySpec> previousKeysById;

    public String encrypt(String plaintext) {
        return encrypt(plaintext, DEFAULT_CONTEXT);
    }

    /**
     * Encrypts under the current active key, producing the versioned, keyed, AAD-bound
     * ciphertext format. context is authenticated (via GCM's AAD) but never encrypted or stored
     * in cleartext elsewhere — decrypt() must be called with this exact same context to succeed.
     */
    public String encrypt(String plaintext, String context) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKeyFor(activeKeyId, base64Key), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv).put(ciphertext);
            String payload = Base64.getEncoder().encodeToString(buffer.array());
            return FORMAT_VERSION + ":" + activeKeyId + ":" + payload;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt credential", e);
        }
    }

    public String decrypt(String encoded) {
        return decrypt(encoded, DEFAULT_CONTEXT);
    }

    /**
     * Transparently handles both the current "v2:<keyId>:<payload>" format and legacy bare
     * base64 ciphertext — distinguished by the literal ':' delimiter, which is never a valid
     * character in plain base64 output.
     */
    public String decrypt(String encoded, String context) {
        if (encoded.indexOf(':') >= 0) {
            String[] parts = encoded.split(":", 3);
            if (parts.length != 3 || !FORMAT_VERSION.equals(parts[0])) {
                // decrypt() runs in real request paths (order placement, risk-profile resume)
                // whose exception handling can return this message to the client verbatim, so
                // the raw envelope-version detail — an internal storage-format implementation
                // detail — stays out of the thrown message and is logged in full server-side
                // instead, for whoever operates this deployment.
                log.error("Unrecognized ciphertext envelope version: '{}' (expected '{}')", parts[0], FORMAT_VERSION);
                throw new IllegalStateException("Failed to decrypt credential.");
            }
            String keyId = parts[1];
            // Deliberately outside the try/catch below -- resolveKeyForDecryption's own
            // "no key configured" failure is an actionable, specific operator-facing error that
            // is logged in full server-side, but should not be returned to an HTTP caller
            // verbatim.
            SecretKeySpec key = resolveKeyForDecryption(keyId);
            try {
                byte[] combined = Base64.getDecoder().decode(parts[2]);
                byte[] iv = new byte[GCM_IV_LENGTH];
                byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
                System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
                System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
                cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
                byte[] plaintext = cipher.doFinal(ciphertext);
                return new String(plaintext, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to decrypt credential", e);
            }
        }

        // Legacy format: no keyId, no AAD -- always decrypted with the current key, since any
        // ciphertext in this format predates key rotation and can only be under the one key
        // that was active before rotation existed.
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKeyFor(activeKeyId, base64Key), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt credential", e);
        }
    }

    /**
     * The key-rotation primitive: decrypts under whatever key/format the ciphertext is
     * currently under, then re-encrypts with the current active key. A no-op in effect (produces
     * an equivalent, freshly re-encrypted value) when the ciphertext is already under the
     * current key -- safe to call unconditionally during a migration pass rather than needing to
     * first check which key a row is under.
     */
    public String reencryptWithCurrentKey(String encoded, String context) {
        return encrypt(decrypt(encoded, context), context);
    }

    /**
     * Tries the strong, row-scoped AAD context first; falls back to the legacy, generic
     * field-only context only when the strong attempt fails GCM authentication specifically
     * (never for any other failure -- a bad key, a malformed envelope, or genuine tampering still
     * fails loudly with no retry). The fallback exists purely so a row encrypted before
     * row-scoped context existed keeps decrypting without a forced, synchronous bulk migration;
     * it logs a warning each time so the gap stays visible rather than silently tolerated
     * forever.
     */
    public String decryptWithLegacyFallback(String encoded, String strongContext, String legacyContext) {
        try {
            return decrypt(encoded, strongContext);
        } catch (IllegalStateException e) {
            if (isAuthenticationFailure(e)) {
                log.warn("Ciphertext did not decrypt under its row-scoped AAD context ('{}') -- falling back to the legacy, "
                    + "field-only context ('{}'). This row predates the 'weak AAD binding' fix and has not been migrated yet "
                    + "-- call reencryptWithCurrentKey to close the gap for it.", strongContext, legacyContext);
                return decrypt(encoded, legacyContext);
            }
            throw e;
        }
    }

    /** True only when decrypt()'s own catch-and-wrap actually hid a GCM authentication failure
     *  (wrong AAD, wrong key, or genuine tampering) -- decrypt() always preserves the real cause
     *  as this IllegalStateException's own getCause(), so that's checked directly rather than
     *  guessed at from the message text. */
    private boolean isAuthenticationFailure(IllegalStateException e) {
        return e.getCause() instanceof javax.crypto.AEADBadTagException;
    }

    private SecretKeySpec resolveKeyForDecryption(String keyId) {
        if (activeKeyId.equals(keyId)) {
            return secretKeyFor(activeKeyId, base64Key);
        }
        Map<String, SecretKeySpec> previous = previousKeys();
        SecretKeySpec key = previous.get(keyId);
        if (key == null) {
            log.error("No key configured for keyId '{}' -- this ciphertext cannot be decrypted. If this key was recently "
                + "retired, add it to app.encryption.previous-keys before removing it from rotation.", keyId);
            throw new IllegalStateException("Failed to decrypt credential.");
        }
        return key;
    }

    private synchronized Map<String, SecretKeySpec> previousKeys() {
        if (previousKeysById == null) {
            Map<String, SecretKeySpec> parsed = new HashMap<>();
            if (previousKeysRaw != null && !previousKeysRaw.isBlank()) {
                for (String entry : previousKeysRaw.split(",")) {
                    String trimmed = entry.trim();
                    if (trimmed.isEmpty()) continue;
                    int eq = trimmed.indexOf('=');
                    if (eq < 0) {
                        log.error("Malformed app.encryption.previous-keys entry (expected keyId=base64key): '{}' -- ignoring it.", trimmed);
                        continue;
                    }
                    String keyId = trimmed.substring(0, eq);
                    String keyValue = trimmed.substring(eq + 1);
                    try {
                        parsed.put(keyId, secretKeyFor(keyId, keyValue));
                    } catch (Exception e) {
                        log.error("Could not load previous encryption key '{}' from app.encryption.previous-keys -- ignoring it: {}", keyId, e.getMessage());
                    }
                }
            }
            previousKeysById = parsed;
        }
        return previousKeysById;
    }

    private SecretKeySpec secretKeyFor(String keyId, String base64KeyValue) {
        byte[] keyBytes = Base64.getDecoder().decode(base64KeyValue);
        if (keyBytes.length != 32) {
            // This can reach a real request's caller through the activeKeyId path in
            // resolveKeyForDecryption (called outside decrypt()'s own try/catch) if the active
            // key is ever misconfigured -- logged in full server-side rather than exposed to
            // whoever made the request.
            log.error("Encryption key '{}' must decode to exactly 32 bytes (AES-256); got {}", keyId, keyBytes.length);
            throw new IllegalStateException("Failed to decrypt credential.");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
