package com.finguard.backend.ingestion.parser;

import org.springframework.stereotype.Component;

/**
 * HDFC: {@code Date | Narration | Chq./Ref.No. | Value Dt | Withdrawal Amt. | Deposit Amt. | Closing Balance},
 * dates as {@code dd/MM/yy}.
 */
@Component
class HdfcParser extends HeaderDrivenStatementParser {

    HdfcParser() {
        super(BankFormat.HDFC, new String[]{"date", "narration"}, "dd/MM/yy");
    }

    @Override
    public boolean matchesHeader(String line) {
        return line.contains("narration") && (line.contains("chq./ref.no.") || line.contains("chq/ref") || line.contains("closing balance"));
    }
}
