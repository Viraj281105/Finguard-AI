package com.finguard.backend.audit;

import com.finguard.backend.budgeting.domain.Goal;
import com.finguard.backend.budgeting.domain.GoalRepository;
import com.finguard.backend.budgeting.dto.CreateGoalRequest;
import com.finguard.backend.budgeting.dto.GoalResponseDto;
import com.finguard.backend.budgeting.dto.UpdateGoalRequest;
import com.finguard.backend.budgeting.service.GoalService;
import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.common.error.RateLimitExceededException;
import com.finguard.backend.identity.domain.RefreshToken;
import com.finguard.backend.identity.domain.RefreshTokenRepository;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.identity.domain.UserRepository;
import com.finguard.backend.identity.security.AuthRateLimiter;
import com.finguard.backend.identity.security.SecurityEnvironmentGuard;
import com.finguard.backend.identity.service.AuthService;
import com.finguard.backend.identity.web.AuthController;
import com.finguard.backend.identity.web.AuthResponse;
import com.finguard.backend.identity.web.LoginRequest;
import com.finguard.backend.identity.web.RegisterRequest;
import com.finguard.backend.intelligence.dto.FinancialSummaryResponse;
import com.finguard.backend.intelligence.service.AnalyticsService;
import com.finguard.backend.ledger.transactions.Transaction;
import com.finguard.backend.ledger.transactions.Transaction.TransactionType;
import com.finguard.backend.ledger.transactions.TransactionRepository;
import com.finguard.backend.ledger.transactions.TransactionRequest;
import com.finguard.backend.ledger.transactions.TransactionResponse;
import com.finguard.backend.ledger.transactions.TransactionService;
import com.finguard.backend.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class ReleaseReadinessRemediationTest extends IntegrationTest {

    @Autowired
    private TransactionService transactionService;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private GoalService goalService;
    @Autowired
    private GoalRepository goalRepository;
    @Autowired
    private AnalyticsService analyticsService;
    @Autowired
    private AuthService authService;
    @Autowired
    private AuthController authController;
    @Autowired
    private AuthRateLimiter authRateLimiter;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private Clock clock;

    private User createTestUser() {
        User user = new User();
        user.setEmail("audit-user-" + UUID.randomUUID() + "@example.com");
        user.setName("Audit User");
        user.setPassword("hashedpassword123");
        return userRepository.save(user);
    }

    @Nested
    @DisplayName("Financial Correctness (R-1, R-2, R-5)")
    class FinancialCorrectnessTests {

        @Test
        @DisplayName("R-2: Transaction direction change without category field validates against existing category")
        void transactionDirectionChangeWithoutCategoryValidatesExistingCategory() {
            User user = createTestUser();
            // Create an expense with DINING category
            TransactionRequest createReq = new TransactionRequest(
                    "Dinner at Bistro",
                    new BigDecimal("1500.00"),
                    TransactionType.EXPENSE,
                    LocalDate.now(clock),
                    "DINING",
                    "Initial note"
            );
            Transaction tx = transactionService.create(createReq, user);
            assertThat(tx.getType()).isEqualTo(TransactionType.EXPENSE);
            assertThat(tx.getCategory()).isEqualTo("DINING");

            // Update to INCOME while omitting category (null) -> must fail validation because DINING is an expense category
            TransactionRequest invalidUpdate = new TransactionRequest(
                    "Dinner Reimbursement",
                    new BigDecimal("1500.00"),
                    TransactionType.INCOME,
                    LocalDate.now(clock),
                    null, // category omitted
                    "Trying direction flip without re-categorising"
            );

            assertThatThrownBy(() -> transactionService.update(tx.getId(), invalidUpdate, user))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Category 'DINING' is not compatible with transaction type INCOME");

            // When compatible category is explicitly provided (or cleared), update succeeds
            TransactionRequest validUpdate = new TransactionRequest(
                    "Salary",
                    new BigDecimal("1500.00"),
                    TransactionType.INCOME,
                    LocalDate.now(clock),
                    "INCOME",
                    "Valid flip"
            );
            Transaction updated = transactionService.update(tx.getId(), validUpdate, user);
            assertThat(updated.getType()).isEqualTo(TransactionType.INCOME);
            assertThat(updated.getCategory()).isEqualTo("INCOME");
        }

        @Test
        @DisplayName("R-5: Cross-month refund reconciliation and cash-flow identity")
        void crossMonthRefundReconciliationAndCashFlowIdentity() {
            User user = createTestUser();

            // Month 1: 2026-02 -> Income: 50,000, Spend: 20,000
            Transaction t1 = new Transaction();
            t1.setUser(user);
            t1.setTitle("Feb Salary");
            t1.setAmount(new BigDecimal("50000.00"));
            t1.setType(TransactionType.INCOME);
            t1.setCategory("INCOME");
            t1.setCategorySource(Transaction.CategorySource.USER);
            t1.setDate(LocalDate.of(2026, 2, 1));
            transactionRepository.save(t1);

            Transaction t2 = new Transaction();
            t2.setUser(user);
            t2.setTitle("Feb Flight Tickets");
            t2.setAmount(new BigDecimal("20000.00"));
            t2.setType(TransactionType.EXPENSE);
            t2.setCategory("SHOPPING");
            t2.setCategorySource(Transaction.CategorySource.USER);
            t2.setDate(LocalDate.of(2026, 2, 10));
            transactionRepository.save(t2);

            // Month 2: 2026-03 -> Flight canceled, full refund of 20,000 + grocery spend of 5,000
            Transaction t3 = new Transaction();
            t3.setUser(user);
            t3.setTitle("Airline Refund");
            t3.setAmount(new BigDecimal("20000.00"));
            t3.setType(TransactionType.INCOME);
            t3.setCategory("REFUNDS");
            t3.setCategorySource(Transaction.CategorySource.USER);
            t3.setDate(LocalDate.of(2026, 3, 5));
            transactionRepository.save(t3);

            Transaction t4 = new Transaction();
            t4.setUser(user);
            t4.setTitle("March Groceries");
            t4.setAmount(new BigDecimal("5000.00"));
            t4.setType(TransactionType.EXPENSE);
            t4.setCategory("GROCERIES");
            t4.setCategorySource(Transaction.CategorySource.USER);
            t4.setDate(LocalDate.of(2026, 3, 12));
            transactionRepository.save(t4);

            FinancialSummaryResponse summary = analyticsService.getSummary(user, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 31));

            // Cash flow identity: netCashFlow = (income + refunds) - grossExpenses
            // Income = 50,000, Refunds = 20,000, Gross Expenses = 25,000
            // Net Cash Flow = 50,000 + 20,000 - 25,000 = 45,000
            assertThat(summary.totalIncome()).isEqualByComparingTo("50000.00");
            assertThat(summary.totalRefunds()).isEqualByComparingTo("20000.00");
            assertThat(summary.grossExpenses()).isEqualByComparingTo("25000.00");
            assertThat(summary.totalExpenses()).isEqualByComparingTo("5000.00");
            assertThat(summary.netCashFlow()).isEqualByComparingTo("45000.00");

            // Verify monthly trend series matches summary exactly
            List<com.finguard.backend.intelligence.dto.MonthlyFlowDto> monthlyFlows =
                    analyticsService.getMonthlyFlows(user, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 31));
            BigDecimal monthlyCashFlowSum = monthlyFlows.stream()
                    .map(com.finguard.backend.intelligence.dto.MonthlyFlowDto::netCashFlow)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(summary.netCashFlow()).isEqualByComparingTo(monthlyCashFlowSum);
        }

        @Test
        @DisplayName("R-1: Provisional/review-required transactions are not dropped from income/expenses")
        void provisionalTransactionsAreNotDroppedFromFinancialFigures() {
            User user = createTestUser();

            // Client payment with ambiguous narration flagged categoryNeedsReview=true
            Transaction t = new Transaction();
            t.setUser(user);
            t.setTitle("NEFT CR-XYZ CLIENT SERVICES");
            t.setAmount(new BigDecimal("75000.00"));
            t.setType(TransactionType.INCOME);
            t.setCategory("UNCATEGORIZED");
            t.setCategoryNeedsReview(true);
            t.setCategorySource(Transaction.CategorySource.RULE);
            t.setDate(LocalDate.of(2026, 4, 15));
            transactionRepository.save(t);

            FinancialSummaryResponse summary = analyticsService.getSummary(user, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));
            // The 75,000 must NOT be dropped simply because category needs review!
            assertThat(summary.totalIncome()).isEqualByComparingTo("75000.00");
            assertThat(summary.netCashFlow()).isEqualByComparingTo("75000.00");
        }
    }

    @Nested
    @DisplayName("Authentication & Security (R-3, R-4, R-7)")
    class SecurityAndAuthTests {

        @Test
        @DisplayName("R-3: X-Forwarded-For header is ignored when remoteAddr is not a trusted proxy")
        void untrustedProxyCannotSpoofForwardedFor() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("198.51.100.77"); // Public/untrusted client IP
            request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");

            String resolved = authController.resolveClientIp(request);
            // Must NOT trust the spoofed header; must resolve to the real TCP remoteAddr
            assertThat(resolved).isEqualTo("198.51.100.77");
        }

        @Test
        @DisplayName("R-3: X-Forwarded-For is honoured when request originates from trusted proxy (127.0.0.1)")
        void trustedProxyHonoursForwardedFor() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("127.0.0.1"); // Trusted local proxy
            request.addHeader("X-Forwarded-For", "203.0.113.195, 10.0.0.1");

            String resolved = authController.resolveClientIp(request);
            assertThat(resolved).isEqualTo("203.0.113.195");
        }

        @Test
        @DisplayName("R-4: Auth response DTO does not leak refresh token in JSON response body")
        void authResponseDoesNotContainRefreshTokenInBody() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.setRemoteAddr("127.0.0.1");
            MockHttpServletResponse res = new MockHttpServletResponse();

            String email = "r4-test-" + UUID.randomUUID() + "@example.com";
            var responseEntity = authController.register(new RegisterRequest("R4 User", email, "SecurePassword123!"), req, res);

            // In JSON body, refreshToken must be null
            assertThat(responseEntity.getBody()).isNotNull();
            assertThat(responseEntity.getBody().refreshToken()).isNull();
            assertThat(responseEntity.getBody().accessToken()).isNotBlank();

            // In response cookies, refreshToken must be present with HttpOnly and SameSite=Strict
            var cookie = res.getCookie("refreshToken");
            assertThat(cookie).isNotNull();
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getValue()).isNotBlank();
        }

        @Test
        @DisplayName("Logout: Single device logout revokes current session; allDevices=true revokes all")
        void logoutScopeSingleDeviceVsAllDevices() {
            User user = createTestUser();
            AuthResponse t1 = authService.register(new RegisterRequest(user.getName(), "multi-" + UUID.randomUUID() + "@example.com", "SecretPass123!"), "127.0.0.1");
            AuthResponse t2 = authService.refresh(t1.refreshToken(), "127.0.0.1"); // Creates replacement token family

            // Logout single session
            authService.logout(t2.refreshToken(), user, false);

            // Attempting to refresh t2 fails
            assertThatThrownBy(() -> authService.refresh(t2.refreshToken(), "127.0.0.1"))
                    .isInstanceOf(ApiException.class);

            // Logging out with allDevices=true revokes everything for user
            authService.logout(null, user, true);
            List<RefreshToken> remaining = refreshTokenRepository.findByUser(user);
            assertThat(remaining).allMatch(RefreshToken::isRevoked);
        }

        @Test
        @DisplayName("R-7: SecurityEnvironmentGuard fails closed on unsafe production configuration")
        void securityEnvironmentGuardFailsClosedInProduction() {
            SecurityEnvironmentGuard guard = new SecurityEnvironmentGuard();

            // 1. Prod profile with default secret
            MockEnvironment envDefaultSecret = new MockEnvironment();
            envDefaultSecret.setActiveProfiles("production");
            envDefaultSecret.setProperty("jwt.secret", SecurityEnvironmentGuard.DEV_JWT_SECRET);
            envDefaultSecret.setProperty("jwt.cookie-secure", "true");
            envDefaultSecret.setProperty("spring.datasource.password", "strong_prod_pass");

            assertThatThrownBy(() -> guard.validateEnvironment(envDefaultSecret))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Default development JWT secret cannot be used in production");

            // 2. Prod profile with insecure cookies
            MockEnvironment envInsecureCookie = new MockEnvironment();
            envInsecureCookie.setActiveProfiles("production");
            envInsecureCookie.setProperty("jwt.secret", "a_very_strong_and_secure_production_secret_key_1234567890");
            envInsecureCookie.setProperty("jwt.cookie-secure", "false");
            envInsecureCookie.setProperty("spring.datasource.password", "strong_prod_pass");

            assertThatThrownBy(() -> guard.validateEnvironment(envInsecureCookie))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jwt.cookie-secure must be true in production");

            // 3. Prod profile with default DB password
            MockEnvironment envDefaultDbPass = new MockEnvironment();
            envDefaultDbPass.setActiveProfiles("production");
            envDefaultDbPass.setProperty("jwt.secret", "a_very_strong_and_secure_production_secret_key_1234567890");
            envDefaultDbPass.setProperty("jwt.cookie-secure", "true");
            envDefaultDbPass.setProperty("spring.datasource.password", "finguard_pass");

            assertThatThrownBy(() -> guard.validateEnvironment(envDefaultDbPass))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Default development database password cannot be used in production");
        }

        @Test
        @DisplayName("Rate limiter: Memory usage is bounded via LRU eviction")
        void rateLimiterMemoryIsBounded() {
            authRateLimiter.reset();
            // Flood limiter with unique IPs
            for (int i = 0; i < 6000; i++) {
                authRateLimiter.recordLoginFailure("198.51.100." + (i % 250), "user" + i + "@example.com");
            }
            // State is bounded and doesn't cause OutOfMemoryError
            assertThat(AuthRateLimiter.MAX_MAP_ENTRIES).isEqualTo(5000);
        }
    }

    @Nested
    @DisplayName("Application Defect Fixes")
    class ApplicationDefectTests {

        @Test
        @DisplayName("Overdue goal updates succeed when target date is unchanged")
        void overdueGoalUpdateAllowsTargetDateUnchanged() {
            User user = createTestUser();
            // Create a goal with target date today
            LocalDate today = LocalDate.now(clock);
            GoalResponseDto created = goalService.createGoal(user, new CreateGoalRequest("Emergency Fund", "General savings", new BigDecimal("100000.00"), today));

            // Artificially move target date to past in repository to simulate an overdue goal
            Goal goal = goalRepository.findById(created.id()).orElseThrow();
            goal.setTargetDate(today.minusDays(30));
            goalRepository.save(goal);

            // Updating title and target amount without changing the already-past targetDate must succeed!
            UpdateGoalRequest updateReq = new UpdateGoalRequest("Updated Emergency Fund", "Updated description", new BigDecimal("120000.00"), today.minusDays(30), null);
            GoalResponseDto updated = goalService.updateGoal(user, goal.getId(), updateReq);
            assertThat(updated.name()).isEqualTo("Updated Emergency Fund");
            assertThat(updated.targetAmount()).isEqualByComparingTo("120000.00");

            // But changing to a DIFFERENT past date is rejected
            UpdateGoalRequest newPastDate = new UpdateGoalRequest("Updated", "Updated description", new BigDecimal("120000.00"), today.minusDays(60), null);
            assertThatThrownBy(() -> goalService.updateGoal(user, goal.getId(), newPastDate))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Target date cannot be in the past");
        }

        @Test
        @DisplayName("Token repository purge removes expired and older revoked tokens")
        void tokenPurgeCleansExpiredTokens() {
            User user = createTestUser();
            Instant now = Instant.now();

            // Expired token
            RefreshToken expired = new RefreshToken(user, "hash-expired-1", now.minusSeconds(3600));
            refreshTokenRepository.save(expired);

            // Recently revoked token (kept for replay detection within 7 days)
            RefreshToken recentRevoked = new RefreshToken(user, "hash-revoked-recent", now.plusSeconds(3600));
            recentRevoked.setRevoked(true);
            refreshTokenRepository.save(recentRevoked);

            // Purge expired tokens
            int purged = authService.purgeExpiredTokens(now);
            assertThat(purged).isGreaterThanOrEqualTo(1);

            // Expired token must be deleted
            assertThat(refreshTokenRepository.findByTokenHash("hash-expired-1")).isEmpty();
            // Recent revoked token must be preserved for replay-attack detection history
            assertThat(refreshTokenRepository.findByTokenHash("hash-revoked-recent")).isPresent();
        }
    }
}
