package com.finguard.backend.intelligence.dto;

import java.math.BigDecimal;
import java.util.List;

public record FinancialSummaryResponse(
        PeriodDto period,
        BigDecimal totalIncome,
        BigDecimal grossExpenses,
        BigDecimal totalRefunds,
        BigDecimal totalExpenses,
        BigDecimal netCashFlow,
        BigDecimal savingsRate,
        boolean savingsRateApplicable,
        String savingsRateCaveat,
        BigDecimal totalInvested,
        BigDecimal totalCurrentValue,
        BigDecimal unrealizedGain,
        BigDecimal gainPercentage,
        int activeLoansCount,
        BigDecimal totalMonthlyEmi,
        BigDecimal totalPrincipal,
        MomComparisonDto momComparison,
        List<String> excludes,
        DataQualitySummaryDto dataQuality,
        List<FinancialInsightDto> topInsights
) {
}
