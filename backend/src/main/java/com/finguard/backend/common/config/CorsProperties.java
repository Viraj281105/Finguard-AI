package com.finguard.backend.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Browser origins allowed to call the API ({@code app.cors.allowed-origins}). */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
