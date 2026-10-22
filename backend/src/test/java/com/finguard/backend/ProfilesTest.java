package com.finguard.backend;

import com.finguard.backend.identity.security.JwtProperties;
import com.finguard.backend.identity.security.SecurityEnvironmentGuard;
import com.finguard.backend.support.TestPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The shipped configuration profiles behave as documented in docs/DEVELOPMENT.md. */
class ProfilesTest {

    @Test
    void devProfileBootsWithoutAnyJwtSecretConfiguredAndValidatesTheSchema() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("dev")
                .run(datasourceArgs())) {
            JwtProperties jwt = context.getBean(JwtProperties.class);
            assertThat(jwt.secret().length()).isGreaterThanOrEqualTo(32);
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        }
    }

    @Test
    void devSecretWithRemoteDatabaseRefusesStartup() {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(BackendApplication.class)
                .initializers(new SecurityEnvironmentGuard())
                .web(WebApplicationType.NONE)
                .profiles("dev");

        assertThatThrownBy(() -> builder.run(
                "--spring.datasource.url=jdbc:postgresql://db.remote-prod-server.com:5432/finguard",
                "--spring.datasource.username=postgres",
                "--spring.datasource.password=postgres",
                "--jwt.secret=" + SecurityEnvironmentGuard.DEV_JWT_SECRET))
                .hasStackTraceContaining("Production safety violation");
    }

    @Test
    void prodProfileRefusesToStartWithoutAJwtSecret() {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("prod");

        assertThatThrownBy(() -> builder.run(datasourceArgs()))
                .hasStackTraceContaining("jwt.secret");
    }

    @Test
    void prodProfileStartsWhenTheSecretIsSupplied() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .run(withArg(datasourceArgs(), "--JWT_SECRET=prod-secret-0123456789abcdef0123456789abcdef"))) {
            assertThat(context.getBean(JwtProperties.class).secret()).startsWith("prod-secret");
            // Verbose dev logging and SQL echo stay off outside dev.
            assertThat(context.getEnvironment().getProperty("spring.jpa.show-sql")).isEqualTo("false");
        }
    }

    /**
     * Command-line arguments outrank the profile's own defaults, exactly as real SPRING_DATASOURCE_*
     * environment variables do, so this models "the operator supplied the database settings".
     */
    private static String[] datasourceArgs() {
        var args = new java.util.ArrayList<String>();
        TestPostgres.register(new DynamicPropertyRegistry() {
            @Override
            public void add(String name, java.util.function.Supplier<Object> valueSupplier) {
                args.add("--" + name + "=" + valueSupplier.get());
            }
        });
        return args.toArray(String[]::new);
    }

    private static String[] withArg(String[] args, String extra) {
        var all = new java.util.ArrayList<>(java.util.List.of(args));
        all.add(extra);
        return all.toArray(String[]::new);
    }
}
