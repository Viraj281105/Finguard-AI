package com.finguard.backend.forecasting.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Month-end spending outlook against the v0.7.0 budgets.
 *
 * <p>{@code risk} values and the rule behind each:
 * <ul>
 *   <li>{@code EXCEEDED} - spending recorded so far is already above the budget (a fact, not a forecast).</li>
 *   <li>{@code LIKELY_OVER} - even the lowest available estimate, including detected recurring payments still
 *       due, is above the budget.</li>
 *   <li>{@code AT_RISK} - the available estimates straddle the budget, or the central estimate is within 10 % of it.</li>
 *   <li>{@code ON_TRACK} - the highest available estimate is at or below 90 % of the budget.</li>
 *   <li>{@code INDETERMINATE} - not enough evidence to estimate month-end spending (see {@code indeterminateReason}).</li>
 *   <li>{@code COMPLETED_WITHIN} / {@code COMPLETED_OVER} - the month is over; actual spending versus the budget.</li>
 * </ul>
 */
public record BudgetOverrunRiskResponse(
        String month,
        String asOf,
        String period,
        int daysInMonth,
        int daysWithRecordedData,
        int daysRemaining,
        List<String> warnings,
        Item overall,
        List<Item> categories
) {

    /**
     * @param actualToDate           net spending recorded in the month (refunds offset)
     * @param recurringStillDue      detected recurring payments in scope not yet seen this month
     * @param expectedLow            lowest available month-end estimate; null when INDETERMINATE
     * @param expectedCentral        mean of the available estimates; null when INDETERMINATE
     * @param expectedHigh           highest available estimate; null when INDETERMINATE
     * @param estimatedOverrun       central estimate above the budget, zero otherwise; null when INDETERMINATE
     * @param basis                  which estimates were used: RUN_RATE, HISTORY
     */
    public record Item(
            String budgetId,
            String category,
            String label,
            BigDecimal budgetAmount,
            BigDecimal actualToDate,
            BigDecimal remainingBudget,
            BigDecimal recurringStillDue,
            BigDecimal expectedLow,
            BigDecimal expectedCentral,
            BigDecimal expectedHigh,
            BigDecimal estimatedOverrun,
            String risk,
            String rule,
            List<String> basis,
            String indeterminateReason,
            List<String> factors
    ) {
    }
}
