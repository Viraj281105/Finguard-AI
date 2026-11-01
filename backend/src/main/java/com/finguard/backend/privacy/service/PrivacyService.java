package com.finguard.backend.privacy.service;

import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.common.events.DomainEventEnvelope;
import com.finguard.backend.common.events.OutboxEventService;
import com.finguard.backend.common.observability.TraceContext;
import com.finguard.backend.common.tenant.TenantContext;
import com.finguard.backend.identity.domain.RefreshTokenRepository;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.identity.domain.UserRepository;
import com.finguard.backend.identity.security.AuthRateLimiter;
import com.finguard.backend.privacy.dto.AccountDeletionRequest;
import com.finguard.backend.privacy.dto.AccountDeletionResponse;
import com.finguard.backend.privacy.dto.AccountExportResponse;
import com.finguard.backend.privacy.dto.AccountExportResponse.ExportBudgetDto;
import com.finguard.backend.privacy.dto.AccountExportResponse.ExportGoalContributionDto;
import com.finguard.backend.privacy.dto.AccountExportResponse.ExportGoalDto;
import com.finguard.backend.privacy.dto.AccountExportResponse.ExportInvestmentDto;
import com.finguard.backend.privacy.dto.AccountExportResponse.ExportLoanDto;
import com.finguard.backend.privacy.dto.AccountExportResponse.ExportRecordCountsDto;
import com.finguard.backend.privacy.dto.AccountExportResponse.ExportSavedScenarioDto;
import com.finguard.backend.privacy.dto.AccountExportResponse.ExportTransactionDto;
import com.finguard.backend.privacy.dto.AccountExportResponse.ExportUploadJobDto;
import com.finguard.backend.privacy.dto.AccountExportResponse.UserProfileExportDto;
import com.finguard.backend.privacy.dto.AuditLogEntryDto;
import com.finguard.backend.privacy.dto.PrivacyStatusResponse;
import com.finguard.backend.privacy.dto.PrivacyStatusResponse.RetentionPolicyDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Implements the S1-05 Privacy Lifecycle (Project J):
 * <ul>
 *   <li>Complete per-tenant account export in JSON and formula-safe CSV formats.</li>
 *   <li>Re-authenticated account deletion with a configurable grace period and cancellation support.</li>
 *   <li>Explicit FK-ordered hard deletion across all 10 user-owned tables with zero residual rows.</li>
 *   <li>Append-only {@code audit_log} inspection scoped to the authenticated user's hashed tenant ID.</li>
 * </ul>
 */
@Slf4j
@Service
public class PrivacyService {

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimiter authRateLimiter;
    private final OutboxEventService outboxEventService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int deletionGraceDays;
    private final int rowErrorsRetentionDays;
    private final int outboxRetentionDays;
    private final int inboxRetentionDays;
    private final int auditLogRetentionDays;

    public PrivacyService(
            JdbcTemplate jdbc,
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            AuthRateLimiter authRateLimiter,
            OutboxEventService outboxEventService,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${app.privacy.deletion-grace-days:30}") int deletionGraceDays,
            @Value("${app.privacy.row-errors-retention-days:90}") int rowErrorsRetentionDays,
            @Value("${app.privacy.outbox-retention-days:14}") int outboxRetentionDays,
            @Value("${app.privacy.inbox-retention-days:30}") int inboxRetentionDays,
            @Value("${app.privacy.audit-log-retention-days:365}") int auditLogRetentionDays
    ) {
        this.jdbc = jdbc;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.authRateLimiter = authRateLimiter;
        this.outboxEventService = outboxEventService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.deletionGraceDays = deletionGraceDays;
        this.rowErrorsRetentionDays = rowErrorsRetentionDays;
        this.outboxRetentionDays = outboxRetentionDays;
        this.inboxRetentionDays = inboxRetentionDays;
        this.auditLogRetentionDays = auditLogRetentionDays;
    }

    @Transactional(readOnly = true)
    public PrivacyStatusResponse getStatus(User principal) {
        User user = loadFreshUser(principal.getId());
        return toStatusResponse(user);
    }

