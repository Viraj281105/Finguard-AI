package com.finguard.backend.identity.web;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthResponse(String accessToken, String refreshToken, String email, String name) {

    public static AuthResponse of(String accessToken, String email, String name) {
        return new AuthResponse(accessToken, null, email, name);
    }
}
