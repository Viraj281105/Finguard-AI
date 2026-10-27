package com.finguard.backend.intelligence.recommendations;

import com.finguard.backend.intelligence.dto.RecurringExpenseItemDto;
import com.finguard.backend.intelligence.recommendations.RecommendationsResponse.Evidence;
import com.finguard.backend.intelligence.recommendations.RecommendationsResponse.NotEvaluated;
import com.finguard.backend.intelligence.recommendations.RecommendationsResponse.Recommendation;
import com.finguard.backend.intelligence.recommendations.RecommendationsResponse.ScenarioPreset;
import com.finguard.backend.intelligence.recommendations.RecommendationsResponse.Severity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The rule set. Pure: it receives figures that were already computed by the analytics module for one period and
 * returns recommendations, reading and writing nothing. A rule never runs on missing input; it is reported in
 * {@code notEvaluated} instead. Threshold comparisons are strict (a value exactly at the threshold does not fire), except the
 * rising-spend rule, which fires at or above its minimum rise.
 */
@Component
public class RecommendationEngine {

    public static final String LOW_SAVINGS_RATE = "LOW_SAVINGS_RATE";
    public static final String HIGH_EMI_BURDEN = "HIGH_EMI_BURDEN";
    public static final String INVESTMENT_CONCENTRATION = "INVESTMENT_CONCENTRATION";
    public static final String RISING_SPEND = "RISING_SPEND";
    public static final String UNCATEGORISED_SHARE = "UNCATEGORISED_SHARE";
    public static final String SUBSCRIPTION_REVIEW = "SUBSCRIPTION_REVIEW";
    public static final String EMERGENCY_FUND = "EMERGENCY_FUND";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final RecommendationProperties props;

    public RecommendationEngine(RecommendationProperties props) {
        this.props = props;
    }

    /** Net spending of one month with recorded transactions. */
    public record MonthSpend(YearMonth month, BigDecimal expenses) {
    }

    /**
     * Everything the rules need. {@code savingsRate} is null when no income was recorded; {@code monthlyEmi} is the sum of the
     * EMIs of active loans; {@code investmentValueByType} uses current value (or invested amount when no value is recorded).
     */
    public record Inputs(
            LocalDate from,
            LocalDate to,
            int monthsWithData,
            BigDecimal totalIncome,
            BigDecimal totalExpenses,
            BigDecimal savingsRate,
            int activeLoans,
            BigDecimal monthlyEmi,
            List<MonthSpend> monthlySpend,
            long totalTransactions,
            BigDecimal uncategorisedPercentage,
            long uncategorisedCount,
            List<RecurringExpenseItemDto> recurring,
            Map<String, BigDecimal> investmentValueByType,
            String topDiscretionaryCategory
    ) {
    }

    public record Result(List<Recommendation> recommendations, List<NotEvaluated> notEvaluated) {
    }

    public Result evaluate(Inputs in) {
        List<Recommendation> out = new ArrayList<>();
        List<NotEvaluated> skipped = new ArrayList<>();
        String q = "?from=" + in.from() + "&to=" + in.to();

        savingsRate(in, q, out, skipped);
        emiBurden(in, q, out, skipped);
        investmentConcentration(in, out);
        risingSpend(in, q, out, skipped);
        uncategorised(in, out, skipped);
        subscriptions(in, q, out);
        skipped.add(new NotEvaluated(EMERGENCY_FUND, "Emergency fund below three months of spending",
                "This needs your actual savings balance. FinGuard holds no account balances and goal contributions are self-tracked, "
                        + "so it cannot tell whether you have three months set aside."));

        out.sort(Comparator.<Recommendation>comparingInt(r -> r.severity().ordinal())
                .thenComparingInt(r -> r.estimatedMonthlyImpact() == null ? 1 : 0)
                .thenComparing(r -> r.estimatedMonthlyImpact() == null ? BigDecimal.ZERO : r.estimatedMonthlyImpact().negate())
                .thenComparing(Recommendation::id));
        return new Result(List.copyOf(out), List.copyOf(skipped));
    }

    // ---- LOW_SAVINGS_RATE -------------------------------------------------------------------------------------

