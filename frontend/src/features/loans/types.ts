export type LoanType = "HOME" | "CAR" | "PERSONAL" | "EDUCATION" | "OTHER";

/** Mirrors `LoanResponse` in the backend (see docs/API.md). Dates are ISO `yyyy-MM-dd`. */
export interface Loan {
  id: string;
  name: string;
  type: LoanType;
  principal: number;
  emiAmount: number;
  interestRate: number;
  startDate: string | null;
  endDate: string | null;
  active: boolean;
  createdAt: string;
}

export interface LoanInput {
  name: string;
  type: LoanType;
  principal: number;
  emiAmount: number;
  interestRate: number;
  startDate?: string;
  endDate?: string;
  active: boolean;
}
