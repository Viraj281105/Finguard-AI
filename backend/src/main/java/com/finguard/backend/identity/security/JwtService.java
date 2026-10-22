package com.finguard.backend.identity.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Issues and verifies signed JWTs. Every token carries a {@code typ} claim so a refresh token
 * can never be used to call the API and an access token can never be used to refresh.
 */
@Service
public class JwtService {

    public static final String TYPE_CLAIM = "typ";
    public static final String ACCESS = "access";
    public static final String REFRESH = "refresh";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(UserDetails user) {
        return build(user.getUsername(), ACCESS, properties.expiration());
    }

    public String generateRefreshToken(UserDetails user) {
        return build(user.getUsername(), REFRESH, properties.refreshExpiration());
    }

    /**
     * Verifies signature, expiry and token type, and returns the subject.
     *
     * @throws JwtException if the token is malformed, tampered with, expired or of the wrong type
     */
    public String extractUsername(String token, String expectedType) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
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
                .id(UUID.randomUUID().toString())
                .subject(subject)
                .claim(TYPE_CLAIM, type)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(key)
                .compact();
    }
}
