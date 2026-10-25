import { apiClient } from "../../shared/api/client";
import type {
  Budget,
  CreateBudgetRequest,
  CreateGoalRequest,
  Goal,
  GoalContribution,
  GoalContributionRequest,
  GoalDetail,
  GoalStatus,
  MonthlyBudgetOverview,
  UpdateBudgetRequest,
  UpdateGoalRequest,
} from "./types";

export const budgetAPI = {
  getOverview: async (month?: string): Promise<MonthlyBudgetOverview> => {
    const params = month ? { month } : {};
    const res = await apiClient.get<MonthlyBudgetOverview>("/budgets/overview", { params });
    return res.data;
  },

  listBudgets: async (month?: string): Promise<Budget[]> => {
    const params = month ? { month } : {};
    const res = await apiClient.get<Budget[]>("/budgets", { params });
    return res.data;
  },

  create: async (request: CreateBudgetRequest): Promise<Budget> => {
    const res = await apiClient.post<Budget>("/budgets", request);
    return res.data;
  },

  update: async (id: string, request: UpdateBudgetRequest): Promise<Budget> => {
    const res = await apiClient.put<Budget>(`/budgets/${id}`, request);
    return res.data;
  },

  delete: async (id: string): Promise<void> => {
    await apiClient.delete(`/budgets/${id}`);
  },
};

export const goalAPI = {
  listGoals: async (status?: GoalStatus): Promise<Goal[]> => {
    const params = status ? { status } : {};
    const res = await apiClient.get<Goal[]>("/goals", { params });
    return res.data;
  },

  getDetail: async (id: string): Promise<GoalDetail> => {
    const res = await apiClient.get<GoalDetail>(`/goals/${id}`);
    return res.data;
  },

  create: async (request: CreateGoalRequest): Promise<Goal> => {
    const res = await apiClient.post<Goal>("/goals", request);
    return res.data;
  },

  update: async (id: string, request: UpdateGoalRequest): Promise<Goal> => {
    const res = await apiClient.put<Goal>(`/goals/${id}`, request);
    return res.data;
  },

  delete: async (id: string): Promise<void> => {
    await apiClient.delete(`/goals/${id}`);
  },

  addContribution: async (goalId: string, request: GoalContributionRequest): Promise<GoalContribution> => {
    const res = await apiClient.post<GoalContribution>(`/goals/${goalId}/contributions`, request);
    return res.data;
  },

  deleteContribution: async (goalId: string, contributionId: string): Promise<void> => {
    await apiClient.delete(`/goals/${goalId}/contributions/${contributionId}`);
  },
};
