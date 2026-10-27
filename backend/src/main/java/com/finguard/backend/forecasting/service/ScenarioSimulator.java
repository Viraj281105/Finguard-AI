package com.finguard.backend.forecasting.service;

import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.forecasting.dto.CashFlowForecastResponse;
import com.finguard.backend.forecasting.dto.CashFlowForecastResponse.ProjectedMonth;
import com.finguard.backend.forecasting.dto.CategorySpendingForecastResponse;
import com.finguard.backend.forecasting.dto.CategorySpendingForecastResponse.CategoryForecast;
import com.finguard.backend.forecasting.dto.ForecastCommon;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest.AddedRecurringExpense;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest.Adjustment;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest.AdjustmentType;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest.CategoryAdjustment;
import com.finguard.backend.forecasting.dto.ScenarioSimulationResponse;
import com.finguard.backend.forecasting.dto.ScenarioSimulationResponse.CategoryEffect;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest.LoanAdjustment;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest.OneTimeItem;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest.OneTimeKind;
import com.finguard.backend.forecasting.dto.ScenarioSimulationResponse.Delta;
import com.finguard.backend.forecasting.dto.ScenarioSimulationResponse.LoanEffect;
import com.finguard.backend.forecasting.dto.ScenarioSimulationResponse.Month;
import com.finguard.backend.forecasting.dto.ScenarioSimulationResponse.Totals;
import com.finguard.backend.intelligence.dto.RecurringExpenseItemDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure what-if arithmetic on top of the baseline forecast. It receives the already-computed baseline,
 * reads nothing and writes nothing, so a scenario cannot change a transaction, budget or goal.
 *
 * <p>Semantics (docs/FINANCIAL_RULES.md section 7.5):
 * <ul>
 *   <li>Adjustments are flat monthly changes applied to every projected month; percentages are relative to
 *       the baseline for that month (income) or the average baseline month (categories).</li>
 *   <li>A category change, a removed recurring payment and an added recurring payment in the same category are
 *       combined and clamped so the category cannot go below zero; recurring payments are already inside the
 *       category baseline, so removing one reduces that category rather than the total on top of it.</li>
 *   <li>Without a baseline there is no scenario: when the forecast is unavailable, or when income is adjusted but
 *       no income was observed, nothing is computed.</li>
 * </ul>
 */
@Component
public class ScenarioSimulator {

    public static final String DISCLAIMER =
            "Hypothetical: these figures are calculated on the fly from your projected baseline and change nothing in your "
                    + "transactions, budgets or goals. They are not predictions of what will happen.";

    static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(500);
    static final BigDecimal MIN_PERCENT = BigDecimal.valueOf(-100);

    /** A loan as the simulator sees it: the estimated outstanding balance at the start of the first projected month. */
    public record LoanInput(String id, String name, BigDecimal outstandingBalance, BigDecimal emi, BigDecimal annualRatePercent) {
    }

    public ScenarioSimulationResponse simulate(
            ScenarioSimulationRequest request,
            int horizon,
            CashFlowForecastResponse baseline,
            CategorySpendingForecastResponse categories,
            List<RecurringExpenseItemDto> activeRecurring
    ) {
        return simulate(request, horizon, baseline, categories, activeRecurring, Map.of());
    }

