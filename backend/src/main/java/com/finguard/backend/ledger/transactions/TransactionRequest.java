package com.finguard.backend.ledger.transactions;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMax;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionRequest(
        @NotBlank @Size(max = 255, message = "title must be at most 255 characters") String title,
        @NotNull @Positive @DecimalMax(value = "10000000000000", message = "amount is too large") @Digits(integer = 14, fraction = 2, message = "amount must have at most 2 decimal places") BigDecimal amount,
        @NotNull TransactionType type,
        LocalDate date,
        String category,
        @Size(max = 255, message = "note must be at most 255 characters") String note
) {
}
