package com.finguard.backend.forecasting.domain;

import com.finguard.backend.identity.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A scenario the user chose to keep: the adjustments and the result snapshot as JSON text, plus the method
 * version that produced it. No transaction, budget or goal data is copied.
 */
@Getter
@Setter
@Entity
@Table(name = "saved_scenarios")
public class SavedScenario {

    @Id
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "horizon_months", nullable = false)
    private int horizonMonths;

    @Column(name = "request_json", nullable = false, columnDefinition = "TEXT")
    private String requestJson;

    @Column(name = "result_json", nullable = false, columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "method_version", nullable = false, length = 80)
    private String methodVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
