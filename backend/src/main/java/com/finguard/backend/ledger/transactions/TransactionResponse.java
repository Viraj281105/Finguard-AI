package com.finguard.backend.ledger.transactions;

import com.finguard.backend.ledger.transactions.Transaction.CategorySource;
import com.finguard.backend.ledger.transactions.Transaction.TransactionSource;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * @param category                     canonical category id (see {@link TransactionCategory}) or null
 * @param categorySource               {@code NONE | RULE | MODEL | USER}: who decided the category
 * @param categoryConfidence           raw confidence of an automatic category; meaningful as a probability
 *                                     only when {@code categoryConfidenceCalibrated} is true; null for user categories
 * @param categoryNeedsReview          automatic category on weak or conflicting evidence
 * @param categoryModelVersion         version of the rule set / model behind an automatic category
 * @param categoryRule                 id of the matching rule for rule-based categories
 */
public record TransactionResponse(
        String id,
        String title,
        BigDecimal amount,
        TransactionType type,
        LocalDate date,
        String category,
        String note,
        TransactionSource source,
        CategorySource categorySource,
        BigDecimal categoryConfidence,
        boolean categoryConfidenceCalibrated,
        boolean categoryNeedsReview,
        String categoryModelVersion,
        String categoryRule,
        LocalDateTime createdAt
) {

    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.getId(),
                t.getTitle(),
                t.getAmount(),
                t.getType(),
                t.getDate(),
                t.getCategory(),
                t.getNote(),
                t.getSource(),
                t.getCategorySource(),
                t.getCategoryConfidence(),
                t.isCategoryConfidenceCalibrated(),
                t.isCategoryNeedsReview(),
                t.getCategoryModelVersion(),
                t.getCategoryRule(),
                t.getCreatedAt()
        );
    }
}
