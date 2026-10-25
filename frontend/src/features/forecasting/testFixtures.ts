/** SYNTHETIC API responses for the forecasting tests. None of this is real user data. */
import type {
  BacktestSummary,
  BudgetOutlook,
  CashFlowForecast,
  CategoryForecastResponse,
  HistoryInfo,
  SavingsProjection,
  ScenarioResult,
} from "./types";

export const HISTORY: HistoryInfo = {
  from: "2026-01", to: "2026-06", monthsUsed: 6, minMonthsLimited: 3, minMonthsReliable: 6,
  missingMonths: [], transactionsUsed: 36, firstMonthPossiblyPartial: false,
};

const observed = (month: string, income: number, expenses: number) => ({ month, income, expenses, net: income - expenses });

export const CASH_FLOW: CashFlowForecast = {
  reliability: "RELIABLE",
  method: "LEVEL_DAMPED_TREND",
  algorithmVersion: "2.0.0",
  asOf: "2026-07-15",
  horizonMonths: 3,
  history: HISTORY,
  warnings: [],
  assumptions: ["Only complete calendar months are used.", "Seasonality is not modelled (it needs at least two full years of history)."],
  intervalNote: "Bands (when shown) span the 10th to 90th percentile of this method's own past forecast errors. They are descriptive, not a calibrated probability.",
  incomeAvailable: true,
  historical: [
    observed("2026-04", 50000, 30000), observed("2026-05", 50000, 31000), observed("2026-06", 50000, 29000),
  ],
  currentMonth: { month: "2026-07", income: 50000, expenses: 5000, net: 45000, note: "Recorded so far this month. Not used to train the forecast because the month is incomplete." },
  projected: [
    {
      month: "2026-08",
      income: { point: 50000, lower: null, upper: null },
      expenses: { point: 30000, lower: 28000, upper: 32500 },
      net: { point: 20000, lower: null, upper: null },
      recurringFloorApplied: false,
      factors: [],
    },
    {
      month: "2026-09",
      income: { point: 50000, lower: null, upper: null },
      expenses: { point: 30000, lower: null, upper: null },
      net: { point: 20000, lower: null, upper: null },
      recurringFloorApplied: false,
      factors: ["No uncertainty band: only 2 past forecast(s) at this distance (8 needed)."],
    },
    {
      month: "2026-10",
      income: { point: 50000, lower: null, upper: null },
      expenses: { point: 30000, lower: null, upper: null },
      net: { point: 20000, lower: null, upper: null },
      recurringFloorApplied: false,
      factors: [],
    },
  ],
  summary: { totalIncome: 150000, totalExpenses: 90000, totalNet: 60000, averageMonthlyNet: 20000, savingsRatePercent: 40 },
  recurring: [
    { id: "rec-1", title: "Netflix subscription", category: "SUBSCRIPTIONS", monthlyAmount: 500, cadence: "MONTHLY", confidence: "HIGH", userConfirmed: false, includedInBaseline: true },
  ],
};

export const UNAVAILABLE_CASH_FLOW: CashFlowForecast = {
  ...CASH_FLOW,
  reliability: "UNAVAILABLE",
  history: { ...HISTORY, monthsUsed: 2, from: "2026-05", to: "2026-06", missingMonths: [] },
  warnings: ["At least 3 complete months with income or spending are needed for a forecast; only 2 found. Nothing is projected rather than guessing."],
  incomeAvailable: false,
  currentMonth: null,
  projected: [],
  summary: null,
  recurring: [],
  historical: [observed("2026-05", 50000, 30000), observed("2026-06", 50000, 30000)],
};

export const NO_INCOME_CASH_FLOW: CashFlowForecast = {
  ...CASH_FLOW,
  reliability: "LIMITED",
  incomeAvailable: false,
  warnings: ["Income was recorded in 0 of 6 usable months (at least 3 needed). Income and net cash flow are not projected; spending is."],
  projected: CASH_FLOW.projected.map((p) => ({ ...p, income: null, net: null })),
  summary: { totalIncome: null, totalExpenses: 90000, totalNet: null, averageMonthlyNet: null, savingsRatePercent: null },
};

