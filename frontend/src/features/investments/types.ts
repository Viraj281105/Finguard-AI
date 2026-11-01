import type { components } from "../../shared/api/generated/openapi";

export type InvestmentType = components["schemas"]["InvestmentType"];

/** Mirrors `InvestmentResponse` in the backend (generated from OpenAPI contract). */
export type Investment = components["schemas"]["InvestmentResponse"];
export type InvestmentInput = components["schemas"]["InvestmentRequest"];

/** The current value, or the amount invested when no valuation was recorded (so no false loss is shown). */
export const currentValueOf = (inv: Investment): number => inv.currentValue ?? inv.amountInvested;
