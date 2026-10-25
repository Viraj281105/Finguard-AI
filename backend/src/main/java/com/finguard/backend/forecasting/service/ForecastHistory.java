package com.finguard.backend.forecasting.service;

import com.finguard.backend.intelligence.dto.RecurringExpenseItemDto;
import com.finguard.backend.ledger.transactions.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * A user's financial history as the forecasters see it: one consistent snapshot, built once per
 * request, using the same definitions as the analytics module.
 *
 * @param asOf              the date the snapshot was taken ("today", from the application clock)
 * @param currentMonth      the calendar month containing {@code asOf}; never part of the training data
 * @param usableMonths      complete calendar months that carry income or spending, oldest first
 * @param missingMonths     complete months between the first usable month and the last complete month
 *                          that have no usable data (gaps and a stale import look the same: unknown, not zero)
 * @param currentMonthSoFar month-to-date figures, or {@code null} when nothing is recorded yet
 * @param categoryNetByMonth net spending per usable month and category id
 * @param windowTransactions every transaction in the history window, newest first (includes the current month)
 */
public record ForecastHistory(
        LocalDate asOf,
        YearMonth currentMonth,
        List<MonthFigures> usableMonths,
        List<YearMonth> missingMonths,
        List<YearMonth> nonInformativeMonths,
        MonthFigures currentMonthSoFar,
        Map<YearMonth, Map<String, BigDecimal>> categoryNetByMonth,
        List<Transaction> windowTransactions,
        List<RecurringExpenseItemDto> activeRecurring,
        int uncategorisedTransactions,
        int usableTransactions,
        boolean firstMonthPossiblyPartial
) {

    /** Income, net expenses (after refunds) and net cash flow of one calendar month. */
    public record MonthFigures(YearMonth month, BigDecimal income, BigDecimal expenses, BigDecimal net, int transactions) {
    }

    public boolean isEmpty() {
        return usableMonths.isEmpty();
    }

    public YearMonth firstUsableMonth() {
        return usableMonths.isEmpty() ? null : usableMonths.get(0).month();
    }

    public YearMonth lastUsableMonth() {
        return usableMonths.isEmpty() ? null : usableMonths.get(usableMonths.size() - 1).month();
    }

    public YearMonth lastCompleteMonth() {
        return currentMonth.minusMonths(1);
    }
}
