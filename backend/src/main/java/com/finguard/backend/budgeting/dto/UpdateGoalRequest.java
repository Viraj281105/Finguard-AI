package com.finguard.backend.budgeting.dto;

import com.finguard.backend.budgeting.domain.GoalStatus;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateGoalRequest(
        String name,
        String description,
        @Positive(message = "targetAmount must be greater than zero")
        BigDecimal targetAmount,
        LocalDate targetDate,
        GoalStatus status
) {}
