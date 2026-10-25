export interface Period {
  from: string | null;
  to: string | null;
}

export interface MomComparison {
  previousIncome: number;
  incomeChange: number;
  incomeChangePct: number | null;
  previousExpenses: number;
  expenseChange: number;
  expenseChangePct: number | null;
  previousNet: number;
  netChange: number;
}

export interface DataQualitySummary {
  totalTransactions: number;
  uncategorizedCount: number;
  uncategorizedPercentage: number;
  hasSufficientHistory: boolean;
}

export interface FinancialInsight {
  id: string;
  title: string;
  type: "ALERT" | "TREND" | "INFO" | "POSITIVE";
  message: string;
  period: string;
  supportingFigures: Record<string, unknown>;
  ruleTriggered: string;
  confidenceCaveat: string | null;
}

export interface FinancialSummary {
  period: Period;
  totalIncome: number;
  grossExpenses: number;
  totalRefunds: number;
  totalExpenses: number;
  netCashFlow: number;
  savingsRate: number | null;
  savingsRateApplicable: boolean;
  savingsRateCaveat: string | null;
  totalInvested: number;
  totalCurrentValue: number;
  unrealizedGain: number;
  gainPercentage: number;
  activeLoansCount: number;
  totalMonthlyEmi: number;
  totalPrincipal: number;
  momComparison: MomComparison | null;
  excludes: string[];
  dataQuality: DataQualitySummary;
  topInsights: FinancialInsight[];
}

export interface MonthlyFlow {
  month: string;
  income: number;
  grossExpenses: number;
  refunds: number;
  expenses: number;
  netCashFlow: number;
  savingsRate: number | null;
  transactionCount: number;
  momExpenseChangePct: number | null;
  momIncomeChangePct: number | null;
}

export interface TransactionBrief {
  id: string;
  title: string;
  amount: number;
  date: string;
  category: string | null;
}

export interface CategorySpending {
  category: string;
  label: string;
  flow: string;
  amount: number;
  percentage: number;
  transactionCount: number;
  averageTransactionAmount: number;
  momChangePct: number | null;
  isIncreasing: boolean;
  largestTransaction: TransactionBrief | null;
}

export interface CategoryBreakdown {
  period: Period;
  categories: CategorySpending[];
  totalGrossExpenses: number;
  totalRefunds: number;
  totalNetExpenses: number;
  largestCategory: string | null;
  risingCategories: string[];
}

export interface RecurringExpenseItem {
  id: string;
  title: string;
  merchant: string;
  category: string;
  amount: number;
  cadence: "MONTHLY" | "WEEKLY" | "ANNUAL";
  occurrences: number;
  lastDate: string;
  confidence: "HIGH" | "MEDIUM" | "LOW";
  isConfirmed: boolean;
  explanation: string;
}

export interface RecurringExpenses {
  period: Period;
  items: RecurringExpenseItem[];
  totalMonthlyRecurring: number;
  recurringPercentageOfExpenses: number;
}

export interface DataQuality {
  coverageFrom: string | null;
  coverageTo: string | null;
  totalDaysCovered: number;
  totalMonthsCovered: number;
  totalTransactions: number;
  uncategorizedCount: number;
  uncategorizedPercentage: number;
  missingMonths: string[];
  hasSufficientHistory: boolean;
  caveats: string[];
}

export interface UnusualTransaction {
  transactionId: string;
  title: string;
  amount: number;
  date: string;
  category: string;
  benchmarkAmount: number;
  ratio: number;
  reason: string;
  rule: string;
}

export interface InsightsData {
  period: Period;
  insights: FinancialInsight[];
  unusualTransactions: UnusualTransaction[];
}
