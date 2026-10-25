package com.finguard.backend.intelligence.dto;

import java.math.BigDecimal;

public record MonthlyFlowDto(
        String month, // yyyy-MM
        BigDecimal income,
        BigDecimal grossExpenses,
        BigDecimal refunds,
        BigDecimal expenses, // net of refunds
        BigDecimal netCashFlow,
        BigDecimal savingsRate,
        int transactionCount,
        BigDecimal momExpenseChangePct,
        BigDecimal momIncomeChangePct
) {
}
