package com.finguard.backend.intelligence.dto;

import java.util.Map;

public record FinancialInsightDto(
        String id,
        String title,
        String type, // ALERT, TREND, INFO, POSITIVE
        String message,
        String period,
        Map<String, Object> supportingFigures,
        String ruleTriggered,
        String confidenceCaveat
) {
}
