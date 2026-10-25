package com.finguard.backend.intelligence.dto;

import java.math.BigDecimal;

public record CategorySpendingDto(
        String category,
        String label,
        String flow,
        BigDecimal amount,
        BigDecimal percentage,
        int transactionCount,
        BigDecimal averageTransactionAmount,
        BigDecimal momChangePct,
        boolean isIncreasing,
        TransactionBriefDto largestTransaction
) {
}
