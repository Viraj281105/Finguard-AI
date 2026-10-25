package com.finguard.backend.intelligence;

import com.finguard.backend.identity.domain.User;
import com.finguard.backend.intelligence.dto.*;
import com.finguard.backend.intelligence.service.AnalyticsService;
import com.finguard.backend.intelligence.service.InsightGenerator;
import com.finguard.backend.intelligence.service.RecurringExpenseDetector;
import com.finguard.backend.intelligence.service.UnusualTransactionDetector;
import com.finguard.backend.ledger.investments.Investment;
import com.finguard.backend.ledger.investments.InvestmentRepository;
import com.finguard.backend.ledger.loans.Loan;
import com.finguard.backend.ledger.loans.LoanRepository;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import com.finguard.backend.ledger.transactions.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private InvestmentRepository investmentRepository;

    @Mock
    private LoanRepository loanRepository;

    private AnalyticsService analyticsService;

    private User user;

    @BeforeEach
    void setUp() {
        RecurringExpenseDetector recurringDetector = new RecurringExpenseDetector();
        UnusualTransactionDetector outlierDetector = new UnusualTransactionDetector();
        InsightGenerator insightGenerator = new InsightGenerator();

        analyticsService = new AnalyticsService(
                transactionRepository,
                investmentRepository,
                loanRepository,
                recurringDetector,
                outlierDetector,
                insightGenerator
        );

        user = new User();
        user.setId("user-1");
        user.setEmail("user1@example.com");
    }

    private Transaction tx(String id, String title, double amount, TransactionType type, String category, LocalDate date) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setUser(user);
        t.setTitle(title);
        t.setAmount(BigDecimal.valueOf(amount).setScale(2));
        t.setType(type);
        t.setCategory(category);
        t.setDate(date);
        return t;
    }

    @Test
    void goldenDatasetCalculations_correctInclusionsAndExclusions() {
        // Golden dataset with specific financial semantics:
        // Income:
        //   - Salary: 50,000.00 (flow INCOME)
        //   - Freelance: 10,000.00 (flow INCOME)
        //   - Transfer credit: 5,000.00 (flow NON_SPENDING - EXCLUDED)
        //   - Refund: 1,500.00 (flow REFUND - reduces expense, NOT income)
        //   Total Income: 60,000.00
        //
        // Expenses:
        //   - Groceries: 4,000.00 (flow EXPENSE)
        //   - Rent/Housing: 15,000.00 (flow EXPENSE)
        //   - Dining: 2,500.00 (flow EXPENSE)
        //   - Credit card payment debit: 12,000.00 (flow NON_SPENDING - EXCLUDED)
        //   - Investment transfer debit: 10,000.00 (flow NON_SPENDING - EXCLUDED)
        //   - Transfer debit: 3,000.00 (flow NON_SPENDING - EXCLUDED)
        //   - Uncategorized expense: 500.00 (counted in cash flow)
        //   Gross Expenses: 4000 + 15000 + 2500 + 500 = 22,000.00
        //
        // Net Expenses:
        //   Gross (22,000.00) - Refund (1,500.00) = 20,500.00
        //
        // Net Cash Flow:
        //   Income (60,000.00) - Net Expenses (20,500.00) = 39,500.00
        //
        // Savings Rate:
        //   (39,500.00 * 100) / 60,000.00 = 65.83%
        List<Transaction> transactions = List.of(
                tx("t1", "Monthly Salary", 50000.00, TransactionType.INCOME, "INCOME", LocalDate.of(2026, 3, 1)),
                tx("t2", "Freelance Project", 10000.00, TransactionType.INCOME, "INCOME", LocalDate.of(2026, 3, 15)),
                tx("t3", "Transfer from Savings", 5000.00, TransactionType.INCOME, "TRANSFERS", LocalDate.of(2026, 3, 2)),
                tx("t4", "Amazon Refund", 1500.00, TransactionType.INCOME, "REFUNDS", LocalDate.of(2026, 3, 18)),
                tx("t5", "Supermarket Groceries", 4000.00, TransactionType.EXPENSE, "GROCERIES", LocalDate.of(2026, 3, 3)),
                tx("t6", "Apartment Rent", 15000.00, TransactionType.EXPENSE, "HOUSING", LocalDate.of(2026, 3, 5)),
                tx("t7", "Weekend Dining", 2500.00, TransactionType.EXPENSE, "DINING", LocalDate.of(2026, 3, 8)),
                tx("t8", "HDFC Credit Card Bill", 12000.00, TransactionType.EXPENSE, "CARD_PAYMENTS", LocalDate.of(2026, 3, 10)),
                tx("t9", "SIP Mutual Fund", 10000.00, TransactionType.EXPENSE, "INVESTMENTS", LocalDate.of(2026, 3, 12)),
                tx("t10", "Self Account Transfer", 3000.00, TransactionType.EXPENSE, "TRANSFERS", LocalDate.of(2026, 3, 14)),
                tx("t11", "Corner Tea Shop", 500.00, TransactionType.EXPENSE, null, LocalDate.of(2026, 3, 20))
        );

        when(transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc(user.getId())).thenReturn(transactions);
        when(investmentRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());
        when(loanRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());

        FinancialSummaryResponse summary = analyticsService.getSummary(user, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(summary.totalIncome()).isEqualByComparingTo("60000.00");
        assertThat(summary.grossExpenses()).isEqualByComparingTo("22000.00");
        assertThat(summary.totalRefunds()).isEqualByComparingTo("1500.00");
        assertThat(summary.totalExpenses()).isEqualByComparingTo("20500.00");
        assertThat(summary.netCashFlow()).isEqualByComparingTo("39500.00");
        assertThat(summary.savingsRate()).isEqualByComparingTo("65.83");
        assertThat(summary.savingsRateApplicable()).isTrue();
        assertThat(summary.excludes()).containsExactlyInAnyOrder("TRANSFERS", "CARD_PAYMENTS", "INVESTMENTS");
    }

    @Test
    void refundsExceedingExpenses_boundNetExpensesAtZero() {
        List<Transaction> transactions = List.of(
                tx("t1", "Salary", 10000.00, TransactionType.INCOME, "INCOME", LocalDate.of(2026, 3, 1)),
                tx("t2", "Big Refund", 5000.00, TransactionType.INCOME, "REFUNDS", LocalDate.of(2026, 3, 5)),
                tx("t3", "Small Expense", 2000.00, TransactionType.EXPENSE, "SHOPPING", LocalDate.of(2026, 3, 6))
        );

        when(transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc(user.getId())).thenReturn(transactions);
        when(investmentRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());
        when(loanRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());

        FinancialSummaryResponse summary = analyticsService.getSummary(user, null, null);

        assertThat(summary.totalIncome()).isEqualByComparingTo("10000.00");
        assertThat(summary.grossExpenses()).isEqualByComparingTo("2000.00");
        assertThat(summary.totalRefunds()).isEqualByComparingTo("5000.00");
        assertThat(summary.totalExpenses()).isEqualByComparingTo("0.00");
        assertThat(summary.netCashFlow()).isEqualByComparingTo("10000.00");
    }

    @Test
    void zeroIncome_marksSavingsRateInapplicable() {
        List<Transaction> transactions = List.of(
                tx("t1", "Groceries", 3000.00, TransactionType.EXPENSE, "GROCERIES", LocalDate.of(2026, 3, 2))
        );

        when(transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc(user.getId())).thenReturn(transactions);
        when(investmentRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());
        when(loanRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());

        FinancialSummaryResponse summary = analyticsService.getSummary(user, null, null);

        assertThat(summary.totalIncome()).isEqualByComparingTo("0.00");
        assertThat(summary.totalExpenses()).isEqualByComparingTo("3000.00");
        assertThat(summary.netCashFlow()).isEqualByComparingTo("-3000.00");
        assertThat(summary.savingsRate()).isNull();
        assertThat(summary.savingsRateApplicable()).isFalse();
        assertThat(summary.savingsRateCaveat()).contains("no income");
    }

    @Test
    void emptyData_returnsCleanZeroResultsAndHonestCaveats() {
        when(transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc(user.getId())).thenReturn(List.of());
        when(investmentRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());
        when(loanRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(List.of());

        FinancialSummaryResponse summary = analyticsService.getSummary(user, null, null);

        assertThat(summary.totalIncome()).isEqualByComparingTo("0.00");
        assertThat(summary.totalExpenses()).isEqualByComparingTo("0.00");
        assertThat(summary.netCashFlow()).isEqualByComparingTo("0.00");
        assertThat(summary.savingsRate()).isNull();
        assertThat(summary.dataQuality().totalTransactions()).isZero();
        assertThat(summary.dataQuality().hasSufficientHistory()).isFalse();
    }

    @Test
    void categoryBreakdown_calculatesPercentagesAndLargestCategory() {
        List<Transaction> transactions = List.of(
                tx("t1", "Salary", 20000.00, TransactionType.INCOME, "INCOME", LocalDate.of(2026, 3, 1)),
                tx("t2", "Big Rent", 12000.00, TransactionType.EXPENSE, "HOUSING", LocalDate.of(2026, 3, 2)),
                tx("t3", "Groceries", 6000.00, TransactionType.EXPENSE, "GROCERIES", LocalDate.of(2026, 3, 5)),
                tx("t4", "Dining Out", 2000.00, TransactionType.EXPENSE, "DINING", LocalDate.of(2026, 3, 10))
        );

        when(transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc(user.getId())).thenReturn(transactions);

        CategoryBreakdownResponse breakdown = analyticsService.getCategoryBreakdown(user, null, null);

        assertThat(breakdown.totalGrossExpenses()).isEqualByComparingTo("20000.00");
        assertThat(breakdown.largestCategory()).isEqualTo("HOUSING");
        assertThat(breakdown.categories()).hasSize(3);

        CategorySpendingDto top = breakdown.categories().get(0);
        assertThat(top.category()).isEqualTo("HOUSING");
        assertThat(top.amount()).isEqualByComparingTo("12000.00");
        assertThat(top.percentage()).isEqualByComparingTo("60.00");
    }

    @Test
    void monthlyFlows_groupsCalendarMonthsAndCalculatesMoM() {
        List<Transaction> transactions = List.of(
                tx("t1", "Jan Salary", 30000.00, TransactionType.INCOME, "INCOME", LocalDate.of(2026, 1, 5)),
                tx("t2", "Jan Expense", 10000.00, TransactionType.EXPENSE, "GROCERIES", LocalDate.of(2026, 1, 10)),
                tx("t3", "Feb Salary", 30000.00, TransactionType.INCOME, "INCOME", LocalDate.of(2026, 2, 5)),
                tx("t4", "Feb Expense", 15000.00, TransactionType.EXPENSE, "GROCERIES", LocalDate.of(2026, 2, 10))
        );

        when(transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc(user.getId())).thenReturn(transactions);

        List<MonthlyFlowDto> monthly = analyticsService.getMonthlyFlows(user, null, null);

        assertThat(monthly).hasSize(2);
        MonthlyFlowDto jan = monthly.get(0);
        assertThat(jan.month()).isEqualTo("2026-01");
        assertThat(jan.expenses()).isEqualByComparingTo("10000.00");
        assertThat(jan.momExpenseChangePct()).isNull();

        MonthlyFlowDto feb = monthly.get(1);
        assertThat(feb.month()).isEqualTo("2026-02");
        assertThat(feb.expenses()).isEqualByComparingTo("15000.00");
        // Jan was 10,000, Feb is 15,000 -> +50.00%
        assertThat(feb.momExpenseChangePct()).isEqualByComparingTo("50.00");
    }
}
