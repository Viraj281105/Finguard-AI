package com.finguard.backend.ledger.transactions;

import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.ledger.transactions.Transaction.CategorySource;
import com.finguard.backend.ledger.transactions.Transaction.TransactionSource;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransactionService {

    /** Keeps {@code IN (...)} lists comfortably below database limits. */
    private static final int LOCK_CHUNK = 500;

    private final TransactionRepository transactionRepository;

    @Transactional
    public Transaction create(TransactionRequest request, User user) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setDate(request.date() != null ? request.date() : LocalDate.now());
        t.setSource(TransactionSource.MANUAL);
        apply(t, request);
        // A category supplied by the person creating the transaction is their choice.
        if (request.category() != null && !request.category().isBlank()) {
            t.assignUserCategory(requireCategory(request.category()));
        }
        return transactionRepository.save(t);
    }

    @Transactional(readOnly = true)
    public List<Transaction> getAll(User user) {
        return transactionRepository.findByUserIdOrderByDateDescCreatedAtDesc(user.getId());
    }

    @Transactional(readOnly = true)
    public Page<Transaction> list(
            User user,
            LocalDate from,
            LocalDate to,
            TransactionType type,
            String category,
            Boolean needsReview,
            Pageable pageable
    ) {
        TransactionCategory categoryFilter = category == null || category.isBlank() ? null : requireCategory(category);

        Specification<Transaction> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("user").get("id"), user.getId()));

            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("date"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("date"), to));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (categoryFilter == TransactionCategory.UNCATEGORIZED) {
                // "Uncategorised" means no category yet, or one the user explicitly left uncategorised.
                predicates.add(cb.or(
                        cb.isNull(root.get("category")),
                        cb.equal(root.get("category"), TransactionCategory.UNCATEGORIZED.name())));
            } else if (categoryFilter != null) {
                predicates.add(cb.equal(root.get("category"), categoryFilter.name()));
            }
            if (Boolean.TRUE.equals(needsReview)) {
                predicates.add(cb.isTrue(root.get("categoryNeedsReview")));
                predicates.add(root.get("categorySource").in(CategorySource.RULE, CategorySource.MODEL));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return transactionRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public Transaction getById(String id, User user) {
        return transactionRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> ApiException.notFound("Transaction not found"));
    }

    /**
     * Full update. {@code category}: absent (null) keeps the current category; blank clears it;
     * any other value is validated and recorded as the user's choice.
     */
    @Transactional
    public Transaction update(String id, TransactionRequest request, User user) {
        Transaction t = getById(id, user);
        if (request.date() != null) {
            t.setDate(request.date());
        }
        apply(t, request);
        if (request.category() != null) {
            if (request.category().isBlank()) {
                t.clearCategory();
            } else {
                t.assignUserCategory(requireCategory(request.category()));
            }
        }
        return transactionRepository.save(t);
    }

    /** A person's correction. It is authoritative: automation never replaces it. */
    @Transactional
    public Transaction setUserCategory(String id, String category, User user) {
        Transaction t = getById(id, user);
        t.assignUserCategory(requireCategory(category));
        return transactionRepository.save(t);
    }

    @Transactional
    public void delete(String id, User user) {
        transactionRepository.delete(getById(id, user));
    }

    /** Persists a batch atomically: either every transaction is saved or none is. Used by statement import. */
    @Transactional
    public List<Transaction> saveAll(List<Transaction> transactions) {
        return transactionRepository.saveAll(transactions);
    }

    @Transactional(readOnly = true)
    public Set<String> findExistingFingerprints(String userId, Collection<String> fingerprints) {
        if (fingerprints == null || fingerprints.isEmpty()) {
            return Set.of();
        }
        return transactionRepository.findExistingFingerprints(userId, fingerprints);
    }

    /** The user's transactions that have no category and have never been looked at, newest first. */
    @Transactional(readOnly = true)
    public List<Transaction> findUncategorised(String userId, int limit) {
        return transactionRepository.findByCategorySource(userId, CategorySource.NONE, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public long countUncategorised(String userId) {
        return transactionRepository.countByUserIdAndCategorySource(userId, CategorySource.NONE);
    }

    /**
     * Applies automatic categories, but only to transactions that still have no category. Each row is
     * locked and re-checked inside the transaction, so a category the user set after the prediction
     * was requested (or a concurrent correction) is never overwritten.
     *
     * @return how many transactions actually received a category
     */
    @Transactional
    public int applyAutomaticCategories(String userId, Map<String, AutomaticCategory> byTransactionId) {
        if (byTransactionId == null || byTransactionId.isEmpty()) {
            return 0;
        }
        List<String> ids = List.copyOf(byTransactionId.keySet());
        LocalDateTime now = LocalDateTime.now();
        int applied = 0;
        for (int from = 0; from < ids.size(); from += LOCK_CHUNK) {
            List<String> chunk = ids.subList(from, Math.min(from + LOCK_CHUNK, ids.size()));
            for (Transaction t : transactionRepository.lockByCategorySource(userId, chunk, CategorySource.NONE)) {
                AutomaticCategory auto = byTransactionId.get(t.getId());
                if (auto == null || !auto.category().allowedFor(t.getType())) {
                    continue;
                }
                t.setCategory(auto.category().name());
                t.setCategorySource(auto.source());
                t.setCategoryConfidence(auto.confidence());
                t.setCategoryConfidenceCalibrated(auto.confidenceCalibrated());
                t.setCategoryModelVersion(auto.modelVersion());
                t.setCategoryRule(auto.rule());
                t.setCategoryNeedsReview(auto.needsReview());
                t.setCategoryUpdatedAt(now);
                applied++;
            }
        }
        return applied;
    }

    private void apply(Transaction t, TransactionRequest request) {
        t.setTitle(request.title());
        t.setAmount(request.amount());
        t.setType(request.type());
        t.setNote(request.note());
    }

    private static TransactionCategory requireCategory(String value) {
        return TransactionCategory.parse(value).orElseThrow(() -> ApiException.badRequest(
                "Unknown category '" + abbreviate(value) + "'. Valid categories: " + validIds()));
    }

    private static String validIds() {
        return Arrays.stream(TransactionCategory.values()).map(Enum::name).collect(Collectors.joining(", "));
    }

    private static String abbreviate(String value) {
        String trimmed = value.trim();
        return trimmed.length() > 40 ? trimmed.substring(0, 40) + "…" : trimmed;
    }
}
