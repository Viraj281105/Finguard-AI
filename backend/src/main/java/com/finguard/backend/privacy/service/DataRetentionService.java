package com.finguard.backend.privacy.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Automated data retention and grace-period deletion lifecycle jobs (S1-05 — Project J, 05 §8):
 * <ul>
 *   <li>Hard-deletes accounts whose deletion grace period ({@code deletion_scheduled_at}) has elapsed.</li>
 *   <li>Purges {@code upload_jobs.row_errors_json} older than 90 days.</li>
 *   <li>Purges published {@code event_outbox} rows older than 14 days.</li>
 *   <li>Purges {@code processed_event} inbox deduplication records older than 30 days.</li>
 * </ul>
 */
@Slf4j
@Service
public class DataRetentionService {

    private final JdbcTemplate jdbc;
    private final PrivacyService privacyService;
    private final Clock clock;
    private final int rowErrorsRetentionDays;
    private final int outboxRetentionDays;
    private final int inboxRetentionDays;

    public DataRetentionService(
            JdbcTemplate jdbc,
            PrivacyService privacyService,
            Clock clock,
            @Value("${app.privacy.row-errors-retention-days:90}") int rowErrorsRetentionDays,
            @Value("${app.privacy.outbox-retention-days:14}") int outboxRetentionDays,
            @Value("${app.privacy.inbox-retention-days:30}") int inboxRetentionDays
    ) {
        this.jdbc = jdbc;
        this.privacyService = privacyService;
        this.clock = clock;
        this.rowErrorsRetentionDays = rowErrorsRetentionDays;
        this.outboxRetentionDays = outboxRetentionDays;
        this.inboxRetentionDays = inboxRetentionDays;
    }

    /**
     * Executes hard deletion for all user accounts whose grace period has expired as of {@code asOf}.
     *
     * @return number of user accounts permanently deleted
     */
    @Transactional
    public int purgeExpiredDeletionRequests(Instant asOf) {
        Instant cutoff = asOf != null ? asOf : Instant.now(clock);
        List<String> dueUserIds = jdbc.queryForList(
                "SELECT id FROM users WHERE deletion_scheduled_at IS NOT NULL AND deletion_scheduled_at <= ? ORDER BY deletion_scheduled_at ASC",
                String.class,
                Timestamp.from(cutoff)
        );
        int deletedUsers = 0;
        for (String userId : dueUserIds) {
            privacyService.executeHardDelete(userId, "GRACE_EXPIRED");
            deletedUsers++;
        }
        return deletedUsers;
    }

    /**
     * Nullifies {@code row_errors_json} on {@code upload_jobs} older than {@code rowErrorsRetentionDays} (default 90d).
     */
    @Transactional
    public int purgeExpiredRowErrors(Instant asOf) {
        Instant now = asOf != null ? asOf : Instant.now(clock);
        Instant cutoff = now.minus(Duration.ofDays(rowErrorsRetentionDays));
        return jdbc.update(
                """
                UPDATE upload_jobs
                SET row_errors_json = NULL
                WHERE row_errors_json IS NOT NULL
                  AND coalesce(completed_at, created_at) < ?
                """,
                Timestamp.from(cutoff)
        );
    }

    /**
     * Deletes published {@code event_outbox} rows older than {@code outboxRetentionDays} (default 14d).
     * Unpublished rows are never deleted by retention.
     */
    @Transactional
    public int purgePublishedOutboxEvents(Instant asOf) {
        Instant now = asOf != null ? asOf : Instant.now(clock);
        Instant cutoff = now.minus(Duration.ofDays(outboxRetentionDays));
        return jdbc.update(
                "DELETE FROM event_outbox WHERE published_at IS NOT NULL AND published_at < ?",
                Timestamp.from(cutoff)
        );
    }

    /**
     * Deletes {@code processed_event} inbox rows older than {@code inboxRetentionDays} (default 30d).
     */
    @Transactional
    public int purgeProcessedInboxEvents(Instant asOf) {
        Instant now = asOf != null ? asOf : Instant.now(clock);
        Instant cutoff = now.minus(Duration.ofDays(inboxRetentionDays));
        return jdbc.update(
                "DELETE FROM processed_event WHERE processed_at < ?",
                Timestamp.from(cutoff)
        );
    }

    @Scheduled(cron = "${app.privacy.retention-cron:0 30 3 * * ?}")
    public void runScheduledRetention() {
        try {
            Instant now = Instant.now(clock);
            int accounts = purgeExpiredDeletionRequests(now);
            int rowErrors = purgeExpiredRowErrors(now);
            int outbox = purgePublishedOutboxEvents(now);
            int inbox = purgeProcessedInboxEvents(now);
            if (accounts > 0 || rowErrors > 0 || outbox > 0 || inbox > 0) {
                log.info("Scheduled privacy retention completed: deletedAccounts={}, clearedRowErrors={}, purgedOutbox={}, purgedInbox={}",
                        accounts, rowErrors, outbox, inbox);
            }
        } catch (Exception e) {
            log.error("Scheduled privacy retention job failed: {}", e.getMessage());
        }
    }
}

