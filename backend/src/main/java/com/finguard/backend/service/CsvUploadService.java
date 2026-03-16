package com.finguard.backend.service;

import com.finguard.backend.model.Transaction;
import com.finguard.backend.model.UploadJob;
import com.finguard.backend.model.User;
import com.finguard.backend.repository.TransactionRepository;
import com.finguard.backend.repository.UploadJobRepository;
import com.finguard.backend.util.CsvParserUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/*
 * CsvUploadService
 * ----------------
 * Handles the full async CSV upload flow in two steps:
 *
 * Step 1 — initiateUpload() [runs on HTTP thread, fast]
 *   → Reads the file bytes
 *   → Detects bank format
 *   → Creates UploadJob (status = PENDING) in DB
 *   → Returns jobId to controller immediately (~50ms response)
 *   → Fires off Step 2 in the background
 *
 * Step 2 — processAsync() [runs on csv-async thread, slow]
 *   → Parses all CSV rows
 *   → Converts each row to a Transaction entity
 *   → Batch saves to DB
 *   → Updates job status to COMPLETED (or FAILED on error)
 *
 * @RequiredArgsConstructor → Lombok generates a constructor that
 * injects all final fields. This is constructor injection —
 * the recommended way to do dependency injection in Spring.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsvUploadService {

    private final UploadJobRepository uploadJobRepository;
    private final TransactionRepository transactionRepository;
    private final CsvParserUtil csvParserUtil;

    /*
     * initiateUpload()
     * ----------------
     * Called synchronously by the controller on the HTTP request thread.
     * Must be FAST — we return the jobId immediately so the user
     * doesn't see a loading spinner for 60 seconds.
     *
     * @param file  the CSV file uploaded by the user
     * @param user  the currently authenticated user (from JWT)
     * @return      the created UploadJob (contains the jobId)
     */
    public UploadJob initiateUpload(MultipartFile file, User user) throws IOException {
        // Read file bytes to String (UTF-8)
        // We do this on the HTTP thread so we can detect the bank name
        // immediately and include it in the job record.
        String csvContent = new String(file.getBytes(), StandardCharsets.UTF_8);

        // Quick bank detection — just reads the header row, very fast
        String bankName = csvParserUtil.detectBank(csvContent).name();

        // Create the UploadJob record in DB
        UploadJob job = new UploadJob();
        job.setUser(user);
        job.setOriginalFilename(file.getOriginalFilename());
        job.setBankName(bankName);
        job.setStatus(UploadJob.JobStatus.PENDING);

        uploadJobRepository.save(job);
        log.info("Created upload job [{}] for user [{}] bank [{}]",
                job.getId(), user.getEmail(), bankName);

        // Fire and forget — processAsync runs in a background thread.
        // We pass the csvContent String (not the MultipartFile) because
        // MultipartFile input streams can't be safely passed across threads.
        processAsync(job.getId(), csvContent, user);

        return job;
    }

    /*
     * processAsync()
     * --------------
     * Runs in a background thread from our "csvTaskExecutor" pool.
     * The @Async annotation means Spring intercepts this call and
     * runs it on a different thread — the HTTP request thread
     * returns immediately without waiting for this to finish.
     *
     * @Transactional → wraps the whole method in a DB transaction.
     * If anything fails midway, all DB changes are rolled back.
     */
    @Async("csvTaskExecutor")
    @Transactional
    public void processAsync(String jobId, String csvContent, User user) {

        // Re-fetch the job from DB (we're on a new thread now,
        // need a fresh entity attached to this thread's DB session)
        UploadJob job = uploadJobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

        try {
            // ── 1. Mark as PROCESSING ────────────────────────────
            job.setStatus(UploadJob.JobStatus.PROCESSING);
            uploadJobRepository.save(job);
            log.info("[Job {}] → PROCESSING", jobId);

            // ── 2. Parse the CSV ─────────────────────────────────
            List<CsvParserUtil.ParsedRow> parsedRows = csvParserUtil.parse(csvContent);
            int total = parsedRows.size();
            log.info("[Job {}] Parsed {} rows from {} CSV", jobId, total, job.getBankName());

            job.setTotalRows(total);
            uploadJobRepository.save(job);

            // ── 3. Convert ParsedRows → Transaction entities ─────
            // We build Transaction objects matching Viraj's entity exactly:
            //   title    → the description from CSV
            //   amount   → the transaction amount
            //   type     → INCOME (credit) or EXPENSE (debit)
            //   category → null for now (AI will fill this in Phase 2)
            //   note     → we store the bank name here since Transaction
            //               doesn't have a bankName field
            List<Transaction> transactions = new ArrayList<>();
            for (CsvParserUtil.ParsedRow row : parsedRows) {
                Transaction tx = new Transaction();
                tx.setUser(user);
                tx.setTitle(row.title());
                tx.setAmount(row.amount());
                tx.setType(row.type());
                tx.setCategory(null);          // Phase 2: AI will classify
                tx.setNote("Imported from " + row.bankName() + " | " + row.date());
                transactions.add(tx);
            }

            // ── 4. Batch save all transactions ───────────────────
            // saveAll() is much faster than saving one-by-one in a loop.
            // Hibernate batches the INSERT statements automatically.
            transactionRepository.saveAll(transactions);
            log.info("[Job {}] Saved {} transactions to DB", jobId, transactions.size());

            // ── 5. Mark as COMPLETED ─────────────────────────────
            job.setStatus(UploadJob.JobStatus.COMPLETED);
            job.setRowsProcessed(total);
            job.setCompletedAt(LocalDateTime.now());
            uploadJobRepository.save(job);
            log.info("[Job {}] → COMPLETED", jobId);

        } catch (Exception e) {
            // ── Error path: mark job FAILED ──────────────────────
            log.error("[Job {}] Processing FAILED: {}", jobId, e.getMessage(), e);
            job.setStatus(UploadJob.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(LocalDateTime.now());
            uploadJobRepository.save(job);
        }
    }

    /*
     * getJobStatus()
     * --------------
     * Called by the controller to check job progress.
     * Enforces ownership — user can only see their own jobs.
     *
     * @param jobId   UUID of the upload job
     * @param userId  authenticated user's ID (from JWT)
     */
    public UploadJob getJobStatus(String jobId, String userId) {
        return uploadJobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new RuntimeException("Upload job not found: " + jobId));
    }
}