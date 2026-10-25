package com.finguard.backend.budgeting.dto;

import java.util.List;

public record GoalDetailResponse(
        GoalResponseDto goal,
        List<GoalContributionDto> contributions,
        String disclaimer
) {}
