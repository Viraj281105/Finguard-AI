package com.finguard.backend.ledger.loans;

import com.finguard.backend.ledger.loans.Loan.LoanType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LoanRequest(
        @NotBlank String name,
        @NotNull LoanType type,
        @NotNull @Positive BigDecimal principal,
        @NotNull @Positive BigDecimal emiAmount,
        @NotNull @PositiveOrZero BigDecimal interestRate,
        LocalDate startDate,
        LocalDate endDate,
        Boolean active
) {

    /** A loan is active unless the client explicitly says otherwise. */
    public boolean activeOrDefault() {
        return active == null || active;
    }
}
