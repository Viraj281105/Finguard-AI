package com.finguard.backend.ingestion.parser;

import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parser behaviour on synthetic fixtures (see fixtures/statements/README.md) and programmatic variations.
 * These verify header mapping, reordered columns, ICICI withdrawal/deposit layout, amount and date formats,
 * and transparent row reconciliation. They do not prove compatibility with every unverified real bank export.
 */
class StatementParserRegistryTest {

    private final StatementParserRegistry registry = new StatementParserRegistry(
            new HdfcParser(), new IciciParser(), new SbiParser(), new AxisParser(), new KotakParser());

    private static String fixture(String name) throws IOException {
        try (var in = StatementParserRegistryTest.class.getResourceAsStream("/fixtures/statements/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ParsedRow row(String date, String title, String amount, TransactionType type, BankFormat bank) {
        return new ParsedRow(LocalDate.parse(date), title, new BigDecimal(amount), type, bank.name());
    }

    @Test
    void hdfcIsDetectedBelowJunkRowsAndParsed() throws IOException {
        String csv = fixture("hdfc.csv");
        assertThat(registry.detectBank(csv)).isEqualTo(BankFormat.HDFC);
        assertThat(registry.parse(csv)).containsExactly(
                row("2026-03-01", "UPI-SWIGGY-swiggy@icici", "450.00", TransactionType.EXPENSE, BankFormat.HDFC),
                row("2026-03-02", "NEFT-ACME LTD-SALARY MARCH", "75000.00", TransactionType.INCOME, BankFormat.HDFC),
                row("2026-03-05", "ATM WDL, MG ROAD BENGALURU", "10000.00", TransactionType.EXPENSE, BankFormat.HDFC));
    }

    @Test
    void iciciUsesTheCrDrColumn() throws IOException {
        String csv = fixture("icici.csv");
        assertThat(registry.detectBank(csv)).isEqualTo(BankFormat.ICICI);
        assertThat(registry.parse(csv)).containsExactly(
                row("2026-03-03", "UPI/ZOMATO/order", "320.50", TransactionType.EXPENSE, BankFormat.ICICI),
                row("2026-03-04", "NEFT/EMPLOYER/SAL", "52000.00", TransactionType.INCOME, BankFormat.ICICI));
    }

    @Test
    void iciciParsesWithdrawalAndDepositLayoutCorrectly() {
        // Resolves KI-005: ICICI statements with separate withdrawal and deposit columns
        String csv = """
                Transaction Date,Value Date,Transaction Remarks,Withdrawal Amount,Deposit Amount,Balance
                03/03/2026,03/03/2026,UPI/ZOMATO/order,320.50,,12000.00
                04/03/2026,04/03/2026,NEFT/EMPLOYER/SAL,,"52,000.00","64,000.00"
                """;
        assertThat(registry.detectBank(csv)).isEqualTo(BankFormat.ICICI);
        StatementParseResult result = registry.parseStatement(csv);
        assertThat(result.rows()).containsExactly(
                row("2026-03-03", "UPI/ZOMATO/order", "320.50", TransactionType.EXPENSE, BankFormat.ICICI),
                row("2026-03-04", "NEFT/EMPLOYER/SAL", "52000.00", TransactionType.INCOME, BankFormat.ICICI));
        assertThat(result.closingBalance()).isEqualByComparingTo("64000.00");
    }

    @Test
    void sbiParsesDayMonthNameDates() throws IOException {
        String csv = fixture("sbi.csv");
        assertThat(registry.detectBank(csv)).isEqualTo(BankFormat.SBI);
        assertThat(registry.parse(csv)).containsExactly(
                row("2026-03-14", "UPI/DR/Big Bazaar", "1250.00", TransactionType.EXPENSE, BankFormat.SBI),
                row("2026-03-15", "INTEREST CREDIT", "45.10", TransactionType.INCOME, BankFormat.SBI));
    }

    @Test
    void axisParsesDrAndCrColumns() throws IOException {
        String csv = fixture("axis.csv");
        assertThat(registry.detectBank(csv)).isEqualTo(BankFormat.AXIS);
        assertThat(registry.parse(csv)).containsExactly(
                row("2026-03-10", "UPI/P2M/UBER INDIA", "215.00", TransactionType.EXPENSE, BankFormat.AXIS),
                row("2026-03-11", "SALARY/ACME", "40000.00", TransactionType.INCOME, BankFormat.AXIS));
    }

    @Test
    void kotakParsesDebitAndCreditAmountColumns() throws IOException {
        String csv = fixture("kotak.csv");
        assertThat(registry.detectBank(csv)).isEqualTo(BankFormat.KOTAK);
        assertThat(registry.parse(csv)).containsExactly(
                row("2026-03-12", "EMI HOME LOAN", "18500.00", TransactionType.EXPENSE, BankFormat.KOTAK),
                row("2026-03-13", "REFUND AMAZON", "899.00", TransactionType.INCOME, BankFormat.KOTAK));
    }

    @Test
    void reorderedColumnsAcrossAllBanksAreParsedIdentically() {
        // Resolves KI-003: column reordering does not affect parsing
        String reorderedHdfc = """
                Narration,Closing Balance,Deposit Amt.,Withdrawal Amt.,Chq./Ref.No.,Date,Value Dt
                UPI-SWIGGY-swiggy@icici,10550.00,,450.00,REF1,01/03/26,01/03/26
                NEFT-ACME LTD-SALARY MARCH,85550.00,75000.00,,REF2,02/03/26,02/03/26
                """;
        assertThat(registry.detectBank(reorderedHdfc)).isEqualTo(BankFormat.HDFC);
        List<ParsedRow> hdfcRows = registry.parse(reorderedHdfc);
        assertThat(hdfcRows).hasSize(2);
        assertThat(hdfcRows.get(0).title()).isEqualTo("UPI-SWIGGY-swiggy@icici");
        assertThat(hdfcRows.get(0).amount()).isEqualByComparingTo("450.00");
        assertThat(hdfcRows.get(0).type()).isEqualTo(TransactionType.EXPENSE);

        String reorderedAxis = """
                PARTICULARS,CR,BAL,DR,Tran Date,CHQNO
                UPI/P2M/UBER INDIA,,5000.00,215.00,10-03-2026,
                SALARY/ACME,40000.00,45000.00,,11-03-2026,
                """;
        assertThat(registry.detectBank(reorderedAxis)).isEqualTo(BankFormat.AXIS);
        List<ParsedRow> axisRows = registry.parse(reorderedAxis);
        assertThat(axisRows).hasSize(2);
        assertThat(axisRows.get(0).title()).isEqualTo("UPI/P2M/UBER INDIA");
        assertThat(axisRows.get(0).amount()).isEqualByComparingTo("215.00");
        assertThat(axisRows.get(1).type()).isEqualTo(TransactionType.INCOME);
    }

    @Test
    void extraAndArbitraryColumnsDoNotBreakParsing() {
        String csvWithExtraCols = """
                Branch Name,Txn Date,Extra Note,Description,Ref No./Cheque No.,Debit,Credit,Tax,Balance,Status
                Koramangala,14 Mar 2026,Store #4,UPI/DR/Big Bazaar,UPI123,1250.00,,0.00,20000.00,SUCCESS
                Indiranagar,15 Mar 2026,Savings,INTEREST CREDIT,,,"45.10",0.00,20045.10,SUCCESS
                """;
        assertThat(registry.detectBank(csvWithExtraCols)).isEqualTo(BankFormat.SBI);
        List<ParsedRow> rows = registry.parse(csvWithExtraCols);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).title()).isEqualTo("UPI/DR/Big Bazaar");
        assertThat(rows.get(0).amount()).isEqualByComparingTo("1250.00");
    }

