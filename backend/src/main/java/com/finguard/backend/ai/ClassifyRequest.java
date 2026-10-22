package com.finguard.backend.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * Body of {@code POST /v1/classify} sent to the AI service.
 * {@code id} lets the caller match results back to its own records.
 */
public record ClassifyRequest(List<Item> transactions) {

    /**
     * @param type   {@code INCOME} (credit) or {@code EXPENSE} (debit); null when unknown
     * @param amount positive amount; null when unknown
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String id, String description, String type, BigDecimal amount) {

        /** Longest description the AI service accepts; longer narrations are cut, never rejected. */
        public static final int MAX_DESCRIPTION = 1000;

        public Item {
            description = description == null ? "" : description;
            if (description.length() > MAX_DESCRIPTION) {
                description = description.substring(0, MAX_DESCRIPTION);
            }
        }
    }
}
