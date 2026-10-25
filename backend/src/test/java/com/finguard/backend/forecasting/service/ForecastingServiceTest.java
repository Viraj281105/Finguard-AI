package com.finguard.backend.forecasting.service;

import com.finguard.backend.forecasting.dto.CashFlowForecastResponse;
import com.finguard.backend.forecasting.dto.CashFlowForecastResponse.ProjectedMonth;
import com.finguard.backend.forecasting.dto.CategorySpendingForecastResponse;
import com.finguard.backend.forecasting.dto.CategorySpendingForecastResponse.CategoryForecast;
import com.finguard.backend.forecasting.dto.ForecastCommon.Estimate;
import com.finguard.backend.forecasting.service.ForecastMethod.Obs;
import com.finguard.backend.intelligence.dto.MonthlyFlowDto;
import com.finguard.backend.ledger.transactions.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static com.finguard.backend.forecasting.service.LedgerFixtures.ANALYTICS;
import static com.finguard.backend.forecasting.service.LedgerFixtures.d;
import static com.finguard.backend.forecasting.service.LedgerFixtures.expense;
import static com.finguard.backend.forecasting.service.LedgerFixtures.history;
import static com.finguard.backend.forecasting.service.LedgerFixtures.income;
import static com.finguard.backend.forecasting.service.LedgerFixtures.month;
import static com.finguard.backend.forecasting.service.LedgerFixtures.months;
import static com.finguard.backend.forecasting.service.LedgerFixtures.once;
import static com.finguard.backend.forecasting.service.LedgerFixtures.service;
import static com.finguard.backend.forecasting.service.LedgerFixtures.tx;
import static org.assertj.core.api.Assertions.assertThat;

/** Forecast semantics against hand-computed, SYNTHETIC ledgers. */
class ForecastingServiceTest {

    private static final YearMonth JAN = YearMonth.of(2026, 1);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static CashFlowForecastResponse cashFlow(List<Transaction> tx, java.time.LocalDate asOf, int horizon) {
        return service().cashFlow(history(tx, asOf), horizon);
    }

    // ---- golden example ---------------------------------------------------------------------

    @Test
    void aStableLedgerProjectsItsOwnLevel_byHand() {
        // Jan..Jun: income 50,000, rent 20,000, groceries 10,000 every month. Asked on 15 July.
        // Level = 50,000 income / 30,000 spending / 20,000 net; horizon 3 => totals 150,000 / 90,000 / 60,000, rate 40 %.
        CashFlowForecastResponse r = cashFlow(months(JAN, 6, "50000", "20000", "10000"), d(2026, 7, 15), 3);

        assertThat(r.reliability()).isEqualTo("RELIABLE");
        assertThat(r.method()).isEqualTo("LEVEL_DAMPED_TREND");
        assertThat(r.algorithmVersion()).isEqualTo("2.0.0");
        assertThat(r.history().monthsUsed()).isEqualTo(6);
        assertThat(r.history().from()).isEqualTo("2026-01");
        assertThat(r.history().to()).isEqualTo("2026-06");
        assertThat(r.projected()).extracting(ProjectedMonth::month).containsExactly("2026-08", "2026-09", "2026-10");
        for (ProjectedMonth p : r.projected()) {
            assertThat(p.income().point()).isEqualByComparingTo("50000");
            assertThat(p.expenses().point()).isEqualByComparingTo("30000");
            assertThat(p.net().point()).isEqualByComparingTo("20000");
        }
        assertThat(r.summary().totalIncome()).isEqualByComparingTo("150000");
        assertThat(r.summary().totalExpenses()).isEqualByComparingTo("90000");
        assertThat(r.summary().totalNet()).isEqualByComparingTo("60000");
        assertThat(r.summary().averageMonthlyNet()).isEqualByComparingTo("20000");
        assertThat(r.summary().savingsRatePercent()).isEqualByComparingTo("40.00");
    }

    @Test
    void observedAndProjectedValuesAreKeptApart() {
        CashFlowForecastResponse r = cashFlow(months(JAN, 6, "50000", "20000", "10000"), d(2026, 7, 15), 2);

        assertThat(r.historical()).hasSize(6);
        assertThat(r.historical()).allSatisfy(h -> assertThat(h.month()).isLessThan("2026-07"));
        assertThat(r.projected()).allSatisfy(p -> assertThat(p.month()).isGreaterThan("2026-07"));
    }

