import { useNavigate } from "react-router-dom";
import { formatCurrency } from "../../../shared/utils/format";
import type { MonthlyBudgetOverview, Goal } from "../types";

interface BudgetHealthPreviewCardProps {
  overview: MonthlyBudgetOverview | null;
  goals: Goal[];
}

export default function BudgetHealthPreviewCard({ overview, goals }: BudgetHealthPreviewCardProps) {
  const navigate = useNavigate();

  const activeGoals = goals.filter((g) => g.status === "ACTIVE");
  const totalGoalSavings = activeGoals.reduce((sum, g) => sum + g.currentAmount, 0);

  return (
    <div className="bg-white p-5 rounded-2xl border border-slate-200/80 shadow-xs mb-8">
      <div className="flex flex-wrap items-center justify-between gap-4 mb-4">
        <div>
          <h2 className="text-sm font-bold text-slate-900">Budget Adherence & Financial Goals</h2>
          <p className="text-xs text-slate-500 mt-0.5">
            Active spending controls and goal allocations for this period
          </p>
        </div>
        <button
          onClick={() => navigate("/budgets")}
          className="text-xs font-semibold text-sky-600 hover:text-sky-700 bg-sky-50 hover:bg-sky-100 px-3 py-1.5 rounded-xl border border-sky-200/60 transition-colors"
        >
          Manage Budgets & Goals →
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Budget Pillar */}
        <div className="p-3.5 rounded-xl bg-slate-50 border border-slate-200/60 flex flex-col justify-between">
          <div>
            <span className="text-[11px] font-bold text-slate-500 uppercase tracking-wider">
              Monthly Budget Utilization
            </span>
            {overview?.overallBudget ? (
              <div className="mt-2 space-y-1.5">
                <div className="flex justify-between items-baseline">
                  <span className="text-base font-extrabold text-slate-900">
                    {formatCurrency(overview.overallBudget.actualSpend)}
                  </span>
                  <span className="text-xs text-slate-500">
                    of {formatCurrency(overview.overallBudget.budgetedAmount)}
                  </span>
                </div>

                <div className="w-full bg-slate-200 rounded-full h-2 overflow-hidden">
                  <div
                    className={`h-full rounded-full transition-all duration-300 ${
                      overview.overallBudget.status === "OVER_BUDGET"
                        ? "bg-rose-500"
                        : overview.overallBudget.status === "APPROACHING_LIMIT"
                        ? "bg-amber-500"
                        : "bg-emerald-500"
                    }`}
                    style={{ width: `${Math.min(overview.overallBudget.percentageUsed, 100)}%` }}
                  />
                </div>

                <div className="flex justify-between items-center text-[11px] text-slate-500">
                  <span>{overview.overallBudget.percentageUsed.toFixed(1)}% spent</span>
                  <span>
                    {overview.categoriesOverBudgetCount > 0
                      ? `${overview.categoriesOverBudgetCount} category over limit`
                      : "All category budgets healthy"}
                  </span>
                </div>
              </div>
            ) : overview && overview.categoryBudgets.length > 0 ? (
              <div className="mt-2">
                <p className="text-sm font-bold text-slate-800">
                  {overview.categoryBudgets.length} Category Budgets Active
                </p>
                <p className="text-xs text-slate-500 mt-1">
                  {overview.categoriesOverBudgetCount > 0
                    ? `${overview.categoriesOverBudgetCount} exceeded limit`
                    : "Tracking category outlays"}
                </p>
              </div>
            ) : (
              <div className="mt-2">
                <p className="text-xs text-slate-500">No monthly budgets configured yet.</p>
                <button
                  onClick={() => navigate("/budgets")}
                  className="mt-2 text-xs font-semibold text-sky-600 hover:underline"
                >
                  + Set monthly budget
                </button>
              </div>
            )}
          </div>
        </div>

        {/* Goals Pillar */}
        <div className="p-3.5 rounded-xl bg-slate-50 border border-slate-200/60 flex flex-col justify-between">
          <div>
            <span className="text-[11px] font-bold text-slate-500 uppercase tracking-wider">
              Savings Goals Allocation
            </span>
            {activeGoals.length > 0 ? (
              <div className="mt-2 space-y-1.5">
                <div className="flex justify-between items-baseline">
                  <span className="text-base font-extrabold text-slate-900">
                    {formatCurrency(totalGoalSavings)}
                  </span>
                  <span className="text-xs text-slate-500">
                    across {activeGoals.length} {activeGoals.length === 1 ? "active goal" : "active goals"}
                  </span>
                </div>
                <div className="text-xs text-slate-600 truncate mt-1">
                  Top target: <strong className="text-slate-800">{activeGoals[0].name}</strong> ({activeGoals[0].progressPercentage.toFixed(0)}% saved)
                </div>
              </div>
            ) : (
              <div className="mt-2">
                <p className="text-xs text-slate-500">No active savings goals tracked yet.</p>
                <button
                  onClick={() => navigate("/budgets")}
                  className="mt-2 text-xs font-semibold text-sky-600 hover:underline"
                >
                  + Create a financial goal
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
