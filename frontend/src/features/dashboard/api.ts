import { analyticsAPI } from "../analytics/api";
import type {
  CategoryBreakdown,
  DataQuality,
  FinancialSummary,
  InsightsData,
  MonthlyFlow,
  RecurringExpenses,
} from "../analytics/types";
import { transactionAPI } from "../transactions/api";
import type { Transaction } from "../transactions/types";

import { budgetAPI, goalAPI } from "../budgeting/api";
import type { Goal, MonthlyBudgetOverview } from "../budgeting/types";

export interface DashboardData {
  summary: FinancialSummary;
  monthly: MonthlyFlow[];
  categories: CategoryBreakdown;
  recurring: RecurringExpenses;
  dataQuality: DataQuality;
  insights: InsightsData;
  recentTransactions: Transaction[];
  budgetOverview: MonthlyBudgetOverview | null;
  goals: Goal[];
}

const RECENT_COUNT = 5;

/**
 * Loads server-calculated financial analytics and recent ledger entries.
 * Arithmetic is strictly server-side (BigDecimal scale 2); the frontend formats only.
 */
export async function loadDashboardData(from?: string, to?: string): Promise<DashboardData> {
  const [summary, monthly, categories, recurring, dataQuality, insights, transactions, budgetOverview, goals] =
    await Promise.all([
      analyticsAPI.getSummary(from, to),
      analyticsAPI.getMonthly(from, to),
      analyticsAPI.getCategories(from, to),
      analyticsAPI.getRecurring(from, to),
      analyticsAPI.getDataQuality(from, to),
      analyticsAPI.getInsights(from, to),
      transactionAPI.getAll(),
      budgetAPI.getOverview().catch(() => null),
      goalAPI.listGoals().catch(() => []),
    ]);

  return {
    summary,
    monthly,
    categories,
    recurring,
    dataQuality,
    insights,
    recentTransactions: transactions.slice(0, RECENT_COUNT),
    budgetOverview,
    goals,
  };
}
