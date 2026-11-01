package com.finguard.backend.common.events;

import com.finguard.backend.common.observability.TraceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only financial audit writer consumer ({@code audit-writer}).
 *
 * <p>Records an immutable row in {@code audit_log} for every domain event, hashing the tenant ID
 * ({@code user_hash}) and storing only the redacted {@code data} summary (never PII or narration text).
 */
@Component
@RequiredArgsConstructor
public class AuditWriterConsumer implements DomainEventConsumer {

    public static final String CONSUMER_NAME = "audit-writer";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    public String consumerName() {
        return CONSUMER_NAME;
    }

    @Override
    public boolean supports(String eventType) {
        return true;
    }

    @Override
    public void handle(UUID eventId, String eventType, String aggregateType, String aggregateId,
                       String tenant, String traceId, JsonNode envelopeNode) {
        String rawUserId = tenant != null && tenant.startsWith("user:")
                ? tenant.substring("user:".length())
                : tenant;
        String tenantHash = TraceContext.hashUserId(rawUserId);

        JsonNode dataNode = envelopeNode.has("data") ? envelopeNode.get("data") : envelopeNode;
        String summaryJson = objectMapper.writeValueAsString(dataNode);

        Instant occurredAt = Instant.now();
        if (envelopeNode.hasNonNull("time")) {
            try {
                occurredAt = Instant.parse(envelopeNode.get("time").asText());
            } catch (Exception ignored) {
            }
        }

        jdbc.update(
                """
                INSERT INTO audit_log (
                    id, event_id, tenant_hash, event_type, aggregate_type, aggregate_id,
                    trace_id, occurred_at, recorded_at, summary_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), ?::jsonb)
                ON CONFLICT (event_id) DO NOTHING
                """,
                DomainEventEnvelope.uuidV7(),
                eventId,
                tenantHash,
                eventType,
                aggregateType,
                aggregateId,
                traceId,
                Timestamp.from(occurredAt),
                summaryJson
        );
    }
}
