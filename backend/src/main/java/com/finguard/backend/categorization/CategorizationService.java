package com.finguard.backend.categorization;

import com.finguard.backend.ai.AiClient;
import com.finguard.backend.ai.AiPrediction;
import com.finguard.backend.ai.ClassifyOutcome;
import com.finguard.backend.ai.ClassifyRequest;
import com.finguard.backend.ledger.transactions.AutomaticCategory;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.Transaction.CategorySource;
import com.finguard.backend.ledger.transactions.TransactionCategory;
import com.finguard.backend.ledger.transactions.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks the AI service for categories and applies the answers to the ledger.
 *
 * <ul>
 *   <li>The AI service is optional. Nothing here can fail an import or lose a transaction: every
 *       failure ends as {@link ClassificationStatus#UNAVAILABLE} or {@code PARTIAL} and the rows
 *       stay uncategorised.</li>
 *   <li>Nothing the AI service says is trusted blindly. A prediction is applied only if its category
 *       is in the taxonomy, is not {@code UNCATEGORIZED}, suits the transaction direction, and came
 *       with a known method.</li>
 *   <li>A category chosen by a person always wins: predictions go through
 *       {@link TransactionService#applyAutomaticCategories}, which only touches rows that still have
 *       no category.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategorizationService {

    /** Upper bound for one user-triggered re-classification run. */
    public static final int MAX_RECLASSIFY = 500;

    private static final int VERSION_MAX = 64;

    private final AiClient aiClient;
    private final TransactionService transactions;

    /** Classifies freshly imported (already persisted) transactions. Never throws. */
    public CategorizationSummary classifyImported(String userId, List<Transaction> saved) {
        return classify(userId, saved);
    }

    /**
     * Retries classification for the user's transactions that still have no category.
     * Never touches a transaction a person has categorised.
     */
    public ReclassifyResponse reclassify(String userId, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, MAX_RECLASSIFY));
        List<Transaction> candidates = transactions.findUncategorised(userId, limit);
        CategorizationSummary summary = classify(userId, candidates);
        return new ReclassifyResponse(summary.status(), summary.attempted(), summary.categorized(),
                summary.needsReview(), transactions.countUncategorised(userId));
    }

    private CategorizationSummary classify(String userId, List<Transaction> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return CategorizationSummary.notAttempted();
        }
        try {
            List<ClassifyRequest.Item> items = candidates.stream()
                    .map(t -> new ClassifyRequest.Item(t.getId(), t.getTitle(), t.getType().name(), t.getAmount()))
                    .toList();
            ClassifyOutcome outcome = aiClient.classify(items);

            Map<String, AutomaticCategory> accepted = accept(candidates, outcome);
            int applied = transactions.applyAutomaticCategories(userId, accepted);
            int review = (int) accepted.values().stream().filter(AutomaticCategory::needsReview).count();

            ClassificationStatus status = outcome.unavailable() ? ClassificationStatus.UNAVAILABLE
                    : outcome.fullyAnswered() ? ClassificationStatus.COMPLETED
                    : ClassificationStatus.PARTIAL;
            return new CategorizationSummary(status, candidates.size(), applied, Math.min(review, applied));
        } catch (RuntimeException e) {
            // Includes database errors while applying: the transactions are already saved, so degrade.
            log.error("Automatic categorisation failed; transactions keep no category", e);
            return new CategorizationSummary(ClassificationStatus.UNAVAILABLE, candidates.size(), 0, 0);
        }
    }

    private Map<String, AutomaticCategory> accept(List<Transaction> candidates, ClassifyOutcome outcome) {
        Map<String, AutomaticCategory> accepted = new HashMap<>();
        int rejected = 0;
        for (Transaction t : candidates) {
            AiPrediction p = outcome.predictions().get(t.getId());
            if (p == null || p.abstained()) {
                continue;
            }
            AutomaticCategory category = toAutomatic(t, p);
            if (category == null) {
                rejected++;
            } else {
                accepted.put(t.getId(), category);
            }
        }
        if (rejected > 0) {
            log.warn("Discarded {} AI predictions that failed validation (unknown category, wrong direction or method)",
                    rejected);
        }
        return accepted;
    }

    /** @return the validated category, or null if the prediction must not be used */
    private static AutomaticCategory toAutomatic(Transaction t, AiPrediction p) {
        TransactionCategory category = canonical(p.category());
        if (category == null || category == TransactionCategory.UNCATEGORIZED || !category.allowedFor(t.getType())) {
            return null;
        }
        CategorySource source = switch (p.method()) {
            case "rule" -> CategorySource.RULE;
            case "model" -> CategorySource.MODEL;
            default -> null;
        };
        if (source == null) {
            return null;
        }
        return new AutomaticCategory(
                category,
                source,
                BigDecimal.valueOf(p.confidence()).setScale(4, RoundingMode.HALF_UP),
                p.confidenceCalibrated(),
                truncate(p.modelVersion()),
                truncate(p.ruleId()),
                p.needsReview()
        );
    }

    /** Strict: the AI service must speak canonical ids; legacy labels are only accepted from humans. */
    private static TransactionCategory canonical(String value) {
        try {
            return TransactionCategory.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() > VERSION_MAX ? value.substring(0, VERSION_MAX) : value;
    }
}
