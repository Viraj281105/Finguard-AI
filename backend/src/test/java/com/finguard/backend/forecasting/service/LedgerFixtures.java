package com.finguard.backend.forecasting.service;

import com.finguard.backend.intelligence.service.AnalyticsService;
import com.finguard.backend.intelligence.service.RecurringExpenseDetector;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.Transaction.CategorySource;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * SYNTHETIC ledgers for the forecasting unit tests: every amount is invented to make the expected result
 * computable by hand. None of it is real user data.
 */
final class LedgerFixtures {

    private LedgerFixtures() {
    }

    static final RecurringExpenseDetector DETECTOR = new RecurringExpenseDetector();
    static final AnalyticsService ANALYTICS = new AnalyticsService(null, null, null, DETECTOR, null, null);

    static HistoryLoader loader() {
        return new HistoryLoader(null, ANALYTICS, DETECTOR, Clock.systemUTC());
    }

    static ForecastingService service() {
        return new ForecastingService(loader(), null, null, new ScenarioSimulator());
    }

    static ForecastHistory history(List<Transaction> tx, LocalDate asOf) {
        return loader().build(tx, asOf);
    }

    static Clock clockAt(LocalDate date) {
        return Clock.fixed(date.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    static Transaction tx(LocalDate date, TransactionType type, String category, String amount, String title) {
        Transaction t = new Transaction();
        t.setDate(date);
        t.setType(type);
        t.setAmount(new BigDecimal(amount));
        t.setTitle(title);
        if (category != null) {
            t.setCategory(category);
            t.setCategorySource(CategorySource.USER);
        }
        return t;
    }

    static Transaction expense(LocalDate date, String category, String amount, String title) {
        return tx(date, TransactionType.EXPENSE, category, amount, title);
    }

    static Transaction income(LocalDate date, String category, String amount, String title) {
        return tx(date, TransactionType.INCOME, category, amount, title);
    }

    /** A title whose first word is unique per month so the recurring detector never groups the months. */
    static String once(String word, YearMonth month) {
        return word + month.toString().replace("-", "");
    }

    /** Salary on the 1st, rent on the 3rd, groceries on the 15th: three transactions, nothing recurring by title. */
    static List<Transaction> month(YearMonth m, String salary, String rent, String groceries) {
        List<Transaction> out = new ArrayList<>();
        if (salary != null) {
            out.add(income(m.atDay(1), "INCOME", salary, once("salary", m)));
        }
        if (rent != null) {
            out.add(expense(m.atDay(3), "HOUSING", rent, once("landlord", m)));
        }
        if (groceries != null) {
            out.add(expense(m.atDay(15), "GROCERIES", groceries, once("market", m)));
        }
        return out;
    }

    /** {@code count} consecutive months starting at {@code first}, each with the given amounts. */
    static List<Transaction> months(YearMonth first, int count, String salary, String rent, String groceries) {
        List<Transaction> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.addAll(month(first.plusMonths(i), salary, rent, groceries));
        }
        return out;
    }

    static LocalDate d(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }
}
