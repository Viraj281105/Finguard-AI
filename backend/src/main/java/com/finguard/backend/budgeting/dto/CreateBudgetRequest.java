package com.finguard.backend.budgeting.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateBudgetRequest(
        @NotBlank(message = "yearMonth is required")
        @Pattern(regexp = "^[0-9]{4}-(0[1-9]|1[0-2])$", message = "yearMonth must follow YYYY-MM format")
        String yearMonth,

        String category,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        @DecimalMax(value = "10000000000000", message = "amount is too large") @Digits(integer = 14, fraction = 2, message = "amount must have at most 2 decimal places")
        BigDecimal amount
) {}
