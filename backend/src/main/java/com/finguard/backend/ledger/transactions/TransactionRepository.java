package com.finguard.backend.ledger.transactions;

import com.finguard.backend.ledger.transactions.Transaction.CategorySource;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Every lookup is scoped by owner. There is deliberately no unscoped {@code findById} usage in
 * services: a record that belongs to someone else is indistinguishable from one that does not exist.
 */
public interface TransactionRepository extends JpaRepository<Transaction, String>, JpaSpecificationExecutor<Transaction> {

    List<Transaction> findByUserIdOrderByDateDescCreatedAtDesc(String userId);

    Optional<Transaction> findByIdAndUserId(String id, String userId);

    boolean existsByUserIdAndFingerprint(String userId, String fingerprint);

    @Query("SELECT t.fingerprint FROM Transaction t WHERE t.user.id = :userId AND t.fingerprint IN :fingerprints")
    Set<String> findExistingFingerprints(@Param("userId") String userId, @Param("fingerprints") Collection<String> fingerprints);

    /** The user's transactions that have no category yet, newest first. */
    @Query("SELECT t FROM Transaction t WHERE t.user.id = :userId AND t.categorySource = :source "
            + "ORDER BY t.date DESC, t.createdAt DESC")
    List<Transaction> findByCategorySource(@Param("userId") String userId, @Param("source") CategorySource source,
                                           Pageable pageable);

    long countByUserIdAndCategorySource(String userId, CategorySource source);

    /**
     * Row-locks the given transactions <em>that still have no category</em>. Used when applying an
     * automatic category: a transaction a person categorised in the meantime is no longer returned,
     * and a concurrent correction waits for the lock and then wins, so automation never overwrites a
     * user's choice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.user.id = :userId AND t.id IN :ids AND t.categorySource = :source")
    List<Transaction> lockByCategorySource(@Param("userId") String userId, @Param("ids") Collection<String> ids,
                                           @Param("source") CategorySource source);
}
