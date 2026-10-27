package com.finguard.backend.intelligence.recommendations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * General guidance derived from the caller's own computed figures. Every item carries the evidence it was derived
 * from, each piece pointing at the analytics endpoint (and period) it can be reproduced from. Rules whose inputs are
 * not available are listed in {@code notEvaluated} with the reason, never silently skipped or guessed.
 */
public record RecommendationsResponse(
        Period period,
        String disclaimer,
        boolean healthy,
        List<Recommendation> recommendations,
        List<NotEvaluated> notEvaluated,
        Map<String, BigDecimal> thresholds
) {

    public static final String DISCLAIMER =
            "General guidance based on simple rules applied to your own records. It is not financial, tax or investment advice "
                    + "and does not know your full situation. You can ignore any item.";

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, INFO
    }

    /** The complete months looked at. {@code monthsWithData} counts the months that have recorded transactions. */
    public record Period(LocalDate from, LocalDate to, int months, int monthsWithData) {
    }

    /** One number behind a recommendation and where to reproduce it: {@code source} is an API path with the period. */
    public record Evidence(String metric, BigDecimal value, String unit, String source) {
    }

    /**
     * A pre-filled what-if for the scenario simulator: {@code request} is a body for
     * POST /api/forecast/scenarios/simulate. It is a starting point, not a prediction.
     */
    public record ScenarioPreset(String label, Map<String, Object> request) {
    }

    public record Recommendation(
            String id,
            Severity severity,
            String title,
            String explanation,
            String suggestedAction,
            List<Evidence> evidence,
            /* Rough monthly rupee effect used for ordering; null when it cannot be estimated. */
            BigDecimal estimatedMonthlyImpact,
            ScenarioPreset scenarioPreset
    ) {
    }

    public record NotEvaluated(String id, String title, String reason) {
    }
}
