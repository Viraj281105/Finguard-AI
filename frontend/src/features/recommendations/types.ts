/** Mirrors `RecommendationsResponse` in the backend (docs/API.md, GET /api/recommendations). */
export type Severity = "CRITICAL" | "HIGH" | "MEDIUM" | "INFO";

export interface RecommendationEvidence {
  metric: string;
  value: number;
  unit: string;
  /** An analytics API path (with the period) the number can be reproduced from. */
  source: string;
}

export interface ScenarioPreset {
  label: string;
  /** A body for POST /api/forecast/scenarios/simulate. */
  request: {
    horizonMonths?: number;
    categoryAdjustments?: { category: string; type: "PERCENTAGE" | "FIXED_AMOUNT"; value: number }[];
  };
}

export interface Recommendation {
  id: string;
  severity: Severity;
  title: string;
  explanation: string;
  suggestedAction: string;
  evidence: RecommendationEvidence[];
  estimatedMonthlyImpact: number | null;
  scenarioPreset: ScenarioPreset | null;
}

export interface NotEvaluated {
  id: string;
  title: string;
  reason: string;
}

export interface Recommendations {
  period: { from: string; to: string; months: number; monthsWithData: number };
  disclaimer: string;
  healthy: boolean;
  recommendations: Recommendation[];
  notEvaluated: NotEvaluated[];
  thresholds: Record<string, number>;
}