    @Transactional
    public AccountExportResponse exportAccountJson(User principal, String password) {
        authRateLimiter.checkPrivacyActionAllowed(principal.getId(), "export");
        User user = loadFreshUser(principal.getId());
        verifyPassword(user, password);

        Instant now = Instant.now(clock);
        AccountExportResponse export = buildAccountExport(user, now);

        outboxEventService.publish(
                "user",
                user.getId(),
                DomainEventEnvelope.TYPE_ACCOUNT_EXPORTED,
                "finguard-backend/privacy",
                "user/" + user.getId(),
                user.getId(),
                new DomainEventEnvelope.AccountExportedPayload(
                        "JSON",
                        export.recordCounts().totalRecords(),
                        now.toString()
                )
        );
        return export;
    }

    @Transactional
    public String exportAccountCsv(User principal, String password) {
        authRateLimiter.checkPrivacyActionAllowed(principal.getId(), "export");
        User user = loadFreshUser(principal.getId());
        verifyPassword(user, password);

        Instant now = Instant.now(clock);
        AccountExportResponse export = buildAccountExport(user, now);
        String csv = renderCsvExport(export);

        outboxEventService.publish(
                "user",
                user.getId(),
                DomainEventEnvelope.TYPE_ACCOUNT_EXPORTED,
                "finguard-backend/privacy",
                "user/" + user.getId(),
                user.getId(),
                new DomainEventEnvelope.AccountExportedPayload(
                        "CSV",
                        export.recordCounts().totalRecords(),
                        now.toString()
                )
        );
        return csv;
    }

    @Transactional
    public AccountDeletionResponse requestAccountDeletion(User principal, AccountDeletionRequest request) {
        authRateLimiter.checkPrivacyActionAllowed(principal.getId(), "delete");
        User user = loadFreshUser(principal.getId());
        verifyPassword(user, request.password());

        Instant requestedAt = Instant.now(clock);
        Instant graceUntil = requestedAt.plus(Duration.ofDays(deletionGraceDays));

        user.setDeletionRequestedAt(requestedAt);
        user.setDeletionScheduledAt(graceUntil);
        userRepository.save(user);

        // Revoke all active refresh token sessions upon deletion request
        refreshTokenRepository.revokeAllForUserId(user.getId());

        outboxEventService.publish(
                "user",
                user.getId(),
                DomainEventEnvelope.TYPE_ACCOUNT_DELETION_REQUESTED,
                "finguard-backend/privacy",
                "user/" + user.getId(),
                user.getId(),
                new DomainEventEnvelope.AccountDeletionRequestedPayload(
                        requestedAt.toString(),
                        graceUntil.toString()
                )
        );

        if (Boolean.TRUE.equals(request.immediate())) {
            int deletedRows = executeHardDelete(user.getId(), "IMMEDIATE");
            return new AccountDeletionResponse(
                    "DELETED",
                    requestedAt.toString(),
                    graceUntil.toString(),
                    deletedRows
            );
        }

        return new AccountDeletionResponse(
                "SCHEDULED",
                requestedAt.toString(),
                graceUntil.toString(),
                0
        );
    }

    @Transactional
    public PrivacyStatusResponse cancelAccountDeletion(User principal, String password) {
        authRateLimiter.checkPrivacyActionAllowed(principal.getId(), "delete-cancel");
        User user = loadFreshUser(principal.getId());
        verifyPassword(user, password);

        if (user.getDeletionRequestedAt() == null) {
            throw ApiException.badRequest("No pending account deletion request to cancel");
        }

        Instant cancelledAt = Instant.now(clock);
        user.setDeletionRequestedAt(null);
        user.setDeletionScheduledAt(null);
        userRepository.save(user);

        outboxEventService.publish(
                "user",
                user.getId(),
                DomainEventEnvelope.TYPE_ACCOUNT_DELETION_CANCELLED,
                "finguard-backend/privacy",
                "user/" + user.getId(),
                user.getId(),
                new DomainEventEnvelope.AccountDeletionCancelledPayload(cancelledAt.toString())
        );

        return toStatusResponse(user);
    }

