package com.finguard.backend.intelligence.recommendations;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * Thresholds of the recommendation rules. Every value can be overridden with {@code recommendations.*}
 * (for example {@code RECOMMENDATIONS_MIN_SAVINGS_RATE_PERCENT=25}); the defaults are listed in
 * docs/FINANCIAL_RULES.md section 9. They are general-guidance heuristics, not personal advice.
 *
 * <p>All comparisons are strict ("below" / "above"): a value exactly at the threshold does not trigger a rule.
 */
@ConfigurationProperties(prefix = "recommendations")
public record RecommendationProperties(
        /* Complete calendar months before the current month that every rule looks at. */
        @DefaultValue("3") int periodMonths,
        /* Months with recorded transactions needed before the period-based rules run. */
        @DefaultValue("3") int minMonths,
        /* Savings rate (percent of income) below which LOW_SAVINGS_RATE fires. */
        @DefaultValue("20") BigDecimal minSavingsRatePercent,
        /* Savings rate below which the item is HIGH instead of MEDIUM; below zero it is CRITICAL. */
        @DefaultValue("10") BigDecimal highSavingsRiskBelowPercent,
        /* Share of average monthly income taken by loan EMIs above which HIGH_EMI_BURDEN fires. */
        @DefaultValue("40") BigDecimal maxEmiSharePercent,
        /* EMI share above which the item is CRITICAL. */
        @DefaultValue("60") BigDecimal criticalEmiSharePercent,
        /* Share of the portfolio in one asset class above which INVESTMENT_CONCENTRATION fires. */
        @DefaultValue("70") BigDecimal maxInvestmentClassSharePercent,
        /* Rise in monthly spending, first to last month of a strictly rising run, at or above which RISING_SPEND fires. */
        @DefaultValue("10") BigDecimal risingSpendMinIncreasePercent,
        /* Share of transactions without a category above which UNCATEGORISED_SHARE fires. */
        @DefaultValue("20") BigDecimal maxUncategorisedPercent,
        /* Transactions needed before the uncategorised share is judged. */
        @DefaultValue("10") int uncategorisedMinTransactions,
        /* Number of active subscription-type recurring payments from which a review is suggested. */
        @DefaultValue("3") int subscriptionReviewCount,
        /* Preset size for "try this scenario": percentage cut applied to the largest discretionary category. */
        @DefaultValue("10") BigDecimal presetCutPercent
) {
}
