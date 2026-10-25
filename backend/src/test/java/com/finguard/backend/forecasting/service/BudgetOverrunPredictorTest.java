package com.finguard.backend.forecasting.service;

import com.finguard.backend.budgeting.domain.Budget;
import com.finguard.backend.budgeting.domain.BudgetRepository;
import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.forecasting.dto.BudgetOverrunRiskResponse;
import com.finguard.backend.forecasting.dto.BudgetOverrunRiskResponse.Item;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.ledger.transactions.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static com.finguard.backend.forecasting.service.LedgerFixtures.ANALYTICS;
import static com.finguard.backend.forecasting.service.LedgerFixtures.d;
import static com.finguard.backend.forecasting.service.LedgerFixtures.expense;
import static com.finguard.backend.forecasting.service.LedgerFixtures.history;
import static com.finguard.backend.forecasting.service.LedgerFixtures.income;
import static com.finguard.backend.forecasting.service.LedgerFixtures.months;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Budget outlook rules, SYNTHETIC ledgers, expected values computed by hand in the comments.
 * Every test fixes "today" through the asOf argument, so nothing depends on the day the suite runs.
 */
class BudgetOverrunPredictorTest {

    private static final YearMonth JAN = YearMonth.of(2026, 1);
    private static final LocalDate TODAY = d(2026, 7, 10);

    private BudgetRepository budgets;
    private BudgetOverrunPredictor predictor;
    private final User user = new User();

    @BeforeEach
    void setUp() {
        budgets = mock(BudgetRepository.class);
        predictor = new BudgetOverrunPredictor(budgets, ANALYTICS);
    }

    private static Budget budget(String yearMonth, String category, String amount) {
        Budget b = new Budget();
        b.setYearMonth(yearMonth);
        b.setCategory(category);
        b.setAmount(new BigDecimal(amount));
        return b;
    }

    private void budgetsFor(String yearMonth, Budget... list) {
        when(budgets.findByUserIdAndYearMonthOrderByCategoryAsc(any(), eq(yearMonth))).thenReturn(List.of(list));
    }

    private BudgetOverrunRiskResponse evaluate(List<Transaction> tx, LocalDate asOf, YearMonth target) {
        return predictor.evaluate(user, target, history(tx, asOf));
    }

    /** Six complete months of 30,000 spending each (rent 20,000 + groceries 10,000), no recurring by title. */
    private static List<Transaction> sixMonths() {
        return new ArrayList<>(months(JAN, 6, "50000", "20000", "10000"));
    }

    /** July so far: six purchases of 1,000 on days 1..10 (the last on the 10th) = 6,000. */
    private static List<Transaction> julySixThousand(List<Transaction> tx) {
        int[] days = {1, 2, 4, 6, 8, 10};
        for (int day : days) {
            tx.add(expense(d(2026, 7, day), "SHOPPING", "1000", "julybuy" + day));
        }
        return tx;
    }

    // ---- facts versus forecasts ---------------------------------------------------------------------

    @Test
    void spendingAlreadyAboveTheBudgetIsAFactNotAForecast() {
        List<Transaction> tx = sixMonths();
        tx.add(expense(d(2026, 7, 4), "SHOPPING", "12000", "bigbuy"));
        budgetsFor("2026-07", budget("2026-07", null, "10000"));

        Item o = evaluate(tx, TODAY, YearMonth.of(2026, 7)).overall();

        assertThat(o.risk()).isEqualTo("EXCEEDED");
        assertThat(o.actualToDate()).isEqualByComparingTo("12000");
        assertThat(o.estimatedOverrun()).isEqualByComparingTo("2000");
        assertThat(o.expectedCentral()).isNull();
        assertThat(o.rule()).contains("already above the budget");
    }

    @Test
    void tooLittleEvidenceIsIndeterminateNotAGuess() {
        // third of July, two small purchases, and no complete past month at all
        List<Transaction> tx = new ArrayList<>();
        tx.add(expense(d(2026, 7, 1), "SHOPPING", "500", "a"));
        tx.add(expense(d(2026, 7, 3), "SHOPPING", "400", "b"));
        budgetsFor("2026-07", budget("2026-07", null, "20000"));

        Item o = evaluate(tx, d(2026, 7, 3), YearMonth.of(2026, 7)).overall();

        assertThat(o.risk()).isEqualTo("INDETERMINATE");
        assertThat(o.expectedLow()).isNull();
        assertThat(o.expectedCentral()).isNull();
        assertThat(o.expectedHigh()).isNull();
        assertThat(o.estimatedOverrun()).isNull();
        assertThat(o.indeterminateReason()).contains("only 3 day(s)").contains("fewer than 3 complete past months");
        assertThat(o.actualToDate()).isEqualByComparingTo("900"); // the facts are still reported
    }

