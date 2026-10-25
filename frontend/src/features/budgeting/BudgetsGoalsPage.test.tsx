import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { budgetAPI, goalAPI } from "./api";
import BudgetsGoalsPage from "./BudgetsGoalsPage";
import type { MonthlyBudgetOverview, Goal } from "./types";

vi.mock("./api", () => ({
  budgetAPI: {
    getOverview: vi.fn(),
    listBudgets: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  },
  goalAPI: {
    listGoals: vi.fn(),
    getDetail: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    addContribution: vi.fn(),
    deleteContribution: vi.fn(),
  },
}));

vi.mock("../auth/api", () => ({
  authAPI: { logout: vi.fn().mockResolvedValue({}) },
}));

const MOCK_BUDGET_OVERVIEW: MonthlyBudgetOverview = {
  yearMonth: "2026-10",
  overallBudget: {
    budgetId: "b-overall",
    budgetedAmount: 50000,
    actualSpend: 32000,
    remainingAmount: 18000,
    percentageUsed: 64.0,
    status: "UNDER_BUDGET",
  },
  categoryBudgets: [
    {
      budgetId: "b-dining",
      category: "DINING",
      categoryLabel: "Dining & Food",
      budgetedAmount: 10000,
      actualSpend: 8500,
      remainingAmount: 1500,
      percentageUsed: 85.0,
      status: "APPROACHING_LIMIT",
      transactionCount: 14,
    },
    {
      budgetId: "b-groceries",
      category: "GROCERIES",
      categoryLabel: "Groceries",
      budgetedAmount: 12000,
      actualSpend: 13500,
      remainingAmount: -1500,
      percentageUsed: 112.5,
      status: "OVER_BUDGET",
      transactionCount: 8,
    },
  ],
  totalCategoryBudgeted: 22000,
  totalActualExpenses: 32000,
  unbudgetedSpending: 10000,
  categoriesOverBudgetCount: 1,
  categoriesApproachingCount: 1,
  warnings: ["1 category has exceeded the monthly limit."],
};

const MOCK_GOALS: Goal[] = [
  {
    id: "g-1",
    name: "Emergency Fund",
    description: "6 months buffer",
    targetAmount: 200000,
    currentAmount: 120000,
    remainingAmount: 80000,
    progressPercentage: 60.0,
    targetDate: "2026-12-31",
    daysRemaining: 68,
    requiredMonthlyPace: 35820.9,
    status: "ACTIVE",
    isOverdue: false,
    contributionCount: 5,
    createdAt: "2026-01-01T00:00:00",
    updatedAt: "2026-10-20T00:00:00",
  },
];

function renderPage() {
  return render(
    <MemoryRouter>
      <BudgetsGoalsPage />
    </MemoryRouter>
  );
}

describe("BudgetsGoalsPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders page header, tabs and loads budget overview", async () => {
    vi.mocked(budgetAPI.getOverview).mockResolvedValue(MOCK_BUDGET_OVERVIEW);
    vi.mocked(goalAPI.listGoals).mockResolvedValue(MOCK_GOALS);

    renderPage();

    // Verify Title
    expect(await screen.findByText("Budgets & Financial Goals")).toBeInTheDocument();

    // Verify Overall Budget Card
    expect(screen.getByText("Overall Monthly Budget")).toBeInTheDocument();
    expect(screen.getAllByText("₹50,000").length).toBeGreaterThanOrEqual(1); // Budget limit
    expect(screen.getAllByText("₹32,000").length).toBeGreaterThanOrEqual(1); // Actual Outflow
    expect(screen.getByText("64.0% utilized")).toBeInTheDocument();

    // Verify Category Budgets
    expect(screen.getByText("Dining & Food")).toBeInTheDocument();
    expect(screen.getByText("Groceries")).toBeInTheDocument();
    expect(screen.getByText("Over Budget")).toBeInTheDocument();
    expect(screen.getByText("Approaching Limit")).toBeInTheDocument();

    // Contextual warning
    expect(screen.getByText(/1 category has exceeded the monthly limit/)).toBeInTheDocument();
  });

  it("switches to Financial Goals tab and displays active goals with disclaimer", async () => {
    vi.mocked(budgetAPI.getOverview).mockResolvedValue(MOCK_BUDGET_OVERVIEW);
    vi.mocked(goalAPI.listGoals).mockResolvedValue(MOCK_GOALS);

    renderPage();

    await screen.findByText("Budgets & Financial Goals");

    // Click Goals Tab
    const user = userEvent.setup();
    const goalsTabBtn = screen.getByRole("button", { name: "Financial Goals" });
    await user.click(goalsTabBtn);

    // Verify Goal Card content
    expect(await screen.findByText("Emergency Fund")).toBeInTheDocument();
    expect(screen.getByText("60.0% saved")).toBeInTheDocument();
    expect(screen.getByText("₹1,20,000")).toBeInTheDocument();
    expect(screen.getByText("₹2,00,000")).toBeInTheDocument();

    // Verify Personal Finance Disclaimer
    expect(
      screen.getByText(/Goal contributions are tracked for planning purposes and do not represent verified bank balances/)
    ).toBeInTheDocument();
  });

  it("renders empty state when user has no budgets configured for a month", async () => {
    const emptyOverview: MonthlyBudgetOverview = {
      yearMonth: "2026-11",
      overallBudget: null,
      categoryBudgets: [],
      totalCategoryBudgeted: 0,
      totalActualExpenses: 0,
      unbudgetedSpending: 0,
      categoriesOverBudgetCount: 0,
      categoriesApproachingCount: 0,
      warnings: ["Future budget period. Spending will be tracked as transactions are recorded for this month."],
    };

    vi.mocked(budgetAPI.getOverview).mockResolvedValue(emptyOverview);
    vi.mocked(goalAPI.listGoals).mockResolvedValue([]);

    renderPage();

    expect(await screen.findByText(/No category budgets configured for/)).toBeInTheDocument();
    expect(screen.getByText("+ Set Overall Budget")).toBeInTheDocument();
  });
});
