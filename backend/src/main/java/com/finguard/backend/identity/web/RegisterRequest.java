package com.finguard.backend.identity.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 100, message = "Name must be at most 100 characters") String name,
        @NotBlank @Email @Size(max = 254, message = "Email must be at most 254 characters") String email,
        @NotBlank @Size(min = 10, max = 128, message = "Password must be at least 10 characters (and at most 128)") String password
) {
    public RegisterRequest {
        if (email != null) {
            email = email.trim().toLowerCase();
        }
    }
}
