package com.finguard.backend.intelligence.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecurringExpenseItemDto(
        String id,
        String title,
        String merchant,
        String category,
        BigDecimal amount,
        String cadence, // MONTHLY, WEEKLY, ANNUAL
        int occurrences,
        LocalDate lastDate,
        String confidence, // HIGH, MEDIUM, LOW
        boolean isConfirmed,
        String explanation
) {
}
