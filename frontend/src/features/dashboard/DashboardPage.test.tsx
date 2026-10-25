import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { loadDashboardData, type DashboardData } from "./api";
import DashboardPage from "./DashboardPage";

vi.mock("./api", () => ({
  loadDashboardData: vi.fn(),
}));

vi.mock("../auth/api", () => ({
  authAPI: { logout: vi.fn().mockResolvedValue({}) },
}));

const POPULATED_DASHBOARD: DashboardData = {
  summary: {
    period: { from: "2026-03-01", to: "2026-03-31" },
    totalIncome: 120000,
    grossExpenses: 45000,
    totalRefunds: 2000,
    totalExpenses: 43000,
    netCashFlow: 77000,
    savingsRate: 64.2,
    savingsRateApplicable: true,
    savingsRateCaveat: null,
    totalInvested: 300000,
    totalCurrentValue: 345000,
    unrealizedGain: 45000,
    gainPercentage: 15.0,
    activeLoansCount: 1,
    totalMonthlyEmi: 18500,
    totalPrincipal: 100000,
    momComparison: {
      previousIncome: 100000,
      incomeChange: 20000,
      incomeChangePct: 20.0,
      previousExpenses: 50000,
      expenseChange: -7000,
      expenseChangePct: -14.0,
      previousNet: 50000,
      netChange: 27000,
    },
    excludes: [],
    dataQuality: {
      totalTransactions: 60,
      uncategorizedCount: 2,
      uncategorizedPercentage: 3.33,
      hasSufficientHistory: true,
    },
    topInsights: [],
  },
  monthly: [
    {
      month: "2026-02",
      income: 100000,
      grossExpenses: 50000,
      refunds: 0,
      expenses: 50000,
      netCashFlow: 50000,
      savingsRate: 50.0,
      transactionCount: 24,
      momExpenseChangePct: null,
      momIncomeChangePct: null,
    },
    {
      month: "2026-03",
      income: 120000,
      grossExpenses: 45000,
      refunds: 2000,
      expenses: 43000,
      netCashFlow: 77000,
      savingsRate: 64.2,
      transactionCount: 32,
      momExpenseChangePct: -14.0,
      momIncomeChangePct: 20.0,
    },
  ],
  categories: {
    period: { from: "2026-03-01", to: "2026-03-31" },
    categories: [
      {
        category: "HOUSING",
        label: "Housing",
        flow: "EXPENSE",
        amount: 25000,
        percentage: 58.14,
        transactionCount: 1,
        averageTransactionAmount: 25000,
        momChangePct: 0,
        isIncreasing: false,
        largestTransaction: null,
      },
      {
        category: "GROCERIES",
        label: "Groceries",
        flow: "EXPENSE",
        amount: 12000,
        percentage: 27.91,
        transactionCount: 8,
        averageTransactionAmount: 1500,
        momChangePct: 20.0,
        isIncreasing: true,
        largestTransaction: null,
      },
    ],
    totalGrossExpenses: 45000,
    totalRefunds: 2000,
    totalNetExpenses: 43000,
    largestCategory: "HOUSING",
    risingCategories: ["GROCERIES"],
  },
  recurring: {
    period: { from: "2026-03-01", to: "2026-03-31" },
    items: [
      {
        id: "rec-1",
        title: "Netflix",
        merchant: "Netflix",
        category: "SUBSCRIPTIONS",
        amount: 649,
        cadence: "MONTHLY",
        occurrences: 4,
        lastDate: "2026-03-15",
        confidence: "HIGH",
        isConfirmed: false,
        explanation: "Consistent ₹649 monthly subscription payment",
      },
    ],
    totalMonthlyRecurring: 649,
    recurringPercentageOfExpenses: 1.5,
  },
  dataQuality: {
    coverageFrom: "2026-01-01",
    coverageTo: "2026-03-31",
    totalDaysCovered: 90,
    totalMonthsCovered: 3,
    totalTransactions: 60,
    uncategorizedCount: 2,
    uncategorizedPercentage: 3.33,
    missingMonths: [],
    hasSufficientHistory: true,
    caveats: ["2 uncategorized transactions (₹1,500) exclude from breakdown"],
  },
  insights: {
    period: { from: "2026-03-01", to: "2026-03-31" },
    insights: [
      {
        id: "ins-1",
        title: "Strong Savings Rate: 64.2%",
        type: "POSITIVE",
        message: "You saved 64.2% of your recorded income (₹77,000 net surplus).",
        period: "Selected Period",
        supportingFigures: { savingsRate: 64.2 },
        ruleTriggered: "SAVINGS_RATE_STRONG",
        confidenceCaveat: null,
      },
      {
        id: "ins-2",
        title: "Groceries Spending Rising",
        type: "TREND",
        message: "Groceries expenses increased by 20.0% compared with prior month.",
        period: "Month over Month",
        supportingFigures: { category: "GROCERIES", momChangePct: 20.0 },
        ruleTriggered: "CATEGORY_SPENDING_SURGE",
        confidenceCaveat: "Based on available classified transactions.",
      },
    ],
    unusualTransactions: [
      {
        transactionId: "tx-outlier-1",
        title: "Croma Electronics",
        amount: 28000,
        date: "2026-03-18",
        category: "SHOPPING",
        benchmarkAmount: 3500,
        ratio: 8.0,
        reason: "Transaction amount (₹28,000) is 8.0x the median category expense (₹3,500).",
        rule: "OUTLIER_VS_MEDIAN",
      },
    ],
  },
  recentTransactions: [
    {
      id: "tx-1",
      title: "Salary Credited",
      amount: 120000,
      type: "INCOME",
      category: "INCOME",
      note: "March payout",
      date: "2026-03-01",
      source: "MANUAL",
      createdAt: "2026-03-01T10:00:00",
    },
  ],
  budgetOverview: null,
  goals: [],
};

