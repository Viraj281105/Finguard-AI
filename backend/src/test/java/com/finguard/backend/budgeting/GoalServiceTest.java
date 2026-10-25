package com.finguard.backend.budgeting;

import com.finguard.backend.budgeting.domain.Goal;
import com.finguard.backend.budgeting.domain.GoalContribution;
import com.finguard.backend.budgeting.domain.GoalContributionRepository;
import com.finguard.backend.budgeting.domain.GoalRepository;
import com.finguard.backend.budgeting.domain.GoalStatus;
import com.finguard.backend.budgeting.dto.*;
import com.finguard.backend.budgeting.service.GoalService;
import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.identity.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    @Mock
    private GoalRepository goalRepository;

    @Mock
    private GoalContributionRepository goalContributionRepository;

    private GoalService goalService;
    private User testUser;

    @BeforeEach
    void setUp() {
        goalService = new GoalService(goalRepository, goalContributionRepository);

        testUser = new User();
        testUser.setId("usr-100");
        testUser.setName("Tester");
        testUser.setEmail("tester@example.com");
    }

    @Test
    @DisplayName("Creates goal with target amount successfully")
    void createGoal_success() {
        CreateGoalRequest request = new CreateGoalRequest(
                "Emergency Fund",
                "3 months buffer",
                BigDecimal.valueOf(100000),
                LocalDate.now().plusMonths(6)
        );

        when(goalRepository.save(any(Goal.class))).thenAnswer(inv -> {
            Goal g = inv.getArgument(0);
            g.setId("g-1");
            return g;
        });

        GoalResponseDto dto = goalService.createGoal(testUser, request);

        assertThat(dto.id()).isEqualTo("g-1");
        assertThat(dto.name()).isEqualTo("Emergency Fund");
        assertThat(dto.targetAmount()).isEqualByComparingTo("100000.00");
        assertThat(dto.currentAmount()).isEqualByComparingTo("0.00");
        assertThat(dto.status()).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    @DisplayName("Rejects goal with zero or negative target amount")
    void createGoal_invalidTargetAmount() {
        CreateGoalRequest request = new CreateGoalRequest("Invalid", null, BigDecimal.ZERO, null);

        assertThatThrownBy(() -> goalService.createGoal(testUser, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("targetAmount must be greater than zero")
                .matches(e -> ((ApiException) e).getStatus() == HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Records contribution and marks goal COMPLETED when target is reached")
    void recordContribution_reachesTarget() {
        Goal goal = new Goal();
        goal.setId("g-2");
        goal.setUser(testUser);
        goal.setName("Vacation");
        goal.setTargetAmount(BigDecimal.valueOf(50000));
        goal.setCurrentAmount(BigDecimal.valueOf(45000));
        goal.setStatus(GoalStatus.ACTIVE);

        when(goalRepository.findByIdAndUserIdForUpdate("g-2", "usr-100")).thenReturn(Optional.of(goal));
        when(goalContributionRepository.save(any(GoalContribution.class))).thenAnswer(inv -> {
            GoalContribution c = inv.getArgument(0);
            c.setId("c-1");
            return c;
        });

        GoalContributionRequest req = new GoalContributionRequest(
                BigDecimal.valueOf(5000),
                LocalDate.now(),
                "Final savings push"
        );

        GoalContributionDto contribDto = goalService.recordContribution(testUser, "g-2", req);

        assertThat(contribDto.amount()).isEqualByComparingTo("5000.00");
        assertThat(goal.getCurrentAmount()).isEqualByComparingTo("50000.00");
        assertThat(goal.getStatus()).isEqualTo(GoalStatus.COMPLETED);
    }

    @Test
    @DisplayName("Deleting contribution reduces currentAmount and reverts COMPLETED status to ACTIVE")
    void deleteContribution_revertsStatus() {
        Goal goal = new Goal();
        goal.setId("g-3");
        goal.setUser(testUser);
        goal.setName("Laptop");
        goal.setTargetAmount(BigDecimal.valueOf(80000));
        goal.setCurrentAmount(BigDecimal.valueOf(80000));
        goal.setStatus(GoalStatus.COMPLETED);

        GoalContribution contrib = new GoalContribution();
        contrib.setId("c-2");
        contrib.setGoal(goal);
        contrib.setUser(testUser);
        contrib.setAmount(BigDecimal.valueOf(10000));

        when(goalRepository.findByIdAndUserIdForUpdate("g-3", "usr-100")).thenReturn(Optional.of(goal));
        when(goalContributionRepository.findByIdAndUserId("c-2", "usr-100")).thenReturn(Optional.of(contrib));

        goalService.deleteContribution(testUser, "g-3", "c-2");

        assertThat(goal.getCurrentAmount()).isEqualByComparingTo("70000.00");
        assertThat(goal.getStatus()).isEqualTo(GoalStatus.ACTIVE);
        verify(goalContributionRepository).delete(contrib);
    }

    @Test
    @DisplayName("Computes required monthly pace and overdue flag for target dates")
    void goalDetail_targetDateCalculations() {
        Goal goal = new Goal();
        goal.setId("g-4");
        goal.setUser(testUser);
        goal.setName("Home Downpayment");
        goal.setTargetAmount(BigDecimal.valueOf(300000));
        goal.setCurrentAmount(BigDecimal.valueOf(100000));
        // Target date 3 months in future
        goal.setTargetDate(LocalDate.now().plusDays(90));
        goal.setStatus(GoalStatus.ACTIVE);

        when(goalRepository.findByIdAndUserId("g-4", "usr-100")).thenReturn(Optional.of(goal));
        when(goalContributionRepository.findByGoalIdAndUserIdOrderByContributionDateDescCreatedAtDesc("g-4", "usr-100"))
                .thenReturn(List.of());

        GoalDetailResponse response = goalService.getGoalDetail(testUser, "g-4");

        assertThat(response.goal().remainingAmount()).isEqualByComparingTo("200000.00");
        assertThat(response.goal().progressPercentage()).isEqualByComparingTo("33.33");
        assertThat(response.goal().daysRemaining()).isGreaterThan(85L);
        assertThat(response.goal().isOverdue()).isFalse();
        assertThat(response.goal().requiredMonthlyPace()).isNotNull();
        assertThat(response.disclaimer()).isEqualTo(GoalService.GOAL_DISCLAIMER);
    }

    @Test
    @DisplayName("Enforces user isolation when accessing goals and contributions")
    void ownershipIsolation_throwsNotFound() {
        when(goalRepository.findByIdAndUserId("g-other", "usr-100")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> goalService.getGoalDetail(testUser, "g-other"))
                .isInstanceOf(ApiException.class)
                .matches(e -> ((ApiException) e).getStatus() == HttpStatus.NOT_FOUND);
    }
}
