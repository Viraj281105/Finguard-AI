package com.finguard.backend.categorization;

/**
 * Result of {@code POST /api/transactions/reclassify}.
 *
 * @param status             whether the AI service was available
 * @param attempted          uncategorised transactions sent to the AI service in this call
 * @param categorized        how many received a category
 * @param needsReview        of those, how many are flagged for review
 * @param stillUncategorized transactions of the caller that still have no category after this call
 */
public record ReclassifyResponse(
        ClassificationStatus status,
        int attempted,
        int categorized,
        int needsReview,
        long stillUncategorized
) {
}
