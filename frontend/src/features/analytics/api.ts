import { apiClient } from "../../shared/api/client";
import type {
  CategoryBreakdown,
  DataQuality,
  FinancialSummary,
  InsightsData,
  MonthlyFlow,
  RecurringExpenses,
} from "./types";

export const analyticsAPI = {
  async getSummary(from?: string, to?: string): Promise<FinancialSummary> {
    const res = await apiClient.get<FinancialSummary>("/analytics/summary", {
      params: { from: from || undefined, to: to || undefined },
    });
    return res.data;
  },

  async getMonthly(from?: string, to?: string): Promise<MonthlyFlow[]> {
    const res = await apiClient.get<MonthlyFlow[]>("/analytics/monthly", {
      params: { from: from || undefined, to: to || undefined },
    });
    return res.data;
  },

  async getCategories(from?: string, to?: string): Promise<CategoryBreakdown> {
    const res = await apiClient.get<CategoryBreakdown>("/analytics/categories", {
      params: { from: from || undefined, to: to || undefined },
    });
    return res.data;
  },

  async getRecurring(from?: string, to?: string): Promise<RecurringExpenses> {
    const res = await apiClient.get<RecurringExpenses>("/analytics/recurring", {
      params: { from: from || undefined, to: to || undefined },
    });
    return res.data;
  },

  async getDataQuality(from?: string, to?: string): Promise<DataQuality> {
    const res = await apiClient.get<DataQuality>("/analytics/data-quality", {
      params: { from: from || undefined, to: to || undefined },
    });
    return res.data;
  },

  async getInsights(from?: string, to?: string): Promise<InsightsData> {
    const res = await apiClient.get<InsightsData>("/analytics/insights", {
      params: { from: from || undefined, to: to || undefined },
    });
    return res.data;
  },
};
