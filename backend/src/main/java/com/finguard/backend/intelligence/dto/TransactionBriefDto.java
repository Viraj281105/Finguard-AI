package com.finguard.backend.intelligence.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionBriefDto(
        String id,
        String title,
        BigDecimal amount,
        LocalDate date,
        String category
) {
}
