package com.finguard.backend.ingestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.backend.categorization.CategorizationService;
import com.finguard.backend.categorization.CategorizationSummary;
import com.finguard.backend.categorization.ClassificationStatus;
import com.finguard.backend.common.config.AsyncConfig;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.identity.domain.UserRepository;
import com.finguard.backend.ingestion.parser.ParsedRow;
import com.finguard.backend.ingestion.parser.RowError;
import com.finguard.backend.ingestion.parser.StatementParseResult;
import com.finguard.backend.ingestion.parser.StatementParserRegistry;
import com.finguard.backend.ingestion.service.UploadJob.JobStatus;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a statement import on the import thread pool.
 *
 * <p>This is a separate bean from {@link ImportService} on purpose: {@code @Async} only works when
 * the call goes through the Spring proxy, which a call from within the same class would bypass.
 *
 * <p>Job state changes are committed immediately (no surrounding transaction) so the frontend can
 * poll real progress. Transactions are persisted in one atomic step, after classification, so a
 * failed import never leaves a partial set of rows behind.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportWorker {

    private static final String GENERIC_FAILURE = "Unexpected error while importing the statement. Please try again.";
    private static final int MAX_STORED_ROW_ERRORS = 50;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UploadJobRepository jobs;
    private final UserRepository users;
    private final StatementParserRegistry parsers;
    private final TransactionService transactions;
    private final CategorizationService categorization;

    @Async(AsyncConfig.IMPORT_EXECUTOR)
    public void process(String jobId, String csvContent, String userId) {
        UploadJob job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            log.error("[Job {}] vanished before processing started", jobId);
            return;
        }

        if (job.getStatus() != JobStatus.PENDING) {
            log.warn("[Job {}] already in status {}, skipping duplicate execution", jobId, job.getStatus());
            return;
        }

        try {
            update(job, JobStatus.PROCESSING);
            log.info("[Job {}] -> PROCESSING", jobId);

            StatementParseResult parseResult = parsers.parseStatement(csvContent);
            List<ParsedRow> rows = parseResult.rows();

            job.setTotalRows(parseResult.totalDataRows());
            job.setRejectedRows(parseResult.errors().size());
            job.setSkippedRows(parseResult.skippedRows());
            job.setClosingBalance(parseResult.closingBalance());
            job.setStatementFrom(parseResult.statementFrom());
            job.setStatementTo(parseResult.statementTo());
            job.setRowErrorsJson(serializeErrors(parseResult.errors()));
            jobs.save(job);

            log.info("[Job {}] parsed {} valid rows, {} errors, {} skipped from {} statement (total data rows: {})",
                    jobId, rows.size(), parseResult.errors().size(), parseResult.skippedRows(),
                    job.getBankName(), parseResult.totalDataRows());

            User owner = users.getReferenceById(userId);
            List<Transaction> candidates = toTransactions(rows, owner, jobId);

            Set<String> candidateFingerprints = candidates.stream()
                    .map(Transaction::getFingerprint)
                    .collect(java.util.stream.Collectors.toSet());

            Set<String> existingFingerprints = transactions.findExistingFingerprints(userId, candidateFingerprints);

            List<Transaction> toImport = new ArrayList<>();
            int duplicateCount = 0;
            for (Transaction tx : candidates) {
                if (existingFingerprints.contains(tx.getFingerprint())) {
                    duplicateCount++;
                } else {
                    toImport.add(tx);
                }
            }

            // Persist first: saving financial data never depends on the AI service.
            List<Transaction> saved = toImport.isEmpty() ? List.of() : transactions.saveAll(toImport);

            job.setRowsProcessed(toImport.size());
            job.setDuplicateRows(duplicateCount);
            categorize(job, userId, saved);
            job.setCompletedAt(LocalDateTime.now());
            update(job, JobStatus.COMPLETED);
            log.info("[Job {}] -> COMPLETED ({} imported, {} duplicates, {} rejected, {} skipped)",
                    jobId, toImport.size(), duplicateCount, job.getRejectedRows(), job.getSkippedRows());

        } catch (IllegalArgumentException e) {
            // Parser errors carry a message meant for the user (unrecognised format, missing header, ...).
            fail(job, e.getMessage());
        } catch (Exception e) {
            log.error("[Job {}] failed", jobId, e);
            fail(job, GENERIC_FAILURE);
        }
    }

    private List<Transaction> toTransactions(List<ParsedRow> rows, User owner, String jobId) {
        record OccurrenceKey(java.time.LocalDate date, Transaction.TransactionType type, java.math.BigDecimal amount, String normalizedTitle) {}
        Map<OccurrenceKey, Integer> occurrences = new java.util.HashMap<>();

        List<Transaction> result = new ArrayList<>(rows.size());
        for (ParsedRow row : rows) {
            String normTitle = (row.title() == null ? "" : row.title().trim().toLowerCase().replaceAll("\\s+", " "));
            OccurrenceKey key = new OccurrenceKey(row.date(), row.type(), row.amount(), normTitle);
            int occurrenceIndex = occurrences.merge(key, 1, Integer::sum);

            String fingerprint = com.finguard.backend.ledger.transactions.TransactionFingerprint.compute(
                    owner.getId(), row.date(), row.type(), row.amount(), row.title(), occurrenceIndex
            );

            Transaction tx = new Transaction();
            tx.setUser(owner);
            tx.setDate(row.date());
            tx.setTitle(row.title());
            tx.setAmount(row.amount());
            tx.setType(row.type());
            tx.setSource(Transaction.TransactionSource.IMPORT);
            tx.setImportJobId(jobId);
            tx.setFingerprint(fingerprint);
            tx.setNote("Imported from " + row.bankName() + " | " + row.date());
            result.add(tx);
        }
        return result;
    }

    /**
     * Best effort, after the transactions are safely stored: whatever happens here (AI service down,
     * slow, wrong, or a failure while applying its answer) the job still completes and the rows stay
     * uncategorised. Categories are applied only to rows nobody has categorised yet.
     */
    private void categorize(UploadJob job, String userId, List<Transaction> saved) {
        CategorizationSummary summary;
        try {
            summary = categorization.classifyImported(userId, saved);
        } catch (RuntimeException e) {
            log.error("[Job {}] categorisation failed unexpectedly; import is unaffected", job.getId(), e);
            summary = new CategorizationSummary(ClassificationStatus.UNAVAILABLE, saved.size(), 0, 0);
        }
        job.setClassificationStatus(summary.status());
        job.setClassifiedRows(summary.categorized());
        log.info("[Job {}] categorisation {}: {} of {} rows categorised ({} flagged for review)", job.getId(),
                summary.status(), summary.categorized(), summary.attempted(), summary.needsReview());
    }

    private String serializeErrors(List<RowError> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        try {
            List<RowError> bounded = errors.size() > MAX_STORED_ROW_ERRORS
                    ? errors.subList(0, MAX_STORED_ROW_ERRORS)
                    : errors;
            return MAPPER.writeValueAsString(bounded);
        } catch (Exception e) {
            log.warn("Could not serialize row errors: {}", e.getMessage());
            return null;
        }
    }

    private void update(UploadJob job, JobStatus status) {
        job.setStatus(status);
        jobs.save(job);
    }

    private void fail(UploadJob job, String message) {
        job.setErrorMessage(message);
        job.setCompletedAt(LocalDateTime.now());
        update(job, JobStatus.FAILED);
        log.warn("[Job {}] -> FAILED: {}", job.getId(), message);
    }
}
