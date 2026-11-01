package com.finguard.backend.migration;

import com.finguard.backend.BackendApplication;
import com.finguard.backend.support.TestPostgres;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Automated PostgreSQL 18 backup and restore rehearsal (S0-09).
 *
 * <p>Seeds all 11 schema tables on a migrated PostgreSQL 18 source database, exports a SHA-256-verified
 * PostgreSQL binary archive, restores it into a clean target PostgreSQL 18 database, and verifies
 * exact row counts, deterministic per-table MD5 checksums, and exact {@link BigDecimal} financial ledger invariants.
 */
class BackupRestoreDrillTest {

    /** Ordered in topological foreign-key dependency order for clean restore. */
    private static final List<String> ALL_TABLES = List.of(
            "flyway_schema_history",
            "users",
            "refresh_tokens",
            "upload_jobs",
            "transactions",
            "loans",
            "investments",
            "budgets",
            "financial_goals",
            "goal_contributions",
            "saved_scenarios",
            "event_outbox",
            "processed_event",
            "event_dead_letter",
            "audit_log"
    );

    private static final Path ROOT = Path.of("..").normalize();

    @Test
    void backupAndRestoreDrillPreservesEveryTableChecksumAndFinancialInvariant(@TempDir Path tempDir) throws Exception {
        String sourceUrl = TestPostgres.createDatabase("backup_drill_source");
        String targetUrl = TestPostgres.createDatabase("backup_drill_target");

        JdbcTemplate sourceJdbc = new JdbcTemplate(new DriverManagerDataSource(sourceUrl, "postgres", "postgres"));
        JdbcTemplate targetJdbc = new JdbcTemplate(new DriverManagerDataSource(targetUrl, "postgres", "postgres"));

        // 1. Migrate source DB and seed synthetic financial records across all 11 tables
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--spring.datasource.url=" + sourceUrl,
                        "--spring.datasource.username=postgres",
                        "--spring.datasource.password=postgres")) {
            seedSyntheticFinancialData(sourceJdbc);
        }

        // 2. Apply schema to target DB, then clear flyway_schema_history so the full 11-table backup restores cleanly
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--spring.datasource.url=" + targetUrl,
                        "--spring.datasource.username=postgres",
                        "--spring.datasource.password=postgres")) {
            targetJdbc.execute("TRUNCATE TABLE flyway_schema_history");
        }

        // 3. Export PostgreSQL binary COPY archive + SHA-256 manifest for each table
        long dumpStartNanos = System.nanoTime();
        for (String table : ALL_TABLES) {
            Path tableDump = tempDir.resolve(table + ".pgbin");
            sourceJdbc.execute((ConnectionCallback<Void>) conn -> {
                CopyManager copyManager = conn.unwrap(PGConnection.class).getCopyAPI();
                try (OutputStream out = Files.newOutputStream(tableDump)) {
                    copyManager.copyOut("COPY " + table + " TO STDOUT WITH (FORMAT binary)", out);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to dump table " + table, e);
                }
                return null;
            });
            assertThat(Files.size(tableDump)).as("Binary dump size for %s", table).isGreaterThan(0L);
            String sha256 = sha256Hex(tableDump);
            Files.writeString(tempDir.resolve(table + ".pgbin.sha256"), sha256, StandardCharsets.UTF_8);
        }
        long dumpMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dumpStartNanos);

        // 4. Verify SHA-256 manifest and restore into target database, measuring RTO
        long restoreStartNanos = System.nanoTime();
        for (String table : ALL_TABLES) {
            Path tableDump = tempDir.resolve(table + ".pgbin");
            String expectedSha = Files.readString(tempDir.resolve(table + ".pgbin.sha256"), StandardCharsets.UTF_8).trim();
            assertThat(sha256Hex(tableDump)).as("SHA-256 integrity check for %s", table).isEqualTo(expectedSha);

            targetJdbc.execute((ConnectionCallback<Void>) conn -> {
                CopyManager copyManager = conn.unwrap(PGConnection.class).getCopyAPI();
                try (InputStream in = Files.newInputStream(tableDump)) {
                    copyManager.copyIn("COPY " + table + " FROM STDIN WITH (FORMAT binary)", in);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to restore table " + table, e);
                }
                return null;
            });
        }
        long restoreMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - restoreStartNanos);

        assertThat(dumpMillis).as("Backup duration ms").isLessThan(15_000L);
        assertThat(restoreMillis).as("Restore RTO duration ms").isLessThan(15_000L);

        // 5. Verify exact row count and deterministic MD5 checksum for every table
        for (String table : ALL_TABLES) {
            Integer sourceCount = sourceJdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
            Integer targetCount = targetJdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
            assertThat(targetCount)
                    .as("Row count for table %s", table)
                    .isEqualTo(sourceCount)
                    .isGreaterThan(0);

            String checksumSql = "SELECT coalesce(md5(string_agg(t::text, '|' ORDER BY 1)), 'EMPTY') "
                    + "FROM (SELECT * FROM " + table + " ORDER BY 1) t";
            String sourceChecksum = sourceJdbc.queryForObject(checksumSql, String.class);
            String targetChecksum = targetJdbc.queryForObject(checksumSql, String.class);
            assertThat(targetChecksum)
                    .as("Deterministic content checksum for table %s", table)
                    .isEqualTo(sourceChecksum);
        }

        // 6. Verify financial ledger arithmetic invariants match to the exact paisa
        for (String sql : List.of(
                "SELECT sum(amount) FROM transactions WHERE type = 'INCOME'",
                "SELECT sum(amount) FROM transactions WHERE type = 'EXPENSE'",
                "SELECT sum(principal) FROM loans WHERE active = true",
                "SELECT sum(interest_rate) FROM loans",
                "SELECT sum(current_value) FROM investments",
                "SELECT sum(amount) FROM budgets",
                "SELECT sum(current_amount) FROM financial_goals",
                "SELECT sum(amount) FROM goal_contributions"
        )) {
            BigDecimal sourceSum = sourceJdbc.queryForObject(sql, BigDecimal.class);
            BigDecimal targetSum = targetJdbc.queryForObject(sql, BigDecimal.class);
            assertThat(targetSum)
                    .as("Financial invariant query [%s]", sql)
                    .isEqualByComparingTo(sourceSum);
        }
    }

    @Test
    void backupAndRestoreScriptsAndRunbookDefinePgDumpCustomFormatAndChecksumVerification() throws Exception {
        String backupSh = Files.readString(ROOT.resolve("scripts/backup-db.sh"), StandardCharsets.UTF_8);
        String backupPs1 = Files.readString(ROOT.resolve("scripts/backup-db.ps1"), StandardCharsets.UTF_8);
        String drillSh = Files.readString(ROOT.resolve("scripts/restore-drill.sh"), StandardCharsets.UTF_8);
        String drillPs1 = Files.readString(ROOT.resolve("scripts/restore-drill.ps1"), StandardCharsets.UTF_8);
        String runbook = Files.readString(ROOT.resolve("docs/runbooks/restore.md"), StandardCharsets.UTF_8);

        for (String backup : List.of(backupSh, backupPs1)) {
            assertThat(backup).contains("pg_dump -Fc --no-owner --no-privileges");
        }
        for (String drill : List.of(drillSh, drillPs1)) {
            assertThat(drill)
                    .contains("pg_restore --no-owner --no-privileges --exit-on-error")
                    .contains("md5(string_agg(t::text");
        }
        assertThat(runbook)
                .contains("pg_dump -Fc")
                .contains("pg_restore --no-owner --no-privileges --exit-on-error")
                .contains("flyway_schema_history");
    }

    private static void seedSyntheticFinancialData(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO users (id, name, email, password, created_at) "
                + "VALUES ('u-drill-1', 'Drill User', 'drill@example.com', 'bcrypt-hash', '2026-07-01 00:00:00')");

        jdbc.update("INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, revoked, created_at) "
                + "VALUES ('rt-1', 'u-drill-1', 'sha256-token-hash-1', '2026-07-08 00:00:00', false, '2026-07-01 00:00:00')");

        jdbc.update("INSERT INTO upload_jobs (id, user_id, bank_name, original_filename, status, total_rows, rows_processed, "
                + "duplicate_rows, rejected_rows, skipped_rows, row_errors_json, closing_balance, classification_status, classified_rows, created_at) "
                + "VALUES ('job-1', 'u-drill-1', 'HDFC', 'hdfc.csv', 'COMPLETED', 5, 3, 0, 1, 1, '[{\"row\":4,\"reason\":\"Invalid amount\"}]', 120000.00, 'COMPLETED', 3, '2026-07-01 00:00:00')");

        jdbc.update("INSERT INTO transactions (id, user_id, title, amount, type, txn_date, category, category_source, "
                + "category_confidence, category_needs_review, source, import_job_id, fingerprint, created_at) VALUES "
                + "('tx-1', 'u-drill-1', 'Salary July', 85000.00, 'INCOME', '2026-07-01', 'INCOME', 'RULE', 1.0000, false, 'IMPORT', 'job-1', 'fp-1', '2026-07-01 09:00:00'),"
                + "('tx-2', 'u-drill-1', 'House Rent', 24500.50, 'EXPENSE', '2026-07-03', 'HOUSING', 'USER', null, false, 'MANUAL', null, 'fp-2', '2026-07-03 10:00:00'),"
                + "('tx-3', 'u-drill-1', 'Supermarket', 4320.75, 'EXPENSE', '2026-07-05', 'GROCERIES', 'MODEL', 0.9100, false, 'IMPORT', 'job-1', 'fp-3', '2026-07-05 12:00:00')");

        jdbc.update("INSERT INTO loans (id, user_id, name, type, principal, emi_amount, interest_rate, start_date, end_date, active, created_at) "
                + "VALUES ('ln-1', 'u-drill-1', 'Home Loan', 'HOME', 3200000.00, 28500.00, 8.6525, '2025-01-01', '2045-01-01', true, '2026-07-01 00:00:00')");

        jdbc.update("INSERT INTO investments (id, user_id, name, type, amount_invested, current_value, notes, created_at) "
                + "VALUES ('inv-1', 'u-drill-1', 'Nifty 50 Index', 'MUTUAL_FUND', 150000.00, 168450.25, 'SIP', '2026-07-01 00:00:00')");

        jdbc.update("INSERT INTO budgets (id, user_id, year_month, category, amount, created_at, updated_at) "
                + "VALUES ('bdg-1', 'u-drill-1', '2026-07', 'GROCERIES', 10000.00, '2026-07-01 00:00:00', '2026-07-01 00:00:00')");

        jdbc.update("INSERT INTO financial_goals (id, user_id, name, description, target_amount, current_amount, target_date, status, created_at, updated_at) "
                + "VALUES ('goal-1', 'u-drill-1', 'Emergency Fund', 'Six months expenses', 300000.00, 25000.00, '2027-06-30', 'ACTIVE', '2026-07-01 00:00:00', '2026-07-01 00:00:00')");

        jdbc.update("INSERT INTO goal_contributions (id, goal_id, user_id, amount, contribution_date, note, created_at) "
                + "VALUES ('gc-1', 'goal-1', 'u-drill-1', 25000.00, '2026-07-02', 'Initial transfer', '2026-07-02 00:00:00')");

        jdbc.update("INSERT INTO saved_scenarios (id, user_id, name, horizon_months, request_json, result_json, method_version, created_at) "
                + "VALUES ('sc-1', 'u-drill-1', 'Car Purchase Check', 6, '{\"horizonMonths\":6}', '{\"feasible\":true}', 'v1.0', '2026-07-02 00:00:00')");

        jdbc.update("INSERT INTO event_outbox (id, aggregate_type, aggregate_id, event_type, schema_version, tenant, trace_id, occurred_at, payload, published_at, attempts) "
                + "VALUES ('0194c4e7-5a1b-7d2e-9f3a-6c1d2b8e4a90', 'upload_job', 'job-1', 'com.finguard.import.completed', 1, 'user:u-drill-1', '4bf92f3577b34da6a3ce929d0e0e4736', '2026-07-02 00:00:00+00', '{\"id\":\"0194c4e7-5a1b-7d2e-9f3a-6c1d2b8e4a90\"}'::jsonb, '2026-07-02 00:00:01+00', 1)");

        jdbc.update("INSERT INTO processed_event (consumer, event_id, processed_at) "
                + "VALUES ('audit-writer', '0194c4e7-5a1b-7d2e-9f3a-6c1d2b8e4a90', '2026-07-02 00:00:01+00')");

        jdbc.update("INSERT INTO event_dead_letter (id, event_id, consumer, event_type, payload, error, attempts, failed_at) "
                + "VALUES ('0194c4e7-5a1b-7d2e-9f3a-6c1d2b8e4a91', '0194c4e7-5a1b-7d2e-9f3a-6c1d2b8e4a99', 'audit-writer', 'com.finguard.import.completed', '{\"poison\":true}'::jsonb, 'Simulated failure', 3, '2026-07-02 00:00:02+00')");

        jdbc.update("INSERT INTO audit_log (id, event_id, tenant_hash, event_type, aggregate_type, aggregate_id, trace_id, occurred_at, recorded_at, summary_json) "
                + "VALUES ('0194c4e7-5a1b-7d2e-9f3a-6c1d2b8e4a92', '0194c4e7-5a1b-7d2e-9f3a-6c1d2b8e4a90', '0123456789ab', 'com.finguard.import.completed', 'upload_job', 'job-1', '4bf92f3577b34da6a3ce929d0e0e4736', '2026-07-02 00:00:00+00', '2026-07-02 00:00:01+00', '{\"rowsProcessed\":3}'::jsonb)");
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(file);
        return HexFormat.of().formatHex(digest.digest(bytes));
    }
}
