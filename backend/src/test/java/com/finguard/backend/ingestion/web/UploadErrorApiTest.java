package com.finguard.backend.ingestion.web;

import com.finguard.backend.ingestion.service.ImportWorker;
import com.finguard.backend.ingestion.service.UploadJob;
import com.finguard.backend.ingestion.service.UploadJobRepository;
import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies upload error paths: HTTP 413 on file over 5 MB, HTTP 400 on empty file,
 * and HTTP 503 when the background task executor is saturated (KI-029).
 */
class UploadErrorApiTest extends IntegrationTest {

    @MockitoBean
    private ImportWorker worker;

    @Autowired
    private UploadJobRepository jobs;

    @Test
    void fileExceeding5MbLimitReturns413PayloadTooLarge() throws Exception {
        TestUser user = registerUser();
        // 5 MB + 1 byte
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "statement.csv", "text/csv", oversized);

        mvc.perform(as(user, multipart("/api/upload/csv").file(file)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.error").value("Payload Too Large"));
    }

    @Test
    void emptyFileReturns400BadRequest() throws Exception {
        TestUser user = registerUser();
        MockMultipartFile file = new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]);

        mvc.perform(as(user, multipart("/api/upload/csv").file(file)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("The uploaded file is empty"));
    }

    @Autowired
    private com.finguard.backend.identity.domain.UserRepository users;

    @Test
    void taskRejectedExceptionWhenQueueIsFullReturns503AndMarksJobFailed() throws Exception {
        TestUser user = registerUser();
        String csvContent = "Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance\n" +
                "01/03/26,UPI-TEST,001,01/03/26,100.00,,5000.00\n";
        MockMultipartFile file = new MockMultipartFile("file", "saturated_queue.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        doThrow(new TaskRejectedException("Executor queue saturated"))
                .when(worker).process(anyString(), anyString(), anyString());

        mvc.perform(as(user, multipart("/api/upload/csv").file(file)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("The import queue is full. Please try again in a moment."));

        // Verify the job was recorded for this user and marked FAILED
        com.finguard.backend.identity.domain.User userEntity = users.findByEmail(user.email()).orElseThrow();
        List<UploadJob> userJobs = jobs.findByUserIdOrderByCreatedAtDesc(userEntity.getId());
        assertThat(userJobs).hasSize(1);
        UploadJob failedJob = userJobs.get(0);

        assertThat(failedJob.getStatus()).isEqualTo(UploadJob.JobStatus.FAILED);
        assertThat(failedJob.getErrorMessage()).contains("The import queue is full");
    }
}
