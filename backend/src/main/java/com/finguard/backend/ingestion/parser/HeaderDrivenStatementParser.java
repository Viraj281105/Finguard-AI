package com.finguard.backend.ingestion.parser;

import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Base parser for bank statements that maps columns by header name rather than fixed positional indices.
 *
 * <p>Tolerates:
 * <ul>
 *   <li>Reordered columns (KI-003)</li>
 *   <li>Arbitrary extra columns</li>
 *   <li>Indian currency numbering formats, symbols, and parenthesized negatives (KI-006)</li>
 *   <li>Bank-specific and standard fallback date formats</li>
 *   <li>Both separate debit/credit and single-amount Cr/Dr layouts (KI-005)</li>
 * </ul>
 */
@Slf4j
public abstract class HeaderDrivenStatementParser implements BankStatementParser {

    private final BankFormat bank;
    private final String[] headerKeywords;
    private final DateTimeFormatter preferredDateFormat;

    protected HeaderDrivenStatementParser(BankFormat bank, String[] headerKeywords, String preferredDatePattern) {
        this.bank = bank;
        this.headerKeywords = headerKeywords;
        this.preferredDateFormat = preferredDatePattern != null ? CsvSupport.dateFormat(preferredDatePattern) : null;
    }

    @Override
    public BankFormat bank() {
        return bank;
    }

