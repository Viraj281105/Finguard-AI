import type { components } from "../../shared/api/generated/openapi";

export type LoanType = components["schemas"]["LoanType"];

/** Mirrors `LoanResponse` in the backend (generated from OpenAPI contract). Dates are ISO `yyyy-MM-dd`. */
export type Loan = components["schemas"]["LoanResponse"];
export type LoanInput = components["schemas"]["LoanRequest"];
