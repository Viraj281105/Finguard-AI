package com.finguard.backend.forecasting.service;

import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.forecasting.dto.CashFlowForecastResponse;
import com.finguard.backend.forecasting.dto.CategorySpendingForecastResponse;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest.AddedRecurringExpense;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest.Adjustment;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest.AdjustmentType;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest.CategoryAdjustment;
import com.finguard.backend.forecasting.dto.ScenarioSimulationResponse;
import com.finguard.backend.intelligence.dto.RecurringExpenseItemDto;
import com.finguard.backend.ledger.transactions.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static com.finguard.backend.forecasting.service.LedgerFixtures.d;
import static com.finguard.backend.forecasting.service.LedgerFixtures.expense;
import static com.finguard.backend.forecasting.service.LedgerFixtures.history;
import static com.finguard.backend.forecasting.service.LedgerFixtures.income;
import static com.finguard.backend.forecasting.service.LedgerFixtures.months;
import static com.finguard.backend.forecasting.service.LedgerFixtures.once;
import static com.finguard.backend.forecasting.service.LedgerFixtures.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Scenario arithmetic on a SYNTHETIC steady ledger: income 50,000, rent 20,000, groceries 10,000, a 500
 * subscription (inside the 10,000 of "shopping"), so the baseline is exactly 50,000 / 30,000 / 20,000 a month.
 */
class ScenarioSimulatorTest {

    private static final YearMonth JAN = YearMonth.of(2026, 1);
    private static final LocalDate ASOF = d(2026, 7, 15);
    private final ScenarioSimulator simulator = new ScenarioSimulator();

