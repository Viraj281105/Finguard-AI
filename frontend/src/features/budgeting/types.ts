export interface Budget {
  id: string;
  yearMonth: string;
  category: string | null;
  amount: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreateBudgetRequest {
  yearMonth: string;
  category?: string | null;
  amount: number;
}

export interface UpdateBudgetRequest {
  amount: number;
}

export type BudgetStatus = "UNDER_BUDGET" | "APPROACHING_LIMIT" | "OVER_BUDGET";

export interface CategoryBudgetPerformance {
  budgetId: string;
  category: string;
  categoryLabel: string;
  budgetedAmount: number;
  actualSpend: number;
  remainingAmount: number;
  percentageUsed: number;
  status: BudgetStatus;
  transactionCount: number;
}

export interface BudgetPerformanceSummary {
  budgetId: string;
  budgetedAmount: number;
  actualSpend: number;
  remainingAmount: number;
  percentageUsed: number;
  status: BudgetStatus;
}

export interface MonthlyBudgetOverview {
  yearMonth: string;
  overallBudget: BudgetPerformanceSummary | null;
  categoryBudgets: CategoryBudgetPerformance[];
  totalCategoryBudgeted: number;
  totalActualExpenses: number;
  unbudgetedSpending: number;
  categoriesOverBudgetCount: number;
  categoriesApproachingCount: number;
  warnings: string[];
}

export type GoalStatus = "ACTIVE" | "COMPLETED" | "ARCHIVED";

export interface Goal {
  id: string;
  name: string;
  description?: string;
  targetAmount: number;
  currentAmount: number;
  remainingAmount: number;
  progressPercentage: number;
  targetDate?: string;
  daysRemaining?: number;
  requiredMonthlyPace?: number;
  status: GoalStatus;
  isOverdue: boolean;
  contributionCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface GoalContribution {
  id: string;
  goalId: string;
  amount: number;
  contributionDate: string;
  note?: string;
  createdAt: string;
}

export interface GoalDetail {
  goal: Goal;
  contributions: GoalContribution[];
  disclaimer: string;
}

export interface CreateGoalRequest {
  name: string;
  description?: string;
  targetAmount: number;
  targetDate?: string;
}

export interface UpdateGoalRequest {
  name?: string;
  description?: string;
  targetAmount?: number;
  targetDate?: string;
  status?: GoalStatus;
}

export interface GoalContributionRequest {
  amount: number;
  contributionDate?: string;
  note?: string;
}
