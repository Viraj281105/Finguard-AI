package com.finguard.backend.intelligence.recommendations;

import com.finguard.backend.identity.domain.User;
import com.finguard.backend.intelligence.dto.CategorySpendingDto;
import com.finguard.backend.intelligence.dto.DataQualityResponse;
import com.finguard.backend.intelligence.dto.FinancialSummaryResponse;
import com.finguard.backend.intelligence.dto.MonthlyFlowDto;
import com.finguard.backend.intelligence.recommendations.RecommendationEngine.Inputs;
import com.finguard.backend.intelligence.recommendations.RecommendationEngine.MonthSpend;
import com.finguard.backend.intelligence.recommendations.RecommendationsResponse.Period;
import com.finguard.backend.intelligence.service.AnalyticsService;
import com.finguard.backend.ledger.investments.Investment;
import com.finguard.backend.ledger.investments.InvestmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gathers the figures for the last complete months from the analytics module - the same calls, with the same
 * {@code from}/{@code to}, that the analytics endpoints make, so every piece of evidence can be reproduced - and hands
 * them to the rule engine. Nothing is stored; the result is calculated per request for the caller only.
 */
@Service
@RequiredArgsConstructor
public class RecommendationService {

    /** Spending categories that are choices rather than commitments; used only to pick a "try this" preset. */
    private static final Set<String> DISCRETIONARY = Set.of("DINING", "SHOPPING", "ENTERTAINMENT", "SUBSCRIPTIONS");

    private final AnalyticsService analyticsService;
    private final InvestmentRepository investmentRepository;
    private final RecommendationEngine engine;
    private final RecommendationProperties props;
    private final Clock clock;

    @Transactional(readOnly = true)
    public RecommendationsResponse getRecommendations(User user) {
        YearMonth current = YearMonth.now(clock);
        LocalDate from = current.minusMonths(props.periodMonths()).atDay(1);
        LocalDate to = current.minusMonths(1).atEndOfMonth();

        FinancialSummaryResponse summary = analyticsService.getSummary(user, from, to);
        List<MonthlyFlowDto> monthly = analyticsService.getMonthlyFlows(user, from, to);
        DataQualityResponse quality = analyticsService.getDataQuality(user, from, to);
        var recurring = analyticsService.getRecurringExpenses(user, from, to).items();

        String top = analyticsService.getCategoryBreakdown(user, from, to).categories().stream()
                .filter(c -> "EXPENSE".equalsIgnoreCase(c.flow()) && DISCRETIONARY.contains(c.category()))
                .max(Comparator.comparing(CategorySpendingDto::amount))
                .map(CategorySpendingDto::category)
                .orElse(null);

        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        for (Investment i : investmentRepository.findByUserIdOrderByCreatedAtDesc(user.getId())) {
            BigDecimal value = i.getCurrentValue() != null ? i.getCurrentValue() : i.getAmountInvested();
            byType.merge(i.getType().name(), value, BigDecimal::add);
        }

        Inputs inputs = new Inputs(from, to, monthly.size(), summary.totalIncome(), summary.totalExpenses(),
                summary.savingsRateApplicable() ? summary.savingsRate() : null, summary.activeLoansCount(), summary.totalMonthlyEmi(),
                monthly.stream().map(m -> new MonthSpend(YearMonth.parse(m.month()), m.expenses())).toList(),
                quality.totalTransactions(), quality.uncategorizedPercentage(), quality.uncategorizedCount(),
                recurring, byType, top);

        RecommendationEngine.Result result = engine.evaluate(inputs);
        boolean enoughData = monthly.size() >= props.minMonths();
        boolean healthy = enoughData && result.recommendations().stream().noneMatch(r -> r.severity() != RecommendationsResponse.Severity.INFO);

        Map<String, BigDecimal> thresholds = new LinkedHashMap<>();
        thresholds.put("minSavingsRatePercent", props.minSavingsRatePercent());
        thresholds.put("maxEmiSharePercent", props.maxEmiSharePercent());
        thresholds.put("maxInvestmentClassSharePercent", props.maxInvestmentClassSharePercent());
        thresholds.put("risingSpendMinIncreasePercent", props.risingSpendMinIncreasePercent());
        thresholds.put("maxUncategorisedPercent", props.maxUncategorisedPercent());
        thresholds.put("subscriptionReviewCount", BigDecimal.valueOf(props.subscriptionReviewCount()));

        return new RecommendationsResponse(new Period(from, to, props.periodMonths(), monthly.size()), RecommendationsResponse.DISCLAIMER,
                healthy, result.recommendations(), result.notEvaluated(), thresholds);
    }
}
