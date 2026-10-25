package com.finguard.backend.budgeting.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BudgetDto(
        String id,
        String yearMonth,
        String category,
        BigDecimal amount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
