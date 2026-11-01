import type { components } from "../../shared/api/generated/openapi";

/** Mirrors the forecasting DTOs of the backend (generated from contracts/openapi/finguard-api.v1.json). */
export type CashFlowForecast = components["schemas"]["CashFlowForecastResponse"];
export type Reliability = CashFlowForecast["reliability"];
export type Estimate = components["schemas"]["ForecastCommon_Estimate"];
export type HistoryInfo = components["schemas"]["ForecastCommon_HistoryInfo"];
export type ObservedMonth = components["schemas"]["CashFlowForecastResponse_ObservedMonth"];
export type MonthToDate = components["schemas"]["CashFlowForecastResponse_MonthToDate"];
export type ProjectedMonth = components["schemas"]["CashFlowForecastResponse_ProjectedMonth"];
export type ForecastSummary = components["schemas"]["CashFlowForecastResponse_Summary"];
export type RecurringInfluence = components["schemas"]["CashFlowForecastResponse_RecurringInfluence"];

export type CategoryForecast = components["schemas"]["CategorySpendingForecastResponse_CategoryForecast"];
export type Trend = CategoryForecast["trend"];
export type CategoryWithoutForecast = components["schemas"]["CategorySpendingForecastResponse_CategoryWithoutForecast"];
export type CategoryForecastResponse = components["schemas"]["CategorySpendingForecastResponse"];

export type BudgetOutlookItem = components["schemas"]["BudgetOverrunRiskResponse_Item"];
export type BudgetRisk = BudgetOutlookItem["risk"];
export type BudgetOutlook = components["schemas"]["BudgetOverrunRiskResponse"];

export type SavingsPoint = components["schemas"]["SavingsProjectionResponse_Point"];
export type GoalOutlook = components["schemas"]["SavingsProjectionResponse_GoalOutlook"];
export type SavingsAccounting = components["schemas"]["SavingsProjectionResponse_Accounting"];
export type SavingsProjection = components["schemas"]["SavingsProjectionResponse"];

export type AdjustmentType = components["schemas"]["ScenarioSimulationRequest_AdjustmentType"];
export type Adjustment = components["schemas"]["ScenarioSimulationRequest_Adjustment"];
export type CategoryAdjustment = components["schemas"]["ScenarioSimulationRequest_CategoryAdjustment"];
export type AddedRecurringExpense = components["schemas"]["ScenarioSimulationRequest_AddedRecurringExpense"];
export type LoanAction = components["schemas"]["ScenarioSimulationRequest_LoanAction"];
export type LoanAdjustment = components["schemas"]["ScenarioSimulationRequest_LoanAdjustment"];
export type OneTimeItem = components["schemas"]["ScenarioSimulationRequest_OneTimeItem"];
export type ScenarioRequest = components["schemas"]["ScenarioSimulationRequest"];

export type LoanEffect = components["schemas"]["ScenarioSimulationResponse_LoanEffect"];
export type ScenarioTotals = components["schemas"]["ScenarioSimulationResponse_Totals"];
export type ScenarioDelta = components["schemas"]["ScenarioSimulationResponse_Delta"];
export type ScenarioMonth = components["schemas"]["ScenarioSimulationResponse_Month"];
export type ScenarioCategoryEffect = components["schemas"]["ScenarioSimulationResponse_CategoryEffect"];
export type ScenarioResult = components["schemas"]["ScenarioSimulationResponse"];

export type SavedScenarioSummary = components["schemas"]["SavedScenarioResponses_Summary"];
export type SavedScenarioDetail = components["schemas"]["SavedScenarioResponses_Detail"];
export type SavedScenarioRerun = components["schemas"]["SavedScenarioResponses_Rerun"];

export type ErrorMetrics = components["schemas"]["BacktestSummaryResponse_ErrorMetrics"];
export type HorizonResult = components["schemas"]["BacktestSummaryResponse_HorizonResult"];
export type Verdict = HorizonResult["verdict"];
export type BacktestSeries = components["schemas"]["BacktestSummaryResponse_SeriesResult"];
export type BacktestSummary = components["schemas"]["BacktestSummaryResponse"];
