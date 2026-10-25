import { formatCurrency } from "../../../shared/utils/format";
import type { CategoryBudgetPerformance } from "../types";

interface CategoryBudgetCardProps {
  item: CategoryBudgetPerformance;
  onEdit: (item: CategoryBudgetPerformance) => void;
  onDelete: (budgetId: string) => void;
}

export default function CategoryBudgetCard({ item, onEdit, onDelete }: CategoryBudgetCardProps) {
  const { categoryLabel, budgetedAmount, actualSpend, remainingAmount, percentageUsed, status, transactionCount } = item;

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
          label: "Approaching Limit",
          barColor: "bg-amber-500",
        };
      default:
        return {
          bg: "bg-emerald-50 text-emerald-700 border-emerald-200",
          label: "Under Budget",
          barColor: "bg-emerald-500",
        };
    }
  };

  const badge = getStatusBadge();
  const cappedPercentage = Math.min(percentageUsed, 100);

  return (
    <div className="bg-white p-4 rounded-xl border border-slate-200/80 shadow-2xs hover:shadow-xs transition-shadow">
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-bold text-slate-900">{categoryLabel}</h3>
          <span className={`text-[10px] font-semibold px-2 py-0.5 rounded-full border ${badge.bg}`}>
            {badge.label}
          </span>
        </div>

        <div className="flex items-center gap-1">
          <button
            onClick={() => onEdit(item)}
            className="p-1 text-slate-400 hover:text-slate-600 hover:bg-slate-100 rounded-lg transition-colors text-xs font-medium"
            title="Edit Budget"
          >
            Edit
          </button>
          <button
            onClick={() => onDelete(item.budgetId)}
            className="p-1 text-slate-400 hover:text-rose-600 hover:bg-rose-50 rounded-lg transition-colors text-xs font-medium"
            title="Delete Budget"
          >
            Delete
          </button>
        </div>
      </div>

      {/* Progress Bar */}
      <div className="space-y-1 mb-3">
        <div className="w-full bg-slate-100 rounded-full h-2 overflow-hidden">
          <div
            className={`h-full rounded-full transition-all duration-300 ${badge.barColor}`}
            style={{ width: `${cappedPercentage}%` }}
          />
        </div>
        <div className="flex justify-between items-center text-[11px] text-slate-500">
          <span>{percentageUsed.toFixed(1)}% spent</span>
          <span>
            {remainingAmount >= 0
              ? `${formatCurrency(remainingAmount)} left`
              : `${formatCurrency(Math.abs(remainingAmount))} exceeded`}
          </span>
        </div>
      </div>

      <div className="flex justify-between items-center text-xs pt-2 border-t border-slate-100">
        <div>
          <span className="text-slate-400 text-[10px]">Actual Outflow</span>
          <p className="font-bold text-slate-800">{formatCurrency(actualSpend)}</p>
        </div>
        <div className="text-right">
          <span className="text-slate-400 text-[10px]">Budget Cap</span>
          <p className="font-bold text-slate-900">{formatCurrency(budgetedAmount)}</p>
        </div>
      </div>

      {transactionCount > 0 && (
        <div className="mt-2 text-[10px] text-slate-400 text-right">
          {transactionCount} {transactionCount === 1 ? "transaction" : "transactions"}
        </div>
      )}
    </div>
  );
}
