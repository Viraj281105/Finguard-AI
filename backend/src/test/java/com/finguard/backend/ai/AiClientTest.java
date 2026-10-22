package com.finguard.backend.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class AiClientTest {

    private static final String URL = "http://ai.test/v1/classify";

    private MockRestServiceServer server;
    private AiClient client;

    @BeforeEach
    void setUp() {
        client = clientWithBatchSize(2, Duration.ofSeconds(90));
    }

    private AiClient clientWithBatchSize(int batchSize, Duration budget) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        server = MockRestServiceServer.bindTo(builder).build();
        AiProperties properties = new AiProperties("http://ai.test", Duration.ofSeconds(1), Duration.ofSeconds(1),
                batchSize, "", budget);
        return new AiClient(builder.build(), properties);
    }

    private static ClassifyRequest.Item item(String id, String description) {
        return new ClassifyRequest.Item(id, description, "EXPENSE", new BigDecimal("10.50"));
    }

    private static String result(String id, String category, String extra) {
        return """
                {"id":"%s","category":"%s","confidence":0.9,"confidence_calibrated":false,"method":"rule",\
                "model_version":"rules-1.0.0","rule_id":"x.y","needs_review":false,"reason":"r","alternatives":[]%s}"""
                .formatted(id, category, extra);
    }

    private static String body(String... results) {
        return "{\"model_version\":\"rules-1.0.0\",\"results\":[" + String.join(",", results) + "]}";
    }

    @Test
    void sendsIdDescriptionDirectionAndAmountToTheVersionedEndpointAndMapsTheFullResult() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.transactions[0].id").value("t1"))
                .andExpect(jsonPath("$.transactions[0].description").value("SWIGGY"))
                .andExpect(jsonPath("$.transactions[0].type").value("EXPENSE"))
                .andExpect(jsonPath("$.transactions[0].amount").value(10.5))
                .andRespond(withSuccess(body(result("t1", "DINING", "")), MediaType.APPLICATION_JSON));

        ClassifyOutcome outcome = client.classify(List.of(item("t1", "SWIGGY")));

        assertThat(outcome.fullyAnswered()).isTrue();
        AiPrediction p = outcome.predictions().get("t1");
        assertThat(p.category()).isEqualTo("DINING");
        assertThat(p.confidence()).isEqualTo(0.9);
        assertThat(p.confidenceCalibrated()).isFalse();
        assertThat(p.method()).isEqualTo("rule");
        assertThat(p.modelVersion()).isEqualTo("rules-1.0.0");
        assertThat(p.ruleId()).isEqualTo("x.y");
        assertThat(p.needsReview()).isFalse();
        assertThat(p.abstained()).isFalse();
        server.verify();
    }

    @Test
    void unknownFieldsInTheResponseAreIgnoredSoTheServiceCanEvolve() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                body(result("t1", "DINING", ",\"future_field\":{\"a\":1}")), MediaType.APPLICATION_JSON));

        assertThat(client.classify(List.of(item("t1", "x"))).predictions()).containsKey("t1");
    }

    @Test
    void overlongDescriptionsAreTruncatedInsteadOfRejectedByTheService() {
        assertThat(new ClassifyRequest.Item("1", "x".repeat(5000), "EXPENSE", null).description())
                .hasSize(ClassifyRequest.Item.MAX_DESCRIPTION);
        assertThat(new ClassifyRequest.Item("1", null, "EXPENSE", null).description()).isEmpty();
    }

    @Test
    void splitsLargeInputsIntoBatches() {
        server.expect(times(3), requestTo(URL))
                .andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));

        ClassifyOutcome outcome = client.classify(
                List.of(item("1", "a"), item("2", "b"), item("3", "c"), item("4", "d"), item("5", "e")));

        server.verify(); // 5 items with batch size 2 -> exactly 3 calls
        assertThat(outcome.batchesTotal()).isEqualTo(3);
    }

    @Test
    void aFailingBatchDoesNotLoseTheOtherBatches() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withSuccess(body(result("3", "HOUSING", "")),
                MediaType.APPLICATION_JSON));

        ClassifyOutcome outcome = client.classify(List.of(item("1", "a"), item("2", "b"), item("3", "c")));

        assertThat(outcome.predictions()).containsOnlyKeys("3");
        assertThat(outcome.batchesFailed()).isEqualTo(1);
        assertThat(outcome.fullyAnswered()).isFalse();
        assertThat(outcome.unavailable()).isFalse();
    }

    @Test
    void serverErrorYieldsAnUnavailableOutcomeInsteadOfAnException() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        ClassifyOutcome outcome = client.classify(List.of(item("1", "a")));

        assertThat(outcome.predictions()).isEmpty();
        assertThat(outcome.unavailable()).isTrue();
    }

    @Test
    void unauthorizedIsHandledLikeAnyOtherFailure() {
        server.expect(requestTo(URL)).andRespond(withUnauthorizedRequest());
        assertThat(client.classify(List.of(item("1", "a"))).unavailable()).isTrue();
    }

    @Test
    void malformedBodyYieldsAnUnavailableOutcome() {
        server.expect(requestTo(URL)).andRespond(withSuccess("this is not json", MediaType.APPLICATION_JSON));
        assertThat(client.classify(List.of(item("1", "a"))).unavailable()).isTrue();
    }

    @Test
    void aBodyWithoutResultsIsTreatedAsAFailedBatch() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"model_version\":\"x\"}", MediaType.APPLICATION_JSON));
        assertThat(client.classify(List.of(item("1", "a"))).batchesFailed()).isEqualTo(1);
    }

    @Test
    void invalidPredictionsAreDroppedAndValidOnesKept() {
        server.expect(requestTo(URL)).andRespond(withSuccess(body(
                result("1", "DINING", ""),
                result("1", "TRANSPORT", ""),                      // duplicate id: first wins
                result("zzz", "DINING", ""),                       // id the client never sent
                result("2", "  ", ""),                             // blank category
                "{\"id\":\"2\",\"category\":\"DINING\",\"confidence\":1.5,\"method\":\"rule\"}",     // out of range
                "{\"id\":\"2\",\"category\":\"DINING\",\"confidence\":-0.1,\"method\":\"rule\"}",    // negative
                "{\"id\":\"2\",\"category\":\"DINING\",\"confidence\":0.5,\"method\":\"magic\"}",    // unknown method
                "{\"id\":\"2\",\"category\":\"DINING\",\"confidence\":0.5}",                          // no method
                "{\"category\":\"DINING\",\"confidence\":0.5,\"method\":\"rule\"}",                   // no id
                "null"), MediaType.APPLICATION_JSON));

        ClassifyOutcome outcome = client.classify(List.of(item("1", "a"), item("2", "b")));

        assertThat(outcome.predictions()).containsOnlyKeys("1");
        assertThat(outcome.predictions().get("1").category()).isEqualTo("DINING");
        assertThat(outcome.fullyAnswered()).isTrue(); // the batch itself was answered
    }

    @Test
    void abstentionsAreReturnedSoCallersCanTellAnsweredFromUnanswered() {
        server.expect(requestTo(URL)).andRespond(withSuccess(body(
                "{\"id\":\"1\",\"category\":\"UNCATEGORIZED\",\"confidence\":0,\"method\":\"none\"}"),
                MediaType.APPLICATION_JSON));

        AiPrediction p = client.classify(List.of(item("1", "a"))).predictions().get("1");

        assertThat(p.abstained()).isTrue();
    }

    @Test
    void stopsCallingAfterTwoConsecutiveFailedBatchesAndReportsTheRest() {
        server.expect(times(2), requestTo(URL)).andRespond(withServerError());

        ClassifyOutcome outcome = client.classify(List.of(
                item("1", "a"), item("2", "b"), item("3", "c"), item("4", "d"), item("5", "e"), item("6", "f")));

        server.verify(); // batches 1 and 2 were tried, batch 3 was not
        assertThat(outcome.batchesTotal()).isEqualTo(3);
        assertThat(outcome.batchesFailed()).isEqualTo(2);
        assertThat(outcome.itemsNotAttempted()).isEqualTo(2);
        assertThat(outcome.unavailable()).isTrue();
    }

    @Test
    void aSuccessResetsTheConsecutiveFailureCount() {
        client = clientWithBatchSize(1, Duration.ofSeconds(90));
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withSuccess(body(result("2", "DINING", "")), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withSuccess(body(result("4", "DINING", "")), MediaType.APPLICATION_JSON));

        ClassifyOutcome outcome = client.classify(List.of(item("1", "a"), item("2", "b"), item("3", "c"), item("4", "d")));

        server.verify();
        assertThat(outcome.predictions()).containsOnlyKeys("2", "4");
        assertThat(outcome.itemsNotAttempted()).isZero();
    }

    @Test
    void anExhaustedTimeBudgetStopsFurtherBatches() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        server = MockRestServiceServer.bindTo(builder).build();
        AiProperties properties = new AiProperties("http://ai.test", Duration.ofSeconds(1), Duration.ofSeconds(1),
                1, "", Duration.ofSeconds(10));
        // A fake clock: the first two readings (start of the call, before batch 1) are at t=0; every
        // later reading is a minute on, i.e. the 10 s budget is spent when batch 2 is about to start.
        int[] readings = {0};
        AiClient slow = new AiClient(builder.build(), properties,
                () -> ++readings[0] <= 2 ? 0L : Duration.ofMinutes(1).toNanos());
        server.expect(requestTo(URL)).andRespond(withSuccess(body(result("1", "DINING", "")), MediaType.APPLICATION_JSON));

        ClassifyOutcome outcome = slow.classify(List.of(item("1", "a"), item("2", "b"), item("3", "c")));

        server.verify(); // only the first batch was sent
        assertThat(outcome.predictions()).containsOnlyKeys("1");
        assertThat(outcome.itemsNotAttempted()).isEqualTo(2);
    }

    @Test
    void blankNullAndRepeatedIdsInTheInputAreIgnored() {
        server.expect(requestTo(URL)).andRespond(withSuccess(body(result("1", "DINING", "")), MediaType.APPLICATION_JSON));

        ClassifyOutcome outcome = client.classify(java.util.Arrays.asList(
                item("1", "a"), item("1", "again"), item(" ", "blank"), item(null, "null"), null));

        assertThat(outcome.batchesTotal()).isEqualTo(1);
        assertThat(outcome.predictions()).containsOnlyKeys("1");
        server.verify();
    }

    @Test
    void emptyInputMakesNoCall() {
        assertThat(client.classify(List.of()).predictions()).isEmpty();
        assertThat(client.classify(null).predictions()).isEmpty();
        server.verify();
    }

    @Test
    void propertiesRejectInvalidSettings() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AiProperties("u", Duration.ofSeconds(1),
                Duration.ofSeconds(1), 0, "", Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AiProperties("u", Duration.ofSeconds(1),
                Duration.ofSeconds(1), 501, "", Duration.ofSeconds(1))).isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AiProperties("u", Duration.ofSeconds(1),
                Duration.ofSeconds(1), 10, "", Duration.ZERO)).isInstanceOf(IllegalStateException.class);
    }
}
