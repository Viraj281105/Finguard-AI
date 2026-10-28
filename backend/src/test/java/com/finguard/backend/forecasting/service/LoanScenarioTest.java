package com.finguard.backend.forecasting.service;

import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest.LoanAction;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest.LoanAdjustment;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest.OneTimeItem;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest.OneTimeKind;
import com.finguard.backend.forecasting.dto.ScenarioSimulationResponse;
import com.finguard.backend.forecasting.service.ScenarioSimulator.LoanInput;
import com.finguard.backend.ledger.transactions.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.finguard.backend.forecasting.service.LedgerFixtures.d;
import static com.finguard.backend.forecasting.service.LedgerFixtures.expense;
import static com.finguard.backend.forecasting.service.LedgerFixtures.history;
import static com.finguard.backend.forecasting.service.LedgerFixtures.income;
import static com.finguard.backend.forecasting.service.LedgerFixtures.once;
import static com.finguard.backend.forecasting.service.LedgerFixtures.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * v0.9.0: loan arithmetic against published reference values, and loan / one-time scenario effects on a SYNTHETIC
 * steady ledger (income 50,000, expenses 30,000 a month).
 */
class LoanScenarioTest {

    private static final BigDecimal EMI = new BigDecimal("8884.88");
    private static final LoanInput LOAN = new LoanInput("L1", "Car loan", new BigDecimal("100000.00"), EMI, new BigDecimal("12"));
    private final ScenarioSimulator simulator = new ScenarioSimulator();

    // ---- amortisation vs reference values -------------------------------------------------------------

    @Test
    void emiMatchesPublishedReferenceValues() {
        // 1,00,000 at 12 % for 12 months -> 8,884.88; 10,00,000 at 10 % for 120 months -> 13,215.07
        assertThat(LoanAmortization.emi(new BigDecimal("100000"), new BigDecimal("12"), 12)).isEqualByComparingTo("8884.88");
        assertThat(LoanAmortization.emi(new BigDecimal("1000000"), new BigDecimal("10"), 120)).isEqualByComparingTo("13215.07");
        assertThat(LoanAmortization.emi(new BigDecimal("12000"), BigDecimal.ZERO, 12)).isEqualByComparingTo("1000.00");
    }

    @Test
    void fullRunRepaysInTheTermAndTotalInterestMatchesReference() {
        LoanAmortization.Run run = LoanAmortization.run(new BigDecimal("100000"), new BigDecimal("12"), EMI, BigDecimal.ZERO, 12);
        assertThat(run.monthsToPayoff()).isEqualTo(12);
        // reference total interest for this loan is 6,618.55 (rounding of the final instalment allowed)
        assertThat(run.totalInterest().doubleValue()).isCloseTo(6618.55, within(0.20));
        assertThat(run.payments()).hasSize(12);
        assertThat(run.payments().get(11)).isLessThanOrEqualTo(EMI);
        assertThat(run.totalPaid().subtract(run.totalInterest())).isEqualByComparingTo("100000");
    }

    @Test
    void balanceAfterTheFullTermIsZero() {
        assertThat(LoanAmortization.balanceAfter(new BigDecimal("100000"), new BigDecimal("12"), EMI, 12).doubleValue()).isLessThan(1.0);
        assertThat(LoanAmortization.balanceAfter(new BigDecimal("100000"), new BigDecimal("12"), EMI, 0)).isEqualByComparingTo("100000");
    }

    @Test
    void anEmiThatDoesNotCoverInterestIsRejected() {
        assertThatThrownBy(() -> LoanAmortization.run(new BigDecimal("100000"), new BigDecimal("12"), new BigDecimal("900"), BigDecimal.ZERO, 3))
                .isInstanceOf(ApiException.class).hasMessageContaining("does not cover the monthly interest");
    }

    // ---- scenario effects ---------------------------------------------------------------------------------

