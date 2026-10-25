package com.finguard.backend.budgeting.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GoalContributionRepository extends JpaRepository<GoalContribution, String> {
    List<GoalContribution> findByGoalIdAndUserIdOrderByContributionDateDescCreatedAtDesc(String goalId, String userId);
    Optional<GoalContribution> findByIdAndUserId(String id, String userId);
    List<GoalContribution> findByGoalId(String goalId);
}
