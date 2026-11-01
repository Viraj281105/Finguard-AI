package com.finguard.backend.common.events;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Contract for idempotent domain event consumers backed by the {@code processed_event} inbox table.
 */
public interface DomainEventConsumer {

    /** Unique stable identifier for this consumer (e.g. {@code "audit-writer"}). */
    String consumerName();

    /** Returns {@code true} if this consumer handles the given CloudEvents {@code type}. */
    boolean supports(String eventType);

    /**
     * Executes the consumer's side effect inside the same database transaction as the
     * {@code processed_event} inbox insert.
     */
    void handle(UUID eventId, String eventType, String aggregateType, String aggregateId,
                String tenant, String traceId, JsonNode envelopeNode);
}
