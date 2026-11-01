package com.finguard.backend.identity.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues and verifies signed JWTs with key rotation support.
 * Every token carries a {@code kid} header and a {@code typ} claim.
 */
@Slf4j
@Service
public class JwtService {

    public static final String TYPE_CLAIM = "typ";
    public static final String ACCESS = "access";
    public static final String REFRESH = "refresh";
    public static final String HEADER_KEY_ID = "kid";

    private final JwtProperties properties;
    private final Map<String, SecretKey> verificationKeys = new ConcurrentHashMap<>();
    private volatile String activeKid;
    private volatile SecretKey activeKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.activeKid = properties.activeKid();
        for (Map.Entry<String, String> entry : properties.keys().entrySet()) {
            this.verificationKeys.put(entry.getKey(), Keys.hmacShaKeyFor(entry.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        this.activeKey = this.verificationKeys.get(this.activeKid);
    }

    public String getActiveKid() {
        return activeKid;
    }

    public Map<String, SecretKey> getVerificationKeys() {
        return Map.copyOf(verificationKeys);
    }

    public synchronized void rotateKey(String newKid, String newSecret) {
        SecretKey newKey = Keys.hmacShaKeyFor(newSecret.getBytes(StandardCharsets.UTF_8));
        this.verificationKeys.put(newKid, newKey);
        this.activeKid = newKid;
        this.activeKey = newKey;
        log.info("JWT active key rotated to kid={}", newKid);
    }

    public synchronized void retireKey(String oldKid) {
        if (oldKid.equals(activeKid)) {
            throw new IllegalArgumentException("Cannot retire the currently active JWT key: " + oldKid);
        }
        this.verificationKeys.remove(oldKid);
        log.info("JWT retired key kid={}", oldKid);
    }

    public String generateAccessToken(UserDetails user) {
        return build(user.getUsername(), ACCESS, properties.expiration());
    }

    public String generateRefreshToken(UserDetails user) {
        return build(user.getUsername(), REFRESH, properties.refreshExpiration());
    }

    /**
     * Verifies signature, expiry and token type, and returns the subject.
     * Uses the 'kid' header to locate the verification key, falling back to the active key
     * for legacy tokens lacking a 'kid' header.
     *
     * @throws JwtException if the token is malformed, tampered with, expired, unsupported key or wrong type
     */
    public String extractUsername(String token, String expectedType) {
        Locator<Key> keyLocator = header -> {
            Object kidObj = header.get(HEADER_KEY_ID);
            String kid = kidObj != null ? kidObj.toString() : null;
            if (kid != null) {
                SecretKey key = verificationKeys.get(kid);
                if (key == null) {
                    throw new JwtException("Unknown or retired JWT key identifier: " + kid);
                }
                return key;
            }
            // Fallback for legacy tokens without a kid header
            return activeKey;
        };

        Claims claims = Jwts.parser()
                .keyLocator(keyLocator)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        if (!expectedType.equals(claims.get(TYPE_CLAIM, String.class))) {
            throw new JwtException("Unexpected token type");
        }
        return claims.getSubject();
    }

    /**
     * Computes a SHA-256 hex digest of a token so raw tokens are never persisted in the database.
     */
    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public JwtProperties getProperties() {
        return properties;
    }

    private String build(String subject, String type, long ttlMillis) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .header().keyId(activeKid).and()
                .id(UUID.randomUUID().toString())
                .subject(subject)
                .claim(TYPE_CLAIM, type)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(activeKey)
                .compact();
    }
}
