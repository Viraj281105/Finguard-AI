import { useState } from "react";
import { formatCurrency } from "../../../shared/utils/format";
import type { MonthlyFlow } from "../types";

interface CashFlowChartProps {
  data: MonthlyFlow[];
}

export default function CashFlowChart({ data }: CashFlowChartProps) {
  const [hoveredIdx, setHoveredIdx] = useState<number | null>(null);

  if (!data || data.length === 0) {
    return (
      <div className="rounded-2xl bg-white border border-slate-200/80 p-8 text-center shadow-sm">
        <h3 className="text-sm font-semibold text-slate-800 mb-1">Cash Flow Trend</h3>
        <p className="text-xs text-slate-500 py-8">
          No monthly cash flow data available for the selected period. Import bank statements to view your timeline.
        </p>
      </div>
    );
  }

  // Calculate scales
  const maxVal = Math.max(
    ...data.map((d) => Math.max(d.income, d.expenses)),
    1000
  );

  // Layout parameters for SVG
  const chartHeight = 220;
  const chartWidth = Math.max(480, data.length * 80);
  const paddingLeft = 60;
  const paddingRight = 30;
  const paddingTop = 20;
  const paddingBottom = 40;
  const plotWidth = chartWidth - paddingLeft - paddingRight;
  const plotHeight = chartHeight - paddingTop - paddingBottom;

  const barGroupWidth = plotWidth / data.length;
  const barWidth = Math.min(22, (barGroupWidth - 14) / 2);

  // Y-axis grid ticks (4 ticks)
  const ticks = [0, maxVal * 0.33, maxVal * 0.66, maxVal];

  return (
    <div className="rounded-2xl bg-white border border-slate-200/80 p-6 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-4 mb-4">
        <div>
          <h3 className="text-base font-semibold text-slate-900">Monthly Cash Flow</h3>
          <p className="text-xs text-slate-500">Inflows vs outflows across calendar months</p>
        </div>

        {/* Legend */}
        <div className="flex items-center gap-4 text-xs">
          <div className="flex items-center gap-1.5">
            <span className="w-3 h-3 rounded-sm bg-sky-500" />
            <span className="text-slate-600 font-medium">Income</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span className="w-3 h-3 rounded-sm bg-slate-400" />
            <span className="text-slate-600 font-medium">Expenses</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-emerald-500 ring-2 ring-emerald-200" />
            <span className="text-slate-600 font-medium">Net Surplus</span>
          </div>
        </div>
      </div>

      <div className="relative overflow-x-auto">
        <svg
          viewBox={`0 0 ${chartWidth} ${chartHeight}`}
          className="w-full h-auto min-w-[480px]"
          aria-label="Monthly cash flow bar chart"
        >
          {/* Grid lines */}
          {ticks.map((t, i) => {
            const y = paddingTop + plotHeight - (t / maxVal) * plotHeight;
            return (
              <g key={i}>
                <line
                  x1={paddingLeft}
                  y1={y}
                  x2={chartWidth - paddingRight}
                  y2={y}
                  stroke="#f1f5f9"
                  strokeWidth="1"
                />
                <text
                  x={paddingLeft - 8}
                  y={y + 3}
                  textAnchor="end"
                  fontSize="10"
                  fill="#94a3b8"
                >
                  {t >= 1000 ? `₹${Math.round(t / 1000)}k` : `₹${Math.round(t)}`}
                </text>
              </g>
            );
          })}

          {/* Bars and Data */}
          {data.map((item, idx) => {
            const groupCenterX = paddingLeft + idx * barGroupWidth + barGroupWidth / 2;
            const incomeH = maxVal > 0 ? (item.income / maxVal) * plotHeight : 0;
            const expenseH = maxVal > 0 ? (item.expenses / maxVal) * plotHeight : 0;

            const incomeX = groupCenterX - barWidth - 1;
            const incomeY = paddingTop + plotHeight - incomeH;

            const expenseX = groupCenterX + 1;
            const expenseY = paddingTop + plotHeight - expenseH;

            const isHovered = hoveredIdx === idx;
            const isSurplus = item.netCashFlow >= 0;

            return (
              <g
                key={item.month}
                onMouseEnter={() => setHoveredIdx(idx)}
                onMouseLeave={() => setHoveredIdx(null)}
                className="cursor-pointer transition-opacity"
              >
                {/* Hover column background highlight */}
                {isHovered && (
                  <rect
                    x={paddingLeft + idx * barGroupWidth}
                    y={paddingTop}
                    width={barGroupWidth}
                    height={plotHeight}
                    fill="#f8fafc"
                    rx="4"
                  />
                )}

                {/* Income Bar */}
                <rect
                  x={incomeX}
                  y={incomeY}
                  width={barWidth}
                  height={Math.max(2, incomeH)}
                  rx="3"
                  fill={isHovered ? "#0284c7" : "#0ea5e9"}
                  className="transition-colors duration-150"
                />

                {/* Expense Bar */}
                <rect
                  x={expenseX}
                  y={expenseY}
                  width={barWidth}
                  height={Math.max(2, expenseH)}
                  rx="3"
                  fill={isHovered ? "#475569" : "#64748b"}
                  className="transition-colors duration-150"
                />

                {/* Net indicator dot */}
                <circle
                  cx={groupCenterX}
                  cy={paddingTop + plotHeight - ((item.income - item.expenses) / maxVal) * plotHeight}
                  r="3.5"
                  fill={isSurplus ? "#10b981" : "#ef4444"}
                  stroke="#ffffff"
                  strokeWidth="1.5"
                />

                {/* Month label on X axis */}
                <text
                  x={groupCenterX}
                  y={chartHeight - 12}
                  textAnchor="middle"
                  fontSize="10.5"
                  fill={isHovered ? "#0f172a" : "#64748b"}
                  fontWeight={isHovered ? "600" : "500"}
                >
                  {formatMonthLabel(item.month)}
                </text>
              </g>
            );
          })}
        </svg>

        {/* Hover Tooltip Overlay */}
        {hoveredIdx !== null && data[hoveredIdx] && (
          <div
            className="absolute z-20 pointer-events-none rounded-xl bg-slate-900 text-white p-3 shadow-xl text-xs border border-slate-700 min-w-[180px]"
            style={{
              left: `${Math.min(
                Math.max(10, ((hoveredIdx + 0.5) / data.length) * 100 - 15),
                70
              )}%`,
              top: "10px",
            }}
          >
            <div className="font-semibold text-slate-200 border-b border-slate-700 pb-1.5 mb-2">
              {formatMonthFull(data[hoveredIdx].month)}
            </div>
            <div className="space-y-1">
              <div className="flex justify-between text-slate-300">
                <span>Income:</span>
                <span className="font-medium text-sky-400">
                  {formatCurrency(data[hoveredIdx].income)}
                </span>
              </div>
              <div className="flex justify-between text-slate-300">
                <span>Expenses:</span>
                <span className="font-medium text-slate-300">
                  {formatCurrency(data[hoveredIdx].expenses)}
                </span>
              </div>
              <div className="flex justify-between border-t border-slate-700/60 pt-1 font-semibold">
                <span>Net Cash Flow:</span>
                <span
                  className={
                    data[hoveredIdx].netCashFlow >= 0
                      ? "text-emerald-400"
                      : "text-rose-400"
                  }
                >
                  {formatCurrency(data[hoveredIdx].netCashFlow)}
                </span>
              </div>
              {data[hoveredIdx].savingsRate !== null && (
                <div className="flex justify-between text-[11px] text-slate-400 pt-0.5">
                  <span>Savings Rate:</span>
                  <span className="text-slate-200 font-medium">
                    {data[hoveredIdx].savingsRate}%
                  </span>
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function formatMonthLabel(ym?: string): string {
  if (!ym) return "";
  const parts = ym.split("-");
  if (parts.length < 2) return ym;
  const [year, month] = parts;
  const monthNames = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
  ];
  const mIdx = parseInt(month, 10) - 1;
  return `${monthNames[mIdx] ?? month} '${year.slice(2)}`;
}

function formatMonthFull(ym?: string): string {
  if (!ym) return "";
  const parts = ym.split("-");
  if (parts.length < 2) return ym;
  const [year, month] = parts;
  const monthNames = [
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"
  ];
  const mIdx = parseInt(month, 10) - 1;
  return `${monthNames[mIdx] ?? month} ${year}`;
}
