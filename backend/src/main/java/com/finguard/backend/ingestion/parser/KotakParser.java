package com.finguard.backend.ingestion.parser;

import org.springframework.stereotype.Component;

/**
 * Kotak: {@code Transaction Date | Value Date | Description | Chq/Ref Number | Debit Amount | Credit Amount | Balance},
 * dates as {@code dd-MM-yyyy}.
 */
@Component
class KotakParser extends HeaderDrivenStatementParser {

    KotakParser() {
        super(BankFormat.KOTAK, new String[]{"transaction date"}, "dd-MM-yyyy");
    }

    @Override
    public boolean matchesHeader(String line) {
        return (line.contains("transaction date") && line.contains("debit amount"))
                || (line.contains("debit amount") && line.contains("credit amount"));
    }
}
