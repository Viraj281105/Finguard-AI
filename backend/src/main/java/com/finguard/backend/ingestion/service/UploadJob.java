package com.finguard.backend.ingestion.service;

import com.finguard.backend.categorization.ClassificationStatus;
import com.finguard.backend.identity.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks one statement import: {@code PENDING -> PROCESSING -> COMPLETED}, or {@code FAILED}
 * with a user-facing {@code errorMessage}.
 */
@Getter
@Setter
@Entity
@Table(name = "upload_jobs")
public class UploadJob {

    @Id
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "original_filename")
    private String originalFilename;

    /** Name of the detected {@code BankFormat}, e.g. "HDFC" or "UNKNOWN". */
    @Column(name = "bank_name")
    private String bankName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private JobStatus status = JobStatus.PENDING;

    @Column(name = "rows_processed")
    private Integer rowsProcessed = 0;

    /** Number of duplicate transactions skipped during import. */
    @Column(name = "duplicate_rows")
    private Integer duplicateRows = 0;

    /** Number of malformed rows rejected with errors. */
    @Column(name = "rejected_rows", nullable = false)
    private Integer rejectedRows = 0;

    /** Number of non-financial or intentionally skipped rows. */
    @Column(name = "skipped_rows", nullable = false)
    private Integer skippedRows = 0;

    /** JSON-encoded list of row errors (rowNumber, errorMessage, snippet). */
    @Column(name = "row_errors_json", columnDefinition = "TEXT")
    private String rowErrorsJson;

    /** Closing balance as extracted from the statement if present. */
    @Column(name = "closing_balance")
    private java.math.BigDecimal closingBalance;

    /** Statement period start date if extracted. */
    @Column(name = "statement_from")
    private java.time.LocalDate statementFrom;

    /** Statement period end date if extracted. */
    @Column(name = "statement_to")
    private java.time.LocalDate statementTo;

    /** Total number of data rows in the statement (excluding header and top metadata). */
    @Column(name = "total_rows")
    private Integer totalRows = 0;

    /** Outcome of automatic categorisation for this import; independent of whether the import itself succeeded. */
    @Enumerated(EnumType.STRING)
    @Column(name = "classification_status", nullable = false)
    private ClassificationStatus classificationStatus = ClassificationStatus.NOT_ATTEMPTED;

    /** Imported transactions that received an automatic category. */
    @Column(name = "classified_rows", nullable = false)
    private Integer classifiedRows = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public enum JobStatus {
        PENDING, PROCESSING, COMPLETED, FAILED
    }
}
