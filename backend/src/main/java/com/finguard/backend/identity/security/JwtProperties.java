package com.finguard.backend.identity.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT settings ({@code jwt.*}).
 * Supports dual-key / multi-key rotation via {@code jwt.active-kid} and {@code jwt.keys.<kid>=<secret>},
 * with full backward compatibility for single-key {@code jwt.secret} configurations.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        String activeKid,
        Map<String, String> keys,
        long expiration,
        long refreshExpiration
) {

    private static final int MIN_SECRET_BYTES = 32;
    public static final String DEFAULT_KID = "v1";

    @ConstructorBinding
    public JwtProperties(
            String secret,
            String activeKid,
            Map<String, String> keys,
            long expiration,
            long refreshExpiration
    ) {
        Map<String, String> resolvedKeys = new LinkedHashMap<>();
        if (keys != null && !keys.isEmpty()) {
            resolvedKeys.putAll(keys);
        }

        String resolvedActiveKid = (activeKid != null && !activeKid.isBlank())
                ? activeKid.trim()
                : DEFAULT_KID;

        if (secret != null && !secret.isBlank() && !resolvedKeys.containsKey(resolvedActiveKid)) {
            resolvedKeys.put(resolvedActiveKid, secret.trim());
        }

        if (resolvedKeys.isEmpty()) {
            throw new IllegalStateException(
                    "jwt.secret or jwt.keys (env JWT_SECRET) must be set and at least " + MIN_SECRET_BYTES + " bytes long");
        }

        if (!resolvedKeys.containsKey(resolvedActiveKid)) {
            throw new IllegalStateException(
                    "jwt.active-kid '" + resolvedActiveKid + "' was not found in jwt.keys");
        }

        for (Map.Entry<String, String> entry : resolvedKeys.entrySet()) {
            String keySecret = entry.getValue();
            if (keySecret == null || keySecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
                throw new IllegalStateException(
                        "JWT key for kid '" + entry.getKey() + "' must be at least " + MIN_SECRET_BYTES + " bytes long");
            }
        }

        this.secret = resolvedKeys.get(resolvedActiveKid);
        this.activeKid = resolvedActiveKid;
        this.keys = Collections.unmodifiableMap(resolvedKeys);
        this.expiration = expiration;
        this.refreshExpiration = refreshExpiration;
    }

    public JwtProperties(String secret, long expiration, long refreshExpiration) {
        this(secret, DEFAULT_KID, (secret != null && !secret.isBlank() ? Map.of(DEFAULT_KID, secret.trim()) : Map.of()), expiration, refreshExpiration);
    }
}
