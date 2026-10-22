package com.finguard.backend.identity.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
    public LoginRequest {
        if (email != null) {
            email = email.trim().toLowerCase();
        }
    }
}
