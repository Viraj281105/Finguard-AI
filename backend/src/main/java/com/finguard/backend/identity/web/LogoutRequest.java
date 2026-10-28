package com.finguard.backend.identity.web;

public record LogoutRequest(String refreshToken, Boolean allDevices) {
    public LogoutRequest(String refreshToken) {
        this(refreshToken, false);
    }
}