    // ---- the month in progress ----------------------------------------------------------------

    @Test
    void theMonthInProgressIsShownButNeverUsedToTrain() {
        List<Transaction> tx = months(JAN, 6, "50000", "20000", "10000");
        // July so far (asked on the 15th): only a salary and a small purchase
        tx.add(income(d(2026, 7, 1), "INCOME", "50000", once("salary", YearMonth.of(2026, 7))));
        tx.add(expense(d(2026, 7, 5), "SHOPPING", "5000", "julyshop"));

        CashFlowForecastResponse r = cashFlow(tx, d(2026, 7, 15), 1);

        assertThat(r.currentMonth().month()).isEqualTo("2026-07");
        assertThat(r.currentMonth().expenses()).isEqualByComparingTo("5000");
        assertThat(r.currentMonth().note()).contains("Not used to train");
        assertThat(r.history().monthsUsed()).isEqualTo(6);
        // 5,000 of a partial month would have dragged a naive mean down to ~27,143; the level stays 30,000
        assertThat(r.projected().get(0).expenses().point()).isEqualByComparingTo("30000");
    }

    // ---- insufficient and incomplete history ----------------------------------------------------

    @Test
    void anEmptyLedgerIsUnavailableNotAForecastOfZero() {
        CashFlowForecastResponse r = cashFlow(List.of(), d(2026, 7, 15), 3);

        assertThat(r.reliability()).isEqualTo("UNAVAILABLE");
        assertThat(r.projected()).isEmpty();
        assertThat(r.summary()).isNull();
        assertThat(r.historical()).isEmpty();
        assertThat(r.warnings()).anyMatch(w -> w.contains("No complete month"));
    }

    @Test
    void twoMonthsIsNotEnough() {
        CashFlowForecastResponse r = cashFlow(months(YearMonth.of(2026, 5), 2, "50000", "20000", "10000"), d(2026, 7, 15), 3);

        assertThat(r.reliability()).isEqualTo("UNAVAILABLE");
        assertThat(r.projected()).isEmpty();
        assertThat(r.summary()).isNull();
        assertThat(r.historical()).hasSize(2); // the facts are still shown
        assertThat(r.warnings().get(0)).contains("At least 3 complete months").contains("only 2");
    }

    @Test
    void exactlyThreeMonthsGivesALimitedForecast() {
        CashFlowForecastResponse r = cashFlow(months(YearMonth.of(2026, 4), 3, "50000", "20000", "10000"), d(2026, 7, 15), 2);

        assertThat(r.reliability()).isEqualTo("LIMITED");
        assertThat(r.projected()).hasSize(2);
        assertThat(r.assumptions()).anyMatch(a -> a.contains("Fewer than 6 months: no trend"));
    }

    @Test
    void aMissingMonthIsUnknownNotZeroAndIsReported() {
        List<Transaction> tx = new ArrayList<>();
        for (int m : new int[]{1, 2, 4, 5, 6}) {   // March missing
            tx.addAll(month(YearMonth.of(2026, m), "50000", "20000", "10000"));
        }
        CashFlowForecastResponse r = cashFlow(tx, d(2026, 7, 10), 1);

        assertThat(r.history().missingMonths()).containsExactly("2026-03");
        assertThat(r.warnings()).anyMatch(w -> w.contains("2026-03") && w.contains("not as zero"));
        assertThat(r.reliability()).isEqualTo("LIMITED");
        // treating March as zero would have pulled the level to 25,000
        assertThat(r.projected().get(0).expenses().point()).isEqualByComparingTo("30000");
    }

    @Test
    void aStaleImportIsFlaggedAndLimitsReliability() {
        // data only for Jan-Mar, asked in July: April, May and June are unknown
        CashFlowForecastResponse r = cashFlow(months(JAN, 3, "50000", "20000", "10000"), d(2026, 7, 10), 1);

        assertThat(r.history().missingMonths()).containsExactly("2026-04", "2026-05", "2026-06");
        assertThat(r.warnings()).anyMatch(w -> w.contains("latest complete month with data is 2026-03"));
        assertThat(r.reliability()).isEqualTo("LIMITED");
    }

