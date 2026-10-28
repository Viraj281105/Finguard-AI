package com.finguard.backend.ingestion.parser;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Small helpers shared by the bank parsers. */
final class CsvSupport {

    /**
     * STRICT resolution: a date that does not exist (31/02/26) is an error, not silently moved to the last day of the
     * month by the lenient SMART style (found by the end-to-end audit). Strict mode needs {@code u} (proleptic year)
     * instead of {@code y}; two-digit {@code uu} years are 2000-2099.
     */
    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }

    private static final List<DateTimeFormatter> FALLBACK_DATE_FORMATTERS = List.of(
            strict("dd/MM/uuuu"),
            strict("dd/MM/uu"),
            strict("dd-MM-uuuu"),
            strict("dd-MM-uu"),
            strict("dd MMM uuuu"),
            strict("d MMM uuuu"),
            strict("uuuu-MM-dd")
    );

    /** Earliest statement date accepted. Anything older is a parsing accident, not a bank transaction. */
    static final LocalDate EARLIEST_PLAUSIBLE_DATE = LocalDate.of(1990, 1, 1);

    /** Largest single amount accepted (₹10 trillion). Larger values are data errors that would overflow storage. */
    static final BigDecimal MAX_PLAUSIBLE_AMOUNT = new BigDecimal("10000000000000");

    /** Longest title stored (the {@code transactions.title} column is varchar(255)). */
    static final int MAX_TITLE_LENGTH = 255;

    /**
     * @param negative the cell carried a minus sign or was written in parentheses; the direction it implies is
     *                 ambiguous in a debit/credit layout (it can be a reversal), so callers decide what to do.
     */
    record ParsedAmount(BigDecimal amount, Boolean isCreditHint, boolean negative) {
    }

    /** A statement cannot contain transactions from the future or from before 1990. */
    static LocalDate requirePlausible(LocalDate date) {
        LocalDate latest = LocalDate.now().plusDays(1); // one day of slack for time zones
        if (date.isBefore(EARLIEST_PLAUSIBLE_DATE) || date.isAfter(latest)) {
            throw new IllegalArgumentException("Date " + date + " is outside the plausible range (" + EARLIEST_PLAUSIBLE_DATE + " to today)");
        }
        return date;
    }

    static String truncateTitle(String title) {
        return title.length() <= MAX_TITLE_LENGTH ? title : title.substring(0, MAX_TITLE_LENGTH - 3) + "...";
    }

    private CsvSupport() {
    }

    /** The bank's preferred format, also strict (see {@link #strict}); {@code y} in the pattern is read as {@code u}. */
    static DateTimeFormatter dateFormat(String pattern) {
        return strict(pattern.replace('y', 'u'));
    }

    static List<String[]> readCsv(String content) {
        try (CSVReader reader = new CSVReader(new StringReader(content))) {
            return reader.readAll();
        } catch (IOException | CsvException e) {
            throw new IllegalArgumentException("The file is not a readable CSV: " + e.getMessage(), e);
        }
    }

    /** Index of the first row containing ALL keywords (banks often put junk rows above the header), or -1. */
    static int findHeaderRow(List<String[]> rows, String... keywords) {
        for (int i = 0; i < rows.size(); i++) {
            String joined = String.join(",", rows.get(i)).toLowerCase(Locale.ROOT);
            if (Arrays.stream(keywords).allMatch(joined::contains)) {
                return i;
            }
        }
        return -1;
    }

    /** Normalizes a header token by stripping non-alphanumeric characters (except '/') and lower-casing. */
    static String normalizeHeader(String header) {
        if (header == null) {
            return "";
        }
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/]", "");
    }

    static String[] lowerCased(String[] headers) {
        return Arrays.stream(headers).map(h -> h.toLowerCase(Locale.ROOT).trim()).toArray(String[]::new);
    }

    static int findColumn(String[] lowerCasedHeaders, String name) {
        for (int i = 0; i < lowerCasedHeaders.length; i++) {
            if (lowerCasedHeaders[i].contains(name)) {
                return i;
            }
        }
        return -1;
    }

    static String get(String[] cols, int idx) {
        return idx < 0 || idx >= cols.length ? "" : cols[idx].trim();
    }

    static LocalDate parseDate(String value, DateTimeFormatter primaryFormat) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Transaction date is empty");
        }
        String trimmed = value.trim();
        if (primaryFormat != null) {
            try {
                return LocalDate.parse(trimmed, primaryFormat);
            } catch (DateTimeParseException ignored) {
            }
        }
        for (DateTimeFormatter fallback : FALLBACK_DATE_FORMATTERS) {
            try {
                return LocalDate.parse(trimmed, fallback);
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new IllegalArgumentException("Cannot parse date: '" + trimmed + "'");
    }

    /**
     * Parses an amount string supporting Indian number format (1,23,456.78), currency symbols (₹, Rs),
     * parenthesized numbers (1,234.50) indicating negative/debit, trailing/leading Dr/Cr suffixes,
     * and signs.
     *
     * @return {@link ParsedAmount} or {@code null} if cell is empty
     * @throws IllegalArgumentException if text cannot be parsed as a number
     */
    static ParsedAmount parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();

        boolean parenthesized = false;
        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            parenthesized = true;
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }

        // Clean currency symbols and commas
        String clean = trimmed
                .replaceAll("(?i)[₹]|rs\\.?|inr", "")
                .replace(",", "")
                .trim();

        Boolean isCredit = null;
        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.endsWith("dr") || lower.startsWith("dr")) {
            isCredit = Boolean.FALSE;
            clean = clean.replaceAll("(?i)\\bdr\\b|dr$", "").trim();
        } else if (lower.endsWith("cr") || lower.startsWith("cr")) {
            isCredit = Boolean.TRUE;
            clean = clean.replaceAll("(?i)\\bcr\\b|cr$", "").trim();
        }

        clean = clean.trim();
        if (clean.isEmpty()) {
            return null;
        }

        try {
            BigDecimal amount = new BigDecimal(clean);
            if (amount.abs().compareTo(MAX_PLAUSIBLE_AMOUNT) > 0) {
                throw new IllegalArgumentException("Amount is implausibly large: '" + value.trim() + "'");
            }
            boolean negative = parenthesized || amount.signum() < 0;
            if (parenthesized || Boolean.FALSE.equals(isCredit) || amount.signum() < 0) {
                return new ParsedAmount(amount.abs(), Boolean.FALSE, negative);
            }
            if (Boolean.TRUE.equals(isCredit)) {
                return new ParsedAmount(amount.abs(), Boolean.TRUE, negative);
            }
            return new ParsedAmount(amount.abs(), null, negative);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cannot parse amount: '" + value.trim() + "'");
        }
    }

    /** Legacy helper returning 0 on empty or invalid amount. */
    static BigDecimal parseMoney(String value) {
        try {
            ParsedAmount pa = parseAmount(value);
            return pa == null ? BigDecimal.ZERO : pa.amount();
        } catch (RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }

    static String normalizeDescription(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ");
    }

    static boolean isBlank(String[] cols) {
        return Arrays.stream(cols).allMatch(c -> c == null || c.isBlank());
    }
}
