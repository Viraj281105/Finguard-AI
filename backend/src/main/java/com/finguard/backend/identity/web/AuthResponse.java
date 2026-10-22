package com.finguard.backend.identity.web;

public record AuthResponse(String accessToken, String refreshToken, String email, String name) {
}