    @Test
    void robustAmountParsingSupportsParenthesesRupeeSymbolsAndDrCrSuffixes() {
        // Resolves KI-006: parenthesized amounts, currency symbols, and trailing Dr/Cr
        String csv = """
                Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
                01/03/26,Fee Adjustment,001,01/03/26,(125.50),,10000.00
                02/03/26,Cashback,002,02/03/26,,"₹ 500.00",10500.00
                03/03/26,Card Charge,003,03/03/26,250.00 Dr,,10250.00
                04/03/26,Interest,004,04/03/26,,Rs. 75.25 Cr,10325.25
                """;
        List<ParsedRow> rows = registry.parse(csv);
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("125.50");
        assertThat(rows.get(0).type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(rows.get(1).amount()).isEqualByComparingTo("500.00");
        assertThat(rows.get(1).type()).isEqualTo(TransactionType.INCOME);
        assertThat(rows.get(2).amount()).isEqualByComparingTo("250.00");
        assertThat(rows.get(2).type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(rows.get(3).amount()).isEqualByComparingTo("75.25");
        assertThat(rows.get(3).type()).isEqualTo(TransactionType.INCOME);
    }

    @Test
    void multiFormatDatesAreParsedAcrossIndianBankStyles() {
        String csv = """
                Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
                01-03-2026,Dash format,001,01-03-2026,100.00,,1000.00
                14 Mar 2026,Month text format,002,14 Mar 2026,200.00,,800.00
                2026-03-20,ISO format,003,2026-03-20,,500.00,1300.00
                """;
        List<ParsedRow> rows = registry.parse(csv);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).date()).isEqualTo(LocalDate.parse("2026-03-01"));
        assertThat(rows.get(1).date()).isEqualTo(LocalDate.parse("2026-03-14"));
        assertThat(rows.get(2).date()).isEqualTo(LocalDate.parse("2026-03-20"));
    }

