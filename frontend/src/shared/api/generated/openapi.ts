/**
 * AUTO-GENERATED FILE — DO NOT EDIT MANUALLY.
 * Source of truth: contracts/openapi/finguard-api.v1.json
 * Regenerate via: ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true
 */

export interface components {
  schemas: {
    ApiError: {
      timestamp: string;
      status: number;
      error: string;
      message: string;
      fieldErrors?: Record<string, string>;
    };
    AuthResponse: {
      accessToken: string;
      refreshToken?: string;
      email: string;
      name: string;
    };
    BacktestSummaryResponse: {
      status: "COMPLETED" | "INSUFFICIENT_DATA";
      evaluatedOn: "USER_HISTORY";
      method: string;
      algorithmVersion: string;
      scheme: string;
      asOf: string;
      usableMonths: number;
      series: components["schemas"]["BacktestSummaryResponse_SeriesResult"][];
      limitations: string[];
    };
    BacktestSummaryResponse_ErrorMetrics: {
      mae: number;
      wapePercent: number | null;
      rmse: number;
      bias: number;
    };
    BacktestSummaryResponse_HorizonResult: {
      horizon: number;
      samples: number;
      model: components["schemas"]["BacktestSummaryResponse_ErrorMetrics"];
      naive: components["schemas"]["BacktestSummaryResponse_ErrorMetrics"];
      mean3: components["schemas"]["BacktestSummaryResponse_ErrorMetrics"];
      verdict: "MODEL_BETTER" | "SIMILAR" | "BASELINE_BETTER" | "INCONCLUSIVE";
    };
    BacktestSummaryResponse_SeriesResult: {
      series: "EXPENSES" | "INCOME" | "NET";
      observedMonths: number;
      horizons: components["schemas"]["BacktestSummaryResponse_HorizonResult"][];
    };
    BudgetDto: {
      id: string;
      yearMonth: string;
      category: string | null;
      amount: number;
      createdAt: string;
      updatedAt: string;
    };
    BudgetOverrunRiskResponse: {
      month: string;
      asOf: string;
      period: "PAST" | "CURRENT" | "FUTURE";
      daysInMonth: number;
      daysWithRecordedData: number;
      daysRemaining: number;
      warnings: string[];
      overall: components["schemas"]["BudgetOverrunRiskResponse_Item"] | null;
      categories: components["schemas"]["BudgetOverrunRiskResponse_Item"][];
    };
    BudgetOverrunRiskResponse_Item: {
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
      risk: "EXCEEDED" | "LIKELY_OVER" | "AT_RISK" | "ON_TRACK" | "INDETERMINATE" | "COMPLETED_WITHIN" | "COMPLETED_OVER";
      rule: string;
      basis: string[];
      indeterminateReason: string | null;
      factors: string[];
    };
    BudgetPerformanceSummaryDto: {
      budgetId: string;
      budgetedAmount: number;
      actualSpend: number;
      remainingAmount: number;
      percentageUsed: number;
      status: "UNDER_BUDGET" | "APPROACHING_LIMIT" | "OVER_BUDGET";
    };
    CashFlowForecastResponse: {
      reliability: "RELIABLE" | "LIMITED" | "UNAVAILABLE";
      method: string;
      algorithmVersion: string;
      asOf: string;
      horizonMonths: number;
      history: components["schemas"]["ForecastCommon_HistoryInfo"];
      warnings: string[];
      assumptions: string[];
      intervalNote: string;
      incomeAvailable: boolean;
      historical: components["schemas"]["CashFlowForecastResponse_ObservedMonth"][];
      currentMonth: components["schemas"]["CashFlowForecastResponse_MonthToDate"] | null;
      projected: components["schemas"]["CashFlowForecastResponse_ProjectedMonth"][];
      summary: components["schemas"]["CashFlowForecastResponse_Summary"] | null;
      recurring: components["schemas"]["CashFlowForecastResponse_RecurringInfluence"][];
    };
    CashFlowForecastResponse_MonthToDate: {
      month: string;
      income: number;
      expenses: number;
      net: number;
      note: string;
    };
    CashFlowForecastResponse_ObservedMonth: {
      month: string;
      income: number;
      expenses: number;
      net: number;
    };
    CashFlowForecastResponse_ProjectedMonth: {
      month: string;
      income: components["schemas"]["ForecastCommon_Estimate"] | null;
      expenses: components["schemas"]["ForecastCommon_Estimate"];
      net: components["schemas"]["ForecastCommon_Estimate"] | null;
      recurringFloorApplied: boolean;
      factors: string[];
    };
    CashFlowForecastResponse_RecurringInfluence: {
      id: string;
      title: string;
      category: string | null;
      monthlyAmount: number;
      cadence: string;
      confidence: string;
      userConfirmed: boolean;
      includedInBaseline: boolean;
    };
    CashFlowForecastResponse_Summary: {
      totalIncome: number | null;
      totalExpenses: number;
      totalNet: number | null;
      averageMonthlyNet: number | null;
      savingsRatePercent: number | null;
    };
    CategoryBreakdownResponse: {
      period: components["schemas"]["PeriodDto"];
      categories: components["schemas"]["CategorySpendingDto"][];
      totalGrossExpenses: number;
      totalRefunds: number;
      totalNetExpenses: number;
      largestCategory: string | null;
      risingCategories: string[];
    };
    CategoryBudgetPerformanceDto: {
      budgetId: string;
      category: string;
      categoryLabel: string;
      budgetedAmount: number;
      actualSpend: number;
      remainingAmount: number;
      percentageUsed: number;
      status: "UNDER_BUDGET" | "APPROACHING_LIMIT" | "OVER_BUDGET";
      transactionCount: number;
    };
    CategorySource: "NONE" | "RULE" | "MODEL" | "USER";
    CategorySpendingDto: {
      category: string;
      label: string;
      flow: string;
      amount: number;
      percentage: number;
      transactionCount: number;
      averageTransactionAmount: number;
      momChangePct: number | null;
      isIncreasing: boolean;
      largestTransaction: components["schemas"]["TransactionBriefDto"] | null;
    };
    CategorySpendingForecastResponse: {
      reliability: "RELIABLE" | "LIMITED" | "UNAVAILABLE";
      method: string;
      algorithmVersion: string;
      asOf: string;
      horizonMonths: number;
      history: components["schemas"]["ForecastCommon_HistoryInfo"];
      warnings: string[];
      assumptions: string[];
      categories: components["schemas"]["CategorySpendingForecastResponse_CategoryForecast"][];
      withoutForecast: components["schemas"]["CategorySpendingForecastResponse_CategoryWithoutForecast"][];
      risingCategories: string[];
    };
    CategorySpendingForecastResponse_CategoryForecast: {
      category: string;
      label: string;
      activeMonths: number;
      recentMonthlyAverage: number;
      monthly: components["schemas"]["ForecastCommon_Estimate"][];
      projectedTotal: number;
      trend: "UPWARD" | "DOWNWARD" | "STABLE" | "NOT_ESTABLISHED";
      trendPercentPerMonth: number | null;
      reliability: "RELIABLE" | "LIMITED" | "UNAVAILABLE";
      recurringMonthly: number;
      outlierMonths: string[];
      explanation: string;
    };
    CategorySpendingForecastResponse_CategoryWithoutForecast: {
      category: string;
      label: string;
      reason: string;
    };
    CategoryUpdateRequest: {
      category: string;
    };
    ClassificationStatus: "NOT_ATTEMPTED" | "COMPLETED" | "PARTIAL" | "UNAVAILABLE";
    CreateBudgetRequest: {
      yearMonth: string;
      category?: string | null;
      amount: number;
    };
    CreateGoalRequest: {
      name: string;
      description?: string;
      targetAmount: number;
      targetDate?: string;
    };
    DataQualityResponse: {
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
    };
    DataQualitySummaryDto: {
      totalTransactions: number;
      uncategorizedCount: number;
      uncategorizedPercentage: number;
      hasSufficientHistory: boolean;
    };
    FinancialInsightDto: {
      id: string;
      title: string;
      type: "ALERT" | "TREND" | "INFO" | "POSITIVE";
      message: string;
      period: string;
      supportingFigures: Record<string, unknown>;
      ruleTriggered: string;
      confidenceCaveat: string | null;
    };
    FinancialSummaryResponse: {
      period: components["schemas"]["PeriodDto"];
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
      momComparison: components["schemas"]["MomComparisonDto"] | null;
      excludes: string[];
      dataQuality: components["schemas"]["DataQualitySummaryDto"];
      topInsights: components["schemas"]["FinancialInsightDto"][];
    };
    ForecastCommon_Estimate: {
      point: number;
      lower: number | null;
      upper: number | null;
    };
    ForecastCommon_HistoryInfo: {
      from: string | null;
      to: string | null;
      monthsUsed: number;
      minMonthsLimited: number;
      minMonthsReliable: number;
      missingMonths: string[];
      transactionsUsed: number;
      firstMonthPossiblyPartial: boolean;
    };
    GoalContributionDto: {
      id: string;
      goalId: string;
      amount: number;
      contributionDate: string;
      note?: string;
      createdAt: string;
    };
    GoalContributionRequest: {
      amount: number;
      contributionDate?: string;
      note?: string;
    };
    GoalDetailResponse: {
      goal: components["schemas"]["GoalResponseDto"];
      contributions: components["schemas"]["GoalContributionDto"][];
      disclaimer: string;
    };
    GoalResponseDto: {
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
      status: components["schemas"]["GoalStatus"];
      isOverdue: boolean;
      contributionCount: number;
      createdAt: string;
      updatedAt: string;
    };
    GoalStatus: "ACTIVE" | "COMPLETED" | "ARCHIVED";
    InsightsResponse: {
      period: components["schemas"]["PeriodDto"];
      insights: components["schemas"]["FinancialInsightDto"][];
      unusualTransactions: components["schemas"]["UnusualTransactionDto"][];
    };
    InvestmentRequest: {
      name: string;
      type: components["schemas"]["InvestmentType"];
      amountInvested: number;
      currentValue?: number;
      notes?: string;
    };
    InvestmentResponse: {
      id: string;
      name: string;
      type: components["schemas"]["InvestmentType"];
      amountInvested: number;
      currentValue: number | null;
      notes: string | null;
      createdAt: string;
    };
    InvestmentType: "STOCKS" | "MUTUAL_FUND" | "FD" | "CRYPTO" | "GOLD" | "OTHER";
    JobStatus: "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED";
    LoanRequest: {
      name: string;
      type: components["schemas"]["LoanType"];
      principal: number;
      emiAmount: number;
      interestRate: number;
      startDate?: string;
      endDate?: string;
      active: boolean;
    };
    LoanResponse: {
      id: string;
      name: string;
      type: components["schemas"]["LoanType"];
      principal: number;
      emiAmount: number;
      interestRate: number;
      startDate: string | null;
      endDate: string | null;
      active: boolean;
      createdAt: string;
    };
    LoanType: "HOME" | "CAR" | "PERSONAL" | "EDUCATION" | "OTHER";
    LoginRequest: {
      email: string;
      password: string;
    };
    LogoutRequest: {
      refreshToken?: string;
      allDevices?: boolean;
    };
    MomComparisonDto: {
      previousIncome: number;
      incomeChange: number;
      incomeChangePct: number | null;
      previousExpenses: number;
      expenseChange: number;
      expenseChangePct: number | null;
      previousNet: number;
      netChange: number;
    };
    MonthlyBudgetOverviewResponse: {
      yearMonth: string;
      overallBudget: components["schemas"]["BudgetPerformanceSummaryDto"] | null;
      categoryBudgets: components["schemas"]["CategoryBudgetPerformanceDto"][];
      totalCategoryBudgeted: number;
      totalActualExpenses: number;
      unbudgetedSpending: number;
      categoriesOverBudgetCount: number;
      categoriesApproachingCount: number;
      warnings: string[];
    };
    MonthlyFlowDto: {
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
    };
    PeriodDto: {
      from: string | null;
      to: string | null;
    };
    ReclassifyResponse: {
      status: components["schemas"]["ClassificationStatus"];
      attempted: number;
      categorized: number;
      needsReview: number;
      stillUncategorized: number;
    };
    RecommendationsResponse: {
      period: components["schemas"]["RecommendationsResponse_Period"];
      disclaimer: string;
      healthy: boolean;
      recommendations: components["schemas"]["RecommendationsResponse_Recommendation"][];
      notEvaluated: components["schemas"]["RecommendationsResponse_NotEvaluated"][];
      thresholds: Record<string, number>;
    };
    RecommendationsResponse_Evidence: {
      metric: string;
      value: number;
      unit: string;
      source: string;
    };
    RecommendationsResponse_NotEvaluated: {
      id: string;
      title: string;
      reason: string;
    };
    RecommendationsResponse_Period: {
      from: string;
      to: string;
      months: number;
      monthsWithData: number;
    };
    RecommendationsResponse_Recommendation: {
      id: string;
      severity: components["schemas"]["RecommendationsResponse_Severity"];
      title: string;
      explanation: string;
      suggestedAction: string;
      evidence: components["schemas"]["RecommendationsResponse_Evidence"][];
      estimatedMonthlyImpact: number | null;
      scenarioPreset: components["schemas"]["RecommendationsResponse_ScenarioPreset"] | null;
    };
    RecommendationsResponse_ScenarioPreset: {
      label: string;
      request: {
        horizonMonths?: number;
        categoryAdjustments?: components["schemas"]["ScenarioSimulationRequest_CategoryAdjustment"][];
      };
    };
    RecommendationsResponse_Severity: "CRITICAL" | "HIGH" | "MEDIUM" | "INFO";
    RecurringExpenseItemDto: {
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
    };
    RecurringExpensesResponse: {
      period: components["schemas"]["PeriodDto"];
      items: components["schemas"]["RecurringExpenseItemDto"][];
      totalMonthlyRecurring: number;
      recurringPercentageOfExpenses: number;
    };
    RefreshRequest: {
      refreshToken?: string;
    };
    RegisterRequest: {
      name: string;
      email: string;
      password: string;
    };
    RowErrorDto: {
      rowNumber: number;
      errorMessage: string;
      snippet: string;
    };
    SavedScenarioResponses_Detail: {
      summary: components["schemas"]["SavedScenarioResponses_Summary"];
      request: components["schemas"]["ScenarioSimulationRequest"];
      snapshot: components["schemas"]["ScenarioSimulationResponse"];
    };
    SavedScenarioResponses_Rerun: {
      summary: components["schemas"]["SavedScenarioResponses_Summary"];
      request: components["schemas"]["ScenarioSimulationRequest"];
      snapshot: components["schemas"]["ScenarioSimulationResponse"];
      current: components["schemas"]["ScenarioSimulationResponse"];
      unchanged: boolean;
      methodVersionChanged: boolean;
      currentMethodVersion: string;
    };
    SavedScenarioResponses_Summary: {
      id: string;
      name: string;
      horizonMonths: number;
      methodVersion: string;
      createdAt: string;
      netChange: number | null;
      spendingChange: number | null;
    };
    SavingsProjectionResponse: {
      reliability: "RELIABLE" | "LIMITED" | "UNAVAILABLE";
      method: string;
      algorithmVersion: string;
      asOf: string;
      horizonMonths: number;
      history: components["schemas"]["ForecastCommon_HistoryInfo"];
      warnings: string[];
      assumptions: string[];
      disclaimer: string;
      incomeAvailable: boolean;
      averageMonthlyNet: number | null;
      projectedSavingsRatePercent: number | null;
      points: components["schemas"]["SavingsProjectionResponse_Point"][];
      goals: components["schemas"]["SavingsProjectionResponse_GoalOutlook"][];
      accounting: components["schemas"]["SavingsProjectionResponse_Accounting"];
    };
    SavingsProjectionResponse_Accounting: {
      projectedSurplusOverHorizon: number | null;
      recordedGoalContributions: number;
      verifiedBankBalance: null;
      verifiedBankBalanceNote: string;
      totalRequiredGoalPace: number | null;
      goalsOvercommitted: boolean | null;
    };
    SavingsProjectionResponse_GoalOutlook: {
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
    };
    SavingsProjectionResponse_Point: {
      month: string;
      net: components["schemas"]["ForecastCommon_Estimate"];
      cumulativeNet: number;
    };
    ScenarioSimulationRequest: {
      name?: string;
      horizonMonths: number;
      incomeAdjustment?: components["schemas"]["ScenarioSimulationRequest_Adjustment"];
      categoryAdjustments?: components["schemas"]["ScenarioSimulationRequest_CategoryAdjustment"][];
      addedRecurringExpenses?: components["schemas"]["ScenarioSimulationRequest_AddedRecurringExpense"][];
      removedRecurringExpenseIds?: string[];
      loanAdjustments?: components["schemas"]["ScenarioSimulationRequest_LoanAdjustment"][];
      oneTimeItems?: components["schemas"]["ScenarioSimulationRequest_OneTimeItem"][];
    };
    ScenarioSimulationRequest_AddedRecurringExpense: {
      title: string;
      category?: string;
      monthlyAmount: number;
    };
    ScenarioSimulationRequest_Adjustment: {
      type: components["schemas"]["ScenarioSimulationRequest_AdjustmentType"];
      value: number;
    };
    ScenarioSimulationRequest_AdjustmentType: "PERCENTAGE" | "FIXED_AMOUNT";
    ScenarioSimulationRequest_CategoryAdjustment: {
      category: string;
      type: components["schemas"]["ScenarioSimulationRequest_AdjustmentType"];
      value: number;
    };
    ScenarioSimulationRequest_LoanAction: "CLOSE" | "PREPAY" | "CHANGE_EMI";
    ScenarioSimulationRequest_LoanAdjustment: {
      loanId: string;
      action: components["schemas"]["ScenarioSimulationRequest_LoanAction"];
      amount?: number;
    };
    ScenarioSimulationRequest_OneTimeItem: {
      title: string;
      kind: components["schemas"]["ScenarioSimulationRequest_OneTimeKind"];
      month: number;
      amount: number;
    };
    ScenarioSimulationRequest_OneTimeKind: "INCOME" | "EXPENSE";
    ScenarioSimulationResponse: {
      name: string;
      hypothetical: true;
      disclaimer: string;
      available: boolean;
      unavailableReason: string | null;
      baselineReliability: "RELIABLE" | "LIMITED" | "UNAVAILABLE";
      horizonMonths: number;
      incomeAvailable: boolean;
      warnings: string[];
      baseline: components["schemas"]["ScenarioSimulationResponse_Totals"] | null;
      scenario: components["schemas"]["ScenarioSimulationResponse_Totals"] | null;
      delta: components["schemas"]["ScenarioSimulationResponse_Delta"] | null;
      months: components["schemas"]["ScenarioSimulationResponse_Month"][];
      categories: components["schemas"]["ScenarioSimulationResponse_CategoryEffect"][];
      explanations: string[];
      loanEffects: components["schemas"]["ScenarioSimulationResponse_LoanEffect"][];
    };
    ScenarioSimulationResponse_CategoryEffect: {
      category: string;
      label: string;
      baselineMonthly: number;
      scenarioMonthly: number;
      deltaMonthly: number;
      note: string;
    };
    ScenarioSimulationResponse_Delta: {
      income: number | null;
      expenses: number;
      net: number | null;
      savingsRatePoints: number | null;
    };
    ScenarioSimulationResponse_LoanEffect: {
      loanId: string;
      loanName: string;
      action: "CLOSE" | "PREPAY" | "CHANGE_EMI";
      estimatedOutstandingBalance: number;
      baselineMonthsToPayoff: number;
      scenarioMonthsToPayoff: number;
      baselineRemainingInterest: number;
      scenarioRemainingInterest: number;
      interestSaved: number;
      cashOutInHorizonChange: number;
      notes: string[];
    };
    ScenarioSimulationResponse_Month: {
      month: string;
      baselineIncome: number | null;
      scenarioIncome: number | null;
      baselineExpenses: number;
      scenarioExpenses: number;
      baselineNet: number | null;
      scenarioNet: number | null;
    };
    ScenarioSimulationResponse_Totals: {
      income: number | null;
      expenses: number;
      net: number | null;
      savingsRatePercent: number | null;
    };
    TransactionBriefDto: {
      id: string;
      title: string;
      amount: number;
      date: string;
      category: string | null;
    };
    TransactionRequest: {
      title: string;
      amount: number;
      type: components["schemas"]["TransactionType"];
      date?: string;
      category?: string;
      note?: string;
    };
    TransactionResponse: {
      id: string;
      title: string;
      amount: number;
      type: components["schemas"]["TransactionType"];
      date: string;
      category: string | null;
      note: string | null;
      source: components["schemas"]["TransactionSource"];
      categorySource?: components["schemas"]["CategorySource"] | null;
      categoryConfidence?: number | null;
      categoryConfidenceCalibrated?: boolean;
      categoryNeedsReview?: boolean;
      categoryModelVersion?: string | null;
      categoryRule?: string | null;
      createdAt: string;
    };
    TransactionSource: "MANUAL" | "IMPORT";
    TransactionType: "INCOME" | "EXPENSE";
    UnusualTransactionDto: {
      transactionId: string;
      title: string;
      amount: number;
      date: string;
      category: string;
      benchmarkAmount: number;
      ratio: number;
      reason: string;
      rule: string;
    };
    UpdateBudgetRequest: {
      amount: number;
    };
    UpdateGoalRequest: {
      name?: string;
      description?: string;
      targetAmount?: number;
      targetDate?: string;
      status?: components["schemas"]["GoalStatus"];
    };
    UploadStatusResponse: {
      jobId: string;
      status: components["schemas"]["JobStatus"];
      bankName: string;
      originalFilename: string;
      totalRows: number;
      rowsProcessed: number;
      duplicateRows: number;
      rejectedRows: number;
      skippedRows: number;
      rowErrors?: components["schemas"]["RowErrorDto"][];
      classificationStatus?: components["schemas"]["ClassificationStatus"];
      classifiedRows?: number;
      closingBalance?: number | null;
      statementFrom?: string | null;
      statementTo?: string | null;
      errorMessage: string | null;
      createdAt: string | null;
      completedAt: string | null;
    };
  };
}