    /**
     * Permanently deletes all user-owned data in strict FK-safe order (05-data-and-event-architecture.md §8):
     * <ol>
     *   <li>{@code goal_contributions}</li>
     *   <li>{@code financial_goals}</li>
     *   <li>{@code budgets}</li>
     *   <li>{@code saved_scenarios}</li>
     *   <li>{@code transactions} (references {@code upload_jobs} without cascade)</li>
     *   <li>{@code upload_jobs} (references {@code users} without cascade)</li>
     *   <li>{@code loans} (references {@code users} without cascade)</li>
     *   <li>{@code investments} (references {@code users} without cascade)</li>
     *   <li>{@code refresh_tokens}</li>
     *   <li>{@code users}</li>
     * </ol>
     * Emits {@code com.finguard.account.deleted} to the outbox so the append-only {@code audit_log}
     * retains an immutable, hashed-tenant record of the erasure without retaining any PII.
     */
    @Transactional
    public int executeHardDelete(String userId, String mode) {
        return TenantContext.callAs(userId, () -> executeHardDeleteInternal(userId, mode));
    }

    private int executeHardDeleteInternal(String userId, String mode) {
        int deleted = 0;
        deleted += jdbc.update("DELETE FROM goal_contributions WHERE user_id = ?", userId);
        deleted += jdbc.update("DELETE FROM financial_goals WHERE user_id = ?", userId);
        deleted += jdbc.update("DELETE FROM budgets WHERE user_id = ?", userId);
        deleted += jdbc.update("DELETE FROM saved_scenarios WHERE user_id = ?", userId);
        deleted += jdbc.update("DELETE FROM transactions WHERE user_id = ?", userId);
        deleted += jdbc.update("DELETE FROM upload_jobs WHERE user_id = ?", userId);
        deleted += jdbc.update("DELETE FROM loans WHERE user_id = ?", userId);
        deleted += jdbc.update("DELETE FROM investments WHERE user_id = ?", userId);
        deleted += jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
        deleted += jdbc.update("DELETE FROM users WHERE id = ?", userId);

        Instant deletedAt = Instant.now(clock);
        outboxEventService.publish(
                "user",
                userId,
                DomainEventEnvelope.TYPE_ACCOUNT_DELETED,
                "finguard-backend/privacy",
                "user/" + userId,
                userId,
                new DomainEventEnvelope.AccountDeletedPayload(
                        deletedAt.toString(),
                        deleted,
                        mode
                )
        );
        log.info("Completed ordered hard deletion for user hash={}, mode={}, deletedRows={}",
                TraceContext.hashUserId(userId), mode, deleted);
        return deleted;
    }

    @Transactional(readOnly = true)
    public List<AuditLogEntryDto> getAuditLog(User principal) {
        String tenantHash = TraceContext.hashUserId(principal.getId());
        return jdbc.query(
                """
                SELECT id, event_id, event_type, aggregate_type, aggregate_id,
                       trace_id, occurred_at, summary_json::text AS summary_text
                FROM audit_log
                WHERE tenant_hash = ?
                ORDER BY occurred_at DESC, id DESC
                LIMIT 100
                """,
                (rs, rowNum) -> {
                    Map<String, Object> summary = Map.of();
                    String rawSummary = rs.getString("summary_text");
                    if (rawSummary != null && !rawSummary.isBlank()) {
                        try {
                            summary = objectMapper.readValue(rawSummary, new TypeReference<Map<String, Object>>() {});
                        } catch (Exception ignored) {
                        }
                    }
                    Timestamp occurredTs = rs.getTimestamp("occurred_at");
                    return new AuditLogEntryDto(
                            rs.getObject("id").toString(),
                            rs.getObject("event_id").toString(),
                            rs.getString("event_type"),
                            rs.getString("aggregate_type"),
                            rs.getString("aggregate_id"),
                            rs.getString("trace_id"),
                            occurredTs != null ? occurredTs.toInstant().toString() : null,
                            summary
                    );
                },
                tenantHash
        );
    }

