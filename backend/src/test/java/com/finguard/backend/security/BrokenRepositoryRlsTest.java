package com.finguard.backend.security;

import com.finguard.backend.common.tenant.TenantContext;
import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1-06 Acceptance Gate (06 §9):
 * Verifies that with PostgreSQL Row-Level Security (RLS) active and transaction-scoped
 * {@code app.current_user_id} set via {@link TenantContext}, a query that deliberately omits
 * the {@code user_id} predicate returns ZERO foreign rows.
 */
@DisplayName("S1-06 — PostgreSQL Row-Level Security (RLS) Pilot & Broken-Repository Guard")
class BrokenRepositoryRlsTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    record TxRow(String id, String userId, String title, BigDecimal amount) {}

    @Test
    @DisplayName("Broken query omitting user_id predicate returns ZERO foreign rows under RLS")
    void brokenQueryOmittingUserIdPredicateReturnsZeroForeignRows() throws Exception {
        TestUser userA = registerUser();
        TestUser userB = registerUser();

        String userAId = extractUserId(userA);
        String userBId = extractUserId(userB);

        TransactionTemplate tx = new TransactionTemplate(txManager);

        // Seed transactions for user A and user B
        String txAId = UUID.randomUUID().toString();
        TenantContext.runAs(userAId, () -> {
            tx.executeWithoutResult(status -> {
                jdbc.update(
                        """
                        INSERT INTO transactions (id, user_id, title, amount, type, source, txn_date, created_at)
                        VALUES (?, ?, 'User A Coffee', 150.00, 'EXPENSE', 'MANUAL', ?, now())
                        """,
                        txAId, userAId, LocalDate.now()
                );
            });
        });

        String txBId = UUID.randomUUID().toString();
        TenantContext.runAs(userBId, () -> {
            tx.executeWithoutResult(status -> {
                jdbc.update(
                        """
                        INSERT INTO transactions (id, user_id, title, amount, type, source, txn_date, created_at)
                        VALUES (?, ?, 'User B Salary', 50000.00, 'INCOME', 'MANUAL', ?, now())
                        """,
                        txBId, userBId, LocalDate.now()
                );
            });
        });

        // 1. Broken query executed inside a transaction as User A:
        //    deliberately omits WHERE user_id = :userId!
        List<TxRow> rowsSeenByA = TenantContext.callAs(userAId, () ->
                tx.execute(status ->
                        jdbc.query(
                                "SELECT id, user_id, title, amount FROM transactions",
                                (rs, i) -> new TxRow(rs.getString("id"), rs.getString("user_id"), rs.getString("title"), rs.getBigDecimal("amount"))
                        )
                )
        );

        // Assert User A sees only User A's transaction: ZERO rows from User B!
        assertThat(rowsSeenByA).extracting(TxRow::id).contains(txAId).doesNotContain(txBId);
        assertThat(rowsSeenByA).allMatch(r -> r.userId().equals(userAId));

        // 2. Broken query executed inside a transaction as User B:
        //    deliberately omits WHERE user_id = :userId!
        List<TxRow> rowsSeenByB = TenantContext.callAs(userBId, () ->
                tx.execute(status ->
                        jdbc.query(
                                "SELECT id, user_id, title, amount FROM transactions",
                                (rs, i) -> new TxRow(rs.getString("id"), rs.getString("user_id"), rs.getString("title"), rs.getBigDecimal("amount"))
                        )
                )
        );

        // Assert User B sees only User B's transaction: ZERO rows from User A!
        assertThat(rowsSeenByB).extracting(TxRow::id).contains(txBId).doesNotContain(txAId);
        assertThat(rowsSeenByB).allMatch(r -> r.userId().equals(userBId));

        // 3. Broken query executed with non-existent foreign user ID: returns ZERO rows!
        String foreignUserId = UUID.randomUUID().toString();
        List<TxRow> rowsSeenByForeign = TenantContext.callAs(foreignUserId, () ->
                tx.execute(status ->
                        jdbc.query(
                                "SELECT id, user_id, title, amount FROM transactions",
                                (rs, i) -> new TxRow(rs.getString("id"), rs.getString("user_id"), rs.getString("title"), rs.getBigDecimal("amount"))
                        )
                )
        );
        assertThat(rowsSeenByForeign).isEmpty();

        // 4. Query with unauthenticated tenant context: returns ZERO rows
        TenantContext.runAs("unauthenticated", () -> {
            List<TxRow> rowsWithoutContext = tx.execute(status ->
                    jdbc.query(
                            "SELECT id, user_id, title, amount FROM transactions WHERE id IN (?, ?)",
                            (rs, i) -> new TxRow(rs.getString("id"), rs.getString("user_id"), rs.getString("title"), rs.getBigDecimal("amount")),
                            txAId, txBId
                    )
            );
            assertThat(rowsWithoutContext).isEmpty();
        });
    }

    @Test
    @DisplayName("RLS prevents cross-tenant writes and updates even if predicate is missing")
    void rlsPreventsCrossTenantModifications() throws Exception {
        TestUser userA = registerUser();
        TestUser userB = registerUser();

        String userAId = extractUserId(userA);
        String userBId = extractUserId(userB);

        TransactionTemplate tx = new TransactionTemplate(txManager);

        String loanAId = UUID.randomUUID().toString();
        TenantContext.runAs(userAId, () -> {
            tx.executeWithoutResult(status -> {
                jdbc.update(
                        """
                        INSERT INTO loans (id, user_id, name, type, principal, emi_amount, interest_rate, created_at)
                        VALUES (?, ?, 'User A Home Loan', 'HOME', 1000000.00, 25000.00, 8.5, now())
                        """,
                        loanAId, userAId
                );
            });
        });

        // User B attempts a blind update on all loans without a user_id predicate
        Integer updatedByB = TenantContext.callAs(userBId, () ->
                tx.execute(status -> jdbc.update("UPDATE loans SET name = 'Hacked Loan'"))
        );

        // Under RLS, User B's query only touches User B's rows (0 rows updated), User A's loan remains untouched!
        assertThat(updatedByB).isEqualTo(0);

        // Verify User A's loan name is unmodified
        String nameAfterAttack = TenantContext.callAs(userAId, () ->
                tx.execute(status ->
                        jdbc.queryForObject("SELECT name FROM loans WHERE id = ?", String.class, loanAId)
                )
        );
        assertThat(nameAfterAttack).isEqualTo("User A Home Loan");
    }

    private String extractUserId(TestUser user) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, user.email());
    }
}