export const LIMITED_CASH_FLOW: CashFlowForecast = {
  ...CASH_FLOW,
  reliability: "LIMITED",
  history: { ...HISTORY, monthsUsed: 5, missingMonths: ["2026-03"] },
  warnings: ["No usable data for 1 month(s) between 2026-01 and 2026-06 (2026-03). These are treated as unknown, not as zero; the data may be an incomplete import."],
};

export const CATEGORIES: CategoryForecastResponse = {
  reliability: "RELIABLE",
  method: "LEVEL_DAMPED_TREND",
  algorithmVersion: "2.0.0",
  asOf: "2026-07-15",
  horizonMonths: 3,
  history: HISTORY,
  warnings: [],
  assumptions: ["Only complete calendar months are used."],
  categories: [
    {
      category: "HOUSING", label: "Housing", activeMonths: 6, recentMonthlyAverage: 20000,
      monthly: [{ point: 20000, lower: null, upper: null }, { point: 20000, lower: null, upper: null }, { point: 20000, lower: null, upper: null }],
      projectedTotal: 60000, trend: "STABLE", trendPercentPerMonth: null, reliability: "RELIABLE", recurringMonthly: 20000, outlierMonths: [],
      explanation: "Based on 6 of 6 months with spending in this category. No significant trend; projected at the recent level.",
    },
    {
      category: "DINING", label: "Dining", activeMonths: 6, recentMonthlyAverage: 5000,
      monthly: [{ point: 6576, lower: 6000, upper: 7100 }, { point: 6900, lower: null, upper: null }, { point: 7100, lower: null, upper: null }],
      projectedTotal: 20576, trend: "UPWARD", trendPercentPerMonth: 8, reliability: "LIMITED", recurringMonthly: 0, outlierMonths: ["2026-03"],
      explanation: "Based on 6 of 6 months with spending in this category. A tested upward trend is applied (damped).",
    },
  ],
  withoutForecast: [{ category: "ENTERTAINMENT", label: "Entertainment", reason: "Spending in only 1 of 6 month(s); at least 3 are needed for a category forecast." }],
  risingCategories: ["DINING"],
};

const budgetItem = (over: Partial<BudgetOutlook["overall"] & object> = {}) => ({
  budgetId: "b1", category: null, label: "Overall monthly spending", budgetAmount: 28000, actualToDate: 6000, remainingBudget: 22000,
  recurringStillDue: 0, expectedLow: 18600, expectedCentral: 24300, expectedHigh: 30000, estimatedOverrun: 0, risk: "AT_RISK" as const,
  rule: "The estimates span ₹18600.00 to ₹30000.00, which reaches above 90% of the budget (₹28000.00).", basis: ["RUN_RATE", "HISTORY"],
  indeterminateReason: null, factors: ["Recorded so far: ₹6000.00 of a ₹28000.00 budget.", "History: the median of the last 6 complete months in this scope is ₹30000.00."],
  ...over,
});

export const BUDGET: BudgetOutlook = {
  month: "2026-07", asOf: "2026-07-15", period: "CURRENT", daysInMonth: 31, daysWithRecordedData: 10, daysRemaining: 21, warnings: [],
  overall: budgetItem(),
  categories: [
    budgetItem({ budgetId: "b2", category: "SHOPPING", label: "Shopping", budgetAmount: 5000, actualToDate: 6000, remainingBudget: -1000,
      expectedLow: null, expectedCentral: null, expectedHigh: null, estimatedOverrun: 1000, risk: "EXCEEDED",
      rule: "Spending recorded so far (₹6000.00) is already above the budget (₹5000.00).", basis: [], factors: [] }),
    budgetItem({ budgetId: "b3", category: "DINING", label: "Dining", budgetAmount: 9000, actualToDate: 300, remainingBudget: 8700,
      expectedLow: null, expectedCentral: null, expectedHigh: null, estimatedOverrun: null, risk: "INDETERMINATE",
      rule: "No month-end estimate is possible with the evidence available.", basis: [],
      indeterminateReason: "Not enough evidence: only 2 day(s) of data recorded this month (at least 7 needed for a run-rate) and fewer than 3 complete past months.", factors: [] }),
  ],
};

