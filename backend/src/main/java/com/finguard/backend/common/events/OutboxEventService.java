package com.finguard.backend.common.events;

import com.finguard.backend.common.observability.FinGuardMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional Outbox + Consumer Inbox + Dead-Letter Queue dispatcher (S1-02 — Project B).
 *
 * <ul>
 *   <li><b>Producer atomicity:</b> {@link #publish} inserts into {@code event_outbox} inside the caller's
 *       active database transaction. If the caller's transaction rolls back, the outbox row rolls back with it.</li>
 *   <li><b>At-least-once delivery &amp; effectively-once effect:</b> {@link #relayPendingEvents()} and
 *       {@link #deliverEvent} process each consumer inside a transaction that first executes
 *       {@code INSERT INTO processed_event (consumer, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING}.
 *       If {@code 0} rows are inserted, the duplicate delivery is skipped; if the consumer throws, both
 *       the inbox row and the consumer's DB writes roll back together.</li>
 *   <li><b>Dead-letter handling:</b> Poison payloads or events failing {@link #MAX_ATTEMPTS} times are
 *       moved to {@code event_dead_letter} and marked completed so they do not block the queue.</li>
 * </ul>
 */
@Slf4j
@Service
public class OutboxEventService {

    public static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final List<DomainEventConsumer> consumers;
    private final FinGuardMetrics metrics;
    private final TransactionTemplate requiresNewTx;
    private volatile boolean eventsEnabled;

    public OutboxEventService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            List<DomainEventConsumer> consumers,
            FinGuardMetrics metrics,
            PlatformTransactionManager transactionManager,
            @Value("${events.enabled:true}") boolean eventsEnabled
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.consumers = consumers;
        this.metrics = metrics;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.eventsEnabled = eventsEnabled;
    }

    public void setEventsEnabled(boolean enabled) {
        this.eventsEnabled = enabled;
    }

    public boolean isEventsEnabled() {
        return eventsEnabled;
    }

    /**
     * Persists a domain event in {@code event_outbox} within the current transaction and schedules
     * an immediate post-commit relay so in-process consumers run with near-zero latency while
     * preserving crash recovery via the scheduled poller.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public <T> UUID publish(
            String aggregateType,
            String aggregateId,
            String eventType,
            String source,
            String subject,
            String userId,
            T data
    ) {
        if (!eventsEnabled) {
            return null;
        }
        DomainEventEnvelope<T> envelope = DomainEventEnvelope.create(
                eventType, source, subject, userId, data
        );
        String payloadJson = objectMapper.writeValueAsString(envelope);

        jdbc.update(
                """
                INSERT INTO event_outbox (
                    id, aggregate_type, aggregate_id, event_type, schema_version,
                    tenant, trace_id, occurred_at, payload, attempts
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 0)
                """,
                envelope.id(),
                aggregateType,
                aggregateId,
                envelope.type(),
                envelope.schemaVersion(),
                envelope.tenant(),
                envelope.traceId(),
                Timestamp.from(Instant.parse(envelope.time())),
                payloadJson
        );

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            UUID eventId = envelope.id();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        deliverEventById(eventId);
                    } catch (Exception e) {
                        log.warn("Post-commit delivery for event {} deferred to scheduled relay: {}",
                                eventId, e.getMessage());
                    }
                }
            });
        }
        return envelope.id();
    }

    /**
     * Scheduled poller that delivers any unpublished outbox events (e.g. after a JVM crash between
     * commit and post-commit hook, or after a transient consumer error) and updates the SLO-5 lag gauge.
     */
    @Scheduled(fixedDelayString = "${events.relay.interval-ms:5000}")
    public int relayPendingEvents() {
        if (!eventsEnabled) {
            return 0;
        }
        List<UUID> pendingIds = jdbc.queryForList(
                "SELECT id FROM event_outbox WHERE published_at IS NULL ORDER BY occurred_at ASC, id ASC LIMIT 100",
                UUID.class
        );
        int delivered = 0;
        for (UUID id : pendingIds) {
            if (deliverEventById(id)) {
                delivered++;
            }
        }
        refreshOutboxLagMetric();
        return delivered;
    }

    /**
     * Delivers a single outbox event by ID across all registered {@link DomainEventConsumer}s.
     * Can be invoked multiple times with the same {@code eventId} to test duplicate delivery:
     * the {@code processed_event} inbox guarantees each consumer applies its effect at most once.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean deliverEventById(UUID eventId) {
        var rows = jdbc.queryForList(
                "SELECT id, aggregate_type, aggregate_id, event_type, tenant, trace_id, payload::text AS payload_text, attempts "
                        + "FROM event_outbox WHERE id = ? FOR UPDATE",
                eventId
        );
        if (rows.isEmpty()) {
            return false;
        }
        var row = rows.getFirst();
        String aggregateType = (String) row.get("aggregate_type");
        String aggregateId = (String) row.get("aggregate_id");
        String eventType = (String) row.get("event_type");
        String tenant = (String) row.get("tenant");
        String traceId = (String) row.get("trace_id");
        String payloadText = (String) row.get("payload_text");
        int attempts = ((Number) row.get("attempts")).intValue() + 1;

        JsonNode envelopeNode;
        try {
            envelopeNode = objectMapper.readTree(payloadText);
        } catch (Exception parseError) {
            // Poison event: malformed JSON goes straight to dead-letter on first failure
            recordDeadLetter(eventId, "deserializer", eventType, "{}", parseError.getMessage(), attempts);
            jdbc.update(
                    "UPDATE event_outbox SET published_at = now(), attempts = ?, last_error = ? WHERE id = ?",
                    attempts, "POISON_PAYLOAD: " + parseError.getMessage(), eventId
            );
            return false;
        }

        for (DomainEventConsumer consumer : consumers) {
            if (!consumer.supports(eventType)) {
                continue;
            }
            try {
                requiresNewTx.executeWithoutResult(status ->
                        deliverToConsumer(consumer, eventId, eventType, aggregateType, aggregateId, tenant, traceId, envelopeNode)
                );
            } catch (Exception consumerError) {
                log.warn("Consumer {} failed on event {} (attempt {}/{}): {}",
                        consumer.consumerName(), eventId, attempts, MAX_ATTEMPTS, consumerError.getMessage());
                if (attempts >= MAX_ATTEMPTS) {
                    recordDeadLetter(eventId, consumer.consumerName(), eventType, payloadText,
                            consumerError.getMessage() != null ? consumerError.getMessage() : consumerError.getClass().getSimpleName(),
                            attempts);
                    jdbc.update(
                            "UPDATE event_outbox SET published_at = now(), attempts = ?, last_error = ? WHERE id = ?",
                            attempts, "DEAD_LETTER: " + consumerError.getMessage(), eventId
                    );
                } else {
                    jdbc.update(
                            "UPDATE event_outbox SET attempts = ?, last_error = ? WHERE id = ?",
                            attempts, consumerError.getMessage(), eventId
                    );
                }
                return false;
            }
        }

        jdbc.update(
                "UPDATE event_outbox SET published_at = now(), attempts = ?, last_error = NULL WHERE id = ?",
                attempts, eventId
        );
        return true;
    }

    /**
     * Executes a single consumer inside an inbox check (`INSERT INTO processed_event ... ON CONFLICT DO NOTHING`).
     * Returns {@code true} if the consumer ran for the first time, or {@code false} if deduplicated by the inbox.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean deliverToConsumer(
            DomainEventConsumer consumer,
            UUID eventId,
            String eventType,
            String aggregateType,
            String aggregateId,
            String tenant,
            String traceId,
            JsonNode envelopeNode
    ) {
        int inserted = jdbc.update(
                "INSERT INTO processed_event (consumer, event_id, processed_at) VALUES (?, ?, now()) ON CONFLICT (consumer, event_id) DO NOTHING",
                consumer.consumerName(),
                eventId
        );
        if (inserted == 0) {
            return false;
        }
        consumer.handle(eventId, eventType, aggregateType, aggregateId, tenant, traceId, envelopeNode);
        return true;
    }

    private void recordDeadLetter(UUID eventId, String consumer, String eventType, String payloadText, String error, int attempts) {
        jdbc.update(
                """
                INSERT INTO event_dead_letter (
                    id, event_id, consumer, event_type, payload, error, attempts, failed_at
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, now())
                """,
                DomainEventEnvelope.uuidV7(),
                eventId,
                consumer,
                eventType,
                payloadText,
                error != null ? error : "Unknown error",
                attempts
        );
    }

    public void refreshOutboxLagMetric() {
        Double oldestSeconds = jdbc.queryForObject(
                "SELECT coalesce(extract(epoch FROM (now() - min(occurred_at))), 0) FROM event_outbox WHERE published_at IS NULL",
                Double.class
        );
        metrics.setOutboxOldestPendingSeconds(oldestSeconds != null ? Math.round(oldestSeconds) : 0L);
    }
}
