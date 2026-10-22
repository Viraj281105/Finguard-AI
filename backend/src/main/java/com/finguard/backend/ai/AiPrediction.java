package com.finguard.backend.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One classification returned by the AI service. {@link AiClient} hands out a prediction only after
 * structural validation (id belongs to the request, confidence is finite and within [0, 1], method is
 * known); whether the category is in the taxonomy and suits the transaction is decided by the caller.
 *
 * @param confidenceCalibrated true only if {@code confidence} is a calibrated probability
 * @param method               {@code rule}, {@code model}, or {@code none} when the service abstained
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiPrediction(
        String id,
        String category,
        double confidence,
        @JsonProperty("confidence_calibrated") boolean confidenceCalibrated,
        String method,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("rule_id") String ruleId,
        @JsonProperty("needs_review") boolean needsReview,
        String reason,
        List<String> alternatives
) {

    public boolean abstained() {
        return "none".equals(method);
    }
}
