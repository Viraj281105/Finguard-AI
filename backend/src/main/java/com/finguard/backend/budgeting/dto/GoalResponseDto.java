package com.finguard.backend.budgeting.dto;

import com.finguard.backend.budgeting.domain.GoalStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record GoalResponseDto(
        String id,
        String name,
        String description,
        BigDecimal targetAmount,
        BigDecimal currentAmount,
        BigDecimal remainingAmount,
        BigDecimal progressPercentage,
        LocalDate targetDate,
        Long daysRemaining,
        BigDecimal requiredMonthlyPace,
        GoalStatus status,
        boolean isOverdue,
        int contributionCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
