package com.finguard.backend.intelligence.service;

import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.intelligence.dto.*;
import com.finguard.backend.ledger.investments.Investment;
import com.finguard.backend.ledger.investments.InvestmentRepository;
import com.finguard.backend.ledger.loans.Loan;
import com.finguard.backend.ledger.loans.LoanRepository;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import com.finguard.backend.ledger.transactions.TransactionCategory;
import com.finguard.backend.ledger.transactions.TransactionCategory.Flow;
import com.finguard.backend.ledger.transactions.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dedicated server-side analytics service implementing verified, reproducible financial calculations
 * with scale-2 BigDecimal arithmetic and explicit exclusion reporting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final List<String> EXCLUDED_FLOWS = List.of("TRANSFERS", "CARD_PAYMENTS", "INVESTMENTS");

    private final TransactionRepository transactionRepository;
    private final InvestmentRepository investmentRepository;
    private final LoanRepository loanRepository;
    private final RecurringExpenseDetector recurringExpenseDetector;
    private final UnusualTransactionDetector unusualTransactionDetector;
    private final InsightGenerator insightGenerator;

    @Transactional(readOnly = true)
    public FinancialSummaryResponse getSummary(User user, LocalDate from, LocalDate to) {
        validateDateRange(from, to);

        List<Transaction> allUserTx = transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc(user.getId());
        List<Transaction> filtered = filterByRange(allUserTx, from, to);

        PeriodDto period = resolvePeriod(filtered, from, to);
        FinancialFigures figures = calculateFigures(filtered);

        // Calculate MoM if applicable
        MomComparisonDto mom = calculateMomComparison(allUserTx, period);

        // Investment calculations
        List<Investment> investments = investmentRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        BigDecimal totalInvested = investments.stream()
                .map(Investment::getAmountInvested)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalCurrentValue = investments.stream()
                .map(i -> i.getCurrentValue() != null ? i.getCurrentValue() : i.getAmountInvested())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal unrealizedGain = totalCurrentValue.subtract(totalInvested).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gainPercentage = totalInvested.compareTo(BigDecimal.ZERO) > 0
                ? unrealizedGain.multiply(BigDecimal.valueOf(100)).divide(totalInvested, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        // Loan calculations
        List<Loan> loans = loanRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        List<Loan> activeLoans = loans.stream().filter(l -> Boolean.TRUE.equals(l.getActive())).toList();
        int activeLoansCount = activeLoans.size();

        BigDecimal totalMonthlyEmi = activeLoans.stream()
                .map(Loan::getEmiAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalPrincipal = activeLoans.stream()
                .map(Loan::getPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        // Data quality block
        DataQualitySummaryDto quality = buildDataQualitySummary(allUserTx, filtered);

        // Recurring & outliers
        List<RecurringExpenseItemDto> recurringItems = recurringExpenseDetector.detectRecurring(allUserTx);
        List<UnusualTransactionDto> outliers = unusualTransactionDetector.detectOutliers(filtered);
        List<CategorySpendingDto> categories = buildCategoryBreakdown(allUserTx, filtered).categories();

        List<FinancialInsightDto> topInsights = insightGenerator.generateInsights(
                period,
                figures.income,
                figures.netExpenses,
                figures.netCashFlow,
                figures.savingsRate,
                mom,
                categories,
                recurringItems,
                outliers,
                quality
        );

        return new FinancialSummaryResponse(
                period,
                figures.income,
                figures.grossExpenses,
                figures.refunds,
                figures.netExpenses,
                figures.netCashFlow,
                figures.savingsRate,
                figures.savingsRateApplicable,
                figures.savingsRateCaveat,
                totalInvested,
                totalCurrentValue,
                unrealizedGain,
                gainPercentage,
                activeLoansCount,
                totalMonthlyEmi,
                totalPrincipal,
                mom,
                EXCLUDED_FLOWS,
                quality,
                topInsights
        );
    }

    @Transactional(readOnly = true)
    public List<MonthlyFlowDto> getMonthlyFlows(User user, LocalDate from, LocalDate to) {
        validateDateRange(from, to);

        List<Transaction> allUserTx = transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc(user.getId());
        return monthlyFlowsOf(filterByRange(allUserTx, from, to));
    }

    /**
     * Monthly income / expense / refund / net-cash-flow figures for the given transactions, using
     * exactly the definitions of {@link #getSummary}: non-spending flows are excluded, refunds offset
     * expenses, uncategorised credits count as income. Pure (no I/O); shared with the forecasting
     * module so history and forecasts can never use different definitions.
     */
    public List<MonthlyFlowDto> monthlyFlowsOf(List<Transaction> filtered) {
        if (filtered.isEmpty()) {
            return List.of();
        }

        // Group by YearMonth
        Map<YearMonth, List<Transaction>> byMonth = filtered.stream()
                .collect(Collectors.groupingBy(t -> YearMonth.from(t.getDate()), TreeMap::new, Collectors.toList()));

        List<MonthlyFlowDto> result = new ArrayList<>();
        BigDecimal prevExpenses = null;
        BigDecimal prevIncome = null;

        for (Map.Entry<YearMonth, List<Transaction>> entry : byMonth.entrySet()) {
            YearMonth ym = entry.getKey();
            List<Transaction> monthTxs = entry.getValue();
            FinancialFigures figs = calculateFigures(monthTxs);

            BigDecimal momExpenseChangePct = null;
            if (prevExpenses != null && prevExpenses.compareTo(BigDecimal.ZERO) > 0) {
                momExpenseChangePct = figs.netExpenses.subtract(prevExpenses)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(prevExpenses, 2, RoundingMode.HALF_UP);
            }

            BigDecimal momIncomeChangePct = null;
            if (prevIncome != null && prevIncome.compareTo(BigDecimal.ZERO) > 0) {
                momIncomeChangePct = figs.income.subtract(prevIncome)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(prevIncome, 2, RoundingMode.HALF_UP);
            }

            result.add(new MonthlyFlowDto(
                    ym.toString(),
                    figs.income,
                    figs.grossExpenses,
                    figs.refunds,
                    figs.netExpenses,
                    figs.netCashFlow,
                    figs.savingsRate,
                    monthTxs.size(),
                    momExpenseChangePct,
                    momIncomeChangePct
            ));

            prevExpenses = figs.netExpenses;
            prevIncome = figs.income;
        }

        return result;
    }

    @Transactional(readOnly = true)
    public CategoryBreakdownResponse getCategoryBreakdown(User user, LocalDate from, LocalDate to) {
        validateDateRange(from, to);

        List<Transaction> allUserTx = transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc(user.getId());
        List<Transaction> filtered = filterByRange(allUserTx, from, to);

        return buildCategoryBreakdown(allUserTx, filtered);
    }

    @Transactional(readOnly = true)
    public RecurringExpensesResponse getRecurringExpenses(User user, LocalDate from, LocalDate to) {
        validateDateRange(from, to);

        List<Transaction> allUserTx = transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc(user.getId());
        List<RecurringExpenseItemDto> items = recurringExpenseDetector.detectRecurring(allUserTx);

        BigDecimal totalMonthly = items.stream()
                .map(RecurringExpenseItemDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        List<Transaction> filtered = filterByRange(allUserTx, from, to);
        FinancialFigures figs = calculateFigures(filtered);

        // totalMonthly is a MONTHLY cost, so compare it with spending per month. Dividing it by the whole period's spending
        // (6 months at a time) made the share look six times smaller than it is (found by the end-to-end audit:
        // 44,948 a month shown as "9.63% of spend" when it was about 58% of a typical month).
        BigDecimal percentageOfExpenses = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        long monthsInPeriod = filtered.stream().map(t -> YearMonth.from(t.getDate())).distinct().count();
        if (figs.netExpenses.compareTo(BigDecimal.ZERO) > 0 && monthsInPeriod > 0) {
            BigDecimal averageMonthlyExpenses = figs.netExpenses.divide(BigDecimal.valueOf(monthsInPeriod), 2, RoundingMode.HALF_UP);
            percentageOfExpenses = totalMonthly.multiply(BigDecimal.valueOf(100))
                    .divide(averageMonthlyExpenses, 2, RoundingMode.HALF_UP);
        }

        PeriodDto period = resolvePeriod(filtered, from, to);
        return new RecurringExpensesResponse(period, items, totalMonthly, percentageOfExpenses);
    }

    @Transactional(readOnly = true)
    public DataQualityResponse getDataQuality(User user, LocalDate from, LocalDate to) {
        validateDateRange(from, to);

        List<Transaction> allUserTx = transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc(user.getId());

        if (allUserTx.isEmpty()) {
            return new DataQualityResponse(
                    null, null, 0, 0, 0, 0,
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    List.of(), false,
                    List.of("No transaction history recorded yet. Import a bank statement to begin.")
            );
        }

        LocalDate minDate = allUserTx.stream().map(Transaction::getDate).min(Comparator.naturalOrder()).orElse(null);
        LocalDate maxDate = allUserTx.stream().map(Transaction::getDate).max(Comparator.naturalOrder()).orElse(null);
        long daysCovered = minDate != null && maxDate != null ? ChronoUnit.DAYS.between(minDate, maxDate) + 1 : 0;

        Set<YearMonth> distinctMonths = allUserTx.stream()
                .map(t -> YearMonth.from(t.getDate()))
                .collect(Collectors.toSet());
        int totalMonthsCovered = distinctMonths.size();

        long uncategorizedCount = allUserTx.stream()
                .filter(t -> t.getCategory() == null || "UNCATEGORIZED".equalsIgnoreCase(t.getCategory()))
                .count();

        BigDecimal uncategorizedPct = BigDecimal.valueOf(uncategorizedCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(allUserTx.size()), 2, RoundingMode.HALF_UP);

        // Check for missing intermediate months
        List<String> missingMonths = new ArrayList<>();
        if (minDate != null && maxDate != null) {
            YearMonth cur = YearMonth.from(minDate);
            YearMonth end = YearMonth.from(maxDate);
            while (cur.isBefore(end)) {
                if (!distinctMonths.contains(cur)) {
                    missingMonths.add(cur.toString());
                }
                cur = cur.plusMonths(1);
            }
        }

        boolean hasSufficientHistory = totalMonthsCovered >= 3;
        List<String> caveats = new ArrayList<>();
        if (!hasSufficientHistory) {
            caveats.add(String.format("Limited history (%d month%s detected). Comprehensive trend comparisons require at least 3 months.",
                    totalMonthsCovered, totalMonthsCovered == 1 ? "" : "s"));
        }
        if (uncategorizedPct.compareTo(BigDecimal.valueOf(10)) > 0) {
            caveats.add(String.format("%s%% of transactions are uncategorized. Spending breakdown by category may be incomplete.",
                    uncategorizedPct.toPlainString()));
        }
        if (!missingMonths.isEmpty()) {
            caveats.add("Gaps detected in transaction timeline for month(s): " + String.join(", ", missingMonths));
        }

        boolean hasCardPayments = allUserTx.stream().anyMatch(t -> {
            TransactionCategory cat = parseCategory(t.getCategory());
            return cat == TransactionCategory.CARD_PAYMENTS;
        });
        if (hasCardPayments) {
            caveats.add("Credit card bill payments detected: credit-card statement imports are not yet supported, so individual card purchases are not captured in spending breakdowns.");
        }

        return new DataQualityResponse(
                minDate, maxDate, daysCovered, totalMonthsCovered,
                allUserTx.size(), uncategorizedCount, uncategorizedPct,
                missingMonths, hasSufficientHistory, caveats
        );
    }

    @Transactional(readOnly = true)
    public InsightsResponse getInsights(User user, LocalDate from, LocalDate to) {
        FinancialSummaryResponse summary = getSummary(user, from, to);
        List<Transaction> allUserTx = transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc(user.getId());
        List<Transaction> filtered = filterByRange(allUserTx, from, to);
        List<UnusualTransactionDto> outliers = unusualTransactionDetector.detectOutliers(filtered);

        return new InsightsResponse(summary.period(), summary.topInsights(), outliers);
    }

    // --- Core Calculation Helpers ---

    /**
     * Net spending per calendar month and category id, with the category rules of the category breakdown:
     * debits in expense or unknown flows, minus credits that the user or classifier placed in the same
     * expense category. Refunds in the generic {@code REFUNDS} category cannot be attributed to a
     * category and are therefore not netted here (they still reduce the overall figures in
     * {@link #monthlyFlowsOf}). Each category is floored at zero. Uncategorised debits are keyed
     * {@code UNCATEGORIZED}.
     */
    public Map<YearMonth, Map<String, BigDecimal>> monthlyCategoryNetSpendOf(List<Transaction> txs) {
        Map<YearMonth, Map<String, BigDecimal>> gross = new TreeMap<>();
        Map<YearMonth, Map<String, BigDecimal>> credits = new HashMap<>();
        for (Transaction t : txs) {
            TransactionCategory cat = parseCategory(t.getCategory());
            Flow flow = cat != null ? cat.flow() : Flow.UNKNOWN;
            if (t.isCategoryNeedsReview()) {
                if (flow == Flow.NON_SPENDING || flow == Flow.REFUND) {
                    flow = Flow.UNKNOWN;
                }
            }
            if (flow == Flow.NON_SPENDING) {
                continue;
            }
            String key = cat != null ? cat.name() : "UNCATEGORIZED";
            YearMonth ym = YearMonth.from(t.getDate());
            if (t.getType() == TransactionType.EXPENSE && (flow == Flow.EXPENSE || flow == Flow.UNKNOWN)) {
                gross.computeIfAbsent(ym, k -> new HashMap<>()).merge(key, t.getAmount(), BigDecimal::add);
            } else if (t.getType() == TransactionType.INCOME && flow == Flow.EXPENSE) {
                credits.computeIfAbsent(ym, k -> new HashMap<>()).merge(key, t.getAmount(), BigDecimal::add);
            }
        }
        Map<YearMonth, Map<String, BigDecimal>> net = new TreeMap<>();
        for (Map.Entry<YearMonth, Map<String, BigDecimal>> month : gross.entrySet()) {
            Map<String, BigDecimal> perCategory = new HashMap<>();
            for (Map.Entry<String, BigDecimal> e : month.getValue().entrySet()) {
                BigDecimal credit = credits.getOrDefault(month.getKey(), Map.of()).getOrDefault(e.getKey(), BigDecimal.ZERO);
                perCategory.put(e.getKey(), e.getValue().subtract(credit).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
            }
            net.put(month.getKey(), perCategory);
        }
        return net;
    }

    private CategoryBreakdownResponse buildCategoryBreakdown(List<Transaction> allUserTx, List<Transaction> filtered) {
        FinancialFigures figures = calculateFigures(filtered);
        PeriodDto period = resolvePeriod(filtered, null, null);

        // Group expense transactions by category
        List<Transaction> expenseTxs = filtered.stream()
                .filter(t -> t.getType() == TransactionType.EXPENSE)
                .filter(t -> {
                    TransactionCategory cat = parseCategory(t.getCategory());
                    return cat == null || cat.flow() == Flow.EXPENSE || cat.flow() == Flow.UNKNOWN;
                })
                .toList();

        Map<String, List<Transaction>> byCategory = expenseTxs.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getCategory() != null ? t.getCategory() : "UNCATEGORIZED",
                        HashMap::new,
                        Collectors.toList()
                ));

        // Group all-time expenses by YearMonth and category using consistent net spending
        Map<YearMonth, Map<String, BigDecimal>> monthCatSpend = monthlyCategoryNetSpendOf(allUserTx);

        List<YearMonth> sortedMonths = new ArrayList<>(monthCatSpend.keySet());

        List<CategorySpendingDto> categories = new ArrayList<>();
        List<String> risingCategories = new ArrayList<>();

        for (Map.Entry<String, List<Transaction>> entry : byCategory.entrySet()) {
            String catKey = entry.getKey();
            List<Transaction> catTxs = entry.getValue();

            BigDecimal amount = catTxs.stream()
                    .map(Transaction::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal pct = figures.grossExpenses.compareTo(BigDecimal.ZERO) > 0
                    ? amount.multiply(BigDecimal.valueOf(100)).divide(figures.grossExpenses, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            BigDecimal avg = amount.divide(BigDecimal.valueOf(catTxs.size()), 2, RoundingMode.HALF_UP);

            Transaction largestTx = catTxs.stream()
                    .max(Comparator.comparing(Transaction::getAmount))
                    .orElse(null);

            TransactionBriefDto brief = largestTx != null
                    ? new TransactionBriefDto(largestTx.getId(), largestTx.getTitle(), largestTx.getAmount(), largestTx.getDate(), largestTx.getCategory())
                    : null;

            // Check if spending in this category has risen for 2+ consecutive months
            boolean isIncreasing = checkCategoryConsistentlyRising(catKey, sortedMonths, monthCatSpend);
            if (isIncreasing) {
                risingCategories.add(catKey);
            }

            // MoM change
            BigDecimal momChangePct = calculateCategoryMom(catKey, sortedMonths, monthCatSpend);

            String label = formatCategoryLabel(catKey);
            TransactionCategory parsedCat = parseCategory(catKey);
            String flow = parsedCat != null ? parsedCat.flow().name() : "UNKNOWN";

            categories.add(new CategorySpendingDto(
                    catKey, label, flow, amount, pct,
                    catTxs.size(), avg, momChangePct, isIncreasing, brief
            ));
        }

        // Sort categories by amount descending
        categories.sort(Comparator.comparing(CategorySpendingDto::amount).reversed());

        String largestCategory = categories.isEmpty() ? null : categories.get(0).category();

        return new CategoryBreakdownResponse(
                period,
                categories,
                figures.grossExpenses,
                figures.refunds,
                figures.netExpenses,
                largestCategory,
                risingCategories
        );
    }

    private boolean checkCategoryConsistentlyRising(String catKey, List<YearMonth> sortedMonths, Map<YearMonth, Map<String, BigDecimal>> monthCatSpend) {
        if (sortedMonths.size() < 3) return false;
        int n = sortedMonths.size();
        YearMonth ym3 = sortedMonths.get(n - 1);
        YearMonth ym2 = sortedMonths.get(n - 2);
        YearMonth ym1 = sortedMonths.get(n - 3);

        // Consecutive calendar months required
        if (!ym3.equals(ym2.plusMonths(1)) || !ym2.equals(ym1.plusMonths(1))) {
            return false;
        }

        BigDecimal m1 = monthCatSpend.getOrDefault(ym1, Map.of()).getOrDefault(catKey, BigDecimal.ZERO);
        BigDecimal m2 = monthCatSpend.getOrDefault(ym2, Map.of()).getOrDefault(catKey, BigDecimal.ZERO);
        BigDecimal m3 = monthCatSpend.getOrDefault(ym3, Map.of()).getOrDefault(catKey, BigDecimal.ZERO);

        return m3.compareTo(m2) > 0 && m2.compareTo(m1) > 0 && m3.compareTo(BigDecimal.valueOf(500)) >= 0;
    }

    private BigDecimal calculateCategoryMom(String catKey, List<YearMonth> sortedMonths, Map<YearMonth, Map<String, BigDecimal>> monthCatSpend) {
        if (sortedMonths.size() < 2) return null;
        int n = sortedMonths.size();
        YearMonth currYm = sortedMonths.get(n - 1);
        YearMonth prevYm = sortedMonths.get(n - 2);
        if (!currYm.equals(prevYm.plusMonths(1))) {
            return null;
        }

        BigDecimal prev = monthCatSpend.getOrDefault(prevYm, Map.of()).getOrDefault(catKey, BigDecimal.ZERO);
        BigDecimal curr = monthCatSpend.getOrDefault(currYm, Map.of()).getOrDefault(catKey, BigDecimal.ZERO);

        if (prev.compareTo(BigDecimal.ZERO) <= 0) return null;
        return curr.subtract(prev).multiply(BigDecimal.valueOf(100)).divide(prev, 2, RoundingMode.HALF_UP);
    }

    private MomComparisonDto calculateMomComparison(List<Transaction> allUserTx, PeriodDto currentPeriod) {
        if (currentPeriod.from() == null || currentPeriod.to() == null) {
            return null;
        }

        // Compute prior period of equal length ending right before currentPeriod.from()
        long days = ChronoUnit.DAYS.between(currentPeriod.from(), currentPeriod.to()) + 1;
        LocalDate prevTo = currentPeriod.from().minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1);

        List<Transaction> prevTx = filterByRange(allUserTx, prevFrom, prevTo);
        if (prevTx.isEmpty()) {
            return null;
        }

        FinancialFigures currFigures = calculateFigures(filterByRange(allUserTx, currentPeriod.from(), currentPeriod.to()));
        FinancialFigures prevFigures = calculateFigures(prevTx);

        BigDecimal incomeChange = currFigures.income.subtract(prevFigures.income).setScale(2, RoundingMode.HALF_UP);
        BigDecimal incomeChangePct = prevFigures.income.compareTo(BigDecimal.ZERO) > 0
                ? incomeChange.multiply(BigDecimal.valueOf(100)).divide(prevFigures.income, 2, RoundingMode.HALF_UP)
                : null;

        BigDecimal expenseChange = currFigures.netExpenses.subtract(prevFigures.netExpenses).setScale(2, RoundingMode.HALF_UP);
        BigDecimal expenseChangePct = prevFigures.netExpenses.compareTo(BigDecimal.ZERO) > 0
                ? expenseChange.multiply(BigDecimal.valueOf(100)).divide(prevFigures.netExpenses, 2, RoundingMode.HALF_UP)
                : null;

        BigDecimal netChange = currFigures.netCashFlow.subtract(prevFigures.netCashFlow).setScale(2, RoundingMode.HALF_UP);

        return new MomComparisonDto(
                prevFigures.income,
                incomeChange,
                incomeChangePct,
                prevFigures.netExpenses,
                expenseChange,
                expenseChangePct,
                prevFigures.netCashFlow,
                netChange
        );
    }

    private FinancialFigures calculateFigures(List<Transaction> txs) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal grossExpenses = BigDecimal.ZERO;
        BigDecimal refunds = BigDecimal.ZERO;
        boolean hasCardPayments = false;

        for (Transaction t : txs) {
            TransactionCategory cat = parseCategory(t.getCategory());
            Flow flow = cat != null ? cat.flow() : Flow.UNKNOWN;

            // An unreviewed automatic category (categoryNeedsReview=true) must NEVER be treated as NON_SPENDING or REFUND,
            // which would silently drop financially relevant activity or distort spending before user confirmation.
            if (t.isCategoryNeedsReview()) {
                if (flow == Flow.NON_SPENDING || flow == Flow.REFUND) {
                    flow = Flow.UNKNOWN;
                }
            }

            if (cat == TransactionCategory.CARD_PAYMENTS && !t.isCategoryNeedsReview()) {
                hasCardPayments = true;
            }

            // Strict exclusion of transfers, card payments and investment movements
            if (flow == Flow.NON_SPENDING) {
                continue;
            }

            if (t.getType() == TransactionType.INCOME) {
                if (flow == Flow.REFUND || flow == Flow.EXPENSE) {
                    refunds = refunds.add(t.getAmount());
                } else if (flow == Flow.INCOME || flow == Flow.UNKNOWN) {
                    income = income.add(t.getAmount());
                }
            } else if (t.getType() == TransactionType.EXPENSE) {
                if (flow == Flow.EXPENSE || flow == Flow.UNKNOWN) {
                    grossExpenses = grossExpenses.add(t.getAmount());
                }
            }
        }

        income = income.setScale(2, RoundingMode.HALF_UP);
        grossExpenses = grossExpenses.setScale(2, RoundingMode.HALF_UP);
        refunds = refunds.setScale(2, RoundingMode.HALF_UP);

        // Net expenses offset by refunds (bounded at zero for display)
        BigDecimal netExpenses = grossExpenses.subtract(refunds);
        if (netExpenses.compareTo(BigDecimal.ZERO) < 0) {
            netExpenses = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        } else {
            netExpenses = netExpenses.setScale(2, RoundingMode.HALF_UP);
        }

        // True net cash flow identity: Cash Inflows (income + refunds) - Cash Outflows (grossExpenses)
        // When grossExpenses >= refunds, this is identical to income - netExpenses.
        // When refunds > grossExpenses (e.g. cross-month refunds), excess refunds preserve positive cash flow.
        BigDecimal netCashFlow = income.add(refunds).subtract(grossExpenses).setScale(2, RoundingMode.HALF_UP);

        BigDecimal savingsRate = null;
        boolean savingsRateApplicable = false;
        String savingsRateCaveat = null;

        if (income.compareTo(BigDecimal.ZERO) > 0) {
            savingsRate = netCashFlow.multiply(BigDecimal.valueOf(100)).divide(income, 2, RoundingMode.HALF_UP);
            savingsRateApplicable = true;
            if (netCashFlow.compareTo(BigDecimal.ZERO) < 0) {
                savingsRateCaveat = "Negative savings rate reflects expenses exceeding recorded income this period.";
            }
        } else {
            savingsRateCaveat = "Savings rate cannot be computed because no income was recorded for this period.";
        }

        if (hasCardPayments) {
            String cardNotice = "Credit card payments were excluded to prevent double-counting, but card statement imports are not yet supported. Card purchases are not captured in spending totals or savings rate.";
            if (savingsRateCaveat == null) {
                savingsRateCaveat = cardNotice;
            } else {
                savingsRateCaveat = savingsRateCaveat + " " + cardNotice;
            }
        }

        return new FinancialFigures(income, grossExpenses, refunds, netExpenses, netCashFlow, savingsRate, savingsRateApplicable, savingsRateCaveat);
    }

    private List<Transaction> filterByRange(List<Transaction> txs, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return txs;
        }
        return txs.stream()
                .filter(t -> from == null || !t.getDate().isBefore(from))
                .filter(t -> to == null || !t.getDate().isAfter(to))
                .toList();
    }

    private PeriodDto resolvePeriod(List<Transaction> txs, LocalDate from, LocalDate to) {
        if (from != null && to != null) {
            return new PeriodDto(from, to);
        }
        if (txs.isEmpty()) {
            return new PeriodDto(from, to);
        }
        LocalDate min = from != null ? from : txs.stream().map(Transaction::getDate).min(Comparator.naturalOrder()).orElse(null);
        LocalDate max = to != null ? to : txs.stream().map(Transaction::getDate).max(Comparator.naturalOrder()).orElse(null);
        return new PeriodDto(min, max);
    }

    private DataQualitySummaryDto buildDataQualitySummary(List<Transaction> allUserTx, List<Transaction> filtered) {
        long total = filtered.size();
        long uncategorized = filtered.stream()
                .filter(t -> t.getCategory() == null || "UNCATEGORIZED".equalsIgnoreCase(t.getCategory()))
                .count();

        BigDecimal pct = total > 0
                ? BigDecimal.valueOf(uncategorized).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        long distinctMonths = allUserTx.stream()
                .map(t -> YearMonth.from(t.getDate()))
                .distinct()
                .count();

        return new DataQualitySummaryDto(total, uncategorized, pct, distinctMonths >= 3);
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("'from' date cannot be after 'to' date");
        }
    }

    private TransactionCategory parseCategory(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) return null;
        return TransactionCategory.parse(categoryName).orElse(null);
    }

    private String formatCategoryLabel(String key) {
        if ("UNCATEGORIZED".equalsIgnoreCase(key)) return "Uncategorized";
        String s = key.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private record FinancialFigures(
            BigDecimal income,
            BigDecimal grossExpenses,
            BigDecimal refunds,
            BigDecimal netExpenses,
            BigDecimal netCashFlow,
            BigDecimal savingsRate,
            boolean savingsRateApplicable,
            String savingsRateCaveat
    ) {}
}
