package com.finguard.backend.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Broken-object-level-authorization guard: a user must never be able to read, change or delete another
 * user's records, and must not even be able to tell that they exist (404, not 403).
 */
class OwnershipApiTest extends IntegrationTest {

    private record Resource(String path, Map<String, Object> create, Map<String, Object> update) {
    }

    private static final Resource TRANSACTIONS = new Resource("/api/transactions",
            Map.of("title", "Coffee", "amount", 120.50, "type", "EXPENSE", "category", "Food"),
            Map.of("title", "Hacked", "amount", 1, "type", "INCOME"));

    private static final Resource INVESTMENTS = new Resource("/api/investments",
            Map.of("name", "Index fund", "type", "MUTUAL_FUND", "amountInvested", 5000, "currentValue", 5200),
            Map.of("name", "Hacked", "type", "STOCKS", "amountInvested", 1));

    private static final Resource LOANS = new Resource("/api/loans",
            Map.of("name", "Car loan", "type", "CAR", "principal", 500000, "emiAmount", 12000, "interestRate", 8.5),
            Map.of("name", "Hacked", "type", "HOME", "principal", 1, "emiAmount", 1, "interestRate", 1));

    @Test
    void transactionsAreIsolatedBetweenUsers() throws Exception {
        assertIsolated(TRANSACTIONS);
    }

    @Test
    void investmentsAreIsolatedBetweenUsers() throws Exception {
        assertIsolated(INVESTMENTS);
    }

    @Test
    void loansAreIsolatedBetweenUsers() throws Exception {
        assertIsolated(LOANS);
    }

    private void assertIsolated(Resource resource) throws Exception {
        TestUser owner = registerUser();
        TestUser intruder = registerUser();
        String id = createAs(owner, resource);
        String item = resource.path() + "/" + id;

        // The owner can read it.
        mvc.perform(as(owner, get(item))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

        // The intruder cannot read, update or delete it, and it is absent from their list.
        mvc.perform(as(intruder, get(item))).andExpect(status().isNotFound());
        mvc.perform(as(intruder, put(item)).contentType(MediaType.APPLICATION_JSON).content(json(resource.update())))
                .andExpect(status().isNotFound());
        mvc.perform(as(intruder, delete(item))).andExpect(status().isNotFound());
        mvc.perform(as(intruder, get(resource.path()))).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));

        // The failed attacks changed nothing.
        mvc.perform(as(owner, get(item))).andExpect(status().isOk());
        mvc.perform(as(owner, get(resource.path()))).andExpect(jsonPath("$", hasSize(1)));

        // The owner can update and then delete their own record.
        mvc.perform(as(owner, put(item)).contentType(MediaType.APPLICATION_JSON).content(json(resource.create())))
                .andExpect(status().isOk());
        mvc.perform(as(owner, delete(item))).andExpect(status().isNoContent());
        mvc.perform(as(owner, get(item))).andExpect(status().isNotFound());
    }

    private String createAs(TestUser user, Resource resource) throws Exception {
        MockHttpServletRequestBuilder request = as(user, post(resource.path()))
                .contentType(MediaType.APPLICATION_JSON).content(json(resource.create()));
        String body = mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return node.get("id").asText();
    }

    @Test
    void unknownIdsAreNotFound() throws Exception {
        TestUser user = registerUser();
        for (Resource resource : new Resource[]{TRANSACTIONS, INVESTMENTS, LOANS}) {
            mvc.perform(as(user, get(resource.path() + "/does-not-exist"))).andExpect(status().isNotFound());
            mvc.perform(as(user, delete(resource.path() + "/does-not-exist"))).andExpect(status().isNotFound());
        }
    }
}
