package com.finguard.backend.privacy.dto;

import jakarta.validation.constraints.NotBlank;

public record AccountDeletionRequest(
        @NotBlank(message = "Current password is required to confirm account deletion")
        String password,
        Boolean immediate
) {
}

