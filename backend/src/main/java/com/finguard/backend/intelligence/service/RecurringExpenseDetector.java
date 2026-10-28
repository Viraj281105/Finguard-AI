package com.finguard.backend.intelligence.service;

import com.finguard.backend.intelligence.dto.RecurringExpenseItemDto;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.Transaction.CategorySource;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import com.finguard.backend.ledger.transactions.TransactionCategory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Detects recurring expenses, subscriptions and regular bills using transaction descriptions,
 * amounts, and temporal cadence.
 */
@Component
public class RecurringExpenseDetector {

    private static final Pattern PREFIX_CLEANER = Pattern.compile(
            "^(UPI[-/]|POS[-/ ]|NEFT[-/]|IMPS[-/]|RTGS[-/]|ACH[-/]|BILLPAY[-/]|INB[-/]|ECOM[-/])+",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ID_DATE_CLEANER = Pattern.compile(
            "[-/_ ]\\d{6,}|[-/_ ][a-z0-9]{8,}|@\\w+",
            Pattern.CASE_INSENSITIVE
    );

    private static final Set<String> KNOWN_SUBSCRIPTION_KEYWORDS = Set.of(
            "netflix", "spotify", "prime", "amazon prime", "hotstar", "disney", "youtube", "apple",
            "icloud", "google one", "airtel", "jio", "broadband", "tatasky", "dth", "electricity",
            "gym", "cult", "fitness", "newspaper", "sub", "patreon", "medium", "chatgpt", "openai"
    );

    public List<RecurringExpenseItemDto> detectRecurring(List<Transaction> allTransactions) {
        // Look only at outgoing expense transactions, excluding non-spending flows
        List<Transaction> expenses = allTransactions.stream()
                .filter(t -> t.getType() == TransactionType.EXPENSE)
                .filter(t -> {
                    TransactionCategory cat = parseCategory(t.getCategory());
                    return cat == null || cat.flow() == TransactionCategory.Flow.EXPENSE || cat.flow() == TransactionCategory.Flow.UNKNOWN;
                })
                .sorted(Comparator.comparing(Transaction::getDate))
                .toList();

        Map<String, List<Transaction>> grouped = new LinkedHashMap<>();
        for (Transaction t : expenses) {
            String key = normalizeMerchant(t.getTitle());
            if (key.length() >= 3) {
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
            }
        }

        List<RecurringExpenseItemDto> detected = new ArrayList<>();
        int sequence = 1;

        for (Map.Entry<String, List<Transaction>> entry : grouped.entrySet()) {
            String merchantKey = entry.getKey();
            List<Transaction> list = entry.getValue();

            Optional<RecurringExpenseItemDto> itemOpt = evaluateGroup(merchantKey, list, "rec-" + sequence);
            if (itemOpt.isPresent()) {
                detected.add(itemOpt.get());
                sequence++;
            }
        }

        // Sort by estimated monthly amount descending
        detected.sort(Comparator.comparing(RecurringExpenseItemDto::amount).reversed());
        return detected;
    }

    private Optional<RecurringExpenseItemDto> evaluateGroup(String merchantKey, List<Transaction> txs, String id) {
        if (txs.isEmpty()) return Optional.empty();

        txs.sort(Comparator.comparing(Transaction::getDate));
        Transaction latest = txs.get(txs.size() - 1);
        String displayTitle = cleanDisplayTitle(latest.getTitle());
        String category = latest.getCategory() != null ? latest.getCategory() : "SUBSCRIPTIONS";
        boolean isConfirmed = txs.stream().anyMatch(t ->
                t.getCategorySource() == CategorySource.USER && "SUBSCRIPTIONS".equalsIgnoreCase(t.getCategory()));

        // Case 1: Multiple transactions with temporal cadence
        if (txs.size() >= 2) {
            List<Long> intervals = new ArrayList<>();
            for (int i = 1; i < txs.size(); i++) {
                long days = ChronoUnit.DAYS.between(txs.get(i - 1).getDate(), txs.get(i).getDate());
                if (days > 0) {
                    intervals.add(days);
                }
            }

            if (!intervals.isEmpty()) {
                double avgInterval = intervals.stream().mapToLong(Long::valueOf).average().orElse(0.0);
                boolean amountsSimilar = areAmountsSimilar(txs);

                // Monthly cadence: 24 to 36 days on average
                if (avgInterval >= 24 && avgInterval <= 36 && amountsSimilar) {
                    BigDecimal avgAmount = calculateAverage(txs);
                    String confidence = txs.size() >= 3 ? "HIGH" : "MEDIUM";
                    String explanation = String.format(
                            "Consistent monthly cadence of ~%d days observed across %d transactions (latest: %s).",
                            Math.round(avgInterval), txs.size(), latest.getDate()
                    );

                    return Optional.of(new RecurringExpenseItemDto(
                            id, displayTitle, capitalize(merchantKey), category,
                            avgAmount, "MONTHLY", txs.size(), latest.getDate(),
                            confidence, isConfirmed, explanation
                    ));
                }

                // Weekly cadence: 6 to 8 days on average
                if (avgInterval >= 6 && avgInterval <= 8 && amountsSimilar) {
                    BigDecimal avgAmount = calculateAverage(txs);
                    BigDecimal monthlyEquiv = avgAmount.multiply(BigDecimal.valueOf(52)).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
                    String confidence = txs.size() >= 4 ? "HIGH" : "MEDIUM";
                    String explanation = String.format(
                            "Consistent weekly cadence of ~%d days observed across %d transactions.",
                            Math.round(avgInterval), txs.size()
                    );

                    return Optional.of(new RecurringExpenseItemDto(
                            id, displayTitle, capitalize(merchantKey), category,
                            monthlyEquiv, "WEEKLY", txs.size(), latest.getDate(),
                            confidence, isConfirmed, explanation
                    ));
                }

                // Known subscription keyword with at least 2 occurrences within 60 days
                if (isKnownSubscription(merchantKey) && amountsSimilar && avgInterval >= 15 && avgInterval <= 45) {
                    BigDecimal avgAmount = calculateAverage(txs);
                    return Optional.of(new RecurringExpenseItemDto(
                            id, displayTitle, capitalize(merchantKey), category,
                            avgAmount, "MONTHLY", txs.size(), latest.getDate(),
                            "MEDIUM", isConfirmed,
                            String.format("Recognized recurring service (%s) with %d recorded payments.", capitalize(merchantKey), txs.size())
                    ));
                }
            }
        }

        // Case 2: Explicit user-confirmed subscription or recognizable keyword with at least 1 transaction
        if (isConfirmed || (isKnownSubscription(merchantKey) && "SUBSCRIPTIONS".equalsIgnoreCase(latest.getCategory()))) {
            BigDecimal avgAmount = calculateAverage(txs);
            String confidence = txs.size() >= 2 ? "MEDIUM" : "LOW";
            String explanation = isConfirmed
                    ? "User-confirmed recurring subscription."
                    : String.format("Known subscription payee (%s) with recorded charges.", capitalize(merchantKey));

            return Optional.of(new RecurringExpenseItemDto(
                    id, displayTitle, capitalize(merchantKey), category,
                    avgAmount, "MONTHLY", txs.size(), latest.getDate(),
                    confidence, isConfirmed, explanation
            ));
        }

        return Optional.empty();
    }

    private boolean areAmountsSimilar(List<Transaction> txs) {
        if (txs.size() < 2) return true;
        BigDecimal min = txs.stream().map(Transaction::getAmount).min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal max = txs.stream().map(Transaction::getAmount).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        if (min.compareTo(BigDecimal.ZERO) == 0) return false;

        // Allow up to 15% fluctuation (e.g. utility bill or currency shift)
        BigDecimal diff = max.subtract(min);
        BigDecimal ratio = diff.divide(min, 4, RoundingMode.HALF_UP);
        return ratio.compareTo(BigDecimal.valueOf(0.15)) <= 0;
    }

    private BigDecimal calculateAverage(List<Transaction> txs) {
        BigDecimal sum = txs.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(txs.size()), 2, RoundingMode.HALF_UP);
    }

