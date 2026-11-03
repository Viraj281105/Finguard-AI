package com.finguard.backend.ingestion.service;

import com.finguard.backend.categorization.CategorizationService;
import com.finguard.backend.categorization.CategorizationSummary;
import com.finguard.backend.categorization.ClassificationStatus;
import com.finguard.backend.common.events.OutboxEventService;
import com.finguard.backend.common.observability.FinGuardMetrics;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.identity.domain.UserRepository;
import com.finguard.backend.ingestion.parser.ParsedRow;
import com.finguard.backend.ingestion.parser.StatementParseResult;
import com.finguard.backend.ingestion.parser.StatementParserRegistry;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two overlapping imports can run at once: both see the rows as new and one then hits the unique fingerprint index.
 * The import must look again and keep only what is still new instead of failing the job (audit finding E-08). The
 * end-to-end test for this is timing dependent and passed even when the retry was missing, so this one forces the race.
 */
class ImportWorkerConcurrentRetryTest {

    private static ParsedRow row(String title, String amount) {
        return new ParsedRow(LocalDate.of(2026, 3, 1), title, new BigDecimal(amount), Transaction.TransactionType.EXPENSE, "HDFC");
    }

    private final UploadJobRepository jobs = mock(UploadJobRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final StatementParserRegistry parsers = mock(StatementParserRegistry.class);
    private final TransactionService transactions = mock(TransactionService.class);
    private final CategorizationService categorization = mock(CategorizationService.class);
    private final FinGuardMetrics metrics = mock(FinGuardMetrics.class);
    private final OutboxEventService outbox = mock(OutboxEventService.class);
    private final ImportWorker worker = new ImportWorker(jobs, users, parsers, transactions, categorization, metrics, outbox);

    private UploadJob pendingJob() {
        UploadJob job = new UploadJob();
        job.setId("job-1");
        job.setBankName("HDFC");
        job.setStatus(UploadJob.JobStatus.PENDING);
        User user = new User();
        user.setId("user-1");
        job.setUser(user);
        return job;
    }

    @SuppressWarnings("unchecked")
    @Test
    void aUniqueViolationFromAConcurrentImportIsRetriedAndTheJobCompletes() {
        UploadJob job = pendingJob();
        User owner = new User();
        owner.setId("user-1");
        when(jobs.findById("job-1")).thenReturn(Optional.of(job));
        when(jobs.save(any(UploadJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(users.getReferenceById("user-1")).thenReturn(owner);
        when(parsers.parseStatement(anyString())).thenReturn(new StatementParseResult(
                List.of(row("UPI-ONE", "10.00"), row("UPI-TWO", "20.00")), List.of(), 2, 0, null, null, null));
        when(categorization.classifyImported(anyString(), anyList()))
                .thenReturn(new CategorizationSummary(ClassificationStatus.UNAVAILABLE, 0, 0, 0));

        // First look: both rows are new. Second look (after the other import committed one of them): one is a duplicate.
        when(transactions.findExistingFingerprints(anyString(), anyCollection()))
                .thenReturn(Set.of())
                .thenAnswer(inv -> Set.of(((java.util.Collection<String>) inv.getArgument(1)).iterator().next()));
        when(transactions.saveAll(anyList()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint uq_transactions_user_fingerprint"))
                .thenAnswer(inv -> inv.getArgument(0));

        worker.process("job-1", "csv", "user-1");

        assertThat(job.getStatus()).isEqualTo(UploadJob.JobStatus.COMPLETED);
        assertThat(job.getRowsProcessed()).isEqualTo(1);
        assertThat(job.getDuplicateRows()).isEqualTo(1);
        verify(transactions, times(2)).saveAll(anyList());
    }

    @Test
    void thePersistentViolationEventuallyFailsTheJobInsteadOfLoopingForever() {
        UploadJob job = pendingJob();
        User owner = new User();
        owner.setId("user-1");
        when(jobs.findById("job-1")).thenReturn(Optional.of(job));
        when(jobs.save(any(UploadJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(users.getReferenceById("user-1")).thenReturn(owner);
        when(parsers.parseStatement(anyString())).thenReturn(new StatementParseResult(
                List.of(row("UPI-ONE", "10.00")), List.of(), 1, 0, null, null, null));
        when(transactions.findExistingFingerprints(anyString(), anyCollection())).thenReturn(Set.of());
        when(transactions.saveAll(anyList())).thenThrow(new DataIntegrityViolationException("always"));

        worker.process("job-1", "csv", "user-1");

        assertThat(job.getStatus()).isEqualTo(UploadJob.JobStatus.FAILED);
        verify(transactions, times(3)).saveAll(anyList());
    }
}
