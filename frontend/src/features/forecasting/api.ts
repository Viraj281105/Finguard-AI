import { apiClient } from "../../shared/api/client";
import type {
  BacktestSummary,
  BudgetOutlook,
  CashFlowForecast,
  CategoryForecastResponse,
  SavedScenarioDetail,
  SavedScenarioRerun,
  SavedScenarioSummary,
  SavingsProjection,
  ScenarioRequest,
  ScenarioResult,
} from "./types";

/** Forecasts are calculated on demand by the backend from the persisted ledger; nothing here is cached or stored. */
export const forecastAPI = {
  getCashFlow: (horizonMonths = 3) =>
    apiClient.get<CashFlowForecast>("/forecast/cash-flow", { params: { horizonMonths } }).then((r) => r.data),

  getCategories: (horizonMonths = 3) =>
    apiClient.get<CategoryForecastResponse>("/forecast/categories", { params: { horizonMonths } }).then((r) => r.data),

  getBudgetOutlook: (yearMonth?: string) =>
    apiClient.get<BudgetOutlook>("/forecast/budget-overruns", { params: yearMonth ? { yearMonth } : {} }).then((r) => r.data),

  getSavings: (horizonMonths = 6) =>
    apiClient.get<SavingsProjection>("/forecast/savings", { params: { horizonMonths } }).then((r) => r.data),

  /** Hypothetical calculation only: the backend reads the ledger and writes nothing. */
  simulateScenario: (request: ScenarioRequest) =>
    apiClient.post<ScenarioResult>("/forecast/scenarios/simulate", request).then((r) => r.data),

  /** Saved scenarios keep the adjustments and a result snapshot only; re-running recalculates on the current ledger. */
  saveScenario: (request: ScenarioRequest) =>
    apiClient.post<SavedScenarioDetail>("/scenarios", request).then((r) => r.data),

  listScenarios: () => apiClient.get<SavedScenarioSummary[]>("/scenarios").then((r) => r.data),

  getScenario: (id: string) => apiClient.get<SavedScenarioDetail>(`/scenarios/${id}`).then((r) => r.data),

  rerunScenario: (id: string) => apiClient.post<SavedScenarioRerun>(`/scenarios/${id}/rerun`).then((r) => r.data),

  deleteScenario: (id: string) => apiClient.delete(`/scenarios/${id}`).then(() => undefined),

  getBacktest: () => apiClient.get<BacktestSummary>("/forecast/backtest").then((r) => r.data),
};
