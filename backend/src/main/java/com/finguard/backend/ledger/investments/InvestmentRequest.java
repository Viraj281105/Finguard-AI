package com.finguard.backend.ledger.investments;

import com.finguard.backend.ledger.investments.Investment.InvestmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record InvestmentRequest(
        @NotBlank String name,
        @NotNull InvestmentType type,
        @NotNull @Positive BigDecimal amountInvested,
        @PositiveOrZero BigDecimal currentValue,
        String notes
) {
}
