package com.finguard.backend.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Connection settings for the Python AI service ({@code ai.service.*}).
 * In Docker Compose the URL is {@code http://ai:8000}; locally it is {@code http://localhost:8000}.
 *
 * @param internalToken    shared secret sent as {@code X-Internal-Token}; empty means no header
 * @param maxTotalDuration time budget of one {@code classify} call across all of its batches
 */
@ConfigurationProperties(prefix = "ai.service")
public record AiProperties(
        @DefaultValue("http://localhost:8000") String url,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration readTimeout,
        @DefaultValue("200") int batchSize,
        @DefaultValue("") String internalToken,
        @DefaultValue("90s") Duration maxTotalDuration
) {

    /** The AI service rejects larger batches. */
    static final int MAX_BATCH_SIZE = 500;

    public AiProperties {
        if (batchSize < 1) {
            throw new IllegalStateException("ai.service.batch-size must be at least 1");
        }
        if (batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException("ai.service.batch-size must not exceed " + MAX_BATCH_SIZE);
        }
        if (maxTotalDuration.isNegative() || maxTotalDuration.isZero()) {
            throw new IllegalStateException("ai.service.max-total-duration must be positive");
        }
    }
}
