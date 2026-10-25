import type { BudgetRisk, Reliability, Trend, Verdict } from "./types";

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "2026-08" -> "Aug 2026". Anything that is not yyyy-MM is returned unchanged. */
export function monthLabel(yearMonth: string): string {
  const match = /^(\d{4})-(\d{2})$/.exec(yearMonth);
  if (!match) return yearMonth;
  return `${MONTHS[Number(match[2]) - 1] ?? match[2]} ${match[1]}`;
}

/** "2026-08" -> "Aug" (chart axis). */
export function shortMonth(yearMonth: string): string {
  const match = /^(\d{4})-(\d{2})$/.exec(yearMonth);
  return match ? (MONTHS[Number(match[2]) - 1] ?? yearMonth) : yearMonth;
}

export function signed(amount: number, format: (n: number) => string): string {
  return `${amount > 0 ? "+" : amount < 0 ? "−" : ""}${format(Math.abs(amount))}`;
}

export const RELIABILITY: Record<Reliability, { label: string; tone: string; help: string }> = {
  RELIABLE: {
    label: "Enough history",
    tone: "bg-emerald-50 text-emerald-700 border-emerald-200",
    help: "At least 6 complete months, no recent gaps, regular income. This describes the evidence, not the accuracy.",
  },
  LIMITED: {
    label: "Limited history",
    tone: "bg-amber-50 text-amber-800 border-amber-200",
    help: "Between 3 and 5 months, or gaps, a stale import, or irregular or missing income. Treat as a rough guide.",
  },
  UNAVAILABLE: {
    label: "Not available",
    tone: "bg-slate-100 text-slate-600 border-slate-200",
    help: "Fewer than 3 complete months of data: nothing is projected rather than guessing.",
  },
};

export const RISK: Record<BudgetRisk, { label: string; tone: string }> = {
  EXCEEDED: { label: "Already over budget", tone: "bg-rose-50 text-rose-700 border-rose-200" },
  LIKELY_OVER: { label: "Likely to exceed", tone: "bg-rose-50 text-rose-700 border-rose-200" },
  AT_RISK: { label: "At risk", tone: "bg-amber-50 text-amber-800 border-amber-200" },
  ON_TRACK: { label: "On track", tone: "bg-emerald-50 text-emerald-700 border-emerald-200" },
  INDETERMINATE: { label: "Not enough data", tone: "bg-slate-100 text-slate-600 border-slate-200" },
  COMPLETED_WITHIN: { label: "Finished within budget", tone: "bg-emerald-50 text-emerald-700 border-emerald-200" },
  COMPLETED_OVER: { label: "Finished over budget", tone: "bg-rose-50 text-rose-700 border-rose-200" },
};

export const TREND: Record<Trend, { label: string; tone: string }> = {
  UPWARD: { label: "Rising", tone: "bg-rose-50 text-rose-700 border-rose-200" },
  DOWNWARD: { label: "Falling", tone: "bg-emerald-50 text-emerald-700 border-emerald-200" },
  STABLE: { label: "Stable", tone: "bg-slate-100 text-slate-600 border-slate-200" },
  NOT_ESTABLISHED: { label: "Trend not tested", tone: "bg-slate-100 text-slate-500 border-slate-200" },
};

export const VERDICT: Record<Verdict, string> = {
  MODEL_BETTER: "Better than simple baselines",
  SIMILAR: "Similar to simple baselines",
  BASELINE_BETTER: "Worse than a simple baseline",
  INCONCLUSIVE: "Inconclusive: too few forecasts to judge",
};

const rupees = new Intl.NumberFormat("en-IN", { minimumFractionDigits: 0, maximumFractionDigits: 2 });

/**
 * The backend writes explanations with plain amounts ("₹33100.00"). This regroups them for reading
 * ("₹33,100") without touching any number: it is presentation only.
 */
export function tidyMoney(text: string): string {
  return text.replace(/₹(\d+(?:\.\d+)?)/g, (_, digits: string) => `₹${rupees.format(Number(digits))}`);
}
