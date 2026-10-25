package com.finguard.backend.intelligence;

import com.finguard.backend.identity.domain.User;
import com.finguard.backend.intelligence.dto.UnusualTransactionDto;
import com.finguard.backend.intelligence.service.UnusualTransactionDetector;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnusualTransactionDetectorTest {

    private UnusualTransactionDetector detector;
    private User user;

    @BeforeEach
    void setUp() {
        detector = new UnusualTransactionDetector();
        user = new User();
        user.setId("u1");
    }

    private Transaction tx(String title, double amount, LocalDate date) {
        Transaction t = new Transaction();
        t.setId("tx-" + amount);
        t.setUser(user);
        t.setTitle(title);
        t.setAmount(BigDecimal.valueOf(amount).setScale(2));
        t.setType(TransactionType.EXPENSE);
        t.setCategory("DINING");
        t.setDate(date);
        return t;
    }

    @Test
    void outlierExceedingMedianThreshold_flaggedWithExplainableReason() {
        // Typical expenses: 200, 300, 400, 500, 600 -> Median is 400
        // Outlier: 15,000 (well above min anomaly threshold of 1000 and 2.5x median)
        List<Transaction> txs = List.of(
                tx("Lunch 1", 200.00, LocalDate.of(2026, 3, 1)),
                tx("Lunch 2", 300.00, LocalDate.of(2026, 3, 2)),
                tx("Lunch 3", 400.00, LocalDate.of(2026, 3, 3)),
                tx("Lunch 4", 500.00, LocalDate.of(2026, 3, 4)),
                tx("Dinner", 600.00, LocalDate.of(2026, 3, 5)),
                tx("Luxury Banquet", 15000.00, LocalDate.of(2026, 3, 6))
        );

        List<UnusualTransactionDto> outliers = detector.detectOutliers(txs);

        assertThat(outliers).hasSize(1);
        UnusualTransactionDto outlier = outliers.get(0);
        assertThat(outlier.title()).isEqualTo("Luxury Banquet");
        assertThat(outlier.amount()).isEqualByComparingTo("15000.00");
        assertThat(outlier.benchmarkAmount()).isEqualByComparingTo("450.00");
        assertThat(outlier.reason()).contains("15000.00").contains("450.00");
    }

    @Test
    void lowAmountTransactions_neverFlaggedAsOutliers() {
        // All amounts under 1000
        List<Transaction> txs = List.of(
                tx("Tea", 10.00, LocalDate.of(2026, 3, 1)),
                tx("Snack", 20.00, LocalDate.of(2026, 3, 2)),
                tx("Coffee", 150.00, LocalDate.of(2026, 3, 3))
        );

        List<UnusualTransactionDto> outliers = detector.detectOutliers(txs);
        assertThat(outliers).isEmpty();
    }
}
