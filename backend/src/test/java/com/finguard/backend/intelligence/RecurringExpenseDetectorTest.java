package com.finguard.backend.intelligence;

import com.finguard.backend.identity.domain.User;
import com.finguard.backend.intelligence.dto.RecurringExpenseItemDto;
import com.finguard.backend.intelligence.service.RecurringExpenseDetector;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecurringExpenseDetectorTest {

    private RecurringExpenseDetector detector;
    private User user;

    @BeforeEach
    void setUp() {
        detector = new RecurringExpenseDetector();
        user = new User();
        user.setId("u1");
    }

    private Transaction tx(String title, double amount, LocalDate date) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setTitle(title);
        t.setAmount(BigDecimal.valueOf(amount).setScale(2));
        t.setType(TransactionType.EXPENSE);
        t.setDate(date);
        return t;
    }

    @Test
    void monthlyCadenceOverThreeMonths_detectedWithHighConfidence() {
        List<Transaction> txs = List.of(
                tx("UPI-NETFLIX-992812", 649.00, LocalDate.of(2026, 1, 5)),
                tx("UPI-NETFLIX-992813", 649.00, LocalDate.of(2026, 2, 5)),
                tx("UPI-NETFLIX-992814", 649.00, LocalDate.of(2026, 3, 5))
        );

        List<RecurringExpenseItemDto> result = detector.detectRecurring(txs);

        assertThat(result).hasSize(1);
        RecurringExpenseItemDto item = result.get(0);
        assertThat(item.merchant()).isEqualTo("Netflix");
        assertThat(item.amount()).isEqualByComparingTo("649.00");
        assertThat(item.cadence()).isEqualTo("MONTHLY");
        assertThat(item.occurrences()).isEqualTo(3);
        assertThat(item.confidence()).isEqualTo("HIGH");
    }

    @Test
    void irregularAmounts_notDetectedAsMonthlyRecurring() {
        List<Transaction> txs = List.of(
                tx("Swiggy Order", 250.00, LocalDate.of(2026, 1, 5)),
                tx("Swiggy Order", 950.00, LocalDate.of(2026, 2, 5)),
                tx("Swiggy Order", 1500.00, LocalDate.of(2026, 3, 5))
        );

        List<RecurringExpenseItemDto> result = detector.detectRecurring(txs);
        assertThat(result).isEmpty();
    }

    @Test
    void weeklyCadence_convertsToMonthlyEquivalent() {
        List<Transaction> txs = List.of(
                tx("Organic Milk Delivery", 300.00, LocalDate.of(2026, 1, 7)),
                tx("Organic Milk Delivery", 300.00, LocalDate.of(2026, 1, 14)),
                tx("Organic Milk Delivery", 300.00, LocalDate.of(2026, 1, 21)),
                tx("Organic Milk Delivery", 300.00, LocalDate.of(2026, 1, 28))
        );

        List<RecurringExpenseItemDto> result = detector.detectRecurring(txs);
        assertThat(result).hasSize(1);
        RecurringExpenseItemDto item = result.get(0);
        assertThat(item.cadence()).isEqualTo("WEEKLY");
        // 300 * 4.3333 = ~1300.00
        assertThat(item.amount()).isEqualByComparingTo("1300.00");
    }
}
