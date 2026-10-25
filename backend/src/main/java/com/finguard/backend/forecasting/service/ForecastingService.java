package com.finguard.backend.forecasting.service;

import com.finguard.backend.budgeting.domain.Goal;
import com.finguard.backend.budgeting.domain.GoalRepository;
import com.finguard.backend.budgeting.domain.GoalStatus;
import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.forecasting.dto.BacktestSummaryResponse;
import com.finguard.backend.forecasting.dto.BacktestSummaryResponse.ErrorMetrics;
import com.finguard.backend.forecasting.dto.BacktestSummaryResponse.HorizonResult;
import com.finguard.backend.forecasting.dto.BacktestSummaryResponse.SeriesResult;
import com.finguard.backend.forecasting.dto.BudgetOverrunRiskResponse;
import com.finguard.backend.forecasting.dto.CashFlowForecastResponse;
import com.finguard.backend.forecasting.dto.CashFlowForecastResponse.MonthToDate;
import com.finguard.backend.forecasting.dto.CashFlowForecastResponse.ObservedMonth;
import com.finguard.backend.forecasting.dto.CashFlowForecastResponse.ProjectedMonth;
import com.finguard.backend.forecasting.dto.CashFlowForecastResponse.RecurringInfluence;
import com.finguard.backend.forecasting.dto.CashFlowForecastResponse.Summary;
import com.finguard.backend.forecasting.dto.CategorySpendingForecastResponse;
import com.finguard.backend.forecasting.dto.CategorySpendingForecastResponse.CategoryForecast;
import com.finguard.backend.forecasting.dto.CategorySpendingForecastResponse.CategoryWithoutForecast;
import com.finguard.backend.forecasting.dto.ForecastCommon.Estimate;
import com.finguard.backend.forecasting.dto.ForecastCommon.HistoryInfo;
import com.finguard.backend.forecasting.dto.SavingsProjectionResponse;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest;
import com.finguard.backend.forecasting.dto.ScenarioSimulationResponse;
import com.finguard.backend.forecasting.service.ForecastHistory.MonthFigures;
import com.finguard.backend.forecasting.service.ForecastMethod.Obs;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.intelligence.dto.RecurringExpenseItemDto;
import com.finguard.backend.ledger.transactions.TransactionCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.finguard.backend.forecasting.dto.ForecastCommon.LIMITED;
import static com.finguard.backend.forecasting.dto.ForecastCommon.RELIABLE;
import static com.finguard.backend.forecasting.dto.ForecastCommon.UNAVAILABLE;

