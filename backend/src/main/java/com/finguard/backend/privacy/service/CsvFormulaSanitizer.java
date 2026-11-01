package com.finguard.backend.privacy.service;

import java.util.Set;

/**
 * Neutralises spreadsheet formula injection (CSV/Excel/Calc DDE &amp; formula payloads)
 * and formats cells according to RFC 4180.
 *
 * <p>Any cell whose raw value or leading non-whitespace character starts with {@code =}, {@code +},
 * {@code -}, {@code @}, {@code \t}, or {@code \r} is prefixed with a single quote ({@code '}) so
 * spreadsheet applications treat the cell strictly as literal text.
 */
public final class CsvFormulaSanitizer {

    private static final Set<Character> FORMULA_TRIGGERS = Set.of('=', '+', '-', '@', '\t', '\r');

    private CsvFormulaSanitizer() {
    }

    /**
     * Neutralises formula-injection triggers in a raw string value.
     */
    public static String neutralizeFormula(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (FORMULA_TRIGGERS.contains(first)) {
            return "'" + value;
        }
        String trimmed = value.stripLeading();
        if (!trimmed.isEmpty() && FORMULA_TRIGGERS.contains(trimmed.charAt(0))) {
            return "'" + value;
        }
        return value;
    }

    /**
     * Neutralises formula triggers and encodes the cell as an RFC 4180 CSV field.
     */
    public static String formatCsvCell(Object value) {
        if (value == null) {
            return "";
        }
        String sanitized = neutralizeFormula(String.valueOf(value));
        boolean needsQuoting = sanitized.indexOf(',') >= 0
                || sanitized.indexOf('"') >= 0
                || sanitized.indexOf('\n') >= 0
                || sanitized.indexOf('\r') >= 0;
        if (!needsQuoting) {
            return sanitized;
        }
        return "\"" + sanitized.replace("\"", "\"\"") + "\"";
    }

    /**
     * Formats an ordered sequence of cell values as a single RFC 4180 CSV row terminated with LF.
     */
    public static String formatCsvRow(Object... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(formatCsvCell(cells[i]));
        }
        sb.append('\n');
        return sb.toString();
    }
}

