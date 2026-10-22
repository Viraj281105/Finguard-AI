package com.finguard.backend.ledger.transactions;

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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single money movement. {@code amount} is always positive; {@code type} carries the direction.
 */
@Getter
@Setter
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(name = "txn_date", nullable = false)
    private LocalDate date;

    @Column
    private String category;

    @Column
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionSource source = TransactionSource.MANUAL;

    @Column(name = "import_job_id")
    private String importJobId;

    @Column(name = "fingerprint", length = 64)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_source", nullable = false)
    private CategorySource categorySource = CategorySource.NONE;

    @Column(name = "category_confidence", precision = 5, scale = 4)
    private BigDecimal categoryConfidence;

    @Column(name = "category_model_version")
    private String categoryModelVersion;

    /** True when the automatic category rests on weak or competing evidence and a person should look at it. */
    @Column(name = "category_needs_review", nullable = false)
    private boolean categoryNeedsReview;

    /** Whether {@code categoryConfidence} is a calibrated probability. False for the rule baseline. */
    @Column(name = "category_confidence_calibrated", nullable = false)
    private boolean categoryConfidenceCalibrated;

    /** Id of the rule that produced an automatic category, e.g. {@code dining.zomato}. */
    @Column(name = "category_rule", length = 64)
    private String categoryRule;

    @Column(name = "category_updated_at")
    private LocalDateTime categoryUpdatedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum TransactionType {
        INCOME, EXPENSE
    }

    public enum TransactionSource {
        MANUAL, IMPORT
    }

    /** Where the current category came from. {@code USER} is authoritative and is never overwritten by automation. */
    public enum CategorySource {
        NONE, RULE, MODEL, USER
    }

    /** Records a category chosen by a person and clears every automatic-classification attribute. */
    public void assignUserCategory(TransactionCategory chosen) {
        this.category = chosen.name();
        this.categorySource = CategorySource.USER;
        resetAutomaticProvenance();
    }

    /** Removes the category together with its provenance. */
    public void clearCategory() {
        this.category = null;
        this.categorySource = CategorySource.NONE;
        resetAutomaticProvenance();
    }

    private void resetAutomaticProvenance() {
        this.categoryConfidence = null;
        this.categoryModelVersion = null;
        this.categoryNeedsReview = false;
        this.categoryConfidenceCalibrated = false;
        this.categoryRule = null;
        this.categoryUpdatedAt = LocalDateTime.now();
    }
}
