package com.finguard.backend.ledger;

import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Request validation and the JSON shape the frontend depends on. */
class LedgerApiTest extends IntegrationTest {

    @Test
    void transactionKeepsDecimalPrecisionAndExposesTheFrontendFields() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Rent\",\"amount\":12345.67,\"type\":\"EXPENSE\",\"category\":\"Rent\",\"note\":\"March\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.amount").value(12345.67))
                .andExpect(jsonPath("$.type").value("EXPENSE"))
                .andExpect(jsonPath("$.category").value("HOUSING"))
                .andExpect(jsonPath("$.note").value("March"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$", not(hasKey("user"))));
    }

    @Test
    void transactionValidationRejectsNonPositiveAmountsAndMissingFields() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "", "amount", -5, "type", "EXPENSE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors", hasKey("title")))
                .andExpect(jsonPath("$.fieldErrors", hasKey("amount")));
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "x", "amount", 5))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors", hasKey("type")));
    }

    @Test
    void loanDefaultsToActiveAndAllowsZeroInterest() throws Exception {
        TestUser user = registerUser();
        Map<String, Object> body = new HashMap<>(Map.of("name", "Phone EMI", "type", "PERSONAL",
                "principal", 30000, "emiAmount", 2500, "interestRate", 0));
        body.put("active", null);
        mvc.perform(as(user, post("/api/loans")).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.interestRate").value(0));
    }

    @Test
    void loanDatesRoundTrip() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, post("/api/loans")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Home", "type", "HOME", "principal", 4000000, "emiAmount", 35000,
                                "interestRate", 8.75, "startDate", "2024-04-01", "endDate", "2044-04-01", "active", false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startDate").value("2024-04-01"))
                .andExpect(jsonPath("$.endDate").value("2044-04-01"))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void investmentCurrentValueIsOptional() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, post("/api/investments")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "FD", "type", "FD", "amountInvested", 100000))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentValue").doesNotExist());
        mvc.perform(as(user, get("/api/investments"))).andExpect(status().isOk());
    }

    @Test
    void unknownEnumValueIsABadRequest() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "x", "amount", 5, "type", "TRANSFER"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void transactionPersistsAndExposesCalendarDate() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Groceries", "amount", 1450.00, "type", "EXPENSE",
                                "date", "2026-03-15", "category", "Food", "note", "Weekly shopping"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.date").value("2026-03-15"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.categorySource").value("USER"));
    }

    @Test
    void transactionCreationWithoutDateDefaultsToCurrentDate() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Coffee", "amount", 120.00, "type", "EXPENSE"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.date").value(java.time.LocalDate.now().toString()))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.categorySource").value("NONE"));
    }

    @Test
    void transactionUpdateCanChangeDateAndCategory() throws Exception {
        TestUser user = registerUser();
        String created = mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Fuel", "amount", 2000, "type", "EXPENSE", "date", "2026-03-01"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = new org.json.JSONObject(created).getString("id");

        mvc.perform(as(user, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/transactions/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "Fuel - Petrol", "amount", 2050, "type", "EXPENSE",
                                "date", "2026-03-02", "category", "Transport", "note", "Updated"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Fuel - Petrol"))
                .andExpect(jsonPath("$.amount").value(2050))
                .andExpect(jsonPath("$.date").value("2026-03-02"))
                .andExpect(jsonPath("$.category").value("TRANSPORT"))
                .andExpect(jsonPath("$.categorySource").value("USER"));
    }

    @Test
    void transactionPaginationAndDeterministicSorting() throws Exception {
        TestUser user = registerUser();
        // Insert 5 transactions with dates
        for (int i = 1; i <= 5; i++) {
            mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("title", "Tx " + i, "amount", 100 * i, "type", "EXPENSE",
                            "date", "2026-03-0" + i))));
        }

        // Page 0, size 2 -> should return Tx 5 and Tx 4 (newest date first)
        mvc.perform(as(user, get("/api/transactions?page=0&size=2")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Total-Count", "5"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Page", "0"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Page-Size", "2"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Total-Pages", "3"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Tx 5"))
                .andExpect(jsonPath("$[1].title").value("Tx 4"));

        // Page 1, size 2 -> Tx 3 and Tx 2
        mvc.perform(as(user, get("/api/transactions?page=1&size=2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Tx 3"))
                .andExpect(jsonPath("$[1].title").value("Tx 2"));

        // Page 2, size 2 -> Tx 1
        mvc.perform(as(user, get("/api/transactions?page=2&size=2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Tx 1"));

        // Page 3, size 2 -> empty page
        mvc.perform(as(user, get("/api/transactions?page=3&size=2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void transactionFilteringByDateRangeTypeAndCategory() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Salary", "amount", 80000, "type", "INCOME", "category", "Salary", "date", "2026-03-01"))));
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Rent", "amount", 25000, "type", "EXPENSE", "category", "Housing", "date", "2026-03-05"))));
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Groceries", "amount", 3000, "type", "EXPENSE", "category", "Food", "date", "2026-03-15"))));
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Freelance", "amount", 15000, "type", "INCOME", "category", "Freelance", "date", "2026-03-25"))));

        // Filter by type=EXPENSE
        mvc.perform(as(user, get("/api/transactions?type=EXPENSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // Filter by category=Food
        mvc.perform(as(user, get("/api/transactions?category=Food")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Groceries"));

        // Filter by date range from 2026-03-02 to 2026-03-20
        mvc.perform(as(user, get("/api/transactions?from=2026-03-02&to=2026-03-20")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void transactionQueryValidationRejectsInvalidParameters() throws Exception {
        TestUser user = registerUser();
        // from after to
        mvc.perform(as(user, get("/api/transactions?from=2026-03-20&to=2026-03-10")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("'from' date cannot be after 'to' date"));

        // negative page
        mvc.perform(as(user, get("/api/transactions?page=-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Page index must not be less than zero"));

        // invalid size
        mvc.perform(as(user, get("/api/transactions?size=0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Page size must be between 1 and 100"));

        mvc.perform(as(user, get("/api/transactions?size=101")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Page size must be between 1 and 100"));
    }

    @Test
    void loanWiderInterestRatePreservesPrecision() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, post("/api/loans")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Auto Loan", "type", "CAR", "principal", 800000,
                                "emiAmount", 18000, "interestRate", 8.755))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.interestRate").value(8.755));
    }
}
