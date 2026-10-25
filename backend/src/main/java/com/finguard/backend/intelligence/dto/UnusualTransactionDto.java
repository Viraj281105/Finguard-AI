package com.finguard.backend.intelligence.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UnusualTransactionDto(
        String transactionId,
        String title,
        BigDecimal amount,
        LocalDate date,
        String category,
        BigDecimal benchmarkAmount,
        BigDecimal ratio,
        String reason,
        String rule
) {
}
