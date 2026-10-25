package com.finguard.backend.forecasting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * A hypothetical change set. Nothing here is ever persisted. Bounds are validated:
 * percentages are within -100 .. 500, fixed amounts within +/- 10,000,000 per month, lists are capped.
 */
public record ScenarioSimulationRequest(
        @Size(max = 80) String name,
        @Min(1) @Max(12) Integer horizonMonths,
        @Valid Adjustment incomeAdjustment,
        @Size(max = 20) List<@Valid CategoryAdjustment> categoryAdjustments,
        @Size(max = 20) List<@Valid AddedRecurringExpense> addedRecurringExpenses,
        @Size(max = 50) List<String> removedRecurringExpenseIds
) {

    public enum AdjustmentType {
        PERCENTAGE,
        FIXED_AMOUNT
    }

    /** A change to a monthly figure: a percentage (-100..500) or a fixed monthly amount (+/- 10,000,000). */
    public record Adjustment(
            @NotNull AdjustmentType type,
            @NotNull @DecimalMin("-10000000") @DecimalMax("10000000") BigDecimal value
    ) {
    }

    public record CategoryAdjustment(
            @NotBlank String category,
            @NotNull AdjustmentType type,
            @NotNull @DecimalMin("-10000000") @DecimalMax("10000000") BigDecimal value
    ) {
    }

    public record AddedRecurringExpense(
            @NotBlank @Size(max = 80) String title,
            String category,
            @NotNull @Positive @DecimalMax("10000000") BigDecimal monthlyAmount
    ) {
    }
}
