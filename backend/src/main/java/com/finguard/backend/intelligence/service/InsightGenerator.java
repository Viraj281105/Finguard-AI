package com.finguard.backend.intelligence.service;

import com.finguard.backend.intelligence.dto.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Deterministic rule-based insight generator that produces explainable, data-grounded observations.
 */
@Component
public class InsightGenerator {

    public List<FinancialInsightDto> generateInsights(
            PeriodDto period,
            BigDecimal totalIncome,
            BigDecimal totalExpenses,
            BigDecimal netCashFlow,
            BigDecimal savingsRate,
            MomComparisonDto momComparison,
            List<CategorySpendingDto> categories,
            List<RecurringExpenseItemDto> recurringItems,
            List<UnusualTransactionDto> outliers,
            DataQualitySummaryDto dataQuality
    ) {
        List<FinancialInsightDto> list = new ArrayList<>();
        String periodDesc = formatPeriod(period);

        // 1. Cash flow deficit
        if (netCashFlow != null && netCashFlow.compareTo(BigDecimal.ZERO) < 0 && totalIncome.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal deficit = netCashFlow.abs();
            list.add(new FinancialInsightDto(
                    "ins-cash-deficit",
                    "Expenses Exceeded Recorded Income",
                    "ALERT",
                    String.format("Your expenses (₹%s) exceeded recorded income (₹%s) by ₹%s in this period.",
                            totalExpenses.toPlainString(), totalIncome.toPlainString(), deficit.toPlainString()),
                    periodDesc,
                    Map.of("income", totalIncome, "expenses", totalExpenses, "deficit", deficit),
                    "CASH_FLOW_DEFICIT",
                    "Ensure all bank accounts receiving income have been imported."
            ));
        }

        // 2. High savings rate
        if (savingsRate != null && savingsRate.compareTo(BigDecimal.valueOf(20)) >= 0) {
            list.add(new FinancialInsightDto(
                    "ins-savings-rate",
                    "Strong Savings Rate",
                    "POSITIVE",
                    String.format("You saved %s%% of your income this period (₹%s net cash surplus).",
                            savingsRate.toPlainString(), netCashFlow.toPlainString()),
                    periodDesc,
                    Map.of("savingsRate", savingsRate, "netCashFlow", netCashFlow),
                    "SAVINGS_RATE_HEALTHY",
                    "Computed as (Net Cash Flow / Income) * 100 on verified ledger transactions."
            ));
        }

        // 3. Month-over-month total expense change
        if (momComparison != null && momComparison.expenseChangePct() != null) {
            BigDecimal pct = momComparison.expenseChangePct();
            if (pct.compareTo(BigDecimal.valueOf(10)) > 0) {
                list.add(new FinancialInsightDto(
                        "ins-mom-expense-increase",
                        "Monthly Spending Increased",
                        "TREND",
                        String.format("Your expenses increased %s%% compared with the previous month (₹%s vs ₹%s).",
                                pct.toPlainString(), totalExpenses.toPlainString(), momComparison.previousExpenses().toPlainString()),
                        periodDesc,
                        Map.of("currentExpenses", totalExpenses, "previousExpenses", momComparison.previousExpenses(), "changePct", pct),
                        "MOM_EXPENSE_INCREASE",
                        "Comparison reflects calendar month boundaries."
                ));
            } else if (pct.compareTo(BigDecimal.valueOf(-10)) < 0) {
                list.add(new FinancialInsightDto(
                        "ins-mom-expense-decrease",
                        "Monthly Spending Decreased",
                        "POSITIVE",
                        String.format("Your expenses decreased %s%% compared with the previous month (₹%s vs ₹%s).",
                                pct.abs().toPlainString(), totalExpenses.toPlainString(), momComparison.previousExpenses().toPlainString()),
                        periodDesc,
                        Map.of("currentExpenses", totalExpenses, "previousExpenses", momComparison.previousExpenses(), "changePct", pct),
                        "MOM_EXPENSE_DECREASE",
                        "Comparison reflects calendar month boundaries."
                ));
            }
        }

        // 4. Category surge & consistently rising categories
        if (categories != null) {
            for (CategorySpendingDto cat : categories) {
                if (cat.momChangePct() != null && cat.momChangePct().compareTo(BigDecimal.valueOf(15)) >= 0
                        && cat.amount().compareTo(BigDecimal.valueOf(500)) >= 0) {
                    list.add(new FinancialInsightDto(
                            "ins-cat-surge-" + cat.category().toLowerCase(Locale.ROOT),
                            cat.label() + " Expenditure Surge",
                            "TREND",
                            String.format("Your %s expenditure increased %s%% compared with the previous month (now ₹%s).",
                                    cat.label().toLowerCase(Locale.ROOT), cat.momChangePct().toPlainString(), cat.amount().toPlainString()),
                            periodDesc,
                            Map.of("category", cat.category(), "currentAmount", cat.amount(), "changePct", cat.momChangePct()),
                            "CATEGORY_SURGE_15PCT",
                            "Based on classified expense transactions."
                    ));
                    break; // report top surging category
                }
            }

            for (CategorySpendingDto cat : categories) {
                if (cat.isIncreasing()) {
                    list.add(new FinancialInsightDto(
                            "ins-cat-rising-" + cat.category().toLowerCase(Locale.ROOT),
                            "Consistently Rising: " + cat.label(),
                            "TREND",
                            String.format("Your %s spending has increased for consecutive months.", cat.label().toLowerCase(Locale.ROOT)),
                            periodDesc,
                            Map.of("category", cat.category(), "currentAmount", cat.amount()),
                            "CONSISTENTLY_RISING_CATEGORY",
                            "Observed across at least two consecutive monthly increments."
                    ));
                    break;
                }
            }
        }

        // 5. Recurring expense burden
        if (recurringItems != null && !recurringItems.isEmpty() && totalExpenses.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal totalRec = recurringItems.stream()
                    .map(RecurringExpenseItemDto::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (totalRec.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal recPct = totalRec.multiply(BigDecimal.valueOf(100))
                        .divide(totalExpenses, 1, java.math.RoundingMode.HALF_UP);

                if (recPct.compareTo(BigDecimal.valueOf(20)) >= 0) {
                    list.add(new FinancialInsightDto(
                            "ins-rec-burden",
                            "Recurring Payments Commitment",
                            "INFO",
                            String.format("%d recurring payments account for approximately ₹%s per month (%s%% of spending).",
                                    recurringItems.size(), totalRec.toPlainString(), recPct.toPlainString()),
                            periodDesc,
                            Map.of("recurringCount", recurringItems.size(), "monthlyTotal", totalRec, "percentOfExpenses", recPct),
                            "RECURRING_EXPENSE_BURDEN",
                            "Estimated from recurring payment cadences and subscriptions."
                    ));
                }
            }
        }

        // 6. Unusual large transactions
        if (outliers != null && !outliers.isEmpty()) {
            UnusualTransactionDto top = outliers.get(0);
            list.add(new FinancialInsightDto(
                    "ins-unusual-transactions",
                    "Unusually Large Transaction",
                    "ALERT",
                    String.format("%d unusual transaction(s) detected, led by ₹%s for '%s' (%s).",
                            outliers.size(), top.amount().toPlainString(), top.title(), top.category()),
                    periodDesc,
                    Map.of("count", outliers.size(), "largestAmount", top.amount(), "benchmark", top.benchmarkAmount()),
                    "STATISTICAL_OUTLIER_DETECTED",
                    "Flagged using explainable statistical benchmarks (>2.5x median or 2 standard deviations)."
            ));
        }

        // 7. Data quality caveat for uncategorized
        if (dataQuality != null && dataQuality.uncategorizedPercentage().compareTo(BigDecimal.valueOf(15)) > 0) {
            list.add(new FinancialInsightDto(
                    "ins-data-uncategorized",
                    "Uncategorized Transactions Notice",
                    "INFO",
                    String.format("%s%% of your transactions (%d total) are uncategorized. Reviewing them will improve category intelligence.",
                            dataQuality.uncategorizedPercentage().toPlainString(), dataQuality.uncategorizedCount()),
                    periodDesc,
                    Map.of("uncategorizedCount", dataQuality.uncategorizedCount(), "percentage", dataQuality.uncategorizedPercentage()),
                    "HIGH_UNCATEGORIZED_VOLUME",
                    "Uncategorized rows are counted as expenses in net cash flow, but lack category breakdown."
            ));
        }

        return list;
    }

    private String formatPeriod(PeriodDto period) {
        if (period == null || period.from() == null || period.to() == null) {
            return "All available history";
        }
        return period.from() + " to " + period.to();
    }
}
