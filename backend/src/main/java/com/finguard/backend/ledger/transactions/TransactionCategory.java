package com.finguard.backend.ledger.transactions;

import com.finguard.backend.ledger.transactions.Transaction.TransactionType;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The canonical category taxonomy. Identifiers must match {@code contracts/categories.json}
 * (shared with the AI service, the database CHECK constraint in V5 and the frontend);
 * {@code TaxonomyContractTest} fails if they drift.
 *
 * <p>{@link Flow} says how a category must be treated by later arithmetic: only {@code EXPENSE}
 * is ordinary spending. Transfers, credit-card bill payments and investment movements are
 * {@code NON_SPENDING}; refunds are {@code REFUND} (they reduce an expense, they are not income).
 */
public enum TransactionCategory {
    INCOME(Flow.INCOME, TransactionType.INCOME),
    GROCERIES(Flow.EXPENSE, TransactionType.EXPENSE),
    DINING(Flow.EXPENSE, TransactionType.EXPENSE),
    TRANSPORT(Flow.EXPENSE, TransactionType.EXPENSE),
    HOUSING(Flow.EXPENSE, TransactionType.EXPENSE),
    UTILITIES(Flow.EXPENSE, TransactionType.EXPENSE),
    HEALTHCARE(Flow.EXPENSE, TransactionType.EXPENSE),
    INSURANCE(Flow.EXPENSE, TransactionType.EXPENSE),
    SHOPPING(Flow.EXPENSE, TransactionType.EXPENSE),
    SUBSCRIPTIONS(Flow.EXPENSE, TransactionType.EXPENSE),
    EDUCATION(Flow.EXPENSE, TransactionType.EXPENSE),
    ENTERTAINMENT(Flow.EXPENSE, TransactionType.EXPENSE),
    FEES(Flow.EXPENSE, TransactionType.EXPENSE),
    LOAN_PAYMENTS(Flow.EXPENSE, TransactionType.EXPENSE),
    INVESTMENTS(Flow.NON_SPENDING, TransactionType.INCOME, TransactionType.EXPENSE),
    TRANSFERS(Flow.NON_SPENDING, TransactionType.INCOME, TransactionType.EXPENSE),
    CARD_PAYMENTS(Flow.NON_SPENDING, TransactionType.EXPENSE),
    REFUNDS(Flow.REFUND, TransactionType.INCOME),
    UNCATEGORIZED(Flow.UNKNOWN, TransactionType.INCOME, TransactionType.EXPENSE);

    public enum Flow {
        INCOME, EXPENSE, NON_SPENDING, REFUND, UNKNOWN
    }

    /** Labels used by the old frontend dropdown and obvious spellings, mapped like the V5 migration does. */
    private static final Map<String, TransactionCategory> LEGACY_ALIASES = Map.ofEntries(
            Map.entry("salary", INCOME),
            Map.entry("freelance", INCOME),
            Map.entry("business", INCOME),
            Map.entry("investment returns", INCOME),
            Map.entry("food", DINING),
            Map.entry("grocery", GROCERIES),
            Map.entry("rent", HOUSING),
            Map.entry("health", HEALTHCARE),
            Map.entry("emi", LOAN_PAYMENTS),
            Map.entry("other", UNCATEGORIZED)
    );

    private static final Map<String, TransactionCategory> BY_ID = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(c -> c.name().toLowerCase(Locale.ROOT), c -> c));

    private final Flow flow;
    private final Set<TransactionType> types;

    TransactionCategory(Flow flow, TransactionType first, TransactionType... rest) {
        this.flow = flow;
        this.types = Set.copyOf(java.util.EnumSet.of(first, rest));
    }

    public Flow flow() {
        return flow;
    }

    /** Whether this category may be assigned <em>automatically</em> to a transaction of the given direction. */
    public boolean allowedFor(TransactionType type) {
        return type == null || types.contains(type);
    }

    /**
     * Resolves a canonical id (any letter case) or a legacy label such as {@code "Food"}.
     * Empty for unknown text and for blank input.
     */
    public static Optional<TransactionCategory> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String key = value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        TransactionCategory byId = BY_ID.get(key);
        if (byId != null) {
            return Optional.of(byId);
        }
        return Optional.ofNullable(LEGACY_ALIASES.get(value.trim().toLowerCase(Locale.ROOT)));
    }
}
