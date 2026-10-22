package com.finguard.backend.ingestion.service;

import com.finguard.backend.ingestion.service.UploadJob.JobStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * On application startup, recovers any jobs that were interrupted in {@code PENDING} or {@code PROCESSING}
 * state by an unexpected application shutdown or restart (KI-007).
 *
 * <p>Because transactions and imports are idempotent (guaranteed by v0.3.0 fingerprints), marking these jobs
 * {@code FAILED} with guidance to re-upload ensures safe and clean recovery without duplicate transactions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportRecoveryListener {

    public static final String RECOVERY_MESSAGE =
            "Import was interrupted by a system restart. Please re-upload your statement.";

    private final UploadJobRepository jobs;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onApplicationReady() {
        List<UploadJob> interruptedJobs = jobs.findByStatusIn(List.of(JobStatus.PENDING, JobStatus.PROCESSING));
        if (interruptedJobs.isEmpty()) {
            return;
        }

        log.warn("Found {} interrupted upload jobs from prior run. Marking as FAILED.", interruptedJobs.size());
        LocalDateTime now = LocalDateTime.now();
        for (UploadJob job : interruptedJobs) {
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(RECOVERY_MESSAGE);
            job.setCompletedAt(now);
            jobs.save(job);
            log.info("[Job {}] recovered -> FAILED (interrupted by shutdown)", job.getId());
        }
    }
}
