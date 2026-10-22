package com.finguard.backend.ingestion.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Result of parsing a bank statement, accounting for every single row in the file.
 *
 * <p>Reconciliation invariant:
 * {@code totalDataRows == rows.size() + errors.size() + skippedRows}
 */
public record StatementParseResult(
        List<ParsedRow> rows,
        List<RowError> errors,
        int totalDataRows,
        int skippedRows,
        BigDecimal closingBalance,
        LocalDate statementFrom,
        LocalDate statementTo
) {
    public static StatementParseResult empty() {
        return new StatementParseResult(List.of(), List.of(), 0, 0, null, null, null);
    }
}