export type ApiError = components["schemas"]["ApiError"];
export type AuthResponse = components["schemas"]["AuthResponse"];
export type BacktestSummaryResponse = components["schemas"]["BacktestSummaryResponse"];
export type BacktestSummaryResponse_ErrorMetrics = components["schemas"]["BacktestSummaryResponse_ErrorMetrics"];
export type BacktestSummaryResponse_HorizonResult = components["schemas"]["BacktestSummaryResponse_HorizonResult"];
export type BacktestSummaryResponse_SeriesResult = components["schemas"]["BacktestSummaryResponse_SeriesResult"];
export type BudgetDto = components["schemas"]["BudgetDto"];
export type BudgetOverrunRiskResponse = components["schemas"]["BudgetOverrunRiskResponse"];
export type BudgetOverrunRiskResponse_Item = components["schemas"]["BudgetOverrunRiskResponse_Item"];
export type BudgetPerformanceSummaryDto = components["schemas"]["BudgetPerformanceSummaryDto"];
export type CashFlowForecastResponse = components["schemas"]["CashFlowForecastResponse"];
export type CashFlowForecastResponse_MonthToDate = components["schemas"]["CashFlowForecastResponse_MonthToDate"];
export type CashFlowForecastResponse_ObservedMonth = components["schemas"]["CashFlowForecastResponse_ObservedMonth"];
export type CashFlowForecastResponse_ProjectedMonth = components["schemas"]["CashFlowForecastResponse_ProjectedMonth"];
export type CashFlowForecastResponse_RecurringInfluence = components["schemas"]["CashFlowForecastResponse_RecurringInfluence"];
export type CashFlowForecastResponse_Summary = components["schemas"]["CashFlowForecastResponse_Summary"];
export type CategoryBreakdownResponse = components["schemas"]["CategoryBreakdownResponse"];
export type CategoryBudgetPerformanceDto = components["schemas"]["CategoryBudgetPerformanceDto"];
export type CategorySource = components["schemas"]["CategorySource"];
export type CategorySpendingDto = components["schemas"]["CategorySpendingDto"];
export type CategorySpendingForecastResponse = components["schemas"]["CategorySpendingForecastResponse"];
export type CategorySpendingForecastResponse_CategoryForecast = components["schemas"]["CategorySpendingForecastResponse_CategoryForecast"];
export type CategorySpendingForecastResponse_CategoryWithoutForecast = components["schemas"]["CategorySpendingForecastResponse_CategoryWithoutForecast"];
export type CategoryUpdateRequest = components["schemas"]["CategoryUpdateRequest"];
export type ClassificationStatus = components["schemas"]["ClassificationStatus"];
export type CreateBudgetRequest = components["schemas"]["CreateBudgetRequest"];
export type CreateGoalRequest = components["schemas"]["CreateGoalRequest"];
export type DataQualityResponse = components["schemas"]["DataQualityResponse"];
export type DataQualitySummaryDto = components["schemas"]["DataQualitySummaryDto"];
export type FinancialInsightDto = components["schemas"]["FinancialInsightDto"];
export type FinancialSummaryResponse = components["schemas"]["FinancialSummaryResponse"];
export type ForecastCommon_Estimate = components["schemas"]["ForecastCommon_Estimate"];
export type ForecastCommon_HistoryInfo = components["schemas"]["ForecastCommon_HistoryInfo"];
export type GoalContributionDto = components["schemas"]["GoalContributionDto"];
export type GoalContributionRequest = components["schemas"]["GoalContributionRequest"];
export type GoalDetailResponse = components["schemas"]["GoalDetailResponse"];
export type GoalResponseDto = components["schemas"]["GoalResponseDto"];
export type GoalStatus = components["schemas"]["GoalStatus"];
export type InsightsResponse = components["schemas"]["InsightsResponse"];
export type InvestmentRequest = components["schemas"]["InvestmentRequest"];
export type InvestmentResponse = components["schemas"]["InvestmentResponse"];
export type InvestmentType = components["schemas"]["InvestmentType"];
export type JobStatus = components["schemas"]["JobStatus"];
export type LoanRequest = components["schemas"]["LoanRequest"];
export type LoanResponse = components["schemas"]["LoanResponse"];
export type LoanType = components["schemas"]["LoanType"];
export type LoginRequest = components["schemas"]["LoginRequest"];
export type LogoutRequest = components["schemas"]["LogoutRequest"];
export type MomComparisonDto = components["schemas"]["MomComparisonDto"];
export type MonthlyBudgetOverviewResponse = components["schemas"]["MonthlyBudgetOverviewResponse"];
export type MonthlyFlowDto = components["schemas"]["MonthlyFlowDto"];
export type PeriodDto = components["schemas"]["PeriodDto"];
export type ReclassifyResponse = components["schemas"]["ReclassifyResponse"];
export type RecommendationsResponse = components["schemas"]["RecommendationsResponse"];
export type RecommendationsResponse_Evidence = components["schemas"]["RecommendationsResponse_Evidence"];
export type RecommendationsResponse_NotEvaluated = components["schemas"]["RecommendationsResponse_NotEvaluated"];
export type RecommendationsResponse_Period = components["schemas"]["RecommendationsResponse_Period"];
export type RecommendationsResponse_Recommendation = components["schemas"]["RecommendationsResponse_Recommendation"];
export type RecommendationsResponse_ScenarioPreset = components["schemas"]["RecommendationsResponse_ScenarioPreset"];
export type RecommendationsResponse_Severity = components["schemas"]["RecommendationsResponse_Severity"];
export type RecurringExpenseItemDto = components["schemas"]["RecurringExpenseItemDto"];
export type RecurringExpensesResponse = components["schemas"]["RecurringExpensesResponse"];
export type RefreshRequest = components["schemas"]["RefreshRequest"];
export type RegisterRequest = components["schemas"]["RegisterRequest"];
export type RowErrorDto = components["schemas"]["RowErrorDto"];
export type SavedScenarioResponses_Detail = components["schemas"]["SavedScenarioResponses_Detail"];
export type SavedScenarioResponses_Rerun = components["schemas"]["SavedScenarioResponses_Rerun"];
export type SavedScenarioResponses_Summary = components["schemas"]["SavedScenarioResponses_Summary"];
export type SavingsProjectionResponse = components["schemas"]["SavingsProjectionResponse"];
export type SavingsProjectionResponse_Accounting = components["schemas"]["SavingsProjectionResponse_Accounting"];
export type SavingsProjectionResponse_GoalOutlook = components["schemas"]["SavingsProjectionResponse_GoalOutlook"];
export type SavingsProjectionResponse_Point = components["schemas"]["SavingsProjectionResponse_Point"];
export type ScenarioSimulationRequest = components["schemas"]["ScenarioSimulationRequest"];
export type ScenarioSimulationRequest_AddedRecurringExpense = components["schemas"]["ScenarioSimulationRequest_AddedRecurringExpense"];
export type ScenarioSimulationRequest_Adjustment = components["schemas"]["ScenarioSimulationRequest_Adjustment"];
export type ScenarioSimulationRequest_AdjustmentType = components["schemas"]["ScenarioSimulationRequest_AdjustmentType"];
export type ScenarioSimulationRequest_CategoryAdjustment = components["schemas"]["ScenarioSimulationRequest_CategoryAdjustment"];
export type ScenarioSimulationRequest_LoanAction = components["schemas"]["ScenarioSimulationRequest_LoanAction"];
export type ScenarioSimulationRequest_LoanAdjustment = components["schemas"]["ScenarioSimulationRequest_LoanAdjustment"];
export type ScenarioSimulationRequest_OneTimeItem = components["schemas"]["ScenarioSimulationRequest_OneTimeItem"];
export type ScenarioSimulationRequest_OneTimeKind = components["schemas"]["ScenarioSimulationRequest_OneTimeKind"];
export type ScenarioSimulationResponse = components["schemas"]["ScenarioSimulationResponse"];
export type ScenarioSimulationResponse_CategoryEffect = components["schemas"]["ScenarioSimulationResponse_CategoryEffect"];
export type ScenarioSimulationResponse_Delta = components["schemas"]["ScenarioSimulationResponse_Delta"];
export type ScenarioSimulationResponse_LoanEffect = components["schemas"]["ScenarioSimulationResponse_LoanEffect"];
export type ScenarioSimulationResponse_Month = components["schemas"]["ScenarioSimulationResponse_Month"];
export type ScenarioSimulationResponse_Totals = components["schemas"]["ScenarioSimulationResponse_Totals"];
export type TransactionBriefDto = components["schemas"]["TransactionBriefDto"];
export type TransactionRequest = components["schemas"]["TransactionRequest"];
export type TransactionResponse = components["schemas"]["TransactionResponse"];
export type TransactionSource = components["schemas"]["TransactionSource"];
export type TransactionType = components["schemas"]["TransactionType"];
export type UnusualTransactionDto = components["schemas"]["UnusualTransactionDto"];
export type UpdateBudgetRequest = components["schemas"]["UpdateBudgetRequest"];
export type UpdateGoalRequest = components["schemas"]["UpdateGoalRequest"];
export type UploadStatusResponse = components["schemas"]["UploadStatusResponse"];

