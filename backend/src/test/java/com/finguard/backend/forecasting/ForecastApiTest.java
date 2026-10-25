package com.finguard.backend.forecasting;

import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The forecasting HTTP API on a real database with "today" fixed at 2026-07-15 and SYNTHETIC ledgers:
 * contracts, authentication, user isolation, validation, and consistency with analytics and budgets.
 */
@Import(ForecastApiTest.FixedClock.class)
class ForecastApiTest extends IntegrationTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    // ---- data ------------------------------------------------------------------------------------

    private void add(TestUser user, String date, String type, String category, double amount, String title) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("amount", amount);
        body.put("type", type);
        body.put("date", date);
        if (category != null) {
            body.put("category", category);
        }
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk());
    }

    /** Jan..Jun 2026: income 50,000, rent 20,000, groceries 10,000 every month (synthetic). */
    private void sixSteadyMonths(TestUser user) throws Exception {
        for (int m = 1; m <= 6; m++) {
            YearMonth ym = YearMonth.of(2026, m);
            add(user, ym.atDay(1).toString(), "INCOME", "INCOME", 50000, "salary" + ym.toString().replace("-", ""));
            add(user, ym.atDay(3).toString(), "EXPENSE", "HOUSING", 20000, "landlord" + ym.toString().replace("-", ""));
            add(user, ym.atDay(15).toString(), "EXPENSE", "GROCERIES", 10000, "market" + ym.toString().replace("-", ""));
        }
    }

    private JsonNode getJson(TestUser user, String url) throws Exception {
        return json.readTree(mvc.perform(as(user, get(url))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    // ---- authentication and validation ----------------------------------------------------------------

    @Test
    void everyForecastEndpointRequiresAuthentication() throws Exception {
        for (String url : List.of("/api/forecast/cash-flow", "/api/forecast/categories", "/api/forecast/budget-overruns",
                "/api/forecast/savings", "/api/forecast/backtest")) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/forecast/scenarios/simulate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void horizonsAndMonthsAreValidated() throws Exception {
        TestUser user = registerUser();
        for (String url : List.of("/api/forecast/cash-flow?horizonMonths=0", "/api/forecast/cash-flow?horizonMonths=13",
                "/api/forecast/cash-flow?horizonMonths=abc", "/api/forecast/categories?horizonMonths=-1",
                "/api/forecast/savings?horizonMonths=99", "/api/forecast/budget-overruns?yearMonth=not-a-month",
                "/api/forecast/budget-overruns?yearMonth=2026-13", "/api/forecast/budget-overruns?yearMonth=2010-01")) {
            mvc.perform(as(user, get(url))).andExpect(status().isBadRequest());
        }
        mvc.perform(as(user, get("/api/forecast/cash-flow?horizonMonths=12"))).andExpect(status().isOk());
        mvc.perform(as(user, get("/api/forecast/cash-flow?horizonMonths=1"))).andExpect(status().isOk());
    }

    // ---- cash flow ---------------------------------------------------------------------------------------

    @Test
    void cashFlowIsBuiltFromThePersistedLedgerAndSeparatesObservedFromProjected() throws Exception {
        TestUser user = registerUser();
        sixSteadyMonths(user);

        JsonNode r = getJson(user, "/api/forecast/cash-flow?horizonMonths=3");

        assertThat(r.get("reliability").asText()).isEqualTo("RELIABLE");
        assertThat(r.get("method").asText()).isEqualTo("LEVEL_DAMPED_TREND");
        assertThat(r.get("algorithmVersion").asText()).isEqualTo("2.0.0");
        assertThat(r.get("asOf").asText()).isEqualTo("2026-07-15");
        assertThat(r.get("history").get("monthsUsed").asInt()).isEqualTo(6);
        assertThat(r.get("history").get("from").asText()).isEqualTo("2026-01");
        assertThat(r.get("historical")).hasSize(6);
        assertThat(r.get("projected")).hasSize(3);
        assertThat(r.get("projected").get(0).get("month").asText()).isEqualTo("2026-08");
        assertThat(r.get("projected").get(0).get("income").get("point").decimalValue()).isEqualByComparingTo("50000");
        assertThat(r.get("projected").get(0).get("expenses").get("point").decimalValue()).isEqualByComparingTo("30000");
        assertThat(r.get("projected").get(0).get("net").get("point").decimalValue()).isEqualByComparingTo("20000");
        assertThat(r.get("summary").get("totalNet").decimalValue()).isEqualByComparingTo("60000");
        assertThat(r.get("intervalNote").asText()).contains("not a calibrated probability");
        assertThat(r.get("assumptions").toString()).contains("Seasonality is not modelled");
    }

    @Test
    void forecastHistoryEqualsTheAnalyticsMonthlyFigures() throws Exception {
        TestUser user = registerUser();
        sixSteadyMonths(user);
        add(user, "2026-03-20", "INCOME", "REFUNDS", 1234.56, "refundone");
        add(user, "2026-04-09", "EXPENSE", "TRANSFERS", 777, "xfer");
        add(user, "2026-05-09", "EXPENSE", null, 321.10, "unknownbuy");

        JsonNode forecast = getJson(user, "/api/forecast/cash-flow?horizonMonths=1").get("historical");
        JsonNode analytics = getJson(user, "/api/analytics/monthly");

        assertThat(forecast).hasSameSizeAs(analytics);
        for (int i = 0; i < analytics.size(); i++) {
            assertThat(forecast.get(i).get("month").asText()).isEqualTo(analytics.get(i).get("month").asText());
            assertThat(forecast.get(i).get("income").decimalValue()).isEqualByComparingTo(analytics.get(i).get("income").decimalValue());
            assertThat(forecast.get(i).get("expenses").decimalValue()).isEqualByComparingTo(analytics.get(i).get("expenses").decimalValue());
            assertThat(forecast.get(i).get("net").decimalValue()).isEqualByComparingTo(analytics.get(i).get("netCashFlow").decimalValue());
        }
    }

    @Test
    void aUserWithoutHistoryGetsAnHonestUnavailableNotZeros() throws Exception {
        TestUser user = registerUser();

        JsonNode r = getJson(user, "/api/forecast/cash-flow?horizonMonths=3");

        assertThat(r.get("reliability").asText()).isEqualTo("UNAVAILABLE");
        assertThat(r.get("projected")).isEmpty();
        assertThat(r.get("summary").isNull()).isTrue();
        assertThat(r.get("warnings").toString()).contains("No complete month");
        assertThat(getJson(user, "/api/forecast/savings").get("reliability").asText()).isEqualTo("UNAVAILABLE");
        assertThat(getJson(user, "/api/forecast/categories").get("categories")).isEmpty();
        assertThat(getJson(user, "/api/forecast/backtest").get("status").asText()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void forecastsOnlyEverUseTheCallersOwnTransactions() throws Exception {
        TestUser alice = registerUser();
        TestUser bob = registerUser();
        sixSteadyMonths(alice);
        for (int m = 1; m <= 6; m++) {   // Bob: a very different, smaller ledger
            YearMonth ym = YearMonth.of(2026, m);
            add(bob, ym.atDay(1).toString(), "INCOME", "INCOME", 10000, "bobsalary" + m);
            add(bob, ym.atDay(5).toString(), "EXPENSE", "GROCERIES", 4000, "bobfood" + m);
            add(bob, ym.atDay(9).toString(), "EXPENSE", "TRANSPORT", 1000, "bobbus" + m);
        }

        JsonNode a = getJson(alice, "/api/forecast/cash-flow?horizonMonths=1");
        JsonNode b = getJson(bob, "/api/forecast/cash-flow?horizonMonths=1");

        assertThat(a.get("projected").get(0).get("expenses").get("point").decimalValue()).isEqualByComparingTo("30000");
        assertThat(b.get("projected").get(0).get("expenses").get("point").decimalValue()).isEqualByComparingTo("5000");
        assertThat(b.get("projected").get(0).get("income").get("point").decimalValue()).isEqualByComparingTo("10000");
        JsonNode bobCategories = getJson(bob, "/api/forecast/categories?horizonMonths=1").get("categories");
        assertThat(bobCategories.toString()).doesNotContain("HOUSING");
        // a newcomer sees none of it
        assertThat(getJson(registerUser(), "/api/forecast/cash-flow").get("reliability").asText()).isEqualTo("UNAVAILABLE");
    }

    // ---- categories -----------------------------------------------------------------------------------------

    @Test
    void categoryForecastsListWhatTheyCoverAndWhatTheyDoNot() throws Exception {
        TestUser user = registerUser();
        sixSteadyMonths(user);
        add(user, "2026-03-09", "EXPENSE", "ENTERTAINMENT", 800, "onemovie");

        JsonNode r = getJson(user, "/api/forecast/categories?horizonMonths=2");

        assertThat(r.get("categories")).hasSize(2);
        assertThat(r.get("categories").get(0).get("category").asText()).isEqualTo("HOUSING");
        assertThat(r.get("categories").get(0).get("monthly")).hasSize(2);
        assertThat(r.get("categories").get(0).get("monthly").get(0).get("point").decimalValue()).isEqualByComparingTo("20000");
        assertThat(r.get("withoutForecast").get(0).get("category").asText()).isEqualTo("ENTERTAINMENT");
        assertThat(r.get("withoutForecast").get(0).get("reason").asText()).contains("at least 3");
    }

    // ---- budgets ------------------------------------------------------------------------------------------------

    @Test
    void budgetOutlookUsesTheSameActualSpendAsTheBudgetOverview() throws Exception {
        TestUser user = registerUser();
        sixSteadyMonths(user);
        for (int day : new int[]{1, 2, 4, 6, 8, 10}) {
            add(user, "2026-07-%02d".formatted(day), "EXPENSE", "SHOPPING", 1000, "julybuy" + day);
        }
        add(user, "2026-07-11", "INCOME", "REFUNDS", 500, "refundjuly");
        mvc.perform(as(user, post("/api/budgets")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("yearMonth", "2026-07", "amount", 40000)))).andExpect(status().isCreated());
        mvc.perform(as(user, post("/api/budgets")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("yearMonth", "2026-07", "category", "SHOPPING", "amount", 5000)))).andExpect(status().isCreated());

        JsonNode outlook = getJson(user, "/api/forecast/budget-overruns?yearMonth=2026-07");
        JsonNode overview = getJson(user, "/api/budgets/overview?month=2026-07");

        assertThat(outlook.get("period").asText()).isEqualTo("CURRENT");
        assertThat(outlook.get("overall").get("actualToDate").decimalValue())
                .isEqualByComparingTo(overview.get("overallBudget").get("actualSpend").decimalValue());
        assertThat(outlook.get("overall").get("actualToDate").decimalValue()).isEqualByComparingTo("5500");   // 6,000 - 500 refund
        JsonNode shopping = outlook.get("categories").get(0);
        assertThat(shopping.get("category").asText()).isEqualTo("SHOPPING");
        assertThat(shopping.get("actualToDate").decimalValue()).isEqualByComparingTo("6000");
        assertThat(shopping.get("risk").asText()).isEqualTo("EXCEEDED");
        assertThat(shopping.get("rule").asText()).contains("already above the budget");
        // overall: run-rate and history are both available and below 90 % of 40,000
        assertThat(outlook.get("overall").get("risk").asText()).isEqualTo("ON_TRACK");
        assertThat(outlook.get("overall").get("basis").toString()).contains("RUN_RATE").contains("HISTORY");
        assertThat(outlook.get("overall").get("factors")).isNotEmpty();
    }

    @Test
    void budgetOutlookIsIndeterminateWhenThereIsNoEvidenceAndNeverAnInventedNumber() throws Exception {
        TestUser user = registerUser();
        add(user, "2026-07-02", "EXPENSE", "SHOPPING", 300, "tiny");
        mvc.perform(as(user, post("/api/budgets")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("yearMonth", "2026-07", "amount", 10000)))).andExpect(status().isCreated());

        JsonNode overall = getJson(user, "/api/forecast/budget-overruns").get("overall");

        assertThat(overall.get("risk").asText()).isEqualTo("INDETERMINATE");
        assertThat(overall.get("expectedCentral").isNull()).isTrue();
        assertThat(overall.get("estimatedOverrun").isNull()).isTrue();
        assertThat(overall.get("indeterminateReason").asText()).contains("Not enough evidence");
    }

    @Test
    void anotherUsersBudgetsNeverAppear() throws Exception {
        TestUser alice = registerUser();
        TestUser bob = registerUser();
        mvc.perform(as(alice, post("/api/budgets")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("yearMonth", "2026-07", "amount", 12345)))).andExpect(status().isCreated());

        JsonNode bobOutlook = getJson(bob, "/api/forecast/budget-overruns?yearMonth=2026-07");

        assertThat(bobOutlook.get("overall").isNull()).isTrue();
        assertThat(bobOutlook.get("categories")).isEmpty();
    }

    // ---- savings ---------------------------------------------------------------------------------------------------------

    @Test
    void savingsKeepProjectedSurplusRecordedGoalContributionsAndBankBalancesApart() throws Exception {
        TestUser user = registerUser();
        sixSteadyMonths(user);
        String goalId = json.readTree(mvc.perform(as(user, post("/api/goals")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Emergency fund", "targetAmount", 60000, "targetDate", "2026-12-31"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(as(user, post("/api/goals/" + goalId + "/contributions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("amount", 6000)))).andExpect(status().isCreated());

        JsonNode r = getJson(user, "/api/forecast/savings?horizonMonths=3");

        assertThat(r.get("disclaimer").asText()).contains("not a bank balance").contains("self-tracked");
        assertThat(r.get("accounting").get("verifiedBankBalance").isNull()).isTrue();
        assertThat(r.get("accounting").get("verifiedBankBalanceNote").asText()).contains("does not hold");
        assertThat(r.get("accounting").get("projectedSurplusOverHorizon").decimalValue()).isEqualByComparingTo("60000");
        assertThat(r.get("accounting").get("recordedGoalContributions").decimalValue()).isEqualByComparingTo("6000");
        assertThat(r.get("averageMonthlyNet").decimalValue()).isEqualByComparingTo("20000");
        assertThat(r.get("points")).hasSize(3);
        assertThat(r.get("points").get(2).get("cumulativeNet").decimalValue()).isEqualByComparingTo("60000");
        JsonNode goal = r.get("goals").get(0);
        // remaining 54,000 over 5 months (Jul..Dec) = 10,800 a month; the projected 20,000 meets it alone
        assertThat(goal.get("requiredMonthlyPace").decimalValue()).isEqualByComparingTo("10800");
        assertThat(goal.get("onPaceIfAlone").asBoolean()).isTrue();
        assertThat(goal.get("monthsIfAllSurplusUsed").asInt()).isEqualTo(3);          // ceil(54,000 / 20,000)
        assertThat(goal.get("completionMonthIfAllSurplusUsed").asText()).isEqualTo("2026-10");
        assertThat(goal.get("recordedAmount").decimalValue()).isEqualByComparingTo("6000");
        assertThat(r.get("accounting").get("goalsOvercommitted").asBoolean()).isFalse();
    }

    // ---- scenarios -------------------------------------------------------------------------------------------------------------

    private JsonNode simulate(TestUser user, Map<String, Object> body, int expectedStatus) throws Exception {
        String content = mvc.perform(as(user, post("/api/forecast/scenarios/simulate")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return json.readTree(content);
    }

    @Test
    void aScenarioIsHypotheticalAndChangesNothingPersisted() throws Exception {
        TestUser user = registerUser();
        sixSteadyMonths(user);
        mvc.perform(as(user, post("/api/budgets")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("yearMonth", "2026-07", "amount", 40000)))).andExpect(status().isCreated());
        mvc.perform(as(user, post("/api/goals")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Trip", "targetAmount", 30000)))).andExpect(status().isCreated());
        String transactionsBefore = getJson(user, "/api/transactions").toString();
        String budgetsBefore = getJson(user, "/api/budgets?month=2026-07").toString();
        String goalsBefore = getJson(user, "/api/goals").toString();

        JsonNode r = simulate(user, Map.of(
                "horizonMonths", 3,
                "incomeAdjustment", Map.of("type", "PERCENTAGE", "value", 10),
                "categoryAdjustments", List.of(Map.of("category", "GROCERIES", "type", "PERCENTAGE", "value", -20)),
                "addedRecurringExpenses", List.of(Map.of("title", "Gym", "monthlyAmount", 1500))), 200);

        // income +5,000/month; groceries -2,000/month; gym +1,500/month => net +5,500/month = +16,500 over 3 months
        assertThat(r.get("hypothetical").asBoolean()).isTrue();
        assertThat(r.get("available").asBoolean()).isTrue();
        assertThat(r.get("disclaimer").asText()).contains("change nothing");
        assertThat(r.get("baseline").get("net").decimalValue()).isEqualByComparingTo("60000");
        assertThat(r.get("scenario").get("net").decimalValue()).isEqualByComparingTo("76500");
        assertThat(r.get("delta").get("net").decimalValue()).isEqualByComparingTo("16500");
        assertThat(r.get("months")).hasSize(3);

        assertThat(getJson(user, "/api/transactions").toString()).isEqualTo(transactionsBefore);
        assertThat(getJson(user, "/api/budgets?month=2026-07").toString()).isEqualTo(budgetsBefore);
        assertThat(getJson(user, "/api/goals").toString()).isEqualTo(goalsBefore);
    }

    @Test
    void loanScenariosUseOnlyTheCallersOwnLoansAndChangeNothing() throws Exception {
        TestUser owner = registerUser();
        TestUser other = registerUser();
        sixSteadyMonths(owner);
        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("name", "Car loan");
        loan.put("type", "CAR");
        loan.put("principal", 100000);
        loan.put("emiAmount", 8884.88);
        loan.put("interestRate", 12);
        loan.put("startDate", "2026-06-01");
        String created = mvc.perform(as(owner, post("/api/loans")).contentType(MediaType.APPLICATION_JSON).content(json(loan)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String loanId = json.readTree(created).get("id").asText();
        String loansBefore = getJson(owner, "/api/loans").toString();

        JsonNode body = simulate(owner, Map.of("horizonMonths", 3,
                "loanAdjustments", List.of(Map.of("loanId", loanId, "action", "CLOSE"))), 200);
        assertThat(body.get("available").asBoolean()).isTrue();
        JsonNode effect = body.get("loanEffects").get(0);
        assertThat(effect.get("loanName").asText()).isEqualTo("Car loan");
        assertThat(effect.get("scenarioMonthsToPayoff").asInt()).isZero();
        assertThat(effect.get("interestSaved").decimalValue().signum()).isPositive();
        assertThat(body.get("explanations").toString()).contains("Car loan");

        // another user cannot see or simulate it; an unknown id is the same 400
        assertThat(simulate(other, Map.of("loanAdjustments", List.of(Map.of("loanId", loanId, "action", "CLOSE"))), 400)
                .get("message").asText()).contains("Unknown loan");
        // a scenario writes nothing
        assertThat(getJson(owner, "/api/loans").toString()).isEqualTo(loansBefore);
    }

    @Test
    void scenarioValidationReturnsClearBadRequests() throws Exception {
        TestUser user = registerUser();
        sixSteadyMonths(user);

        assertThat(simulate(user, Map.of("horizonMonths", 13), 400).get("message").asText()).isNotBlank();
        assertThat(simulate(user, Map.of("incomeAdjustment", Map.of("type", "PERCENTAGE", "value", 900)), 400)
                .get("message").asText()).contains("between -100 and 500");
        assertThat(simulate(user, Map.of("incomeAdjustment", Map.of("type", "FIXED_AMOUNT", "value", 99999999999L)), 400)).isNotNull();
        assertThat(simulate(user, Map.of("categoryAdjustments", List.of(Map.of("category", "NOPE", "type", "PERCENTAGE", "value", -5))), 400)
                .get("message").asText()).contains("Unknown category");
        assertThat(simulate(user, Map.of("addedRecurringExpenses", List.of(Map.of("title", "x", "monthlyAmount", -5))), 400)).isNotNull();
        assertThat(simulate(user, Map.of("removedRecurringExpenseIds", List.of("rec-999")), 400)
                .get("message").asText()).contains("Unknown recurring expense id");
        mvc.perform(as(user, post("/api/forecast/scenarios/simulate")).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aScenarioWithoutABaselineSaysSoInsteadOfInventingOne() throws Exception {
        JsonNode r = simulate(registerUser(), Map.of("incomeAdjustment", Map.of("type", "PERCENTAGE", "value", 10)), 200);

        assertThat(r.get("available").asBoolean()).isFalse();
        assertThat(r.get("unavailableReason").asText()).isNotBlank();
        assertThat(r.get("baseline").isNull()).isTrue();
        assertThat(r.get("scenario").isNull()).isTrue();
        assertThat(r.get("months")).isEmpty();
    }

    // ---- evaluation ------------------------------------------------------------------------------------------------------------------

    @Test
    void theEvaluationEndpointReportsSampleSizesAndRefusesToConcludeFromAFewMonths() throws Exception {
        TestUser user = registerUser();
        sixSteadyMonths(user);

        JsonNode r = getJson(user, "/api/forecast/backtest");

        assertThat(r.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(r.get("evaluatedOn").asText()).isEqualTo("USER_HISTORY");
        assertThat(r.get("scheme").asText()).isEqualTo("CHRONOLOGICAL_ROLLING_ORIGIN");
        assertThat(r.get("series")).hasSize(3);
        JsonNode expenses = r.get("series").get(0);
        assertThat(expenses.get("series").asText()).isEqualTo("EXPENSES");
        JsonNode h1 = expenses.get("horizons").get(0);
        assertThat(h1.get("horizon").asInt()).isEqualTo(1);
        assertThat(h1.get("samples").asInt()).isEqualTo(3);            // targets Apr, May, Jun (>= 3 months of history each)
        assertThat(h1.get("verdict").asText()).isEqualTo("INCONCLUSIVE");
        assertThat(h1.get("model").get("mae").decimalValue()).isEqualByComparingTo("0");      // perfectly flat data
        assertThat(h1.get("model").get("wapePercent").decimalValue()).isEqualByComparingTo("0");
        assertThat(r.get("limitations").toString()).contains("INCONCLUSIVE").contains("synthetic fixtures");
    }
}
