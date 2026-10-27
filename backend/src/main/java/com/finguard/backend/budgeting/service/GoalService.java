package com.finguard.backend.budgeting.service;

import com.finguard.backend.budgeting.domain.Goal;
import com.finguard.backend.budgeting.domain.GoalContribution;
import com.finguard.backend.budgeting.domain.GoalContributionRepository;
import com.finguard.backend.budgeting.domain.GoalRepository;
import com.finguard.backend.budgeting.domain.GoalStatus;
import com.finguard.backend.budgeting.dto.*;
import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.identity.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
public class GoalService {

    public static final String GOAL_DISCLAIMER =
            "Goal contributions are tracked for planning purposes and do not represent verified bank balances or automated account transfers.";

    private final GoalRepository goalRepository;
    private final GoalContributionRepository goalContributionRepository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public GoalService(GoalRepository goalRepository, GoalContributionRepository goalContributionRepository, Clock clock) {
        this.goalRepository = goalRepository;
        this.goalContributionRepository = goalContributionRepository;
        this.clock = clock;
    }

    public GoalService(GoalRepository goalRepository, GoalContributionRepository goalContributionRepository) {
        this(goalRepository, goalContributionRepository, Clock.system(java.time.ZoneId.of("Asia/Kolkata")));
    }

    @Transactional
    public GoalResponseDto createGoal(User user, CreateGoalRequest request) {
        if (request.targetAmount() == null || request.targetAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("targetAmount must be greater than zero");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("Goal name is required");
        }

        if (request.targetDate() != null && request.targetDate().isBefore(LocalDate.now(clock))) {
            throw ApiException.badRequest("Target date cannot be in the past");
        }

        Goal goal = new Goal();
        goal.setUser(user);
        goal.setName(request.name().trim());
        goal.setDescription(request.description());
        goal.setTargetAmount(request.targetAmount().setScale(2, RoundingMode.HALF_UP));
        goal.setCurrentAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        goal.setTargetDate(request.targetDate());
        goal.setStatus(GoalStatus.ACTIVE);

        Goal saved = goalRepository.save(goal);
        return mapToDto(saved, 0);
    }

    @Transactional
    public GoalResponseDto updateGoal(User user, String id, UpdateGoalRequest request) {
        Goal goal = goalRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> ApiException.notFound("Goal not found: " + id));

        if (request.name() != null && !request.name().isBlank()) {
            goal.setName(request.name().trim());
        }
        if (request.description() != null) {
            goal.setDescription(request.description());
        }
        if (request.targetAmount() != null) {
            if (request.targetAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw ApiException.badRequest("targetAmount must be greater than zero");
            }
            goal.setTargetAmount(request.targetAmount().setScale(2, RoundingMode.HALF_UP));
            // Update status if target reached
            if (goal.getCurrentAmount().compareTo(goal.getTargetAmount()) >= 0 && goal.getStatus() == GoalStatus.ACTIVE) {
                goal.setStatus(GoalStatus.COMPLETED);
            } else if (goal.getCurrentAmount().compareTo(goal.getTargetAmount()) < 0 && goal.getStatus() == GoalStatus.COMPLETED) {
                goal.setStatus(GoalStatus.ACTIVE);
            }
        }
        if (request.targetDate() != null) {
            if (!request.targetDate().equals(goal.getTargetDate()) && request.targetDate().isBefore(LocalDate.now(clock))) {
                throw ApiException.badRequest("Target date cannot be in the past");
            }
            goal.setTargetDate(request.targetDate());
        }
        if (request.status() != null) {
            goal.setStatus(request.status());
        }

