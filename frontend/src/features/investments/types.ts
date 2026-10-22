export type InvestmentType = "STOCKS" | "MUTUAL_FUND" | "FD" | "CRYPTO" | "GOLD" | "OTHER";

/** Mirrors `InvestmentResponse` in the backend (see docs/API.md). */
export interface Investment {
  id: string;
  name: string;
  type: InvestmentType;
  amountInvested: number;
  /** Optional on the API; use `currentValueOf` for a safe number. */
  currentValue: number | null;
  notes: string | null;
  createdAt: string;
}

export interface InvestmentInput {
  name: string;
  type: InvestmentType;
  amountInvested: number;
  currentValue?: number;
  notes?: string;
}

/** The current value, or the amount invested when no valuation was recorded (so no false loss is shown). */
export const currentValueOf = (inv: Investment): number => inv.currentValue ?? inv.amountInvested;
