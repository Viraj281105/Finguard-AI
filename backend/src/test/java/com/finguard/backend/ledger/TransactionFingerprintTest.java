package com.finguard.backend.ledger;

import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import com.finguard.backend.ledger.transactions.TransactionFingerprint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionFingerprintTest {

    private static final String USER_A = "user-123";
    private static final String USER_B = "user-456";
    private static final LocalDate DATE_1 = LocalDate.of(2026, 3, 15);
    private static final LocalDate DATE_2 = LocalDate.of(2026, 3, 16);

    @Test
    void isDeterministic() {
        String fp1 = TransactionFingerprint.compute(USER_A, DATE_1, TransactionType.EXPENSE, new BigDecimal("150.00"), "Swiggy Bangalore", 1);
        String fp2 = TransactionFingerprint.compute(USER_A, DATE_1, TransactionType.EXPENSE, new BigDecimal("150.00"), "Swiggy Bangalore", 1);
        assertThat(fp1).isEqualTo(fp2);
        assertThat(fp1).hasSize(64);
    }

    @Test
    void isolatesUsers() {
        String fpUserA = TransactionFingerprint.compute(USER_A, DATE_1, TransactionType.EXPENSE, new BigDecimal("150.00"), "Swiggy", 1);
        String fpUserB = TransactionFingerprint.compute(USER_B, DATE_1, TransactionType.EXPENSE, new BigDecimal("150.00"), "Swiggy", 1);
        assertThat(fpUserA).isNotEqualTo(fpUserB);
    }

    @Test
    void differsByDate() {
        String fp1 = TransactionFingerprint.compute(USER_A, DATE_1, TransactionType.EXPENSE, new BigDecimal("150.00"), "Swiggy", 1);
        String fp2 = TransactionFingerprint.compute(USER_A, DATE_2, TransactionType.EXPENSE, new BigDecimal("150.00"), "Swiggy", 1);
        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    void differsByType() {
        String fpExpense = TransactionFingerprint.compute(USER_A, DATE_1, TransactionType.EXPENSE, new BigDecimal("150.00"), "Transfer", 1);
        String fpIncome = TransactionFingerprint.compute(USER_A, DATE_1, TransactionType.INCOME, new BigDecimal("150.00"), "Transfer", 1);
        assertThat(fpExpense).isNotEqualTo(fpIncome);
    }

    @Test
    void normalizesScaleForAmount() {
        String fp1 = TransactionFingerprint.compute(USER_A, DATE_1, TransactionType.EXPENSE, new BigDecimal("150.5"), "Swiggy", 1);
        String fp2 = TransactionFingerprint.compute(USER_A, DATE_1, TransactionType.EXPENSE, new BigDecimal("150.50"), "Swiggy", 1);
        String fp3 = TransactionFingerprint.compute(USER_A, DATE_1, TransactionType.EXPENSE, new BigDecimal("150.5000"), "Swiggy", 1);
        assertThat(fp1).isEqualTo(fp2).isEqualTo(fp3);
    }

    @Test
    void normalizesTitleWhitespaceAndCase() {
        String fp1 = TransactionFingerprint.compute(USER_A, DATE_1, TransactionType.EXPENSE, new BigDecimal("150.00"), "UPI-SWIGGY-12345", 1);
        String fp2 = TransactionFingerprint.compute(USER_A, DATE_1, TransactionType.EXPENSE, new BigDecimal("150.00"), "  upi-swiggy-12345  ", 1);
        String fp3 = TransactionFingerprint.compute(USER_A, DATE_1, TransactionType.EXPENSE, new BigDecimal("150.00"), "upi-swiggy-12345", 1);
        assertThat(fp1).isEqualTo(fp2).isEqualTo(fp3);
    }

    @Test
    void intraFileOccurrenceIndexDifferentiatesIdenticalSameDayTransactions() {
        String fpFirstPurchase = TransactionFingerprint.compute(USER_A, DATE_1, TransactionType.EXPENSE, new BigDecimal("20.00"), "Chai Point", 1);
        String fpSecondPurchase = TransactionFingerprint.compute(USER_A, DATE_1, TransactionType.EXPENSE, new BigDecimal("20.00"), "Chai Point", 2);
        assertThat(fpFirstPurchase).isNotEqualTo(fpSecondPurchase);
    }
}
