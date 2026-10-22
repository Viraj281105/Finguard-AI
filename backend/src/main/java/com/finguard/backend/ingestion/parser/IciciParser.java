package com.finguard.backend.ingestion.parser;

import org.springframework.stereotype.Component;

/**
 * ICICI: supports both single-amount Cr/Dr layout
 * ({@code Transaction Date | Transaction Remarks | Cr/Dr | Amount | Balance})
 * and withdrawal/deposit layout
 * ({@code Transaction Date | Transaction Remarks | Withdrawal Amount | Deposit Amount | Balance}),
 * dates as {@code dd/MM/yyyy}.
 */
@Component
class IciciParser extends HeaderDrivenStatementParser {

    IciciParser() {
        super(BankFormat.ICICI, new String[]{"transaction remarks"}, "dd/MM/yyyy");
    }

    @Override
    public boolean matchesHeader(String line) {
        return (line.contains("transaction remarks") || line.contains("transaction date"))
                && (line.contains("cr/dr") || line.contains("cr / dr") || (line.contains("withdrawal") && line.contains("deposit")));
    }
}
