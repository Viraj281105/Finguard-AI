package com.finguard.backend.budgeting;

import com.finguard.backend.budgeting.domain.Budget;
import com.finguard.backend.budgeting.domain.BudgetRepository;
import com.finguard.backend.budgeting.dto.*;
import com.finguard.backend.budgeting.service.BudgetService;
import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import com.finguard.backend.ledger.transactions.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BudgetServiceTest {

    @Mock
    private BudgetRepository budgetRepository;

    @Mock
    private TransactionRepository transactionRepository;

    private BudgetService budgetService;
    private User testUser;

    @BeforeEach
    void setUp() {
        budgetService = new BudgetService(budgetRepository, transactionRepository);

        testUser = new User();
        testUser.setId("usr-100");
        testUser.setName("Tester");
        testUser.setEmail("tester@example.com");
    }

    @Test
    @DisplayName("Creates category budget successfully")
    void createCategoryBudget_success() {
        CreateBudgetRequest request = new CreateBudgetRequest("2026-10", "DINING", BigDecimal.valueOf(5000));
        when(budgetRepository.findByUserIdAndYearMonthAndCategory("usr-100", "2026-10", "DINING"))
                .thenReturn(Optional.empty());

        when(budgetRepository.save(any(Budget.class))).thenAnswer(inv -> {
            Budget b = inv.getArgument(0);
            b.setId("b-1");
            return b;
        });

        BudgetDto dto = budgetService.createBudget(testUser, request);

        assertThat(dto.id()).isEqualTo("b-1");
        assertThat(dto.yearMonth()).isEqualTo("2026-10");
        assertThat(dto.category()).isEqualTo("DINING");
        assertThat(dto.amount()).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("Creates overall monthly budget when category is null")
    void createOverallBudget_success() {
        CreateBudgetRequest request = new CreateBudgetRequest("2026-10", null, BigDecimal.valueOf(40000));
        when(budgetRepository.findByUserIdAndYearMonthAndCategoryIsNull("usr-100", "2026-10"))
                .thenReturn(Optional.empty());

        when(budgetRepository.save(any(Budget.class))).thenAnswer(inv -> {
            Budget b = inv.getArgument(0);
            b.setId("b-overall");
            return b;
        });

        BudgetDto dto = budgetService.createBudget(testUser, request);

        assertThat(dto.category()).isNull();
        assertThat(dto.amount()).isEqualByComparingTo("40000.00");
    }

    @Test
    @DisplayName("Rejects non-spending category as budget category")
    void createBudget_rejectsNonSpendingCategory() {
        CreateBudgetRequest transferReq = new CreateBudgetRequest("2026-10", "TRANSFERS", BigDecimal.valueOf(5000));
        assertThatThrownBy(() -> budgetService.createBudget(testUser, transferReq))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Non-spending category")
                .matches(e -> ((ApiException) e).getStatus() == HttpStatus.BAD_REQUEST);

        CreateBudgetRequest cardPayReq = new CreateBudgetRequest("2026-10", "CARD_PAYMENTS", BigDecimal.valueOf(5000));
        assertThatThrownBy(() -> budgetService.createBudget(testUser, cardPayReq))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Non-spending category");

        CreateBudgetRequest investReq = new CreateBudgetRequest("2026-10", "INVESTMENTS", BigDecimal.valueOf(5000));
        assertThatThrownBy(() -> budgetService.createBudget(testUser, investReq))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Non-spending category");
    }

    @Test
    @DisplayName("Rejects duplicate budget for same user and month with 409 Conflict")
    void createBudget_duplicateConflict() {
        CreateBudgetRequest request = new CreateBudgetRequest("2026-10", "GROCERIES", BigDecimal.valueOf(8000));
        when(budgetRepository.findByUserIdAndYearMonthAndCategory("usr-100", "2026-10", "GROCERIES"))
                .thenReturn(Optional.of(new Budget()));

        assertThatThrownBy(() -> budgetService.createBudget(testUser, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists")
                .matches(e -> ((ApiException) e).getStatus() == HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Updates existing budget amount")
    void updateBudget_success() {
        Budget existing = new Budget();
        existing.setId("b-2");
        existing.setUser(testUser);
        existing.setYearMonth("2026-10");
        existing.setCategory("DINING");
        existing.setAmount(BigDecimal.valueOf(5000));

        when(budgetRepository.findByIdAndUserId("b-2", "usr-100")).thenReturn(Optional.of(existing));
        when(budgetRepository.save(any(Budget.class))).thenAnswer(inv -> inv.getArgument(0));

        BudgetDto updated = budgetService.updateBudget(testUser, "b-2", new UpdateBudgetRequest(BigDecimal.valueOf(7000)));

        assertThat(updated.amount()).isEqualByComparingTo("7000.00");
    }

    @Test
    @DisplayName("Deletes existing budget")
    void deleteBudget_success() {
        Budget existing = new Budget();
        existing.setId("b-3");
        existing.setUser(testUser);

        when(budgetRepository.findByIdAndUserId("b-3", "usr-100")).thenReturn(Optional.of(existing));

        budgetService.deleteBudget(testUser, "b-3");

        verify(budgetRepository).delete(existing);
    }

    @Test
    @DisplayName("Calculates monthly budget overview with refund offsets and non-spending exclusions")
    void getMonthlyOverview_comprehensiveCalculation() {
        // Setup budgets: Overall = 20,000, DINING = 5,000, GROCERIES = 8,000
        Budget overall = new Budget();
        overall.setId("b-ov");
        overall.setUser(testUser);
        overall.setYearMonth("2026-10");
        overall.setCategory(null);
        overall.setAmount(BigDecimal.valueOf(20000));

        Budget dining = new Budget();
        dining.setId("b-din");
        dining.setUser(testUser);
        dining.setYearMonth("2026-10");
        dining.setCategory("DINING");
        dining.setAmount(BigDecimal.valueOf(5000));

        Budget groceries = new Budget();
        groceries.setId("b-groc");
        groceries.setUser(testUser);
        groceries.setYearMonth("2026-10");
        groceries.setCategory("GROCERIES");
        groceries.setAmount(BigDecimal.valueOf(8000));

        when(budgetRepository.findByUserIdAndYearMonthOrderByCategoryAsc("usr-100", "2026-10"))
                .thenReturn(List.of(overall, dining, groceries));

        // Setup transactions in October 2026:
        // 1. DINING debit ₹4,500
        Transaction t1 = createTx("DINING", TransactionType.EXPENSE, BigDecimal.valueOf(4500), LocalDate.of(2026, 10, 5));
        // 2. DINING refund credit ₹500 -> net dining = 4,000 (80% of 5,000 -> APPROACHING_LIMIT)
        Transaction t2 = createTx("REFUNDS", TransactionType.INCOME, BigDecimal.valueOf(500), LocalDate.of(2026, 10, 8));
        t2.setCategory("DINING"); // category refund
        // 3. GROCERIES debit ₹9,000 -> net groceries = 9,000 (112.5% of 8,000 -> OVER_BUDGET)
        Transaction t3 = createTx("GROCERIES", TransactionType.EXPENSE, BigDecimal.valueOf(9000), LocalDate.of(2026, 10, 10));
        // 4. UNBUDGETED category: ENTERTAINMENT debit ₹1,500
        Transaction t4 = createTx("ENTERTAINMENT", TransactionType.EXPENSE, BigDecimal.valueOf(1500), LocalDate.of(2026, 10, 12));
        // 5. Non-spending: TRANSFERS debit ₹10,000 (MUST BE EXCLUDED)
        Transaction t5 = createTx("TRANSFERS", TransactionType.EXPENSE, BigDecimal.valueOf(10000), LocalDate.of(2026, 10, 15));
        // 6. Non-spending: CARD_PAYMENTS debit ₹5,000 (MUST BE EXCLUDED)
        Transaction t6 = createTx("CARD_PAYMENTS", TransactionType.EXPENSE, BigDecimal.valueOf(5000), LocalDate.of(2026, 10, 20));

        when(transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc("usr-100"))
                .thenReturn(List.of(t1, t2, t3, t4, t5, t6));

        MonthlyBudgetOverviewResponse overview = budgetService.getMonthlyOverview(testUser, "2026-10");

        // Overall expenses = 4500 (dining) + 9000 (groceries) + 1500 (entertainment) - 500 (refund) = 14,500
        assertThat(overview.totalActualExpenses()).isEqualByComparingTo("14500.00");
        assertThat(overview.overallBudget()).isNotNull();
        assertThat(overview.overallBudget().budgetedAmount()).isEqualByComparingTo("20000.00");
        assertThat(overview.overallBudget().remainingAmount()).isEqualByComparingTo("5500.00");
        assertThat(overview.overallBudget().percentageUsed()).isEqualByComparingTo("72.50"); // 14500 / 20000 = 72.5%
        assertThat(overview.overallBudget().status()).isEqualTo("UNDER_BUDGET");

        // Category budgets:
        // DINING: net 4,000 / 5,000 = 80.00% -> APPROACHING_LIMIT
        CategoryBudgetPerformanceDto diningPerf = overview.categoryBudgets().stream()
                .filter(b -> "DINING".equals(b.category())).findFirst().orElseThrow();
        assertThat(diningPerf.actualSpend()).isEqualByComparingTo("4000.00");
        assertThat(diningPerf.remainingAmount()).isEqualByComparingTo("1000.00");
        assertThat(diningPerf.percentageUsed()).isEqualByComparingTo("80.00");
        assertThat(diningPerf.status()).isEqualTo("APPROACHING_LIMIT");

        // GROCERIES: net 9,000 / 8,000 = 112.50% -> OVER_BUDGET
        CategoryBudgetPerformanceDto grocPerf = overview.categoryBudgets().stream()
                .filter(b -> "GROCERIES".equals(b.category())).findFirst().orElseThrow();
        assertThat(grocPerf.actualSpend()).isEqualByComparingTo("9000.00");
        assertThat(grocPerf.remainingAmount()).isEqualByComparingTo("-1000.00");
        assertThat(grocPerf.percentageUsed()).isEqualByComparingTo("112.50");
        assertThat(grocPerf.status()).isEqualTo("OVER_BUDGET");

        // Unbudgeted spending = ₹1,500 (entertainment)
        assertThat(overview.unbudgetedSpending()).isEqualByComparingTo("1500.00");
        assertThat(overview.categoriesOverBudgetCount()).isEqualTo(1);
        assertThat(overview.categoriesApproachingCount()).isEqualTo(1);
    }

    private Transaction createTx(String category, TransactionType type, BigDecimal amount, LocalDate date) {
        Transaction t = new Transaction();
        t.setId("tx-" + System.nanoTime());
        t.setUser(testUser);
        t.setCategory(category);
        t.setType(type);
        t.setAmount(amount);
        t.setDate(date);
        return t;
    }
}
