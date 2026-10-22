package com.finguard.backend.categorization;

/** What automatic classification achieved for a set of transactions. */
public enum ClassificationStatus {
    /** Nothing was sent to the AI service (no candidate rows, or the row predates classification). */
    NOT_ATTEMPTED,
    /** The AI service answered every request; some rows may still be uncategorised because it abstained. */
    COMPLETED,
    /** The AI service answered only part of the request (some batches failed or were skipped). */
    PARTIAL,
    /** The AI service could not be reached or answered nothing usable. Transactions are saved regardless. */
    UNAVAILABLE
}
