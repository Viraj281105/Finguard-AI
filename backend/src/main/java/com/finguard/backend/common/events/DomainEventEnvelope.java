package com.finguard.backend.common.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.finguard.backend.common.observability.TraceContext;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CloudEvents-style immutable domain event envelope and v1 event payload contracts (S1-02).
 *
 * <p>Every payload is strictly verified against {@code contracts/events/*.v1.json}: no event may
 * ever contain transaction narration text ({@code title}, {@code note}, {@code description}),
 * raw email addresses, or user names.
 */
public record DomainEventEnvelope<T>(
        UUID id,
        String type,
        @JsonProperty("schema_version") int schemaVersion,
        String source,
        String time,
        String subject,
        String tenant,
        @JsonProperty("trace_id") String traceId,
        T data
) {

    public static final String TYPE_IMPORT_COMPLETED = "com.finguard.import.completed";
    public static final String TYPE_LEDGER_PERIOD_CHANGED = "com.finguard.ledger.period_changed";
    public static final String TYPE_CATEGORY_REVIEWED = "com.finguard.transaction.category_reviewed";
    public static final String TYPE_ACCOUNT_DELETION_REQUESTED = "com.finguard.account.deletion_requested";

    public static <T> DomainEventEnvelope<T> create(
            String type,
            String source,
            String subject,
            String userId,
            T data
    ) {
        return new DomainEventEnvelope<>(
                uuidV7(),
                type,
                1,
                source,
                Instant.now().toString(),
                subject,
                "user:" + userId,
                MDC.get(TraceContext.MDC_TRACE_ID),
                data
        );
    }

    /**
     * Generates a time-ordered RFC 9562 UUIDv7 (48-bit Unix epoch ms + 4-bit version 7 + 12-bit rand_a + 2-bit variant + 62-bit rand_b).
     */
    public static UUID uuidV7() {
        long timestampMs = System.currentTimeMillis();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        long randA = rng.nextLong(0, 1L << 12);
        long randB = rng.nextLong() & 0x3FFFFFFFFFFFFFFFL;

        long msb = ((timestampMs & 0xFFFFFFFFFFFFL) << 16) | 0x7000L | randA;
        long lsb = 0x8000000000000000L | randB;
        return new UUID(msb, lsb);
    }

    /** Payload for {@code com.finguard.import.completed} v1. */
    public record ImportCompletedPayload(
            String importJobId,
            String bank,
            int rowsProcessed,
            int duplicateRows,
            int rejectedRows,
            int skippedRows,
            String statementFrom,
            String statementTo,
            String classificationStatus,
            int classifiedRows
    ) {
    }

    /** Payload for {@code com.finguard.ledger.period_changed} v1. */
    public record LedgerPeriodChangedPayload(
            List<String> affectedMonths,
            String cause
    ) {
    }

    /** Payload for {@code com.finguard.transaction.category_reviewed} v1. */
    public record CategoryReviewedPayload(
            String transactionId,
            String decision,
            String previousCategory,
            String newCategory,
            String previousSource,
            String ruleId,
            String modelVersion
    ) {
    }

    /** Payload for {@code com.finguard.account.deletion_requested} v1. */
    public record AccountDeletionRequestedPayload(
            String requestedAt,
            String graceUntil
    ) {
    }
}
