import type { components } from "../../shared/api/generated/openapi";

/** Budgeting and Goals DTOs generated from contracts/openapi/finguard-api.v1.json. */
export type Budget = components["schemas"]["BudgetDto"];
export type CreateBudgetRequest = components["schemas"]["CreateBudgetRequest"];
export type UpdateBudgetRequest = components["schemas"]["UpdateBudgetRequest"];
export type CategoryBudgetPerformance = components["schemas"]["CategoryBudgetPerformanceDto"];
export type BudgetPerformanceSummary = components["schemas"]["BudgetPerformanceSummaryDto"];
export type BudgetStatus = BudgetPerformanceSummary["status"];
export type MonthlyBudgetOverview = components["schemas"]["MonthlyBudgetOverviewResponse"];

export type GoalStatus = components["schemas"]["GoalStatus"];
export type Goal = components["schemas"]["GoalResponseDto"];
export type GoalContribution = components["schemas"]["GoalContributionDto"];
export type GoalDetail = components["schemas"]["GoalDetailResponse"];
export type CreateGoalRequest = components["schemas"]["CreateGoalRequest"];
export type UpdateGoalRequest = components["schemas"]["UpdateGoalRequest"];
export type GoalContributionRequest = components["schemas"]["GoalContributionRequest"];
