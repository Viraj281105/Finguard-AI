package com.finguard.backend.identity.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank String name,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 10, message = "Password must be at least 10 characters") String password
) {
    public RegisterRequest {
        if (email != null) {
            email = email.trim().toLowerCase();
        }
    }
}