    @Test
    void aMonthWithOnlyTransfersAndInvestmentsIsExcludedNotCountedAsZeroSpending() {
        List<Transaction> tx = new ArrayList<>(months(JAN, 6, "50000", "20000", "10000"));
        // replace March by a month that only moved money around
        tx.removeIf(t -> t.getDate().getMonthValue() == 3);
        tx.add(tx(d(2026, 3, 5), com.finguard.backend.ledger.transactions.Transaction.TransactionType.EXPENSE, "TRANSFERS", "10000", "moved"));
        tx.add(tx(d(2026, 3, 9), com.finguard.backend.ledger.transactions.Transaction.TransactionType.EXPENSE, "INVESTMENTS", "5000", "sipfund"));

        CashFlowForecastResponse r = cashFlow(tx, d(2026, 7, 15), 1);

        assertThat(r.history().monthsUsed()).isEqualTo(5);
        assertThat(r.history().missingMonths()).isEmpty();
        assertThat(r.warnings()).anyMatch(w -> w.contains("2026-03") && w.contains("only transfers"));
        assertThat(r.projected().get(0).expenses().point()).isEqualByComparingTo("30000");
    }

    @Test
    void aFirstMonthThatStartsLateIsFlaggedAsPossiblyPartial() {
        List<Transaction> tx = new ArrayList<>(months(YearMonth.of(2026, 2), 5, "50000", "20000", "10000"));
        tx.add(expense(d(2026, 1, 22), "GROCERIES", "3000", "lateStart"));
        tx.add(income(d(2026, 1, 25), "INCOME", "50000", "salaryjan"));

        CashFlowForecastResponse r = cashFlow(tx, d(2026, 7, 15), 1);

        assertThat(r.history().firstMonthPossiblyPartial()).isTrue();
        assertThat(r.warnings()).anyMatch(w -> w.contains("may be a partial month"));
    }

    // ---- income -----------------------------------------------------------------------------------

    @Test
    void withoutObservedIncomeTheForecastDoesNotInventAZero() {
        CashFlowForecastResponse r = cashFlow(months(JAN, 6, null, "20000", "10000"), d(2026, 7, 15), 3);

        assertThat(r.incomeAvailable()).isFalse();
        assertThat(r.reliability()).isEqualTo("LIMITED");
        assertThat(r.projected()).allSatisfy(p -> {
            assertThat(p.income()).isNull();
            assertThat(p.net()).isNull();
            assertThat(p.expenses().point()).isEqualByComparingTo("30000");
        });
        assertThat(r.summary().totalIncome()).isNull();
        assertThat(r.summary().totalNet()).isNull();
        assertThat(r.summary().savingsRatePercent()).isNull();
        assertThat(r.summary().totalExpenses()).isEqualByComparingTo("90000");
        assertThat(r.warnings()).anyMatch(w -> w.contains("Income was recorded in 0 of 6"));
    }

    @Test
    void incomeInOnlyTwoOfSixMonthsIsStillNotEnough() {
        List<Transaction> tx = new ArrayList<>(months(JAN, 6, null, "20000", "10000"));
        tx.add(income(d(2026, 2, 1), "INCOME", "50000", "bonusfeb"));
        tx.add(income(d(2026, 5, 1), "INCOME", "50000", "bonusmay"));

        CashFlowForecastResponse r = cashFlow(tx, d(2026, 7, 15), 1);

        assertThat(r.incomeAvailable()).isFalse();
        assertThat(r.projected().get(0).income()).isNull();
    }

    @Test
    void irregularIncomeIsLimitedAndSaysSo() {
        List<Transaction> tx = new ArrayList<>();
        String[] salaries = {"10000", "90000", "10000", "90000", "10000", "90000"};
        for (int i = 0; i < 6; i++) {
            tx.addAll(month(JAN.plusMonths(i), salaries[i], "20000", "10000"));
        }
        CashFlowForecastResponse r = cashFlow(tx, d(2026, 7, 15), 1);

        assertThat(r.incomeAvailable()).isTrue();
        assertThat(r.reliability()).isEqualTo("LIMITED");
        assertThat(r.warnings()).anyMatch(w -> w.contains("Income is irregular"));
    }

