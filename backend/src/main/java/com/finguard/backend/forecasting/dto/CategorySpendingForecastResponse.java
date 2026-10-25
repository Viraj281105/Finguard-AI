package com.finguard.backend.forecasting.dto;

import com.finguard.backend.forecasting.dto.ForecastCommon.Estimate;
import com.finguard.backend.forecasting.dto.ForecastCommon.HistoryInfo;

import java.math.BigDecimal;
import java.util.List;

/**
 * Projected spending by category. A category appears in {@code categories} only when it has enough
 * evidence of its own (see {@code ForecastingService.MIN_CATEGORY_ACTIVE_MONTHS}); every other category
 * is listed in {@code withoutForecast} with the reason, so nothing is silently dropped.
 */
public record CategorySpendingForecastResponse(
        String reliability,
        String method,
        String algorithmVersion,
        String asOf,
        int horizonMonths,
        HistoryInfo history,
        List<String> warnings,
        List<String> assumptions,
        List<CategoryForecast> categories,
        List<CategoryWithoutForecast> withoutForecast,
        List<String> risingCategories
) {

    /**
     * @param trend                 UPWARD / DOWNWARD only when a statistically tested trend exists, STABLE when the
     *                              level model applies, NOT_ESTABLISHED when there is too little history to test one
     * @param trendPercentPerMonth  fitted monthly change relative to the recent average; null unless a trend exists
     * @param recurringMonthly      detected active recurring payments in this category (already inside the history)
     */
    public record CategoryForecast(
            String category,
            String label,
            int activeMonths,
            BigDecimal recentMonthlyAverage,
            List<Estimate> monthly,
            BigDecimal projectedTotal,
            String trend,
            BigDecimal trendPercentPerMonth,
            String reliability,
            BigDecimal recurringMonthly,
            List<String> outlierMonths,
            String explanation
    ) {
    }

    public record CategoryWithoutForecast(String category, String label, String reason) {
    }
}
