package com.finguard.backend.ledger.loans;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMax;
import com.finguard.backend.ledger.loans.Loan.LoanType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LoanRequest(
        @NotBlank @Size(max = 255, message = "name must be at most 255 characters") String name,
        @NotNull LoanType type,
        @NotNull @Positive @DecimalMax(value = "10000000000000", message = "amount is too large") @Digits(integer = 14, fraction = 2, message = "amount must have at most 2 decimal places") BigDecimal principal,
        @NotNull @Positive @DecimalMax(value = "10000000000000", message = "amount is too large") @Digits(integer = 14, fraction = 2, message = "amount must have at most 2 decimal places") BigDecimal emiAmount,
        @NotNull @PositiveOrZero @DecimalMax(value = "100", message = "interestRate must be at most 100 (percent a year)") BigDecimal interestRate,
        LocalDate startDate,
        LocalDate endDate,
        Boolean active
) {

    /** A loan is active unless the client explicitly says otherwise. */
    public boolean activeOrDefault() {
        return active == null || active;
    }
}
