package com.finguard.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/*
 * UploadJob Entity
 * ----------------
 * Tracks the lifecycle of an async CSV upload job.
 *
 * Why do we need this?
 * --------------------
 * When a user uploads a CSV with 500 rows, we can't make them
 * wait 30-60 seconds for a response. So instead:
 *
 *   1. User uploads CSV
 *   2. We immediately create an UploadJob (status = PENDING)
 *   3. We return the jobId to frontend right away (~50ms)
 *   4. A background thread processes the CSV asynchronously
 *   5. Frontend polls GET /api/upload/status/{jobId} to check progress
 *
 * Status flow:
 *   PENDING → PROCESSING → COMPLETED
 *                       ↘ FAILED (if error occurs)
 */
@Entity
@Table(name = "upload_jobs")
@Data
public class UploadJob {

    /*
     * UUID primary key — same pattern as Viraj's Transaction entity.
     */
    @Id
    private String id = UUID.randomUUID().toString();

    /*
     * Which user triggered this upload.
     * Many upload jobs can belong to one user.
     * fetch = LAZY → don't load full User object unless explicitly needed.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /*
     * Original filename the user uploaded.
     * Example: "HDFC_March2026.csv"
     * Stored for display in upload history.
     */
    @Column(name = "original_filename")
    private String originalFilename;

    /*
     * Which bank was auto-detected from the CSV header.
     * Example: "HDFC", "ICICI", "SBI", "AXIS", "KOTAK", "UNKNOWN"
     */
    @Column(name = "bank_name")
    private String bankName;

    /*
     * Current processing status.
     * @Enumerated(EnumType.STRING) → stored as "PENDING"/"COMPLETED" etc.
     * in the DB (not as numbers), which is safer and more readable.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private JobStatus status = JobStatus.PENDING;

    /*
     * How many transaction rows were successfully saved.
     * Starts at 0, updated when processing completes.
     */
    @Column(name = "rows_processed")
    private Integer rowsProcessed = 0;

    /*
     * Total rows found in the CSV (excluding header row).
     * Set at the start of processing so frontend can show a progress %.
     */
    @Column(name = "total_rows")
    private Integer totalRows = 0;

    /*
     * Error message if status = FAILED.
     * Example: "Unrecognized CSV format. Please upload HDFC/ICICI/SBI/Axis/Kotak statement."
     * columnDefinition = "TEXT" → no 255 char limit.
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /*
     * Timestamp when the job was created (upload received).
     * @CreationTimestamp → Hibernate sets this automatically on first save.
     * Same pattern Viraj used in Transaction.java.
     */
    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /*
     * Timestamp when processing finished (success or failure).
     * Null while still in PENDING/PROCESSING state.
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /*
     * JobStatus Enum
     * --------------
     * PENDING     → Job created, background thread hasn't started yet
     * PROCESSING  → Background thread is actively parsing the CSV
     * COMPLETED   → All rows saved to DB successfully
     * FAILED      → Something went wrong; see errorMessage field
     */
    public enum JobStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}