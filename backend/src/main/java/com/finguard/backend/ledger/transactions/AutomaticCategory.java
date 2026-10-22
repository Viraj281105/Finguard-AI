package com.finguard.backend.ledger.transactions;

import com.finguard.backend.ledger.transactions.Transaction.CategorySource;

import java.math.BigDecimal;

/**
 * A category proposed by automation, already validated against the taxonomy and the transaction.
 * It is applied only to transactions whose category source is still {@code NONE}.
 *
 * @param source {@code RULE} or {@code MODEL}, never {@code USER} or {@code NONE}
 */
public record AutomaticCategory(
        TransactionCategory category,
        CategorySource source,
        BigDecimal confidence,
        boolean confidenceCalibrated,
        String modelVersion,
        String rule,
        boolean needsReview
) {

    public AutomaticCategory {
        if (source != CategorySource.RULE && source != CategorySource.MODEL) {
            throw new IllegalArgumentException("An automatic category must come from a RULE or a MODEL");
        }
        if (category == TransactionCategory.UNCATEGORIZED) {
            throw new IllegalArgumentException("Automation must abstain instead of assigning UNCATEGORIZED");
        }
    }
}
