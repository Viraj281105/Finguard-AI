package com.finguard.backend.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Body returned by {@code POST /v1/classify}: one result per input id. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClassifyResponse(
        @JsonProperty("model_version") String modelVersion,
        List<AiPrediction> results
) {
}
