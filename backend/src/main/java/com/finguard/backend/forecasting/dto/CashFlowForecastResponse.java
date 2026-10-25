package com.finguard.backend.forecasting.dto;

import com.finguard.backend.forecasting.dto.ForecastCommon.Estimate;
import com.finguard.backend.forecasting.dto.ForecastCommon.HistoryInfo;

import java.math.BigDecimal;
import java.util.List;

/**
 * Projected monthly income, spending and net cash flow, kept visibly separate from observed history.
 * When {@code reliability} is {@code UNAVAILABLE}, {@code projected} is empty and {@code summary} is
 * {@code null}: lack of data is never turned into a forecast of zero. {@code income} and {@code net} of a
 * projected point are {@code null} when no income was observed ({@code incomeAvailable = false}).
 */
public record CashFlowForecastResponse(
        String reliability,
        String method,
        String algorithmVersion,
        String asOf,
        int horizonMonths,
        HistoryInfo history,
        List<String> warnings,
        List<String> assumptions,
        String intervalNote,
        boolean incomeAvailable,
        List<ObservedMonth> historical,
        MonthToDate currentMonth,
        List<ProjectedMonth> projected,
        Summary summary,
        List<RecurringInfluence> recurring
) {

    /** Complete calendar month of recorded data. Never projected. */
    public record ObservedMonth(String month, BigDecimal income, BigDecimal expenses, BigDecimal net) {
    }

    /** The calendar month in progress: recorded so far, deliberately excluded from training. */
    public record MonthToDate(String month, BigDecimal income, BigDecimal expenses, BigDecimal net, String note) {
    }

    public record ProjectedMonth(
            String month,
            Estimate income,
            Estimate expenses,
            Estimate net,
            boolean recurringFloorApplied,
            List<String> factors
    ) {
    }

    /** Totals over the horizon; {@code totalIncome}, {@code totalNet} and rates are null without observed income. */
    public record Summary(
            BigDecimal totalIncome,
            BigDecimal totalExpenses,
            BigDecimal totalNet,
            BigDecimal averageMonthlyNet,
            BigDecimal savingsRatePercent
    ) {
    }

    /**
     * A detected recurring payment. {@code includedInBaseline} is always true: the payment is already part of
     * the observed history the baseline is computed from, so it is not added a second time; it only
     * lifts the projection when the projection would fall below the recurring commitments.
     */
    public record RecurringInfluence(
            String id,
            String title,
            String category,
            BigDecimal monthlyAmount,
            String cadence,
            String confidence,
            boolean userConfirmed,
            boolean includedInBaseline
    ) {
    }
}
