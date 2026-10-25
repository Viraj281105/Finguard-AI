package com.finguard.backend.budgeting.web;

import com.finguard.backend.budgeting.domain.GoalStatus;
import com.finguard.backend.budgeting.dto.*;
import com.finguard.backend.budgeting.service.GoalService;
import com.finguard.backend.identity.domain.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;

    @PostMapping
    public ResponseEntity<GoalResponseDto> createGoal(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateGoalRequest request
    ) {
        GoalResponseDto created = goalService.createGoal(user, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<GoalResponseDto>> listGoals(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) GoalStatus status
    ) {
        return ResponseEntity.ok(goalService.listGoals(user, status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GoalDetailResponse> getGoalDetail(
            @AuthenticationPrincipal User user,
            @PathVariable String id
    ) {
        return ResponseEntity.ok(goalService.getGoalDetail(user, id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<GoalResponseDto> updateGoal(
            @AuthenticationPrincipal User user,
            @PathVariable String id,
            @Valid @RequestBody UpdateGoalRequest request
    ) {
        return ResponseEntity.ok(goalService.updateGoal(user, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGoal(
            @AuthenticationPrincipal User user,
            @PathVariable String id
    ) {
        goalService.deleteGoal(user, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/contributions")
    public ResponseEntity<GoalContributionDto> recordContribution(
            @AuthenticationPrincipal User user,
            @PathVariable String id,
            @Valid @RequestBody GoalContributionRequest request
    ) {
        GoalContributionDto contrib = goalService.recordContribution(user, id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(contrib);
    }

    @DeleteMapping("/{goalId}/contributions/{contributionId}")
    public ResponseEntity<Void> deleteContribution(
            @AuthenticationPrincipal User user,
            @PathVariable String goalId,
            @PathVariable String contributionId
    ) {
        goalService.deleteContribution(user, goalId, contributionId);
        return ResponseEntity.noContent().build();
    }
}
