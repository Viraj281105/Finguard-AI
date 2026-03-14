package com.finguard.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "loans")
@Data
public class Loan {

    @Id
    private String id = UUID.randomUUID().toString();

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name; // e.g. "Home Loan - SBI"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanType type; // HOME, CAR, PERSONAL, EDUCATION, OTHER

    @Column(nullable = false)
    private BigDecimal principal;

    @Column(nullable = false)
    private BigDecimal emiAmount;

    @Column(nullable = false)
    private BigDecimal interestRate;

    @Column
    private LocalDate startDate;

    @Column
    private LocalDate endDate;

    @Column
    private Boolean active = true;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum LoanType {
        HOME, CAR, PERSONAL, EDUCATION, OTHER
    }
}