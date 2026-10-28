package com.finguard.backend.budgeting.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record GoalContributionRequest(
        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        @DecimalMax(value = "10000000000000", message = "amount is too large") @Digits(integer = 14, fraction = 2, message = "amount must have at most 2 decimal places")
        BigDecimal amount,

        LocalDate contributionDate,

        @Size(max = 500, message = "note must be at most 500 characters")
        String note
) {}
