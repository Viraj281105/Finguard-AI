package com.finguard.backend.forecasting.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Baseline versus hypothetical alternative. {@code hypothetical} is always {@code true}; calculating a
 * scenario reads the ledger and never writes anything. When the baseline forecast is unavailable the
 * response says so ({@code available = false}) and carries no numbers - a scenario on top of a missing
 * baseline would be an invention. Scenario figures are point estimates: no uncertainty band is given.
 */
public record ScenarioSimulationResponse(
        String name,
        boolean hypothetical,
        String disclaimer,
        boolean available,
        String unavailableReason,
        String baselineReliability,
        int horizonMonths,
        boolean incomeAvailable,
        List<String> warnings,
        Totals baseline,
        Totals scenario,
        Delta delta,
        List<Month> months,
        List<CategoryEffect> categories,
        List<String> explanations,
        List<LoanEffect> loanEffects
) {

    /** What a loan adjustment does, from two deterministic runs of the same loan (before and after). */
    public record LoanEffect(
            String loanId,
            String loanName,
            String action,
            BigDecimal estimatedOutstandingBalance,
            int baselineMonthsToPayoff,
            int scenarioMonthsToPayoff,
            BigDecimal baselineRemainingInterest,
            BigDecimal scenarioRemainingInterest,
            BigDecimal interestSaved,
            BigDecimal cashOutInHorizonChange,
            List<String> notes
    ) {
    }

    /** Totals over the horizon; income, net and the savings rate are null when no income was observed. */
    public record Totals(BigDecimal income, BigDecimal expenses, BigDecimal net, BigDecimal savingsRatePercent) {
    }

    public record Delta(BigDecimal income, BigDecimal expenses, BigDecimal net, BigDecimal savingsRatePoints) {
    }

    public record Month(
            String month,
            BigDecimal baselineIncome,
            BigDecimal scenarioIncome,
            BigDecimal baselineExpenses,
            BigDecimal scenarioExpenses,
            BigDecimal baselineNet,
            BigDecimal scenarioNet
    ) {
    }

    public record CategoryEffect(
            String category,
            String label,
            BigDecimal baselineMonthly,
            BigDecimal scenarioMonthly,
            BigDecimal deltaMonthly,
            String note
    ) {
    }
}
