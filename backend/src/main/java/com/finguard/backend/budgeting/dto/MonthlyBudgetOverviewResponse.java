package com.finguard.backend.budgeting.dto;

import java.math.BigDecimal;
import java.util.List;

public record MonthlyBudgetOverviewResponse(
        String yearMonth,
        BudgetPerformanceSummaryDto overallBudget,
        List<CategoryBudgetPerformanceDto> categoryBudgets,
        BigDecimal totalCategoryBudgeted,
        BigDecimal totalActualExpenses,
        BigDecimal unbudgetedSpending,
        int categoriesOverBudgetCount,
        int categoriesApproachingCount,
        List<String> warnings
) {}
