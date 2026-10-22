import { countsTowardTotals } from "../../shared/categories";
import { currentValueOf, type Investment } from "../investments/types";
import type { Loan } from "../loans/types";
import type { Transaction } from "../transactions/types";

export interface DashboardSummary {
  totalIncome: number;
  totalExpenses: number;
  totalInvested: number;
  totalCurrentValue: number;
  activeLoans: number;
  totalEMI: number;
}

/**
 * Totals shown on the dashboard cards, computed in the browser from the lists the API returns.
 * Transfers, credit-card bill payments, investment movements and refunds are not income or
 * spending and are left out (see docs/FINANCIAL_RULES.md). Server-side analytics replace this in a
 * later release; see docs/ROADMAP.md.
 */
export function computeSummary(
  transactions: Transaction[],
  investments: Investment[],
  loans: Loan[],
): DashboardSummary {
  const totalIncome = transactions
    .filter((t) => t.type === "INCOME" && countsTowardTotals(t.category))
    .reduce((sum, t) => sum + t.amount, 0);

  const totalExpenses = transactions
    .filter((t) => t.type === "EXPENSE" && countsTowardTotals(t.category))
    .reduce((sum, t) => sum + t.amount, 0);

  const totalInvested = investments.reduce((sum, i) => sum + i.amountInvested, 0);
  const totalCurrentValue = investments.reduce((sum, i) => sum + currentValueOf(i), 0);

  const activeLoans = loans.filter((l) => l.active);
  const totalEMI = activeLoans.reduce((sum, l) => sum + l.emiAmount, 0);

  return {
    totalIncome,
    totalExpenses,
    totalInvested,
    totalCurrentValue,
    activeLoans: activeLoans.length,
    totalEMI,
  };
}

/** Gain/loss of `current` versus `invested` as a percentage string with two decimals ("0.00" when nothing is invested). */
export function gainPercent(current: number, invested: number): string {
  if (invested === 0) return "0.00";
  return (((current - invested) / invested) * 100).toFixed(2);
}
