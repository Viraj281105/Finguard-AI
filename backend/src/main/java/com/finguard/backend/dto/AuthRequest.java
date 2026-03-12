package com.finguard.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AuthRequest {

    private String name; // only used for register

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 6)
    private String password;
}