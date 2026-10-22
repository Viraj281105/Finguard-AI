import { investmentAPI } from "../investments/api";
import { loanAPI } from "../loans/api";
import { transactionAPI } from "../transactions/api";
import type { Transaction } from "../transactions/types";
import { computeSummary, gainPercent, type DashboardSummary } from "./summary";

export interface DashboardData {
  summary: DashboardSummary;
  recentTransactions: Transaction[];
  gainPct: string;
}

const RECENT_COUNT = 5;

/** Fetches everything the dashboard shows. The API returns transactions newest first. */
export async function loadDashboardData(): Promise<DashboardData> {
  const [transactions, investments, loans] = await Promise.all([
    transactionAPI.getAll(),
    investmentAPI.getAll(),
    loanAPI.getAll(),
  ]);
  const summary = computeSummary(transactions, investments, loans);
  return {
    summary,
    recentTransactions: transactions.slice(0, RECENT_COUNT),
    gainPct: gainPercent(summary.totalCurrentValue, summary.totalInvested),
  };
}
