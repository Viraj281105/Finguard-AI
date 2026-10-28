package com.finguard.backend.intelligence;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/recommendations over HTTP with the clock fixed at 2026-07-15 (so the period is 2026-04-01 .. 2026-06-30)
 * and SYNTHETIC ledgers.
 */
@Import(RecommendationApiTest.FixedClock.class)
class RecommendationApiTest extends IntegrationTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    private void add(TestUser user, String date, String type, String category, double amount, String title) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("amount", amount);
        body.put("type", type);
        body.put("date", date);
        body.put("category", category);
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated());
    }

    /** Apr-Jun 2026: income 50,000; housing 20,000, dining 16,000, groceries 10,000 -> spending 46,000, savings rate 8 %. */
    private void tightMonths(TestUser user) throws Exception {
        for (int m = 4; m <= 6; m++) {
            YearMonth ym = YearMonth.of(2026, m);
            String tag = ym.toString().replace("-", "");
            add(user, ym.atDay(1).toString(), "INCOME", "INCOME", 50000, "salary" + tag);
            add(user, ym.atDay(3).toString(), "EXPENSE", "HOUSING", 20000, "landlord" + tag);
            add(user, ym.atDay(12).toString(), "EXPENSE", "DINING", 16000, "restaurants" + tag);
            add(user, ym.atDay(15).toString(), "EXPENSE", "GROCERIES", 10000, "market" + tag);
        }
    }

    private void addLoan(TestUser user, double emi) throws Exception {
        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("name", "Car loan");
        loan.put("type", "CAR");
        loan.put("principal", 500000);
        loan.put("emiAmount", emi);
        loan.put("interestRate", 9);
        loan.put("startDate", "2026-01-01");
        mvc.perform(as(user, post("/api/loans")).contentType(MediaType.APPLICATION_JSON).content(json(loan))).andExpect(status().isCreated());
    }

    private JsonNode recommendations(TestUser user) throws Exception {
        return json.readTree(mvc.perform(as(user, get("/api/recommendations"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode api(TestUser user, String url) throws Exception {
        return json.readTree(mvc.perform(as(user, get(url))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> ids(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("recommendations").forEach(r -> out.add(r.get("id").asText()));
        return out;
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/recommendations")).andExpect(status().isUnauthorized());
    }

    @Test
    void noHistoryProducesNoRecommendationsAndSaysWhy() throws Exception {
        JsonNode body = recommendations(registerUser());
        assertThat(body.get("recommendations")).isEmpty();
        assertThat(body.get("healthy").asBoolean()).isFalse(); // nothing was evaluated, so nothing is "healthy"
        assertThat(body.get("period").get("monthsWithData").asInt()).isZero();
        assertThat(body.get("period").get("from").asText()).isEqualTo("2026-04-01");
        assertThat(body.get("period").get("to").asText()).isEqualTo("2026-06-30");
        assertThat(body.get("disclaimer").asText()).contains("not financial, tax or investment advice");
        List<String> skipped = new ArrayList<>();
        body.get("notEvaluated").forEach(n -> skipped.add(n.get("id").asText()));
        assertThat(skipped).contains("LOW_SAVINGS_RATE", "RISING_SPEND", "EMERGENCY_FUND");
    }

    @Test
    void tightBudgetGivesEvidenceThatReproducesFromTheAnalyticsApi() throws Exception {
        TestUser user = registerUser();
        tightMonths(user);
        addLoan(user, 25000); // 25,000 of 50,000 income = 50 %

        JsonNode body = recommendations(user);
        assertThat(ids(body)).containsExactly("LOW_SAVINGS_RATE", "HIGH_EMI_BURDEN"); // both HIGH; larger impact first
        JsonNode savings = body.get("recommendations").get(0);
        assertThat(savings.get("severity").asText()).isEqualTo("HIGH");

        // the evidence equals the analytics API for the same period
        JsonNode summary = api(user, "/api/analytics/summary?from=2026-04-01&to=2026-06-30");
        assertThat(savings.get("evidence").get(0).get("value").decimalValue()).isEqualByComparingTo(summary.get("savingsRate").decimalValue());
        assertThat(savings.get("evidence").get(0).get("value").decimalValue()).isEqualByComparingTo("8.00");
        assertThat(savings.get("evidence").get(1).get("value").decimalValue()).isEqualByComparingTo(summary.get("totalIncome").decimalValue());
        assertThat(savings.get("evidence").get(2).get("value").decimalValue()).isEqualByComparingTo(summary.get("totalExpenses").decimalValue());
        assertThat(savings.get("estimatedMonthlyImpact").decimalValue()).isEqualByComparingTo("6000.00"); // (20 - 8) % of 50,000

        JsonNode emi = body.get("recommendations").get(1);
        assertThat(emi.get("evidence").get(0).get("value").decimalValue()).isEqualByComparingTo(summary.get("totalMonthlyEmi").decimalValue());
        assertThat(emi.get("evidence").get(2).get("value").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(emi.get("estimatedMonthlyImpact").decimalValue()).isEqualByComparingTo("5000.00"); // 25,000 - 40 % of 50,000

        // the preset is a ready request for the scenario simulator, aimed at the largest flexible category
        JsonNode preset = savings.get("scenarioPreset");
        assertThat(preset.get("label").asText()).isEqualTo("Cut Dining by 10%");
        assertThat(preset.get("request").get("categoryAdjustments").get(0).get("category").asText()).isEqualTo("DINING");
        assertThat(preset.get("request").get("categoryAdjustments").get(0).get("value").decimalValue()).isEqualByComparingTo("-10");

        // thresholds are reported
        assertThat(body.get("thresholds").get("minSavingsRatePercent").decimalValue()).isEqualByComparingTo("20");
        assertThat(body.get("healthy").asBoolean()).isFalse();
    }

    @Test
    void aHealthyLedgerIsMarkedHealthy() throws Exception {
        TestUser user = registerUser();
        for (int m = 4; m <= 6; m++) {
            YearMonth ym = YearMonth.of(2026, m);
            String tag = ym.toString().replace("-", "");
            add(user, ym.atDay(1).toString(), "INCOME", "INCOME", 80000, "salary" + tag);
            add(user, ym.atDay(3).toString(), "EXPENSE", "HOUSING", 20000, "landlord" + tag);
            add(user, ym.atDay(15).toString(), "EXPENSE", "GROCERIES", 10000, "market" + tag);
        }
        JsonNode body = recommendations(user);
        assertThat(body.get("recommendations")).isEmpty();
        assertThat(body.get("healthy").asBoolean()).isTrue();
    }

    @Test
    void recommendationsUseOnlyTheCallersOwnData() throws Exception {
        TestUser tight = registerUser();
        TestUser fresh = registerUser();
        tightMonths(tight);
        addLoan(tight, 25000);

        assertThat(ids(recommendations(tight))).isNotEmpty();
        JsonNode other = recommendations(fresh);
        assertThat(other.get("recommendations")).isEmpty();
        assertThat(other.get("period").get("monthsWithData").asInt()).isZero();
    }

    @Test
    void shortHistoryDoesNotInventRecommendations() throws Exception {
        TestUser user = registerUser();
        add(user, "2026-06-01", "INCOME", "INCOME", 50000, "salary202606");
        add(user, "2026-06-03", "EXPENSE", "HOUSING", 60000, "landlord202606");
        JsonNode body = recommendations(user);
        assertThat(body.get("recommendations")).isEmpty(); // one month of data: the period rules do not run
        assertThat(body.get("period").get("monthsWithData").asInt()).isEqualTo(1);
        assertThat(body.get("healthy").asBoolean()).isFalse();
        assertThat(body.get("notEvaluated").toString()).contains("found 1");
    }

    @Test
    void currentMonthIsNeverPartOfThePeriod() throws Exception {
        TestUser user = registerUser();
        tightMonths(user);
        // a very bad July (the current month) must not change a thing
        add(user, "2026-07-02", "EXPENSE", "SHOPPING", 900000, "huge purchase");
        JsonNode body = recommendations(user);
        assertThat(body.get("recommendations").get(0).get("evidence").get(0).get("value").decimalValue()).isEqualByComparingTo("8.00");
        assertThat(body.get("period").get("to").asText()).isEqualTo("2026-06-30");
    }
}
