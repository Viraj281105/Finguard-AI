package com.finguard.backend.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.backend.ai.AiClient;
import com.finguard.backend.ai.AiPrediction;
import com.finguard.backend.ai.ClassifyOutcome;
import com.finguard.backend.ai.ClassifyRequest;
import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What import does with the AI service's answers: persistence and provenance, validation of what the
 * service says, graceful degradation, and the rule that a person's category is never overwritten.
 */
class ImportClassificationTest extends IntegrationTest {

    private static final String HDFC_HEADER =
            "Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance\n";

    @MockitoBean
    private AiClient aiClient;

    @BeforeEach
    void resetMock() {
        reset(aiClient);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static AiPrediction rule(String id, String category, boolean review) {
        return new AiPrediction(id, category, 0.9, false, "rule", "rules-1.0.0", "test." + category.toLowerCase(),
                review, "reason", List.of());
    }

    private static AiPrediction raw(String id, String category, String method) {
        return new AiPrediction(id, category, 0.9, false, method, "rules-1.0.0", null, false, "r", List.of());
    }

    private static AiPrediction abstain(String id) {
        return new AiPrediction(id, "UNCATEGORIZED", 0.0, false, "none", "rules-1.0.0", null, true, "no rule", List.of());
    }

    private static ClassifyOutcome answered(Map<String, AiPrediction> predictions) {
        return new ClassifyOutcome(predictions, 1, 0, 0);
    }

    /** Answers each item by the first keyword its description contains. */
    private void answerByKeyword(Function<ClassifyRequest.Item, AiPrediction> rules) {
        when(aiClient.classify(anyList())).thenAnswer(invocation -> {
            List<ClassifyRequest.Item> items = invocation.getArgument(0);
            Map<String, AiPrediction> out = new HashMap<>();
            for (ClassifyRequest.Item item : items) {
                AiPrediction p = rules.apply(item);
                if (p != null) {
                    out.put(item.id(), p);
                }
            }
            return answered(out);
        });
    }

    private JsonNode importFile(TestUser user, MockMultipartFile file) throws Exception {
        String jobId = json.readTree(mvc.perform(as(user, multipart("/api/upload/csv").file(file)))
                        .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString())
                .get("jobId").asText();
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            last[0] = json.readTree(mvc.perform(as(user, get("/api/upload/status/" + jobId)))
                    .andReturn().getResponse().getContentAsString());
            assertThat(last[0].get("status").asText()).isIn("COMPLETED", "FAILED");
        });
        return last[0];
    }

    private JsonNode transactions(TestUser user) throws Exception {
        return json.readTree(mvc.perform(as(user, get("/api/transactions")))
                .andReturn().getResponse().getContentAsString());
    }

    private static JsonNode byTitle(JsonNode transactions, String titlePart) {
        for (JsonNode tx : transactions) {
            if (tx.get("title").asText().contains(titlePart)) {
                return tx;
            }
        }
        throw new AssertionError("no transaction containing " + titlePart);
    }

    private static MockMultipartFile statement(String rows) {
        return new MockMultipartFile("file", "stmt.csv", "text/csv",
                (HDFC_HEADER + rows).getBytes(StandardCharsets.UTF_8));
    }

    // ---- persistence and provenance -----------------------------------------------------------

    @Test
    void predictedCategoriesAndTheirProvenanceArePersisted() throws Exception {
        answerByKeyword(item -> item.description().contains("SWIGGY") ? rule(item.id(), "DINING", true)
                : item.description().contains("SALARY") ? rule(item.id(), "INCOME", false)
                : abstain(item.id()));

        TestUser user = registerUser();
        JsonNode job = importFile(user, ImportApiTest.csv("hdfc.csv"));

        assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(job.get("classificationStatus").asText()).isEqualTo("COMPLETED");
        assertThat(job.get("classifiedRows").asInt()).isEqualTo(2);

        JsonNode all = transactions(user);
        assertThat(all).hasSize(3);

        JsonNode swiggy = byTitle(all, "SWIGGY");
        assertThat(swiggy.get("category").asText()).isEqualTo("DINING");
        assertThat(swiggy.get("categorySource").asText()).isEqualTo("RULE");
        assertThat(swiggy.get("categoryConfidence").asDouble()).isEqualTo(0.9);
        assertThat(swiggy.get("categoryConfidenceCalibrated").asBoolean()).isFalse();
        assertThat(swiggy.get("categoryNeedsReview").asBoolean()).isTrue();
        assertThat(swiggy.get("categoryModelVersion").asText()).isEqualTo("rules-1.0.0");
        assertThat(swiggy.get("categoryRule").asText()).isEqualTo("test.dining");

        JsonNode salary = byTitle(all, "SALARY");
        assertThat(salary.get("category").asText()).isEqualTo("INCOME");
        assertThat(salary.get("categoryNeedsReview").asBoolean()).isFalse();

        JsonNode atm = byTitle(all, "ATM");
        assertThat(atm.get("category").isNull()).isTrue();
        assertThat(atm.get("categorySource").asText()).isEqualTo("NONE");
        assertThat(atm.get("categoryConfidence").isNull()).isTrue();
        assertThat(atm.get("categoryRule").isNull()).isTrue();
    }

    @Test
    void theAiServiceIsToldTheDirectionAndAmountOfEachTransaction() throws Exception {
        answerByKeyword(item -> null);

        importFile(registerUser(), ImportApiTest.csv("hdfc.csv"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ClassifyRequest.Item>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiClient).classify(captor.capture());
        Map<String, ClassifyRequest.Item> byDescription = new HashMap<>();
        captor.getValue().forEach(i -> byDescription.put(i.description(), i));
        ClassifyRequest.Item swiggy = byDescription.get("UPI-SWIGGY-swiggy@icici");
        assertThat(swiggy.type()).isEqualTo("EXPENSE");
        assertThat(swiggy.amount()).isEqualByComparingTo("450.00");
        ClassifyRequest.Item salary = byDescription.get("NEFT-ACME LTD-SALARY MARCH");
        assertThat(salary.type()).isEqualTo("INCOME");
        assertThat(salary.amount()).isEqualByComparingTo("75000.00");
    }

    @Test
    void transfersRefundsCardPaymentsAndInvestmentsKeepTheirAmountsAndDirections() throws Exception {
        answerByKeyword(item -> {
            String d = item.description();
            return d.contains("SELF TRANSFER") ? rule(item.id(), "TRANSFERS", false)
                    : d.contains("CREDIT CARD") ? rule(item.id(), "CARD_PAYMENTS", false)
                    : d.contains("REFUND") ? rule(item.id(), "REFUNDS", false)
                    : d.contains("ZERODHA") ? rule(item.id(), "INVESTMENTS", false)
                    : null;
        });

        TestUser user = registerUser();
        importFile(user, statement("""
                01/03/26,SELF TRANSFER TO SAVINGS,R1,01/03/26,5000.00,,50000.00
                02/03/26,HDFC CREDIT CARD BILL PAYMENT,R2,02/03/26,18000.00,,32000.00
                03/03/26,REFUND FROM AMAZON,R3,03/03/26,,499.00,32499.00
                04/03/26,ZERODHA FUNDS,R4,04/03/26,10000.00,,22499.00
                """));

        JsonNode all = transactions(user);
        assertThat(all).hasSize(4);
        assertThat(byTitle(all, "SELF TRANSFER").get("category").asText()).isEqualTo("TRANSFERS");
        assertThat(byTitle(all, "CREDIT CARD").get("category").asText()).isEqualTo("CARD_PAYMENTS");
        assertThat(byTitle(all, "REFUND").get("category").asText()).isEqualTo("REFUNDS");
        assertThat(byTitle(all, "ZERODHA").get("category").asText()).isEqualTo("INVESTMENTS");
        // Classification never changes money or direction.
        assertThat(byTitle(all, "REFUND").get("type").asText()).isEqualTo("INCOME");
        assertThat(byTitle(all, "REFUND").get("amount").decimalValue()).isEqualByComparingTo("499.00");
        assertThat(byTitle(all, "CREDIT CARD").get("type").asText()).isEqualTo("EXPENSE");
        assertThat(byTitle(all, "CREDIT CARD").get("amount").decimalValue()).isEqualByComparingTo("18000.00");
    }

    // ---- never trust the AI service blindly ---------------------------------------------------

    @Test
    void predictionsThatFailValidationAreIgnored() throws Exception {
        when(aiClient.classify(anyList())).thenAnswer(invocation -> {
            List<ClassifyRequest.Item> items = invocation.getArgument(0);
            Map<String, AiPrediction> out = new HashMap<>();
            for (ClassifyRequest.Item item : items) {
                String d = item.description();
                if (d.contains("SWIGGY")) {
                    out.put(item.id(), raw(item.id(), "FOOD", "rule"));           // not in the taxonomy
                } else if (d.contains("SALARY")) {
                    out.put(item.id(), raw(item.id(), "GROCERIES", "rule"));      // an expense category for a credit
                } else {
                    out.put(item.id(), raw(item.id(), "UNCATEGORIZED", "rule"));  // automation must abstain, not assign
                }
            }
            return answered(out);
        });

        TestUser user = registerUser();
        JsonNode job = importFile(user, ImportApiTest.csv("hdfc.csv"));

        assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(job.get("classifiedRows").asInt()).isZero();
        for (JsonNode tx : transactions(user)) {
            assertThat(tx.get("category").isNull()).as(tx.get("title").asText()).isTrue();
            assertThat(tx.get("categorySource").asText()).isEqualTo("NONE");
        }
    }

    @Test
    void anUnknownMethodAndLegacyLabelsAreNotAccepted() throws Exception {
        answerByKeyword(item -> item.description().contains("SWIGGY") ? raw(item.id(), "DINING", "magic")
                : item.description().contains("SALARY") ? raw(item.id(), "Salary", "rule")  // legacy label from an AI
                : null);

        TestUser user = registerUser();
        importFile(user, ImportApiTest.csv("hdfc.csv"));

        for (JsonNode tx : transactions(user)) {
            assertThat(tx.get("category").isNull()).isTrue();
        }
    }

    // ---- reliability: classification can fail without compromising the import ----------------

    @Test
    void aFailingAiClientNeverFailsTheImport() throws Exception {
        when(aiClient.classify(anyList())).thenThrow(new IllegalStateException("boom"));

        TestUser user = registerUser();
        JsonNode job = importFile(user, ImportApiTest.csv("hdfc.csv"));

        assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(job.get("rowsProcessed").asInt()).isEqualTo(3);
        assertThat(job.get("classificationStatus").asText()).isEqualTo("UNAVAILABLE");
        assertThat(job.get("classifiedRows").asInt()).isZero();
        assertThat(transactions(user)).hasSize(3);
    }

    @Test
    void anUnavailableAiServiceLeavesRowsUncategorisedAndSaysSo() throws Exception {
        when(aiClient.classify(anyList())).thenReturn(new ClassifyOutcome(Map.of(), 1, 1, 0));

        TestUser user = registerUser();
        JsonNode job = importFile(user, ImportApiTest.csv("hdfc.csv"));

        assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(job.get("classificationStatus").asText()).isEqualTo("UNAVAILABLE");
        assertThat(transactions(user)).allSatisfy(tx -> assertThat(tx.get("category").isNull()).isTrue());
    }

    @Test
    void aPartialAnswerIsAppliedAndReportedAsPartial() throws Exception {
        when(aiClient.classify(anyList())).thenAnswer(invocation -> {
            List<ClassifyRequest.Item> items = invocation.getArgument(0);
            ClassifyRequest.Item swiggy = items.stream().filter(i -> i.description().contains("SWIGGY")).findFirst().orElseThrow();
            return new ClassifyOutcome(Map.of(swiggy.id(), rule(swiggy.id(), "DINING", false)), 2, 1, 1);
        });

        TestUser user = registerUser();
        JsonNode job = importFile(user, ImportApiTest.csv("hdfc.csv"));

        assertThat(job.get("classificationStatus").asText()).isEqualTo("PARTIAL");
        assertThat(job.get("classifiedRows").asInt()).isEqualTo(1);
        assertThat(byTitle(transactions(user), "SWIGGY").get("category").asText()).isEqualTo("DINING");
    }

    // ---- a person's category always wins -------------------------------------------------------

    @Test
    void aCategoryChosenWhileClassificationIsRunningIsNeverOverwritten() throws Exception {
        TestUser user = registerUser();
        when(aiClient.classify(anyList())).thenAnswer(invocation -> {
            List<ClassifyRequest.Item> items = invocation.getArgument(0);
            Map<String, AiPrediction> out = new HashMap<>();
            for (ClassifyRequest.Item item : items) {
                out.put(item.id(), rule(item.id(), item.type().equals("INCOME") ? "INCOME" : "DINING", false));
                if (item.description().contains("SWIGGY")) {
                    // The rows are already stored when classification runs, so the user can correct one now.
                    mvc.perform(as(user, patch("/api/transactions/" + item.id() + "/category"))
                                    .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"GROCERIES\"}"))
                            .andExpect(status().isOk());
                }
            }
            return answered(out);
        });

        importFile(user, ImportApiTest.csv("hdfc.csv"));

        JsonNode swiggy = byTitle(transactions(user), "SWIGGY");
        assertThat(swiggy.get("category").asText()).isEqualTo("GROCERIES");
        assertThat(swiggy.get("categorySource").asText()).isEqualTo("USER");
        assertThat(swiggy.get("categoryRule").isNull()).isTrue();
        assertThat(byTitle(transactions(user), "SALARY").get("categorySource").asText()).isEqualTo("RULE");
    }

    @Test
    void reImportingTheSameStatementKeepsUserCategoriesAndDoesNotCallTheAiServiceAgain() throws Exception {
        answerByKeyword(item -> rule(item.id(), item.type().equals("INCOME") ? "INCOME" : "DINING", false));
        TestUser user = registerUser();
        importFile(user, ImportApiTest.csv("hdfc.csv"));

        String swiggyId = byTitle(transactions(user), "SWIGGY").get("id").asText();
        mvc.perform(as(user, patch("/api/transactions/" + swiggyId + "/category"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"GROCERIES\"}"))
                .andExpect(status().isOk());

        JsonNode second = importFile(user, ImportApiTest.csv("hdfc.csv"));

        assertThat(second.get("duplicateRows").asInt()).isEqualTo(3);
        assertThat(second.get("rowsProcessed").asInt()).isZero();
        assertThat(second.get("classificationStatus").asText()).isEqualTo("NOT_ATTEMPTED");
        verify(aiClient, times(1)).classify(anyList()); // only the first import asked
        JsonNode swiggy = byTitle(transactions(user), "SWIGGY");
        assertThat(transactions(user)).hasSize(3);
        assertThat(swiggy.get("category").asText()).isEqualTo("GROCERIES");
        assertThat(swiggy.get("categorySource").asText()).isEqualTo("USER");
    }
}
