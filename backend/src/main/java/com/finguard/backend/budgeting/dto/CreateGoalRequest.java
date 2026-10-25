package com.finguard.backend.budgeting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateGoalRequest(
        @NotBlank(message = "Goal name is required")
        String name,

        String description,

        @NotNull(message = "targetAmount is required")
        @Positive(message = "targetAmount must be greater than zero")
        BigDecimal targetAmount,

        LocalDate targetDate
) {}
