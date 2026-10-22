package com.finguard.backend;

import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application against a real PostgreSQL. Starting at all proves that the Flyway
 * migrations apply cleanly and that the JPA entities match the migrated schema (ddl-auto=validate).
 */
class BackendApplicationTests extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contextLoads() {
    }

    @Test
    void flywayAppliedTheBaselineMigration() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void migratedSchemaContainsTheExpectedTables() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("users", "transactions", "investments", "loans", "upload_jobs");
    }
}
