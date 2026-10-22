package com.finguard.backend.ingestion.parser;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Detects which bank a statement came from and delegates to that bank's parser. */
@Component
public class StatementParserRegistry {

    private static final int HEADER_SCAN_LINES = 15;
    private static final char BOM = '\uFEFF';

    private final List<BankStatementParser> parsers;

    StatementParserRegistry(HdfcParser hdfc, IciciParser icici, SbiParser sbi, AxisParser axis, KotakParser kotak) {
        // Order matters: the first parser whose header matches wins.
        this.parsers = List.of(hdfc, icici, sbi, axis, kotak);
    }

    /** Returns the detected bank, or {@link BankFormat#UNKNOWN}. */
    public BankFormat detectBank(String csvContent) {
        return find(withoutBom(csvContent)).map(BankStatementParser::bank).orElse(BankFormat.UNKNOWN);
    }

    /**
     * Parses a statement into full result accounting for valid, duplicate, rejected, and skipped rows.
     *
     * @throws IllegalArgumentException if the bank format is not recognised or the header row is missing
     */
    public StatementParseResult parseStatement(String csvContent) {
        String clean = withoutBom(csvContent);
        BankStatementParser parser = find(clean).orElseThrow(() -> new IllegalArgumentException(
                "Unrecognized CSV format. Please upload a statement from HDFC, ICICI, SBI, Axis, or Kotak."));
        return parser.parseStatement(CsvSupport.readCsv(clean));
    }

    /**
     * Backward-compatible statement parse returning only valid rows.
     *
     * @throws IllegalArgumentException if the bank format is not recognised or the header row is missing
     */
    public List<ParsedRow> parse(String csvContent) {
        return parseStatement(csvContent).rows();
    }

    /**
     * Scans the first lines for a bank's header. The final chunk of {@code split} holds the remainder
     * of the file, so a header that sits below junk rows is still found.
     */
    private Optional<BankStatementParser> find(String csvContent) {
        for (String line : withoutBom(csvContent).split("\n", HEADER_SCAN_LINES)) {
            String lower = line.toLowerCase(Locale.ROOT);
            for (BankStatementParser parser : parsers) {
                if (parser.matchesHeader(lower)) {
                    return Optional.of(parser);
                }
            }
        }
        return Optional.empty();
    }

    private static String withoutBom(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        if (content.charAt(0) == BOM || content.charAt(0) == '\uFFFE') {
            return content.substring(1);
        }
        return content;
    }
}
