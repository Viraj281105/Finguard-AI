package com.finguard.backend.intelligence.service;

import com.finguard.backend.intelligence.dto.UnusualTransactionDto;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import com.finguard.backend.ledger.transactions.TransactionCategory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Detects unusually large transactions using explainable statistical rules.
 */
@Component
public class UnusualTransactionDetector {

    private static final BigDecimal MIN_ANOMALY_THRESHOLD = BigDecimal.valueOf(1000);
    private static final BigDecimal RATIO_THRESHOLD = BigDecimal.valueOf(2.5);

    public List<UnusualTransactionDto> detectOutliers(List<Transaction> transactions) {
        List<Transaction> expenses = transactions.stream()
                .filter(t -> t.getType() == TransactionType.EXPENSE)
                .filter(t -> {
                    TransactionCategory cat = parseCategory(t.getCategory());
                    return cat == null || cat.flow() == TransactionCategory.Flow.EXPENSE || cat.flow() == TransactionCategory.Flow.UNKNOWN;
                })
                .sorted(Comparator.comparing(Transaction::getAmount))
                .toList();

        if (expenses.size() < 3) {
            return List.of();
        }

        // Calculate median
        BigDecimal median = calculateMedian(expenses);
        // Calculate mean and std dev
        double mean = expenses.stream()
                .mapToDouble(t -> t.getAmount().doubleValue())
                .average()
                .orElse(0.0);

        double variance = expenses.stream()
                .mapToDouble(t -> Math.pow(t.getAmount().doubleValue() - mean, 2))
                .average()
                .orElse(0.0);
        double stdDev = Math.sqrt(variance);

        List<UnusualTransactionDto> outliers = new ArrayList<>();

        for (Transaction t : expenses) {
            BigDecimal amount = t.getAmount();
            if (amount.compareTo(MIN_ANOMALY_THRESHOLD) < 0) {
                continue;
            }

            boolean isMedianOutlier = median.compareTo(BigDecimal.ZERO) > 0 &&
                    amount.divide(median, 4, RoundingMode.HALF_UP).compareTo(RATIO_THRESHOLD) >= 0;

            boolean isStdDevOutlier = stdDev > 0 &&
                    amount.doubleValue() > (mean + 2.0 * stdDev);

            if (isMedianOutlier || isStdDevOutlier) {
                BigDecimal benchmark = isMedianOutlier ? median : BigDecimal.valueOf(mean).setScale(2, RoundingMode.HALF_UP);
                BigDecimal ratio = benchmark.compareTo(BigDecimal.ZERO) > 0
                        ? amount.divide(benchmark, 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

                String rule = isMedianOutlier ? "STATISTICAL_OUTLIER_2_5X_MEDIAN" : "STATISTICAL_OUTLIER_2_STDDEV";
                String benchmarkName = isMedianOutlier ? "median" : "average";
                String reason = String.format(
                        "Amount (₹%s) is %sx your %s expense (₹%s) in this period.",
                        amount.toPlainString(), ratio.toPlainString(), benchmarkName, benchmark.toPlainString()
                );

                outliers.add(new UnusualTransactionDto(
                        t.getId(),
                        t.getTitle(),
                        t.getAmount(),
                        t.getDate(),
                        t.getCategory() != null ? t.getCategory() : "UNCATEGORIZED",
                        benchmark,
                        ratio,
                        reason,
                        rule
                ));
            }
        }

        // Sort by amount descending, limit to top 10
        outliers.sort(Comparator.comparing(UnusualTransactionDto::amount).reversed());
        if (outliers.size() > 10) {
            return outliers.subList(0, 10);
        }
        return outliers;
    }

    private BigDecimal calculateMedian(List<Transaction> sortedByAmount) {
        int n = sortedByAmount.size();
        if (n == 0) return BigDecimal.ZERO;
        if (n % 2 == 1) {
            return sortedByAmount.get(n / 2).getAmount();
        } else {
            BigDecimal a = sortedByAmount.get(n / 2 - 1).getAmount();
            BigDecimal b = sortedByAmount.get(n / 2).getAmount();
            return a.add(b).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        }
    }

    private TransactionCategory parseCategory(String name) {
        if (name == null || name.isBlank()) return null;
        return TransactionCategory.parse(name).orElse(null);
    }
}
