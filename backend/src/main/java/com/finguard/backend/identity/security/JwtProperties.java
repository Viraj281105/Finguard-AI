package com.finguard.backend.identity.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;

/**
 * JWT settings ({@code jwt.*}). The secret has no default outside the dev profile and must be
 * at least 32 bytes so a missing or weak secret fails at startup instead of at first login.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, long expiration, long refreshExpiration) {

    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret (env JWT_SECRET) must be set and at least " + MIN_SECRET_BYTES + " bytes long");
        }
    }
}