    @Override
    public StatementParseResult parseStatement(List<String[]> lines) {
        int headerIdx = CsvSupport.findHeaderRow(lines, headerKeywords);
        if (headerIdx == -1) {
            throw new IllegalArgumentException("Cannot find header row in " + bank + " statement");
        }

        String[] headerCols = lines.get(headerIdx);
        ColumnMapping mapping = mapColumns(headerCols);

        if (mapping.dateCol == -1) {
            throw new IllegalArgumentException("Cannot identify date column in " + bank + " statement headers");
        }
        if (mapping.descCol == -1) {
            throw new IllegalArgumentException("Cannot identify description column in " + bank + " statement headers");
        }
        if (mapping.debitCol == -1 && mapping.creditCol == -1 && mapping.amtCol == -1) {
            throw new IllegalArgumentException("Cannot identify amount columns in " + bank + " statement headers");
        }

        List<ParsedRow> validRows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        int skippedRows = 0;
        BigDecimal lastClosingBalance = null;

        for (int i = headerIdx + 1; i < lines.size(); i++) {
            String[] cols = lines.get(i);
            if (CsvSupport.isBlank(cols)) {
                continue;
            }

            int rowNumber = i + 1; // 1-indexed line number in the source file
            String rawLine = String.join(",", cols);

            // 1. Date
            String dateVal = CsvSupport.get(cols, mapping.dateCol);
            LocalDate date;
            try {
                date = CsvSupport.parseDate(dateVal, preferredDateFormat);
            } catch (IllegalArgumentException e) {
                // If the date is completely empty and no amounts exist, classify as skipped metadata row
                String debitStr = mapping.debitCol != -1 ? CsvSupport.get(cols, mapping.debitCol) : "";
                String creditStr = mapping.creditCol != -1 ? CsvSupport.get(cols, mapping.creditCol) : "";
                String amtStr = mapping.amtCol != -1 ? CsvSupport.get(cols, mapping.amtCol) : "";
                if (dateVal.isBlank() && debitStr.isBlank() && creditStr.isBlank() && amtStr.isBlank()) {
                    skippedRows++;
                    continue;
                }
                errors.add(RowError.of(rowNumber, e.getMessage(), rawLine));
                continue;
            }

            // 2. Description
            String title = CsvSupport.normalizeDescription(CsvSupport.get(cols, mapping.descCol));
            if (title.isBlank()) {
                title = bank.name() + " Transaction";
            }

            // 3. Amount & Direction
            try {
                if (mapping.debitCol != -1 || mapping.creditCol != -1) {
                    // Separate debit & credit columns layout
                    String debitStr = mapping.debitCol != -1 ? CsvSupport.get(cols, mapping.debitCol) : "";
                    String creditStr = mapping.creditCol != -1 ? CsvSupport.get(cols, mapping.creditCol) : "";

                    CsvSupport.ParsedAmount debitParsed = CsvSupport.parseAmount(debitStr);
                    CsvSupport.ParsedAmount creditParsed = CsvSupport.parseAmount(creditStr);

                    BigDecimal debitVal = debitParsed != null ? debitParsed.amount() : null;
                    BigDecimal creditVal = creditParsed != null ? creditParsed.amount() : null;

                    boolean hasDebit = debitVal != null && debitVal.signum() > 0;
                    boolean hasCredit = creditVal != null && creditVal.signum() > 0;

                    if (!hasDebit && !hasCredit) {
                        skippedRows++;
                    } else if (hasDebit && hasCredit) {
                        errors.add(RowError.of(rowNumber, "Ambiguous row: both debit and credit amounts are present", rawLine));
                    } else if (hasDebit) {
                        validRows.add(new ParsedRow(date, title, debitVal, TransactionType.EXPENSE, bank.name()));
                    } else {
                        validRows.add(new ParsedRow(date, title, creditVal, TransactionType.INCOME, bank.name()));
                    }

                } else if (mapping.amtCol != -1) {
                    // Single amount column with Cr/Dr column or signed/suffixed amount
                    String amtStr = CsvSupport.get(cols, mapping.amtCol);
                    CsvSupport.ParsedAmount amtParsed = CsvSupport.parseAmount(amtStr);

                    if (amtParsed == null || amtParsed.amount().compareTo(BigDecimal.ZERO) == 0) {
                        skippedRows++;
                    } else {
                        BigDecimal amt = amtParsed.amount();
                        TransactionType type = null;

                        if (mapping.crdrCol != -1) {
                            String crdr = CsvSupport.get(cols, mapping.crdrCol).toUpperCase(Locale.ROOT).trim();
                            if (crdr.contains("CR") || "C".equals(crdr)) {
                                type = TransactionType.INCOME;
                            } else if (crdr.contains("DR") || "D".equals(crdr)) {
                                type = TransactionType.EXPENSE;
                            }
                        }

                        if (type == null && amtParsed.isCreditHint() != null) {
                            type = amtParsed.isCreditHint() ? TransactionType.INCOME : TransactionType.EXPENSE;
                        }

                        if (type != null) {
                            validRows.add(new ParsedRow(date, title, amt, type, bank.name()));
                        } else {
                            errors.add(RowError.of(rowNumber, "Cannot determine transaction direction: Cr/Dr indicator is missing or ambiguous", rawLine));
                        }
                    }
                }
            } catch (IllegalArgumentException e) {
                errors.add(RowError.of(rowNumber, e.getMessage(), rawLine));
                continue;
            }

            // 4. Balance column
            if (mapping.balCol != -1) {
                String balStr = CsvSupport.get(cols, mapping.balCol);
                try {
                    CsvSupport.ParsedAmount bal = CsvSupport.parseAmount(balStr);
                    if (bal != null) {
                        lastClosingBalance = bal.amount();
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }

        LocalDate statementFrom = validRows.stream().map(ParsedRow::date).min(LocalDate::compareTo).orElse(null);
        LocalDate statementTo = validRows.stream().map(ParsedRow::date).max(LocalDate::compareTo).orElse(null);

        int totalDataRows = validRows.size() + errors.size() + skippedRows;
        return new StatementParseResult(validRows, errors, totalDataRows, skippedRows, lastClosingBalance, statementFrom, statementTo);
    }

    private ColumnMapping mapColumns(String[] headerCols) {
        int dateCol = -1;
        int descCol = -1;
        int debitCol = -1;
        int creditCol = -1;
        int amtCol = -1;
        int crdrCol = -1;
        int balCol = -1;

        for (int i = 0; i < headerCols.length; i++) {
            String norm = CsvSupport.normalizeHeader(headerCols[i]);

            // Date mapping: prioritize exact matches
            if (dateCol == -1 && (norm.equals("date") || norm.equals("txndate") || norm.equals("trandate") || norm.equals("transactiondate"))) {
                dateCol = i;
            } else if (dateCol == -1 && (norm.contains("date") || norm.contains("valuedt") || norm.contains("tran date")) && !norm.contains("valuedate")) {
                dateCol = i;
            }

            // Description mapping
            if (descCol == -1 && (norm.contains("narration") || norm.contains("description") || norm.contains("particulars")
                    || norm.contains("remarks") || norm.contains("transactionremarks"))) {
                descCol = i;
            }

            // Debit mapping
            if (debitCol == -1 && (norm.contains("withdrawalamt") || norm.contains("withdrawalamount") || norm.contains("withdrawals")
                    || norm.contains("withdrawal") || norm.contains("debitamount") || norm.equals("debit") || norm.equals("dr"))) {
                debitCol = i;
            }

            // Credit mapping
            if (creditCol == -1 && (norm.contains("depositamt") || norm.contains("depositamount") || norm.contains("deposits")
                    || norm.contains("deposit") || norm.contains("creditamount") || norm.equals("credit") || norm.equals("cr"))) {
                creditCol = i;
            }

            // Cr/Dr mapping
            if (crdrCol == -1 && (norm.contains("cr/dr") || norm.contains("crdr") || norm.equals("type") || norm.equals("drcr"))) {
                crdrCol = i;
            }

            // Amount mapping (single column)
            if (amtCol == -1 && (norm.equals("amount") || norm.equals("txnamount") || norm.equals("transactionamount") || norm.contains("amt"))
                    && !norm.contains("withdrawal") && !norm.contains("deposit") && !norm.contains("debit") && !norm.contains("credit")) {
                amtCol = i;
            }

            // Balance mapping
            if (balCol == -1 && (norm.contains("closingbalance") || norm.contains("balance") || norm.equals("bal"))) {
                balCol = i;
            }
        }

        // Fallback for date if only "valuedate" was present
        if (dateCol == -1) {
            for (int i = 0; i < headerCols.length; i++) {
                String norm = CsvSupport.normalizeHeader(headerCols[i]);
                if (norm.contains("date")) {
                    dateCol = i;
                    break;
                }
            }
        }

        return new ColumnMapping(dateCol, descCol, debitCol, creditCol, amtCol, crdrCol, balCol);
    }

    private record ColumnMapping(
            int dateCol,
            int descCol,
            int debitCol,
            int creditCol,
            int amtCol,
            int crdrCol,
            int balCol
    ) {}
}