    private static List<Transaction> steadyLedger() {
        List<Transaction> tx = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            YearMonth m = JAN.plusMonths(i);
            tx.add(income(m.atDay(1), "INCOME", "50000", once("salary", m)));
            tx.add(expense(m.atDay(3), "HOUSING", "20000", once("landlord", m)));
            tx.add(expense(m.atDay(15), "GROCERIES", "9500", once("market", m)));
            tx.add(expense(m.atDay(5), "SUBSCRIPTIONS", "500", "Netflix subscription"));
        }
        return tx;
    }

    private ScenarioSimulationResponse run(List<Transaction> tx, ScenarioSimulationRequest request, int horizon) {
        var h = history(tx, ASOF);
        ForecastingService s = service();
        CashFlowForecastResponse cash = s.cashFlow(h, horizon);
        CategorySpendingForecastResponse cats = s.categories(h, horizon);
        return simulator.simulate(request, horizon, cash, cats, h.activeRecurring());
    }

    private static ScenarioSimulationRequest request(Adjustment income, List<CategoryAdjustment> cats,
                                                     List<AddedRecurringExpense> added, List<String> removed) {
        return new ScenarioSimulationRequest("test", null, income, cats, added, removed);
    }

    private static Adjustment pct(String v) {
        return new Adjustment(AdjustmentType.PERCENTAGE, new BigDecimal(v));
    }

    private static Adjustment fixed(String v) {
        return new Adjustment(AdjustmentType.FIXED_AMOUNT, new BigDecimal(v));
    }

    private static CategoryAdjustment cat(String category, AdjustmentType type, String v) {
        return new CategoryAdjustment(category, type, new BigDecimal(v));
    }

    // ---- baseline equals scenario when nothing changes --------------------------------------------------

    @Test
    void withoutAdjustmentsTheScenarioIsExactlyTheBaseline() {
        ScenarioSimulationResponse r = run(steadyLedger(), request(null, null, null, null), 3);

        assertThat(r.available()).isTrue();
        assertThat(r.hypothetical()).isTrue();
        assertThat(r.baseline()).usingRecursiveComparison().isEqualTo(r.scenario());
        assertThat(r.delta().net()).isEqualByComparingTo("0");
        assertThat(r.delta().expenses()).isEqualByComparingTo("0");
        assertThat(r.explanations()).containsExactly("No adjustments chosen: the scenario equals the baseline.");
        assertThat(r.baseline().income()).isEqualByComparingTo("150000");
        assertThat(r.baseline().expenses()).isEqualByComparingTo("90000");
        assertThat(r.baseline().net()).isEqualByComparingTo("60000");
        assertThat(r.disclaimer()).contains("change nothing in your transactions, budgets or goals");
    }

    // ---- by-hand adjustments ---------------------------------------------------------------------------------

    @Test
    void cuttingACategoryByAPercentage_byHand() {
        // groceries baseline 9,500/month; -20 % = -1,900/month; over 3 months expenses 90,000 -> 84,300, net 60,000 -> 65,700
        ScenarioSimulationResponse r = run(steadyLedger(),
                request(null, List.of(cat("GROCERIES", AdjustmentType.PERCENTAGE, "-20")), null, null), 3);

        assertThat(r.delta().expenses()).isEqualByComparingTo("-5700");
        assertThat(r.delta().net()).isEqualByComparingTo("5700");
        assertThat(r.scenario().expenses()).isEqualByComparingTo("84300");
        assertThat(r.scenario().net()).isEqualByComparingTo("65700");
        // savings rate 40 % -> 65,700/150,000 = 43.8 %
        assertThat(r.scenario().savingsRatePercent()).isEqualByComparingTo("43.80");
        assertThat(r.delta().savingsRatePoints()).isEqualByComparingTo("3.80");
        assertThat(r.categories()).hasSize(1);
        assertThat(r.categories().get(0).deltaMonthly()).isEqualByComparingTo("-1900");
        assertThat(r.categories().get(0).scenarioMonthly()).isEqualByComparingTo("7600");
    }

    @Test
    void aFixedCategoryReductionAndAnIncomeChange_byHand() {
        // housing -2,000/month, income +10 % (= +5,000/month): net +7,000/month = +21,000 over 3 months
        ScenarioSimulationResponse r = run(steadyLedger(),
                request(pct("10"), List.of(cat("HOUSING", AdjustmentType.FIXED_AMOUNT, "-2000")), null, null), 3);

        assertThat(r.delta().income()).isEqualByComparingTo("15000");
        assertThat(r.delta().expenses()).isEqualByComparingTo("-6000");
        assertThat(r.delta().net()).isEqualByComparingTo("21000");
        assertThat(r.months()).hasSize(3);
        assertThat(r.months().get(0).scenarioIncome()).isEqualByComparingTo("55000");
        assertThat(r.months().get(0).scenarioExpenses()).isEqualByComparingTo("28000");
        assertThat(r.months().get(0).scenarioNet()).isEqualByComparingTo("27000");
        assertThat(r.months().get(0).baselineNet()).isEqualByComparingTo("20000");
    }

    @Test
    void aFixedIncomeDrop() {
        ScenarioSimulationResponse r = run(steadyLedger(), request(fixed("-10000"), null, null, null), 2);

        assertThat(r.delta().income()).isEqualByComparingTo("-20000");
        assertThat(r.scenario().net()).isEqualByComparingTo("20000");  // (40,000-30,000) x 2
    }

    @Test
    void incomeCannotGoBelowZero() {
        ScenarioSimulationResponse r = run(steadyLedger(), request(pct("-100"), null, null, null), 1);

        assertThat(r.scenario().income()).isEqualByComparingTo("0");
        assertThat(r.scenario().net()).isEqualByComparingTo("-30000");
        assertThat(r.scenario().savingsRatePercent()).isNull();       // undefined without income, not 0 %
    }

    @Test
    void anAssumedRecurringExpenseIsAddedToEveryMonth() {
        ScenarioSimulationResponse r = run(steadyLedger(),
                request(null, null, List.of(new AddedRecurringExpense("Gym", null, new BigDecimal("1500"))), null), 4);

        assertThat(r.delta().expenses()).isEqualByComparingTo("6000");
        assertThat(r.delta().net()).isEqualByComparingTo("-6000");
        assertThat(r.explanations()).anyMatch(e -> e.contains("Gym") && e.contains("1500"));
        assertThat(r.explanations()).anyMatch(e -> e.contains("could not be placed in a forecast category"));
    }

    @Test
    void anAssumedRecurringExpenseInAKnownCategoryRaisesThatCategory() {
        ScenarioSimulationResponse r = run(steadyLedger(),
                request(null, null, List.of(new AddedRecurringExpense("Magazine", "SUBSCRIPTIONS", new BigDecimal("300"))), null), 2);

        assertThat(r.delta().expenses()).isEqualByComparingTo("600");
        assertThat(r.categories().get(0).category()).isEqualTo("SUBSCRIPTIONS");
        assertThat(r.categories().get(0).scenarioMonthly()).isEqualByComparingTo("800");   // 500 + 300
    }

    @Test
    void removingADetectedRecurringPaymentReducesItsCategoryOnceNotTheTotalTwice() {
        var h = history(steadyLedger(), ASOF);
        RecurringExpenseItemDto netflix = h.activeRecurring().stream()
                .filter(r -> r.title().toLowerCase().contains("netflix")).findFirst().orElseThrow();

        ScenarioSimulationResponse r = run(steadyLedger(), request(null, null, null, List.of(netflix.id())), 3);

        assertThat(r.delta().expenses()).isEqualByComparingTo("-1500");   // 500 x 3
        assertThat(r.categories()).hasSize(1);
        assertThat(r.categories().get(0).category()).isEqualTo("SUBSCRIPTIONS");
        assertThat(r.categories().get(0).scenarioMonthly()).isEqualByComparingTo("0");
        assertThat(r.categories().get(0).note()).contains("removes");
    }

    @Test
    void removingTheRecurringPaymentAndCuttingItsCategoryCannotGoBelowZero() {
        var h = history(steadyLedger(), ASOF);
        String netflixId = h.activeRecurring().stream().filter(r -> r.title().toLowerCase().contains("netflix"))
                .findFirst().orElseThrow().id();

        ScenarioSimulationResponse r = run(steadyLedger(), request(null,
                List.of(cat("SUBSCRIPTIONS", AdjustmentType.PERCENTAGE, "-100")), null, List.of(netflixId)), 1);

        // -500 (cut) and -500 (removed) would be -1,000 on a 500 category: limited to -500
        assertThat(r.delta().expenses()).isEqualByComparingTo("-500");
        assertThat(r.categories().get(0).deltaMonthly()).isEqualByComparingTo("-500");
        assertThat(r.warnings()).anyMatch(w -> w.contains("exceeds the projected spending"));
    }

    @Test
    void anAdjustmentToACategoryWithoutAForecastIsIgnoredAndSaid() {
        List<Transaction> tx = steadyLedger();
        tx.add(expense(d(2026, 3, 9), "ENTERTAINMENT", "800", "onemovie"));   // one transaction: no category forecast

        ScenarioSimulationResponse r = run(tx, request(null, List.of(cat("ENTERTAINMENT", AdjustmentType.FIXED_AMOUNT, "-500")), null, null), 2);

        assertThat(r.delta().expenses()).isEqualByComparingTo("0");
        assertThat(r.warnings()).anyMatch(w -> w.contains("Entertainment has no category forecast"));
    }

    // ---- unavailable baseline ---------------------------------------------------------------------------------------------

    @Test
    void withoutABaselineThereIsNoScenarioAndNoNumbers() {
        ScenarioSimulationResponse r = run(months(YearMonth.of(2026, 5), 2, "50000", "20000", "10000"),
                request(pct("10"), null, null, null), 3);

        assertThat(r.available()).isFalse();
        assertThat(r.unavailableReason()).contains("At least 3 complete months");
        assertThat(r.baseline()).isNull();
        assertThat(r.scenario()).isNull();
        assertThat(r.delta()).isNull();
        assertThat(r.months()).isEmpty();
    }

    @Test
    void anIncomeScenarioNeedsAnObservedIncomeBaseline() {
        List<Transaction> noIncome = months(JAN, 6, null, "20000", "10000");

        assertThatThrownBy(() -> run(noIncome, request(pct("10"), null, null, null), 3))
                .isInstanceOf(ApiException.class).hasMessageContaining("need an income baseline");

        // spending-only scenarios still work without income
        ScenarioSimulationResponse r = run(noIncome,
                request(null, List.of(cat("GROCERIES", AdjustmentType.PERCENTAGE, "-10")), null, null), 2);
        assertThat(r.available()).isTrue();
        assertThat(r.incomeAvailable()).isFalse();
        assertThat(r.baseline().income()).isNull();
        assertThat(r.delta().expenses()).isEqualByComparingTo("-2000");
        assertThat(r.delta().net()).isNull();
    }

    // ---- validation ---------------------------------------------------------------------------------------------------------------

    @Test
    void invalidScenariosAreRejectedWithClearMessages() {
        List<Transaction> tx = steadyLedger();

        assertThatThrownBy(() -> run(tx, request(pct("501"), null, null, null), 3))
                .isInstanceOf(ApiException.class).hasMessageContaining("between -100 and 500");
        assertThatThrownBy(() -> run(tx, request(pct("-100.5"), null, null, null), 3))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> run(tx, request(null, List.of(cat("NOPE", AdjustmentType.PERCENTAGE, "-5")), null, null), 3))
                .isInstanceOf(ApiException.class).hasMessageContaining("Unknown category");
        assertThatThrownBy(() -> run(tx, request(null, List.of(cat("INCOME", AdjustmentType.PERCENTAGE, "-5")), null, null), 3))
                .isInstanceOf(ApiException.class).hasMessageContaining("not a spending category");
        assertThatThrownBy(() -> run(tx, request(null, List.of(cat("TRANSFERS", AdjustmentType.PERCENTAGE, "-5")), null, null), 3))
                .isInstanceOf(ApiException.class).hasMessageContaining("not a spending category");
        assertThatThrownBy(() -> run(tx, request(null, List.of(
                cat("GROCERIES", AdjustmentType.PERCENTAGE, "-5"), cat("groceries", AdjustmentType.FIXED_AMOUNT, "-5")), null, null), 3))
                .isInstanceOf(ApiException.class).hasMessageContaining("more than once");
        assertThatThrownBy(() -> run(tx, request(null, null, null, List.of("rec-does-not-exist")), 3))
                .isInstanceOf(ApiException.class).hasMessageContaining("Unknown recurring expense id");
        assertThatThrownBy(() -> run(tx, request(null, null,
                List.of(new AddedRecurringExpense("x", "BOGUS", new BigDecimal("10"))), null), 3))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theScenarioIsPureCalculation_runningItTwiceGivesTheSameAnswerAndTheInputsAreUntouched() {
        List<Transaction> tx = steadyLedger();
        int before = tx.size();
        BigDecimal firstAmount = tx.get(0).getAmount();
        ScenarioSimulationRequest req = request(pct("5"), List.of(cat("GROCERIES", AdjustmentType.PERCENTAGE, "-10")), null, null);

        assertThat(run(tx, req, 3)).usingRecursiveComparison().isEqualTo(run(tx, req, 3));
        assertThat(tx).hasSize(before);
        assertThat(tx.get(0).getAmount()).isEqualTo(firstAmount);
    }
}