    @Test
    void transparentRowReconciliationAndErrorSanitization() throws IOException {
        // Resolves KI-004: every single row is accounted for, never silently discarded
        String csv = fixture("hdfc.csv");
        StatementParseResult result = registry.parseStatement(csv);

        assertThat(result.rows()).hasSize(3);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.skippedRows()).isEqualTo(1);
        assertThat(result.totalDataRows()).isEqualTo(5);

        // Verification of reconciliation invariant: totalDataRows == rows + errors + skipped
        assertThat(result.totalDataRows()).isEqualTo(result.rows().size() + result.errors().size() + result.skippedRows());

        // Check error details (1-indexed row number, reason, sanitized snippet)
        RowError error = result.errors().get(0);
        assertThat(error.rowNumber()).isEqualTo(8);
        assertThat(error.errorMessage()).contains("Cannot parse date: 'notadate'");
        assertThat(error.snippet()).contains("INVALID DATE ROW");

        // Statement period and closing balance
        assertThat(result.statementFrom()).isEqualTo(LocalDate.parse("2026-03-01"));
        assertThat(result.statementTo()).isEqualTo(LocalDate.parse("2026-03-05"));
        assertThat(result.closingBalance()).isEqualByComparingTo("75550.00");
    }

    @Test
    void piiInErrorSnippetsIsSanitizedToMaskLongDigits() {
        String rawWithAccount = "01/01/26,TRANSFER TO A/C 1234567890123456,REF,01/01/26,notanamount,,5000";
        RowError error = RowError.of(4, "Cannot parse amount: 'notanamount'", rawWithAccount);
        assertThat(error.snippet()).doesNotContain("1234567890123456");
        assertThat(error.snippet()).contains("1234****3456");
    }

    @Test
    void unrecognisedFormatIsRejectedWithAUserFacingMessage() throws IOException {
        String csv = fixture("unknown.csv");
        assertThat(registry.detectBank(csv)).isEqualTo(BankFormat.UNKNOWN);
        assertThatThrownBy(() -> registry.parse(csv))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognized CSV format");
    }

    @Test
    void byteOrderMarkDoesNotBreakDetectionOrParsing() throws IOException {
        String csv = "\uFEFF" + fixture("axis.csv");
        assertThat(registry.detectBank(csv)).isEqualTo(BankFormat.AXIS);
        assertThat(registry.parse(csv)).hasSize(2);
    }

    @Test
    void amountsKeepFullDecimalPrecision() throws IOException {
        List<ParsedRow> rows = registry.parse(fixture("icici.csv"));
        assertThat(rows.get(0).amount()).isEqualByComparingTo("320.50");
        assertThat(rows.get(0).amount().scale()).isEqualTo(2);
    }
}
