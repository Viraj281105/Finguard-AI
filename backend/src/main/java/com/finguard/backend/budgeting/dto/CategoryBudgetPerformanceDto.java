package com.finguard.backend.budgeting.dto;

import java.math.BigDecimal;

public record CategoryBudgetPerformanceDto(
        String budgetId,
        String category,
        String categoryLabel,
        BigDecimal budgetedAmount,
        BigDecimal actualSpend,
        BigDecimal remainingAmount,
        BigDecimal percentageUsed,
        String status,
        int transactionCount
) {}
