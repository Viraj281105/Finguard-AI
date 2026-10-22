package com.finguard.backend.ingestion.service;

import com.finguard.backend.identity.domain.User;
import com.finguard.backend.identity.domain.UserRepository;
import com.finguard.backend.ingestion.service.UploadJob.JobStatus;
import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies recovery of interrupted statement import jobs on application startup (KI-007).
 */
class ImportRecoveryTest extends IntegrationTest {

    @Autowired
    private ImportRecoveryListener recoveryListener;

    @Autowired
    private UploadJobRepository jobs;

    @Autowired
    private UserRepository users;

    @Test
    void pendingAndProcessingJobsTransitionToFailedOnStartup() throws Exception {
        TestUser testUser = registerUser();
        User user = users.findByEmail(testUser.email()).orElseThrow();

        UploadJob pending = new UploadJob();
        pending.setUser(user);
        pending.setStatus(JobStatus.PENDING);
        pending.setOriginalFilename("pending.csv");
        jobs.save(pending);

        UploadJob processing = new UploadJob();
        processing.setUser(user);
        processing.setStatus(JobStatus.PROCESSING);
        processing.setOriginalFilename("processing.csv");
        jobs.save(processing);

        UploadJob completed = new UploadJob();
        completed.setUser(user);
        completed.setStatus(JobStatus.COMPLETED);
        completed.setOriginalFilename("completed.csv");
        jobs.save(completed);

        UploadJob failed = new UploadJob();
        failed.setUser(user);
        failed.setStatus(JobStatus.FAILED);
        failed.setOriginalFilename("failed.csv");
        failed.setErrorMessage("Original error");
        jobs.save(failed);

        // Simulate application restart
        recoveryListener.onApplicationReady();

        UploadJob recoveredPending = jobs.findById(pending.getId()).orElseThrow();
        assertThat(recoveredPending.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(recoveredPending.getErrorMessage()).isEqualTo(ImportRecoveryListener.RECOVERY_MESSAGE);
        assertThat(recoveredPending.getCompletedAt()).isNotNull();

        UploadJob recoveredProcessing = jobs.findById(processing.getId()).orElseThrow();
        assertThat(recoveredProcessing.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(recoveredProcessing.getErrorMessage()).isEqualTo(ImportRecoveryListener.RECOVERY_MESSAGE);
        assertThat(recoveredProcessing.getCompletedAt()).isNotNull();

        UploadJob untouchedCompleted = jobs.findById(completed.getId()).orElseThrow();
        assertThat(untouchedCompleted.getStatus()).isEqualTo(JobStatus.COMPLETED);

        UploadJob untouchedFailed = jobs.findById(failed.getId()).orElseThrow();
        assertThat(untouchedFailed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(untouchedFailed.getErrorMessage()).isEqualTo("Original error");
    }
}
