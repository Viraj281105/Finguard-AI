package com.finguard.backend.identity.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.lang.NonNull;

import java.util.Arrays;

/**
 * Startup guard that prevents deploying with development defaults in production or outside localhost (KI-028, R-7).
 * Fails closed fast before Flyway or any database connections are attempted:
 * 1. In prod/production profiles: rejects default/short JWT secret, insecure cookies, default database passwords,
 *    or a missing/short AI service shared secret, even when the database is hosted locally.
 * 2. In any profile: running against a remote database with the default development JWT key is prohibited.
 */
@Slf4j
public class SecurityEnvironmentGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String DEV_JWT_SECRET = "finguard-dev-only-signing-key-change-me-0123456789abcdef";
    public static final String DEV_DB_PASSWORD = "finguard_pass";
    /** Same minimum as the AI service's {@code MIN_TOKEN_LENGTH}. */
    public static final int MIN_AI_TOKEN_LENGTH = 32;

    @Override
    public void initialize(@NonNull ConfigurableApplicationContext applicationContext) {
        validateEnvironment(applicationContext.getEnvironment());
    }

    public void validateEnvironment(@NonNull Environment environment) {
        String secret = environment.getProperty("jwt.secret", "");
        String datasourceUrl = environment.getProperty("spring.datasource.url", "");
        String datasourcePassword = environment.getProperty("spring.datasource.password", "");
        boolean cookieSecure = Boolean.parseBoolean(environment.getProperty("jwt.cookie-secure", "false"));

        boolean isProd = environment.acceptsProfiles(Profiles.of("prod", "production"))
                || Arrays.asList(environment.getActiveProfiles()).contains("prod")
                || Arrays.asList(environment.getActiveProfiles()).contains("production");

        boolean isDevSecret = DEV_JWT_SECRET.equals(secret);
        boolean isLocalDatabase = datasourceUrl.isEmpty()
                || datasourceUrl.contains("localhost")
                || datasourceUrl.contains("127.0.0.1")
                || datasourceUrl.contains("embedded")
                || datasourceUrl.startsWith("jdbc:tc:");

        // Production profile guards (fail-closed)
        if (isProd) {
            if (isDevSecret) {
                throw new IllegalStateException(
                        "Production safety violation: Default development JWT secret cannot be used in production. Set a strong JWT_SECRET.");
            }
            if (secret.length() < 32) {
                throw new IllegalStateException(
                        "Production safety violation: JWT secret in production must be at least 32 characters long.");
            }
            if (!cookieSecure) {
                throw new IllegalStateException(
                        "Production safety violation: jwt.cookie-secure must be true in production to protect refresh token cookies.");
            }
            if (DEV_DB_PASSWORD.equals(datasourcePassword)) {
                throw new IllegalStateException(
                        "Production safety violation: Default development database password cannot be used in production. Set a secure DB password.");
            }
            // The AI service refuses to start outside dev without the same secret (S0-06, KI-033), so an
            // unset or short value here would silently turn classification off in production.
            String aiToken = environment.getProperty("ai.service.internal-token", "");
            if (aiToken.length() < MIN_AI_TOKEN_LENGTH) {
                throw new IllegalStateException(
                        "Production safety violation: ai.service.internal-token (AI_INTERNAL_TOKEN) must be at least "
                                + MIN_AI_TOKEN_LENGTH + " characters in production and equal the AI service's AI_INTERNAL_TOKEN.");
            }
        }

        // General guard: remote DB with dev key is always rejected regardless of active profile
        if (isDevSecret && !isLocalDatabase) {
            throw new IllegalStateException(
                    "Production safety violation: Running against a remote database with the default development JWT key is prohibited. Set JWT_SECRET in production.");
        }

        if (isDevSecret) {
            log.warn("Application starting with development-only JWT signing key. Do not use this configuration in production.");
        }
    }
}