    // ---- two independent estimates -----------------------------------------------------------------------

    @Test
    void onTrackWhenEveryEstimateIsWellBelowTheBudget() {
        // run-rate: 6,000 over 10 days = 600/day, 21 days left => 6,000 + 12,600 = 18,600
        // history : median of six 30,000 months = 30,000
        // budget 40,000: highest estimate 30,000 <= 90 % (36,000) => ON_TRACK; central (18,600+30,000)/2 = 24,300
        budgetsFor("2026-07", budget("2026-07", null, "40000"));
        Item o = evaluate(julySixThousand(sixMonths()), TODAY, YearMonth.of(2026, 7)).overall();

        assertThat(o.risk()).isEqualTo("ON_TRACK");
        assertThat(o.basis()).containsExactly("RUN_RATE", "HISTORY");
        assertThat(o.expectedLow()).isEqualByComparingTo("18600");
        assertThat(o.expectedHigh()).isEqualByComparingTo("30000");
        assertThat(o.expectedCentral()).isEqualByComparingTo("24300");
        assertThat(o.estimatedOverrun()).isEqualByComparingTo("0");
        assertThat(o.remainingBudget()).isEqualByComparingTo("34000");
    }

    @Test
    void atRiskWhenTheEstimatesStraddleTheBudget() {
        budgetsFor("2026-07", budget("2026-07", null, "28000"));    // estimates 18,600 .. 30,000
        Item o = evaluate(julySixThousand(sixMonths()), TODAY, YearMonth.of(2026, 7)).overall();

        assertThat(o.risk()).isEqualTo("AT_RISK");
        assertThat(o.rule()).contains("span");
        assertThat(o.estimatedOverrun()).isEqualByComparingTo("0");   // central 24,300 is under the budget
    }

    @Test
    void likelyOverOnlyWhenTwoIndependentEstimatesBothExceedTheBudget() {
        budgetsFor("2026-07", budget("2026-07", null, "17000"));    // both 18,600 and 30,000 are above
        Item o = evaluate(julySixThousand(sixMonths()), TODAY, YearMonth.of(2026, 7)).overall();

        assertThat(o.risk()).isEqualTo("LIKELY_OVER");
        assertThat(o.rule()).contains("Both independent estimates");
        assertThat(o.expectedLow()).isEqualByComparingTo("18600");
        assertThat(o.estimatedOverrun()).isEqualByComparingTo("7300");  // 24,300 - 17,000
    }

    @Test
    void aSingleNaiveExtrapolationNeverCallsAnOverrunLikely() {
        // Only two complete past months => no history estimate; the run-rate says 18,600 against a 15,000 budget.
        // One extrapolation of six purchases is not enough evidence: AT_RISK, not LIKELY_OVER.
        List<Transaction> tx = new ArrayList<>(months(YearMonth.of(2026, 4), 2, "50000", "20000", "10000"));
        budgetsFor("2026-07", budget("2026-07", null, "15000"));

        Item o = evaluate(julySixThousand(tx), TODAY, YearMonth.of(2026, 7)).overall();

        assertThat(o.basis()).containsExactly("RUN_RATE");
        assertThat(o.expectedHigh()).isEqualByComparingTo("18600");
        assertThat(o.risk()).isEqualTo("AT_RISK");
        assertThat(o.rule()).contains("Only one estimate");
        assertThat(o.factors()).anyMatch(f -> f.contains("single estimate"));
    }

    // ---- recurring payments -------------------------------------------------------------------------------------