    private static List<Transaction> steadyLedger() {
        List<Transaction> tx = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            YearMonth m = YearMonth.of(2026, 1).plusMonths(i);
            tx.add(income(m.atDay(1), "INCOME", "50000", once("salary", m)));
            tx.add(expense(m.atDay(3), "HOUSING", "20000", once("landlord", m)));
            tx.add(expense(m.atDay(15), "GROCERIES", "10000", once("market", m)));
        }
        return tx;
    }

    private ScenarioSimulationResponse run(ScenarioSimulationRequest request, int horizon) {
        var h = history(steadyLedger(), d(2026, 7, 15));
        ForecastingService s = service();
        return simulator.simulate(request, horizon, s.cashFlow(h, horizon), s.categories(h, horizon), h.activeRecurring(), Map.of("L1", LOAN));
    }

    private static ScenarioSimulationRequest req(List<LoanAdjustment> loans, List<OneTimeItem> items) {
        return new ScenarioSimulationRequest("t", null, null, null, null, null, loans, items);
    }

    @Test
    void noLoanAdjustmentReproducesTheBaselineExactly() {
        ScenarioSimulationResponse r = run(req(null, null), 3);
        assertThat(r.baseline()).usingRecursiveComparison().isEqualTo(r.scenario());
        assertThat(r.loanEffects()).isEmpty();
    }

    @Test
    void changingTheEmiToItsCurrentValueIsAnIdentity() {
        ScenarioSimulationResponse r = run(req(List.of(new LoanAdjustment("L1", LoanAction.CHANGE_EMI, EMI)), null), 3);
        assertThat(r.delta().net()).isEqualByComparingTo("0");
        assertThat(r.loanEffects().get(0).interestSaved()).isEqualByComparingTo("0");
    }

    @Test
    void closingTheLoan_paysTheBalanceNowAndRemovesExactlyTheEmiAfterwards() {
        ScenarioSimulationResponse r = run(req(List.of(new LoanAdjustment("L1", LoanAction.CLOSE, null)), null), 3);
        // month 1: pay 100,000 instead of the 8,884.88 EMI; months 2-3: 0 instead of the EMI
        assertThat(r.months().get(0).scenarioExpenses().subtract(r.months().get(0).baselineExpenses())).isEqualByComparingTo("91115.12");
        assertThat(r.months().get(1).scenarioExpenses().subtract(r.months().get(1).baselineExpenses())).isEqualByComparingTo("-8884.88");
        assertThat(r.months().get(2).scenarioExpenses().subtract(r.months().get(2).baselineExpenses())).isEqualByComparingTo("-8884.88");
        var effect = r.loanEffects().get(0);
        assertThat(effect.scenarioMonthsToPayoff()).isZero();
        assertThat(effect.scenarioRemainingInterest()).isEqualByComparingTo("0");
        assertThat(effect.interestSaved()).isEqualByComparingTo(effect.baselineRemainingInterest());
        assertThat(r.delta().expenses()).isEqualByComparingTo(effect.cashOutInHorizonChange());
        assertThat(r.warnings()).anyMatch(w -> w.contains("estimate, not a lender statement"));
    }

    @Test
    void aPrepaymentShortensTheLoanAndSavesInterest() {
        ScenarioSimulationResponse r = run(req(List.of(new LoanAdjustment("L1", LoanAction.PREPAY, new BigDecimal("30000"))), null), 3);
        var effect = r.loanEffects().get(0);
        assertThat(effect.scenarioMonthsToPayoff()).isLessThan(effect.baselineMonthsToPayoff());
        assertThat(effect.interestSaved().signum()).isPositive();
        assertThat(r.months().get(0).scenarioExpenses().subtract(r.months().get(0).baselineExpenses())).isEqualByComparingTo("30000");
        assertThat(r.months().get(1).scenarioExpenses()).isEqualByComparingTo(r.months().get(1).baselineExpenses());
    }

    @Test
    void aLargerPrepaymentSavesMoreInterest_monotonic() {
        var small = run(req(List.of(new LoanAdjustment("L1", LoanAction.PREPAY, new BigDecimal("10000"))), null), 3).loanEffects().get(0);
        var large = run(req(List.of(new LoanAdjustment("L1", LoanAction.PREPAY, new BigDecimal("40000"))), null), 3).loanEffects().get(0);
        assertThat(large.interestSaved()).isGreaterThan(small.interestSaved());
    }

    @Test
    void aLowerEmiLowersMonthlyOutflowButCostsMoreInterest() {
        ScenarioSimulationResponse r = run(req(List.of(new LoanAdjustment("L1", LoanAction.CHANGE_EMI, new BigDecimal("5000"))), null), 3);
        assertThat(r.months().get(0).scenarioExpenses().subtract(r.months().get(0).baselineExpenses())).isEqualByComparingTo("-3884.88");
        assertThat(r.loanEffects().get(0).interestSaved().signum()).isNegative();
    }

    @Test
    void oneTimeItemsLandInTheirMonthOnly() {
        ScenarioSimulationResponse r = run(req(null, List.of(
                new OneTimeItem("Laptop", OneTimeKind.EXPENSE, 2, new BigDecimal("40000")),
                new OneTimeItem("Bonus", OneTimeKind.INCOME, 3, new BigDecimal("25000")))), 3);
        assertThat(r.months().get(0).scenarioNet()).isEqualByComparingTo(r.months().get(0).baselineNet());
        assertThat(r.months().get(1).scenarioNet().subtract(r.months().get(1).baselineNet())).isEqualByComparingTo("-40000");
        assertThat(r.months().get(2).scenarioNet().subtract(r.months().get(2).baselineNet())).isEqualByComparingTo("25000");
        assertThat(r.delta().net()).isEqualByComparingTo("-15000");
    }

    @Test
    void invalidLoanAndItemInputIsRejected() {
        assertThatThrownBy(() -> run(req(List.of(new LoanAdjustment("nope", LoanAction.CLOSE, null)), null), 3))
                .isInstanceOf(ApiException.class).hasMessageContaining("Unknown loan");
        assertThatThrownBy(() -> run(req(List.of(new LoanAdjustment("L1", LoanAction.PREPAY, null)), null), 3))
                .isInstanceOf(ApiException.class).hasMessageContaining("needs an amount");
        assertThatThrownBy(() -> run(req(List.of(new LoanAdjustment("L1", LoanAction.CLOSE, null), new LoanAdjustment("L1", LoanAction.PREPAY, BigDecimal.TEN)), null), 3))
                .isInstanceOf(ApiException.class).hasMessageContaining("more than once");
        assertThatThrownBy(() -> run(req(null, List.of(new OneTimeItem("Late", OneTimeKind.EXPENSE, 5, BigDecimal.TEN))), 3))
                .isInstanceOf(ApiException.class).hasMessageContaining("beyond");
    }

    @Test
    void deterministic() {
        var request = req(List.of(new LoanAdjustment("L1", LoanAction.PREPAY, new BigDecimal("20000"))), null);
        assertThat(run(request, 4)).usingRecursiveComparison().isEqualTo(run(request, 4));
    }
}
