package com.finguard.backend.privacy.dto;

import jakarta.validation.constraints.NotBlank;

public record PrivacyActionRequest(
        @NotBlank(message = "Current password is required to confirm this privacy operation")
        String password
) {
}

