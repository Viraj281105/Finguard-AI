package com.finguard.backend.budgeting.service;

import com.finguard.backend.budgeting.domain.Budget;
import com.finguard.backend.budgeting.domain.BudgetRepository;
import com.finguard.backend.budgeting.dto.*;
import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import com.finguard.backend.ledger.transactions.TransactionCategory;
import com.finguard.backend.ledger.transactions.TransactionCategory.Flow;
import com.finguard.backend.ledger.transactions.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BudgetService {

    private static final Pattern YEAR_MONTH_PATTERN = Pattern.compile("^[0-9]{4}-(0[1-9]|1[0-2])$");

    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public BudgetService(BudgetRepository budgetRepository, TransactionRepository transactionRepository, Clock clock) {
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
        this.clock = clock;
    }

    public BudgetService(BudgetRepository budgetRepository, TransactionRepository transactionRepository) {
        this(budgetRepository, transactionRepository, Clock.system(java.time.ZoneId.of("Asia/Kolkata")));
    }

    @Transactional
    public BudgetDto createBudget(User user, CreateBudgetRequest request) {
        validateYearMonth(request.yearMonth());

        String categoryKey = normalizeAndValidateCategory(request.category());

        // Check for duplicate budget
        if (categoryKey != null) {
            if (budgetRepository.findByUserIdAndYearMonthAndCategory(user.getId(), request.yearMonth(), categoryKey).isPresent()) {
                throw ApiException.conflict("A budget for category " + categoryKey + " already exists for " + request.yearMonth());
            }
        } else {
            if (budgetRepository.findByUserIdAndYearMonthAndCategoryIsNull(user.getId(), request.yearMonth()).isPresent()) {
                throw ApiException.conflict("An overall monthly budget already exists for " + request.yearMonth());
            }
        }

        Budget budget = new Budget();
        budget.setUser(user);
        budget.setYearMonth(request.yearMonth());
        budget.setCategory(categoryKey);
        budget.setAmount(request.amount().setScale(2, RoundingMode.HALF_UP));

        Budget saved = budgetRepository.save(budget);
        return mapToDto(saved);
    }

    @Transactional
    public BudgetDto updateBudget(User user, String id, UpdateBudgetRequest request) {
        Budget budget = budgetRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> ApiException.notFound("Budget not found: " + id));

        budget.setAmount(request.amount().setScale(2, RoundingMode.HALF_UP));
        Budget updated = budgetRepository.save(budget);
        return mapToDto(updated);
    }

    @Transactional
    public void deleteBudget(User user, String id) {
        Budget budget = budgetRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> ApiException.notFound("Budget not found: " + id));
        budgetRepository.delete(budget);
    }

    @Transactional(readOnly = true)
    public List<BudgetDto> listBudgets(User user, String yearMonth) {
        List<Budget> budgets;
        if (yearMonth != null && !yearMonth.isBlank()) {
            validateYearMonth(yearMonth);
            budgets = budgetRepository.findByUserIdAndYearMonthOrderByCategoryAsc(user.getId(), yearMonth);
        } else {
            budgets = budgetRepository.findByUserIdOrderByYearMonthDescCategoryAsc(user.getId());
        }
        return budgets.stream().map(this::mapToDto).toList();
    }

    @Transactional(readOnly = true)
    public MonthlyBudgetOverviewResponse getMonthlyOverview(User user, String yearMonth) {
        validateYearMonth(yearMonth);
        YearMonth ym = YearMonth.parse(yearMonth);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        // 1. Fetch user transactions for this month
        List<Transaction> allUserTx = transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc(user.getId());
        List<Transaction> monthTx = allUserTx.stream()
                .filter(t -> !t.getDate().isBefore(start) && !t.getDate().isAfter(end))
                .toList();

        // 2. Fetch budgets for this month
        List<Budget> userBudgets = budgetRepository.findByUserIdAndYearMonthOrderByCategoryAsc(user.getId(), yearMonth);

        // 3. Calculate category spending and overall spending
        Map<String, CategorySpendAccumulator> categorySpends = new HashMap<>();
        BigDecimal totalGrossExpenses = BigDecimal.ZERO;
        BigDecimal totalRefunds = BigDecimal.ZERO;

        for (Transaction t : monthTx) {
            TransactionCategory cat = parseCategory(t.getCategory());
            Flow flow = cat != null ? cat.flow() : Flow.UNKNOWN;

            // An unreviewed automatic category must not be treated as NON_SPENDING or REFUND
            if (t.isCategoryNeedsReview()) {
                if (flow == Flow.NON_SPENDING || flow == Flow.REFUND) {
                    flow = Flow.UNKNOWN;
                }
            }

            // Exclude non-spending (TRANSFERS, CARD_PAYMENTS, INVESTMENTS)
            if (flow == Flow.NON_SPENDING) {
                continue;
            }

            String catKey = cat != null ? cat.name() : (t.getCategory() != null ? t.getCategory().toUpperCase(Locale.ROOT) : "UNCATEGORIZED");

            if (t.getType() == TransactionType.EXPENSE) {
                if (flow == Flow.EXPENSE || flow == Flow.UNKNOWN) {
                    totalGrossExpenses = totalGrossExpenses.add(t.getAmount());
                    categorySpends.computeIfAbsent(catKey, k -> new CategorySpendAccumulator())
                            .addDebit(t.getAmount());
                }
            } else if (t.getType() == TransactionType.INCOME) {
                if (flow == Flow.REFUND || flow == Flow.EXPENSE) {
                    totalRefunds = totalRefunds.add(t.getAmount());
                    if (cat != TransactionCategory.REFUNDS) {
                        categorySpends.computeIfAbsent(catKey, k -> new CategorySpendAccumulator())
                                .addCredit(t.getAmount());
                    }
                }
            }
        }

        BigDecimal totalActualExpenses = totalGrossExpenses.subtract(totalRefunds);
        if (totalActualExpenses.compareTo(BigDecimal.ZERO) < 0) {
            totalActualExpenses = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        } else {
            totalActualExpenses = totalActualExpenses.setScale(2, RoundingMode.HALF_UP);
        }

        // 4. Overall budget processing
        Budget overallBudgetEntity = userBudgets.stream()
                .filter(b -> b.getCategory() == null)
                .findFirst()
                .orElse(null);

        BudgetPerformanceSummaryDto overallBudgetDto = null;
        if (overallBudgetEntity != null) {
            BigDecimal budgeted = overallBudgetEntity.getAmount();
            BigDecimal remaining = budgeted.subtract(totalActualExpenses).setScale(2, RoundingMode.HALF_UP);
            BigDecimal percentage = budgeted.compareTo(BigDecimal.ZERO) > 0
                    ? totalActualExpenses.multiply(BigDecimal.valueOf(100)).divide(budgeted, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            String status = determineStatus(percentage);
            overallBudgetDto = new BudgetPerformanceSummaryDto(
                    overallBudgetEntity.getId(),
                    budgeted,
                    totalActualExpenses,
                    remaining,
                    percentage,
                    status
            );
        }

        // 5. Category budgets processing
        List<Budget> categoryBudgetEntities = userBudgets.stream()
                .filter(b -> b.getCategory() != null)
                .toList();

        List<CategoryBudgetPerformanceDto> categoryBudgetDtos = new ArrayList<>();
        Set<String> budgetedCategories = new HashSet<>();
        BigDecimal totalCategoryBudgeted = BigDecimal.ZERO;
        int overBudgetCount = 0;
        int approachingCount = 0;

        for (Budget b : categoryBudgetEntities) {
            String cat = b.getCategory();
            budgetedCategories.add(cat);
            BigDecimal budgeted = b.getAmount();
            totalCategoryBudgeted = totalCategoryBudgeted.add(budgeted);

            CategorySpendAccumulator accum = categorySpends.getOrDefault(cat, new CategorySpendAccumulator());
            BigDecimal actualSpend = accum.netSpend();
            BigDecimal remaining = budgeted.subtract(actualSpend).setScale(2, RoundingMode.HALF_UP);
            BigDecimal percentage = budgeted.compareTo(BigDecimal.ZERO) > 0
                    ? actualSpend.multiply(BigDecimal.valueOf(100)).divide(budgeted, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            String status = determineStatus(percentage);
            if ("OVER_BUDGET".equals(status)) {
                overBudgetCount++;
            } else if ("APPROACHING_LIMIT".equals(status)) {
                approachingCount++;
            }

            categoryBudgetDtos.add(new CategoryBudgetPerformanceDto(
                    b.getId(),
                    cat,
                    formatCategoryLabel(cat),
                    budgeted,
                    actualSpend,
                    remaining,
                    percentage,
                    status,
                    accum.transactionCount
            ));
        }

        // Sort category budgets: OVER_BUDGET first, then descending by amount
        categoryBudgetDtos.sort((a, b) -> {
            if (a.status().equals("OVER_BUDGET") && !b.status().equals("OVER_BUDGET")) return -1;
            if (!a.status().equals("OVER_BUDGET") && b.status().equals("OVER_BUDGET")) return 1;
            return b.budgetedAmount().compareTo(a.budgetedAmount());
        });

        // 6. Unbudgeted spending: sum of actual net spend in categories without a category budget
        BigDecimal unbudgetedSpending = BigDecimal.ZERO;
        for (Map.Entry<String, CategorySpendAccumulator> entry : categorySpends.entrySet()) {
            if (!budgetedCategories.contains(entry.getKey())) {
                unbudgetedSpending = unbudgetedSpending.add(entry.getValue().netSpend());
            }
        }
        unbudgetedSpending = unbudgetedSpending.setScale(2, RoundingMode.HALF_UP);
        totalCategoryBudgeted = totalCategoryBudgeted.setScale(2, RoundingMode.HALF_UP);

        // 7. Contextual warnings
        List<String> warnings = new ArrayList<>();
        YearMonth currentYm = YearMonth.now(clock);
        if (ym.isAfter(currentYm)) {
            warnings.add("Future budget period. Spending will be tracked as transactions are recorded for this month.");
        }
        if (overBudgetCount > 0) {
            warnings.add(overBudgetCount + (overBudgetCount == 1 ? " category has" : " categories have") + " exceeded the monthly limit.");
        }
        if (overallBudgetDto != null && "OVER_BUDGET".equals(overallBudgetDto.status())) {
            warnings.add("Total monthly spending has exceeded the overall budget cap.");
        }
        if (unbudgetedSpending.compareTo(BigDecimal.ZERO) > 0 && !categoryBudgetEntities.isEmpty()) {
            warnings.add("You have ₹" + unbudgetedSpending + " in unbudgeted category spending.");
        }

        return new MonthlyBudgetOverviewResponse(
                yearMonth,
                overallBudgetDto,
                categoryBudgetDtos,
                totalCategoryBudgeted,
                totalActualExpenses,
                unbudgetedSpending,
                overBudgetCount,
                approachingCount,
                warnings
        );
    }

    private String determineStatus(BigDecimal percentage) {
        if (percentage.compareTo(BigDecimal.valueOf(100)) > 0) {
            return "OVER_BUDGET";
        } else if (percentage.compareTo(BigDecimal.valueOf(80)) >= 0) {
            return "APPROACHING_LIMIT";
        } else {
            return "UNDER_BUDGET";
        }
    }

    private void validateYearMonth(String yearMonth) {
        if (yearMonth == null || !YEAR_MONTH_PATTERN.matcher(yearMonth).matches()) {
            throw ApiException.badRequest("Invalid yearMonth format: " + yearMonth + ". Expected YYYY-MM.");
        }
        try {
            YearMonth.parse(yearMonth);
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest("Invalid yearMonth: " + yearMonth);
        }
    }

    private String normalizeAndValidateCategory(String category) {
        if (category == null || category.isBlank()) {
            return null; // overall budget
        }

        Optional<TransactionCategory> parsed = TransactionCategory.parse(category);
        if (parsed.isEmpty()) {
            throw ApiException.badRequest("Invalid category: " + category);
        }

        TransactionCategory cat = parsed.get();
        if (cat.flow() == Flow.NON_SPENDING) {
            throw ApiException.badRequest("Non-spending category '" + cat.name() + "' cannot have a spending budget.");
        }
        if (cat.flow() == Flow.INCOME) {
            throw ApiException.badRequest("Income category cannot have a spending budget.");
        }
        if (cat.flow() == Flow.REFUND) {
            throw ApiException.badRequest("Refunds category cannot have a spending budget.");
        }

        return cat.name();
    }

    private TransactionCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return TransactionCategory.parse(raw).orElse(null);
    }

    private String formatCategoryLabel(String key) {
        if (key == null) return "Overall Budget";
        return Arrays.stream(key.split("_"))
                .map(word -> word.isEmpty() ? "" : Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
    }

    private BudgetDto mapToDto(Budget b) {
        return new BudgetDto(
                b.getId(),
                b.getYearMonth(),
                b.getCategory(),
                b.getAmount(),
                b.getCreatedAt(),
                b.getUpdatedAt()
        );
    }

    private static class CategorySpendAccumulator {
        private BigDecimal grossDebits = BigDecimal.ZERO;
        private BigDecimal refunds = BigDecimal.ZERO;
        private int transactionCount = 0;

        void addDebit(BigDecimal amount) {
            grossDebits = grossDebits.add(amount);
            transactionCount++;
        }

        void addCredit(BigDecimal amount) {
            refunds = refunds.add(amount);
            transactionCount++;
        }

        BigDecimal netSpend() {
            BigDecimal net = grossDebits.subtract(refunds);
            if (net.compareTo(BigDecimal.ZERO) < 0) {
                return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            return net.setScale(2, RoundingMode.HALF_UP);
        }
    }
}
