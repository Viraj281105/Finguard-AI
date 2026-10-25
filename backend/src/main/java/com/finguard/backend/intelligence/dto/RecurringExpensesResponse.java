package com.finguard.backend.intelligence.dto;

import java.math.BigDecimal;
import java.util.List;

public record RecurringExpensesResponse(
        PeriodDto period,
        List<RecurringExpenseItemDto> items,
        BigDecimal totalMonthlyRecurring,
        BigDecimal recurringPercentageOfExpenses
) {
}
