package com.finguard.backend.budgeting.web;

import com.finguard.backend.budgeting.dto.*;
import com.finguard.backend.budgeting.service.BudgetService;
import com.finguard.backend.identity.domain.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

@RestController
@RequestMapping("/api/budgets")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetService budgetService;
    private final Clock clock;

    @PostMapping
    public ResponseEntity<BudgetDto> createBudget(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateBudgetRequest request
    ) {
        BudgetDto created = budgetService.createBudget(user, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<BudgetDto>> listBudgets(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) String month
    ) {
        return ResponseEntity.ok(budgetService.listBudgets(user, month));
    }

    @GetMapping("/overview")
    public ResponseEntity<MonthlyBudgetOverviewResponse> getMonthlyOverview(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) String yearMonth,
            @RequestParam(required = false) String month
    ) {
        // docs/API.md documents `yearMonth` (as the forecasting endpoint does); `month` is the older name the frontend
        // sends, kept as an alias. Before, `yearMonth` was silently ignored and the current month came back.
        String requested = (yearMonth != null && !yearMonth.isBlank()) ? yearMonth : month;
        String targetMonth = (requested != null && !requested.isBlank()) ? requested : YearMonth.now(clock).toString();
        return ResponseEntity.ok(budgetService.getMonthlyOverview(user, targetMonth));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BudgetDto> updateBudget(
            @AuthenticationPrincipal User user,
            @PathVariable String id,
            @Valid @RequestBody UpdateBudgetRequest request
    ) {
        return ResponseEntity.ok(budgetService.updateBudget(user, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBudget(
            @AuthenticationPrincipal User user,
            @PathVariable String id
    ) {
        budgetService.deleteBudget(user, id);
        return ResponseEntity.noContent().build();
    }
}
