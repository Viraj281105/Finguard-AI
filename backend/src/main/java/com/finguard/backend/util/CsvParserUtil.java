package com.finguard.backend.util;

import com.finguard.backend.model.Transaction;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/*
 * CsvParserUtil
 * -------------
 * Detects which Indian bank a CSV came from and parses it into
 * a list of ParsedRow objects, which the service then converts
 * into Transaction entities and saves to the database.
 *
 * Supported banks:
 *   HDFC · ICICI · SBI · Axis · Kotak
 *
 * Why is this complex?
 * --------------------
 * Every bank has a completely different CSV format:
 *   - Different column names and ordering
 *   - Different date formats (dd/MM/yy vs dd/MM/yyyy vs dd MMM yyyy)
 *   - Different ways to represent debit/credit
 *     (some use separate columns, some use a single Cr/Dr flag)
 *   - Some banks add junk rows at the top before the actual header
 *
 * @Slf4j → Lombok generates a `log` variable for logging.
 */
@Slf4j
@Component
public class CsvParserUtil {

    /*
     * ParsedRow
     * ---------
     * A simple data holder for one parsed CSV row.
     * Using Java record (immutable, compact syntax).
     *
     * Note: we map to Viraj's Transaction fields:
     *   - title  → the transaction description from the CSV
     *   - type   → INCOME (credit) or EXPENSE (debit)
     *   - bankName → stored in the note field (since Transaction has no bankName)
     */
    public record ParsedRow(
            LocalDate date,
            String title,
            BigDecimal amount,
            Transaction.TransactionType type,
            String bankName
    ) {}

    /*
     * BankFormat
     * ----------
     * Detected bank — drives which parser to use.
     */
    public enum BankFormat {
        HDFC, ICICI, SBI, AXIS, KOTAK, UNKNOWN
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC METHODS
    // ─────────────────────────────────────────────────────────────

    /*
     * detectBank()
     * ------------
     * Scans the first few lines of the CSV for distinctive keywords
     * that identify which bank it came from.
     */
    public BankFormat detectBank(String csvContent) {
        try {
            String[] firstLines = csvContent.split("\n", 10);
            for (String line : firstLines) {
                String lower = line.toLowerCase();

                // HDFC: has "narration" and "chq./ref.no." in header
                if (lower.contains("narration") && lower.contains("chq./ref.no.")) {
                    return BankFormat.HDFC;
                }
                // ICICI: has "transaction remarks" and "cr/dr"
                if (lower.contains("transaction remarks") && lower.contains("cr/dr")) {
                    return BankFormat.ICICI;
                }
                // SBI: has "txn date" and "ref no./cheque no."
                if (lower.contains("txn date") && lower.contains("ref no./cheque no.")) {
                    return BankFormat.SBI;
                }
                // Axis: has "tran date" and "chqno"
                if (lower.contains("tran date") && lower.contains("chqno")) {
                    return BankFormat.AXIS;
                }
                // Kotak: has "transaction date" and "debit amount" and "credit amount"
                if (lower.contains("transaction date") && lower.contains("debit amount") && lower.contains("credit amount")) {
                    return BankFormat.KOTAK;
                }
            }
        } catch (Exception e) {
            log.warn("Bank detection failed: {}", e.getMessage());
        }
        return BankFormat.UNKNOWN;
    }

    /*
     * parse()
     * -------
     * Main entry point. Detects the bank and delegates to the
     * correct parser. Throws IllegalArgumentException if format
     * is unrecognized (caught by CsvUploadService → job marked FAILED).
     */
    public List<ParsedRow> parse(String csvContent) {
        BankFormat bank = detectBank(csvContent);
        log.info("Detected bank format: {}", bank);

        return switch (bank) {
            case HDFC  -> parseHdfc(csvContent);
            case ICICI -> parseIcici(csvContent);
            case SBI   -> parseSbi(csvContent);
            case AXIS  -> parseAxis(csvContent);
            case KOTAK -> parseKotak(csvContent);
            case UNKNOWN -> throw new IllegalArgumentException(
                "Unrecognized CSV format. Please upload a statement from HDFC, ICICI, SBI, Axis, or Kotak."
            );
        };
    }

    // ─────────────────────────────────────────────────────────────
    // BANK PARSERS
    // ─────────────────────────────────────────────────────────────