    private void savingsRate(Inputs in, String q, List<Recommendation> out, List<NotEvaluated> skipped) {
        String title = "Savings rate below " + plain(props.minSavingsRatePercent()) + "%";
        if (in.monthsWithData() < props.minMonths()) {
            skipped.add(new NotEvaluated(LOW_SAVINGS_RATE, title, needMonths(in)));
            return;
        }
        if (in.savingsRate() == null) {
            skipped.add(new NotEvaluated(LOW_SAVINGS_RATE, title, "No income was recorded in this period, so a savings rate cannot be computed."));
            return;
        }
        if (in.savingsRate().compareTo(props.minSavingsRatePercent()) >= 0) {
            return;
        }
        Severity severity = in.savingsRate().signum() < 0 ? Severity.CRITICAL
                : in.savingsRate().compareTo(props.highSavingsRiskBelowPercent()) < 0 ? Severity.HIGH : Severity.MEDIUM;
        BigDecimal monthlyIncome = perMonth(in.totalIncome(), in.monthsWithData());
        BigDecimal impact = props.minSavingsRatePercent().subtract(in.savingsRate()).multiply(monthlyIncome).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        String src = "/api/analytics/summary" + q;
        out.add(new Recommendation(LOW_SAVINGS_RATE, severity, title,
                "Over the last " + in.monthsWithData() + " months you kept " + plain(in.savingsRate()) + "% of recorded income"
                        + (in.savingsRate().signum() < 0 ? " (you spent more than you recorded earning)" : "")
                        + ". A common rule of thumb is to keep at least " + plain(props.minSavingsRatePercent()) + "%.",
                "Look at your largest flexible categories first; reaching " + plain(props.minSavingsRatePercent()) + "% would mean keeping about ₹"
                        + impact.toPlainString() + " more a month.",
                List.of(ev("Savings rate", in.savingsRate(), "%", src), ev("Total income", in.totalIncome(), "₹", src),
                        ev("Total spending", in.totalExpenses(), "₹", src), ev("Months with records", BigDecimal.valueOf(in.monthsWithData()), "months", "/api/analytics/data-quality")),
                impact, preset(in, "Cut " + label(in.topDiscretionaryCategory()) + " by " + plain(props.presetCutPercent()) + "%")));
    }

    // ---- HIGH_EMI_BURDEN --------------------------------------------------------------------------------------

    private void emiBurden(Inputs in, String q, List<Recommendation> out, List<NotEvaluated> skipped) {
        if (in.activeLoans() == 0) {
            return; // nothing to evaluate
        }
        String title = "Loan EMIs above " + plain(props.maxEmiSharePercent()) + "% of income";
        BigDecimal monthlyIncome = perMonth(in.totalIncome(), in.monthsWithData());
        if (in.monthsWithData() < props.minMonths()) {
            skipped.add(new NotEvaluated(HIGH_EMI_BURDEN, title, needMonths(in)));
            return;
        }
        if (monthlyIncome.signum() <= 0) {
            skipped.add(new NotEvaluated(HIGH_EMI_BURDEN, title, "No income was recorded in this period, so the EMI share of income cannot be computed."));
            return;
        }
        BigDecimal share = in.monthlyEmi().multiply(HUNDRED).divide(monthlyIncome, 2, RoundingMode.HALF_UP);
        if (share.compareTo(props.maxEmiSharePercent()) <= 0) {
            return;
        }
        Severity severity = share.compareTo(props.criticalEmiSharePercent()) > 0 ? Severity.CRITICAL : Severity.HIGH;
        BigDecimal excess = in.monthlyEmi().subtract(props.maxEmiSharePercent().multiply(monthlyIncome).divide(HUNDRED, 2, RoundingMode.HALF_UP));
        String src = "/api/analytics/summary" + q;
        out.add(new Recommendation(HIGH_EMI_BURDEN, severity, title,
                "Your active loan EMIs add up to ₹" + in.monthlyEmi().toPlainString() + " a month, " + plain(share)
                        + "% of your average recorded monthly income of ₹" + monthlyIncome.toPlainString() + ".",
                "Lenders often look for EMIs below about " + plain(props.maxEmiSharePercent()) + "% of income. Try a prepayment or EMI change in the scenario simulator to see the effect "
                        + "(about ₹" + excess.toPlainString() + " a month above that level).",
                List.of(ev("Total monthly EMI", in.monthlyEmi(), "₹", src), ev("Average monthly income", monthlyIncome, "₹", src),
                        ev("EMI share of income", share, "%", src), ev("Active loans", BigDecimal.valueOf(in.activeLoans()), "loans", "/api/loans")),
                excess, null));
    }

