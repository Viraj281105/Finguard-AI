package com.finguard.backend.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * One real PostgreSQL server per test JVM, started from embedded binaries (no Docker needed).
 * The major version is pinned in pom.xml to match docker-compose.
 */
public final class TestPostgres {

    private static final EmbeddedPostgres SERVER = start();

    private TestPostgres() {
    }

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres server = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // best effort on JVM exit
                }
            }));
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start embedded PostgreSQL for tests", e);
        }
    }

    /** Creates an empty database on the shared server and returns its JDBC URL. */
    public static String createDatabase(String name) {
        try (var connection = SERVER.getPostgresDatabase().getConnection();
             var statement = connection.createStatement()) {
            statement.execute("create database " + name);
            return SERVER.getJdbcUrl("postgres", name);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Could not create test database " + name, e);
        }
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SERVER.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }
}
