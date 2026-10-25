package com.finguard.backend.budgeting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record GoalContributionDto(
        String id,
        String goalId,
        BigDecimal amount,
        LocalDate contributionDate,
        String note,
        LocalDateTime createdAt
) {}
