/** Mirrors the forecasting DTOs of the backend (docs/API.md, "Forecasting"). */

/** How much evidence a forecast rests on - not how accurate it will be. */
export type Reliability = "RELIABLE" | "LIMITED" | "UNAVAILABLE";

/**
 * A projected amount. `lower`/`upper` are the 10th-90th percentile of the method's own past forecast errors
 * added to `point`; both are null when there are too few past errors for a band. Not a calibrated probability.
 */
export interface Estimate {
  point: number;
  lower: number | null;
  upper: number | null;
}

export interface HistoryInfo {
  from: string | null;
  to: string | null;
  monthsUsed: number;
  minMonthsLimited: number;
  minMonthsReliable: number;
  missingMonths: string[];
  transactionsUsed: number;
  firstMonthPossiblyPartial: boolean;
}

export interface ObservedMonth {
  month: string;
  income: number;
  expenses: number;
  net: number;
}

export interface MonthToDate {
  month: string;
  income: number;
  expenses: number;
  net: number;
  note: string;
}

export interface ProjectedMonth {
  month: string;
  /** Null when no income was observed: income and net are then not projected (never zero). */
  income: Estimate | null;
  expenses: Estimate;
  net: Estimate | null;
  recurringFloorApplied: boolean;
  factors: string[];
}

export interface ForecastSummary {
  totalIncome: number | null;
  totalExpenses: number;
  totalNet: number | null;
  averageMonthlyNet: number | null;
  savingsRatePercent: number | null;
}

export interface RecurringInfluence {
  id: string;
  title: string;
  category: string | null;
  monthlyAmount: number;
  cadence: string;
  confidence: string;
  userConfirmed: boolean;
  includedInBaseline: boolean;
}

export interface CashFlowForecast {
  reliability: Reliability;
  method: string;
  algorithmVersion: string;
  asOf: string;
  horizonMonths: number;
  history: HistoryInfo;
  warnings: string[];
  assumptions: string[];
  intervalNote: string;
  incomeAvailable: boolean;
  historical: ObservedMonth[];
  currentMonth: MonthToDate | null;
  projected: ProjectedMonth[];
  summary: ForecastSummary | null;
  recurring: RecurringInfluence[];
}

export type Trend = "UPWARD" | "DOWNWARD" | "STABLE" | "NOT_ESTABLISHED";

export interface CategoryForecast {
  category: string;
  label: string;
  activeMonths: number;
  recentMonthlyAverage: number;
  monthly: Estimate[];
  projectedTotal: number;
  trend: Trend;
  trendPercentPerMonth: number | null;
  reliability: Reliability;
  recurringMonthly: number;
  outlierMonths: string[];
  explanation: string;
}

export interface CategoryWithoutForecast {
  category: string;
  label: string;
  reason: string;
}

export interface CategoryForecastResponse {
  reliability: Reliability;
  method: string;
  algorithmVersion: string;
  asOf: string;
  horizonMonths: number;
  history: HistoryInfo;
  warnings: string[];
  assumptions: string[];
  categories: CategoryForecast[];
  withoutForecast: CategoryWithoutForecast[];
  risingCategories: string[];
}

export type BudgetRisk =
  | "EXCEEDED"
  | "LIKELY_OVER"
  | "AT_RISK"
  | "ON_TRACK"
  | "INDETERMINATE"
  | "COMPLETED_WITHIN"
  | "COMPLETED_OVER";

export interface BudgetOutlookItem {
  budgetId: string;
  category: string | null;
  label: string;
  budgetAmount: number;
  actualToDate: number;
  remainingBudget: number;
  recurringStillDue: number;
  expectedLow: number | null;
  expectedCentral: number | null;
  expectedHigh: number | null;
  estimatedOverrun: number | null;
  risk: BudgetRisk;
  rule: string;
  basis: string[];
  indeterminateReason: string | null;
  factors: string[];
}

export interface BudgetOutlook {
  month: string;
  asOf: string;
  period: "PAST" | "CURRENT" | "FUTURE";
  daysInMonth: number;
  daysWithRecordedData: number;
  daysRemaining: number;
  warnings: string[];
  overall: BudgetOutlookItem | null;
  categories: BudgetOutlookItem[];
}

export interface SavingsPoint {
  month: string;
  net: Estimate;
  cumulativeNet: number;
}

export interface GoalOutlook {
  id: string;
  name: string;
  targetAmount: number;
  recordedAmount: number;
  remaining: number;
  targetDate: string | null;
  requiredMonthlyPace: number | null;
  monthsIfAllSurplusUsed: number | null;
  completionMonthIfAllSurplusUsed: string | null;
  onPaceIfAlone: boolean | null;
}

export interface SavingsAccounting {
  projectedSurplusOverHorizon: number | null;
  recordedGoalContributions: number;
  verifiedBankBalance: null;
  verifiedBankBalanceNote: string;
  totalRequiredGoalPace: number | null;
  goalsOvercommitted: boolean | null;
}