    /*
     * HDFC Bank
     * Columns: Date | Narration | Chq./Ref.No. | Value Dt | Withdrawal Amt. | Deposit Amt. | Closing Balance
     * Date format: dd/MM/yy
     * Debit  → Withdrawal Amt. has a value
     * Credit → Deposit Amt.    has a value
     */
    private List<ParsedRow> parseHdfc(String csvContent) {
        List<ParsedRow> rows = new ArrayList<>();
        List<String[]> lines = readCsv(csvContent);
        int headerIdx = findHeaderRow(lines, "date", "narration");
        if (headerIdx == -1) throw new IllegalArgumentException("Cannot find header row in HDFC CSV");

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yy");
        for (int i = headerIdx + 1; i < lines.size(); i++) {
            String[] cols = lines.get(i);
            if (cols.length < 6 || isBlank(cols)) continue;
            try {
                LocalDate date       = parseDate(cols[0], fmt);
                String title         = cols[1].trim();
                BigDecimal withdrawal = parseMoney(cols[4]);
                BigDecimal deposit   = parseMoney(cols[5]);

                if (withdrawal.compareTo(BigDecimal.ZERO) > 0) {
                    rows.add(new ParsedRow(date, title, withdrawal, Transaction.TransactionType.EXPENSE, "HDFC"));
                } else if (deposit.compareTo(BigDecimal.ZERO) > 0) {
                    rows.add(new ParsedRow(date, title, deposit, Transaction.TransactionType.INCOME, "HDFC"));
                }
            } catch (Exception e) {
                log.warn("Skipping HDFC row {}: {}", i, e.getMessage());
            }
        }
        return rows;
    }

    /*
     * ICICI Bank
     * Columns: Transaction Date | Transaction Remarks | Withdrawal Amount | Deposit Amount | Balance
     * OR uses Cr/Dr column with single Amount column
     * Date format: dd/MM/yyyy
     */
    private List<ParsedRow> parseIcici(String csvContent) {
        List<ParsedRow> rows = new ArrayList<>();
        List<String[]> lines = readCsv(csvContent);
        int headerIdx = findHeaderRow(lines, "transaction date", "transaction remarks");
        if (headerIdx == -1) throw new IllegalArgumentException("Cannot find header row in ICICI CSV");

        String[] headers = normalizeHeaders(lines.get(headerIdx));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        int dateIdx     = findCol(headers, "transaction date");
        int descIdx     = findCol(headers, "transaction remarks");
        int crdrIdx     = findCol(headers, "cr/dr");
        int amtIdx      = findCol(headers, "amount");
        int withdrawIdx = findCol(headers, "withdrawal amount");
        int depositIdx  = findCol(headers, "deposit amount");

        for (int i = headerIdx + 1; i < lines.size(); i++) {
            String[] cols = lines.get(i);
            if (isBlank(cols)) continue;
            try {
                LocalDate date   = parseDate(safeGet(cols, dateIdx), fmt);
                String title     = safeGet(cols, descIdx);
                BigDecimal amount;
                Transaction.TransactionType type;

                if (crdrIdx != -1 && amtIdx != -1) {
                    amount = parseMoney(safeGet(cols, amtIdx));
                    String crdr = safeGet(cols, crdrIdx).toUpperCase();
                    type = crdr.contains("CR") ? Transaction.TransactionType.INCOME : Transaction.TransactionType.EXPENSE;
                } else {
                    BigDecimal w = parseMoney(safeGet(cols, withdrawIdx));
                    BigDecimal d = parseMoney(safeGet(cols, depositIdx));
                    if (w.compareTo(BigDecimal.ZERO) > 0) { amount = w; type = Transaction.TransactionType.EXPENSE; }
                    else { amount = d; type = Transaction.TransactionType.INCOME; }
                }

                if (amount.compareTo(BigDecimal.ZERO) > 0) {
                    rows.add(new ParsedRow(date, title, amount, type, "ICICI"));
                }
            } catch (Exception e) {
                log.warn("Skipping ICICI row {}: {}", i, e.getMessage());
            }
        }
        return rows;
    }

    /*
     * SBI Bank
     * Columns: Txn Date | Value Date | Description | Ref No./Cheque No. | Debit | Credit | Balance
     * Date format: dd MMM yyyy  (e.g. "14 Mar 2026")
     */
    private List<ParsedRow> parseSbi(String csvContent) {
        List<ParsedRow> rows = new ArrayList<>();
        List<String[]> lines = readCsv(csvContent);
        int headerIdx = findHeaderRow(lines, "txn date", "description");
        if (headerIdx == -1) throw new IllegalArgumentException("Cannot find header row in SBI CSV");

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd MMM yyyy");
        for (int i = headerIdx + 1; i < lines.size(); i++) {
            String[] cols = lines.get(i);
            if (cols.length < 6 || isBlank(cols)) continue;
            try {
                LocalDate date    = parseDate(cols[0], fmt);
                String title      = cols[2].trim();
                BigDecimal debit  = parseMoney(cols[4]);
                BigDecimal credit = parseMoney(cols[5]);

                if (debit.compareTo(BigDecimal.ZERO) > 0) {
                    rows.add(new ParsedRow(date, title, debit, Transaction.TransactionType.EXPENSE, "SBI"));
                } else if (credit.compareTo(BigDecimal.ZERO) > 0) {
                    rows.add(new ParsedRow(date, title, credit, Transaction.TransactionType.INCOME, "SBI"));
                }
            } catch (Exception e) {
                log.warn("Skipping SBI row {}: {}", i, e.getMessage());
            }
        }
        return rows;
    }

