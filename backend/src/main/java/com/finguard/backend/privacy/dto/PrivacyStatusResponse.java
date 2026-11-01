package com.finguard.backend.privacy.dto;

public record PrivacyStatusResponse(
        String userId,
        String email,
        boolean deletionPending,
        String deletionRequestedAt,
        String deletionScheduledAt,
        int gracePeriodDays,
        RetentionPolicyDto retentionPolicy
) {
    public record RetentionPolicyDto(
            int rowErrorsRetentionDays,
            int outboxRetentionDays,
            int inboxRetentionDays,
            int auditLogRetentionDays
    ) {
    }
}

