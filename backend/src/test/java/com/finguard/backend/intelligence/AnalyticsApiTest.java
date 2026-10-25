package com.finguard.backend.intelligence;

import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalyticsApiTest extends IntegrationTest {

    @Test
    void unauthenticatedRequestsReturn401() throws Exception {
        mvc.perform(get("/api/analytics/summary"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/analytics/monthly"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/analytics/categories"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/analytics/recurring"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/analytics/data-quality"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/analytics/insights"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dateRangeValidationRejectsFromAfterTo() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, get("/api/analytics/summary?from=2026-03-31&to=2026-03-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("'from' date cannot be after 'to' date")));
    }

    @Test
    void userIsolationGuaranteesDataPrivacy() throws Exception {
        TestUser alice = registerUser();
        TestUser bob = registerUser();

        // Alice adds income and expense
        mvc.perform(as(alice, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Alice Salary", "amount", 80000, "type", "INCOME", "category", "INCOME", "date", "2026-03-01"))))
                .andExpect(status().isOk());
        mvc.perform(as(alice, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Alice Rent", "amount", 20000, "type", "EXPENSE", "category", "HOUSING", "date", "2026-03-05"))))
                .andExpect(status().isOk());

        // Bob has no transactions
        mvc.perform(as(bob, get("/api/analytics/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIncome").value(0))
                .andExpect(jsonPath("$.totalExpenses").value(0))
                .andExpect(jsonPath("$.netCashFlow").value(0));

        // Alice's summary reflects her transactions
        mvc.perform(as(alice, get("/api/analytics/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIncome").value(80000.00))
                .andExpect(jsonPath("$.totalExpenses").value(20000.00))
                .andExpect(jsonPath("$.netCashFlow").value(60000.00))
                .andExpect(jsonPath("$.savingsRate").value(75.00))
                .andExpect(jsonPath("$.excludes", hasItems("TRANSFERS", "CARD_PAYMENTS", "INVESTMENTS")));
    }

    @Test
    void allAnalyticsEndpointsReturnConsistentContracts() throws Exception {
        TestUser user = registerUser();

        // Populate transactions with categories, transfers and refunds
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Salary", "amount", 50000, "type", "INCOME", "category", "INCOME", "date", "2026-03-01"))))
                .andExpect(status().isOk());
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Groceries", "amount", 5000, "type", "EXPENSE", "category", "GROCERIES", "date", "2026-03-04"))))
                .andExpect(status().isOk());
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Transfer Out", "amount", 10000, "type", "EXPENSE", "category", "TRANSFERS", "date", "2026-03-05"))))
                .andExpect(status().isOk());
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Store Refund", "amount", 500, "type", "INCOME", "category", "REFUNDS", "date", "2026-03-10"))))
                .andExpect(status().isOk());

        // 1. Summary
        mvc.perform(as(user, get("/api/analytics/summary?from=2026-03-01&to=2026-03-31")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalIncome").value(50000.00))
                .andExpect(jsonPath("$.grossExpenses").value(5000.00))
                .andExpect(jsonPath("$.totalRefunds").value(500.00))
                .andExpect(jsonPath("$.totalExpenses").value(4500.00))
                .andExpect(jsonPath("$.netCashFlow").value(45500.00))
                .andExpect(jsonPath("$.savingsRate").value(91.00))
                .andExpect(jsonPath("$.savingsRateApplicable").value(true))
                .andExpect(jsonPath("$.period.from").value("2026-03-01"))
                .andExpect(jsonPath("$.period.to").value("2026-03-31"));

        // 2. Monthly flows
        mvc.perform(as(user, get("/api/analytics/monthly")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].month").value("2026-03"))
                .andExpect(jsonPath("$[0].income").value(50000.00))
                .andExpect(jsonPath("$[0].expenses").value(4500.00));

        // 3. Category breakdown
        mvc.perform(as(user, get("/api/analytics/categories")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories", hasSize(1)))
                .andExpect(jsonPath("$.categories[0].category").value("GROCERIES"))
                .andExpect(jsonPath("$.categories[0].amount").value(5000.00))
                .andExpect(jsonPath("$.categories[0].percentage").value(100.00));

        // 4. Recurring
        mvc.perform(as(user, get("/api/analytics/recurring")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalMonthlyRecurring").exists());

        // 5. Data Quality
        mvc.perform(as(user, get("/api/analytics/data-quality")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions").value(4))
                .andExpect(jsonPath("$.totalMonthsCovered").value(1))
                .andExpect(jsonPath("$.hasSufficientHistory").value(false));

        // 6. Insights
        mvc.perform(as(user, get("/api/analytics/insights")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insights").isArray())
                .andExpect(jsonPath("$.unusualTransactions").isArray());
    }
}
