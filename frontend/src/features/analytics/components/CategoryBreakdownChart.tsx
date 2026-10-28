import { useMemo, useState } from "react";
import { formatCurrency } from "../../../shared/utils/format";
import type { CategoryBreakdown } from "../types";

interface CategoryBreakdownChartProps {
  data: CategoryBreakdown | null;
}

// Sophisticated, accessible financial palette
const CATEGORY_COLORS: Record<string, string> = {
  HOUSING: "#0284c7",       // Sky
  GROCERIES: "#0d9488",     // Teal
  DINING: "#f59e0b",        // Amber
  TRANSPORT: "#6366f1",     // Indigo
  UTILITIES: "#3b82f6",     // Blue
  HEALTHCARE: "#ec4899",    // Pink
  INSURANCE: "#8b5cf6",     // Purple
  SHOPPING: "#f97316",      // Orange
  SUBSCRIPTIONS: "#14b8a6", // Cyan-teal
  EDUCATION: "#10b981",     // Emerald
  ENTERTAINMENT: "#a855f7", // Violet
  FEES: "#64748b",          // Slate
  LOAN_PAYMENTS: "#dc2626", // Red
  UNCATEGORIZED: "#94a3b8", // Slate light
};

const DEFAULT_COLOR = "#64748b";

export default function CategoryBreakdownChart({ data }: CategoryBreakdownChartProps) {
  const [activeCategory, setActiveCategory] = useState<string | null>(null);

  const radius = 68;
  const circumference = 2 * Math.PI * radius;

  const segments = useMemo(() => {
    if (!data) return [];
    let offset = 0;
    return data.categories.map((cat) => {
      const strokeLength = (cat.percentage / 100) * circumference;
      const strokeDasharray = `${strokeLength} ${circumference - strokeLength}`;
      const strokeDashoffset = -offset;
      offset += strokeLength;
      const color = CATEGORY_COLORS[cat.category] ?? DEFAULT_COLOR;
      return {
        cat,
        color,
        strokeDasharray,
        strokeDashoffset,
      };
    });
  }, [data, circumference]);

  if (!data || data.categories.length === 0) {
    return (
      <div className="rounded-2xl bg-white border border-slate-200/80 p-8 text-center shadow-sm">
        <h3 className="text-base font-semibold text-slate-900 mb-1">Category Spending</h3>
        <p className="text-xs text-slate-500 py-8">
          No categorized spending transactions in this period.
        </p>
      </div>
    );
  }

  const { categories, totalNetExpenses, totalGrossExpenses, totalRefunds } = data;

  return (
    <div className="rounded-2xl bg-white border border-slate-200/80 p-6 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-2 mb-6">
        <div>
          <h3 className="text-base font-semibold text-slate-900">Spending by Category</h3>
          <p className="text-xs text-slate-500">
            Breakdown across classified expense categories
            {totalRefunds > 0 && ` (offset by ${formatCurrency(totalRefunds)} refunds)`}
          </p>
        </div>
        <div className="text-right">
          <span className="text-xs text-slate-400 uppercase tracking-wider font-semibold block">Net Expenses</span>
          <span className="text-lg font-bold text-slate-900">{formatCurrency(totalNetExpenses)}</span>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-12 gap-8 items-center">
        {/* Donut Chart */}
        <div className="md:col-span-5 flex flex-col items-center justify-center relative">
          <svg width="190" height="190" viewBox="0 0 190 190" className="transform -rotate-90">
            {/* Background ring */}
            <circle
              cx="95"
              cy="95"
              r={radius}
              fill="transparent"
              stroke="#f1f5f9"
              strokeWidth="22"
            />
            {/* Category segments */}
            {segments.map(({ cat, color, strokeDasharray, strokeDashoffset }) => {
              const isSelected = activeCategory === cat.category;

              return (
                <circle
                  key={cat.category}
                  cx="95"
                  cy="95"
                  r={radius}
                  fill="transparent"
                  stroke={color}
                  strokeWidth={isSelected ? 26 : 22}
                  strokeDasharray={strokeDasharray}
                  strokeDashoffset={strokeDashoffset}
                  className="transition-all duration-200 cursor-pointer"
                  onMouseEnter={() => setActiveCategory(cat.category)}
                  onMouseLeave={() => setActiveCategory(null)}
                />
              );
            })}
          </svg>

          {/* Center text in donut */}
          <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none text-center">
            {activeCategory ? (
              (() => {
                const active = categories.find((c) => c.category === activeCategory);
                return active ? (
                  <>
                    <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-tight">
                      {active.label}
                    </span>
                    <span className="text-base font-bold text-slate-900">
                      {formatCurrency(active.amount)}
                    </span>
                    <span className="text-[11px] font-medium text-slate-400">
                      {active.percentage}%
                    </span>
                  </>
                ) : null;
              })()
            ) : (
              <>
                <span className="text-[11px] font-semibold text-slate-400 uppercase tracking-tight">
                  Total Spend
                </span>
                <span className="text-base font-bold text-slate-900">
                  {formatCurrency(totalGrossExpenses)}
                </span>
                <span className="text-[11px] text-slate-400">
                  {categories.length} categories
                </span>
              </>
            )}
          </div>
        </div>

        {/* Category List */}
        <div className="md:col-span-7 space-y-2.5 max-h-[280px] overflow-y-auto pr-1">
          {categories.map((cat) => {
            const color = CATEGORY_COLORS[cat.category] ?? DEFAULT_COLOR;
            const isSelected = activeCategory === cat.category;

            return (
              <div
                key={cat.category}
                onMouseEnter={() => setActiveCategory(cat.category)}
                onMouseLeave={() => setActiveCategory(null)}
                className={`flex items-center justify-between p-2.5 rounded-xl border transition-all cursor-pointer ${
                  isSelected
                    ? "bg-slate-50 border-slate-300 shadow-sm"
                    : "border-slate-100 hover:bg-slate-50/60"
                }`}
              >
                <div className="flex items-center gap-2.5 min-w-0">
                  <span
                    className="w-3 h-3 rounded-full flex-shrink-0"
                    style={{ backgroundColor: color }}
                  />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5 flex-wrap">
                      <span className="text-xs font-semibold text-slate-800" title={cat.label}>
                        {cat.label}
                      </span>
                      {cat.isIncreasing && (
                        <span className="text-[10px] font-medium px-1.5 py-0.2 rounded bg-amber-50 text-amber-700 border border-amber-200">
                          Rising 2+ mos
                        </span>
                      )}
                    </div>
                    <span className="text-[11px] text-slate-400">
                      {cat.transactionCount} txn{cat.transactionCount !== 1 ? "s" : ""} · avg {formatCurrency(cat.averageTransactionAmount)}
                    </span>
                  </div>
                </div>

                <div className="text-right flex-shrink-0 ml-3">
                  <div className="text-xs font-bold text-slate-900">
                    {formatCurrency(cat.amount)}
                  </div>
                  <div className="flex items-center justify-end gap-1.5 text-[11px]">
                    <span className="text-slate-500 font-medium">{cat.percentage}%</span>
                    {cat.momChangePct !== null && (
                      <span
                        className={
                          cat.momChangePct > 0
                            ? "text-rose-600 font-medium"
                            : "text-emerald-600 font-medium"
                        }
                      >
                        {cat.momChangePct > 0 ? "↑" : "↓"}
                        {Math.abs(cat.momChangePct).toFixed(1)}%
                      </span>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
