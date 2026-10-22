package com.finguard.backend.ingestion.parser;

import org.springframework.stereotype.Component;

/**
 * SBI: {@code Txn Date | Value Date | Description | Ref No./Cheque No. | Debit | Credit | Balance},
 * dates as {@code dd MMM yyyy} (e.g. "14 Mar 2026").
 */
@Component
class SbiParser extends HeaderDrivenStatementParser {

    SbiParser() {
        super(BankFormat.SBI, new String[]{"description"}, "dd MMM yyyy");
    }

    @Override
    public boolean matchesHeader(String line) {
        return line.contains("txn date") || (line.contains("description") && (line.contains("ref no./cheque no.") || line.contains("ref no/cheque no")));
    }
}
