package com.finguard.backend.privacy.dto;

public record AccountDeletionResponse(
        String status,
        String requestedAt,
        String graceUntil,
        int deletedRows
) {
}

