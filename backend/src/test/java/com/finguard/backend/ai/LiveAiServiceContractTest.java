package com.finguard.backend.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.backend.support.LiveAiService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Java client against the <em>real</em> Python service (not a mock), replaying the recorded
 * contract examples in {@code contracts/classify-examples.json}. Skipped when no Python environment
 * is available locally; in CI {@code FINGUARD_REQUIRE_LIVE_AI=true} turns a missing service into a failure.
 */
class LiveAiServiceContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static String baseUrl;

    @BeforeAll
    static void startService() {
        var url = LiveAiService.url();
        if (LiveAiService.required()) {
            assertThat(url).as("FINGUARD_REQUIRE_LIVE_AI is set, so the AI service must be startable").isPresent();
        }
        assumeTrue(url.isPresent(), "No Python environment for the AI service (see LiveAiService)");
        baseUrl = url.get();
    }

    private static AiClient client(String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        if (token != null) {
            builder.defaultHeader("X-Internal-Token", token);
        }
        return new AiClient(builder.build(),
                new AiProperties(baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(5), 200, "", Duration.ofSeconds(30)));
    }

    private static JsonNode examples() throws Exception {
        return MAPPER.readTree(Files.readString(Path.of("..", "contracts", "classify-examples.json")));
    }

    private static List<ClassifyRequest.Item> recordedItems(JsonNode request) {
        List<ClassifyRequest.Item> items = new ArrayList<>();
        for (JsonNode t : request.get("transactions")) {
            items.add(new ClassifyRequest.Item(t.get("id").asText(), t.get("description").asText(),
                    t.get("type").asText(), new BigDecimal(t.get("amount").asText())));
        }
        return items;
    }

    @Test
    void healthAdvertisesExactlyTheImplementedCapabilities() throws Exception {
        JsonNode health = MAPPER.readTree(RestClient.create(baseUrl).get().uri("/health").retrieve().body(String.class));

        assertThat(health.get("capabilities")).hasSize(1);
        assertThat(health.get("capabilities").get(0).asText()).isEqualTo("classify");
        assertThat(health.get("classification").get("ml_model").asBoolean()).isFalse();
        assertThat(health.get("classification").get("confidence_calibrated").asBoolean()).isFalse();
    }

    @Test
    void theClientParsesEveryRecordedResultFromTheRealService() throws Exception {
        JsonNode recorded = examples();

        ClassifyOutcome outcome = client(LiveAiService.TOKEN).classify(recordedItems(recorded.get("request")));

        assertThat(outcome.fullyAnswered()).isTrue();
        assertThat(outcome.predictions()).hasSize(recorded.get("response").get("results").size());
        for (JsonNode expected : recorded.get("response").get("results")) {
            AiPrediction actual = outcome.predictions().get(expected.get("id").asText());
            assertThat(actual).as(expected.get("id").asText()).isNotNull();
            assertThat(actual.category()).isEqualTo(expected.get("category").asText());
            assertThat(actual.method()).isEqualTo(expected.get("method").asText());
            assertThat(actual.confidence()).isEqualTo(expected.get("confidence").asDouble());
            assertThat(actual.confidenceCalibrated()).isEqualTo(expected.get("confidence_calibrated").asBoolean());
            assertThat(actual.needsReview()).isEqualTo(expected.get("needs_review").asBoolean());
            assertThat(actual.modelVersion()).isEqualTo(expected.get("model_version").asText());
            assertThat(actual.alternatives()).hasSize(expected.get("alternatives").size());
        }
    }

    @Test
    void theServiceRefusesAMissingOrWrongSharedSecretAndTheClientDegrades() throws Exception {
        List<ClassifyRequest.Item> items = recordedItems(examples().get("request"));

        assertThat(client(null).classify(items).unavailable()).isTrue();
        assertThat(client("wrong-token").classify(items).unavailable()).isTrue();
    }

    @Test
    void aRequestTheServiceRejectsAsInvalidDegradesInsteadOfThrowing() {
        // An amount of -1 is a validation error (422) on the Python side.
        List<ClassifyRequest.Item> items = List.of(
                new ClassifyRequest.Item("bad", "ZOMATO", "EXPENSE", new BigDecimal("-1")));

        ClassifyOutcome outcome = client(LiveAiService.TOKEN).classify(items);

        assertThat(outcome.predictions()).isEmpty();
        assertThat(outcome.batchesFailed()).isEqualTo(1);
    }
}
