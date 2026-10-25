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

class GoalApiTest extends IntegrationTest {

    @Test
    @DisplayName("Unauthenticated requests return 401 Unauthorized")
    void unauthenticatedRequestsReturn401() throws Exception {
        mvc.perform(get("/api/goals")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/goals").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Test Goal", "targetAmount", 50000))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Goal lifecycle, contributions, completion trigger, and user isolation")
    void goalLifecycleAndContributions() throws Exception {
        TestUser alice = registerUser();
        TestUser bob = registerUser();

        // 1. Alice creates savings goal
        var res1 = mvc.perform(as(alice, post("/api/goals")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "name", "Emergency Fund",
                        "description", "3-6 months runway",
                        "targetAmount", 50000,
                        "targetDate", "2026-12-31"
                ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Emergency Fund"))
                .andExpect(jsonPath("$.targetAmount").value(50000.00))
                .andExpect(jsonPath("$.currentAmount").value(0.00))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        String goalId = json.readTree(res1.getResponse().getContentAsString()).get("id").asText();

        // 2. Bob lists goals -> empty
        mvc.perform(as(bob, get("/api/goals")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 3. Bob attempts to access Alice's goal -> 404 Not Found
        mvc.perform(as(bob, get("/api/goals/" + goalId)))
                .andExpect(status().isNotFound());

        // 4. Bob attempts to contribute to Alice's goal -> 404 Not Found
        mvc.perform(as(bob, post("/api/goals/" + goalId + "/contributions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("amount", 10000))))
                .andExpect(status().isNotFound());

        // 5. Alice records first contribution
        var resContrib1 = mvc.perform(as(alice, post("/api/goals/" + goalId + "/contributions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "amount", 20000,
                        "note", "First bonus deposit",
                        "contributionDate", "2026-10-15"
                ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.amount").value(20000.00))
                .andReturn();
        String contrib1Id = json.readTree(resContrib1.getResponse().getContentAsString()).get("id").asText();

        // 6. Alice checks goal detail: progress = 40%, remaining = 30000, disclaimer present
        mvc.perform(as(alice, get("/api/goals/" + goalId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal.currentAmount").value(20000.00))
                .andExpect(jsonPath("$.goal.remainingAmount").value(30000.00))
                .andExpect(jsonPath("$.goal.progressPercentage").value(40.00))
                .andExpect(jsonPath("$.goal.status").value("ACTIVE"))
                .andExpect(jsonPath("$.contributions", hasSize(1)))
                .andExpect(jsonPath("$.disclaimer").value(containsString("do not represent verified bank balances")));

        // 7. Alice records second contribution that completes the goal (30,000)
        var resContrib2 = mvc.perform(as(alice, post("/api/goals/" + goalId + "/contributions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("amount", 30000, "note", "Final target reached"))))
                .andExpect(status().isCreated())
                .andReturn();
        String contrib2Id = json.readTree(resContrib2.getResponse().getContentAsString()).get("id").asText();

        // Goal status becomes COMPLETED
        mvc.perform(as(alice, get("/api/goals/" + goalId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal.currentAmount").value(50000.00))
                .andExpect(jsonPath("$.goal.remainingAmount").value(0.00))
                .andExpect(jsonPath("$.goal.progressPercentage").value(100.00))
                .andExpect(jsonPath("$.goal.status").value("COMPLETED"));

        // 8. Alice deletes the second contribution -> status reverts to ACTIVE
        mvc.perform(as(alice, delete("/api/goals/" + goalId + "/contributions/" + contrib2Id)))
                .andExpect(status().isNoContent());

        mvc.perform(as(alice, get("/api/goals/" + goalId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal.currentAmount").value(20000.00))
                .andExpect(jsonPath("$.goal.status").value("ACTIVE"));

        // 9. Alice deletes the goal
        mvc.perform(as(alice, delete("/api/goals/" + goalId)))
                .andExpect(status().isNoContent());

        mvc.perform(as(alice, get("/api/goals/" + goalId)))
                .andExpect(status().isNotFound());
    }
}
