package com.finguard.backend.identity.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("S1-06 — JWT Dual-Key Rotation and kid Header Drill")
class JwtRotationTest {

    private static final String SECRET_K1 = "key-1-very-secret-signing-key-0123456789abcdef";
    private static final String SECRET_K2 = "key-2-rotated-secret-signing-key-0123456789abcdef";
    private static final String SECRET_K3 = "key-3-third-generation-key-0123456789abcdef";

    private final UserDetails user = new User("alice@example.com", "password", Collections.emptyList());

    @Test
    @DisplayName("Tokens are issued with the active kid header and verify with the matching key")
    void tokensCarryActiveKidHeader() {
        JwtProperties props = new JwtProperties(
                SECRET_K1,
                "k1",
                Map.of("k1", SECRET_K1),
                60_000,
                300_000
        );
        JwtService service = new JwtService(props);

        String token = service.generateAccessToken(user);
        assertThat(service.getActiveKid()).isEqualTo("k1");

        // Inspect header directly to verify kid is present
        var jws = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET_K1.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token);

        assertThat(jws.getHeader().getKeyId()).isEqualTo("k1");

        // Verifies through the service
        String username = service.extractUsername(token, JwtService.ACCESS);
        assertThat(username).isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("Rotation drill: old tokens verify during dual-key window, then fail when key is retired")
    void rotationDrillOldTokensVerifyThenFailAfterRetirement() {
        // Step 1: Initialize service with key k1
        JwtProperties props = new JwtProperties(
                SECRET_K1,
                "k1",
                Map.of("k1", SECRET_K1),
                60_000,
                300_000
        );
        JwtService service = new JwtService(props);

        // User obtains a token signed with k1
        String tokenK1 = service.generateAccessToken(user);
        assertThat(service.extractUsername(tokenK1, JwtService.ACCESS)).isEqualTo("alice@example.com");

        // Step 2: Rotate active key to k2 (both k1 and k2 are now valid verification keys)
        service.rotateKey("k2", SECRET_K2);
        assertThat(service.getActiveKid()).isEqualTo("k2");

        // Newly issued tokens use k2
        String tokenK2 = service.generateAccessToken(user);
        var jwsK2 = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET_K2.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(tokenK2);
        assertThat(jwsK2.getHeader().getKeyId()).isEqualTo("k2");
        assertThat(service.extractUsername(tokenK2, JwtService.ACCESS)).isEqualTo("alice@example.com");

        // CRITICAL DRILL REQUIREMENT: old token signed with k1 STILL verifies during the transition window
        assertThat(service.extractUsername(tokenK1, JwtService.ACCESS)).isEqualTo("alice@example.com");

        // Step 3: Retire old key k1 after the grace period expires
        service.retireKey("k1");

        // Old token signed with k1 now FAILS verification
        assertThatThrownBy(() -> service.extractUsername(tokenK1, JwtService.ACCESS))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("retired JWT key identifier: k1");

        // Token signed with current key k2 continues to verify
        assertThat(service.extractUsername(tokenK2, JwtService.ACCESS)).isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("Cannot retire the currently active key")
    void cannotRetireActiveKey() {
        JwtProperties props = new JwtProperties(SECRET_K1, 60_000, 300_000);
        JwtService service = new JwtService(props);

        assertThatThrownBy(() -> service.retireKey("v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot retire the currently active JWT key");
    }

    @Test
    @DisplayName("Legacy token without kid header falls back to active key")
    void legacyTokenWithoutKidFallsBackToActiveKey() {
        JwtProperties props = new JwtProperties(SECRET_K1, 60_000, 300_000);
        JwtService service = new JwtService(props);

        // Build a raw token without a kid header (simulating legacy token issued before rotation support)
        long now = System.currentTimeMillis();
        String legacyToken = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject("alice@example.com")
                .claim(JwtService.TYPE_CLAIM, JwtService.ACCESS)
                .issuedAt(new Date(now))
                .expiration(new Date(now + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET_K1.getBytes(StandardCharsets.UTF_8)))
                .compact();

        String username = service.extractUsername(legacyToken, JwtService.ACCESS);
        assertThat(username).isEqualTo("alice@example.com");
    }
}

