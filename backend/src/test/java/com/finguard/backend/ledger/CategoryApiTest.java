package com.finguard.backend.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.backend.ai.AiClient;
import com.finguard.backend.ai.AiPrediction;
import com.finguard.backend.ai.ClassifyOutcome;
import com.finguard.backend.ai.ClassifyRequest;
import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Manual category correction, listing filters and re-classification, on a real database. */
class CategoryApiTest extends IntegrationTest {

    @MockitoBean
    private AiClient aiClient;

    // ---- helpers ------------------------------------------------------------------------------

    private String createTransaction(TestUser user, String title, String type, String category) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", title, "amount", 100, "type", type));
        if (category != null) {
            body.put("category", category);
        }
        return json.readTree(mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode setCategory(TestUser user, String id, String category) throws Exception {
        return json.readTree(mvc.perform(as(user, patch("/api/transactions/" + id + "/category"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("category", category))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode fetch(TestUser user, String id) throws Exception {
        return json.readTree(mvc.perform(as(user, get("/api/transactions/" + id)))
                .andReturn().getResponse().getContentAsString());
    }

    private static AiPrediction rule(String id, String category, boolean review) {
        return new AiPrediction(id, category, 0.75, false, "rule", "rules-1.0.0", "t." + category.toLowerCase(),
                review, "reason", List.of());
    }

    private void aiAnswers(String category) {
        when(aiClient.classify(anyList())).thenAnswer(invocation -> {
            List<ClassifyRequest.Item> items = invocation.getArgument(0);
            Map<String, AiPrediction> out = new HashMap<>();
            items.forEach(i -> out.put(i.id(), rule(i.id(), category, false)));
            return new ClassifyOutcome(out, 1, 0, 0);
        });
    }

    // ---- user correction ----------------------------------------------------------------------

    @Test
    void aUserCorrectionIsRecordedAsUserAndPersists() throws Exception {
        TestUser user = registerUser();
        String id = createTransaction(user, "Zepto order", "EXPENSE", null);

        JsonNode updated = setCategory(user, id, "GROCERIES");

        assertThat(updated.get("category").asText()).isEqualTo("GROCERIES");
        assertThat(updated.get("categorySource").asText()).isEqualTo("USER");
        assertThat(updated.get("categoryNeedsReview").asBoolean()).isFalse();
        assertThat(updated.get("categoryConfidence").isNull()).isTrue();
        JsonNode reloaded = fetch(user, id);
        assertThat(reloaded.get("category").asText()).isEqualTo("GROCERIES");
        assertThat(reloaded.get("categorySource").asText()).isEqualTo("USER");
    }

    @Test
    void aUserCorrectionReplacesAnAutomaticCategoryAndItsProvenance() throws Exception {
        TestUser user = registerUser();
        String id = createTransaction(user, "Swiggy", "EXPENSE", null);
        aiAnswers("DINING");
        mvc.perform(as(user, post("/api/transactions/reclassify"))).andExpect(status().isOk());
        assertThat(fetch(user, id).get("categorySource").asText()).isEqualTo("RULE");
        assertThat(fetch(user, id).get("categoryRule").asText()).isEqualTo("t.dining");

        JsonNode updated = setCategory(user, id, "groceries");  // any letter case

        assertThat(updated.get("category").asText()).isEqualTo("GROCERIES");
        assertThat(updated.get("categorySource").asText()).isEqualTo("USER");
        assertThat(updated.get("categoryRule").isNull()).isTrue();
        assertThat(updated.get("categoryModelVersion").isNull()).isTrue();
        assertThat(updated.get("categoryConfidence").isNull()).isTrue();
    }

    @Test
    void legacyLabelsAreAcceptedAndNormalised() throws Exception {
        TestUser user = registerUser();
        String id = createTransaction(user, "Lunch", "EXPENSE", null);

        assertThat(setCategory(user, id, "Food").get("category").asText()).isEqualTo("DINING");
        assertThat(setCategory(user, id, "EMI").get("category").asText()).isEqualTo("LOAN_PAYMENTS");
        assertThat(setCategory(user, id, "Other").get("category").asText()).isEqualTo("UNCATEGORIZED");
        assertThat(fetch(user, id).get("categorySource").asText()).isEqualTo("USER");
    }

    @Test
    void unknownBlankAndMissingCategoriesAreRejected() throws Exception {
        TestUser user = registerUser();
        String id = createTransaction(user, "Lunch", "EXPENSE", "DINING");

        for (String body : List.of("{\"category\":\"NOPE\"}", "{\"category\":\"\"}", "{\"category\":\"   \"}", "{}")) {
            mvc.perform(as(user, patch("/api/transactions/" + id + "/category"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(as(user, patch("/api/transactions/" + id + "/category"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"NOPE\"}"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Valid categories")));
        assertThat(fetch(user, id).get("category").asText()).isEqualTo("DINING");  // unchanged
    }

    @Test
    void anotherUsersTransactionCannotBeCategorisedAndIsIndistinguishableFromMissing() throws Exception {
        TestUser owner = registerUser();
        TestUser intruder = registerUser();
        String id = createTransaction(owner, "Rent", "EXPENSE", "HOUSING");

        mvc.perform(as(intruder, patch("/api/transactions/" + id + "/category"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"FEES\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(as(intruder, patch("/api/transactions/does-not-exist/category"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"FEES\"}"))
                .andExpect(status().isNotFound());

        assertThat(fetch(owner, id).get("category").asText()).isEqualTo("HOUSING");
    }

    @Test
    void categoryEndpointsRequireAuthentication() throws Exception {
        mvc.perform(patch("/api/transactions/x/category").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"FEES\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/transactions/reclassify")).andExpect(status().isUnauthorized());
    }

    // ---- create / update semantics ------------------------------------------------------------

    @Test
    void createAndUpdateValidateAndNormaliseCategories() throws Exception {
        TestUser user = registerUser();

        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "x", "amount", 5, "type", "EXPENSE", "category", "Nonsense"))))
                .andExpect(status().isBadRequest());

        String id = createTransaction(user, "Pay", "INCOME", "Salary");
        JsonNode created = fetch(user, id);
        assertThat(created.get("category").asText()).isEqualTo("INCOME");
        assertThat(created.get("categorySource").asText()).isEqualTo("USER");

        // PUT without a category keeps it (and its provenance)...
        mvc.perform(as(user, put("/api/transactions/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Pay March", "amount", 6, "type", "INCOME"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("INCOME"))
                .andExpect(jsonPath("$.categorySource").value("USER"));

        // ...a blank category clears it entirely...
        mvc.perform(as(user, put("/api/transactions/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Pay March", "amount", 6, "type", "INCOME", "category", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").doesNotExist())
                .andExpect(jsonPath("$.categorySource").value("NONE"));

        // ...and an invalid one is rejected without touching the row.
        mvc.perform(as(user, put("/api/transactions/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Hacked", "amount", 6, "type", "INCOME", "category", "NOPE"))))
                .andExpect(status().isBadRequest());
        assertThat(fetch(user, id).get("title").asText()).isEqualTo("Pay March");
    }

    // ---- listing filters ------------------------------------------------------------------------

    @Test
    void uncategorisedAndNeedsReviewFilters() throws Exception {
        TestUser user = registerUser();
        String none = createTransaction(user, "No category", "EXPENSE", null);
        String left = createTransaction(user, "Left uncategorised by user", "EXPENSE", "UNCATEGORIZED");
        createTransaction(user, "Categorised", "EXPENSE", "DINING");
        String review = createTransaction(user, "Auto weak", "EXPENSE", null);

        when(aiClient.classify(anyList())).thenAnswer(invocation -> {
            List<ClassifyRequest.Item> items = invocation.getArgument(0);
            Map<String, AiPrediction> out = new HashMap<>();
            items.stream().filter(i -> i.id().equals(review))
                    .forEach(i -> out.put(i.id(), rule(i.id(), "TRANSFERS", true)));
            return new ClassifyOutcome(out, 1, 0, 0);
        });
        mvc.perform(as(user, post("/api/transactions/reclassify"))).andExpect(status().isOk());

        mvc.perform(as(user, get("/api/transactions?category=UNCATEGORIZED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.containsInAnyOrder(none, left)));
        mvc.perform(as(user, get("/api/transactions?needsReview=true")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(review));
        mvc.perform(as(user, get("/api/transactions?category=DINING")))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(as(user, get("/api/transactions?category=NOPE"))).andExpect(status().isBadRequest());
    }

    // ---- re-classification ------------------------------------------------------------------------

    @Test
    void reclassifyCategorisesOnlyUncategorisedRowsAndNeverTouchesUserCategories() throws Exception {
        TestUser user = registerUser();
        String uncategorised = createTransaction(user, "Zepto", "EXPENSE", null);
        String userChosen = createTransaction(user, "Chosen by me", "EXPENSE", "FEES");
        aiAnswers("GROCERIES");

        JsonNode result = json.readTree(mvc.perform(as(user, post("/api/transactions/reclassify")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(result.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.get("attempted").asInt()).isEqualTo(1);
        assertThat(result.get("categorized").asInt()).isEqualTo(1);
        assertThat(result.get("stillUncategorized").asLong()).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ClassifyRequest.Item>> sent = ArgumentCaptor.forClass(List.class);
        verify(aiClient).classify(sent.capture());
        assertThat(sent.getValue()).extracting(ClassifyRequest.Item::id).containsExactly(uncategorised);

        assertThat(fetch(user, uncategorised).get("category").asText()).isEqualTo("GROCERIES");
        assertThat(fetch(user, uncategorised).get("categorySource").asText()).isEqualTo("RULE");
        assertThat(fetch(user, userChosen).get("category").asText()).isEqualTo("FEES");
        assertThat(fetch(user, userChosen).get("categorySource").asText()).isEqualTo("USER");
    }

    @Test
    void reclassifyOnlyEverSendsTheCallersOwnTransactions() throws Exception {
        TestUser alice = registerUser();
        TestUser bob = registerUser();
        String aliceTx = createTransaction(alice, "Alice tx", "EXPENSE", null);
        String bobTx = createTransaction(bob, "Bob tx", "EXPENSE", null);
        aiAnswers("SHOPPING");

        mvc.perform(as(alice, post("/api/transactions/reclassify"))).andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ClassifyRequest.Item>> sent = ArgumentCaptor.forClass(List.class);
        verify(aiClient).classify(sent.capture());
        assertThat(sent.getValue()).extracting(ClassifyRequest.Item::id).containsExactly(aliceTx);
        assertThat(fetch(bob, bobTx).get("category").isNull()).isTrue();
    }

    @Test
    void reclassifyWhenTheAiServiceIsDownIsHarmlessAndReportsIt() throws Exception {
        TestUser user = registerUser();
        String id = createTransaction(user, "Zepto", "EXPENSE", null);
        when(aiClient.classify(anyList())).thenReturn(new ClassifyOutcome(Map.of(), 1, 1, 0));

        mvc.perform(as(user, post("/api/transactions/reclassify")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.categorized").value(0))
                .andExpect(jsonPath("$.stillUncategorized").value(1));
        assertThat(fetch(user, id).get("category").isNull()).isTrue();
    }

    @Test
    void reclassifyWithNothingToDoDoesNotCallTheAiService() throws Exception {
        TestUser user = registerUser();
        createTransaction(user, "Already done", "EXPENSE", "DINING");

        mvc.perform(as(user, post("/api/transactions/reclassify")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_ATTEMPTED"))
                .andExpect(jsonPath("$.attempted").value(0));
        verify(aiClient, never()).classify(anyList());
    }

    @Test
    void anAbstainingAiServiceLeavesRowsAvailableForALaterRetry() throws Exception {
        TestUser user = registerUser();
        String id = createTransaction(user, "UPI-RAMESH", "EXPENSE", null);
        when(aiClient.classify(anyList())).thenAnswer(invocation -> {
            List<ClassifyRequest.Item> items = invocation.getArgument(0);
            Map<String, AiPrediction> out = new HashMap<>();
            items.forEach(i -> out.put(i.id(), new AiPrediction(i.id(), "UNCATEGORIZED", 0, false, "none",
                    "rules-1.0.0", null, true, "UPI payee is not a recognised merchant", List.of())));
            return new ClassifyOutcome(out, 1, 0, 0);
        });

        mvc.perform(as(user, post("/api/transactions/reclassify")))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.categorized").value(0))
                .andExpect(jsonPath("$.stillUncategorized").value(1));
        assertThat(fetch(user, id).get("categorySource").asText()).isEqualTo("NONE");
    }
}
