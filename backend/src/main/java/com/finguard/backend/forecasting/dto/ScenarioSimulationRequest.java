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
        @Size(max = 50) List<String> removedRecurringExpenseIds,
        @Size(max = 10) List<@Valid LoanAdjustment> loanAdjustments,
        @Size(max = 20) List<@Valid OneTimeItem> oneTimeItems
) {

    /** Keeps the pre-0.9.0 six-argument form working: no loan adjustments, no one-time items. */
    public ScenarioSimulationRequest(String name, Integer horizonMonths, Adjustment incomeAdjustment,
                                     List<CategoryAdjustment> categoryAdjustments, List<AddedRecurringExpense> addedRecurringExpenses,
                                     List<String> removedRecurringExpenseIds) {
        this(name, horizonMonths, incomeAdjustment, categoryAdjustments, addedRecurringExpenses, removedRecurringExpenseIds, null, null);
    }

    public enum LoanAction {
        /** Repay the whole outstanding balance at the start of month 1; no instalments afterwards. */
        CLOSE,
        /** Pay {@code amount} extra at the start of month 1; the instalment stays, so the loan ends sooner. */
        PREPAY,
        /** Replace the monthly instalment with {@code amount}. */
        CHANGE_EMI
    }

    /** A change to one of the caller's own loans. {@code amount} is required for PREPAY and CHANGE_EMI. */
    public record LoanAdjustment(
            @NotBlank String loanId,
            @NotNull LoanAction action,
            @DecimalMin("0.01") @DecimalMax("1000000000") BigDecimal amount
    ) {
    }

    public enum OneTimeKind {
        INCOME,
        EXPENSE
    }

    /** A single hypothetical income or expense in projected month {@code month} (1 = the first projected month). */
    public record OneTimeItem(
            @NotBlank @Size(max = 80) String title,
            @NotNull OneTimeKind kind,
            @NotNull @Min(1) @Max(12) Integer month,
            @NotNull @Positive @DecimalMax("1000000000") BigDecimal amount
    ) {
    }

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