    private AccountExportResponse buildAccountExport(User user, Instant exportedAt) {
        String userId = user.getId();

        List<ExportTransactionDto> transactions = jdbc.query(
                """
                SELECT id, title, amount, type, txn_date, category, category_source,
                       category_confidence, category_needs_review, note, source, import_job_id, created_at
                FROM transactions
                WHERE user_id = ?
                ORDER BY txn_date DESC, created_at DESC, id ASC
                """,
                (rs, rowNum) -> new ExportTransactionDto(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getBigDecimal("amount"),
                        rs.getString("type"),
                        toLocalDate(rs.getDate("txn_date")),
                        rs.getString("category"),
                        rs.getString("category_source"),
                        rs.getBigDecimal("category_confidence"),
                        rs.getBoolean("category_needs_review"),
                        rs.getString("note"),
                        rs.getString("source"),
                        rs.getString("import_job_id"),
                        toLocalDateTime(rs.getTimestamp("created_at"))
                ),
                userId
        );

        List<ExportInvestmentDto> investments = jdbc.query(
                """
                SELECT id, name, type, amount_invested, current_value, notes, created_at
                FROM investments
                WHERE user_id = ?
                ORDER BY created_at DESC, id ASC
                """,
                (rs, rowNum) -> new ExportInvestmentDto(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getBigDecimal("amount_invested"),
                        rs.getBigDecimal("current_value"),
                        rs.getString("notes"),
                        toLocalDateTime(rs.getTimestamp("created_at"))
                ),
                userId
        );

        List<ExportLoanDto> loans = jdbc.query(
                """
                SELECT id, name, type, principal, emi_amount, interest_rate,
                       start_date, end_date, active, created_at
                FROM loans
                WHERE user_id = ?
                ORDER BY created_at DESC, id ASC
                """,
                (rs, rowNum) -> new ExportLoanDto(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getBigDecimal("principal"),
                        rs.getBigDecimal("emi_amount"),
                        rs.getBigDecimal("interest_rate"),
                        toLocalDate(rs.getDate("start_date")),
                        toLocalDate(rs.getDate("end_date")),
                        rs.getBoolean("active"),
                        toLocalDateTime(rs.getTimestamp("created_at"))
                ),
                userId
        );

        List<ExportUploadJobDto> uploadJobs = jdbc.query(
                """
                SELECT id, original_filename, bank_name, status,
                       coalesce(total_rows, 0) AS total_rows,
                       coalesce(rows_processed, 0) AS rows_processed,
                       coalesce(duplicate_rows, 0) AS duplicate_rows,
                       coalesce(rejected_rows, 0) AS rejected_rows,
                       coalesce(skipped_rows, 0) AS skipped_rows,
                       closing_balance, statement_from, statement_to,
                       classification_status, coalesce(classified_rows, 0) AS classified_rows,
                       created_at, completed_at
                FROM upload_jobs
                WHERE user_id = ?
                ORDER BY created_at DESC, id ASC
                """,
                (rs, rowNum) -> new ExportUploadJobDto(
                        rs.getString("id"),
                        rs.getString("original_filename"),
                        rs.getString("bank_name"),
                        rs.getString("status"),
                        rs.getInt("total_rows"),
                        rs.getInt("rows_processed"),
                        rs.getInt("duplicate_rows"),
                        rs.getInt("rejected_rows"),
                        rs.getInt("skipped_rows"),
                        rs.getBigDecimal("closing_balance"),
                        toLocalDate(rs.getDate("statement_from")),
                        toLocalDate(rs.getDate("statement_to")),
                        rs.getString("classification_status"),
                        rs.getInt("classified_rows"),
                        toLocalDateTime(rs.getTimestamp("created_at")),
                        toLocalDateTime(rs.getTimestamp("completed_at"))
                ),
                userId
        );

        List<ExportBudgetDto> budgets = jdbc.query(
                """
                SELECT id, year_month, category, amount, created_at, updated_at
                FROM budgets
                WHERE user_id = ?
                ORDER BY year_month DESC, category ASC NULLS FIRST, id ASC
                """,
                (rs, rowNum) -> new ExportBudgetDto(
                        rs.getString("id"),
                        rs.getString("year_month"),
                        rs.getString("category"),
                        rs.getBigDecimal("amount"),
                        toLocalDateTime(rs.getTimestamp("created_at")),
                        toLocalDateTime(rs.getTimestamp("updated_at"))
                ),
                userId
        );

        List<ExportGoalDto> goals = jdbc.query(
                """
                SELECT id, name, description, target_amount, current_amount,
                       target_date, status, created_at, updated_at
                FROM financial_goals
                WHERE user_id = ?
                ORDER BY created_at DESC, id ASC
                """,
                (rs, rowNum) -> new ExportGoalDto(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getBigDecimal("target_amount"),
                        rs.getBigDecimal("current_amount"),
                        toLocalDate(rs.getDate("target_date")),
                        rs.getString("status"),
                        toLocalDateTime(rs.getTimestamp("created_at")),
                        toLocalDateTime(rs.getTimestamp("updated_at"))
                ),
                userId
        );

        List<ExportGoalContributionDto> goalContributions = jdbc.query(
                """
                SELECT id, goal_id, amount, contribution_date, note, created_at
                FROM goal_contributions
                WHERE user_id = ?
                ORDER BY contribution_date DESC, created_at DESC, id ASC
                """,
                (rs, rowNum) -> new ExportGoalContributionDto(
                        rs.getString("id"),
                        rs.getString("goal_id"),
                        rs.getBigDecimal("amount"),
                        toLocalDate(rs.getDate("contribution_date")),
                        rs.getString("note"),
                        toLocalDateTime(rs.getTimestamp("created_at"))
                ),
                userId
        );

        List<ExportSavedScenarioDto> savedScenarios = jdbc.query(
                """
                SELECT id, name, horizon_months, method_version, request_json, result_json, created_at
                FROM saved_scenarios
                WHERE user_id = ?
                ORDER BY created_at DESC, id ASC
                """,
                (rs, rowNum) -> new ExportSavedScenarioDto(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getInt("horizon_months"),
                        rs.getString("method_version"),
                        rs.getString("request_json"),
                        rs.getString("result_json"),
                        toLocalDateTime(rs.getTimestamp("created_at"))
                ),
                userId
        );

        int totalRecords = transactions.size()
                + investments.size()
                + loans.size()
                + uploadJobs.size()
                + budgets.size()
                + goals.size()
                + goalContributions.size()
                + savedScenarios.size();

        UserProfileExportDto profile = new UserProfileExportDto(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getCreatedAt(),
                user.getDeletionRequestedAt() != null ? user.getDeletionRequestedAt().toString() : null,
                user.getDeletionScheduledAt() != null ? user.getDeletionScheduledAt().toString() : null
        );

        ExportRecordCountsDto counts = new ExportRecordCountsDto(
                transactions.size(),
                investments.size(),
                loans.size(),
                uploadJobs.size(),
                budgets.size(),
                goals.size(),
                goalContributions.size(),
                savedScenarios.size(),
                totalRecords
        );

        return new AccountExportResponse(
                exportedAt.toString(),
                profile,
                counts,
                transactions,
                investments,
                loans,
                uploadJobs,
                budgets,
                goals,
                goalContributions,
                savedScenarios
        );
    }

