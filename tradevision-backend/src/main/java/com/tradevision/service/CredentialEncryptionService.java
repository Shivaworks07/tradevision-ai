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
 * P2-6 fix ("CredentialEncryptionService: no key id/version, no AAD, no rotation path" --
 * external review, confirmed real by direct inspection before this fix: every ciphertext was a
 * bare base64([12-byte IV][GCM ciphertext+tag]) with no indication of WHICH key encrypted it,
 * no binding to what field/record it belongs to, and no way to introduce a new key without
 * making every existing ciphertext undecryptable overnight): all three addressed below, with a
 * new versioned envelope format that stays able to decrypt every ciphertext this class ever
 * produced under the OLD format, since real BrokerCredential rows already exist in production
 * under it — this fix cannot afford to silently strand them.
 *
 * NEW ciphertext format: {@code "v2:<keyId>:<base64(iv+ciphertext)>"}. The literal ':' delimiter
 * is never a valid character in the OLD format's plain base64 output (Base64's own alphabet is
 * exactly [A-Za-z0-9+/=], confirmed before relying on this), so decrypt() can tell the two
 * formats apart unambiguously by checking for it — no separate migration flag or schema change
 * needed. An encoded value with no ':' is decrypted via the untouched OLD code path (single
 * fixed key, no AAD, no keyId) exactly as before this fix; every new encrypt() call always
 * produces the NEW format, under the CURRENT key only.
 *
 * KEY ID / VERSION: app.encryption.key-id names the currently ACTIVE key (defaults to "v1" if
 * unset, so an existing deployment that only ever set app.encryption.key keeps working
 * unchanged). app.encryption.previous-keys optionally lists retired keys as
 * "keyId1=base64key1,keyId2=base64key2" — used for DECRYPTING ciphertext still under an old key
 * after a rotation, never for encrypting anything new.
 *
 * AAD (Associated Additional Data): callers now pass a context string (BrokerCredentialService
 * passes "apiKey" or "apiSecret") that GCM authenticates but never encrypts. This is what closes
 * "no AAD" from this finding: a ciphertext GCM-authenticated under context="apiSecret" fails
 * decryption outright (AEADBadTagException) if presented as an apiKey's own ciphertext -- an
 * attacker (or a bug) that swaps which encrypted field ends up in which database column cannot
 * silently succeed, only fail loudly.
 *
 * ROTATION PATH: reencryptWithCurrentKey() is the actual primitive this finding's "no rotation
 * path" asks for -- decrypts whatever key/format a ciphertext is currently under and re-encrypts
 * it with the CURRENT active key. Deliberately NOT wired into an automatic scheduled job in this
 * pass (that needs its own decision about how to safely iterate every BrokerCredential row
 * without blocking real trading traffic, a materially larger change than this fix attempts) --
 * disclosed here rather than silently left unusable: an operator (or a future scheduled task)
 * can call this once app.encryption.key/-key-id are rotated to migrate existing rows off the
 * retired key, one credential at a time, using BrokerCredentialService's own existing decrypt/
 * re-save machinery.
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
     * P2-6 fix, full context in this class's own header javadoc: optional retired keys, used only
     * to DECRYPT ciphertext still under an old key after app.encryption.key/-key-id are rotated to
     * a new value. Format: "keyId1=base64key1,keyId2=base64key2". Empty/unset by default -- a
     * deployment that has never rotated its key never needs this populated.
     */
    @Value("${app.encryption.previous-keys:}")
    private String previousKeysRaw;

    private final SecureRandom random = new SecureRandom();
    private Map<String, SecretKeySpec> previousKeysById;

    public String encrypt(String plaintext) {
        return encrypt(plaintext, DEFAULT_CONTEXT);
    }

    /**
     * P2-6 fix, full context in this class's own header javadoc: always produces the NEW,
     * versioned+keyed+AAD-bound format, under the CURRENT active key only. context is
     * authenticated (via GCM's AAD) but never encrypted or stored in cleartext elsewhere --
     * decrypt() must be called with this exact same context to succeed.
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
     * P2-6 fix, full context in this class's own header javadoc: transparently handles both the
     * NEW "v2:<keyId>:<payload>" format (this class's own current output) and every OLD, bare
     * base64 ciphertext this class ever produced before this fix (real BrokerCredential rows
     * already exist in production under it) -- distinguished by the literal ':' delimiter, which
     * is never a valid character in plain base64 output.
     */
    public String decrypt(String encoded, String context) {
        if (encoded.indexOf(':') >= 0) {
            String[] parts = encoded.split(":", 3);
            if (parts.length != 3 || !FORMAT_VERSION.equals(parts[0])) {
                // P2-16 fix ("GlobalExceptionHandler.handleBadState returns IllegalStateException
                // messages to clients" -- external review, full context in
                // GlobalExceptionHandler's own updated javadoc): decrypt() runs in real request
                // paths (order placement, risk-profile resume) that end in handleBadState, which
                // returns this message to the client verbatim -- the raw envelope-version detail
                // is an internal storage-format implementation detail, not something a caller
                // needs. Logged in full server-side for whoever operates this deployment.
                log.error("Unrecognized ciphertext envelope version: '{}' (expected '{}')", parts[0], FORMAT_VERSION);
                throw new IllegalStateException("Failed to decrypt credential.");
            }
            String keyId = parts[1];
            // Deliberately OUTSIDE the try/catch below -- resolveKeyForDecryption's own
            // "no key configured" failure is an actionable, specific operator-facing error (see
            // its own javadoc) that is logged in full server-side (same P2-16 reasoning as
            // above), but no longer returned to an HTTP caller verbatim.
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

        // OLD format: no keyId, no AAD -- always decrypted with the CURRENT key, exactly as
        // this class behaved before this fix (real production ciphertexts predate key rotation
        // ever existing, so they can only ever be under the one key that was active when this
        // class had no rotation concept at all).
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
     * P2-6 fix, full context in this class's own header javadoc: the actual rotation primitive --
     * decrypts under whatever key/format the ciphertext is currently under, then re-encrypts with
     * the CURRENT active key. A no-op in effect (produces an equivalent, freshly re-encrypted
     * value) when the ciphertext is already under the current key -- safe to call unconditionally
     * during a migration pass rather than needing to first check which key a row is under.
     */
    public String reencryptWithCurrentKey(String encoded, String context) {
        return encrypt(decrypt(encoded, context), context);
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
            // P2-16 fix (full context in decrypt()'s own updated comment above): this can reach a
            // real request's caller through the activeKeyId path in resolveKeyForDecryption
            // (called outside decrypt()'s own try/catch) if the active key is ever misconfigured
            // -- logged in full server-side rather than exposed to whoever made the request.
            log.error("Encryption key '{}' must decode to exactly 32 bytes (AES-256); got {}", keyId, keyBytes.length);
            throw new IllegalStateException("Failed to decrypt credential.");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
