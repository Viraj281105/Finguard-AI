package com.finguard.backend.ledger.transactions;

import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionRequest(
        @NotBlank String title,
        @NotNull @Positive BigDecimal amount,
        @NotNull TransactionType type,
        LocalDate date,
        String category,
        String note
) {
}
