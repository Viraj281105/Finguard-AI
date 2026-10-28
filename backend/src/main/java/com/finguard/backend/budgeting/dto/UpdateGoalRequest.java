package com.finguard.backend.budgeting.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMax;
import com.finguard.backend.budgeting.domain.GoalStatus;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateGoalRequest(
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,
        @Size(max = 2000, message = "description must be at most 2000 characters")
        String description,
        @Positive(message = "targetAmount must be greater than zero")
        @DecimalMax(value = "10000000000000", message = "amount is too large") @Digits(integer = 14, fraction = 2, message = "amount must have at most 2 decimal places")
        BigDecimal targetAmount,
        LocalDate targetDate,
        GoalStatus status
) {}