export const SAVINGS: SavingsProjection = {
  reliability: "RELIABLE", method: "LEVEL_DAMPED_TREND", algorithmVersion: "2.0.0", asOf: "2026-07-15", horizonMonths: 3, history: HISTORY,
  warnings: [], assumptions: ["Only complete calendar months are used."],
  disclaimer: "Projected surplus is the expected difference between recorded-style income and spending. It is not a bank balance and not verified savings. Goal contributions are self-tracked planning figures, not deposits. FinGuard holds no account balances.",
  incomeAvailable: true, averageMonthlyNet: 20000, projectedSavingsRatePercent: 40,
  points: [
    { month: "2026-08", net: { point: 20000, lower: null, upper: null }, cumulativeNet: 20000 },
    { month: "2026-09", net: { point: 20000, lower: null, upper: null }, cumulativeNet: 40000 },
    { month: "2026-10", net: { point: 20000, lower: null, upper: null }, cumulativeNet: 60000 },
  ],
  goals: [
    { id: "g1", name: "Emergency fund", targetAmount: 60000, recordedAmount: 6000, remaining: 54000, targetDate: "2026-12-31",
      requiredMonthlyPace: 10800, monthsIfAllSurplusUsed: 3, completionMonthIfAllSurplusUsed: "2026-10", onPaceIfAlone: true },
  ],
  accounting: {
    projectedSurplusOverHorizon: 60000, recordedGoalContributions: 6000, verifiedBankBalance: null,
    verifiedBankBalanceNote: "Not available. FinGuard does not hold or verify account balances.", totalRequiredGoalPace: 10800, goalsOvercommitted: false,
  },
};

export const BACKTEST: BacktestSummary = {
  status: "COMPLETED", evaluatedOn: "USER_HISTORY", method: "LEVEL_DAMPED_TREND", algorithmVersion: "2.0.0",
  scheme: "CHRONOLOGICAL_ROLLING_ORIGIN", asOf: "2026-07-15", usableMonths: 6,
  series: [
    {
      series: "EXPENSES", observedMonths: 6,
      horizons: [
        {
          horizon: 1, samples: 3,
          model: { mae: 1200, wapePercent: 4.1, rmse: 1400, bias: -200 },
          naive: { mae: 1800, wapePercent: 6.2, rmse: 2000, bias: 0 },
          mean3: { mae: 1500, wapePercent: 5.1, rmse: 1700, bias: 0 },
          verdict: "INCONCLUSIVE",
        },
      ],
    },
    { series: "INCOME", observedMonths: 6, horizons: [] },
  ],
  limitations: [
    "Evaluated on this account's own recorded history; synthetic fixtures are evaluated separately (docs/TESTING.md).",
    "Fewer than 6 scored forecasts at a horizon is reported as INCONCLUSIVE; a handful of months cannot establish accuracy.",
  ],
};

export const SCENARIO: ScenarioResult = {
  name: "Hypothetical scenario", hypothetical: true,
  disclaimer: "Hypothetical: these figures are calculated on the fly from your projected baseline and change nothing in your transactions, budgets or goals. They are not predictions of what will happen.",
  available: true, unavailableReason: null, baselineReliability: "RELIABLE", horizonMonths: 3, incomeAvailable: true, warnings: [],
  baseline: { income: 150000, expenses: 90000, net: 60000, savingsRatePercent: 40 },
  scenario: { income: 150000, expenses: 90000, net: 60000, savingsRatePercent: 40 },
  delta: { income: 0, expenses: 0, net: 0, savingsRatePoints: 0 },
  months: [
    { month: "2026-08", baselineIncome: 50000, scenarioIncome: 50000, baselineExpenses: 30000, scenarioExpenses: 30000, baselineNet: 20000, scenarioNet: 20000 },
  ],
  categories: [],
  explanations: ["No adjustments chosen: the scenario equals the baseline."],
};

export const SCENARIO_CHANGED: ScenarioResult = {
  ...SCENARIO,
  scenario: { income: 165000, expenses: 84300, net: 80700, savingsRatePercent: 48.9 },
  delta: { income: 15000, expenses: -5700, net: 20700, savingsRatePoints: 8.9 },
  categories: [{ category: "GROCERIES", label: "Groceries", baselineMonthly: 9500, scenarioMonthly: 7600, deltaMonthly: -1900, note: "adjusted" }],
  explanations: ["Income changed by 10% in every projected month.", "Over 3 month(s) the projected surplus changes by +₹20700."],
};