    private String renderCsvExport(AccountExportResponse export) {
        StringBuilder sb = new StringBuilder();
        sb.append(CsvFormulaSanitizer.formatCsvRow(
                "record_type", "id", "date_or_period", "type_or_status",
                "category", "title_or_name", "amount", "secondary_amount", "notes_or_details"
        ));

        UserProfileExportDto p = export.profile();
        sb.append(CsvFormulaSanitizer.formatCsvRow(
                "PROFILE", p.id(), p.createdAt(),
                p.deletionRequestedAt() != null ? "DELETION_PENDING" : "ACTIVE",
                "", p.name(), "", "", p.email()
        ));

        for (ExportTransactionDto tx : export.transactions()) {
            sb.append(CsvFormulaSanitizer.formatCsvRow(
                    "TRANSACTION", tx.id(), tx.date(), tx.type(),
                    tx.category(), tx.title(), tx.amount(), "", tx.note()
            ));
        }

        for (ExportInvestmentDto inv : export.investments()) {
            sb.append(CsvFormulaSanitizer.formatCsvRow(
                    "INVESTMENT", inv.id(), inv.createdAt(), inv.type(),
                    "", inv.name(), inv.amountInvested(), inv.currentValue(), inv.notes()
            ));
        }

        for (ExportLoanDto loan : export.loans()) {
            sb.append(CsvFormulaSanitizer.formatCsvRow(
                    "LOAN", loan.id(), loan.startDate(), loan.type(),
                    loan.active() ? "ACTIVE" : "CLOSED", loan.name(), loan.principal(), loan.emiAmount(),
                    "rate=" + loan.interestRate()
            ));
        }

        for (ExportUploadJobDto job : export.uploadJobs()) {
            sb.append(CsvFormulaSanitizer.formatCsvRow(
                    "UPLOAD_JOB", job.id(), job.createdAt(), job.status(),
                    job.bankName(), job.originalFilename(), job.closingBalance(), job.rowsProcessed(),
                    "total=" + job.totalRows() + ";dup=" + job.duplicateRows()
            ));
        }

        for (ExportBudgetDto b : export.budgets()) {
            sb.append(CsvFormulaSanitizer.formatCsvRow(
                    "BUDGET", b.id(), b.yearMonth(), "BUDGET",
                    b.category() != null ? b.category() : "OVERALL",
                    b.yearMonth(), b.amount(), "", ""
            ));
        }

        for (ExportGoalDto g : export.goals()) {
            sb.append(CsvFormulaSanitizer.formatCsvRow(
                    "GOAL", g.id(), g.targetDate(), g.status(),
                    "", g.name(), g.targetAmount(), g.currentAmount(), g.description()
            ));
        }

        for (ExportGoalContributionDto gc : export.goalContributions()) {
            sb.append(CsvFormulaSanitizer.formatCsvRow(
                    "GOAL_CONTRIBUTION", gc.id(), gc.contributionDate(), "CONTRIBUTION",
                    gc.goalId(), gc.note(), gc.amount(), "", ""
            ));
        }

        for (ExportSavedScenarioDto sc : export.savedScenarios()) {
            sb.append(CsvFormulaSanitizer.formatCsvRow(
                    "SAVED_SCENARIO", sc.id(), sc.createdAt(), sc.methodVersion(),
                    "horizon=" + sc.horizonMonths(), sc.name(), "", "", sc.requestJson()
            ));
        }

        return sb.toString();
    }

    private User loadFreshUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("User account no longer exists"));
    }

    private void verifyPassword(User user, String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank() || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw ApiException.unauthorized("Invalid password confirmation");
        }
    }

    private PrivacyStatusResponse toStatusResponse(User user) {
        return new PrivacyStatusResponse(
                user.getId(),
                user.getEmail(),
                user.getDeletionRequestedAt() != null,
                user.getDeletionRequestedAt() != null ? user.getDeletionRequestedAt().toString() : null,
                user.getDeletionScheduledAt() != null ? user.getDeletionScheduledAt().toString() : null,
                deletionGraceDays,
                new RetentionPolicyDto(
                        rowErrorsRetentionDays,
                        outboxRetentionDays,
                        inboxRetentionDays,
                        auditLogRetentionDays
                )
        );
    }

    private static LocalDate toLocalDate(Date sqlDate) {
        return sqlDate != null ? sqlDate.toLocalDate() : null;
    }

    private static LocalDateTime toLocalDateTime(Timestamp sqlTs) {
        return sqlTs != null ? sqlTs.toLocalDateTime() : null;
    }
}

