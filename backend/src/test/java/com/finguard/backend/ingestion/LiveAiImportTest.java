package com.finguard.backend.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.backend.support.IntegrationTest;
import com.finguard.backend.support.LiveAiService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole pipeline with nothing mocked: statement upload -> Spring Boot import -> HTTP -> the real
 * Python classifier -> database. Skipped when no Python environment exists locally
 * (see {@link LiveAiService}); required in CI.
 */
class LiveAiImportTest extends IntegrationTest {

    @BeforeAll
    static void requireLiveService() {
        var url = LiveAiService.url();
        if (LiveAiService.required()) {
            assertThat(url).as("FINGUARD_REQUIRE_LIVE_AI is set, so the AI service must be startable").isPresent();
        }
        assumeTrue(url.isPresent(), "No Python environment for the AI service (see LiveAiService)");
    }

    @DynamicPropertySource
    static void liveService(DynamicPropertyRegistry registry) {
        registry.add("ai.service.url", () -> LiveAiService.url().orElse("http://localhost:1"));
        registry.add("ai.service.internal-token", () -> LiveAiService.TOKEN);
        registry.add("ai.service.read-timeout", () -> "10s");
    }

    @Test
    void anImportedStatementIsCategorisedByTheRealClassifierWithProvenance() throws Exception {
        TestUser user = registerUser();

        String jobId = json.readTree(mvc.perform(as(user, multipart("/api/upload/csv").file(ImportApiTest.csv("hdfc.csv"))))
                        .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString())
                .get("jobId").asText();
        JsonNode[] job = new JsonNode[1];
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            job[0] = json.readTree(mvc.perform(as(user, get("/api/upload/status/" + jobId)))
                    .andReturn().getResponse().getContentAsString());
            assertThat(job[0].get("status").asText()).isEqualTo("COMPLETED");
        });

        assertThat(job[0].get("classificationStatus").asText()).isEqualTo("COMPLETED");
        assertThat(job[0].get("classifiedRows").asInt()).isEqualTo(2);

        for (JsonNode tx : json.readTree(mvc.perform(as(user, get("/api/transactions")))
                .andReturn().getResponse().getContentAsString())) {
            String title = tx.get("title").asText();
            if (title.contains("SWIGGY")) {
                assertThat(tx.get("category").asText()).isEqualTo("DINING");
                assertThat(tx.get("categorySource").asText()).isEqualTo("RULE");
                assertThat(tx.get("categoryRule").asText()).isEqualTo("dining.swiggy");
                assertThat(tx.get("categoryModelVersion").asText()).startsWith("rules-");
                assertThat(tx.get("categoryConfidenceCalibrated").asBoolean()).isFalse();
            } else if (title.contains("SALARY")) {
                assertThat(tx.get("category").asText()).isEqualTo("INCOME");
                assertThat(tx.get("categorySource").asText()).isEqualTo("RULE");
            } else {
                // "ATM WDL": the classifier abstains rather than guess where the cash went.
                assertThat(tx.get("category").isNull()).as(title).isTrue();
                assertThat(tx.get("categorySource").asText()).isEqualTo("NONE");
            }
        }
    }
}