        Goal updated = goalRepository.save(goal);
        int contribCount = goalContributionRepository.findByGoalIdAndUserIdOrderByContributionDateDescCreatedAtDesc(id, user.getId()).size();
        return mapToDto(updated, contribCount);
    }

    @Transactional
    public void deleteGoal(User user, String id) {
        Goal goal = goalRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> ApiException.notFound("Goal not found: " + id));
        goalRepository.delete(goal);
    }

    @Transactional(readOnly = true)
    public List<GoalResponseDto> listGoals(User user, GoalStatus statusFilter) {
        List<Goal> goals;
        if (statusFilter != null) {
            goals = goalRepository.findByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), statusFilter);
        } else {
            goals = goalRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        }

        return goals.stream().map(g -> {
            int contribCount = goalContributionRepository.findByGoalIdAndUserIdOrderByContributionDateDescCreatedAtDesc(g.getId(), user.getId()).size();
            return mapToDto(g, contribCount);
        }).toList();
    }

    @Transactional(readOnly = true)
    public GoalDetailResponse getGoalDetail(User user, String id) {
        Goal goal = goalRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> ApiException.notFound("Goal not found: " + id));

        List<GoalContribution> contributions = goalContributionRepository
                .findByGoalIdAndUserIdOrderByContributionDateDescCreatedAtDesc(id, user.getId());

        List<GoalContributionDto> contribDtos = contributions.stream()
                .map(this::mapContributionToDto)
                .toList();

        GoalResponseDto goalDto = mapToDto(goal, contributions.size());
        return new GoalDetailResponse(goalDto, contribDtos, GOAL_DISCLAIMER);
    }

    @Transactional
    public GoalContributionDto recordContribution(User user, String goalId, GoalContributionRequest request) {
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("Contribution amount must be greater than zero");
        }

        // Row lock on goal to ensure concurrency safety
        Goal goal = goalRepository.findByIdAndUserIdForUpdate(goalId, user.getId())
                .orElseThrow(() -> ApiException.notFound("Goal not found: " + goalId));

        if (goal.getStatus() == GoalStatus.ARCHIVED) {
            throw ApiException.badRequest("Cannot add contributions to an archived goal.");
        }

        LocalDate contribDate = request.contributionDate() != null ? request.contributionDate() : LocalDate.now(clock);

        GoalContribution contribution = new GoalContribution();
        contribution.setGoal(goal);
        contribution.setUser(user);
        contribution.setAmount(request.amount().setScale(2, RoundingMode.HALF_UP));
        contribution.setContributionDate(contribDate);
        contribution.setNote(request.note());

        GoalContribution saved = goalContributionRepository.save(contribution);

        // Update goal current amount
        BigDecimal newTotal = goal.getCurrentAmount().add(saved.getAmount()).setScale(2, RoundingMode.HALF_UP);
        goal.setCurrentAmount(newTotal);

        if (newTotal.compareTo(goal.getTargetAmount()) >= 0 && goal.getStatus() == GoalStatus.ACTIVE) {
            goal.setStatus(GoalStatus.COMPLETED);
            log.info("Goal {} ({}) reached target amount of {}", goal.getId(), goal.getName(), goal.getTargetAmount());
        }

        goalRepository.save(goal);
        return mapContributionToDto(saved);
    }

    @Transactional
    public void deleteContribution(User user, String goalId, String contributionId) {
        Goal goal = goalRepository.findByIdAndUserIdForUpdate(goalId, user.getId())
                .orElseThrow(() -> ApiException.notFound("Goal not found: " + goalId));

        GoalContribution contribution = goalContributionRepository.findByIdAndUserId(contributionId, user.getId())
                .orElseThrow(() -> ApiException.notFound("Contribution not found: " + contributionId));

        if (!contribution.getGoal().getId().equals(goalId)) {
            throw ApiException.notFound("Contribution does not belong to goal: " + goalId);
        }

        BigDecimal newAmount = goal.getCurrentAmount().subtract(contribution.getAmount());
        if (newAmount.compareTo(BigDecimal.ZERO) < 0) {
            newAmount = BigDecimal.ZERO;
        }
        goal.setCurrentAmount(newAmount.setScale(2, RoundingMode.HALF_UP));

        if (goal.getCurrentAmount().compareTo(goal.getTargetAmount()) < 0 && goal.getStatus() == GoalStatus.COMPLETED) {
            goal.setStatus(GoalStatus.ACTIVE);
        }

        goalRepository.save(goal);
        goalContributionRepository.delete(contribution);
    }

    private GoalResponseDto mapToDto(Goal g, int contributionCount) {
        BigDecimal target = g.getTargetAmount();
        BigDecimal current = g.getCurrentAmount() != null ? g.getCurrentAmount() : BigDecimal.ZERO;

        BigDecimal remaining = target.subtract(current);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) {
            remaining = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        } else {
            remaining = remaining.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal progress = target.compareTo(BigDecimal.ZERO) > 0
                ? current.multiply(BigDecimal.valueOf(100)).divide(target, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        LocalDate today = LocalDate.now(clock);
        Long daysRemaining = null;
        boolean isOverdue = false;
        BigDecimal requiredMonthlyPace = null;

        if (g.getTargetDate() != null) {
            daysRemaining = ChronoUnit.DAYS.between(today, g.getTargetDate());
            if (daysRemaining < 0 && current.compareTo(target) < 0) {
                isOverdue = true;
            } else if (daysRemaining > 0 && remaining.compareTo(BigDecimal.ZERO) > 0) {
                double monthsRemaining = Math.max(1.0, daysRemaining / 30.4167);
                requiredMonthlyPace = remaining.divide(BigDecimal.valueOf(monthsRemaining), 2, RoundingMode.HALF_UP);
            }
        }

        return new GoalResponseDto(
                g.getId(),
                g.getName(),
                g.getDescription(),
                target,
                current,
                remaining,
                progress,
                g.getTargetDate(),
                daysRemaining,
                requiredMonthlyPace,
                g.getStatus(),
                isOverdue,
                contributionCount,
                g.getCreatedAt(),
                g.getUpdatedAt()
        );
    }

    private GoalContributionDto mapContributionToDto(GoalContribution c) {
        return new GoalContributionDto(
                c.getId(),
                c.getGoal().getId(),
                c.getAmount(),
                c.getContributionDate(),
                c.getNote(),
                c.getCreatedAt()
        );
    }
}
