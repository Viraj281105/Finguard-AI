package com.finguard.backend.privacy;

import com.finguard.backend.common.events.DomainEventEnvelope;
import com.finguard.backend.common.observability.TraceContext;
import com.finguard.backend.identity.security.AuthRateLimiter;
import com.finguard.backend.privacy.service.CsvFormulaSanitizer;
import com.finguard.backend.privacy.service.DataRetentionService;
import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance &amp; verification suite for S1-05 (Project J — Privacy Lifecycle: Export, Ordered Deletion,
 * Append-Only Audit Log, Formula Neutralisation, and Retention Jobs).
 */
class PrivacyLifecycleTest extends IntegrationTest {

    private static final Path CONTRACTS_DIR = Path.of("..").resolve("contracts").resolve("events").normalize();

    private static final List<String> USER_OWNED_TABLES = List.of(
            "goal_contributions",
            "financial_goals",
            "budgets",
            "saved_scenarios",
            "transactions",
            "upload_jobs",
            "loans",
            "investments",
            "refresh_tokens"
    );

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataRetentionService dataRetentionService;

    @Autowired
    private AuthRateLimiter authRateLimiter;

    @BeforeEach
    void resetLimiter() {
        authRateLimiter.reset();
    }

    @Test
    @DisplayName("S1-05: Ordered deletion erases 100% of deleted user rows across all 10 tables while leaving second user untouched")
    void orderedDeletionRemovesAllRowsForTargetUserAndPreservesOtherUserCompletely() throws Exception {
        TestUser userA = registerUser();
        TestUser userB = registerUser();
        String userAId = getUserId(userA.email());
        String userBId = getUserId(userB.email());

        seedEveryTableForUser(userAId, "A");
        seedEveryTableForUser(userBId, "B");

        // Verify both users have rows in all 10 tables before deletion
        for (String table : USER_OWNED_TABLES) {
            assertThat(countRowsForUser(table, userAId)).as("Pre-delete %s for userA", table).isGreaterThan(0);
            assertThat(countRowsForUser(table, userBId)).as("Pre-delete %s for userB", table).isGreaterThan(0);
        }

        // Capture userB exact counts before deleting userA
        Map<String, Integer> userBCountsBefore = snapshotCounts(userBId);

        // 1. Request scheduled deletion for userA (grace period)
        JsonNode scheduleResp = json.readTree(mvc.perform(as(userA, post("/api/privacy/delete-request"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", PASSWORD, "immediate", false))))
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"))
                .andReturn().getResponse().getContentAsString());
        assertThat(scheduleResp.path("status").asText()).isEqualTo("SCHEDULED");
        assertThat(scheduleResp.path("requestedAt").asText()).isNotBlank();
        assertThat(scheduleResp.path("graceUntil").asText()).isNotBlank();

        // Check status shows deletionPending = true
        JsonNode statusPending = json.readTree(mvc.perform(as(userA, get("/api/privacy/status")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(statusPending.path("deletionPending").asBoolean()).isTrue();

        // 2. Cancel deletion during grace period
        JsonNode cancelResp = json.readTree(mvc.perform(as(userA, post("/api/privacy/delete-cancel"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(cancelResp.path("deletionPending").asBoolean()).isFalse();

        // 3. Re-request scheduled deletion and advance retention clock past the 30-day grace window
        JsonNode rescheduleResp = json.readTree(mvc.perform(as(userA, post("/api/privacy/delete-request"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", PASSWORD, "immediate", false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        Instant graceUntil = Instant.parse(rescheduleResp.path("graceUntil").asText());

        // Running retention before grace period expires does NOT delete userA
        int deletedBeforeGrace = dataRetentionService.purgeExpiredDeletionRequests(graceUntil.minusSeconds(60));
        assertThat(deletedBeforeGrace).isZero();
        assertThat(countUserRow(userAId)).isEqualTo(1);

        // Running retention after grace period expires permanently hard-deletes userA
        int deletedAfterGrace = dataRetentionService.purgeExpiredDeletionRequests(graceUntil.plusSeconds(1));
        assertThat(deletedAfterGrace).isEqualTo(1);

        // 4. Verify ZERO residual rows for userA across all 10 user-owned tables
        for (String table : USER_OWNED_TABLES) {
            assertThat(countRowsForUser(table, userAId))
                    .as("Post-delete residual rows in %s for deleted userA", table)
                    .isZero();
        }
        assertThat(countUserRow(userAId)).as("users row for deleted userA").isZero();

        // 5. Verify userA's token is immediately rejected with 401
        mvc.perform(as(userA, get("/api/transactions")))
                .andExpect(status().isUnauthorized());

        // 6. Verify userB's rows across all 10 tables are 100% unchanged
        assertThat(snapshotCounts(userBId)).isEqualTo(userBCountsBefore);
        assertThat(countUserRow(userBId)).isEqualTo(1);
    }

    @Test
    @DisplayName("S1-05: Export JSON & CSV are complete, strictly tenant-isolated, and neutralise spreadsheet formula injection")
    void exportJsonAndCsvAreCompleteTenantIsolatedAndFormulaSafe() throws Exception {
        TestUser userA = registerUser();
        TestUser userB = registerUser();
        String userAId = getUserId(userA.email());
        String userBId = getUserId(userB.email());

        seedEveryTableForUser(userAId, "TENANT-A-SECRET");
        seedEveryTableForUser(userBId, "TENANT-B-OWNED");

        // Inject adversarial spreadsheet formulas into userB's transaction title and note
        jdbc.update(
                """
                INSERT INTO transactions (
                    id, user_id, title, amount, type, txn_date, category,
                    category_source, category_needs_review, note, source, created_at
                ) VALUES (?, ?, ?, 999.00, 'EXPENSE', '2026-07-15', 'SHOPPING', 'USER', false, ?, 'MANUAL', now())
                """,
                "tx-formula-" + userBId,
                userBId,
                "=cmd|' /C calc'!A0",
                "  @SUM(1+1)"
        );

        // 1. Export JSON for userB
        JsonNode jsonExport = json.readTree(mvc.perform(as(userB, post("/api/privacy/export"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        JsonNode counts = jsonExport.path("recordCounts");
        assertThat(counts.path("transactions").asInt()).isEqualTo(2);
        assertThat(counts.path("investments").asInt()).isEqualTo(1);
        assertThat(counts.path("loans").asInt()).isEqualTo(1);
        assertThat(counts.path("uploadJobs").asInt()).isEqualTo(1);
        assertThat(counts.path("budgets").asInt()).isEqualTo(1);
        assertThat(counts.path("goals").asInt()).isEqualTo(1);
        assertThat(counts.path("goalContributions").asInt()).isEqualTo(1);
        assertThat(counts.path("savedScenarios").asInt()).isEqualTo(1);
        assertThat(counts.path("totalRecords").asInt()).isEqualTo(9);

        assertThat(jsonExport.path("transactions")).hasSize(2);
        assertThat(jsonExport.path("investments")).hasSize(1);
        assertThat(jsonExport.path("loans")).hasSize(1);
        assertThat(jsonExport.path("uploadJobs")).hasSize(1);
        assertThat(jsonExport.path("budgets")).hasSize(1);
        assertThat(jsonExport.path("goals")).hasSize(1);
        assertThat(jsonExport.path("goalContributions")).hasSize(1);
        assertThat(jsonExport.path("savedScenarios")).hasSize(1);

        // Cross-tenant isolation check: zero data from userA appears in userB's export
        String rawJsonExport = jsonExport.toString();
        assertThat(rawJsonExport)
                .contains("TENANT-B-OWNED")
                .doesNotContain("TENANT-A-SECRET")
                .doesNotContain(userA.email())
                .doesNotContain(userAId);

        // 2. Export CSV for userB and verify formula neutralisation
        String csvExport = mvc.perform(as(userB, post("/api/privacy/export/csv"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"finguard-export.csv\""))
                .andReturn().getResponse().getContentAsString();

        assertThat(csvExport)
                .contains("'=cmd|' /C calc'!A0")
                .contains("'  @SUM(1+1)")
                .contains("TENANT-B-OWNED")
                .doesNotContain("TENANT-A-SECRET")
                .doesNotContain(userA.email());

        // Unit check on all formula prefixes (=, +, -, @, \t, \r)
        assertThat(CsvFormulaSanitizer.neutralizeFormula("=1+1")).isEqualTo("'=1+1");
        assertThat(CsvFormulaSanitizer.neutralizeFormula("+1+1")).isEqualTo("'+1+1");
        assertThat(CsvFormulaSanitizer.neutralizeFormula("-1+1")).isEqualTo("'-1+1");
        assertThat(CsvFormulaSanitizer.neutralizeFormula("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(CsvFormulaSanitizer.neutralizeFormula("\t=1+1")).isEqualTo("'\t=1+1");
        assertThat(CsvFormulaSanitizer.neutralizeFormula("\r=1+1")).isEqualTo("'\r=1+1");
        assertThat(CsvFormulaSanitizer.neutralizeFormula("Normal Narration")).isEqualTo("Normal Narration");
    }

    @Test
    @DisplayName("S1-05: Audit trail records login, category review, export, and delete without narrations; DB trigger blocks mutation")
    void auditTrailRecordsLifecycleEventsConformingToSchemasAndEnforcesAppendOnlyTrigger() throws Exception {
        TestUser user = registerUser();
        String userId = getUserId(user.email());
        String tenantTag = "user:" + userId;
        String tenantHash = TraceContext.hashUserId(userId);

        // 1. Login -> emits com.finguard.auth.logged_in
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", user.email(), "password", PASSWORD))))
                .andExpect(status().isOk());

        // 2. Create and review a transaction category -> emits ledger.period_changed + transaction.category_reviewed
        seedEveryTableForUser(userId, "SENSITIVE-NARRATION-DO-NOT-LOG");
        mvc.perform(as(user, patch("/api/transactions/tx-" + userId + "/category"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"DINING\"}"))
                .andExpect(status().isOk());

        // 3. Export account -> emits com.finguard.account.exported
        mvc.perform(as(user, post("/api/privacy/export"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", PASSWORD))))
                .andExpect(status().isOk());

        // 4. Inspect /api/privacy/audit-log before hard delete
        JsonNode auditLogApi = json.readTree(mvc.perform(as(user, get("/api/privacy/audit-log")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(auditLogApi.isArray()).isTrue();
        assertThat(auditLogApi.toString())
                .doesNotContain("SENSITIVE-NARRATION-DO-NOT-LOG")
                .doesNotContain(user.email());

        // 5. Immediate hard delete -> emits com.finguard.account.deletion_requested + com.finguard.account.deleted
        JsonNode deleteResp = json.readTree(mvc.perform(as(user, post("/api/privacy/delete-request"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", PASSWORD, "immediate", true))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(deleteResp.path("status").asText()).isEqualTo("DELETED");
        assertThat(deleteResp.path("deletedRows").asInt()).isGreaterThanOrEqualTo(10);

        // 6. Verify audit_log rows survive user deletion and include login, export, delete, and category review
        List<String> recordedEventTypes = jdbc.queryForList(
                "SELECT event_type FROM audit_log WHERE tenant_hash = ? ORDER BY occurred_at ASC",
                String.class,
                tenantHash
        );
        assertThat(recordedEventTypes).contains(
                DomainEventEnvelope.TYPE_AUTH_LOGGED_IN,
                DomainEventEnvelope.TYPE_CATEGORY_REVIEWED,
                DomainEventEnvelope.TYPE_ACCOUNT_EXPORTED,
                DomainEventEnvelope.TYPE_ACCOUNT_DELETION_REQUESTED,
                DomainEventEnvelope.TYPE_ACCOUNT_DELETED
        );

        // 7. Validate all emitted outbox events against contracts/events/*.v1.json
        List<Map<String, Object>> outboxRows = jdbc.queryForList(
                "SELECT event_type, payload::text AS payload_text FROM event_outbox WHERE tenant = ?",
                tenantTag
        );
        for (Map<String, Object> row : outboxRows) {
            String eventType = (String) row.get("event_type");
            String payloadText = (String) row.get("payload_text");
            assertThat(payloadText)
                    .doesNotContain("SENSITIVE-NARRATION-DO-NOT-LOG")
                    .doesNotContain(user.email());

            JsonNode envelope = json.readTree(payloadText);
            String shortName = eventType.replace("com.finguard.", "");
            Path schemaPath = CONTRACTS_DIR.resolve(shortName + ".v1.json");
            assertThat(Files.exists(schemaPath)).as("Schema %s must exist", schemaPath).isTrue();
            JsonNode schema = json.readTree(Files.readString(schemaPath, StandardCharsets.UTF_8));
            assertConformsToSchema(envelope, schema);
        }

        // 8. Verify PostgreSQL trigger trg_audit_log_append_only blocks UPDATE and DELETE on audit_log
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_log SET event_type = 'tampered' WHERE tenant_hash = ?", tenantHash))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_log is append-only");

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM audit_log WHERE tenant_hash = ?", tenantHash))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit_log is append-only");
    }

    @Test
    @DisplayName("S1-05: Re-authentication rejects wrong password and rate limiter throttles excessive privacy requests")
    void reAuthenticationAndRateLimitingProtectPrivacyEndpoints() throws Exception {
        TestUser user = registerUser();

        // Wrong password rejected with 401 on export and delete-request
        mvc.perform(as(user, post("/api/privacy/export"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", "wrong-password-123"))))
                .andExpect(status().isUnauthorized());

        mvc.perform(as(user, post("/api/privacy/delete-request"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", "wrong-password-123", "immediate", true))))
                .andExpect(status().isUnauthorized());

        // Exceeding MAX_PRIVACY_ACTIONS_PER_WINDOW (10) returns 429 Too Many Requests
        for (int i = 1; i < AuthRateLimiter.MAX_PRIVACY_ACTIONS_PER_WINDOW; i++) {
            mvc.perform(as(user, post("/api/privacy/export"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("password", PASSWORD))))
                    .andExpect(status().isOk());
        }

        mvc.perform(as(user, post("/api/privacy/export"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", PASSWORD))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("S1-05: Retention jobs purge >90d row_errors_json, >14d published outbox events, and >30d inbox records")
    void dataRetentionJobsPurgeExpiredArtifactsWhilePreservingRecentAndUnpublishedRecords() throws Exception {
        TestUser user = registerUser();
        String userId = getUserId(user.email());
        Instant now = Instant.now();

        // 1. Seed upload_jobs: one 95 days old with row_errors_json, one 10 days old with row_errors_json
        String oldJobId = "job-old-" + UUID.randomUUID();
        String recentJobId = "job-recent-" + UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO upload_jobs (
                    id, user_id, status, row_errors_json, created_at, completed_at
                ) VALUES (?, ?, 'COMPLETED', '[{"row":2,"reason":"bad date"}]', ?, ?)
                """,
                oldJobId, userId,
                Timestamp.from(now.minus(Duration.ofDays(95))),
                Timestamp.from(now.minus(Duration.ofDays(95)))
        );
        jdbc.update(
                """
                INSERT INTO upload_jobs (
                    id, user_id, status, row_errors_json, created_at, completed_at
                ) VALUES (?, ?, 'COMPLETED', '[{"row":3,"reason":"bad amount"}]', ?, ?)
                """,
                recentJobId, userId,
                Timestamp.from(now.minus(Duration.ofDays(10))),
                Timestamp.from(now.minus(Duration.ofDays(10)))
        );

        int clearedErrors = dataRetentionService.purgeExpiredRowErrors(now);
        assertThat(clearedErrors).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT row_errors_json FROM upload_jobs WHERE id = ?", String.class, oldJobId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT row_errors_json FROM upload_jobs WHERE id = ?", String.class, recentJobId))
                .isNotNull();

        // 2. Seed event_outbox: one published 20 days ago, one unpublished 20 days ago, one published 2 days ago
        UUID oldPublishedId = DomainEventEnvelope.uuidV7();
        UUID oldUnpublishedId = DomainEventEnvelope.uuidV7();
        UUID recentPublishedId = DomainEventEnvelope.uuidV7();

        jdbc.update(
                """
                INSERT INTO event_outbox (
                    id, aggregate_type, aggregate_id, event_type, schema_version,
                    tenant, occurred_at, payload, published_at, attempts
                ) VALUES (?, 'user', ?, 'com.finguard.auth.logged_in', 1, ?, ?, '{}'::jsonb, ?, 1)
                """,
                oldPublishedId, userId, "user:" + userId,
                Timestamp.from(now.minus(Duration.ofDays(20))),
                Timestamp.from(now.minus(Duration.ofDays(20)))
        );
        jdbc.update(
                """
                INSERT INTO event_outbox (
                    id, aggregate_type, aggregate_id, event_type, schema_version,
                    tenant, occurred_at, payload, published_at, attempts
                ) VALUES (?, 'user', ?, 'com.finguard.auth.logged_in', 1, ?, ?, '{}'::jsonb, NULL, 0)
                """,
                oldUnpublishedId, userId, "user:" + userId,
                Timestamp.from(now.minus(Duration.ofDays(20)))
        );
        jdbc.update(
                """
                INSERT INTO event_outbox (
                    id, aggregate_type, aggregate_id, event_type, schema_version,
                    tenant, occurred_at, payload, published_at, attempts
                ) VALUES (?, 'user', ?, 'com.finguard.auth.logged_in', 1, ?, ?, '{}'::jsonb, ?, 1)
                """,
                recentPublishedId, userId, "user:" + userId,
                Timestamp.from(now.minus(Duration.ofDays(2))),
                Timestamp.from(now.minus(Duration.ofDays(2)))
        );

        int purgedOutbox = dataRetentionService.purgePublishedOutboxEvents(now);
        assertThat(purgedOutbox).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE id = ?", Integer.class, oldPublishedId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE id = ?", Integer.class, oldUnpublishedId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE id = ?", Integer.class, recentPublishedId)).isEqualTo(1);

        // Clean up the synthetic unpublished row so it does not interfere with other tests
        jdbc.update("DELETE FROM event_outbox WHERE id = ?", oldUnpublishedId);

        // 3. Seed processed_event: one 35 days old, one 5 days old
        UUID oldInboxEventId = DomainEventEnvelope.uuidV7();
        UUID recentInboxEventId = DomainEventEnvelope.uuidV7();
        jdbc.update(
                "INSERT INTO processed_event (consumer, event_id, processed_at) VALUES ('retention-test', ?, ?)",
                oldInboxEventId, Timestamp.from(now.minus(Duration.ofDays(35)))
        );
        jdbc.update(
                "INSERT INTO processed_event (consumer, event_id, processed_at) VALUES ('retention-test', ?, ?)",
                recentInboxEventId, Timestamp.from(now.minus(Duration.ofDays(5)))
        );

        int purgedInbox = dataRetentionService.purgeProcessedInboxEvents(now);
        assertThat(purgedInbox).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM processed_event WHERE consumer = 'retention-test' AND event_id = ?",
                Integer.class, oldInboxEventId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM processed_event WHERE consumer = 'retention-test' AND event_id = ?",
                Integer.class, recentInboxEventId)).isEqualTo(1);
    }

    private String getUserId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, email);
    }

    private int countRowsForUser(String table, String userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
        return count != null ? count : 0;
    }

    private int countUserRow(String userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, userId);
        return count != null ? count : 0;
    }

    private Map<String, Integer> snapshotCounts(String userId) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String table : USER_OWNED_TABLES) {
            counts.put(table, countRowsForUser(table, userId));
        }
        return counts;
    }

    private void seedEveryTableForUser(String userId, String marker) {
        String jobId = "job-" + userId;
        String txId = "tx-" + userId;
        String loanId = "ln-" + userId;
        String invId = "inv-" + userId;
        String budgetId = "bdg-" + userId;
        String goalId = "goal-" + userId;
        String gcId = "gc-" + userId;
        String scId = "sc-" + userId;

        jdbc.update(
                """
                INSERT INTO upload_jobs (
                    id, user_id, bank_name, original_filename, status, total_rows, rows_processed,
                    duplicate_rows, rejected_rows, skipped_rows, closing_balance,
                    classification_status, classified_rows, created_at
                ) VALUES (?, ?, 'HDFC', ?, 'COMPLETED', 1, 1, 0, 0, 0, 50000.00, 'COMPLETED', 1, now())
                """,
                jobId, userId, marker + "-statement.csv"
        );

        jdbc.update(
                """
                INSERT INTO transactions (
                    id, user_id, title, amount, type, txn_date, category, category_source,
                    category_confidence, category_needs_review, note, source, import_job_id, fingerprint, created_at
                ) VALUES (?, ?, ?, 1500.00, 'EXPENSE', '2026-07-10', 'GROCERIES', 'RULE', 0.9000, false, ?, 'IMPORT', ?, ?, now())
                """,
                txId, userId, marker + " Grocery Mart", marker + " note", jobId, "fp-" + userId
        );

        jdbc.update(
                """
                INSERT INTO loans (
                    id, user_id, name, type, principal, emi_amount, interest_rate,
                    start_date, end_date, active, created_at
                ) VALUES (?, ?, ?, 'HOME', 2000000.00, 22000.00, 8.5000, '2025-01-01', '2040-01-01', true, now())
                """,
                loanId, userId, marker + " Home Loan"
        );

        jdbc.update(
                """
                INSERT INTO investments (
                    id, user_id, name, type, amount_invested, current_value, notes, created_at
                ) VALUES (?, ?, ?, 'MUTUAL_FUND', 50000.00, 55000.00, ?, now())
                """,
                invId, userId, marker + " Index Fund", marker + " SIP"
        );

        jdbc.update(
                """
                INSERT INTO budgets (
                    id, user_id, year_month, category, amount, created_at, updated_at
                ) VALUES (?, ?, '2026-07', 'GROCERIES', 12000.00, now(), now())
                """,
                budgetId, userId
        );

        jdbc.update(
                """
                INSERT INTO financial_goals (
                    id, user_id, name, description, target_amount, current_amount,
                    target_date, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 100000.00, 10000.00, '2027-12-31', 'ACTIVE', now(), now())
                """,
                goalId, userId, marker + " Goal", marker + " Goal Description"
        );

        jdbc.update(
                """
                INSERT INTO goal_contributions (
                    id, goal_id, user_id, amount, contribution_date, note, created_at
                ) VALUES (?, ?, ?, 10000.00, '2026-07-11', ?, now())
                """,
                gcId, goalId, userId, marker + " Contribution"
        );

        jdbc.update(
                """
                INSERT INTO saved_scenarios (
                    id, user_id, name, horizon_months, request_json, result_json, method_version, created_at
                ) VALUES (?, ?, ?, 6, '{"horizonMonths":6}', '{"available":true}', '2.0.0', now())
                """,
                scId, userId, marker + " Scenario"
        );
    }

    private static void assertConformsToSchema(JsonNode instance, JsonNode schema) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode req : required) {
                assertThat(instance.has(req.asText()))
                        .as("Missing required property '%s' in %s", req.asText(), instance)
                        .isTrue();
            }
        }
        JsonNode props = schema.get("properties");
        if (props != null && props.isObject() && !schema.path("additionalProperties").asBoolean(true)) {
            Set<String> allowed = new HashSet<>();
            Iterator<String> fieldNames = props.propertyNames().iterator();
            while (fieldNames.hasNext()) {
                allowed.add(fieldNames.next());
            }
            Iterator<String> actualNames = instance.propertyNames().iterator();
            while (actualNames.hasNext()) {
                String field = actualNames.next();
                assertThat(allowed)
                        .as("Unexpected property '%s' not permitted by schema", field)
                        .contains(field);
            }
            if (props.has("data") && instance.has("data")) {
                assertConformsToSchema(instance.get("data"), props.get("data"));
            }
        }
    }
}

