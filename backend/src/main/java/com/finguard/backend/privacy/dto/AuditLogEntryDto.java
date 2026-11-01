package com.finguard.backend.privacy.dto;

import java.util.Map;

public record AuditLogEntryDto(
        String id,
        String eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String traceId,
        String occurredAt,
        Map<String, Object> summary
) {
}

