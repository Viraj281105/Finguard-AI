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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Saved scenarios over HTTP with the clock fixed at 2026-07-15 and SYNTHETIC ledgers. */
@Import(SavedScenarioApiTest.FixedClock.class)
class SavedScenarioApiTest extends IntegrationTest {

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

    private void sixSteadyMonths(TestUser user) throws Exception {
        for (int m = 1; m <= 6; m++) {
            YearMonth ym = YearMonth.of(2026, m);
            String tag = ym.toString().replace("-", "");
            add(user, ym.atDay(1).toString(), "INCOME", "INCOME", 50000, "salary" + tag);
            add(user, ym.atDay(3).toString(), "EXPENSE", "HOUSING", 20000, "landlord" + tag);
            add(user, ym.atDay(15).toString(), "EXPENSE", "GROCERIES", 10000, "market" + tag);
        }
    }

    private JsonNode call(TestUser user, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, int expected) throws Exception {
        String body = mvc.perform(as(user, req)).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private JsonNode save(TestUser user, Map<String, Object> body, int expected) throws Exception {
        return call(user, post("/api/scenarios").contentType(MediaType.APPLICATION_JSON).content(json(body)), expected);
    }

    private static Map<String, Object> groceriesCut(String name) {
        return Map.of("name", name, "horizonMonths", 3,
                "categoryAdjustments", List.of(Map.of("category", "GROCERIES", "type", "PERCENTAGE", "value", -20)));
    }

    @Test
    void endpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/scenarios")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/scenarios").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/scenarios/x/rerun")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/scenarios/x")).andExpect(status().isUnauthorized());
    }

    @Test
    void saveListAndReadBack() throws Exception {
        TestUser user = registerUser();
        sixSteadyMonths(user);

        JsonNode saved = save(user, groceriesCut("Cut groceries"), 201);
        String id = saved.get("summary").get("id").asText();
        assertThat(saved.get("summary").get("name").asText()).isEqualTo("Cut groceries");
        assertThat(saved.get("summary").get("methodVersion").asText()).isEqualTo("LEVEL_DAMPED_TREND 2.0.0");
        assertThat(saved.get("summary").get("netChange").decimalValue()).isEqualByComparingTo("6000");
        assertThat(saved.get("snapshot").get("hypothetical").asBoolean()).isTrue();

        JsonNode list = call(user, get("/api/scenarios"), 200);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asText()).isEqualTo(id);

        JsonNode detail = call(user, get("/api/scenarios/" + id), 200);
        assertThat(detail.get("request").get("categoryAdjustments").get(0).get("category").asText()).isEqualTo("GROCERIES");
        assertThat(detail.get("snapshot").get("delta").get("net").decimalValue()).isEqualByComparingTo("6000");
    }

    @Test
    void aSavedScenarioReproducesWhileTheDataIsUnchangedAndReportsChangeWhenItIsNot() throws Exception {
        TestUser user = registerUser();
        sixSteadyMonths(user);
        String id = save(user, groceriesCut("Cut groceries"), 201).get("summary").get("id").asText();

        JsonNode same = call(user, post("/api/scenarios/" + id + "/rerun"), 200);
        assertThat(same.get("unchanged").asBoolean()).isTrue();
        assertThat(same.get("methodVersionChanged").asBoolean()).isFalse();
        assertThat(same.get("current").toString()).isEqualTo(same.get("snapshot").toString());

        // new data in a past month changes the baseline: the snapshot stays as saved, the fresh result differs
        add(user, "2026-06-20", "EXPENSE", "GROCERIES", 6000, "extra shop");
        JsonNode changed = call(user, post("/api/scenarios/" + id + "/rerun"), 200);
        assertThat(changed.get("unchanged").asBoolean()).isFalse();
        assertThat(changed.get("snapshot").get("delta").get("net").decimalValue()).isEqualByComparingTo("6000");
        assertThat(changed.get("current").get("baseline").get("expenses").decimalValue())
                .isNotEqualByComparingTo(changed.get("snapshot").get("baseline").get("expenses").decimalValue());
        // and the stored snapshot itself was not overwritten
        assertThat(call(user, get("/api/scenarios/" + id), 200).get("snapshot").get("delta").get("net").decimalValue())
                .isEqualByComparingTo("6000");
    }

    @Test
    void validationAndUnavailableBaselines() throws Exception {
        TestUser user = registerUser();
        // no history: nothing to save
        assertThat(save(user, Map.of("name", "x", "horizonMonths", 3), 400).get("message").asText()).contains("cannot be saved");
        sixSteadyMonths(user);
        assertThat(save(user, Map.of("horizonMonths", 3), 400).get("message").asText()).contains("needs a name");
        assertThat(save(user, Map.of("name", "   ", "horizonMonths", 3), 400).get("message").asText()).contains("needs a name");
        // invalid adjustments are rejected exactly as in simulate
        assertThat(save(user, Map.of("name", "bad", "incomeAdjustment", Map.of("type", "PERCENTAGE", "value", 900)), 400)
                .get("message").asText()).contains("between -100 and 500");
        assertThat(call(user, get("/api/scenarios"), 200)).isEmpty();
    }

    @Test
    void scenariosBelongToTheirOwnerOnly() throws Exception {
        TestUser owner = registerUser();
        TestUser other = registerUser();
        sixSteadyMonths(owner);
        String id = save(owner, groceriesCut("Mine"), 201).get("summary").get("id").asText();

        assertThat(call(other, get("/api/scenarios"), 200)).isEmpty();
        call(other, get("/api/scenarios/" + id), 404);
        call(other, post("/api/scenarios/" + id + "/rerun"), 404);
        call(other, delete("/api/scenarios/" + id), 404);
        assertThat(call(owner, get("/api/scenarios"), 200)).hasSize(1);
    }

    @Test
    void deleting() throws Exception {
        TestUser user = registerUser();
        sixSteadyMonths(user);
        String id = save(user, groceriesCut("Temp"), 201).get("summary").get("id").asText();
        call(user, delete("/api/scenarios/" + id), 204);
        call(user, get("/api/scenarios/" + id), 404);
        assertThat(call(user, get("/api/scenarios"), 200)).isEmpty();
    }

    @Test
    void savingDoesNotChangeTheLedger() throws Exception {
        TestUser user = registerUser();
        sixSteadyMonths(user);
        String before = call(user, get("/api/transactions?size=100"), 200).toString();
        save(user, groceriesCut("Keep ledger"), 201);
        assertThat(call(user, get("/api/transactions?size=100"), 200).toString()).isEqualTo(before);
    }
}
