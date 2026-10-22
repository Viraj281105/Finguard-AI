package com.finguard.backend.ingestion.parser;

import org.springframework.stereotype.Component;

/** Axis: {@code Tran Date | CHQNO | PARTICULARS | DR | CR | BAL}, dates as {@code dd-MM-yyyy}. */
@Component
class AxisParser extends HeaderDrivenStatementParser {

    AxisParser() {
        super(BankFormat.AXIS, new String[]{"particulars"}, "dd-MM-yyyy");
    }

    @Override
    public boolean matchesHeader(String line) {
        return line.contains("tran date") || (line.contains("particulars") && (line.contains("chqno") || (line.contains("dr") && line.contains("cr"))));
    }
}
