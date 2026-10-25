package com.finguard.backend.forecasting.service;

import com.finguard.backend.budgeting.domain.Budget;
import com.finguard.backend.budgeting.domain.BudgetRepository;
import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.forecasting.dto.BudgetOverrunRiskResponse;
import com.finguard.backend.forecasting.dto.BudgetOverrunRiskResponse.Item;
import com.finguard.backend.forecasting.service.ForecastHistory.MonthFigures;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.intelligence.dto.MonthlyFlowDto;
import com.finguard.backend.intelligence.dto.RecurringExpenseItemDto;
import com.finguard.backend.intelligence.service.AnalyticsService;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Month-end budget outlook that is explicit about what it knows.
 *
 * <p>Actual spending uses the v0.7.0 budget definition (debits in expense/unknown categories minus
 * refunds; transfers, card payments and investments excluded). For the current or a future month
 * two independent estimates of month-end spending are formed, each only when its evidence exists:
 * <ul>
 *   <li><b>RUN_RATE</b> (needs at least {@value #MIN_RUN_RATE_DAYS} days of recorded data and enough transactions):
 *       spending so far, plus detected recurring payments still due, plus the <em>discretionary</em> daily rate
 *       (spending so far minus recurring payments already paid, per recorded day) for the remaining days.
 *       Recurring payments are never counted twice: paid ones are removed from the rate, unpaid ones are added once.</li>
 *   <li><b>HISTORY</b> (needs at least {@value #MIN_HISTORY_MONTHS} complete months): the median of the last up to six
 *       complete months in the same scope, which already contains that scope's recurring payments, never below
 *       spending so far plus recurring still due.</li>
 * </ul>
 * See {@link BudgetOverrunRiskResponse} for the risk classes. Only spending that has already happened is ever
 * called certain; a single naive extrapolation can at most raise {@code AT_RISK}.
 */
@Component
@RequiredArgsConstructor
public class BudgetOverrunPredictor {

    static final int MIN_RUN_RATE_DAYS = 7;
    static final int MIN_HISTORY_MONTHS = 3;
    static final int MIN_OVERALL_TRANSACTIONS = 5;
    static final int MIN_CATEGORY_TRANSACTIONS = 3;
    static final double ON_TRACK_FRACTION = 0.90;
    /** A recurring match must be within this relative distance of the detected amount. */
    static final double RECURRING_AMOUNT_TOLERANCE = 0.40;
    static final int STALE_DAYS = 3;

    private final BudgetRepository budgetRepository;
    private final AnalyticsService analyticsService;

    public BudgetOverrunRiskResponse evaluate(User user, YearMonth requested, ForecastHistory history) {
        YearMonth current = history.currentMonth();
        YearMonth target = requested != null ? requested : current;
        YearMonth earliest = current.minusMonths(HistoryLoader.WINDOW_MONTHS);
        YearMonth latest = current.plusMonths(ForecastingService.MAX_HORIZON);
        if (target.isBefore(earliest) || target.isAfter(latest)) {
            throw ApiException.badRequest("yearMonth must be between " + earliest + " and " + latest + ".");
        }

        String period = target.isBefore(current) ? "PAST" : target.equals(current) ? "CURRENT" : "FUTURE";
        int daysInMonth = target.lengthOfMonth();
        List<Transaction> monthTx = history.windowTransactions().stream()
                .filter(t -> YearMonth.from(t.getDate()).equals(target))
                .toList();
        LocalDate lastRecorded = monthTx.stream().map(Transaction::getDate).max(Comparator.naturalOrder()).orElse(null);
        int dataDays = "PAST".equals(period) ? daysInMonth : lastRecorded == null ? 0 : lastRecorded.getDayOfMonth();
        int daysRemaining = "PAST".equals(period) ? 0 : daysInMonth - dataDays;

        List<String> warnings = new ArrayList<>();
        if ("CURRENT".equals(period) && lastRecorded != null && ChronoUnit.DAYS.between(lastRecorded, history.asOf()) > STALE_DAYS) {
            warnings.add("The latest recorded transaction is from " + lastRecorded + ", " + ChronoUnit.DAYS.between(lastRecorded, history.asOf())
                    + " days ago. Spending since then may not be imported; the outlook assumes the recorded days are representative.");
        }
        if ("FUTURE".equals(period)) {
            warnings.add("This month has not started. Any outlook relies on past months only.");
        }

        BigDecimal overallActual = analyticsService.monthlyFlowsOf(monthTx).stream().findFirst()
                .map(MonthlyFlowDto::expenses).orElse(BigDecimal.ZERO.setScale(2));
        Map<String, BigDecimal> categoryActual = analyticsService.monthlyCategoryNetSpendOf(monthTx).getOrDefault(target, Map.of());

        List<Budget> budgets = budgetRepository.findByUserIdAndYearMonthOrderByCategoryAsc(user.getId(), target.toString());
        if (budgets.isEmpty()) {
            warnings.add("No budgets are set for " + target + ".");
        }

        Context base = new Context(period, daysInMonth, dataDays, daysRemaining, history, monthTx);
        Item overall = null;
        List<Item> categories = new ArrayList<>();
        for (Budget b : budgets) {
            if (b.getCategory() == null) {
                overall = item(b, null, "Overall monthly spending", overallActual, base);
            } else {
                categories.add(item(b, b.getCategory(), ForecastingService.label(b.getCategory()),
                        categoryActual.getOrDefault(b.getCategory(), BigDecimal.ZERO.setScale(2)), base));
            }
        }
        return new BudgetOverrunRiskResponse(target.toString(), history.asOf().toString(), period, daysInMonth, dataDays,
                daysRemaining, warnings, overall, categories);
    }

    private record Context(String period, int daysInMonth, int dataDays, int daysRemaining, ForecastHistory history, List<Transaction> monthTx) {
    }

    private Item item(Budget budget, String category, String label, BigDecimal actual, Context ctx) {
        BigDecimal limit = budget.getAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal actualToDate = actual.setScale(2, RoundingMode.HALF_UP);
        BigDecimal remaining = limit.subtract(actualToDate);
        List<String> factors = new ArrayList<>();
        factors.add("Recorded so far: ₹" + actualToDate + " of a ₹" + limit + " budget.");

        if ("PAST".equals(ctx.period())) {
            boolean over = actualToDate.compareTo(limit) > 0;
            return new Item(budget.getId(), category, label, limit, actualToDate, remaining, BigDecimal.ZERO.setScale(2), null, null, null,
                    over ? actualToDate.subtract(limit) : BigDecimal.ZERO.setScale(2),
                    over ? "COMPLETED_OVER" : "COMPLETED_WITHIN",
                    "The month is over: final spending was ₹" + actualToDate + (over ? ", above" : ", within") + " the budget of ₹" + limit + ".",
                    List.of(), null, factors);
        }

        if (actualToDate.compareTo(limit) > 0) {
            factors.add("Spending is already ₹" + actualToDate.subtract(limit) + " above the budget; further spending only adds to it.");
            return new Item(budget.getId(), category, label, limit, actualToDate, remaining, BigDecimal.ZERO.setScale(2), null, null, null,
                    actualToDate.subtract(limit), "EXCEEDED",
                    "Spending recorded so far (₹" + actualToDate + ") is already above the budget (₹" + limit + ").", List.of(), null, factors);
        }

        // Recurring payments in scope: which were already paid this month, which are still due.
        List<RecurringExpenseItemDto> inScope = ctx.history().activeRecurring().stream()
                .filter(r -> category == null || category.equals(ForecastingService.canonical(r.category())))
                .toList();
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal due = BigDecimal.ZERO;
        List<String> dueNames = new ArrayList<>();
        for (RecurringExpenseItemDto r : inScope) {
            BigDecimal matched = matchedThisMonth(r, ctx.monthTx());
            BigDecimal monthly = r.amount();
            BigDecimal paidPart = matched.min(monthly);
            paid = paid.add(paidPart);
            BigDecimal stillDue = monthly.subtract(paidPart);
            if (stillDue.signum() > 0) {
                due = due.add(stillDue);
                dueNames.add(r.title() + " ₹" + stillDue.setScale(2, RoundingMode.HALF_UP));
            }
        }
        paid = paid.setScale(2, RoundingMode.HALF_UP);
        due = due.setScale(2, RoundingMode.HALF_UP);
        if (due.signum() > 0) {
            factors.add("Detected recurring payments not yet seen this month: " + String.join(", ", dueNames) + " (₹" + due + " in total).");
        }
        if (paid.signum() > 0) {
            factors.add("₹" + paid + " of the spending so far is recurring payments, so it is excluded from the daily rate.");
        }
        BigDecimal floor = actualToDate.add(due);

        List<BigDecimal> candidates = new ArrayList<>();
        List<String> basis = new ArrayList<>();
        long scopeTx = ctx.monthTx().stream().filter(t -> t.getType() == TransactionType.EXPENSE)
                .filter(t -> category == null || category.equals(ForecastingService.canonical(t.getCategory()))).count();
        int minTx = category == null ? MIN_OVERALL_TRANSACTIONS : MIN_CATEGORY_TRANSACTIONS;
        if ("CURRENT".equals(ctx.period()) && ctx.dataDays() >= MIN_RUN_RATE_DAYS && scopeTx >= minTx) {
            BigDecimal discretionary = actualToDate.subtract(paid).max(BigDecimal.ZERO);
            BigDecimal dailyRate = discretionary.divide(BigDecimal.valueOf(ctx.dataDays()), 6, RoundingMode.HALF_UP);
            BigDecimal estimate = floor.add(dailyRate.multiply(BigDecimal.valueOf(ctx.daysRemaining()))).setScale(2, RoundingMode.HALF_UP);
            candidates.add(estimate);
            basis.add("RUN_RATE");
            factors.add("Run-rate: ₹" + dailyRate.setScale(2, RoundingMode.HALF_UP) + " per day of non-recurring spending over " + ctx.dataDays()
                    + " recorded day(s), for the remaining " + ctx.daysRemaining() + " day(s) gives ₹" + estimate + ".");
        }

        List<BigDecimal> past = pastMonthlySpend(ctx.history(), category);
        if (past.size() >= MIN_HISTORY_MONTHS) {
            double[] values = past.stream().mapToDouble(BigDecimal::doubleValue).toArray();
            BigDecimal median = ForecastMethod.money(ForecastMethod.median(values));
            BigDecimal estimate = median.max(floor).setScale(2, RoundingMode.HALF_UP);
            candidates.add(estimate);
            basis.add("HISTORY");
            factors.add("History: the median of the last " + past.size() + " complete months in this scope is ₹" + median
                    + (estimate.compareTo(median) > 0 ? ", raised to ₹" + estimate + " because spending so far plus recurring still due is higher." : "."));
        }

        if (candidates.isEmpty()) {
            String reason = "CURRENT".equals(ctx.period())
                    ? "Not enough evidence: " + (ctx.dataDays() < MIN_RUN_RATE_DAYS ? "only " + ctx.dataDays() + " day(s) of data recorded this month (at least "
                    + MIN_RUN_RATE_DAYS + " needed for a run-rate)" : "too few transactions in this scope this month (at least " + minTx + " needed)")
                    + " and fewer than " + MIN_HISTORY_MONTHS + " complete past months."
                    : "No complete past months to base an outlook on (at least " + MIN_HISTORY_MONTHS + " needed).";
            if (floor.compareTo(limit) > 0) {
                factors.add("Recorded spending plus recurring payments still due is ₹" + floor + ", above the budget.");
                return new Item(budget.getId(), category, label, limit, actualToDate, remaining, due, floor, floor, floor,
                        floor.subtract(limit), "LIKELY_OVER",
                        "Recorded spending plus detected recurring payments still due (₹" + floor + ") already exceeds the budget (₹" + limit + ").",
                        List.of("COMMITTED"), null, factors);
            }
            return new Item(budget.getId(), category, label, limit, actualToDate, remaining, due, null, null, null, null,
                    "INDETERMINATE", "No month-end estimate is possible with the evidence available.", List.of(), reason, factors);
        }

        BigDecimal low = candidates.stream().min(Comparator.naturalOrder()).orElseThrow().max(floor);
        BigDecimal high = candidates.stream().max(Comparator.naturalOrder()).orElseThrow().max(floor);
        BigDecimal central = candidates.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(candidates.size()), 2, RoundingMode.HALF_UP).max(floor);
        BigDecimal overrun = central.compareTo(limit) > 0 ? central.subtract(limit) : BigDecimal.ZERO.setScale(2);

        String risk;
        String rule;
        boolean committedOver = floor.compareTo(limit) > 0;
        if (committedOver || (candidates.size() >= 2 && low.compareTo(limit) > 0)) {
            risk = "LIKELY_OVER";
            rule = committedOver
                    ? "Recorded spending plus detected recurring payments still due (₹" + floor + ") already exceeds the budget."
                    : "Both independent estimates (run-rate and history) are above the budget; the lower one is ₹" + low + ".";
        } else if (high.compareTo(limit.multiply(BigDecimal.valueOf(ON_TRACK_FRACTION))) <= 0) {
            risk = "ON_TRACK";
            rule = "The highest available estimate (₹" + high + ") is at or below 90% of the budget.";
        } else {
            risk = "AT_RISK";
            rule = candidates.size() == 1
                    ? "Only one estimate is available (" + basis.get(0) + ", ₹" + high + "); it is above 90% of the budget, which is not enough evidence to call an overrun likely."
                    : "The estimates span ₹" + low + " to ₹" + high + ", which reaches above 90% of the budget (₹" + limit + ").";
        }
        if (candidates.size() == 1) {
            factors.add("Based on a single estimate (" + basis.get(0) + "): treat as indicative.");
        }
        return new Item(budget.getId(), category, label, limit, actualToDate, remaining, due, low, central, high, overrun, risk, rule,
                List.copyOf(basis), null, factors);
    }

    /** Sum of this month's expense transactions that look like the recurring payment (same merchant, similar amount). */
    private BigDecimal matchedThisMonth(RecurringExpenseItemDto item, List<Transaction> monthTx) {
        String merchant = normalise(item.merchant());
        if (merchant.length() < 3) {
            return BigDecimal.ZERO;
        }
        BigDecimal lo = item.amount().multiply(BigDecimal.valueOf(1 - RECURRING_AMOUNT_TOLERANCE));
        BigDecimal hi = item.amount().multiply(BigDecimal.valueOf(1 + RECURRING_AMOUNT_TOLERANCE));
        return monthTx.stream()
                .filter(t -> t.getType() == TransactionType.EXPENSE)
                .filter(t -> normalise(t.getTitle()).contains(merchant))
                .map(Transaction::getAmount)
                .filter(a -> a.compareTo(lo) >= 0 && a.compareTo(hi) <= 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<BigDecimal> pastMonthlySpend(ForecastHistory history, String category) {
        List<MonthFigures> usable = history.usableMonths();
        List<MonthFigures> lastSix = usable.subList(Math.max(0, usable.size() - ForecastMethod.LEVEL_WINDOW), usable.size());
        List<BigDecimal> out = new ArrayList<>();
        for (MonthFigures m : lastSix) {
            out.add(category == null ? m.expenses()
                    : history.categoryNetByMonth().getOrDefault(m.month(), Map.of()).getOrDefault(category, BigDecimal.ZERO));
        }
        return out;
    }

    private static String normalise(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