    /** Six months of a constant ₹20,000 "Landlord rent" on the 3rd (a detectable recurring payment) + 10,000 of groceries. */
    private static List<Transaction> sixMonthsWithRecurringRent() {
        List<Transaction> tx = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            YearMonth m = JAN.plusMonths(i);
            tx.add(income(m.atDay(1), "INCOME", "50000", "salary" + m.toString().replace("-", "")));
            tx.add(expense(m.atDay(3), "HOUSING", "20000", "Landlord rent"));
            tx.add(expense(m.atDay(15), "GROCERIES", "10000", "market" + m.toString().replace("-", "")));
        }
        return tx;
    }

    @Test
    void aRecurringPaymentAlreadyMadeIsNotCountedAgainInTheRunRate() {
        // July: rent 20,000 paid on the 3rd, groceries 3,000, five purchases of 500 => 25,500 over 10 days.
        // Non-recurring spend so far = 5,500 => 550/day; 21 days left => 25,500 + 11,550 = 37,050.
        // (Counting the rent in the daily rate would have given 25,500 + 2,550*21 = 79,050.)
        List<Transaction> tx = sixMonthsWithRecurringRent();
        tx.add(expense(d(2026, 7, 3), "HOUSING", "20000", "Landlord rent"));
        tx.add(expense(d(2026, 7, 5), "GROCERIES", "3000", "marketjuly"));
        for (int day = 6; day <= 10; day++) {
            tx.add(expense(d(2026, 7, day), "SHOPPING", "500", "pocket" + day));
        }
        budgetsFor("2026-07", budget("2026-07", null, "50000"));

        Item o = evaluate(tx, TODAY, YearMonth.of(2026, 7)).overall();

        assertThat(o.actualToDate()).isEqualByComparingTo("25500");
        assertThat(o.recurringStillDue()).isEqualByComparingTo("0");
        assertThat(o.expectedHigh()).isEqualByComparingTo("37050");
        assertThat(o.factors()).anyMatch(f -> f.contains("₹20000.00 of the spending so far is recurring"));
        assertThat(o.risk()).isEqualTo("ON_TRACK");
    }

    @Test
    void aRecurringPaymentNotYetMadeIsAddedOnceAndNamed() {
        // Same July but the rent has not been paid yet: actual 5,500, rent 20,000 still due => floor 25,500.
        // Run-rate: 5,500/10 = 550/day => 25,500 + 11,550 = 37,050. History: max(median 30,000, floor) = 30,000.
        List<Transaction> tx = sixMonthsWithRecurringRent();
        tx.add(expense(d(2026, 7, 5), "GROCERIES", "3000", "marketjuly"));
        for (int day = 6; day <= 10; day++) {
            tx.add(expense(d(2026, 7, day), "SHOPPING", "500", "pocket" + day));
        }
        budgetsFor("2026-07", budget("2026-07", null, "50000"));

        Item o = evaluate(tx, TODAY, YearMonth.of(2026, 7)).overall();

        assertThat(o.actualToDate()).isEqualByComparingTo("5500");
        assertThat(o.recurringStillDue()).isEqualByComparingTo("20000");
        assertThat(o.expectedHigh()).isEqualByComparingTo("37050");
        assertThat(o.expectedLow()).isEqualByComparingTo("30000");
        assertThat(o.factors()).anyMatch(f -> f.contains("Landlord rent") && f.contains("20000"));
    }

    @Test
    void recurringPaymentsStillDueCanMakeABudgetLikelyOverEvenWithoutAnyRunRate() {
        // Only 3 days into the month, no history needed: 5,500 spent + 20,000 rent still due = 25,500 > 22,000
        List<Transaction> tx = sixMonthsWithRecurringRent();
        tx.add(expense(d(2026, 7, 2), "SHOPPING", "5500", "early"));
        budgetsFor("2026-07", budget("2026-07", null, "22000"));

        Item o = evaluate(tx, d(2026, 7, 3), YearMonth.of(2026, 7)).overall();

        assertThat(o.risk()).isEqualTo("LIKELY_OVER");
        assertThat(o.rule()).contains("recurring payments still due");
    }

    @Test
    void categoryBudgetsLookOnlyAtTheirOwnCategoryAndItsRecurringPayments() {
        List<Transaction> tx = sixMonthsWithRecurringRent();
        tx.add(expense(d(2026, 7, 3), "HOUSING", "20000", "Landlord rent"));
        tx.add(expense(d(2026, 7, 5), "GROCERIES", "3000", "marketjuly"));
        tx.add(expense(d(2026, 7, 6), "SHOPPING", "900", "pocket"));
        budgetsFor("2026-07", budget("2026-07", "HOUSING", "25000"), budget("2026-07", "GROCERIES", "9000"));

        BudgetOverrunRiskResponse r = evaluate(tx, TODAY, YearMonth.of(2026, 7));

        Item housing = r.categories().stream().filter(i -> "HOUSING".equals(i.category())).findFirst().orElseThrow();
        Item groceries = r.categories().stream().filter(i -> "GROCERIES".equals(i.category())).findFirst().orElseThrow();
        assertThat(housing.actualToDate()).isEqualByComparingTo("20000");
        assertThat(housing.recurringStillDue()).isEqualByComparingTo("0");
        assertThat(housing.basis()).containsExactly("HISTORY");           // one housing transaction: no run-rate
        assertThat(housing.expectedHigh()).isEqualByComparingTo("20000");
        assertThat(housing.risk()).isEqualTo("ON_TRACK");                 // 20,000 <= 90 % of 25,000
        assertThat(groceries.actualToDate()).isEqualByComparingTo("3000");
    }

    // ---- calendar ------------------------------------------------------------------------------------------------------

    @Test
    void aFinishedMonthIsSimplyWithinOrOverItsBudget() {
        budgetsFor("2026-06", budget("2026-06", null, "25000"), budget("2026-06", "GROCERIES", "12000"));

        BudgetOverrunRiskResponse r = evaluate(sixMonths(), TODAY, YearMonth.of(2026, 6));

        assertThat(r.period()).isEqualTo("PAST");
        assertThat(r.daysRemaining()).isZero();
        assertThat(r.overall().risk()).isEqualTo("COMPLETED_OVER");
        assertThat(r.overall().estimatedOverrun()).isEqualByComparingTo("5000");
        assertThat(r.categories().get(0).risk()).isEqualTo("COMPLETED_WITHIN");
        assertThat(r.overall().expectedCentral()).isNull();
    }

    @Test
    void aFutureMonthRestsOnHistoryAloneAndSaysSo() {
        // September: nothing recorded; history median 30,000 against a 25,000 budget, one estimate only
        budgetsFor("2026-09", budget("2026-09", null, "25000"));

        BudgetOverrunRiskResponse r = evaluate(sixMonths(), TODAY, YearMonth.of(2026, 9));

        assertThat(r.period()).isEqualTo("FUTURE");
        assertThat(r.warnings()).anyMatch(w -> w.contains("has not started"));
        assertThat(r.overall().basis()).containsExactly("HISTORY");
        assertThat(r.overall().risk()).isEqualTo("AT_RISK");
        assertThat(r.overall().actualToDate()).isEqualByComparingTo("0");
    }

    @Test
    void staleDataIsReportedAndTheRunRateUsesOnlyTheRecordedDays() {
        // last transaction on 9 July but today is the 20th
        List<Transaction> tx = sixMonths();
        for (int day : new int[]{1, 2, 4, 6, 8, 9}) {
            tx.add(expense(d(2026, 7, day), "SHOPPING", "1000", "juybuy" + day));
        }
        budgetsFor("2026-07", budget("2026-07", null, "100000"));

        BudgetOverrunRiskResponse r = evaluate(tx, d(2026, 7, 20), YearMonth.of(2026, 7));

        assertThat(r.daysWithRecordedData()).isEqualTo(9);
        assertThat(r.daysRemaining()).isEqualTo(22);
        assertThat(r.warnings()).anyMatch(w -> w.contains("2026-07-09") && w.contains("11 days ago"));
        // run-rate = 6,000 / 9 days over 22 remaining days: 6,000 + 666.666667 * 22 = 20,666.67
        assertThat(r.overall().expectedLow()).isEqualByComparingTo("20666.67");
    }

    @Test
    void monthsOutsideTheSupportedWindowAreRejected() {
        assertThatThrownBy(() -> evaluate(sixMonths(), TODAY, YearMonth.of(2020, 1)))
                .isInstanceOf(ApiException.class).hasMessageContaining("yearMonth must be between");
        assertThatThrownBy(() -> evaluate(sixMonths(), TODAY, YearMonth.of(2028, 1)))
                .isInstanceOf(ApiException.class);
    }

    // ---- definitions -------------------------------------------------------------------------------------------------------

    @Test
    void actualSpendingFollowsTheBudgetDefinition_refundsOffsetAndNonSpendingIsExcluded() {
        List<Transaction> tx = sixMonths();
        tx.add(expense(d(2026, 7, 2), "SHOPPING", "12000", "buy"));
        tx.add(income(d(2026, 7, 3), "REFUNDS", "4000", "refundbuy"));
        tx.add(expense(d(2026, 7, 4), "TRANSFERS", "9000", "toself"));
        tx.add(expense(d(2026, 7, 5), "CARD_PAYMENTS", "15000", "cardbill"));
        tx.add(expense(d(2026, 7, 6), "INVESTMENTS", "5000", "fund"));
        budgetsFor("2026-07", budget("2026-07", null, "50000"));

        Item o = evaluate(tx, TODAY, YearMonth.of(2026, 7)).overall();

        assertThat(o.actualToDate()).isEqualByComparingTo("8000");   // 12,000 - 4,000
    }

    @Test
    void noBudgetsMeansNoItemsAndAWarning() {
        budgetsFor("2026-07");
        BudgetOverrunRiskResponse r = evaluate(sixMonths(), TODAY, YearMonth.of(2026, 7));

        assertThat(r.overall()).isNull();
        assertThat(r.categories()).isEmpty();
        assertThat(r.warnings()).anyMatch(w -> w.contains("No budgets are set"));
    }

    @Test
    void theSameInputGivesTheSameOutlook() {
        budgetsFor("2026-07", budget("2026-07", null, "28000"));
        List<Transaction> tx = julySixThousand(sixMonths());
        assertThat(evaluate(tx, TODAY, YearMonth.of(2026, 7))).usingRecursiveComparison()
                .ignoringFields("overall.budgetId")
                .isEqualTo(evaluate(tx, TODAY, YearMonth.of(2026, 7)));
    }
}
