package com.finguard.backend.budgeting.dto;

import java.math.BigDecimal;

public record BudgetPerformanceSummaryDto(
        String budgetId,
        BigDecimal budgetedAmount,
        BigDecimal actualSpend,
        BigDecimal remainingAmount,
        BigDecimal percentageUsed,
        String status
) {}