    public ScenarioSimulationResponse simulate(
            ScenarioSimulationRequest request,
            int horizon,
            CashFlowForecastResponse baseline,
            CategorySpendingForecastResponse categories,
            List<RecurringExpenseItemDto> activeRecurring,
            Map<String, LoanInput> loans
    ) {
        String name = request.name() == null || request.name().isBlank() ? "Hypothetical scenario" : request.name().trim();
        validate(request, baseline, activeRecurring);
        validateLoansAndItems(request, horizon, baseline, loans);

        if (ForecastCommon.UNAVAILABLE.equals(baseline.reliability())) {
            return new ScenarioSimulationResponse(name, true, DISCLAIMER, false,
                    baseline.warnings().isEmpty() ? "Not enough history for a baseline forecast." : baseline.warnings().get(0),
                    baseline.reliability(), horizon, false, List.copyOf(baseline.warnings()), null, null, null, List.of(), List.of(), List.of(), List.of());
        }

        List<String> warnings = new ArrayList<>();
        if (ForecastCommon.LIMITED.equals(baseline.reliability())) {
            warnings.add("The baseline forecast is LIMITED (thin or irregular history); this scenario inherits that limitation.");
        }
        List<String> explanations = new ArrayList<>();
        boolean incomeAvailable = baseline.incomeAvailable();
        List<ProjectedMonth> months = baseline.projected();

        // ---- income -------------------------------------------------------------------------
        Adjustment incomeAdj = request.incomeAdjustment();
        if (incomeAdj != null) {
            explanations.add(incomeAdj.type() == AdjustmentType.PERCENTAGE
                    ? String.format("Income changed by %s%% in every projected month.", plain(incomeAdj.value()))
                    : String.format("Income changed by ₹%s per month.", plain(incomeAdj.value())));
        }

        // ---- categories ---------------------------------------------------------------------
        Map<String, CategoryForecast> byCategory = new LinkedHashMap<>();
        categories.categories().forEach(c -> byCategory.put(c.category(), c));
        Map<String, BigDecimal> baselineMonthly = new HashMap<>();
        Map<String, BigDecimal> delta = new HashMap<>();
        Map<String, List<String>> notes = new HashMap<>();
        byCategory.forEach((id, c) -> baselineMonthly.put(id, c.projectedTotal().divide(BigDecimal.valueOf(horizon), 2, RoundingMode.HALF_UP)));
        BigDecimal unattributed = BigDecimal.ZERO;

        for (CategoryAdjustment a : nullSafe(request.categoryAdjustments())) {
            String id = ForecastingService.canonical(a.category());
            BigDecimal base = baselineMonthly.get(id);
            if (base == null) {
                warnings.add(ForecastingService.label(id) + " has no category forecast (too little spending history), so its adjustment was ignored.");
                continue;
            }
            BigDecimal d = a.type() == AdjustmentType.PERCENTAGE
                    ? base.multiply(a.value()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    : a.value().setScale(2, RoundingMode.HALF_UP);
            delta.merge(id, d, BigDecimal::add);
            explanations.add(String.format("%s spending %s: %s₹%s per month on a baseline of ₹%s.", ForecastingService.label(id),
                    a.type() == AdjustmentType.PERCENTAGE ? "changed by " + plain(a.value()) + "%" : "changed by ₹" + plain(a.value()) + " a month",
                    d.signum() >= 0 ? "+" : "-", d.abs().toPlainString(), base));
        }
        for (String removedId : nullSafe(request.removedRecurringExpenseIds())) {
            RecurringExpenseItemDto r = activeRecurring.stream().filter(x -> x.id().equals(removedId)).findFirst().orElseThrow();
            String id = ForecastingService.canonical(r.category());
            BigDecimal amount = r.amount().negate();
            if (baselineMonthly.containsKey(id)) {
                delta.merge(id, amount, BigDecimal::add);
                notes.computeIfAbsent(id, k -> new ArrayList<>()).add("removes " + r.title());
            } else {
                unattributed = unattributed.add(amount);
            }
            explanations.add("Recurring payment “" + r.title() + "” (₹" + r.amount() + " a month) removed.");
        }
        for (AddedRecurringExpense add : nullSafe(request.addedRecurringExpenses())) {
            String id = add.category() == null || add.category().isBlank() ? null : ForecastingService.canonical(add.category());
            BigDecimal amount = add.monthlyAmount().setScale(2, RoundingMode.HALF_UP);
            if (id != null && baselineMonthly.containsKey(id)) {
                delta.merge(id, amount, BigDecimal::add);
                notes.computeIfAbsent(id, k -> new ArrayList<>()).add("adds " + add.title());
            } else {
                unattributed = unattributed.add(amount);
            }
            explanations.add("Assumed recurring expense “" + add.title() + "” of ₹" + amount + " a month added.");
        }

        // Clamp: a category cannot be reduced below zero spending.
        List<CategoryEffect> effects = new ArrayList<>();
        BigDecimal expenseDelta = unattributed;
        for (Map.Entry<String, BigDecimal> e : delta.entrySet()) {
            BigDecimal base = baselineMonthly.get(e.getKey());
            BigDecimal applied = e.getValue().max(base.negate());
            List<String> n = new ArrayList<>(notes.getOrDefault(e.getKey(), List.of()));
            if (applied.compareTo(e.getValue()) != 0) {
                n.add("reduction limited so spending cannot go below zero");
                warnings.add(ForecastingService.label(e.getKey()) + ": the reduction exceeds the projected spending and was limited to zero spending.");
            }
            expenseDelta = expenseDelta.add(applied);
            effects.add(new CategoryEffect(e.getKey(), ForecastingService.label(e.getKey()), base, base.add(applied).setScale(2, RoundingMode.HALF_UP),
                    applied.setScale(2, RoundingMode.HALF_UP), n.isEmpty() ? "adjusted" : String.join("; ", n)));
        }
        if (unattributed.signum() != 0) {
            explanations.add("₹" + unattributed.abs().toPlainString() + " per month of "
                    + (unattributed.signum() > 0 ? "added" : "removed") + " recurring payments could not be placed in a forecast category and is applied to total spending only.");
        }

        // ---- loans & one-time items ------------------------------------------------------------
        BigDecimal[] loanDelta = new BigDecimal[horizon];
        BigDecimal[] oneTimeExpense = new BigDecimal[horizon];
        BigDecimal[] oneTimeIncome = new BigDecimal[horizon];
        java.util.Arrays.fill(loanDelta, BigDecimal.ZERO);
        java.util.Arrays.fill(oneTimeExpense, BigDecimal.ZERO);
        java.util.Arrays.fill(oneTimeIncome, BigDecimal.ZERO);
        List<LoanEffect> loanEffects = new ArrayList<>();
        for (LoanAdjustment a : nullSafe(request.loanAdjustments())) {
            LoanInput loan = loans.get(a.loanId());
            LoanAmortization.Run base = LoanAmortization.run(loan.outstandingBalance(), loan.annualRatePercent(), loan.emi(), BigDecimal.ZERO, horizon);
            LoanAmortization.Run scn = switch (a.action()) {
                case CLOSE -> LoanAmortization.run(loan.outstandingBalance(), loan.annualRatePercent(), loan.emi(), loan.outstandingBalance(), horizon);
                case PREPAY -> LoanAmortization.run(loan.outstandingBalance(), loan.annualRatePercent(), loan.emi(), a.amount(), horizon);
                case CHANGE_EMI -> LoanAmortization.run(loan.outstandingBalance(), loan.annualRatePercent(), a.amount(), BigDecimal.ZERO, horizon);
            };
            BigDecimal baseOut = BigDecimal.ZERO;
            BigDecimal scnOut = BigDecimal.ZERO;
            for (int i = 0; i < horizon; i++) {
                loanDelta[i] = loanDelta[i].add(scn.payments().get(i)).subtract(base.payments().get(i));
                baseOut = baseOut.add(base.payments().get(i));
                scnOut = scnOut.add(scn.payments().get(i));
            }
            BigDecimal saved = base.totalInterest().subtract(scn.totalInterest());
            List<String> n = new ArrayList<>();
            String what = switch (a.action()) {
                case CLOSE -> "Repay the whole estimated balance of ₹" + loan.outstandingBalance().toPlainString() + " in month 1 and stop the EMI";
                case PREPAY -> "Prepay ₹" + a.amount().toPlainString() + " in month 1 and keep the EMI of ₹" + loan.emi().toPlainString();
                case CHANGE_EMI -> "Change the EMI from ₹" + loan.emi().toPlainString() + " to ₹" + a.amount().toPlainString();
            };
            n.add("Within the " + horizon + "-month horizon cash out changes by " + (scnOut.compareTo(baseOut) >= 0 ? "+" : "-") + "₹"
                    + scnOut.subtract(baseOut).abs().toPlainString() + "; over the remaining life of the loan interest changes by "
                    + (saved.signum() >= 0 ? "-" : "+") + "₹" + saved.abs().toPlainString() + ".");
            explanations.add(loan.name() + ": " + what + ". Payoff moves from " + base.monthsToPayoff() + " to " + scn.monthsToPayoff()
                    + " month(s); estimated interest saved over the remaining life: ₹" + saved.toPlainString() + ".");
            loanEffects.add(new LoanEffect(loan.id(), loan.name(), a.action().name(), loan.outstandingBalance(), base.monthsToPayoff(),
                    scn.monthsToPayoff(), base.totalInterest(), scn.totalInterest(), saved, scnOut.subtract(baseOut), n));
        }
        if (!loanEffects.isEmpty()) {
            warnings.add("Loan figures assume reducing-balance interest from the loan start date, an unchanged rate and no fees or prepayment charges; "
                    + "the outstanding balance is an estimate, not a lender statement.");
        }
        for (OneTimeItem item : nullSafe(request.oneTimeItems())) {
            int i = item.month() - 1;
            BigDecimal amount = item.amount().setScale(2, RoundingMode.HALF_UP);
            if (item.kind() == OneTimeKind.EXPENSE) {
                oneTimeExpense[i] = oneTimeExpense[i].add(amount);
            } else {
                oneTimeIncome[i] = oneTimeIncome[i].add(amount);
            }
            explanations.add("One-time " + item.kind().name().toLowerCase() + " “" + item.title() + "” of ₹" + amount + " in projected month " + item.month() + ".");
        }

        // ---- months & totals ------------------------------------------------------------------
        List<Month> out = new ArrayList<>();
        BigDecimal bi = BigDecimal.ZERO;
        BigDecimal si = BigDecimal.ZERO;
        BigDecimal be = BigDecimal.ZERO;
        BigDecimal se = BigDecimal.ZERO;
        int monthIdx = 0;
        for (ProjectedMonth m : months) {
            int mi = monthIdx++;
            BigDecimal baseExp = m.expenses().point();
            BigDecimal scnExp = baseExp.add(expenseDelta).add(loanDelta[mi]).add(oneTimeExpense[mi]).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            BigDecimal baseInc = null;
            BigDecimal scnInc = null;
            if (incomeAvailable) {
                baseInc = m.income().point();
                BigDecimal incDelta = incomeAdj == null ? BigDecimal.ZERO
                        : incomeAdj.type() == AdjustmentType.PERCENTAGE
                        ? baseInc.multiply(incomeAdj.value()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                        : incomeAdj.value();
                scnInc = baseInc.add(incDelta).add(oneTimeIncome[mi]).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
                bi = bi.add(baseInc);
                si = si.add(scnInc);
            }
            be = be.add(baseExp);
            se = se.add(scnExp);
            out.add(new Month(m.month(), baseInc, scnInc, baseExp, scnExp,
                    baseInc == null ? null : baseInc.subtract(baseExp), scnInc == null ? null : scnInc.subtract(scnExp)));
        }

        Totals baseTotals = totals(incomeAvailable ? bi : null, be);
        Totals scnTotals = totals(incomeAvailable ? si : null, se);
        Delta d = new Delta(
                incomeAvailable ? scnTotals.income().subtract(baseTotals.income()) : null,
                scnTotals.expenses().subtract(baseTotals.expenses()),
                incomeAvailable ? scnTotals.net().subtract(baseTotals.net()) : null,
                incomeAvailable && scnTotals.savingsRatePercent() != null && baseTotals.savingsRatePercent() != null
                        ? scnTotals.savingsRatePercent().subtract(baseTotals.savingsRatePercent()) : null);

        if (explanations.isEmpty()) {
            explanations.add("No adjustments chosen: the scenario equals the baseline.");
        } else if (incomeAvailable) {
            explanations.add("Over " + horizon + " month(s) the projected surplus changes by " + (d.net().signum() >= 0 ? "+" : "-") + "₹"
                    + d.net().abs().toPlainString() + ".");
        }
        if (!incomeAvailable) {
            warnings.add("No income baseline: only spending is compared.");
        }
        effects.sort((a, b) -> b.deltaMonthly().abs().compareTo(a.deltaMonthly().abs()));
        return new ScenarioSimulationResponse(name, true, DISCLAIMER, true, null, baseline.reliability(), horizon, incomeAvailable,
                warnings, baseTotals, scnTotals, d, out, effects, explanations, loanEffects);
    }

    private void validateLoansAndItems(ScenarioSimulationRequest r, int horizon, CashFlowForecastResponse baseline, Map<String, LoanInput> loans) {
        Set<String> seen = new HashSet<>();
        for (LoanAdjustment a : nullSafe(r.loanAdjustments())) {
            if (!loans.containsKey(a.loanId())) {
                throw ApiException.badRequest("Unknown loan '" + a.loanId() + "'.");
            }
            if (!seen.add(a.loanId())) {
                throw ApiException.badRequest("Loan '" + a.loanId() + "' appears more than once in loanAdjustments.");
            }
            if (a.action() != ScenarioSimulationRequest.LoanAction.CLOSE && a.amount() == null) {
                throw ApiException.badRequest("Action " + a.action() + " needs an amount.");
            }
        }
        for (OneTimeItem item : nullSafe(r.oneTimeItems())) {
            if (item.month() > horizon) {
                throw ApiException.badRequest("One-time item '" + item.title() + "' is in month " + item.month() + ", beyond the " + horizon + "-month horizon.");
            }
            if (item.kind() == OneTimeKind.INCOME && !ForecastCommon.UNAVAILABLE.equals(baseline.reliability()) && !baseline.incomeAvailable()) {
                throw ApiException.badRequest("A one-time income needs an income baseline, and no regular income was found in your history.");
            }
        }
    }

    private void validate(ScenarioSimulationRequest r, CashFlowForecastResponse baseline, List<RecurringExpenseItemDto> activeRecurring) {
        if (r.incomeAdjustment() != null) {
            checkValue(r.incomeAdjustment().type(), r.incomeAdjustment().value(), "income");
            if (!ForecastCommon.UNAVAILABLE.equals(baseline.reliability()) && !baseline.incomeAvailable()) {
                throw ApiException.badRequest("Income scenarios need an income baseline, and no regular income was found in your history.");
            }
        }
        Set<String> seen = new HashSet<>();
        for (CategoryAdjustment a : nullSafe(r.categoryAdjustments())) {
            checkValue(a.type(), a.value(), a.category());
            String id = requireAdjustableCategory(a.category());
            if (!seen.add(id)) {
                throw ApiException.badRequest("Category " + id + " appears more than once in categoryAdjustments.");
            }
        }
        for (AddedRecurringExpense add : nullSafe(r.addedRecurringExpenses())) {
            if (add.category() != null && !add.category().isBlank()) {
                requireAdjustableCategory(add.category());
            }
        }
        Set<String> known = new HashSet<>();
        activeRecurring.forEach(x -> known.add(x.id()));
        Set<String> removed = new HashSet<>();
        for (String id : nullSafe(r.removedRecurringExpenseIds())) {
            if (!known.contains(id)) {
                throw ApiException.badRequest("Unknown recurring expense id '" + id + "'.");
            }
            if (!removed.add(id)) {
                throw ApiException.badRequest("Recurring expense '" + id + "' is listed more than once.");
            }
        }
    }

    private static void checkValue(AdjustmentType type, BigDecimal value, String what) {
        if (type == AdjustmentType.PERCENTAGE && (value.compareTo(MIN_PERCENT) < 0 || value.compareTo(MAX_PERCENT) > 0)) {
            throw ApiException.badRequest("Percentage change for " + what + " must be between -100 and 500.");
        }
    }

    private static String requireAdjustableCategory(String raw) {
        String id = ForecastingService.canonical(raw);
        if ("UNCATEGORIZED".equals(id) && !"UNCATEGORIZED".equalsIgnoreCase(raw.trim())) {
            throw ApiException.badRequest("Unknown category '" + raw + "'.");
        }
        if (!ForecastingService.adjustableCategories().contains(id)) {
            throw ApiException.badRequest("Category " + id + " is not a spending category and cannot be adjusted.");
        }
        return id;
    }

    private static Totals totals(BigDecimal income, BigDecimal expenses) {
        BigDecimal net = income == null ? null : income.subtract(expenses);
        BigDecimal rate = income != null && net != null && income.signum() > 0
                ? net.multiply(BigDecimal.valueOf(100)).divide(income, 2, RoundingMode.HALF_UP) : null;
        return new Totals(income, expenses, net, rate);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
