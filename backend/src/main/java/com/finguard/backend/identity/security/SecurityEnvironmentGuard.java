package com.finguard.backend.identity.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Startup guard that prevents deploying with development defaults outside localhost (KI-028).
 * If the development-only signing key is configured alongside a remote database, startup fails fast
 * before Flyway or any database connections are attempted.
 */
@Slf4j
public class SecurityEnvironmentGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String DEV_JWT_SECRET = "finguard-dev-only-signing-key-change-me-0123456789abcdef";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        Environment environment = applicationContext.getEnvironment();
        String secret = environment.getProperty("jwt.secret", "");
        String datasourceUrl = environment.getProperty("spring.datasource.url", "");

        boolean isDevSecret = DEV_JWT_SECRET.equals(secret);
        boolean isLocalDatabase = datasourceUrl.isEmpty()
                || datasourceUrl.contains("localhost")
                || datasourceUrl.contains("127.0.0.1")
                || datasourceUrl.contains("embedded")
                || datasourceUrl.startsWith("jdbc:tc:");

        if (isDevSecret && !isLocalDatabase) {
            throw new IllegalStateException(
                    "Production safety violation: Running against a remote database with the default development JWT key is prohibited. Set JWT_SECRET in production.");
        }

        if (isDevSecret) {
            log.warn("Application starting with development-only JWT signing key. Do not use this configuration in production.");
        }
    }
}