    /*
     * Axis Bank
     * Columns: Tran Date | CHQNO | PARTICULARS | DR | CR | BAL
     * Date format: dd-MM-yyyy
     */
    private List<ParsedRow> parseAxis(String csvContent) {
        List<ParsedRow> rows = new ArrayList<>();
        List<String[]> lines = readCsv(csvContent);
        int headerIdx = findHeaderRow(lines, "tran date", "particulars");
        if (headerIdx == -1) throw new IllegalArgumentException("Cannot find header row in Axis CSV");

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        for (int i = headerIdx + 1; i < lines.size(); i++) {
            String[] cols = lines.get(i);
            if (cols.length < 5 || isBlank(cols)) continue;
            try {
                LocalDate date  = parseDate(cols[0], fmt);
                String title    = cols[2].trim();
                BigDecimal dr   = parseMoney(cols[3]);
                BigDecimal cr   = parseMoney(cols[4]);

                if (dr.compareTo(BigDecimal.ZERO) > 0) {
                    rows.add(new ParsedRow(date, title, dr, Transaction.TransactionType.EXPENSE, "AXIS"));
                } else if (cr.compareTo(BigDecimal.ZERO) > 0) {
                    rows.add(new ParsedRow(date, title, cr, Transaction.TransactionType.INCOME, "AXIS"));
                }
            } catch (Exception e) {
                log.warn("Skipping Axis row {}: {}", i, e.getMessage());
            }
        }
        return rows;
    }

    /*
     * Kotak Mahindra Bank
     * Columns: Transaction Date | Value Date | Description | Chq/Ref Number | Debit Amount | Credit Amount | Balance
     * Date format: dd-MM-yyyy
     */
    private List<ParsedRow> parseKotak(String csvContent) {
        List<ParsedRow> rows = new ArrayList<>();
        List<String[]> lines = readCsv(csvContent);
        int headerIdx = findHeaderRow(lines, "transaction date", "debit amount");
        if (headerIdx == -1) throw new IllegalArgumentException("Cannot find header row in Kotak CSV");

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        for (int i = headerIdx + 1; i < lines.size(); i++) {
            String[] cols = lines.get(i);
            if (cols.length < 6 || isBlank(cols)) continue;
            try {
                LocalDate date    = parseDate(cols[0], fmt);
                String title      = cols[2].trim();
                BigDecimal debit  = parseMoney(cols[4]);
                BigDecimal credit = parseMoney(cols[5]);

                if (debit.compareTo(BigDecimal.ZERO) > 0) {
                    rows.add(new ParsedRow(date, title, debit, Transaction.TransactionType.EXPENSE, "KOTAK"));
                } else if (credit.compareTo(BigDecimal.ZERO) > 0) {
                    rows.add(new ParsedRow(date, title, credit, Transaction.TransactionType.INCOME, "KOTAK"));
                }
            } catch (Exception e) {
                log.warn("Skipping Kotak row {}: {}", i, e.getMessage());
            }
        }
        return rows;
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────

    private List<String[]> readCsv(String content) {
        try (CSVReader reader = new CSVReader(new StringReader(content))) {
            return reader.readAll();
        } catch (IOException | CsvException e) {
            throw new RuntimeException("Failed to parse CSV: " + e.getMessage(), e);
        }
    }

    /*
     * Scan rows until we find one containing ALL the given keywords.
     * Handles banks that put junk rows before the actual column headers.
     */
    private int findHeaderRow(List<String[]> rows, String... keywords) {
        for (int i = 0; i < rows.size(); i++) {
            String joined = String.join(",", rows.get(i)).toLowerCase();
            if (Arrays.stream(keywords).allMatch(joined::contains)) return i;
        }
        return -1;
    }

    private String[] normalizeHeaders(String[] headers) {
        return Arrays.stream(headers).map(h -> h.toLowerCase().trim()).toArray(String[]::new);
    }

    private int findCol(String[] headers, String name) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].contains(name)) return i;
        }
        return -1;
    }

    private String safeGet(String[] cols, int idx) {
        if (idx < 0 || idx >= cols.length) return "";
        return cols[idx].trim();
    }

    private LocalDate parseDate(String val, DateTimeFormatter fmt) {
        try {
            return LocalDate.parse(val.trim(), fmt);
        } catch (DateTimeParseException e) {
            throw new RuntimeException("Cannot parse date: '" + val + "'");
        }
    }

    /*
     * Strips commas, ₹ symbol, whitespace from Indian money strings.
     * "1,23,456.78" → BigDecimal(123456.78)
     * "" or null    → BigDecimal.ZERO (empty cell = zero, not an error)
     */
    private BigDecimal parseMoney(String val) {
        if (val == null || val.isBlank()) return BigDecimal.ZERO;
        String clean = val.trim().replace(",", "").replace("₹", "").replace("Rs.", "").replace("Rs", "").trim();
        if (clean.isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(clean);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private boolean isBlank(String[] cols) {
        return Arrays.stream(cols).allMatch(c -> c == null || c.isBlank());
    }
}