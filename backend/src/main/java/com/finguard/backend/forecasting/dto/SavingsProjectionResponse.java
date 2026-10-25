package com.finguard.backend.forecasting.dto;

import com.finguard.backend.forecasting.dto.ForecastCommon.Estimate;
import com.finguard.backend.forecasting.dto.ForecastCommon.HistoryInfo;

import java.math.BigDecimal;
import java.util.List;

/**
 * Projected cash-flow surplus. It is not a bank balance, and it is not savings anyone has verified:
 * the {@code accounting} block keeps the three notions apart, and {@code verifiedBankBalance} is always
 * {@code null} because the application does not hold account balances.
 */
public record SavingsProjectionResponse(
        String reliability,
        String method,
        String algorithmVersion,
        String asOf,
        int horizonMonths,
        HistoryInfo history,
        List<String> warnings,
        List<String> assumptions,
        String disclaimer,
        boolean incomeAvailable,
        BigDecimal averageMonthlyNet,
        BigDecimal projectedSavingsRatePercent,
        List<Point> points,
        List<GoalOutlook> goals,
        Accounting accounting
) {

    /** {@code cumulativeNet} is the running sum of point estimates; no band is given because errors do not add up simply. */
    public record Point(String month, Estimate net, BigDecimal cumulativeNet) {
    }

    /**
     * @param recordedGoalContributions sum of self-tracked goal contributions (planning figures, not deposits)
     * @param verifiedBankBalance       always null: not available
     * @param totalRequiredGoalPace     sum of the monthly pace every active dated goal needs
     * @param goalsOvercommitted        required pace of all goals together exceeds the projected monthly surplus
     */
    public record Accounting(
            BigDecimal projectedSurplusOverHorizon,
            BigDecimal recordedGoalContributions,
            BigDecimal verifiedBankBalance,
            String verifiedBankBalanceNote,
            BigDecimal totalRequiredGoalPace,
            Boolean goalsOvercommitted
    ) {
    }

    /**
     * @param recordedAmount           self-tracked progress, not verified savings
     * @param requiredMonthlyPace      remaining amount over the months to the target date; null without a target date
     * @param monthsIfAllSurplusUsed   months to finish if the whole projected surplus went to this goal alone; null when no surplus
     * @param onPaceIfAlone            whether the projected surplus alone would meet the required pace; null when unknowable
     */
    public record GoalOutlook(
            String id,
            String name,
            BigDecimal targetAmount,
            BigDecimal recordedAmount,
            BigDecimal remaining,
            String targetDate,
            BigDecimal requiredMonthlyPace,
            Integer monthsIfAllSurplusUsed,
            String completionMonthIfAllSurplusUsed,
            Boolean onPaceIfAlone
    ) {
    }
}
