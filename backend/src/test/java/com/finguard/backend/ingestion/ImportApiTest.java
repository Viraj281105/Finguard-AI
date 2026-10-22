package com.finguard.backend.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end through the HTTP API on a real database: upload, asynchronous processing, status
 * polling, persisted transactions. The AI service is unreachable here, which also covers the
 * "classification unavailable" fallback.
 */
class ImportApiTest extends IntegrationTest {

    static MockMultipartFile csv(String fixture) throws IOException {
        try (var in = ImportApiTest.class.getResourceAsStream("/fixtures/statements/" + fixture)) {
            return new MockMultipartFile("file", fixture, "text/csv", in.readAllBytes());
        }
    }

    private JsonNode upload(TestUser user, MockMultipartFile file) throws Exception {
        String body = mvc.perform(as(user, multipart("/api/upload/csv").file(file)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode awaitTerminalStatus(TestUser user, String jobId) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            String body = mvc.perform(as(user, get("/api/upload/status/" + jobId)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            last[0] = json.readTree(body);
            assertThat(last[0].get("status").asText()).isIn("COMPLETED", "FAILED");
        });
        return last[0];
    }

    @Test
    void uploadIsAcceptedImmediatelyThenCompletesAndPersistsTransactions() throws Exception {
        TestUser user = registerUser();

        JsonNode accepted = upload(user, csv("hdfc.csv"));
        assertThat(accepted.get("jobId").asText()).isNotBlank();
        assertThat(accepted.get("bankName").asText()).isEqualTo("HDFC");
        assertThat(accepted.get("originalFilename").asText()).isEqualTo("hdfc.csv");
        assertThat(accepted.has("user")).isFalse();

        JsonNode done = awaitTerminalStatus(user, accepted.get("jobId").asText());
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("totalRows").asInt()).isEqualTo(5);
        assertThat(done.get("rowsProcessed").asInt()).isEqualTo(3);
        assertThat(done.get("rejectedRows").asInt()).isEqualTo(1);
        assertThat(done.get("skippedRows").asInt()).isEqualTo(1);
        assertThat(done.get("duplicateRows").asInt()).isEqualTo(0);
        assertThat(done.get("closingBalance").asDouble()).isEqualTo(75550.00);
        assertThat(done.get("statementFrom").asText()).isEqualTo("2026-03-01");
        assertThat(done.get("statementTo").asText()).isEqualTo("2026-03-05");
        assertThat(done.get("completedAt").isNull()).isFalse();

        mvc.perform(as(user, get("/api/transactions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.title == 'UPI-SWIGGY-swiggy@icici')].type").value("EXPENSE"))
                .andExpect(jsonPath("$[?(@.title == 'NEFT-ACME LTD-SALARY MARCH')].amount").value(75000.00))
                // AI service is down in this test: rows are kept, uncategorised.
                .andExpect(jsonPath("$[0].category").doesNotExist());
    }

    @Test
    void importedTransactionsBelongOnlyToTheUploader() throws Exception {
        TestUser uploader = registerUser();
        TestUser other = registerUser();

        awaitTerminalStatus(uploader, upload(uploader, csv("kotak.csv")).get("jobId").asText());

        mvc.perform(as(uploader, get("/api/transactions"))).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(as(other, get("/api/transactions"))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anotherUsersJobIsNotVisible() throws Exception {
        TestUser uploader = registerUser();
        TestUser other = registerUser();
        String jobId = upload(uploader, csv("sbi.csv")).get("jobId").asText();

        mvc.perform(as(other, get("/api/upload/status/" + jobId))).andExpect(status().isNotFound());
        mvc.perform(as(uploader, get("/api/upload/status/" + jobId))).andExpect(status().isOk());
    }

    @Test
    void unrecognisedStatementFailsTheJobWithAUserFacingMessage() throws Exception {
        TestUser user = registerUser();
        JsonNode job = upload(user, csv("unknown.csv"));

        JsonNode done = awaitTerminalStatus(user, job.get("jobId").asText());
        assertThat(done.get("status").asText()).isEqualTo("FAILED");
        assertThat(done.get("errorMessage").asText()).contains("Unrecognized CSV format");
        mvc.perform(as(user, get("/api/transactions"))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void nonCsvAndEmptyFilesAreRejected() throws Exception {
        TestUser user = registerUser();
        mvc.perform(as(user, multipart("/api/upload/csv")
                        .file(new MockMultipartFile("file", "statement.pdf", "application/pdf", "x".getBytes()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only .csv files are supported"));
        mvc.perform(as(user, multipart("/api/upload/csv")
                        .file(new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]))))
                .andExpect(status().isBadRequest());
        mvc.perform(as(user, multipart("/api/upload/csv"))).andExpect(status().isBadRequest());
    }

    @Test
    void uploadRequiresAuthentication() throws Exception {
        mvc.perform(multipart("/api/upload/csv").file(
                        new MockMultipartFile("file", "a.csv", "text/csv", "a,b".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/upload/status/anything")).andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedUploadOfSameStatementDoesNotCreateDuplicates() throws Exception {
        TestUser user = registerUser();

        // 1st upload
        JsonNode first = awaitTerminalStatus(user, upload(user, csv("hdfc.csv")).get("jobId").asText());
        assertThat(first.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(first.get("rowsProcessed").asInt()).isEqualTo(3);
        assertThat(first.get("duplicateRows").asInt()).isEqualTo(0);

        // Verify 3 rows in database
        mvc.perform(as(user, get("/api/transactions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].source").value("IMPORT"))
                .andExpect(jsonPath("$[0].date").exists());

        // 2nd upload of the exact same statement
        JsonNode second = awaitTerminalStatus(user, upload(user, csv("hdfc.csv")).get("jobId").asText());
        assertThat(second.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(second.get("rowsProcessed").asInt()).isEqualTo(0);
        assertThat(second.get("duplicateRows").asInt()).isEqualTo(3);

        // Total rows in database must still be exactly 3, not 6
        mvc.perform(as(user, get("/api/transactions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void sameStatementUploadedByDifferentUsersBothImportWithoutCollision() throws Exception {
        TestUser userA = registerUser();
        TestUser userB = registerUser();

        JsonNode doneA = awaitTerminalStatus(userA, upload(userA, csv("kotak.csv")).get("jobId").asText());
        assertThat(doneA.get("rowsProcessed").asInt()).isEqualTo(2);

        JsonNode doneB = awaitTerminalStatus(userB, upload(userB, csv("kotak.csv")).get("jobId").asText());
        assertThat(doneB.get("rowsProcessed").asInt()).isEqualTo(2);

        mvc.perform(as(userA, get("/api/transactions"))).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(as(userB, get("/api/transactions"))).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void legitimateSameDayIdenticalTransactionsBothImport() throws Exception {
        TestUser user = registerUser();
        // HDFC format fixture with two identical rows on the same date
        String doubleChaiCsv = """
                * * * HDFC BANK STATEMENT * * *
                Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
                15/03/24,UPI-CHAI POINT-12345,REF001,15/03/24,20.00,,5000.00
                15/03/24,UPI-CHAI POINT-12345,REF002,15/03/24,20.00,,4980.00
                """;

        MockMultipartFile file = new MockMultipartFile("file", "double_chai.csv", "text/csv", doubleChaiCsv.getBytes(StandardCharsets.UTF_8));
        JsonNode first = awaitTerminalStatus(user, upload(user, file).get("jobId").asText());
        assertThat(first.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(first.get("rowsProcessed").asInt()).isEqualTo(2);
        assertThat(first.get("duplicateRows").asInt()).isEqualTo(0);

        mvc.perform(as(user, get("/api/transactions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].date").value("2024-03-15"))
                .andExpect(jsonPath("$[1].date").value("2024-03-15"));

        // Re-uploading this double-chai fixture should skip both as duplicates
        JsonNode second = awaitTerminalStatus(user, upload(user, file).get("jobId").asText());
        assertThat(second.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(second.get("rowsProcessed").asInt()).isEqualTo(0);
        assertThat(second.get("duplicateRows").asInt()).isEqualTo(2);

        mvc.perform(as(user, get("/api/transactions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getJobsReturnsImportHistoryScopedToUser() throws Exception {
        TestUser userA = registerUser();
        TestUser userB = registerUser();

        awaitTerminalStatus(userA, upload(userA, csv("axis.csv")).get("jobId").asText());
        awaitTerminalStatus(userB, upload(userB, csv("kotak.csv")).get("jobId").asText());

        // userA sees only their own job
        mvc.perform(as(userA, get("/api/upload/jobs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bankName").value("AXIS"))
                .andExpect(jsonPath("$[0].originalFilename").value("axis.csv"))
                .andExpect(jsonPath("$[0].rowsProcessed").value(2));

        // userB sees only their own job
        mvc.perform(as(userB, get("/api/upload/jobs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bankName").value("KOTAK"))
                .andExpect(jsonPath("$[0].originalFilename").value("kotak.csv"));
    }

    @Test
    void binaryFileWithNullBytesIsRejectedWithBadRequest() throws Exception {
        TestUser user = registerUser();
        byte[] binaryContent = new byte[]{0x25, 0x50, 0x44, 0x46, 0x00, 0x01, 0x02}; // Mock binary with null byte
        MockMultipartFile file = new MockMultipartFile("file", "fake.csv", "text/csv", binaryContent);

        mvc.perform(as(user, multipart("/api/upload/csv").file(file)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The uploaded file appears to be a binary file, not a valid CSV statement."));
    }

    @Test
    void windows1252EncodedCsvIsDecodedAndImported() throws Exception {
        TestUser user = registerUser();
        // Byte 0xA3 is pound sign in Windows-1252 (invalid UTF-8 sequence on its own)
        byte[] win1252Bytes = ("Tran Date,CHQNO,PARTICULARS,DR,CR,BAL\n" +
                "10-03-2026,,UPI/P2M/CAFE \u00A3 LONDON,150.00,,5000.00\n").getBytes(java.nio.charset.Charset.forName("windows-1252"));

        MockMultipartFile file = new MockMultipartFile("file", "windows1252.csv", "text/csv", win1252Bytes);
        JsonNode done = awaitTerminalStatus(user, upload(user, file).get("jobId").asText());

        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("rowsProcessed").asInt()).isEqualTo(1);

        mvc.perform(as(user, get("/api/transactions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("UPI/P2M/CAFE £ LONDON"));
    }

    @Test
    void iciciWithdrawalDepositLayoutIsRecognizedAndImported() throws Exception {
        TestUser user = registerUser();
        String iciciWdlDepCsv = """
                Transaction Date,Value Date,Transaction Remarks,Withdrawal Amount,Deposit Amount,Balance
                03/03/2026,03/03/2026,UPI/ZOMATO/order,320.50,,12000.00
                04/03/2026,04/03/2026,NEFT/EMPLOYER/SAL,,"52,000.00","64,000.00"
                """;
        MockMultipartFile file = new MockMultipartFile("file", "icici_wdl.csv", "text/csv", iciciWdlDepCsv.getBytes(StandardCharsets.UTF_8));
        JsonNode done = awaitTerminalStatus(user, upload(user, file).get("jobId").asText());

        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("bankName").asText()).isEqualTo("ICICI");
        assertThat(done.get("rowsProcessed").asInt()).isEqualTo(2);
        assertThat(done.get("closingBalance").asDouble()).isEqualTo(64000.00);

        mvc.perform(as(user, get("/api/transactions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.title == 'UPI/ZOMATO/order')].type").value("EXPENSE"))
                .andExpect(jsonPath("$[?(@.title == 'NEFT/EMPLOYER/SAL')].type").value("INCOME"));
    }
}
