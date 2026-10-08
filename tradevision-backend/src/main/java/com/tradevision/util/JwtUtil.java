package com.tradevision.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.security.MessageDigest;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtil {

    @Value("${app.jwt.secret}")      private String secret;
    @Value("${app.jwt.expiration}")  private long   expiration;      // 24h default
    @Value("${app.jwt.refresh-expiration:604800000}") private long refreshExpiration; // 7d default

    /**
     * Validates app.jwt.secret at startup rather than letting key() below discover a too-short
     * or missing secret lazily on first use, which would otherwise let this application start up
     * and appear healthy only to fail at the first real login attempt. HS256 (the weakest
     * HMAC-SHA variant Keys.hmacShaKeyFor can select) requires at least 256 bits (32 bytes) per
     * RFC 7518.
     */
    @jakarta.annotation.PostConstruct
    void validateSecretAtStartup() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.jwt.secret is not configured. A real, sufficiently long secret is required -- refusing "
                + "to start rather than fail later at the first real login attempt.");
        }
        int byteLength = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (byteLength < 32) {
            throw new IllegalStateException("app.jwt.secret is only " + byteLength + " bytes -- HS256 (the weakest HMAC-SHA variant "
                + "this application's own signing key selection can produce) requires at least 32 bytes (256 bits) per RFC 7518. "
                + "Refusing to start with a secret this weak rather than fail later, unpredictably, at the first real login attempt.");
        }
        key(); // force Keys.hmacShaKeyFor's own validation to run now, at startup, not lazily later
    }

    private SecretKey key() { return Keys.hmacShaKeyFor(secret.getBytes()); }

    // ── Access Token (short-lived: 24h) ──────────────────────
    public String generateToken(String mobile, String userId, long tokenVersion) {
        return Jwts.builder()
            .subject(mobile)
            .claim("userId",       userId)
            .claim("tokenVersion", tokenVersion)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expiration))
            .signWith(key()).compact();
    }

    // ── Refresh Token (long-lived: 7d) ───────────────────────
    public String generateRefreshToken(String userId) {
        return Jwts.builder()
            .subject(userId)
            .claim("type", "refresh")
            .id(UUID.randomUUID().toString())   // unique per issuance
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + refreshExpiration))
            .signWith(key()).compact();
    }

    /** Hash refresh token before storing in DB (never store raw tokens) */
    public String hashToken(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(token.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return token; }
    }

    // ── Parse ─────────────────────────────────────────────────
    public String getMobile(String token) {
        return claims(token).getSubject();
    }

    public String getUserId(String token) {
        return (String) claims(token).get("userId");
    }

    public long getTokenVersion(String token) {
        Object v = claims(token).get("tokenVersion");
        return v != null ? ((Number) v).longValue() : 0L;
    }

    public boolean isRefreshToken(String token) {
        return "refresh".equals(claims(token).get("type"));
    }

    public boolean isValid(String token) {
        try { claims(token); return true; }
        catch (Exception e) { return false; }
    }

    public long getRefreshExpirationMs() { return refreshExpiration; }

    /** Access token lifetime in milliseconds, used to set the access-token cookie's Max-Age. */
    public long getAccessExpirationMs() { return expiration; }

    private Claims claims(String token) {
        return Jwts.parser().verifyWith(key()).build()
                   .parseSignedClaims(token).getPayload();
    }
}
