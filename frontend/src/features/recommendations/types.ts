import type { components } from "../../shared/api/generated/openapi";

/** Mirrors `RecommendationsResponse` in the backend (generated from contracts/openapi/finguard-api.v1.json). */
export type Severity = components["schemas"]["RecommendationsResponse_Severity"];
export type RecommendationEvidence = components["schemas"]["RecommendationsResponse_Evidence"];
export type ScenarioPreset = components["schemas"]["RecommendationsResponse_ScenarioPreset"];
export type Recommendation = components["schemas"]["RecommendationsResponse_Recommendation"];
export type NotEvaluated = components["schemas"]["RecommendationsResponse_NotEvaluated"];
export type Recommendations = components["schemas"]["RecommendationsResponse"];