    // ---- INVESTMENT_CONCENTRATION ----------------------------------------------------------------------------

    private void investmentConcentration(Inputs in, List<Recommendation> out) {
        Map<String, BigDecimal> byType = in.investmentValueByType();
        BigDecimal total = byType.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            return; // no investments recorded
        }
        Map.Entry<String, BigDecimal> top = byType.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        BigDecimal share = top.getValue().multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
        if (share.compareTo(props.maxInvestmentClassSharePercent()) <= 0) {
            return;
        }
        String cls = label(top.getKey());
        out.add(new Recommendation(INVESTMENT_CONCENTRATION, Severity.MEDIUM, "One investment type is above " + plain(props.maxInvestmentClassSharePercent()) + "% of your portfolio",
                plain(share) + "% of the ₹" + total.toPlainString() + " you have recorded in investments is in " + cls + ".",
                "Spreading across asset types can reduce the effect of one market falling. Whether and how to change this depends on your goals and risk tolerance.",
                List.of(ev(cls + " value", top.getValue(), "₹", "/api/investments"), ev("Total portfolio value", total, "₹", "/api/investments"),
                        ev(cls + " share", share, "%", "/api/investments")),
                null, null));
    }

    // ---- RISING_SPEND -----------------------------------------------------------------------------------------

    private void risingSpend(Inputs in, String q, List<Recommendation> out, List<NotEvaluated> skipped) {
        String title = "Spending has risen three months in a row";
        List<MonthSpend> sorted = in.monthlySpend().stream().sorted(Comparator.comparing(MonthSpend::month)).toList();
        if (sorted.size() < 3 || in.monthsWithData() < props.minMonths()) {
            skipped.add(new NotEvaluated(RISING_SPEND, title, needMonths(in)));
            return;
        }
        List<MonthSpend> last = sorted.subList(sorted.size() - 3, sorted.size());
        if (!last.get(0).month().plusMonths(1).equals(last.get(1).month()) || !last.get(1).month().plusMonths(1).equals(last.get(2).month())) {
            skipped.add(new NotEvaluated(RISING_SPEND, title, "The last three months with records are not consecutive, so a trend cannot be judged."));
            return;
        }
        BigDecimal a = last.get(0).expenses();
        BigDecimal b = last.get(1).expenses();
        BigDecimal c = last.get(2).expenses();
        if (a.signum() <= 0 || b.compareTo(a) <= 0 || c.compareTo(b) <= 0) {
            return;
        }
        BigDecimal rise = c.subtract(a).multiply(HUNDRED).divide(a, 2, RoundingMode.HALF_UP);
        if (rise.compareTo(props.risingSpendMinIncreasePercent()) < 0) {
            return;
        }
        String src = "/api/analytics/monthly" + q;
        out.add(new Recommendation(RISING_SPEND, Severity.MEDIUM, title,
                "Monthly spending went from ₹" + a.toPlainString() + " to ₹" + b.toPlainString() + " to ₹" + c.toPlainString() + " ("
                        + last.get(0).month() + " to " + last.get(2).month() + "), up " + plain(rise) + "%.",
                "Check which categories grew; a one-off purchase can explain a rise, a habit may not.",
                List.of(ev("Spending " + last.get(0).month(), a, "₹", src), ev("Spending " + last.get(1).month(), b, "₹", src),
                        ev("Spending " + last.get(2).month(), c, "₹", src), ev("Rise over the run", rise, "%", src)),
                c.subtract(a), preset(in, "Cut " + label(in.topDiscretionaryCategory()) + " by " + plain(props.presetCutPercent()) + "%")));
    }

    // ---- UNCATEGORISED_SHARE (data quality) ----------------------------------------------------------------

    private void uncategorised(Inputs in, List<Recommendation> out, List<NotEvaluated> skipped) {
        String title = "Many transactions have no category";
        if (in.totalTransactions() < props.uncategorisedMinTransactions()) {
            skipped.add(new NotEvaluated(UNCATEGORISED_SHARE, title,
                    "Needs at least " + props.uncategorisedMinTransactions() + " transactions; found " + in.totalTransactions() + "."));
            return;
        }
        if (in.uncategorisedPercentage().compareTo(props.maxUncategorisedPercent()) <= 0) {
            return;
        }
        out.add(new Recommendation(UNCATEGORISED_SHARE, Severity.INFO, title,
                plain(in.uncategorisedPercentage()) + "% of your " + in.totalTransactions() + " transactions have no category, so spending breakdowns and the rules above may be incomplete.",
                "Open Transactions and categorise the uncategorised ones; the categoriser suggests categories you can accept.",
                List.of(ev("Uncategorised transactions", BigDecimal.valueOf(in.uncategorisedCount()), "transactions", "/api/analytics/data-quality"),
                        ev("Uncategorised share", in.uncategorisedPercentage(), "%", "/api/analytics/data-quality")),
                null, null));
    }

    // ---- SUBSCRIPTION_REVIEW ----------------------------------------------------------------------------------

    private void subscriptions(Inputs in, String q, List<Recommendation> out) {
        List<RecurringExpenseItemDto> subs = in.recurring().stream().filter(r -> "SUBSCRIPTIONS".equalsIgnoreCase(r.category())).toList();
        Map<String, Long> byMerchant = subs.stream().filter(r -> r.merchant() != null && !r.merchant().isBlank())
                .collect(Collectors.groupingBy(r -> r.merchant().trim().toLowerCase(), Collectors.counting()));
        long duplicateGroups = byMerchant.values().stream().filter(n -> n >= 2).count();
        if (duplicateGroups == 0 && subs.size() < props.subscriptionReviewCount()) {
            return;
        }
        BigDecimal monthly = subs.stream().map(RecurringExpenseItemDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        String src = "/api/analytics/recurring" + q;
        out.add(new Recommendation(SUBSCRIPTION_REVIEW, Severity.INFO, duplicateGroups > 0 ? "Possible duplicate subscriptions" : "Several recurring subscriptions",
                (duplicateGroups > 0 ? "The same merchant appears in more than one recurring subscription. " : "")
                        + "You have " + subs.size() + " detected recurring subscription payments costing about ₹" + monthly.toPlainString() + " a month.",
                "Check that you still use each one" + (duplicateGroups > 0 ? " and whether any of the duplicates is a mistake" : "") + ". You can stop a payment in the scenario simulator to see the effect.",
                List.of(ev("Recurring subscriptions", BigDecimal.valueOf(subs.size()), "payments", src), ev("Subscription cost per month", monthly, "₹", src),
                        ev("Merchants with more than one", BigDecimal.valueOf(duplicateGroups), "merchants", src)),
                null, null));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private ScenarioPreset preset(Inputs in, String label) {
        if (in.topDiscretionaryCategory() == null) {
            return null;
        }
        return new ScenarioPreset(label, Map.of("horizonMonths", 6, "categoryAdjustments",
                List.of(Map.of("category", in.topDiscretionaryCategory(), "type", "PERCENTAGE", "value", props.presetCutPercent().negate()))));
    }

    private String needMonths(Inputs in) {
        return "Needs records in at least " + props.minMonths() + " of the last " + props.periodMonths() + " complete months; found " + in.monthsWithData() + ".";
    }

    private static BigDecimal perMonth(BigDecimal total, int months) {
        return months <= 0 ? BigDecimal.ZERO : total.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
    }

    private static Evidence ev(String metric, BigDecimal value, String unit, String source) {
        return new Evidence(metric, value, unit, source);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    static String label(String id) {
        if (id == null) {
            return "";
        }
        String s = id.toLowerCase().replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
