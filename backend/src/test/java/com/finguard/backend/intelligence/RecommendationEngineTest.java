package com.finguard.backend.intelligence;

import com.finguard.backend.intelligence.dto.RecurringExpenseItemDto;
import com.finguard.backend.intelligence.recommendations.RecommendationEngine;
import com.finguard.backend.intelligence.recommendations.RecommendationEngine.Inputs;
import com.finguard.backend.intelligence.recommendations.RecommendationEngine.MonthSpend;
import com.finguard.backend.intelligence.recommendations.RecommendationProperties;
import com.finguard.backend.intelligence.recommendations.RecommendationsResponse.Recommendation;
import com.finguard.backend.intelligence.recommendations.RecommendationsResponse.Severity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One boundary test per rule (below / at / above the threshold), evidence, ordering, missing inputs and
 * configurable thresholds. All figures are SYNTHETIC. Defaults: savings 20, EMI 40 (critical 60), class share 70,
 * rise 10, uncategorised 20 (>= 10 transactions), subscription review at 3.
 */
class RecommendationEngineTest {

    private static final RecommendationProperties DEFAULTS = new RecommendationProperties(
            3, 3, bd("20"), bd("10"), bd("40"), bd("60"), bd("70"), bd("10"), bd("20"), 10, 3, bd("10"));
    private final RecommendationEngine engine = new RecommendationEngine(DEFAULTS);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** A healthy 3-month period: income 50,000 and spending 30,000 a month, no loans, investments or recurring items. */
    private static Inputs healthy() {
        return new Inputs(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), 3, bd("150000"), bd("90000"), bd("40.00"), 0, bd("0"),
                spend("30000", "30000", "30000"), 100, bd("0.00"), 0, List.of(), Map.of(), "DINING");
    }

    private static List<MonthSpend> spend(String a, String b, String c) {
        return List.of(new MonthSpend(YearMonth.of(2026, 4), bd(a)), new MonthSpend(YearMonth.of(2026, 5), bd(b)), new MonthSpend(YearMonth.of(2026, 6), bd(c)));
    }

    private static Inputs withSavings(Inputs i, String rate) {
        return new Inputs(i.from(), i.to(), i.monthsWithData(), i.totalIncome(), i.totalExpenses(), rate == null ? null : bd(rate), i.activeLoans(), i.monthlyEmi(),
                i.monthlySpend(), i.totalTransactions(), i.uncategorisedPercentage(), i.uncategorisedCount(), i.recurring(), i.investmentValueByType(), i.topDiscretionaryCategory());
    }

    private static Inputs withEmi(Inputs i, int loans, String emi, String income) {
        return new Inputs(i.from(), i.to(), i.monthsWithData(), bd(income), i.totalExpenses(), i.savingsRate(), loans, bd(emi),
                i.monthlySpend(), i.totalTransactions(), i.uncategorisedPercentage(), i.uncategorisedCount(), i.recurring(), i.investmentValueByType(), i.topDiscretionaryCategory());
    }

    private static Inputs withSpend(Inputs i, List<MonthSpend> s) {
        return new Inputs(i.from(), i.to(), i.monthsWithData(), i.totalIncome(), i.totalExpenses(), i.savingsRate(), i.activeLoans(), i.monthlyEmi(),
                s, i.totalTransactions(), i.uncategorisedPercentage(), i.uncategorisedCount(), i.recurring(), i.investmentValueByType(), i.topDiscretionaryCategory());
    }

    private static Inputs withMonths(Inputs i, int months) {
        return new Inputs(i.from(), i.to(), months, i.totalIncome(), i.totalExpenses(), i.savingsRate(), i.activeLoans(), i.monthlyEmi(),
                i.monthlySpend(), i.totalTransactions(), i.uncategorisedPercentage(), i.uncategorisedCount(), i.recurring(), i.investmentValueByType(), i.topDiscretionaryCategory());
    }

    private static Inputs withQuality(Inputs i, long tx, String pct, long count) {
        return new Inputs(i.from(), i.to(), i.monthsWithData(), i.totalIncome(), i.totalExpenses(), i.savingsRate(), i.activeLoans(), i.monthlyEmi(),
                i.monthlySpend(), tx, bd(pct), count, i.recurring(), i.investmentValueByType(), i.topDiscretionaryCategory());
    }

    private static Inputs withInvestments(Inputs i, Map<String, BigDecimal> m) {
        return new Inputs(i.from(), i.to(), i.monthsWithData(), i.totalIncome(), i.totalExpenses(), i.savingsRate(), i.activeLoans(), i.monthlyEmi(),
                i.monthlySpend(), i.totalTransactions(), i.uncategorisedPercentage(), i.uncategorisedCount(), i.recurring(), m, i.topDiscretionaryCategory());
    }

    private static Inputs withRecurring(Inputs i, List<RecurringExpenseItemDto> r) {
        return new Inputs(i.from(), i.to(), i.monthsWithData(), i.totalIncome(), i.totalExpenses(), i.savingsRate(), i.activeLoans(), i.monthlyEmi(),
                i.monthlySpend(), i.totalTransactions(), i.uncategorisedPercentage(), i.uncategorisedCount(), r, i.investmentValueByType(), i.topDiscretionaryCategory());
    }

    private static Inputs withTop(Inputs i, String top) {
        return new Inputs(i.from(), i.to(), i.monthsWithData(), i.totalIncome(), i.totalExpenses(), i.savingsRate(), i.activeLoans(), i.monthlyEmi(),
                i.monthlySpend(), i.totalTransactions(), i.uncategorisedPercentage(), i.uncategorisedCount(), i.recurring(), i.investmentValueByType(), top);
    }

    private static RecurringExpenseItemDto sub(String id, String merchant, String category) {
        return new RecurringExpenseItemDto(id, merchant + " plan", merchant, category, bd("500"), "MONTHLY", 4, LocalDate.of(2026, 6, 5), "HIGH", false, "x");
    }

    private List<String> ids(Inputs in) {
        return engine.evaluate(in).recommendations().stream().map(Recommendation::id).toList();
    }

    private Recommendation only(Inputs in, String id) {
        return engine.evaluate(in).recommendations().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    // ---- healthy -------------------------------------------------------------------------------------

    @Test
    void aHealthyPeriodProducesNoRecommendationAndOnlyTheEmergencyFundCannotBeEvaluated() {
        var result = engine.evaluate(healthy());
        assertThat(result.recommendations()).isEmpty();
        assertThat(result.notEvaluated()).extracting("id").containsExactly(RecommendationEngine.EMERGENCY_FUND);
        assertThat(result.notEvaluated().get(0).reason()).contains("no account balances");
    }

    // ---- savings rate --------------------------------------------------------------------------------

    @Test
    void savingsRateBoundaries() {
        assertThat(ids(withSavings(healthy(), "20.00"))).isEmpty(); // at the threshold: not below
        assertThat(only(withSavings(healthy(), "19.99"), "LOW_SAVINGS_RATE").severity()).isEqualTo(Severity.MEDIUM);
        assertThat(only(withSavings(healthy(), "10.00"), "LOW_SAVINGS_RATE").severity()).isEqualTo(Severity.MEDIUM);
        assertThat(only(withSavings(healthy(), "9.99"), "LOW_SAVINGS_RATE").severity()).isEqualTo(Severity.HIGH);
        assertThat(only(withSavings(healthy(), "0.00"), "LOW_SAVINGS_RATE").severity()).isEqualTo(Severity.HIGH);
        assertThat(only(withSavings(healthy(), "-0.01"), "LOW_SAVINGS_RATE").severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void savingsRateEvidenceAndImpact_byHand() {
        // 15 % on 50,000 a month; reaching 20 % means (20 - 15) % of 50,000 = 2,500 a month
        Recommendation r = only(withSavings(healthy(), "15.00"), "LOW_SAVINGS_RATE");
        assertThat(r.estimatedMonthlyImpact()).isEqualByComparingTo("2500.00");
        assertThat(r.evidence()).extracting("metric").contains("Savings rate", "Total income", "Total spending", "Months with records");
        assertThat(r.evidence().get(0).value()).isEqualByComparingTo("15.00");
        assertThat(r.evidence().get(0).source()).isEqualTo("/api/analytics/summary?from=2026-04-01&to=2026-06-30");
        assertThat(r.explanation()).contains("15%").contains("last 3 months");
    }

    @Test
    void savingsRateNeedsIncomeAndEnoughMonths() {
        var noIncome = engine.evaluate(withSavings(healthy(), null));
        assertThat(noIncome.recommendations()).isEmpty();
        assertThat(noIncome.notEvaluated()).anyMatch(n -> n.id().equals("LOW_SAVINGS_RATE") && n.reason().contains("No income"));

        var shortHistory = engine.evaluate(withMonths(withSavings(healthy(), "5.00"), 2));
        assertThat(shortHistory.recommendations()).isEmpty();
        assertThat(shortHistory.notEvaluated()).anyMatch(n -> n.id().equals("LOW_SAVINGS_RATE") && n.reason().contains("found 2"));
    }

    // ---- EMI burden ----------------------------------------------------------------------------------

    @Test
    void emiBurdenBoundaries() {
        // income 150,000 over 3 months = 50,000 a month
        assertThat(ids(withEmi(healthy(), 1, "20000", "150000"))).isEmpty(); // exactly 40.00 %: not above
        Recommendation high = only(withEmi(healthy(), 1, "20500", "150000"), "HIGH_EMI_BURDEN"); // 41 %
        assertThat(high.severity()).isEqualTo(Severity.HIGH);
        assertThat(high.estimatedMonthlyImpact()).isEqualByComparingTo("500.00"); // 20,500 - 40 % of 50,000
        assertThat(only(withEmi(healthy(), 1, "30000", "150000"), "HIGH_EMI_BURDEN").severity()).isEqualTo(Severity.HIGH); // exactly 60 %
        assertThat(only(withEmi(healthy(), 1, "30500", "150000"), "HIGH_EMI_BURDEN").severity()).isEqualTo(Severity.CRITICAL);
        assertThat(high.evidence()).extracting("metric").contains("Total monthly EMI", "Average monthly income", "EMI share of income", "Active loans");
        assertThat(high.evidence().get(2).value()).isEqualByComparingTo("41.00");
    }

    @Test
    void emiBurdenIsSilentWithoutLoansAndExplainsMissingIncome() {
        assertThat(ids(withEmi(healthy(), 0, "0", "150000"))).isEmpty();
        assertThat(engine.evaluate(withEmi(healthy(), 0, "0", "150000")).notEvaluated()).extracting("id").doesNotContain("HIGH_EMI_BURDEN");
        var noIncome = engine.evaluate(withEmi(healthy(), 1, "10000", "0"));
        assertThat(noIncome.recommendations()).extracting(Recommendation::id).doesNotContain("HIGH_EMI_BURDEN");
        assertThat(noIncome.notEvaluated()).anyMatch(n -> n.id().equals("HIGH_EMI_BURDEN"));
    }

    // ---- investment concentration ---------------------------------------------------------------------

    @Test
    void investmentConcentrationBoundaries() {
        assertThat(ids(withInvestments(healthy(), Map.of("STOCKS", bd("70"), "FD", bd("30"))))).isEmpty(); // exactly 70 %
        Recommendation r = only(withInvestments(healthy(), Map.of("STOCKS", bd("71"), "FD", bd("29"))), "INVESTMENT_CONCENTRATION");
        assertThat(r.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(r.evidence()).extracting("value").contains(bd("71"), bd("100"));
        assertThat(r.evidence().get(2).value()).isEqualByComparingTo("71.00");
        assertThat(r.explanation()).contains("71%").contains("Stocks");
        assertThat(ids(withInvestments(healthy(), Map.of()))).isEmpty(); // no investments recorded
    }

    // ---- rising spend ----------------------------------------------------------------------------------

    @Test
    void risingSpendBoundaries() {
        assertThat(ids(withSpend(healthy(), spend("30000", "31000", "32000")))).isEmpty(); // +6.67 %: below 10
        Recommendation atThreshold = only(withSpend(healthy(), spend("30000", "31500", "33000")), "RISING_SPEND"); // exactly +10 %
        assertThat(atThreshold.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(atThreshold.estimatedMonthlyImpact()).isEqualByComparingTo("3000");
        assertThat(ids(withSpend(healthy(), spend("30000", "28000", "36000")))).isEmpty(); // not strictly rising
        assertThat(ids(withSpend(healthy(), spend("30000", "30000", "36000")))).isEmpty();
        assertThat(atThreshold.evidence()).extracting("metric").contains("Spending 2026-04", "Spending 2026-05", "Spending 2026-06", "Rise over the run");
        assertThat(atThreshold.evidence().get(0).source()).isEqualTo("/api/analytics/monthly?from=2026-04-01&to=2026-06-30");
    }

    @Test
    void risingSpendNeedsThreeConsecutiveMonths() {
        var gap = engine.evaluate(withSpend(healthy(), List.of(new MonthSpend(YearMonth.of(2026, 3), bd("1")), new MonthSpend(YearMonth.of(2026, 5), bd("2")), new MonthSpend(YearMonth.of(2026, 6), bd("3")))));
        assertThat(gap.recommendations()).isEmpty();
        assertThat(gap.notEvaluated()).anyMatch(n -> n.id().equals("RISING_SPEND") && n.reason().contains("not consecutive"));
        var two = engine.evaluate(withSpend(healthy(), healthy().monthlySpend().subList(0, 2)));
        assertThat(two.notEvaluated()).anyMatch(n -> n.id().equals("RISING_SPEND"));
    }

    // ---- data quality ----------------------------------------------------------------------------------

    @Test
    void uncategorisedShareBoundaries() {
        assertThat(ids(withQuality(healthy(), 100, "20.00", 20))).isEmpty(); // exactly 20 %
        Recommendation r = only(withQuality(healthy(), 100, "20.01", 20), "UNCATEGORISED_SHARE");
        assertThat(r.severity()).isEqualTo(Severity.INFO);
        assertThat(r.evidence().get(0).source()).isEqualTo("/api/analytics/data-quality");
        var few = engine.evaluate(withQuality(healthy(), 9, "90.00", 8));
        assertThat(few.recommendations()).isEmpty();
        assertThat(few.notEvaluated()).anyMatch(n -> n.id().equals("UNCATEGORISED_SHARE") && n.reason().contains("found 9"));
    }

    @Test
    void subscriptionReview() {
        assertThat(ids(withRecurring(healthy(), List.of(sub("1", "Netflix", "SUBSCRIPTIONS"), sub("2", "Spotify", "SUBSCRIPTIONS"))))).isEmpty();
        Recommendation three = only(withRecurring(healthy(), List.of(sub("1", "Netflix", "SUBSCRIPTIONS"), sub("2", "Spotify", "SUBSCRIPTIONS"), sub("3", "Hotstar", "SUBSCRIPTIONS"))), "SUBSCRIPTION_REVIEW");
        assertThat(three.severity()).isEqualTo(Severity.INFO);
        assertThat(three.title()).isEqualTo("Several recurring subscriptions");
        Recommendation dup = only(withRecurring(healthy(), List.of(sub("1", "Netflix", "SUBSCRIPTIONS"), sub("2", "netflix ", "SUBSCRIPTIONS"))), "SUBSCRIPTION_REVIEW");
        assertThat(dup.title()).isEqualTo("Possible duplicate subscriptions");
        // other categories are not subscriptions
        assertThat(ids(withRecurring(healthy(), List.of(sub("1", "Rent", "HOUSING"), sub("2", "Rent", "HOUSING"), sub("3", "Loan", "LOAN_PAYMENTS"))))).isEmpty();
    }

    // ---- ordering and presets ---------------------------------------------------------------------------

    @Test
    void itemsAreOrderedBySeverityThenImpactThenId() {
        Inputs in = withQuality(withEmi(withSavings(healthy(), "-5.00"), 1, "20500", "150000"), 100, "30.00", 30);
        // CRITICAL savings, HIGH emi, INFO uncategorised
        assertThat(ids(in)).containsExactly("LOW_SAVINGS_RATE", "HIGH_EMI_BURDEN", "UNCATEGORISED_SHARE");

        // same severity: larger impact first (savings 15 % -> 2,500 vs EMI 500 would both be below HIGH; use HIGH savings 5 % -> 7,500)
        Inputs two = withEmi(withSavings(healthy(), "5.00"), 1, "20500", "150000");
        assertThat(ids(two)).containsExactly("LOW_SAVINGS_RATE", "HIGH_EMI_BURDEN");
        Inputs smaller = withEmi(withSavings(healthy(), "9.00"), 1, "40000", "150000"); // savings impact 5,500; EMI excess 20,000
        assertThat(ids(smaller)).containsExactly("HIGH_EMI_BURDEN", "LOW_SAVINGS_RATE");
    }

    @Test
    void scenarioPresetIsOfferedOnlyWhenThereIsADiscretionaryCategory() {
        Recommendation r = only(withSavings(healthy(), "5.00"), "LOW_SAVINGS_RATE");
        assertThat(r.scenarioPreset().label()).isEqualTo("Cut Dining by 10%");
        assertThat(r.scenarioPreset().request()).containsEntry("horizonMonths", 6);
        assertThat(r.scenarioPreset().request().get("categoryAdjustments").toString()).contains("DINING").contains("PERCENTAGE").contains("-10");
        assertThat(only(withTop(withSavings(healthy(), "5.00"), null), "LOW_SAVINGS_RATE").scenarioPreset()).isNull();
    }

    // ---- configuration ------------------------------------------------------------------------------------

    @Test
    void thresholdsAreConfigurable() {
        RecommendationEngine strict = new RecommendationEngine(new RecommendationProperties(
                3, 3, bd("30"), bd("10"), bd("40"), bd("60"), bd("70"), bd("10"), bd("20"), 10, 3, bd("10")));
        assertThat(strict.evaluate(withSavings(healthy(), "25.00")).recommendations()).extracting(Recommendation::id).containsExactly("LOW_SAVINGS_RATE");
        assertThat(engine.evaluate(withSavings(healthy(), "25.00")).recommendations()).isEmpty();
    }

    @Test
    void deterministic() {
        Inputs in = withEmi(withSavings(healthy(), "5.00"), 1, "20500", "150000");
        assertThat(engine.evaluate(in)).usingRecursiveComparison().isEqualTo(engine.evaluate(in));
    }
}
