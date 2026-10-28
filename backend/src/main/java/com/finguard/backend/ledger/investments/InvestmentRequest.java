package com.finguard.backend.ledger.investments;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMax;
import com.finguard.backend.ledger.investments.Investment.InvestmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record InvestmentRequest(
        @NotBlank @Size(max = 255, message = "name must be at most 255 characters") String name,
        @NotNull InvestmentType type,
        @NotNull @Positive @DecimalMax(value = "10000000000000", message = "amount is too large") @Digits(integer = 14, fraction = 2, message = "amount must have at most 2 decimal places") BigDecimal amountInvested,
        @PositiveOrZero @DecimalMax(value = "10000000000000", message = "amount is too large") @Digits(integer = 14, fraction = 2, message = "amount must have at most 2 decimal places") BigDecimal currentValue,
        @Size(max = 255, message = "notes must be at most 255 characters") String notes
) {
}
