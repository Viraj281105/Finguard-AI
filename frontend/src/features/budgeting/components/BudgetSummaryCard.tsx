import { formatCurrency } from "../../../shared/utils/format";
import type { BudgetPerformanceSummary } from "../types";

interface BudgetSummaryCardProps {
  overallBudget: BudgetPerformanceSummary | null;
  totalActualExpenses: number;
  totalCategoryBudgeted: number;
  unbudgetedSpending: number;
  onSetOverallBudget: () => void;
}

export default function BudgetSummaryCard({
  overallBudget,
  totalActualExpenses,
  totalCategoryBudgeted,
  unbudgetedSpending,
  onSetOverallBudget,
}: BudgetSummaryCardProps) {
  if (!overallBudget) {
    return (
      <div className="bg-white p-5 rounded-2xl border border-slate-200/80 shadow-xs mb-6">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <h2 className="text-sm font-bold text-slate-900">Overall Monthly Budget</h2>
            <p className="text-xs text-slate-500 mt-0.5">
              Set a spending ceiling for the entire month to monitor total cash outflows.
            </p>
          </div>
          <button
            onClick={onSetOverallBudget}
            className="px-4 py-2 text-xs font-semibold text-white bg-sky-600 hover:bg-sky-700 rounded-xl transition-colors shadow-2xs"
          >
            + Set Overall Budget
          </button>
        </div>

        <div className="mt-4 pt-4 border-t border-slate-100 grid grid-cols-2 sm:grid-cols-3 gap-3">
          <div>
            <span className="text-xs text-slate-500">Actual Outflows</span>
            <p className="text-base font-extrabold text-slate-900 mt-0.5">
              {formatCurrency(totalActualExpenses)}
            </p>
          </div>
          <div>
            <span className="text-xs text-slate-500">Category Budgets Total</span>
            <p className="text-base font-extrabold text-slate-900 mt-0.5">
              {formatCurrency(totalCategoryBudgeted)}
            </p>
          </div>
          <div>
            <span className="text-xs text-slate-500">Unbudgeted Spend</span>
            <p className="text-base font-extrabold text-slate-900 mt-0.5">
              {formatCurrency(unbudgetedSpending)}
            </p>
          </div>
        </div>
      </div>
    );
  }

  const { budgetedAmount, actualSpend, remainingAmount, percentageUsed, status } = overallBudget;

  const getStatusBadge = () => {
    switch (status) {
      case "OVER_BUDGET":
        return {
          bg: "bg-rose-50 text-rose-700 border-rose-200",
          label: "Over Budget",
          barColor: "bg-rose-500",
        };
      case "APPROACHING_LIMIT":
        return {
          bg: "bg-amber-50 text-amber-700 border-amber-200",
          label: "Approaching Limit (≥80%)",
          barColor: "bg-amber-500",
        };
      default:
        return {
          bg: "bg-emerald-50 text-emerald-700 border-emerald-200",
          label: "On Track",
          barColor: "bg-emerald-500",
        };
    }
  };

  const badge = getStatusBadge();
  const cappedPercentage = Math.min(percentageUsed, 100);

  return (
    <div className="bg-white p-5 rounded-2xl border border-slate-200/80 shadow-xs mb-6">
      <div className="flex flex-wrap items-center justify-between gap-4 mb-4">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-sm font-bold text-slate-900">Overall Monthly Budget</h2>
            <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full border ${badge.bg}`}>
              {badge.label}
            </span>
          </div>
          <p className="text-xs text-slate-500 mt-0.5">
            Total recorded consumer outflows against your monthly spending limit
          </p>
        </div>

        <button
          onClick={onSetOverallBudget}
          className="text-xs font-semibold text-slate-600 hover:text-slate-900 bg-slate-50 hover:bg-slate-100 px-3 py-1.5 rounded-xl border border-slate-200 transition-colors"
        >
          Edit Budget
        </button>
      </div>

      {/* Progress Bar */}
      <div className="space-y-1.5 mb-4">
        <div className="w-full bg-slate-100 rounded-full h-3 overflow-hidden">
          <div
            className={`h-full rounded-full transition-all duration-300 ${badge.barColor}`}
            style={{ width: `${cappedPercentage}%` }}
          />
        </div>
        <div className="flex justify-between items-center text-xs">
          <span className="font-semibold text-slate-700">
            {percentageUsed.toFixed(1)}% utilized
          </span>
          <span className="text-slate-500">
            {remainingAmount >= 0
              ? `${formatCurrency(remainingAmount)} remaining`
              : `${formatCurrency(Math.abs(remainingAmount))} over budget`}
          </span>
        </div>
      </div>

      {/* Metrics Grid */}
      <div className="pt-3 border-t border-slate-100 grid grid-cols-2 sm:grid-cols-4 gap-3">
        <div>
          <span className="text-xs text-slate-500">Budget Limit</span>
          <p className="text-base font-extrabold text-slate-900 mt-0.5">
            {formatCurrency(budgetedAmount)}
          </p>
        </div>
        <div>
          <span className="text-xs text-slate-500">Actual Outflows</span>
          <p className="text-base font-extrabold text-slate-900 mt-0.5">
            {formatCurrency(actualSpend)}
          </p>
        </div>
        <div>
          <span className="text-xs text-slate-500">Category Budgets</span>
          <p className="text-base font-extrabold text-slate-900 mt-0.5">
            {formatCurrency(totalCategoryBudgeted)}
          </p>
        </div>
        <div>
          <span className="text-xs text-slate-500">Unbudgeted Spend</span>
          <p className="text-base font-extrabold text-slate-900 mt-0.5">
            {formatCurrency(unbudgetedSpending)}
          </p>
        </div>
      </div>
    </div>
  );
}
