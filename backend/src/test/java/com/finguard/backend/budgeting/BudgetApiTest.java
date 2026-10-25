package com.finguard.backend.budgeting;

import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BudgetApiTest extends IntegrationTest {

    @Test
    @DisplayName("Unauthenticated requests return 401 Unauthorized")
    void unauthenticatedRequestsReturn401() throws Exception {
        mvc.perform(get("/api/budgets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/budgets/overview")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/budgets").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("yearMonth", "2026-10", "amount", 5000))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Create, list, update and delete budget with full user isolation")
    void budgetLifecycleAndOwnership() throws Exception {
        TestUser alice = registerUser();
        TestUser bob = registerUser();

        // 1. Alice creates category budget for DINING
        var res = mvc.perform(as(alice, post("/api/budgets")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "yearMonth", "2026-10",
                        "category", "DINING",
                        "amount", 6000
                ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.yearMonth").value("2026-10"))
                .andExpect(jsonPath("$.category").value("DINING"))
                .andExpect(jsonPath("$.amount").value(6000.00))
                .andReturn();
        String budgetId = json.readTree(res.getResponse().getContentAsString()).get("id").asText();

        // 2. Alice creates overall monthly budget
        mvc.perform(as(alice, post("/api/budgets")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "yearMonth", "2026-10",
                        "amount", 40000
                ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").isEmpty())
                .andExpect(jsonPath("$.amount").value(40000.00));

        // 3. Duplicate budget for same category and month returns 409 Conflict
        mvc.perform(as(alice, post("/api/budgets")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "yearMonth", "2026-10",
                        "category", "DINING",
                        "amount", 7000
                ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("already exists")));

        // 4. Bob lists budgets -> returns empty
        mvc.perform(as(bob, get("/api/budgets?month=2026-10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 5. Bob tries to update Alice's budget -> 404 Not Found
        mvc.perform(as(bob, put("/api/budgets/" + budgetId)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("amount", 9000))))
                .andExpect(status().isNotFound());

        // 6. Alice updates budget amount -> 200 OK
        mvc.perform(as(alice, put("/api/budgets/" + budgetId)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("amount", 8500))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(8500.00));

        // 7. Non-spending category rejected with 400 Bad Request
        mvc.perform(as(alice, post("/api/budgets")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "yearMonth", "2026-10",
                        "category", "TRANSFERS",
                        "amount", 10000
                ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Non-spending category")));

        // 8. Bob tries to delete Alice's budget -> 404 Not Found
        mvc.perform(as(bob, delete("/api/budgets/" + budgetId)))
                .andExpect(status().isNotFound());

        // 9. Alice deletes budget -> 204 No Content
        mvc.perform(as(alice, delete("/api/budgets/" + budgetId)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Monthly overview reflects actual transactions, refunds and unbudgeted categories")
    void monthlyOverviewCalculation() throws Exception {
        TestUser user = registerUser();

        // 1. Create budgets: Overall 25,000, DINING 5,000
        mvc.perform(as(user, post("/api/budgets")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("yearMonth", "2026-10", "amount", 25000))))
                .andExpect(status().isCreated());

        mvc.perform(as(user, post("/api/budgets")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("yearMonth", "2026-10", "category", "DINING", "amount", 5000))))
                .andExpect(status().isCreated());

        // 2. Add transactions:
        // DINING expense 4000
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "title", "Restaurant Dinner",
                        "amount", 4000,
                        "type", "EXPENSE",
                        "category", "DINING",
                        "date", "2026-10-10"
                )))).andExpect(status().isOk());

        // DINING refund credit 500 (net DINING = 3500)
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "title", "Dinner Refund",
                        "amount", 500,
                        "type", "INCOME",
                        "category", "REFUNDS",
                        "date", "2026-10-12"
                )))).andExpect(status().isOk());

        // Unbudgeted SHOPPING expense 2000
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "title", "Clothes",
                        "amount", 2000,
                        "type", "EXPENSE",
                        "category", "SHOPPING",
                        "date", "2026-10-14"
                )))).andExpect(status().isOk());

        // 3. Fetch overview
        mvc.perform(as(user, get("/api/budgets/overview?month=2026-10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yearMonth").value("2026-10"))
                .andExpect(jsonPath("$.overallBudget.budgetedAmount").value(25000.00))
                .andExpect(jsonPath("$.totalActualExpenses").value(5500.00)) // (4000 + 2000) - 500 = 5500
                .andExpect(jsonPath("$.overallBudget.remainingAmount").value(19500.00))
                .andExpect(jsonPath("$.overallBudget.percentageUsed").value(22.00)) // 5500 / 25000 = 22%
                .andExpect(jsonPath("$.overallBudget.status").value("UNDER_BUDGET"))
                .andExpect(jsonPath("$.categoryBudgets", hasSize(1)))
                .andExpect(jsonPath("$.categoryBudgets[0].category").value("DINING"))
                .andExpect(jsonPath("$.categoryBudgets[0].actualSpend").value(4000.00))
                .andExpect(jsonPath("$.categoryBudgets[0].remainingAmount").value(1000.00))
                .andExpect(jsonPath("$.categoryBudgets[0].percentageUsed").value(80.00))
                .andExpect(jsonPath("$.categoryBudgets[0].status").value("APPROACHING_LIMIT"))
                .andExpect(jsonPath("$.unbudgetedSpending").value(2000.00));
    }
}
