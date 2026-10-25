package com.finguard.backend.intelligence.dto;

import java.math.BigDecimal;
import java.util.List;

public record CategoryBreakdownResponse(
        PeriodDto period,
        List<CategorySpendingDto> categories,
        BigDecimal totalGrossExpenses,
        BigDecimal totalRefunds,
        BigDecimal totalNetExpenses,
        String largestCategory,
        List<String> risingCategories
) {
}
