package com.finguard.backend.migration;

import com.finguard.backend.BackendApplication;
import com.finguard.backend.support.TestPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A database created by the old {@code ddl-auto=update} setup already contains the tables and has no
 * Flyway history. Starting the new version must adopt it as baseline V1, execute subsequent migrations (V2),
 * and safely normalize existing rows without data loss.
 */
class LegacyDatabaseAdoptionTest {

    @Test
    void existingDatabaseWithDataIsAdoptedAsBaselineAndUpgradedWithoutDataLoss() throws Exception {
        String url = TestPostgres.createDatabase("legacy_adoption");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", "postgres"));

        // Stand in for the legacy schema: the same tables Hibernate used to generate, no Flyway history.
        String legacySchema = new ClassPathResource("db/migration/V1__baseline.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(legacySchema);
        jdbc.update("insert into users (id, name, email, password) values ('u1', 'Old User', 'OLD@EXAMPLE.COM', 'hash')");
        jdbc.update("insert into transactions (id, user_id, title, amount, type, note) values ('t1', 'u1', 'Rent', 12000.50, 'EXPENSE', 'Imported from HDFC | 2026-03-14')");
        jdbc.update("insert into transactions (id, user_id, title, amount, type, created_at) values ('t2', 'u1', 'Coffee', 120.00, 'EXPENSE', '2026-03-10 12:00:00')");
        jdbc.update("insert into loans (id, user_id, name, type, principal, emi_amount, interest_rate) values ('l1', 'u1', 'Home Loan', 'HOME', 5000000, 45000, 8.7550)");
        // Free-text categories as the old frontend and API allowed them.
        jdbc.update("insert into transactions (id, user_id, title, amount, type, category, created_at) values ('c1', 'u1', 'Lunch', 200, 'EXPENSE', 'Food', '2026-03-11 12:00:00')");
        jdbc.update("insert into transactions (id, user_id, title, amount, type, category, created_at) values ('c2', 'u1', 'Pay', 5000, 'INCOME', 'Salary', '2026-03-11 12:00:00')");
        jdbc.update("insert into transactions (id, user_id, title, amount, type, category, created_at) values ('c3', 'u1', 'Loan', 900, 'EXPENSE', 'EMI', '2026-03-11 12:00:00')");
        jdbc.update("insert into transactions (id, user_id, title, amount, type, category, created_at) values ('c4', 'u1', 'Mystery', 10, 'EXPENSE', 'Pet supplies', '2026-03-11 12:00:00')");
        jdbc.update("insert into transactions (id, user_id, title, amount, type, category, note, created_at) values ('c5', 'u1', 'Mystery2', 10, 'EXPENSE', 'Pet supplies', 'my note', '2026-03-11 12:00:00')");
        jdbc.update("insert into transactions (id, user_id, title, amount, type, category, created_at) values ('c6', 'u1', 'Blank', 10, 'EXPENSE', '   ', '2026-03-11 12:00:00')");
        jdbc.update("insert into transactions (id, user_id, title, amount, type, category, created_at) values ('c7', 'u1', 'Other', 10, 'EXPENSE', 'Other', '2026-03-11 12:00:00')");
        jdbc.update("insert into transactions (id, user_id, title, amount, type, category, created_at) values ('c8', 'u1', 'Lower', 10, 'EXPENSE', 'groceries', '2026-03-11 12:00:00')");

        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--spring.datasource.url=" + url,
                        "--spring.datasource.username=postgres",
                        "--spring.datasource.password=postgres")) {

            List<Map<String, Object>> history = jdbc.queryForList(
                    "select version, type, success from flyway_schema_history order by installed_rank");
            assertThat(history).hasSize(7);
            assertThat(history.get(0)).containsEntry("version", "1").containsEntry("type", "BASELINE")
                    .containsEntry("success", true);
            assertThat(history.get(1)).containsEntry("version", "2").containsEntry("type", "SQL")
                    .containsEntry("success", true);
            assertThat(history.get(2)).containsEntry("version", "3").containsEntry("type", "SQL")
                    .containsEntry("success", true);
            assertThat(history.get(3)).containsEntry("version", "4").containsEntry("type", "SQL")
                    .containsEntry("success", true);
            assertThat(history.get(4)).containsEntry("version", "5").containsEntry("type", "SQL")
                    .containsEntry("success", true);
            assertThat(history.get(5)).containsEntry("version", "6").containsEntry("type", "SQL")
                    .containsEntry("success", true);
            assertThat(history.get(6)).containsEntry("version", "7").containsEntry("type", "SQL")
                    .containsEntry("success", true);

            // Verify V6 tables (budgets, financial_goals, goal_contributions)
            jdbc.update("insert into budgets (id, user_id, year_month, category, amount, created_at, updated_at) " +
                    "values ('b1', 'u1', '2026-10', 'DINING', 5000.00, now(), now())");
            assertThat(jdbc.queryForObject("select amount from budgets where id = 'b1'", java.math.BigDecimal.class))
                    .isEqualByComparingTo("5000.00");
            assertThatThrownBy(() -> jdbc.update("insert into budgets (id, user_id, year_month, category, amount, created_at, updated_at) " +
                    "values ('b2', 'u1', '2026-10', 'TRANSFERS', 5000.00, now(), now())"))
                    .hasMessageContaining("ck_budgets_category");

            jdbc.update("insert into financial_goals (id, user_id, name, target_amount, current_amount, status, created_at, updated_at) " +
                    "values ('g1', 'u1', 'Emergency', 100000.00, 0.00, 'ACTIVE', now(), now())");
            assertThat(jdbc.queryForObject("select target_amount from financial_goals where id = 'g1'", java.math.BigDecimal.class))
                    .isEqualByComparingTo("100000.00");

            // V5 normalised the free-text categories without losing anything.
            assertThat(category(jdbc, "c1")).containsEntry("category", "DINING").containsEntry("category_source", "USER");
            assertThat(category(jdbc, "c2")).containsEntry("category", "INCOME").containsEntry("category_source", "USER");
            assertThat(category(jdbc, "c3")).containsEntry("category", "LOAN_PAYMENTS");
            assertThat(category(jdbc, "c4")).containsEntry("category", "UNCATEGORIZED")
                    .containsEntry("category_source", "USER")
                    .containsEntry("note", "Previous category: Pet supplies");
            assertThat(category(jdbc, "c5")).containsEntry("note", "my note | Previous category: Pet supplies");
            assertThat(category(jdbc, "c6")).containsEntry("category", null).containsEntry("category_source", "NONE");
            assertThat(category(jdbc, "c7")).containsEntry("category", "UNCATEGORIZED");
            assertThat(category(jdbc, "c8")).containsEntry("category", "GROCERIES");
            assertThat(category(jdbc, "t1")).containsEntry("category", null).containsEntry("category_source", "NONE");
            assertThat(jdbc.queryForObject("select count(*) from transactions", Integer.class)).isEqualTo(10);

            // The database now refuses inconsistent categorisation.
            assertThatThrownBy(() -> jdbc.update(
                    "update transactions set category = 'Food', category_source = 'USER' where id = 't2'"))
                    .hasMessageContaining("ck_transactions_category");
            assertThatThrownBy(() -> jdbc.update("update transactions set category = 'DINING' where id = 't2'"))
                    .hasMessageContaining("ck_transactions_category_provenance");
            assertThatThrownBy(() -> jdbc.update("update transactions set category_source = 'USER' where id = 't2'"))
                    .hasMessageContaining("ck_transactions_category_provenance");
            assertThatThrownBy(() -> jdbc.update("update transactions set category = 'DINING', "
                    + "category_source = 'RULE', category_confidence = 1.5 where id = 't2'"))
                    .hasMessageContaining("ck_transactions_category_confidence");

            // Verify V4 columns on upload_jobs table
            jdbc.update("insert into upload_jobs (id, user_id, status, rejected_rows, skipped_rows, closing_balance) " +
                    "values ('j1', 'u1', 'COMPLETED', 1, 2, 75000.50)");
            assertThat(jdbc.queryForObject("select rejected_rows from upload_jobs where id = 'j1'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select skipped_rows from upload_jobs where id = 'j1'", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select closing_balance from upload_jobs where id = 'j1'", java.math.BigDecimal.class))
                    .isEqualByComparingTo("75000.50");

            assertThat(jdbc.queryForObject("select classification_status from upload_jobs where id = 'j1'", String.class))
                    .isEqualTo("NOT_ATTEMPTED");
            assertThat(jdbc.queryForObject("select classified_rows from upload_jobs where id = 'j1'", Integer.class))
                    .isZero();

            assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select email from users where id = 'u1'", String.class)).isEqualTo("old@example.com");
            assertThat(jdbc.queryForObject("select amount from transactions where id = 't1'", java.math.BigDecimal.class))
                    .isEqualByComparingTo("12000.50");
            assertThat(jdbc.queryForObject("select txn_date from transactions where id = 't1'", java.sql.Date.class))
                    .isEqualTo(java.sql.Date.valueOf("2026-03-14"));
            assertThat(jdbc.queryForObject("select source from transactions where id = 't1'", String.class))
                    .isEqualTo("IMPORT");
            assertThat(jdbc.queryForObject("select txn_date from transactions where id = 't2'", java.sql.Date.class))
                    .isEqualTo(java.sql.Date.valueOf("2026-03-10"));
            assertThat(jdbc.queryForObject("select source from transactions where id = 't2'", String.class))
                    .isEqualTo("MANUAL");
            assertThat(jdbc.queryForObject("select interest_rate from loans where id = 'l1'", java.math.BigDecimal.class))
                    .isEqualByComparingTo("8.7600");

            // Post-migration insertions preserve 4 decimal places (resolves KI-026)
            jdbc.update("insert into loans (id, user_id, name, type, principal, emi_amount, interest_rate) values ('l2', 'u1', 'Car Loan', 'CAR', 1000000, 20000, 8.7550)");
            assertThat(jdbc.queryForObject("select interest_rate from loans where id = 'l2'", java.math.BigDecimal.class))
                    .isEqualByComparingTo("8.7550");
        }
    }

    private static Map<String, Object> category(JdbcTemplate jdbc, String id) {
        return jdbc.queryForMap("select category, category_source, note from transactions where id = ?", id);
    }

    @Test
    void emptyDatabaseGetsTheFullSchemaFromMigrations() {
        String url = TestPostgres.createDatabase("fresh_install");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", "postgres"));

        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--spring.datasource.url=" + url,
                        "--spring.datasource.username=postgres",
                        "--spring.datasource.password=postgres")) {

            assertThat(jdbc.queryForObject(
                    "select type from flyway_schema_history where version = '1'", String.class)).isEqualTo("SQL");
            assertThat(jdbc.queryForObject(
                    "select type from flyway_schema_history where version = '2'", String.class)).isEqualTo("SQL");
            assertThat(jdbc.queryForObject(
                    "select type from flyway_schema_history where version = '3'", String.class)).isEqualTo("SQL");
            assertThat(jdbc.queryForObject(
                    "select type from flyway_schema_history where version = '4'", String.class)).isEqualTo("SQL");
            assertThat(jdbc.queryForObject(
                    "select type from flyway_schema_history where version = '5'", String.class)).isEqualTo("SQL");
            assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isZero();
        }
    }

    @Test
    void duplicateCaseVariantEmailsHaltV2MigrationWithClearMessage() throws Exception {
        String url = TestPostgres.createDatabase("legacy_duplicates");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", "postgres"));

        String legacySchema = new ClassPathResource("db/migration/V1__baseline.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(legacySchema);
        jdbc.update("insert into users (id, name, email, password) values ('u1', 'User 1', 'alice@example.com', 'hash')");
        jdbc.update("insert into users (id, name, email, password) values ('u2', 'User 2', 'ALICE@EXAMPLE.COM', 'hash')");

        SpringApplicationBuilder builder = new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test");

        assertThatThrownBy(() -> builder.run(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=postgres",
                "--spring.datasource.password=postgres"))
                .hasStackTraceContaining("Duplicate emails found differing only by case in users table");
    }
}