/**
 * Orchestrates the forecasts. Every request loads one bounded, owner-scoped history, applies the
 * evidence rules below, and calls the pure methods in {@link ForecastMethod} / {@link ForecastBacktest}.
 * Nothing is persisted: forecasts are calculated on demand, so there is no cache to invalidate when a
 * transaction, category, budget or goal changes.
 *
 * <p>Evidence rules (documented in docs/FINANCIAL_RULES.md section 7):
 * <ul>
 *   <li>Training data = complete calendar months only; the month in progress is shown, never learnt from.</li>
 *   <li>Fewer than {@value #MIN_MONTHS_LIMITED} usable months: {@code UNAVAILABLE}. 3-5 months, gaps, a stale
 *       import, irregular or missing income: {@code LIMITED}. At least {@value #MIN_MONTHS_RELIABLE} months,
 *       at most one gap in the last six, no staleness, regular income: {@code RELIABLE}.</li>
 *   <li>No income observed in {@value #MIN_INCOME_MONTHS} months: income and net are unavailable, never zero.</li>
 *   <li>A category needs spending in at least {@value #MIN_CATEGORY_ACTIVE_MONTHS} months for its own forecast.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ForecastingService {

    public static final int MIN_MONTHS_LIMITED = 3;
    public static final int MIN_MONTHS_RELIABLE = 6;
    public static final int MIN_INCOME_MONTHS = 3;
    public static final int MIN_CATEGORY_ACTIVE_MONTHS = 3;
    public static final int RELIABLE_CATEGORY_ACTIVE_MONTHS = 6;
    public static final int MAX_HORIZON = 12;
    /** Monthly change, as a share of the recent average, from which a tested upward trend is called meaningful. */
    static final double MEANINGFUL_TREND_PERCENT = 3.0;
    /** A coefficient of variation of monthly income above this marks income as irregular. */
    static final double IRREGULAR_INCOME_CV = 0.5;

    public static final String SAVINGS_DISCLAIMER =
            "Projected surplus is the expected difference between recorded-style income and spending. It is not a bank "
                    + "balance and not verified savings. Goal contributions are self-tracked planning figures, not "
                    + "deposits. FinGuard holds no account balances.";

    private final HistoryLoader historyLoader;
    private final GoalRepository goalRepository;
    private final BudgetOverrunPredictor budgetOverrunPredictor;
    private final ScenarioSimulator scenarioSimulator;

    // ------------------------------------------------------------------------------------------------
    // Cash flow
    // ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public CashFlowForecastResponse getCashFlowForecast(User user, int horizonMonths) {
        validateHorizon(horizonMonths);
        return cashFlow(historyLoader.load(user), horizonMonths);
    }

    CashFlowForecastResponse cashFlow(ForecastHistory history, int horizon) {
        List<MonthFigures> months = history.usableMonths();
        List<String> warnings = qualityWarnings(history);
        List<String> assumptions = baseAssumptions(history);
        List<ObservedMonth> observed = months.stream()
                .map(m -> new ObservedMonth(m.month().toString(), m.income(), m.expenses(), m.net()))
                .toList();
        MonthToDate current = history.currentMonthSoFar() == null ? null : new MonthToDate(
                history.currentMonth().toString(), history.currentMonthSoFar().income(),
                history.currentMonthSoFar().expenses(), history.currentMonthSoFar().net(),
                "Recorded so far this month. Not used to train the forecast because the month is incomplete.");
        HistoryInfo info = historyInfo(history);

        if (months.size() < MIN_MONTHS_LIMITED) {
            warnings.add(0, months.isEmpty()
                    ? "No complete month of income or spending is recorded yet, so no forecast can be made."
                    : "At least " + MIN_MONTHS_LIMITED + " complete months with income or spending are needed for a forecast; only "
                    + months.size() + " found. Nothing is projected rather than guessing.");
            return new CashFlowForecastResponse(UNAVAILABLE, ForecastMethod.NAME, ForecastMethod.VERSION, history.asOf().toString(),
                    horizon, info, warnings, assumptions, intervalNote(), false, observed, current, List.of(), null, List.of());
        }

        int origin = ForecastMethod.indexOf(history.lastCompleteMonth());
        List<Obs> expenseObs = months.stream().map(m -> obs(m.month(), m.expenses())).toList();
        List<Obs> incomeObs = months.stream().map(m -> obs(m.month(), m.income())).toList();

        long incomeMonths = months.stream().filter(m -> m.income().signum() > 0).count();
        boolean incomeAvailable = incomeMonths >= MIN_INCOME_MONTHS;
        boolean incomeRegular = true;
        if (!incomeAvailable) {
            warnings.add("Income was recorded in " + incomeMonths + " of " + months.size() + " usable months (at least "
                    + MIN_INCOME_MONTHS + " needed). Income and net cash flow are not projected; spending is.");
        } else if (incomeMonths < months.size() || coefficientOfVariation(incomeObs) > IRREGULAR_INCOME_CV) {
            incomeRegular = false;
            warnings.add("Income is irregular (it varies a lot or is missing in some months), so the income projection is a rough level, not a schedule.");
        }

        BigDecimal recurringFloor = history.activeRecurring().stream()
                .map(RecurringExpenseItemDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);

        SeriesForecast expenses = forecastSeries(expenseObs, origin, horizon, true, recurringFloor);
        SeriesForecast income = incomeAvailable ? forecastSeries(incomeObs, origin, horizon, true, BigDecimal.ZERO) : null;
        addOutlierWarnings(warnings, "Spending", expenses.projection());
        if (income != null) {
            addOutlierWarnings(warnings, "Income", income.projection());
        }
        if (expenses.projection().trendApplied()) {
            assumptions.add("A statistically tested spending trend (" + signed(expenses.projection().slopePerMonth())
                    + " per month, damped) is applied.");
        } else if (months.size() >= ForecastMethod.MIN_TREND_OBSERVATIONS) {
            assumptions.add("No statistically significant spending trend was found, so spending is projected at its recent level.");
        } else {
            assumptions.add("Fewer than " + ForecastMethod.MIN_TREND_OBSERVATIONS + " months: no trend is fitted, spending is projected at its recent level.");
        }

        List<ProjectedMonth> projected = new ArrayList<>();
        BigDecimal totalExpenses = BigDecimal.ZERO;
        BigDecimal totalIncome = BigDecimal.ZERO;
        for (int k = 1; k <= horizon; k++) {
            Estimate exp = expenses.estimates().get(k - 1);
            Estimate inc = income == null ? null : income.estimates().get(k - 1);
            Estimate net = inc == null ? null : netEstimate(inc, exp, income.runs().get(k - 1), expenses.runs().get(k - 1));
            List<String> factors = new ArrayList<>(expenses.factors().get(k - 1));
            projected.add(new ProjectedMonth(history.currentMonth().plusMonths(k).toString(), inc, exp, net,
                    expenses.floorApplied().get(k - 1), factors));
            totalExpenses = totalExpenses.add(exp.point());
            if (inc != null) {
                totalIncome = totalIncome.add(inc.point());
            }
        }

        Summary summary;
        if (income != null) {
            BigDecimal totalNet = totalIncome.subtract(totalExpenses);
            summary = new Summary(totalIncome, totalExpenses, totalNet,
                    totalNet.divide(BigDecimal.valueOf(horizon), 2, RoundingMode.HALF_UP),
                    totalIncome.signum() > 0 ? totalNet.multiply(BigDecimal.valueOf(100)).divide(totalIncome, 2, RoundingMode.HALF_UP) : null);
        } else {
            summary = new Summary(null, totalExpenses, null, null, null);
        }

        String reliability = reliability(history, incomeAvailable, incomeRegular);
        if (recurringFloor.signum() > 0) {
            assumptions.add("Detected active recurring payments (₹" + recurringFloor + "/month) are already inside the observed history; "
                    + "they are not added again and only raise a projection that would otherwise fall below them.");
        }

        List<RecurringInfluence> recurring = history.activeRecurring().stream()
                .map(r -> new RecurringInfluence(r.id(), r.title(), r.category(), r.amount(), r.cadence(), r.confidence(), r.isConfirmed(), true))
                .toList();

        return new CashFlowForecastResponse(reliability, ForecastMethod.NAME, ForecastMethod.VERSION, history.asOf().toString(),
                horizon, info, warnings, assumptions, intervalNote(), incomeAvailable, observed, current, projected, summary, recurring);
    }

    // ------------------------------------------------------------------------------------------------
    // Categories
    // ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public CategorySpendingForecastResponse getCategoryForecast(User user, int horizonMonths) {
        validateHorizon(horizonMonths);
        return categories(historyLoader.load(user), horizonMonths);
    }

    CategorySpendingForecastResponse categories(ForecastHistory history, int horizon) {
        List<MonthFigures> months = history.usableMonths();
        List<String> warnings = qualityWarnings(history);
        List<String> assumptions = baseAssumptions(history);
        assumptions.add("Category figures use the category breakdown rules: refunds filed under the generic Refunds category cannot be "
                + "attributed to a category, so category totals can exceed the overall net expense figure by those refunds.");
        HistoryInfo info = historyInfo(history);

        if (months.size() < MIN_MONTHS_LIMITED) {
            warnings.add(0, "At least " + MIN_MONTHS_LIMITED + " complete months with income or spending are needed; found " + months.size() + ".");
            return new CategorySpendingForecastResponse(UNAVAILABLE, ForecastMethod.NAME, ForecastMethod.VERSION, history.asOf().toString(),
                    horizon, info, warnings, assumptions, List.of(), List.of(), List.of());
        }

        int origin = ForecastMethod.indexOf(history.lastCompleteMonth());
        Set<String> categoryIds = new TreeSet<>();
        history.categoryNetByMonth().values().forEach(m -> categoryIds.addAll(m.keySet()));

        List<CategoryForecast> forecasts = new ArrayList<>();
        List<CategoryWithoutForecast> without = new ArrayList<>();
        List<String> rising = new ArrayList<>();
        String overall = reliability(history, true, true);

        for (String id : categoryIds) {
            List<Obs> series = new ArrayList<>();
            int active = 0;
            for (MonthFigures m : months) {
                BigDecimal v = history.categoryNetByMonth().getOrDefault(m.month(), Map.of()).getOrDefault(id, BigDecimal.ZERO);
                series.add(obs(m.month(), v));
                if (v.signum() > 0) {
                    active++;
                }
            }
            if (active < MIN_CATEGORY_ACTIVE_MONTHS) {
                without.add(new CategoryWithoutForecast(id, label(id), "Spending in only " + active + " of " + months.size()
                        + " month(s); at least " + MIN_CATEGORY_ACTIVE_MONTHS + " are needed for a category forecast."));
                continue;
            }
            BigDecimal recurring = history.activeRecurring().stream()
                    .filter(r -> id.equals(canonical(r.category())))
                    .map(RecurringExpenseItemDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
            SeriesForecast f = forecastSeries(series, origin, horizon, true, recurring);

            double recent = series.subList(Math.max(0, series.size() - ForecastMethod.LEVEL_WINDOW), series.size())
                    .stream().mapToDouble(Obs::value).average().orElse(0);
            String trend;
            BigDecimal trendPct = null;
            if (f.projection().trendApplied()) {
                trend = f.projection().slopePerMonth() > 0 ? "UPWARD" : "DOWNWARD";
                if (recent > 0) {
                    trendPct = BigDecimal.valueOf(f.projection().slopePerMonth() * 100 / recent).setScale(1, RoundingMode.HALF_UP);
                }
                if ("UPWARD".equals(trend) && trendPct != null && trendPct.doubleValue() >= MEANINGFUL_TREND_PERCENT) {
                    rising.add(id);
                }
            } else {
                trend = months.size() >= ForecastMethod.MIN_TREND_OBSERVATIONS ? "STABLE" : "NOT_ESTABLISHED";
            }

            boolean reliable = RELIABLE.equals(overall) && active >= RELIABLE_CATEGORY_ACTIVE_MONTHS
                    && active >= Math.ceil(0.75 * months.size());
            List<String> outlierMonths = f.projection().outlierIndexes().stream().map(i -> ForecastMethod.monthOf(i).toString()).toList();
            BigDecimal total = f.estimates().stream().map(Estimate::point).reduce(BigDecimal.ZERO, BigDecimal::add);
            String explanation = "Based on " + active + " of " + months.size() + " months with spending in this category. "
                    + switch (trend) {
                case "UPWARD", "DOWNWARD" -> "A tested " + trend.toLowerCase() + " trend is applied (damped).";
                case "STABLE" -> "No significant trend; projected at the recent level.";
                default -> "Too little history to test a trend; projected at the recent level.";
            } + (active < months.size() ? " Spending in this category is intermittent." : "");

            forecasts.add(new CategoryForecast(id, label(id), active, money(recent), f.estimates(), total, trend, trendPct,
                    reliable ? RELIABLE : LIMITED, recurring, outlierMonths, explanation));
        }
        forecasts.sort(Comparator.comparing(CategoryForecast::projectedTotal).reversed());

        return new CategorySpendingForecastResponse(overall, ForecastMethod.NAME, ForecastMethod.VERSION, history.asOf().toString(),
                horizon, info, warnings, assumptions, forecasts, without, rising);
    }

    // ------------------------------------------------------------------------------------------------
    // Budgets, savings, scenarios, evaluation
    // ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public BudgetOverrunRiskResponse getBudgetOverrunRisk(User user, String yearMonth) {
        YearMonth month = null;
        if (yearMonth != null && !yearMonth.isBlank()) {
            try {
                month = YearMonth.parse(yearMonth.trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw ApiException.badRequest("yearMonth must look like 2026-03.");
            }
        }
        return budgetOverrunPredictor.evaluate(user, month, historyLoader.load(user));
    }

    @Transactional(readOnly = true)
    public SavingsProjectionResponse getSavingsProjections(User user, int horizonMonths) {
        validateHorizon(horizonMonths);
        ForecastHistory history = historyLoader.load(user);
        CashFlowForecastResponse cashFlow = cashFlow(history, horizonMonths);

        List<String> warnings = new ArrayList<>(cashFlow.warnings());
        List<Goal> goals = goalRepository.findByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), GoalStatus.ACTIVE);
        BigDecimal recorded = goals.stream().map(g -> g.getCurrentAmount() == null ? BigDecimal.ZERO : g.getCurrentAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);

        boolean usable = !UNAVAILABLE.equals(cashFlow.reliability()) && cashFlow.incomeAvailable();
        if (!usable) {
            if (!UNAVAILABLE.equals(cashFlow.reliability())) {
                warnings.add("Savings need a projected income; none could be established, so no surplus is projected.");
            }
            return new SavingsProjectionResponse(UNAVAILABLE, cashFlow.method(), cashFlow.algorithmVersion(), cashFlow.asOf(), horizonMonths,
                    cashFlow.history(), warnings, cashFlow.assumptions(), SAVINGS_DISCLAIMER, cashFlow.incomeAvailable(), null, null,
                    List.of(), goalOutlooks(goals, history.currentMonth(), null),
                    new SavingsProjectionResponse.Accounting(null, recorded, null, BANK_NOTE, null, null));
        }

        List<SavingsProjectionResponse.Point> points = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (ProjectedMonth p : cashFlow.projected()) {
            cumulative = cumulative.add(p.net().point());
            points.add(new SavingsProjectionResponse.Point(p.month(), p.net(), cumulative));
        }
        BigDecimal averageNet = cashFlow.summary().averageMonthlyNet();
        List<SavingsProjectionResponse.GoalOutlook> outlooks = goalOutlooks(goals, history.currentMonth(), averageNet);
        BigDecimal totalPace = outlooks.stream().map(SavingsProjectionResponse.GoalOutlook::requiredMonthlyPace)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean anyDated = outlooks.stream().anyMatch(o -> o.requiredMonthlyPace() != null);

        return new SavingsProjectionResponse(cashFlow.reliability(), cashFlow.method(), cashFlow.algorithmVersion(), cashFlow.asOf(),
                horizonMonths, cashFlow.history(), warnings, cashFlow.assumptions(), SAVINGS_DISCLAIMER, true, averageNet,
                cashFlow.summary().savingsRatePercent(), points, outlooks,
                new SavingsProjectionResponse.Accounting(cumulative, recorded, null, BANK_NOTE,
                        anyDated ? totalPace : null, anyDated ? totalPace.compareTo(averageNet.max(BigDecimal.ZERO)) > 0 : null));
    }

    private static final String BANK_NOTE = "Not available. FinGuard does not hold or verify account balances.";

    private List<SavingsProjectionResponse.GoalOutlook> goalOutlooks(List<Goal> goals, YearMonth current, BigDecimal averageNet) {
        List<SavingsProjectionResponse.GoalOutlook> out = new ArrayList<>();
        for (Goal g : goals) {
            BigDecimal recorded = g.getCurrentAmount() == null ? BigDecimal.ZERO : g.getCurrentAmount();
            BigDecimal remaining = g.getTargetAmount().subtract(recorded).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            BigDecimal pace = null;
            if (g.getTargetDate() != null) {
                long monthsToTarget = Math.max(1, java.time.temporal.ChronoUnit.MONTHS.between(current, YearMonth.from(g.getTargetDate())));
                pace = remaining.divide(BigDecimal.valueOf(monthsToTarget), 2, RoundingMode.HALF_UP);
            }
            Integer months = null;
            String completion = null;
            if (remaining.signum() == 0) {
                months = 0;
                completion = current.toString();
            } else if (averageNet != null && averageNet.signum() > 0) {
                months = remaining.divide(averageNet, 0, RoundingMode.CEILING).intValueExact();
                completion = current.plusMonths(months).toString();
            }
            Boolean onPace = pace != null && averageNet != null ? averageNet.compareTo(pace) >= 0 : null;
            out.add(new SavingsProjectionResponse.GoalOutlook(g.getId(), g.getName(), g.getTargetAmount(), recorded, remaining,
                    g.getTargetDate() == null ? null : g.getTargetDate().toString(), pace, months, completion, onPace));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ScenarioSimulationResponse simulateScenario(User user, ScenarioSimulationRequest request) {
        int horizon = request.horizonMonths() != null ? request.horizonMonths() : 6;
        validateHorizon(horizon);
        ForecastHistory history = historyLoader.load(user);
        return scenarioSimulator.simulate(request, horizon, cashFlow(history, horizon), categories(history, horizon), history.activeRecurring());
    }

    @Transactional(readOnly = true)
    public BacktestSummaryResponse getBacktestSummary(User user) {
        ForecastHistory history = historyLoader.load(user);
        List<MonthFigures> months = history.usableMonths();
        List<String> limitations = new ArrayList<>(List.of(
                "Evaluated on this account's own recorded history; synthetic fixtures are evaluated separately (docs/TESTING.md).",
                "Each forecast is scored using only months that precede its origin (chronological rolling origin, no random splits).",
                "Fewer than 6 scored forecasts at a horizon is reported as INCONCLUSIVE; a handful of months cannot establish accuracy.",
                "Past performance does not guarantee future results; the baselines are 'last month repeats' (naive) and 'mean of the last 3 months'."));
        if (months.size() < MIN_MONTHS_LIMITED + 1) {
            limitations.add(0, "Not enough history to score any forecast: at least " + (MIN_MONTHS_LIMITED + 1) + " usable months are needed.");
            return new BacktestSummaryResponse("INSUFFICIENT_DATA", "USER_HISTORY", ForecastMethod.NAME, ForecastMethod.VERSION,
                    "CHRONOLOGICAL_ROLLING_ORIGIN", history.asOf().toString(), months.size(), List.of(), limitations);
        }
        List<Obs> expense = months.stream().map(m -> obs(m.month(), m.expenses())).toList();
        List<Obs> income = months.stream().map(m -> obs(m.month(), m.income())).toList();
        List<Obs> net = months.stream().map(m -> obs(m.month(), m.net())).toList();

        List<SeriesResult> results = new ArrayList<>();
        boolean any = false;
        for (Object[] s : new Object[][]{{"EXPENSES", expense}, {"INCOME", income}, {"NET", net}}) {
            @SuppressWarnings("unchecked")
            List<Obs> series = (List<Obs>) s[1];
            List<HorizonResult> horizons = new ArrayList<>();
            for (int h : new int[]{1, 2, 3, 6}) {
                ForecastBacktest.Run run = ForecastBacktest.run(series, h);
                if (run.size() == 0) {
                    continue;
                }
                any = true;
                ForecastBacktest.Metrics model = ForecastBacktest.model(run);
                ForecastBacktest.Metrics naive = ForecastBacktest.naive(run);
                ForecastBacktest.Metrics mean3 = ForecastBacktest.mean3(run);
                horizons.add(new HorizonResult(h, run.size(), metrics(model), metrics(naive), metrics(mean3),
                        ForecastBacktest.verdict(model, naive, mean3)));
            }
            results.add(new SeriesResult((String) s[0], series.size(), horizons));
        }
        if (income.stream().filter(o -> o.value() > 0).count() < MIN_INCOME_MONTHS) {
            limitations.add("Income was recorded in fewer than " + MIN_INCOME_MONTHS + " months, so the INCOME and NET results are not meaningful.");
        }
        return new BacktestSummaryResponse(any ? "COMPLETED" : "INSUFFICIENT_DATA", "USER_HISTORY", ForecastMethod.NAME, ForecastMethod.VERSION,
                "CHRONOLOGICAL_ROLLING_ORIGIN", history.asOf().toString(), months.size(), results, limitations);
    }

    // ------------------------------------------------------------------------------------------------
    // Series forecasting with bands, floor and explanations
    // ------------------------------------------------------------------------------------------------

    /** Per forecast month k = 1..H after the current month. */
    record SeriesForecast(
            ForecastMethod.Projection projection,
            List<Estimate> estimates,
            List<ForecastBacktest.Run> runs,
            List<Boolean> floorApplied,
            List<List<String>> factors
    ) {
    }

    /**
     * Forecasts {@code horizon} months after the current month. {@code origin} is the index of the last
     * complete month, so the method is asked for {@code horizon + 1} steps and the first (the current,
     * incomplete month) is skipped. Bands come from the method's own past errors at the same distance.
     */
    SeriesForecast forecastSeries(List<Obs> series, int origin, int horizon, boolean nonNegative, BigDecimal floor) {
        ForecastMethod.Projection p = ForecastMethod.project(series, origin, horizon + 1).orElseThrow();
        List<Estimate> estimates = new ArrayList<>();
        List<ForecastBacktest.Run> runs = new ArrayList<>();
        List<Boolean> floorApplied = new ArrayList<>();
        List<List<String>> factors = new ArrayList<>();
        for (int k = 1; k <= horizon; k++) {
            BigDecimal point = p.values().get(k);
            ForecastBacktest.Run run = ForecastBacktest.run(series, k + 1);
            runs.add(run);
            List<String> f = new ArrayList<>();
            boolean lifted = floor.signum() > 0 && point.compareTo(floor) < 0;
            BigDecimal effective = lifted ? floor : point;
            if (lifted) {
                f.add("Raised to ₹" + floor + ", the total of detected active recurring payments.");
            }
            if (p.trendApplied()) {
                f.add("Includes a tested, damped trend of " + signed(p.slopePerMonth()) + " per month.");
            }
            Estimate estimate = ForecastBacktest.band(run, effective.doubleValue(), nonNegative)
                    .map(b -> new Estimate(effective, ForecastMethod.money(lifted ? Math.max(b[0], floor.doubleValue()) : b[0]), ForecastMethod.money(b[1])))
                    .orElse(Estimate.of(effective));
            if (estimate.lower() == null) {
                f.add("No uncertainty band: only " + run.size() + " past forecast(s) at this distance (" + ForecastMethod.MIN_INTERVAL_SAMPLES + " needed).");
            }
            estimates.add(estimate);
            floorApplied.add(lifted);
            factors.add(f);
        }
        return new SeriesForecast(p, estimates, runs, floorApplied, factors);
    }

    private Estimate netEstimate(Estimate income, Estimate expenses, ForecastBacktest.Run incomeRun, ForecastBacktest.Run expenseRun) {
        BigDecimal point = income.point().subtract(expenses.point());
        ForecastBacktest.Run netRun = ForecastBacktest.netRun(incomeRun, expenseRun);
        return ForecastBacktest.band(netRun, point.doubleValue(), false)
                .map(b -> new Estimate(point, ForecastMethod.money(b[0]), ForecastMethod.money(b[1])))
                .orElse(Estimate.of(point));
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private String reliability(ForecastHistory h, boolean incomeAvailable, boolean incomeRegular) {
        int n = h.usableMonths().size();
        if (n < MIN_MONTHS_LIMITED) {
            return UNAVAILABLE;
        }
        YearMonth last = h.lastCompleteMonth();
        long missingRecent = h.missingMonths().stream().filter(m -> !m.isBefore(last.minusMonths(5))).count();
        boolean stale = !last.equals(h.lastUsableMonth());
        boolean enoughVolume = h.usableTransactions() >= 3L * n;
        return n >= MIN_MONTHS_RELIABLE && missingRecent <= 1 && !stale && enoughVolume && incomeAvailable && incomeRegular
                ? RELIABLE : LIMITED;
    }

    private List<String> qualityWarnings(ForecastHistory h) {
        List<String> w = new ArrayList<>();
        if (h.isEmpty()) {
            return w;
        }
        if (!h.missingMonths().isEmpty()) {
            w.add("No usable data for " + h.missingMonths().size() + " month(s) between " + h.firstUsableMonth() + " and "
                    + h.lastCompleteMonth() + " (" + String.join(", ", h.missingMonths().stream().map(YearMonth::toString).toList())
                    + "). These are treated as unknown, not as zero; the data may be an incomplete import.");
        }
        if (!h.lastCompleteMonth().equals(h.lastUsableMonth())) {
            w.add("The latest complete month with data is " + h.lastUsableMonth() + ", not " + h.lastCompleteMonth()
                    + ". The history may be out of date; projections rest on older months.");
        }
        if (!h.nonInformativeMonths().isEmpty()) {
            w.add("Month(s) " + String.join(", ", h.nonInformativeMonths().stream().map(YearMonth::toString).toList())
                    + " contain only transfers, card payments or investments and were excluded.");
        }
        if (h.firstMonthPossiblyPartial()) {
            w.add("The first recorded month (" + h.firstUsableMonth() + ") starts after day 10, so it may be a partial month and may understate that month.");
        }
        if (h.usableTransactions() > 0 && h.uncategorisedTransactions() * 10 > h.usableTransactions()) {
            w.add(h.uncategorisedTransactions() + " of " + h.usableTransactions()
                    + " transactions are uncategorised. Totals are unaffected, but category forecasts may be incomplete.");
        }
        if (!h.usableMonths().isEmpty() && h.usableTransactions() < 3L * h.usableMonths().size()) {
            w.add("Low transaction volume (" + h.usableTransactions() + " transactions in " + h.usableMonths().size()
                    + " months): the history may be incomplete.");
        }
        return w;
    }

    private List<String> baseAssumptions(ForecastHistory h) {
        List<String> a = new ArrayList<>();
        a.add("Method " + ForecastMethod.NAME + " " + ForecastMethod.VERSION + ": a robust recent level (outliers clipped) plus a damped trend only when it is statistically significant.");
        a.add("Only complete calendar months are used; the current month (" + h.currentMonth() + ") is excluded from training.");
        a.add("Projected months are the " + "months after the current month; amounts are in rupees.");
        a.add("Seasonality is not modelled (it needs at least two full years of history).");
        a.add("Definitions follow docs/FINANCIAL_RULES.md: transfers, card payments and investments are excluded; refunds offset spending; uncategorised credits count as income.");
        a.add("Annual and irregular payments are not detected, so a large yearly bill can make its own month higher than projected.");
        return a;
    }

    private static String intervalNote() {
        return "Bands (when shown) span the 10th to 90th percentile of this method's own past forecast errors on this history, "
                + "added to the projection; at least " + ForecastMethod.MIN_INTERVAL_SAMPLES + " past errors at the same distance are required. "
                + "They are descriptive, not a calibrated probability.";
    }

    private HistoryInfo historyInfo(ForecastHistory h) {
        return new HistoryInfo(
                h.firstUsableMonth() == null ? null : h.firstUsableMonth().toString(),
                h.lastUsableMonth() == null ? null : h.lastUsableMonth().toString(),
                h.usableMonths().size(), MIN_MONTHS_LIMITED, MIN_MONTHS_RELIABLE,
                h.missingMonths().stream().map(YearMonth::toString).toList(),
                h.usableTransactions(), h.firstMonthPossiblyPartial());
    }

    private static void addOutlierWarnings(List<String> warnings, String what, ForecastMethod.Projection p) {
        if (!p.outlierIndexes().isEmpty()) {
            warnings.add(what + " in " + String.join(", ", p.outlierIndexes().stream().map(i -> ForecastMethod.monthOf(i).toString()).toList())
                    + " was unusually high or low and was limited so it does not distort the projection.");
        }
    }

    private static double coefficientOfVariation(List<Obs> series) {
        double mean = series.stream().mapToDouble(Obs::value).average().orElse(0);
        if (mean <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        double variance = series.stream().mapToDouble(o -> (o.value() - mean) * (o.value() - mean)).sum() / Math.max(1, series.size() - 1);
        return Math.sqrt(variance) / mean;
    }

    private static Obs obs(YearMonth month, BigDecimal value) {
        return new Obs(ForecastMethod.indexOf(month), value.doubleValue());
    }

    private static ErrorMetrics metrics(ForecastBacktest.Metrics m) {
        return new ErrorMetrics(money(m.mae()), m.wape() == null ? null : money(m.wape() * 100), money(m.rmse()), money(m.bias()));
    }

    private static BigDecimal money(double v) {
        return ForecastMethod.money(v);
    }

    private static String signed(double v) {
        return (v >= 0 ? "+₹" : "-₹") + ForecastMethod.money(Math.abs(v));
    }

    static String canonical(String category) {
        return TransactionCategory.parse(category).map(Enum::name).orElse("UNCATEGORIZED");
    }

    static String label(String id) {
        if ("UNCATEGORIZED".equals(id)) {
            return "Uncategorized";
        }
        String s = id.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void validateHorizon(int horizonMonths) {
        if (horizonMonths < 1 || horizonMonths > MAX_HORIZON) {
            throw ApiException.badRequest("Forecast horizon must be between 1 and " + MAX_HORIZON + " months.");
        }
    }

    /** Distinct category ids that are expense or unknown flow, for validation in scenarios. */
    static Set<String> adjustableCategories() {
        Set<String> ids = new HashSet<>();
        for (TransactionCategory c : TransactionCategory.values()) {
            if (c.flow() == TransactionCategory.Flow.EXPENSE || c.flow() == TransactionCategory.Flow.UNKNOWN) {
                ids.add(c.name());
            }
        }
        return ids;
    }
}
