package com.finguard.backend.ingestion.parser;

import java.util.List;

/** Recognises and parses the CSV layout of one bank. */
public interface BankStatementParser {

    BankFormat bank();

    /** @param lowerCaseLine one line of the file, lower-cased; true if it is this bank's header line */
    boolean matchesHeader(String lowerCaseLine);

    /**
     * Parses the statement, returning full row reconciliation, errors, and metadata.
     *
     * @param rows every CSV record in the file
     * @return the parse result
     * @throws IllegalArgumentException if the header row cannot be located
     */
    StatementParseResult parseStatement(List<String[]> rows);

    /**
     * Backward-compatible helper returning only the valid parsed transactions.
     *
     * @param rows every CSV record in the file
     * @return the successfully parsed transactions; unparseable rows are skipped
     * @throws IllegalArgumentException if the header row cannot be located
     */
    default List<ParsedRow> parse(List<String[]> rows) {
        return parseStatement(rows).rows();
    }
}
