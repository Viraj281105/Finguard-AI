package com.finguard.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "investments")
@Data
public class Investment {

    @Id
    private String id = UUID.randomUUID().toString();

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name; // e.g. "Zerodha - NIFTY50"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvestmentType type; // STOCKS, MUTUAL_FUND, FD, CRYPTO, GOLD, OTHER

    @Column(nullable = false)
    private BigDecimal amountInvested;

    @Column
    private BigDecimal currentValue;

    @Column
    private String notes;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum InvestmentType {
        STOCKS, MUTUAL_FUND, FD, CRYPTO, GOLD, OTHER
    }
}