export interface SavingsProjection {
  reliability: Reliability;
  method: string;
  algorithmVersion: string;
  asOf: string;
  horizonMonths: number;
  history: HistoryInfo;
  warnings: string[];
  assumptions: string[];
  disclaimer: string;
  incomeAvailable: boolean;
  averageMonthlyNet: number | null;
  projectedSavingsRatePercent: number | null;
  points: SavingsPoint[];
  goals: GoalOutlook[];
  accounting: SavingsAccounting;
}

export type AdjustmentType = "PERCENTAGE" | "FIXED_AMOUNT";

export interface Adjustment {
  type: AdjustmentType;
  value: number;
}

export interface CategoryAdjustment extends Adjustment {
  category: string;
}

export interface AddedRecurringExpense {
  title: string;
  category?: string;
  monthlyAmount: number;
}

export interface ScenarioRequest {
  name?: string;
  horizonMonths: number;
  incomeAdjustment?: Adjustment;
  categoryAdjustments?: CategoryAdjustment[];
  addedRecurringExpenses?: AddedRecurringExpense[];
  removedRecurringExpenseIds?: string[];
  loanAdjustments?: LoanAdjustment[];
  oneTimeItems?: OneTimeItem[];
}

export type LoanAction = "CLOSE" | "PREPAY" | "CHANGE_EMI";

export interface LoanAdjustment {
  loanId: string;
  action: LoanAction;
  /** Required for PREPAY and CHANGE_EMI. */
  amount?: number;
}

export interface OneTimeItem {
  title: string;
  kind: "INCOME" | "EXPENSE";
  /** 1 = the first projected month. */
  month: number;
  amount: number;
}

export interface LoanEffect {
  loanId: string;
  loanName: string;
  action: LoanAction;
  estimatedOutstandingBalance: number;
  baselineMonthsToPayoff: number;
  scenarioMonthsToPayoff: number;
  baselineRemainingInterest: number;
  scenarioRemainingInterest: number;
  interestSaved: number;
  cashOutInHorizonChange: number;
  notes: string[];
}

export interface ScenarioTotals {
  income: number | null;
  expenses: number;
  net: number | null;
  savingsRatePercent: number | null;
}

export interface ScenarioDelta {
  income: number | null;
  expenses: number;
  net: number | null;
  savingsRatePoints: number | null;
}

export interface ScenarioMonth {
  month: string;
  baselineIncome: number | null;
  scenarioIncome: number | null;
  baselineExpenses: number;
  scenarioExpenses: number;
  baselineNet: number | null;
  scenarioNet: number | null;
}

export interface ScenarioCategoryEffect {
  category: string;
  label: string;
  baselineMonthly: number;
  scenarioMonthly: number;
  deltaMonthly: number;
  note: string;
}

export interface ScenarioResult {
  name: string;
  hypothetical: true;
  disclaimer: string;
  available: boolean;
  unavailableReason: string | null;
  baselineReliability: Reliability;
  horizonMonths: number;
  incomeAvailable: boolean;
  warnings: string[];
  baseline: ScenarioTotals | null;
  scenario: ScenarioTotals | null;
  delta: ScenarioDelta | null;
  months: ScenarioMonth[];
  categories: ScenarioCategoryEffect[];
  explanations: string[];
  loanEffects: LoanEffect[];
}

export interface SavedScenarioSummary {
  id: string;
  name: string;
  horizonMonths: number;
  methodVersion: string;
  createdAt: string;
  netChange: number | null;
  spendingChange: number | null;
}

export interface SavedScenarioDetail {
  summary: SavedScenarioSummary;
  request: ScenarioRequest;
  snapshot: ScenarioResult;
}

export interface SavedScenarioRerun {
  summary: SavedScenarioSummary;
  request: ScenarioRequest;
  snapshot: ScenarioResult;
  current: ScenarioResult;
  unchanged: boolean;
  methodVersionChanged: boolean;
  currentMethodVersion: string;
}

export type Verdict = "MODEL_BETTER" | "SIMILAR" | "BASELINE_BETTER" | "INCONCLUSIVE";

export interface ErrorMetrics {
  mae: number;
  wapePercent: number | null;
  rmse: number;
  bias: number;
}

export interface HorizonResult {
  horizon: number;
  samples: number;
  model: ErrorMetrics;
  naive: ErrorMetrics;
  mean3: ErrorMetrics;
  verdict: Verdict;
}

export interface BacktestSeries {
  series: "EXPENSES" | "INCOME" | "NET";
  observedMonths: number;
  horizons: HorizonResult[];
}

export interface BacktestSummary {
  status: "COMPLETED" | "INSUFFICIENT_DATA";
  evaluatedOn: "USER_HISTORY";
  method: string;
  algorithmVersion: string;
  scheme: string;
  asOf: string;
  usableMonths: number;
  series: BacktestSeries[];
  limitations: string[];
}