    @Test
    void aMonthWithIncomeButNoSpendingIsRealDataAndProjectsZeroSpendingBecauseZeroWasObserved() {
        List<Transaction> tx = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tx.addAll(month(JAN.plusMonths(i), "50000", null, null));
        }
        CashFlowForecastResponse r = cashFlow(tx, d(2026, 7, 15), 1);

        assertThat(r.history().monthsUsed()).isEqualTo(6);
        assertThat(r.projected().get(0).expenses().point()).isEqualByComparingTo("0");
        assertThat(r.projected().get(0).net().point()).isEqualByComparingTo("50000");
    }

    // ---- consistent financial definitions ------------------------------------------------------------

    @Test
    void transfersCardPaymentsAndInvestmentsAreNeitherIncomeNorSpending() {
        List<Transaction> tx = months(JAN, 6, "50000", "20000", "10000");
        for (int i = 0; i < 6; i++) {
            YearMonth m = JAN.plusMonths(i);
            tx.add(expense(m.atDay(8), "TRANSFERS", "10000", once("self", m)));
            tx.add(expense(m.atDay(9), "CARD_PAYMENTS", "15000", once("cardbill", m)));
            tx.add(expense(m.atDay(10), "INVESTMENTS", "5000", once("mutualfund", m)));
            tx.add(income(m.atDay(11), "INVESTMENTS", "2000", once("redeem", m)));
            tx.add(income(m.atDay(12), "TRANSFERS", "9000", once("backin", m)));
        }
        CashFlowForecastResponse r = cashFlow(tx, d(2026, 7, 15), 1);

        assertThat(r.projected().get(0).expenses().point()).isEqualByComparingTo("30000");
        assertThat(r.projected().get(0).income().point()).isEqualByComparingTo("50000");
    }

    @Test
    void refundsOffsetSpendingAndUncategorisedCreditsCountAsIncome_likeTheAnalyticsModule() {
        List<Transaction> tx = months(JAN, 6, "50000", "20000", "10000");
        for (int i = 0; i < 6; i++) {
            YearMonth m = JAN.plusMonths(i);
            tx.add(income(m.atDay(20), "REFUNDS", "2000", once("refund", m)));       // offsets spending
            tx.add(income(m.atDay(21), null, "1000", once("misc", m)));              // uncategorised credit => income
            tx.add(expense(m.atDay(22), null, "500", once("unknown", m)));            // uncategorised debit => spending
        }
        CashFlowForecastResponse r = cashFlow(tx, d(2026, 7, 15), 1);

        // spending 30,000 + 500 - 2,000 = 28,500 ; income 50,000 + 1,000 = 51,000
        assertThat(r.projected().get(0).expenses().point()).isEqualByComparingTo("28500");
        assertThat(r.projected().get(0).income().point()).isEqualByComparingTo("51000");
    }

    @Test
    void historyMatchesTheAnalyticsMonthlyFlowsExactly() {
        List<Transaction> tx = months(JAN, 6, "50000", "20000", "10000");
        tx.add(income(d(2026, 3, 20), "REFUNDS", "1234.56", "refundone"));
        tx.add(expense(d(2026, 4, 9), "TRANSFERS", "777", "xfer"));
        tx.add(expense(d(2026, 5, 9), null, "321.10", "unknownbuy"));

        CashFlowForecastResponse r = cashFlow(tx, d(2026, 7, 15), 1);
        List<MonthlyFlowDto> analytics = ANALYTICS.monthlyFlowsOf(tx);

        assertThat(r.historical()).hasSameSizeAs(analytics);
        for (int i = 0; i < analytics.size(); i++) {
            assertThat(r.historical().get(i).month()).isEqualTo(analytics.get(i).month());
            assertThat(r.historical().get(i).income()).isEqualByComparingTo(analytics.get(i).income());
            assertThat(r.historical().get(i).expenses()).isEqualByComparingTo(analytics.get(i).expenses());
            assertThat(r.historical().get(i).net()).isEqualByComparingTo(analytics.get(i).netCashFlow());
        }
    }

    // ---- outliers, trend, recurring, bands ---------------------------------------------------------------

    @Test
    void anUnusuallyLargeMonthIsLimitedAndReported() {
        List<Transaction> tx = new ArrayList<>();
        String[] spend = {"30000", "30000", "30000", "30000", "30000", "300000"};
        for (int i = 0; i < 6; i++) {
            YearMonth m = JAN.plusMonths(i);
            tx.add(income(m.atDay(1), "INCOME", "400000", once("salary", m)));
            tx.add(expense(m.atDay(10), "SHOPPING", spend[i], once("buy", m)));
        }
        CashFlowForecastResponse r = cashFlow(tx, d(2026, 7, 15), 1);

        // median 30,000, scale floor 3,000 => the 300,000 month counts as 39,000: level (5*30,000 + 39,000)/6 = 31,500
        assertThat(r.projected().get(0).expenses().point()).isEqualByComparingTo("31500");
        assertThat(r.warnings()).anyMatch(w -> w.contains("2026-06") && w.contains("unusually"));
    }

    @Test
    void aSteadyRiseOverSixMonthsIsFollowedWithADampedTrend_andSaysSo() {
        List<Transaction> tx = new ArrayList<>();
        for (int i = 0; i < 6; i++) {   // spending 20,000 + 2,000 per month, exactly
            YearMonth m = JAN.plusMonths(i);
            tx.add(income(m.atDay(1), "INCOME", "100000", once("salary", m)));
            tx.add(expense(m.atDay(10), "SHOPPING", String.valueOf(20000 + 2000 * i), once("buy", m)));
        }
        CashFlowForecastResponse r = cashFlow(tx, d(2026, 7, 15), 2);

        // last observed (June) is 30,000 and the slope 2,000/month. The first projected month (August) is two
        // steps after June: 30,000 + 2,000 * (0.8 + 0.64) = 32,880 ; September adds 0.512 => 33,904
        assertThat(r.projected().get(0).expenses().point()).isEqualByComparingTo("32880");
        assertThat(r.projected().get(1).expenses().point()).isEqualByComparingTo("33904");
        assertThat(r.assumptions()).anyMatch(a -> a.contains("statistically tested spending trend"));
    }

    @Test
    void recurringPaymentsInsideTheHistoryAreNotAddedASecondTime() {
        List<Transaction> tx = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            YearMonth m = JAN.plusMonths(i);
            tx.add(income(m.atDay(1), "INCOME", "50000", once("salary", m)));
            tx.add(expense(m.atDay(5), "SUBSCRIPTIONS", "500", "Netflix subscription"));    // recurring, ₹500
            tx.add(expense(m.atDay(12), "GROCERIES", "29500", once("market", m)));          // the rest of the 30,000
        }
        CashFlowForecastResponse r = cashFlow(tx, d(2026, 7, 15), 1);

        assertThat(r.recurring()).extracting(c -> c.title()).anyMatch(t -> t.toLowerCase().contains("netflix"));
        assertThat(r.recurring()).allSatisfy(c -> assertThat(c.includedInBaseline()).isTrue());
        assertThat(r.projected().get(0).expenses().point()).isEqualByComparingTo("30000");   // not 30,500
        assertThat(r.projected().get(0).recurringFloorApplied()).isFalse();
    }

    @Test
    void theRecurringFloorOnlyLiftsAProjectionThatWouldFallBelowIt() {
        List<Obs> flat = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            flat.add(new Obs(i, 1000));
        }
        ForecastingService.SeriesForecast f = service().forecastSeries(flat, 5, 2, true, bd("1500"));

        assertThat(f.estimates().get(0).point()).isEqualByComparingTo("1500");
        assertThat(f.floorApplied()).containsExactly(true, true);
        assertThat(f.factors().get(0)).anyMatch(s -> s.contains("detected active recurring"));

        ForecastingService.SeriesForecast none = service().forecastSeries(flat, 5, 1, true, bd("900"));
        assertThat(none.estimates().get(0).point()).isEqualByComparingTo("1000");
        assertThat(none.floorApplied()).containsExactly(false);
    }

    @Test
    void shortHistoryHasNoUncertaintyBandAndExplainsWhy() {
        CashFlowForecastResponse r = cashFlow(months(JAN, 6, "50000", "20000", "10000"), d(2026, 7, 15), 1);
        Estimate e = r.projected().get(0).expenses();

        assertThat(e.lower()).isNull();
        assertThat(e.upper()).isNull();
        assertThat(r.projected().get(0).factors()).anyMatch(s -> s.contains("No uncertainty band"));
        assertThat(r.intervalNote()).contains("10th to 90th percentile").contains("not a calibrated probability");
    }

    @Test
    void withEnoughHistoryTheBandIsBuiltFromPastErrorsAndContainsTheProjection() {
        // 16 months of alternating spending 28,000 / 32,000 (groceries 8,000 / 12,000)
        List<Transaction> tx = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            tx.addAll(month(JAN.plusMonths(i), "50000", "20000", i % 2 == 0 ? "8000" : "12000"));
        }
        CashFlowForecastResponse r = cashFlow(tx, d(2027, 6, 15), 1);   // 2026-01 .. 2027-04 complete; 2027-05 missing

        Estimate e = r.projected().get(0).expenses();
        assertThat(e.lower()).isNotNull();
        assertThat(e.lower()).isLessThanOrEqualTo(e.point());
        assertThat(e.upper()).isGreaterThanOrEqualTo(e.point());
        assertThat(e.lower().signum()).isGreaterThanOrEqualTo(0);
        // income never varies, so its past errors are zero => a zero-width band is the honest answer
        Estimate inc = r.projected().get(0).income();
        assertThat(inc.lower()).isEqualByComparingTo(inc.point());
        assertThat(inc.upper()).isEqualByComparingTo(inc.point());
    }

    // ---- calendar edges ----------------------------------------------------------------------------------

    @Test
    void aLeapDayBelongsToFebruaryAndBoundariesFollowTheCalendar() {
        List<Transaction> tx = new ArrayList<>();
        tx.addAll(months(YearMonth.of(2024, 1), 6, "50000", "20000", "10000"));
        tx.add(expense(d(2024, 2, 29), "SHOPPING", "1000", "leapday"));     // last day of a leap February
        tx.add(expense(d(2024, 1, 31), "SHOPPING", "2000", "janend"));      // last day of January
        tx.add(expense(d(2024, 3, 1), "SHOPPING", "4000", "marstart"));     // first day of March

        CashFlowForecastResponse r = cashFlow(tx, d(2024, 8, 5), 2);

        assertThat(r.historical().get(0).expenses()).isEqualByComparingTo("32000");   // Jan 30,000 + 2,000
        assertThat(r.historical().get(1).expenses()).isEqualByComparingTo("31000");   // Feb 30,000 + 1,000 (Feb 29)
        assertThat(r.historical().get(2).expenses()).isEqualByComparingTo("34000");   // Mar 30,000 + 4,000
        assertThat(r.projected()).extracting(ProjectedMonth::month).containsExactly("2024-09", "2024-10");
    }

    @Test
    void projectedMonthsCrossTheYearBoundary() {
        CashFlowForecastResponse r = cashFlow(months(YearMonth.of(2026, 5), 6, "50000", "20000", "10000"), d(2026, 11, 20), 3);
        assertThat(r.projected()).extracting(ProjectedMonth::month).containsExactly("2026-12", "2027-01", "2027-02");
    }

    @Test
    void theSameLedgerAlwaysGivesTheSameForecast() {
        List<Transaction> tx = months(JAN, 6, "50000", "20000", "10000");
        assertThat(cashFlow(tx, d(2026, 7, 15), 3)).usingRecursiveComparison().isEqualTo(cashFlow(tx, d(2026, 7, 15), 3));
    }

    // ---- categories --------------------------------------------------------------------------------------------

    private static CategorySpendingForecastResponse categories(List<Transaction> tx, java.time.LocalDate asOf, int horizon) {
        return service().categories(history(tx, asOf), horizon);
    }

    @Test
    void aCategoryWithTooLittleSpendingGetsNoForecastAndIsListedWithTheReason() {
        List<Transaction> tx = months(JAN, 6, "50000", "20000", "10000");
        tx.add(expense(d(2026, 3, 9), "ENTERTAINMENT", "800", "onemovie"));          // a single transaction
        tx.add(expense(d(2026, 4, 9), "HEALTHCARE", "400", "clinicone"));
        tx.add(expense(d(2026, 5, 9), "HEALTHCARE", "450", "clinictwo"));            // two months only

        CategorySpendingForecastResponse r = categories(tx, d(2026, 7, 15), 3);

        assertThat(r.categories()).extracting(CategoryForecast::category).containsExactlyInAnyOrder("HOUSING", "GROCERIES");
        assertThat(r.withoutForecast()).extracting(w -> w.category()).containsExactlyInAnyOrder("ENTERTAINMENT", "HEALTHCARE");
        assertThat(r.withoutForecast().stream().filter(w -> w.category().equals("ENTERTAINMENT")).findFirst().orElseThrow().reason())
                .contains("only 1 of 6").contains("at least 3");
    }

    @Test
    void aTestedUpwardTrendIsFlaggedAsRisingAndOthersAreStable() {
        List<Transaction> tx = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            YearMonth m = JAN.plusMonths(i);
            tx.add(income(m.atDay(1), "INCOME", "100000", once("salary", m)));
            tx.add(expense(m.atDay(10), "DINING", String.valueOf(4000 + 400 * i), once("eat", m)));       // +400 a month, exactly
            tx.add(expense(m.atDay(12), "HOUSING", "20000", once("landlord", m)));                         // flat
        }
        CategorySpendingForecastResponse r = categories(tx, d(2026, 7, 15), 2);

        CategoryForecast dining = r.categories().stream().filter(c -> c.category().equals("DINING")).findFirst().orElseThrow();
        CategoryForecast housing = r.categories().stream().filter(c -> c.category().equals("HOUSING")).findFirst().orElseThrow();
        assertThat(dining.trend()).isEqualTo("UPWARD");
        // recent average (Jan..Jun) = 5,000 ; slope 400 => 8.0 % per month
        assertThat(dining.trendPercentPerMonth()).isEqualByComparingTo("8.0");
        assertThat(housing.trend()).isEqualTo("STABLE");
        assertThat(housing.trendPercentPerMonth()).isNull();
        assertThat(r.risingCategories()).containsExactly("DINING");
        // list is ordered by projected total, largest first
        assertThat(r.categories().get(0).category()).isEqualTo("HOUSING");
        assertThat(dining.monthly()).hasSize(2);
        // Aug = last (June 6,000) + 400*(0.8+0.64) = 6,576
        assertThat(dining.monthly().get(0).point()).isEqualByComparingTo("6576");
    }

    @Test
    void intermittentCategoriesAreLimitedAndSaySo() {
        List<Transaction> tx = months(JAN, 6, "50000", "20000", "10000");
        for (int m : new int[]{1, 3, 5}) {   // travel in three of six months
            tx.add(expense(d(2026, m, 20), "TRANSPORT", "3000", "trip" + m));
        }
        CategorySpendingForecastResponse r = categories(tx, d(2026, 7, 15), 1);

        CategoryForecast transport = r.categories().stream().filter(c -> c.category().equals("TRANSPORT")).findFirst().orElseThrow();
        assertThat(transport.reliability()).isEqualTo("LIMITED");
        assertThat(transport.activeMonths()).isEqualTo(3);
        assertThat(transport.explanation()).contains("intermittent");
    }

    @Test
    void categoriesAreUnavailableWithoutEnoughHistory() {
        CategorySpendingForecastResponse r = categories(months(YearMonth.of(2026, 5), 2, "50000", "20000", "10000"), d(2026, 7, 15), 3);
        assertThat(r.reliability()).isEqualTo("UNAVAILABLE");
        assertThat(r.categories()).isEmpty();
    }

    @Test
    void categorySeriesAreFlooredAtZeroAndRefundsInThatCategoryNetOffItsSpending() {
        List<Transaction> tx = months(JAN, 6, "50000", "20000", "10000");
        for (int i = 0; i < 6; i++) {   // groceries 10,000 less a 2,500 credit filed under the same expense category
            tx.add(income(JAN.plusMonths(i).atDay(20), "GROCERIES", "2500", once("credit", JAN.plusMonths(i))));
        }
        CategorySpendingForecastResponse r = categories(tx, d(2026, 7, 15), 1);

        CategoryForecast groceries = r.categories().stream().filter(c -> c.category().equals("GROCERIES")).findFirst().orElseThrow();
        assertThat(groceries.monthly().get(0).point()).isEqualByComparingTo("7500");
    }
}