export interface operations {
  Analytics_getCategoryBreakdown: {
    parameters: {
      query: {
        from?: string;
        to?: string;
      };
    };
    responses: {
      200: components["schemas"]["CategoryBreakdownResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Analytics_getDataQuality: {
    parameters: {
      query: {
        from?: string;
        to?: string;
      };
    };
    responses: {
      200: components["schemas"]["DataQualityResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Analytics_getInsights: {
    parameters: {
      query: {
        from?: string;
        to?: string;
      };
    };
    responses: {
      200: components["schemas"]["InsightsResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Analytics_getMonthlyFlows: {
    parameters: {
      query: {
        from?: string;
        to?: string;
      };
    };
    responses: {
      200: components["schemas"]["MonthlyFlowDto"][];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Analytics_getRecurringExpenses: {
    parameters: {
      query: {
        from?: string;
        to?: string;
      };
    };
    responses: {
      200: components["schemas"]["RecurringExpensesResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Analytics_getSummary: {
    parameters: {
      query: {
        from?: string;
        to?: string;
      };
    };
    responses: {
      200: components["schemas"]["FinancialSummaryResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Auth_login: {
    requestBody: components["schemas"]["LoginRequest"];
    responses: {
      200: components["schemas"]["AuthResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      409: components["schemas"]["ApiError"];
      429: components["schemas"]["ApiError"];
    };
  };
  Auth_logout: {
    parameters: {
      cookie: {
        refreshToken?: string;
      };
    };
    requestBody?: components["schemas"]["LogoutRequest"];
    responses: {
      200: Record<string, string>;
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
      409: components["schemas"]["ApiError"];
      429: components["schemas"]["ApiError"];
    };
  };
  Auth_refresh: {
    parameters: {
      cookie: {
        refreshToken?: string;
      };
    };
    requestBody?: components["schemas"]["RefreshRequest"];
    responses: {
      200: components["schemas"]["AuthResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      409: components["schemas"]["ApiError"];
      429: components["schemas"]["ApiError"];
    };
  };
  Auth_register: {
    requestBody: components["schemas"]["RegisterRequest"];
    responses: {
      200: components["schemas"]["AuthResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      409: components["schemas"]["ApiError"];
      429: components["schemas"]["ApiError"];
    };
  };
  Budget_listBudgets: {
    parameters: {
      query: {
        yearMonth?: string;
        month?: string;
      };
    };
    responses: {
      200: components["schemas"]["BudgetDto"][];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Budget_createBudget: {
    requestBody: components["schemas"]["CreateBudgetRequest"];
    responses: {
      201: components["schemas"]["BudgetDto"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
      409: components["schemas"]["ApiError"];
    };
  };
  Budget_getMonthlyOverview: {
    parameters: {
      query: {
        yearMonth?: string;
        month?: string;
      };
    };
    responses: {
      200: components["schemas"]["MonthlyBudgetOverviewResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Budget_updateBudget: {
    parameters: {
      path: {
        id: string;
      };
    };
    requestBody: components["schemas"]["UpdateBudgetRequest"];
    responses: {
      200: components["schemas"]["BudgetDto"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Budget_deleteBudget: {
    parameters: {
      path: {
        id: string;
      };
    };
    responses: {
      204: void;
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Forecast_getBacktestSummary: {
    responses: {
      200: components["schemas"]["BacktestSummaryResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Forecast_getBudgetOverrunRisk: {
    parameters: {
      query: {
        yearMonth?: string;
      };
    };
    responses: {
      200: components["schemas"]["BudgetOverrunRiskResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Forecast_getCashFlowForecast: {
    parameters: {
      query: {
        horizonMonths?: number;
      };
    };
    responses: {
      200: components["schemas"]["CashFlowForecastResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Forecast_getCategoryForecast: {
    parameters: {
      query: {
        horizonMonths?: number;
      };
    };
    responses: {
      200: components["schemas"]["CategorySpendingForecastResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Forecast_getSavingsProjections: {
    parameters: {
      query: {
        horizonMonths?: number;
      };
    };
    responses: {
      200: components["schemas"]["SavingsProjectionResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Forecast_simulateScenario: {
    requestBody: components["schemas"]["ScenarioSimulationRequest"];
    responses: {
      200: components["schemas"]["ScenarioSimulationResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
      409: components["schemas"]["ApiError"];
    };
  };
  Goal_listGoals: {
    parameters: {
      query: {
        status?: components["schemas"]["GoalStatus"];
      };
    };
    responses: {
      200: components["schemas"]["GoalResponseDto"][];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Goal_createGoal: {
    requestBody: components["schemas"]["CreateGoalRequest"];
    responses: {
      201: components["schemas"]["GoalResponseDto"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
      409: components["schemas"]["ApiError"];
    };
  };
  Goal_deleteContribution: {
    parameters: {
      path: {
        goalId: string;
        contributionId: string;
      };
    };
    responses: {
      204: void;
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Goal_getGoalDetail: {
    parameters: {
      path: {
        id: string;
      };
    };
    responses: {
      200: components["schemas"]["GoalDetailResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Goal_updateGoal: {
    parameters: {
      path: {
        id: string;
      };
    };
    requestBody: components["schemas"]["UpdateGoalRequest"];
    responses: {
      200: components["schemas"]["GoalResponseDto"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Goal_deleteGoal: {
    parameters: {
      path: {
        id: string;
      };
    };
    responses: {
      204: void;
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Goal_recordContribution: {
    parameters: {
      path: {
        id: string;
      };
    };
    requestBody: components["schemas"]["GoalContributionRequest"];
    responses: {
      201: components["schemas"]["GoalContributionDto"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
      409: components["schemas"]["ApiError"];
    };
  };
  Investment_getAll: {
    responses: {
      200: components["schemas"]["InvestmentResponse"][];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Investment_create: {
    requestBody: components["schemas"]["InvestmentRequest"];
    responses: {
      201: components["schemas"]["InvestmentResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
      409: components["schemas"]["ApiError"];
    };
  };
  Investment_getById: {
    parameters: {
      path: {
        id: string;
      };
    };
    responses: {
      200: components["schemas"]["InvestmentResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Investment_update: {
    parameters: {
      path: {
        id: string;
      };
    };
    requestBody: components["schemas"]["InvestmentRequest"];
    responses: {
      200: components["schemas"]["InvestmentResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Investment_delete: {
    parameters: {
      path: {
        id: string;
      };
    };
    responses: {
      204: void;
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Loan_getAll: {
    responses: {
      200: components["schemas"]["LoanResponse"][];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Loan_create: {
    requestBody: components["schemas"]["LoanRequest"];
    responses: {
      201: components["schemas"]["LoanResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
      409: components["schemas"]["ApiError"];
    };
  };
  Loan_getById: {
    parameters: {
      path: {
        id: string;
      };
    };
    responses: {
      200: components["schemas"]["LoanResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Loan_update: {
    parameters: {
      path: {
        id: string;
      };
    };
    requestBody: components["schemas"]["LoanRequest"];
    responses: {
      200: components["schemas"]["LoanResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Loan_delete: {
    parameters: {
      path: {
        id: string;
      };
    };
    responses: {
      204: void;
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Recommendation_getRecommendations: {
    responses: {
      200: components["schemas"]["RecommendationsResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  SavedScenario_list: {
    responses: {
      200: components["schemas"]["SavedScenarioResponses_Summary"][];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  SavedScenario_save: {
    requestBody: components["schemas"]["ScenarioSimulationRequest"];
    responses: {
      201: components["schemas"]["SavedScenarioResponses_Detail"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
      409: components["schemas"]["ApiError"];
    };
  };
  SavedScenario_get: {
    parameters: {
      path: {
        id: string;
      };
    };
    responses: {
      200: components["schemas"]["SavedScenarioResponses_Detail"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  SavedScenario_delete: {
    parameters: {
      path: {
        id: string;
      };
    };
    responses: {
      204: void;
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  SavedScenario_rerun: {
    parameters: {
      path: {
        id: string;
      };
    };
    responses: {
      200: components["schemas"]["SavedScenarioResponses_Rerun"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
      409: components["schemas"]["ApiError"];
    };
  };
  Transaction_getAll: {
    parameters: {
      query: {
        page?: number;
        size?: number;
        unpaged?: boolean;
        from?: string;
        to?: string;
        type?: components["schemas"]["TransactionType"];
        category?: string;
        needsReview?: boolean;
      };
    };
    responses: {
      200: components["schemas"]["TransactionResponse"][];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Transaction_create: {
    requestBody: components["schemas"]["TransactionRequest"];
    responses: {
      201: components["schemas"]["TransactionResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
      409: components["schemas"]["ApiError"];
    };
  };
  Categorization_reclassify: {
    parameters: {
      query: {
        limit?: number;
      };
    };
    responses: {
      200: components["schemas"]["ReclassifyResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
      409: components["schemas"]["ApiError"];
    };
  };
  Transaction_getById: {
    parameters: {
      path: {
        id: string;
      };
    };
    responses: {
      200: components["schemas"]["TransactionResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Transaction_update: {
    parameters: {
      path: {
        id: string;
      };
    };
    requestBody: components["schemas"]["TransactionRequest"];
    responses: {
      200: components["schemas"]["TransactionResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Transaction_delete: {
    parameters: {
      path: {
        id: string;
      };
    };
    responses: {
      204: void;
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Transaction_updateCategory: {
    parameters: {
      path: {
        id: string;
      };
    };
    requestBody: components["schemas"]["CategoryUpdateRequest"];
    responses: {
      200: components["schemas"]["TransactionResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Upload_uploadCsv: {
    requestBody: FormData;
    responses: {
      202: components["schemas"]["UploadStatusResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
      409: components["schemas"]["ApiError"];
    };
  };
  Upload_getJobs: {
    responses: {
      200: components["schemas"]["UploadStatusResponse"][];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
  Upload_getStatus: {
    parameters: {
      path: {
        jobId: string;
      };
    };
    responses: {
      200: components["schemas"]["UploadStatusResponse"];
      400: components["schemas"]["ApiError"];
      401: components["schemas"]["ApiError"];
      404: components["schemas"]["ApiError"];
    };
  };
}

export interface paths {
  "/api/analytics/categories": {
    get: operations["Analytics_getCategoryBreakdown"];
  };
  "/api/analytics/data-quality": {
    get: operations["Analytics_getDataQuality"];
  };
  "/api/analytics/insights": {
    get: operations["Analytics_getInsights"];
  };
  "/api/analytics/monthly": {
    get: operations["Analytics_getMonthlyFlows"];
  };
  "/api/analytics/recurring": {
    get: operations["Analytics_getRecurringExpenses"];
  };
  "/api/analytics/summary": {
    get: operations["Analytics_getSummary"];
  };
  "/api/auth/login": {
    post: operations["Auth_login"];
  };
  "/api/auth/logout": {
    post: operations["Auth_logout"];
  };
  "/api/auth/refresh": {
    post: operations["Auth_refresh"];
  };
  "/api/auth/register": {
    post: operations["Auth_register"];
  };
  "/api/budgets": {
    get: operations["Budget_listBudgets"];
    post: operations["Budget_createBudget"];
  };
  "/api/budgets/overview": {
    get: operations["Budget_getMonthlyOverview"];
  };
  "/api/budgets/{id}": {
    put: operations["Budget_updateBudget"];
    delete: operations["Budget_deleteBudget"];
  };
  "/api/forecast/backtest": {
    get: operations["Forecast_getBacktestSummary"];
  };
  "/api/forecast/budget-overruns": {
    get: operations["Forecast_getBudgetOverrunRisk"];
  };
  "/api/forecast/cash-flow": {
    get: operations["Forecast_getCashFlowForecast"];
  };
  "/api/forecast/categories": {
    get: operations["Forecast_getCategoryForecast"];
  };
  "/api/forecast/savings": {
    get: operations["Forecast_getSavingsProjections"];
  };
  "/api/forecast/scenarios/simulate": {
    post: operations["Forecast_simulateScenario"];
  };
  "/api/goals": {
    get: operations["Goal_listGoals"];
    post: operations["Goal_createGoal"];
  };
  "/api/goals/{goalId}/contributions/{contributionId}": {
    delete: operations["Goal_deleteContribution"];
  };
  "/api/goals/{id}": {
    get: operations["Goal_getGoalDetail"];
    put: operations["Goal_updateGoal"];
    delete: operations["Goal_deleteGoal"];
  };
  "/api/goals/{id}/contributions": {
    post: operations["Goal_recordContribution"];
  };
  "/api/investments": {
    get: operations["Investment_getAll"];
    post: operations["Investment_create"];
  };
  "/api/investments/{id}": {
    get: operations["Investment_getById"];
    put: operations["Investment_update"];
    delete: operations["Investment_delete"];
  };
  "/api/loans": {
    get: operations["Loan_getAll"];
    post: operations["Loan_create"];
  };
  "/api/loans/{id}": {
    get: operations["Loan_getById"];
    put: operations["Loan_update"];
    delete: operations["Loan_delete"];
  };
  "/api/recommendations": {
    get: operations["Recommendation_getRecommendations"];
  };
  "/api/scenarios": {
    get: operations["SavedScenario_list"];
    post: operations["SavedScenario_save"];
  };
  "/api/scenarios/{id}": {
    get: operations["SavedScenario_get"];
    delete: operations["SavedScenario_delete"];
  };
  "/api/scenarios/{id}/rerun": {
    post: operations["SavedScenario_rerun"];
  };
  "/api/transactions": {
    get: operations["Transaction_getAll"];
    post: operations["Transaction_create"];
  };
  "/api/transactions/reclassify": {
    post: operations["Categorization_reclassify"];
  };
  "/api/transactions/{id}": {
    get: operations["Transaction_getById"];
    put: operations["Transaction_update"];
    delete: operations["Transaction_delete"];
  };
  "/api/transactions/{id}/category": {
    patch: operations["Transaction_updateCategory"];
  };
  "/api/upload/csv": {
    post: operations["Upload_uploadCsv"];
  };
  "/api/upload/jobs": {
    get: operations["Upload_getJobs"];
  };
  "/api/upload/status/{jobId}": {
    get: operations["Upload_getStatus"];
  };
}
