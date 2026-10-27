package com.finguard.backend.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for defects found by running the whole application end to end (real browser, Docker PostgreSQL and AI
 * service) rather than by the unit suites. Each test names the defect it pins down. Statements are SYNTHETIC.
 */
class E2eAuditRegressionApiTest extends IntegrationTest {

    private static final String HDFC = "Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance\n";

    private static String row(String date, String narration, String withdrawal, String deposit) {
        return String.format("%s,\"%s\",REF%s,%s,\"%s\",\"%s\",\"1,000.00\"%n", date, narration, narration.hashCode(), date, withdrawal, deposit);
    }

    private JsonNode importCsv(TestUser user, String csv) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "s.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(as(user, multipart("/api/upload/csv").file(file))).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String jobId = json.readTree(body).get("jobId").asText();
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            last[0] = json.readTree(mvc.perform(as(user, get("/api/upload/status/" + jobId))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertThat(last[0].get("status").asText()).isIn("COMPLETED", "FAILED");
        });
        return last[0];
    }

    private JsonNode transactions(TestUser user) throws Exception {
        return json.readTree(mvc.perform(as(user, get("/api/transactions").param("unpaged", "true"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    // ---- CORS: the browser could not read the pagination headers ---------------------------------------------------------

    @Test
    void paginationHeadersAreExposedToTheBrowser() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, get("/api/transactions").param("page", "0").param("size", "5").header("Origin", "http://localhost:5173")))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("X-Total-Count"), org.hamcrest.Matchers.containsString("X-Total-Pages"),
                        org.hamcrest.Matchers.containsString("X-Page"), org.hamcrest.Matchers.containsString("X-Page-Size"))));
    }

    // ---- import: dates and amounts that used to be silently altered or crash the whole job ----------------------------

    @Test
    void aDateThatDoesNotExistIsRejectedNotMovedToTheLastDayOfTheMonth() throws Exception {
        TestUser user = registerUser();
        JsonNode job = importCsv(user, HDFC + row("31/02/26", "UPI-FEB31", "100.00", "") + row("28/02/26", "UPI-FEB28", "50.00", ""));
        assertThat(job.get("rowsProcessed").asInt()).isEqualTo(1);
        assertThat(job.get("rejectedRows").asInt()).isEqualTo(1);
        assertThat(job.get("rowErrors").get(0).get("errorMessage").asText()).contains("Cannot parse date");
        JsonNode tx = transactions(user);
        assertThat(tx).hasSize(1);
        assertThat(tx.get(0).get("title").asText()).isEqualTo("UPI-FEB28");
    }

    @Test
    void implausibleDatesAreRejected() throws Exception {
        TestUser user = registerUser();
        JsonNode job = importCsv(user, HDFC + row("01/01/2099", "UPI-FUTURE", "10.00", "") + row("01/01/1901", "UPI-ANCIENT", "10.00", "")
                + row("01/01/99", "UPI-TWO-DIGIT-99", "10.00", "") + row("02/03/26", "UPI-OK", "10.00", ""));
        assertThat(job.get("rowsProcessed").asInt()).isEqualTo(1);
        assertThat(job.get("rejectedRows").asInt()).isEqualTo(3);
        assertThat(job.get("rowErrors").get(0).get("errorMessage").asText()).contains("plausible range");
    }

    @Test
    void aNegativeCreditIsNotSilentlyTurnedIntoIncome() throws Exception {
        TestUser user = registerUser();
        // a negative or parenthesised DEBIT stays an expense (documented, KI-006); a negative CREDIT is a reversal and is refused
        JsonNode job = importCsv(user, HDFC + row("03/03/26", "UPI-NEGDEP", "", "-500.00") + row("04/03/26", "UPI-PAREN-DEBIT", "(750.00)", "")
                + row("05/03/26", "UPI-NORMAL", "10.00", ""));
        assertThat(job.get("rowsProcessed").asInt()).isEqualTo(2);
        assertThat(job.get("rejectedRows").asInt()).isEqualTo(1);
        assertThat(job.get("rowErrors").get(0).get("errorMessage").asText()).contains("credit column").contains("direction is ambiguous");
        JsonNode tx = transactions(user);
        assertThat(tx).hasSize(2);
        for (JsonNode t : tx) {
            assertThat(t.get("type").asText()).isEqualTo("EXPENSE");
        }
    }

    @Test
    void aVeryLongNarrationDoesNotFailTheWholeStatement() throws Exception {
        TestUser user = registerUser();
        JsonNode job = importCsv(user, HDFC + row("02/03/26", "UPI-" + "LONG".repeat(2500), "100.00", "") + row("03/03/26", "UPI-SHORT", "20.00", ""));
        assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(job.get("rowsProcessed").asInt()).isEqualTo(2);
        for (JsonNode t : transactions(user)) {
            assertThat(t.get("title").asText().length()).isLessThanOrEqualTo(255);
        }
    }

    @Test
    void anAbsurdAmountRejectsOnlyThatRow() throws Exception {
        TestUser user = registerUser();
        JsonNode job = importCsv(user, HDFC + row("02/03/26", "UPI-OVERFLOW", "9".repeat(40), "") + row("03/03/26", "UPI-FINE", "20.00", ""));
        assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(job.get("rowsProcessed").asInt()).isEqualTo(1);
        assertThat(job.get("rejectedRows").asInt()).isEqualTo(1);
    }

    @Test
    void aStatementWithExactlyTenThousandDataRowsIsAccepted() throws Exception {
        TestUser user = registerUser();
        StringBuilder sb = new StringBuilder(HDFC);
        for (int i = 0; i < 10_000; i++) {
            sb.append("02/03/26,\"UPI-ROW ").append(i).append("\",R").append(i).append(",02/03/26,\"1.00\",\"\",\"1.00\"\n");
        }
        MockMultipartFile ok = new MockMultipartFile("file", "ten-k.csv", "text/csv", sb.toString().getBytes(StandardCharsets.UTF_8));
        mvc.perform(as(user, multipart("/api/upload/csv").file(ok))).andExpect(status().isAccepted());
        sb.append("02/03/26,\"UPI-ONE-TOO-MANY\",R,02/03/26,\"1.00\",\"\",\"1.00\"\n");
        MockMultipartFile tooMany = new MockMultipartFile("file", "ten-k-plus-one.csv", "text/csv", sb.toString().getBytes(StandardCharsets.UTF_8));
        mvc.perform(as(user, multipart("/api/upload/csv").file(tooMany))).andExpect(status().isBadRequest());
    }

    @Test
    void overlappingStatementsImportedAtTheSameTimeDoNotFailAJobOrDuplicateRows() throws Exception {
        TestUser user = registerUser();
        StringBuilder sb = new StringBuilder(HDFC);
        for (int i = 0; i < 40; i++) {
            sb.append(row(String.format("%02d/03/26", 1 + i % 28), "UPI-PARALLEL " + i, "10.00", ""));
        }
        String csv = sb.toString();
        int parallel = 6;
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<JsonNode>> jobs = new ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            jobs.add(pool.submit(() -> {
                go.await();
                return importCsv(user, csv);
            }));
        }
        go.countDown();
        int imported = 0;
        int duplicates = 0;
        for (Future<JsonNode> f : jobs) {
            JsonNode job = f.get();
            assertThat(job.get("status").asText()).as("job %s", job).isEqualTo("COMPLETED");
            imported += job.get("rowsProcessed").asInt();
            duplicates += job.get("duplicateRows").asInt();
        }
        pool.shutdown();
        assertThat(imported).isEqualTo(40);
        assertThat(duplicates).isEqualTo(40 * (parallel - 1));
        assertThat(transactions(user)).hasSize(40);
    }

    // ---- input validation: values the database cannot hold must be a 400, not a misleading 409 or a 200 ----------------

    @Test
    void oversizedValuesAreRejectedAsBadRequests() throws Exception {
        TestUser user = registerUser();
        Map<String, Object> tx = new LinkedHashMap<>(Map.of("title", "x".repeat(5000), "amount", 100, "type", "EXPENSE", "date", "2026-05-10"));
        mvc.perform(as(user, post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(json(tx)))).andExpect(status().isBadRequest());
        tx.put("title", "ok");
        tx.put("note", "n".repeat(100_000));
        mvc.perform(as(user, post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(json(tx)))).andExpect(status().isBadRequest());
        tx.remove("note");
        tx.put("amount", new java.math.BigDecimal("1e20"));
        mvc.perform(as(user, post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(json(tx)))).andExpect(status().isBadRequest());
        tx.put("amount", new java.math.BigDecimal("10.005"));
        mvc.perform(as(user, post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(json(tx)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "n".repeat(5000), "email", "long-name@example.com", "password", PASSWORD)))).andExpect(status().isBadRequest());
        mvc.perform(as(user, post("/api/loans").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "L", "type", "HOME", "principal", 100, "emiAmount", 10, "interestRate", 500))))).andExpect(status().isBadRequest());
        mvc.perform(as(user, post("/api/budgets").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("yearMonth", "2026-06", "category", "DINING", "amount", new java.math.BigDecimal("1e30")))))).andExpect(status().isBadRequest());
        // a normal two-decimal amount is still fine
        tx.put("amount", new java.math.BigDecimal("10.05"));
        mvc.perform(as(user, post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(json(tx)))).andExpect(status().isCreated());
    }

    @Test
    void uploadWithoutAMultipartBodyIsABadRequestNotAServerError() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, post("/api/upload/csv"))).andExpect(status().isBadRequest());
    }

    // ---- refresh tokens: one token, many simultaneous requests ------------------------------------------------------

    @Test
    void concurrentRefreshOfTheSameTokenSucceedsAtMostOnce() throws Exception {
        TestUser user = registerUser();
        int parallel = 8;
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", user.refreshToken())))).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Integer> f : results) {
            int code = f.get();
            assertThat(code).isIn(200, 401);
            if (code == 200) {
                ok++;
            }
        }
        pool.shutdown();
        assertThat(ok).as("a single-use refresh token must be redeemable only once").isEqualTo(1);
    }

    @Test
    void concurrentRefreshRacePreservesNewlyRotatedSessionWithinGracePeriod() throws Exception {
        TestUser user = registerUser();
        // First refresh succeeds and rotates user.refreshToken() -> newRefreshToken
        var res1 = mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("refreshToken", user.refreshToken()))))
                .andExpect(status().isOk())
                .andReturn();
        jakarta.servlet.http.Cookie refreshCookie = res1.getResponse().getCookie("refreshToken");
        assertThat(refreshCookie).isNotNull();
        String newRefreshToken = refreshCookie.getValue();

        // Second refresh with the old token arrives immediately (concurrent tab race within grace window).
        // It fails with 401, BUT it must NOT revoke newRefreshToken!
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("refreshToken", user.refreshToken()))))
                .andExpect(status().isUnauthorized());

        // The newly rotated token must still be valid and able to refresh!
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("refreshToken", newRefreshToken))))
                .andExpect(status().isOk());
    }

    // ---- budgets: documented query parameter ---------------------------------------------------------------------------

    @Test
    void budgetOverviewHonoursTheDocumentedYearMonthParameterAndTheOlderMonthAlias() throws Exception {
        TestUser user = registerUser();
        for (String param : new String[]{"yearMonth", "month"}) {
            JsonNode body = json.readTree(mvc.perform(as(user, get("/api/budgets/overview").param(param, "2026-06"))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertThat(body.get("yearMonth").asText()).as(param).isEqualTo("2026-06");
        }
        mvc.perform(as(user, get("/api/budgets/overview").param("yearMonth", "garbage"))).andExpect(status().isBadRequest());
    }

    // ---- analytics: a monthly cost must be compared with monthly spending ---------------------------------------------------

    @Test
    void recurringShareOfSpendingComparesMonthlyCostWithMonthlySpending() throws Exception {
        TestUser user = registerUser();
        for (int m = 1; m <= 4; m++) {
            // rent is identical every month (recurring); the market spend varies in amount, day and name (not recurring)
            for (String[] t : new String[][]{{"RENT-TO-LANDLORD", "HOUSING", "10000", "05"}, {"SHOP-" + "ABCD".charAt(m - 1) + " " + m * 7, "GROCERIES", String.valueOf(6000 + m * 1300), String.valueOf(9 + m * 4)}}) {
                Map<String, Object> body = new LinkedHashMap<>(Map.of("title", t[0], "category", t[1], "amount", new java.math.BigDecimal(t[2]),
                        "type", "EXPENSE", "date", String.format("2026-%02d-%s", m, t[3])));
                mvc.perform(as(user, post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(json(body)))).andExpect(status().isCreated());
            }
        }
        JsonNode recurring = json.readTree(mvc.perform(as(user, get("/api/analytics/recurring"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(recurring.get("totalMonthlyRecurring").decimalValue()).isEqualByComparingTo("10000");
        // spending = 4 x 10,000 rent + (7,300 + 8,600 + 9,900 + 11,200) market = 40,000 + 37,000 = 77,000 over 4 months,
        // i.e. 19,250 a month; the 10,000 monthly recurring cost is 51.95 % of that, not 10,000 / 77,000 = 12.99 % of the whole period
        assertThat(recurring.get("recurringPercentageOfExpenses").decimalValue()).isEqualByComparingTo("51.95");
    }

    // ---- preflight stays closed to other origins (guards the CORS change above) -----------------------------------------

    @Test
    void transactionsEndpointDefaultsToPageZeroSizeTwentyUnlessUnpagedRequested() throws Exception {
        TestUser user = registerUser();
        for (int i = 1; i <= 25; i++) {
            Map<String, Object> body = Map.of(
                    "title", "Item " + i,
                    "amount", 10.0,
                    "type", "EXPENSE",
                    "date", "2026-03-01"
            );
            mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isCreated());
        }

        // Call without page or size parameters -> defaults to page 0, size 20
        mvc.perform(as(user, get("/api/transactions")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "25"))
                .andExpect(header().string("X-Page", "0"))
                .andExpect(header().string("X-Page-Size", "20"))
                .andExpect(header().string("X-Total-Pages", "2"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(20));

        // Call with unpaged=true -> returns all 25 rows
        mvc.perform(as(user, get("/api/transactions").param("unpaged", "true")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "25"))
                .andExpect(header().string("X-Page", "0"))
                .andExpect(header().string("X-Page-Size", "25"))
                .andExpect(header().string("X-Total-Pages", "1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(25));
    }

    @Test
    void bankFooterSummaryLinesAreSkippedNotCountedAsErrors() throws Exception {
        TestUser user = registerUser();
        String csv = HDFC
                + row("01/03/26", "UPI-ITEM-1", "100.00", "")
                + row("02/03/26", "UPI-ITEM-2", "200.00", "")
                + "Opening Balance,,,50000.00\n"
                + "Total Debits,,300.00,\n"
                + "Closing Balance,,,49700.00\n";
        JsonNode job = importCsv(user, csv);
        assertThat(job.get("rowsProcessed").asInt()).isEqualTo(2);
        assertThat(job.get("rejectedRows").asInt()).isEqualTo(0);
        assertThat(job.get("skippedRows").asInt()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void manualTransactionValidationRejectsImplausibleDateAndIncompatibleCategory() throws Exception {
        TestUser user = registerUser();

        // Future date beyond tomorrow
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Future item", "amount", 100, "type", "EXPENSE", "date", "2099-01-01"))))
                .andExpect(status().isBadRequest());

        // Ancient date before 1990
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Ancient item", "amount", 100, "type", "EXPENSE", "date", "1980-01-01"))))
                .andExpect(status().isBadRequest());

        // Incompatible category: INCOME with expense category DINING
        mvc.perform(as(user, post("/api/transactions")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Salary check", "amount", 50000, "type", "INCOME", "category", "DINING"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loanValidationRejectsInvertedDatesAndExcessiveEmi() throws Exception {
        TestUser user = registerUser();

        // End date before start date
        mvc.perform(as(user, post("/api/loans")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Inverted Loan", "type", "PERSONAL", "principal", 100000, "emiAmount", 5000,
                        "interestRate", 10.5, "startDate", "2026-06-01", "endDate", "2025-06-01"))))
                .andExpect(status().isBadRequest());

        // EMI exceeding principal
        mvc.perform(as(user, post("/api/loans")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Excessive EMI", "type", "PERSONAL", "principal", 10000, "emiAmount", 15000,
                        "interestRate", 10.5, "startDate", "2026-01-01", "endDate", "2027-01-01"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void goalValidationRejectsPastTargetDate() throws Exception {
        TestUser user = registerUser();

        mvc.perform(as(user, post("/api/goals")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "Past Goal", "targetAmount", 50000, "targetDate", "2020-01-01"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void corsStillRejectsUnknownOrigins() throws Exception {
        mvc.perform(options("/api/transactions").header("Origin", "http://evil.example").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