const EMPTY_DASHBOARD: DashboardData = {
  summary: {
    period: { from: null, to: null },
    totalIncome: 0,
    grossExpenses: 0,
    totalRefunds: 0,
    totalExpenses: 0,
    netCashFlow: 0,
    savingsRate: null,
    savingsRateApplicable: false,
    savingsRateCaveat: "No income recorded in this period.",
    totalInvested: 0,
    totalCurrentValue: 0,
    unrealizedGain: 0,
    gainPercentage: 0,
    activeLoansCount: 0,
    totalMonthlyEmi: 0,
    totalPrincipal: 0,
    momComparison: null,
    excludes: [],
    dataQuality: {
      totalTransactions: 0,
      uncategorizedCount: 0,
      uncategorizedPercentage: 0,
      hasSufficientHistory: false,
    },
    topInsights: [],
  },
  monthly: [],
  categories: {
    period: { from: null, to: null },
    categories: [],
    totalGrossExpenses: 0,
    totalRefunds: 0,
    totalNetExpenses: 0,
    largestCategory: null,
    risingCategories: [],
  },
  recurring: {
    period: { from: null, to: null },
    items: [],
    totalMonthlyRecurring: 0,
    recurringPercentageOfExpenses: 0,
  },
  dataQuality: {
    coverageFrom: null,
    coverageTo: null,
    totalDaysCovered: 0,
    totalMonthsCovered: 0,
    totalTransactions: 0,
    uncategorizedCount: 0,
    uncategorizedPercentage: 0,
    missingMonths: [],
    hasSufficientHistory: false,
    caveats: ["No transactions recorded in the account yet."],
  },
  insights: {
    period: { from: null, to: null },
    insights: [],
    unusualTransactions: [],
  },
  recentTransactions: [],
  budgetOverview: null,
  goals: [],
};

function renderPage() {
  return render(
    <MemoryRouter>
      <DashboardPage />
    </MemoryRouter>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("DashboardPage (v0.6.0 Financial Intelligence)", () => {
  it("renders a spinner while loading analytics", () => {
    vi.mocked(loadDashboardData).mockImplementation(() => new Promise(() => {}));
    renderPage();
    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  it("renders an error message when backend analytics request fails", async () => {
    vi.mocked(loadDashboardData).mockRejectedValue(new Error("Network Error"));
    renderPage();

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(
      screen.getByText("Failed to load financial analytics. Is the backend running?")
    ).toBeInTheDocument();
  });

  it("renders the populated financial intelligence dashboard", async () => {
    vi.mocked(loadDashboardData).mockResolvedValue(POPULATED_DASHBOARD);
    renderPage();

    // Verify header
    expect(await screen.findByText("Financial Intelligence & Analytics")).toBeInTheDocument();

    // KPI Cards
    expect(screen.getByText("₹1,20,000")).toBeInTheDocument(); // Income
    expect(screen.getAllByText("₹43,000").length).toBeGreaterThanOrEqual(1); // Net expenses
    expect(screen.getByText("₹77,000")).toBeInTheDocument(); // Net cash flow
    expect(screen.getByText("64.2%")).toBeInTheDocument(); // Savings rate

    // Month-over-month badges
    expect(screen.getAllByText(/20\.0%/).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/14\.0%/)).toBeInTheDocument();
    expect(screen.getAllByText("vs prior period").length).toBeGreaterThanOrEqual(1);

    // Visualizations
    expect(screen.getByText("Monthly Cash Flow")).toBeInTheDocument();
    expect(screen.getByText("Spending by Category")).toBeInTheDocument();

    // Insights
    expect(screen.getByText("Strong Savings Rate: 64.2%")).toBeInTheDocument();
    expect(screen.getByText("Groceries Spending Rising")).toBeInTheDocument();

    // Recurring Commitments
    expect(screen.getByText("Recurring Commitments")).toBeInTheDocument();
    expect(screen.getByText("Netflix")).toBeInTheDocument();

    // Attention Needed / Unusual spending
    expect(screen.getByText("Attention Needed")).toBeInTheDocument();
    expect(screen.getByText("Croma Electronics")).toBeInTheDocument();
    expect(screen.getByText(/8(\.0)?x Benchmark/i)).toBeInTheDocument();

    // Data Quality & Diagnostics
    expect(screen.getByText("Data Coverage & Diagnostics")).toBeInTheDocument();
    expect(screen.getByText("90 days span")).toBeInTheDocument();

    // Recent Transactions
    expect(screen.getByText("Salary Credited")).toBeInTheDocument();
  });

  it("renders empty state when user has no transactions", async () => {
    vi.mocked(loadDashboardData).mockResolvedValue(EMPTY_DASHBOARD);
    renderPage();

    expect(await screen.findByText("No Financial Data Available")).toBeInTheDocument();
    expect(
      screen.getByText(/We need transaction history to calculate cash flow/i)
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Add Transaction/i })).toBeInTheDocument();
  });

  it("reloads analytics when date range preset button is clicked", async () => {
    vi.mocked(loadDashboardData).mockResolvedValue(POPULATED_DASHBOARD);
    renderPage();

    await screen.findByText("Financial Intelligence & Analytics");

    const thisMonthBtn = screen.getByRole("button", { name: "This Month" });
    await userEvent.click(thisMonthBtn);

    await waitFor(() => {
      expect(loadDashboardData).toHaveBeenCalledTimes(2);
    });
  });
});
