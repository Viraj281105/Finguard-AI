package com.finguard.backend.forecasting.service;

import com.finguard.backend.forecasting.service.ForecastHistory.MonthFigures;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.intelligence.dto.MonthlyFlowDto;
import com.finguard.backend.intelligence.dto.RecurringExpenseItemDto;
import com.finguard.backend.intelligence.service.AnalyticsService;
import com.finguard.backend.intelligence.service.RecurringExpenseDetector;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the {@link ForecastHistory} every forecaster works from. One bounded query per request
 * (the last {@value #WINDOW_MONTHS} months, owner-scoped); all figures come from {@link AnalyticsService}
 * so forecasts and history use the definitions in docs/FINANCIAL_RULES.md: transfers, card payments
 * and investments are excluded, refunds offset expenses, uncategorised credits count as income.
 */
@Component
@RequiredArgsConstructor
public class HistoryLoader {

    public static final int WINDOW_MONTHS = 36;
    /** A recurring payment last seen longer ago than this is treated as cancelled. */
    static final int RECURRING_ACTIVE_DAYS = 45;

    private final TransactionRepository transactionRepository;
    private final AnalyticsService analyticsService;
    private final RecurringExpenseDetector recurringExpenseDetector;
    private final Clock clock;

    public ForecastHistory load(User user) {
        LocalDate asOf = LocalDate.now(clock);
        LocalDate from = YearMonth.from(asOf).minusMonths(WINDOW_MONTHS).atDay(1);
        List<Transaction> window = transactionRepository
                .findByUserIdAndDateGreaterThanEqualOrderByDateDescCreatedAtDesc(user.getId(), from);
        return build(window, asOf);
    }

    /** Pure construction from an already-loaded window; used directly by tests. */
    public ForecastHistory build(List<Transaction> window, LocalDate asOf) {
        YearMonth current = YearMonth.from(asOf);

        List<Transaction> complete = window.stream()
                .filter(t -> YearMonth.from(t.getDate()).isBefore(current))
                .toList();
        List<Transaction> currentMonthTx = window.stream()
                .filter(t -> YearMonth.from(t.getDate()).equals(current))
                .toList();

        List<MonthFigures> usable = new ArrayList<>();
        Set<YearMonth> nonInformative = new HashSet<>();
        for (MonthlyFlowDto flow : analyticsService.monthlyFlowsOf(complete)) {
            YearMonth month = YearMonth.parse(flow.month());
            boolean informative = flow.income().signum() > 0 || flow.grossExpenses().signum() > 0 || flow.refunds().signum() > 0;
            if (informative) {
                usable.add(new MonthFigures(month, flow.income(), flow.expenses(), flow.netCashFlow(), flow.transactionCount()));
            } else {
                nonInformative.add(month);
            }
        }
        usable.sort(Comparator.comparing(MonthFigures::month));

        List<YearMonth> missing = new ArrayList<>();
        if (!usable.isEmpty()) {
            Set<YearMonth> known = usable.stream().map(MonthFigures::month).collect(Collectors.toCollection(HashSet::new));
            for (YearMonth m = usable.get(0).month(); !m.isAfter(current.minusMonths(1)); m = m.plusMonths(1)) {
                if (!known.contains(m) && !nonInformative.contains(m)) {
                    missing.add(m);
                }
            }
        }

        MonthFigures currentSoFar = analyticsService.monthlyFlowsOf(currentMonthTx).stream().findFirst()
                .map(f -> new MonthFigures(current, f.income(), f.expenses(), f.netCashFlow(), f.transactionCount()))
                .orElse(null);

        Set<YearMonth> usableSet = usable.stream().map(MonthFigures::month).collect(Collectors.toSet());
        Map<YearMonth, Map<String, BigDecimal>> categoryNet = new HashMap<>();
        analyticsService.monthlyCategoryNetSpendOf(complete).forEach((m, v) -> {
            if (usableSet.contains(m)) {
                categoryNet.put(m, v);
            }
        });

        int uncategorised = 0;
        int usableTransactions = 0;
        for (Transaction t : complete) {
            if (usableSet.contains(YearMonth.from(t.getDate()))) {
                usableTransactions++;
                if (t.getCategory() == null || "UNCATEGORIZED".equalsIgnoreCase(t.getCategory())) {
                    uncategorised++;
                }
            }
        }

        boolean firstPartial = false;
        if (!usable.isEmpty()) {
            YearMonth first = usable.get(0).month();
            firstPartial = complete.stream()
                    .filter(t -> YearMonth.from(t.getDate()).equals(first))
                    .map(Transaction::getDate)
                    .min(Comparator.naturalOrder())
                    .map(d -> d.getDayOfMonth() > 10)
                    .orElse(false);
        }

        return new ForecastHistory(asOf, current, List.copyOf(usable), List.copyOf(missing), List.copyOf(nonInformative.stream().sorted().toList()),
                currentSoFar, categoryNet, window, activeRecurring(window, asOf), uncategorised, usableTransactions, firstPartial);
    }

    /**
     * Detected recurring payments that should still be expected: a monthly or weekly cadence that is either
     * user-confirmed or seen at least three times with HIGH/MEDIUM confidence, and seen in the last
     * {@value #RECURRING_ACTIVE_DAYS} days. Annual and irregular payments are not detected by the
     * recurring detector and therefore never appear here.
     */
    List<RecurringExpenseItemDto> activeRecurring(List<Transaction> window, LocalDate asOf) {
        return recurringExpenseDetector.detectRecurring(window).stream()
                .filter(i -> "MONTHLY".equals(i.cadence()) || "WEEKLY".equals(i.cadence()))
                .filter(i -> i.isConfirmed() || (i.occurrences() >= 3 && ("HIGH".equals(i.confidence()) || "MEDIUM".equals(i.confidence()))))
                .filter(i -> i.lastDate() != null && ChronoUnit.DAYS.between(i.lastDate(), asOf) <= RECURRING_ACTIVE_DAYS)
                .toList();
    }
}
