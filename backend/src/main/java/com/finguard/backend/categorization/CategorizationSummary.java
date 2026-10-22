package com.finguard.backend.categorization;

/**
 * @param attempted   transactions sent for classification
 * @param categorized transactions that received a category (and did not already have one from a person)
 * @param needsReview of those, how many rest on weak or conflicting evidence
 */
public record CategorizationSummary(
        ClassificationStatus status,
        int attempted,
        int categorized,
        int needsReview
) {

    public static CategorizationSummary notAttempted() {
        return new CategorizationSummary(ClassificationStatus.NOT_ATTEMPTED, 0, 0, 0);
    }
}
