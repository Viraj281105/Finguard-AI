package com.finguard.backend.common.events;

import com.finguard.backend.common.observability.TraceContext;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.ingestion.ImportApiTest;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import com.finguard.backend.ledger.transactions.TransactionRequest;
import com.finguard.backend.ledger.transactions.TransactionService;
import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance tests for S1-02 (Project B — Transactional Outbox, Consumer Inbox, Dead-Letter Queue,
 * Append-Only Audit Log, and Event JSON Schema Contracts):
 * <ul>
 *   <li><b>Rollback atomicity:</b> a ledger write that rolls back leaves zero rows in {@code event_outbox}
 *       and {@code audit_log}; a committed write leaves exactly one outbox event and one audit record.</li>
 *   <li><b>Crash &amp; restart recovery:</b> an unpublished event committed to {@code event_outbox}
 *       (simulating JVM crash before post-commit hook) is picked up by {@link OutboxEventService#relayPendingEvents()}
 *       and produces its consumer effect once.</li>
 *   <li><b>Duplicate delivery idempotency:</b> delivering the same {@code event_id} 100 times through
 *       {@link OutboxEventService#deliverEventById(UUID)} yields exactly one {@code processed_event} row
 *       and one {@code audit_log} row.</li>
 *   <li><b>Dead-letter handling:</b> poison payloads or repeated consumer failures move to {@code event_dead_letter}.</li>
 *   <li><b>Schema &amp; privacy validation:</b> every emitted event conforms to {@code contracts/events/*.v1.json}
 *       with {@code additionalProperties: false} and never contains transaction narration text or PII.</li>
 * </ul>
 */
class OutboxInboxAndEventSchemaTest extends IntegrationTest {

    private static final Path CONTRACTS_DIR = Path.of("..").resolve("contracts").resolve("events").normalize();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private OutboxEventService outboxEventService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void rolledBackLedgerWriteLeavesZeroOutboxEventsWhileCommittedWritePersistsAndDeliversExactlyOne() throws Exception {
        TestUser testUser = registerUser();
        User userEntity = getUserEntity(testUser.email());
        String tenantTag = "user:" + userEntity.getId();
        String tenantHash = TraceContext.hashUserId(userEntity.getId());

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // 1. Force a rollback after TransactionService.create() writes to transactions + event_outbox
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            transactionService.create(
                    new TransactionRequest(
                            "Secret Rollback Coffee",
                            new BigDecimal("250.00"),
                            TransactionType.EXPENSE,
                            LocalDate.of(2026, 3, 15),
                            "DINING",
                            "Private rollback note"
                    ),
                    userEntity
            );
            throw new IllegalStateException("Simulated mid-transaction failure");
        })).isInstanceOf(IllegalStateException.class);

        Integer outboxAfterRollback = jdbc.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE tenant = ?", Integer.class, tenantTag);
        Integer auditAfterRollback = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_hash = ?", Integer.class, tenantHash);
        assertThat(outboxAfterRollback).isZero();
        assertThat(auditAfterRollback).isZero();

        // 2. Commit a valid ledger write -> outbox event and audit_log row are both persisted
        Transaction created = transactionService.create(
                new TransactionRequest(
                        "CONFIDENTIAL-MERCHANT-NARRATION-XYZ",
                        new BigDecimal("1200.00"),
                        TransactionType.EXPENSE,
                        LocalDate.of(2026, 3, 18),
                        "GROCERIES",
                        "CONFIDENTIAL-NOTE-999"
                ),
                userEntity
        );
        assertThat(created.getId()).isNotBlank();

        Integer outboxAfterCommit = jdbc.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE tenant = ? AND event_type = ?",
                Integer.class, tenantTag, DomainEventEnvelope.TYPE_LEDGER_PERIOD_CHANGED);
        Integer auditAfterCommit = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_hash = ? AND event_type = ?",
                Integer.class, tenantHash, DomainEventEnvelope.TYPE_LEDGER_PERIOD_CHANGED);

        assertThat(outboxAfterCommit).isEqualTo(1);
        assertThat(auditAfterCommit).isEqualTo(1);
    }

    @Test
    void duplicateDelivery100TimesAndCrashRecoveryYieldsExactlyOneEffect() throws Exception {
        TestUser testUser = registerUser();
        User userEntity = getUserEntity(testUser.email());
        String tenantHash = TraceContext.hashUserId(userEntity.getId());

        // Simulate a JVM crash between DB commit and post-commit delivery by inserting an unpublished outbox row directly
        UUID eventId = DomainEventEnvelope.uuidV7();
        DomainEventEnvelope<DomainEventEnvelope.LedgerPeriodChangedPayload> envelope = new DomainEventEnvelope<>(
                eventId,
                DomainEventEnvelope.TYPE_LEDGER_PERIOD_CHANGED,
                1,
                "finguard-backend/ledger",
                "2026-03-20T10:00:00Z",
                "ledger/tx-crash-test",
                "user:" + userEntity.getId(),
                "4bf92f3577b34da6a3ce929d0e0e4736",
                new DomainEventEnvelope.LedgerPeriodChangedPayload(List.of("2026-03"), "MANUAL")
        );

        jdbc.update(
                """
                INSERT INTO event_outbox (
                    id, aggregate_type, aggregate_id, event_type, schema_version,
                    tenant, trace_id, occurred_at, payload, published_at, attempts
                ) VALUES (?, 'ledger', ?, ?, 1, ?, ?, now(), ?::jsonb, NULL, 0)
                """,
                eventId,
                userEntity.getId(),
                envelope.type(),
                envelope.tenant(),
                envelope.traceId(),
                json(envelope)
        );

        // Scheduled relay recovers the unpublished event
        int relayed = outboxEventService.relayPendingEvents();
        assertThat(relayed).isGreaterThanOrEqualTo(1);

        // Now deliver the exact same event_id 100 times (simulating at-least-once redelivery storm)
        for (int i = 0; i < 100; i++) {
            outboxEventService.deliverEventById(eventId);
        }

        Integer inboxCount = jdbc.queryForObject(
                "SELECT count(*) FROM processed_event WHERE consumer = ? AND event_id = ?",
                Integer.class, AuditWriterConsumer.CONSUMER_NAME, eventId);
        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE event_id = ? AND tenant_hash = ?",
                Integer.class, eventId, tenantHash);

        assertThat(inboxCount).isEqualTo(1);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void consumerFailureRetriesAndMovesToDeadLetterQueueWithAtomicInboxRollback() {
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        DomainEventConsumer failingConsumer = new DomainEventConsumer() {
            @Override
            public String consumerName() {
                return "always-failing-consumer";
            }

            @Override
            public boolean supports(String eventType) {
                return "com.finguard.test.poison".equals(eventType);
            }

            @Override
            public void handle(UUID eventId, String eventType, String aggregateType, String aggregateId,
                               String tenant, String traceId, JsonNode envelopeNode) {
                callCount.incrementAndGet();
                throw new IllegalStateException("Simulated downstream consumer failure");
            }
        };

        OutboxEventService serviceWithFailingConsumer = new OutboxEventService(
                jdbc,
                json,
                List.of(failingConsumer),
                new com.finguard.backend.common.observability.FinGuardMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                transactionManager,
                true
        );

        UUID failingEventId = DomainEventEnvelope.uuidV7();
        jdbc.update(
                """
                INSERT INTO event_outbox (
                    id, aggregate_type, aggregate_id, event_type, schema_version,
                    tenant, trace_id, occurred_at, payload, published_at, attempts
                ) VALUES (?, 'test', 'agg-fail', 'com.finguard.test.poison', 1, 'user:u-fail', NULL, now(), '{"id":"%s","data":{}}'::jsonb, NULL, 0)
                """.formatted(failingEventId),
                failingEventId
        );

        for (int attempt = 1; attempt <= OutboxEventService.MAX_ATTEMPTS; attempt++) {
            boolean ok = serviceWithFailingConsumer.deliverEventById(failingEventId);
            assertThat(ok).isFalse();
        }
        assertThat(callCount.get()).isEqualTo(OutboxEventService.MAX_ATTEMPTS);

        // Verify that the inbox row rolled back on every failed attempt so retries were not falsely skipped
        Integer inboxRows = jdbc.queryForObject(
                "SELECT count(*) FROM processed_event WHERE consumer = 'always-failing-consumer' AND event_id = ?",
                Integer.class, failingEventId);
        assertThat(inboxRows).isZero();

        Integer dlqCount = jdbc.queryForObject(
                "SELECT count(*) FROM event_dead_letter WHERE event_id = ? AND consumer = 'always-failing-consumer'",
                Integer.class, failingEventId);
        assertThat(dlqCount).isEqualTo(1);

        java.sql.Timestamp publishedAt = jdbc.queryForObject(
                "SELECT published_at FROM event_outbox WHERE id = ?", java.sql.Timestamp.class, failingEventId);
        assertThat(publishedAt).as("Dead-lettered event is marked completed so it no longer blocks the outbox").isNotNull();
    }

    @Test
    void importAndCategoryReviewEmitEventsMatchingJsonSchemasWithZeroNarrationOrPiiLeakage() throws Exception {
        TestUser user = registerUser();
        User userEntity = getUserEntity(user.email());
        String tenantTag = "user:" + userEntity.getId();

        // 1. Trigger statement import -> emits import.completed + ledger.period_changed
        String jobId = json.readTree(mvc.perform(as(user, multipart("/api/upload/csv").file(ImportApiTest.csv("hdfc.csv"))))
                        .andExpect(status().isAccepted())
                        .andReturn().getResponse().getContentAsString())
                .get("jobId").asText();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            JsonNode jobNode = json.readTree(mvc.perform(as(user, get("/api/upload/status/" + jobId)))
                    .andReturn().getResponse().getContentAsString());
            assertThat(jobNode.get("status").asText()).isEqualTo("COMPLETED");
        });

        // 2. Review a transaction category -> emits transaction.category_reviewed + ledger.period_changed
        JsonNode txList = json.readTree(mvc.perform(as(user, get("/api/transactions")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String txId = txList.get(0).get("id").asText();

        mvc.perform(as(user, patch("/api/transactions/" + txId + "/category"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"DINING\"}"))
                .andExpect(status().isOk());

        // 3. Inspect all emitted events for this tenant and validate against contracts/events/*.v1.json
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_type, payload::text AS payload_text FROM event_outbox WHERE tenant = ? ORDER BY occurred_at ASC",
                tenantTag
        );
        assertThat(rows).hasSizeGreaterThanOrEqualTo(4);

        Set<String> observedTypes = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String eventType = (String) row.get("event_type");
            String payloadText = (String) row.get("payload_text");
            observedTypes.add(eventType);

            // Privacy check: payload must never contain transaction narration text or email
            assertThat(payloadText)
                    .doesNotContain("SWIGGY")
                    .doesNotContain("SALARY")
                    .doesNotContain("KORAMANGALA")
                    .doesNotContain(user.email());

            JsonNode envelope = json.readTree(payloadText);
            String shortName = eventType.replace("com.finguard.", "");
            Path schemaPath = CONTRACTS_DIR.resolve(shortName + ".v1.json");
            assertThat(Files.exists(schemaPath))
                    .as("JSON Schema must exist at %s", schemaPath)
                    .isTrue();

            JsonNode schema = json.readTree(Files.readString(schemaPath, StandardCharsets.UTF_8));
            assertConformsToSchema(envelope, schema);
        }

        assertThat(observedTypes).contains(
                DomainEventEnvelope.TYPE_IMPORT_COMPLETED,
                DomainEventEnvelope.TYPE_LEDGER_PERIOD_CHANGED,
                DomainEventEnvelope.TYPE_CATEGORY_REVIEWED
        );
    }

    private User getUserEntity(String email) {
        String id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, email);
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
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
        if (props != null && props.isObject() && schema.path("additionalProperties").asBoolean(true) == false) {
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
