package com.finguard.backend.ingestion.parser;

import com.finguard.backend.ledger.transactions.Transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One normalised statement line: a positive {@code amount} plus a direction ({@code type}).
 *
 * @param bankName name of the {@link BankFormat} the row came from
 */
public record ParsedRow(
        LocalDate date,
        String title,
        BigDecimal amount,
        TransactionType type,
        String bankName
) {
}
