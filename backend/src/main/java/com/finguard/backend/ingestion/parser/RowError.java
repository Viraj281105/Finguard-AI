package com.finguard.backend.ingestion.parser;

/**
 * Details of one row in a bank statement that could not be parsed.
 *
 * <p>Excerpts are sanitized to prevent logging or returning raw PII such as full account or card numbers.
 */
public record RowError(
        int rowNumber,
        String errorMessage,
        String snippet
) {
    public static RowError of(int rowNumber, String errorMessage, String rawLine) {
        return new RowError(rowNumber, errorMessage, sanitizeSnippet(rawLine));
    }

    public static String sanitizeSnippet(String rawLine) {
        if (rawLine == null) {
            return "";
        }
        String clean = rawLine.trim().replaceAll("\\s+", " ");
        // Mask sequences of 10 or more digits (e.g. account numbers, card numbers)
        // Replaces digits between first 4 and last 4 with ****
        clean = clean.replaceAll("(?<=\\b\\d{4})\\d{2,12}(?=\\d{4}\\b)", "****");
        if (clean.length() > 100) {
            clean = clean.substring(0, 97) + "...";
        }
        return clean;
    }
}
