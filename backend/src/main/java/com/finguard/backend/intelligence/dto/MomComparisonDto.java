package com.finguard.backend.intelligence.dto;

import java.math.BigDecimal;

public record MomComparisonDto(
        BigDecimal previousIncome,
        BigDecimal incomeChange,
        BigDecimal incomeChangePct,
        BigDecimal previousExpenses,
        BigDecimal expenseChange,
        BigDecimal expenseChangePct,
        BigDecimal previousNet,
        BigDecimal netChange
) {
}
