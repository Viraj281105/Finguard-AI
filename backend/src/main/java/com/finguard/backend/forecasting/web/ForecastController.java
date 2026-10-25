package com.finguard.backend.forecasting.web;

import com.finguard.backend.forecasting.dto.*;
import com.finguard.backend.forecasting.service.ForecastingService;
import com.finguard.backend.identity.domain.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/forecast")
@RequiredArgsConstructor
public class ForecastController {

    private final ForecastingService forecastingService;

    @GetMapping("/cash-flow")
    public ResponseEntity<CashFlowForecastResponse> getCashFlowForecast(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "3") int horizonMonths
    ) {
        return ResponseEntity.ok(forecastingService.getCashFlowForecast(user, horizonMonths));
    }

    @GetMapping("/categories")
    public ResponseEntity<CategorySpendingForecastResponse> getCategoryForecast(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "3") int horizonMonths
    ) {
        return ResponseEntity.ok(forecastingService.getCategoryForecast(user, horizonMonths));
    }

    @GetMapping("/budget-overruns")
    public ResponseEntity<BudgetOverrunRiskResponse> getBudgetOverrunRisk(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) String yearMonth
    ) {
        return ResponseEntity.ok(forecastingService.getBudgetOverrunRisk(user, yearMonth));
    }

    @GetMapping("/savings")
    public ResponseEntity<SavingsProjectionResponse> getSavingsProjections(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "6") int horizonMonths
    ) {
        return ResponseEntity.ok(forecastingService.getSavingsProjections(user, horizonMonths));
    }

    @PostMapping("/scenarios/simulate")
    public ResponseEntity<ScenarioSimulationResponse> simulateScenario(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody ScenarioSimulationRequest request
    ) {
        return ResponseEntity.ok(forecastingService.simulateScenario(user, request));
    }

    @GetMapping("/backtest")
    public ResponseEntity<BacktestSummaryResponse> getBacktestSummary(
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(forecastingService.getBacktestSummary(user));
    }
}
