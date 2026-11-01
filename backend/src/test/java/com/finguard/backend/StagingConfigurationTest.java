package com.finguard.backend;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the reproducible staging configuration and cross-platform wrapper invariants (S0-08).
 */
class StagingConfigurationTest {

    private static final Path ROOT = Path.of("..").normalize();

    @Test
    void stagingComposeOverlayEnforcesProdProfileSecureCookiesAndLoopbackBindings() throws Exception {
        String compose = Files.readString(ROOT.resolve("docker-compose.staging.yml"), StandardCharsets.UTF_8);

        assertThat(compose)
                .contains("SPRING_PROFILES_ACTIVE: prod")
                .contains("JWT_COOKIE_SECURE: \"true\"")
                .contains("AI_ENV: staging")
                .contains("127.0.0.1:${STAGING_PG_PORT:-15433}:5432")
                .contains("127.0.0.1:${STAGING_AI_PORT:-18000}:8000")
                .contains("127.0.0.1:${STAGING_API_PORT:-18080}:8080")
                .contains("127.0.0.1:${STAGING_WEB_PORT:-14173}:8080");
    }

    @Test
    void stagingScriptsUseContainerisedFullProfileWithoutHostProcessSpawning() throws Exception {
        String upPs1 = Files.readString(ROOT.resolve("scripts/staging-up.ps1"), StandardCharsets.UTF_8);
        String downPs1 = Files.readString(ROOT.resolve("scripts/staging-down.ps1"), StandardCharsets.UTF_8);
        String upSh = Files.readString(ROOT.resolve("scripts/staging-up.sh"), StandardCharsets.UTF_8);
        String downSh = Files.readString(ROOT.resolve("scripts/staging-down.sh"), StandardCharsets.UTF_8);
        String smokeMjs = Files.readString(ROOT.resolve("scripts/staging-smoke.mjs"), StandardCharsets.UTF_8);

        for (String upScript : new String[]{upPs1, upSh}) {
            assertThat(upScript)
                    .contains("--profile full")
                    .contains("docker-compose.staging.yml")
                    .doesNotContain("Start-Process java")
                    .doesNotContain("vite preview");
        }

        for (String downScript : new String[]{downPs1, downSh}) {
            assertThat(downScript)
                    .contains("--profile full")
                    .contains("down -v --remove-orphans");
        }

        assertThat(smokeMjs)
                .contains("/api/auth/register")
                .contains("/api/auth/login")
                .contains("/api/upload/csv")
                .contains("/api/transactions")
                .contains("/api/analytics/summary")
                .contains("/api/recommendations");
    }
}
