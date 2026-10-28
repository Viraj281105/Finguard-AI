package com.finguard.backend.ingestion.parser;

/**
 * @deprecated Superceded by {@link HeaderDrivenStatementParser} which dynamically maps columns by header names.
 */
@Deprecated
abstract class PositionalDebitCreditParser extends HeaderDrivenStatementParser {

    /** Column positions and format of one bank's layout. */
    @Deprecated
    record Layout(String[] headerKeywords, String datePattern,
                  int dateCol, int titleCol, int debitCol, int creditCol, int minColumns) {
    }

    @Deprecated
    PositionalDebitCreditParser(BankFormat bank, Layout layout) {
        super(bank, layout.headerKeywords(), layout.datePattern());
    }
}