    private String normalizeMerchant(String rawTitle) {
        if (rawTitle == null) return "";
        String s = PREFIX_CLEANER.matcher(rawTitle).replaceAll("");
        s = ID_DATE_CLEANER.matcher(s).replaceAll("");
        s = s.replaceAll("[^a-zA-Z0-9 ]", " ").trim().toLowerCase(Locale.ROOT);
        s = s.replaceAll("\\s+", " ");
        // If merchant has multiple words, take the primary anchor word if it's long enough
        String[] parts = s.split(" ");
        if (parts.length > 0 && parts[0].length() >= 3) {
            return parts[0];
        }
        return s;
    }

    private String cleanDisplayTitle(String rawTitle) {
        if (rawTitle == null) return "Recurring Payment";
        String s = PREFIX_CLEANER.matcher(rawTitle).replaceAll("").trim();
        return s.isBlank() ? rawTitle : s;
    }

    private boolean isKnownSubscription(String key) {
        return KNOWN_SUBSCRIPTION_KEYWORDS.stream().anyMatch(k -> key.contains(k) || k.contains(key));
    }

    private String capitalize(String str) {
        if (str == null || str.isBlank()) return "";
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private TransactionCategory parseCategory(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) return null;
        return TransactionCategory.parse(categoryName).orElse(null);
    }
}
