import { useId, useState } from "react";
import { formatCurrency } from "../../../shared/utils/format";
import { monthLabel, shortMonth, signed, tidyMoney } from "../format";
import type { CashFlowForecast, Estimate } from "../types";

type Kind = "actual" | "partial" | "projected";

interface Column {
  month: string;
  kind: Kind;
  income: number | null;
  expenses: number;
  net: number | null;
  expenseBand: Estimate | null;
  netBand: Estimate | null;
  incomeBand: Estimate | null;
  factors: string[];
}

const KIND_LABEL: Record<Kind, string> = { actual: "Actual", partial: "So far (incomplete month)", projected: "Projected" };

function describe(c: Column): string {
  const parts = [`${monthLabel(c.month)}, ${KIND_LABEL[c.kind].toLowerCase()}`];
  parts.push(c.income === null ? "income not projected" : `income ${formatCurrency(c.income)}`);
  parts.push(`spending ${formatCurrency(c.expenses)}`);
  parts.push(c.net === null ? "net not projected" : `net ${formatCurrency(c.net)}`);
  if (c.expenseBand?.lower != null && c.expenseBand.upper != null) {
    parts.push(`spending range ${formatCurrency(c.expenseBand.lower)} to ${formatCurrency(c.expenseBand.upper)}`);
  }
  return parts.join(", ");
}

const tick = (n: number) => (Math.abs(n) >= 1000 ? `₹${Math.round(n / 1000)}k` : `₹${Math.round(n)}`);

/**
 * Observed months (solid), the month in progress (outlined, not used to train) and projected months
 * (hatched) in one SVG. Uncertainty whiskers appear only where the backend supplies a band built from
 * the method's own past errors; there is no invented band. Every month is keyboard-focusable and
 * mirrored in a data table for screen readers.
 */
export default function CashFlowForecastChart({ data }: { data: CashFlowForecast }) {
  const hatchId = useId();
  const [active, setActive] = useState<Column | null>(null);

  const columns: Column[] = [
    ...data.historical.map<Column>((h) => ({
      month: h.month, kind: "actual", income: h.income, expenses: h.expenses, net: h.net,
      expenseBand: null, netBand: null, incomeBand: null, factors: [],
    })),
    ...(data.currentMonth
      ? [{
          month: data.currentMonth.month, kind: "partial" as const, income: data.currentMonth.income,
          expenses: data.currentMonth.expenses, net: data.currentMonth.net,
          expenseBand: null, netBand: null, incomeBand: null, factors: [data.currentMonth.note],
        }]
      : []),
    ...data.projected.map<Column>((p) => ({
      month: p.month, kind: "projected", income: p.income?.point ?? null, expenses: p.expenses.point,
      net: p.net?.point ?? null, expenseBand: p.expenses, netBand: p.net, incomeBand: p.income, factors: p.factors,
    })),
  ];

  if (columns.length === 0) {
    return (
      <div className="rounded-2xl bg-white border border-slate-200/80 p-8 text-center shadow-sm">
        <h3 className="text-base font-semibold text-slate-900">Cash flow</h3>
        <p className="text-xs text-slate-500 mt-2">No transactions recorded yet, so there is nothing to show or project.</p>
      </div>
    );
  }

  const values: number[] = [0];
  columns.forEach((c) => {
    values.push(c.expenses);
    if (c.income !== null) values.push(c.income);
    if (c.net !== null) values.push(c.net);
    if (c.expenseBand?.upper != null) values.push(c.expenseBand.upper);
    if (c.incomeBand?.upper != null) values.push(c.incomeBand.upper);
    if (c.netBand?.lower != null) values.push(c.netBand.lower);
    if (c.netBand?.upper != null) values.push(c.netBand.upper);
  });
  const max = Math.max(...values, 1000);
  const min = Math.min(...values, 0);
  const span = max - min || 1;

  const height = 280;
  const width = Math.max(680, columns.length * 78);
  const left = 62;
  const right = 24;
  const top = 34;
  const bottom = 44;
  const plotW = width - left - right;
  const plotH = height - top - bottom;
  const group = plotW / columns.length;
  const barW = Math.min(22, (group - 14) / 2);
  const y = (v: number) => top + plotH - ((v - min) / span) * plotH;
  const zeroY = y(0);
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => min + f * span);
  const firstProjected = columns.findIndex((c) => c.kind === "projected");
  // The net line joins complete months only: the month in progress is a partial total and would draw a false spike.
  const at = (i: number) => `${left + i * group + group / 2},${y(columns[i].net as number)}`;
  const actualIdx = columns.flatMap((c, i) => (c.kind === "actual" && c.net !== null ? [i] : []));
  const projectedIdx = columns.flatMap((c, i) => (c.kind === "projected" && c.net !== null ? [i] : []));
  const actualLine = actualIdx.map(at);
  const projectedLine = [...(actualIdx.length ? [at(actualIdx[actualIdx.length - 1])] : []), ...projectedIdx.map(at)];

  return (
    <div className="rounded-2xl bg-white border border-slate-200/80 p-5 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-2 mb-3">
        <div>
          <h3 className="text-base font-semibold text-slate-900">Cash flow: actual and projected</h3>
          <p className="text-xs text-slate-500 mt-0.5">
            Solid bars are recorded facts. Hatched bars are projections for the {data.horizonMonths} month
            {data.horizonMonths === 1 ? "" : "s"} after {data.currentMonth ? monthLabel(data.currentMonth.month) : "the latest month"}.
          </p>
        </div>
      </div>

      <ul className="flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px] text-slate-600 mb-3" aria-label="Chart legend">
        <li className="flex items-center gap-1.5"><span className="w-3 h-3 rounded-sm bg-sky-600" />Income (actual)</li>
        <li className="flex items-center gap-1.5"><span className="w-3 h-3 rounded-sm bg-slate-500" />Spending (actual)</li>
        <li className="flex items-center gap-1.5"><span className="w-3 h-3 rounded-sm border border-dashed border-sky-700 bg-sky-100" />Projected</li>
        <li className="flex items-center gap-1.5"><span className="w-3 h-3 rounded-sm border border-slate-400 bg-white" />Month so far (not used to forecast)</li>
        <li className="flex items-center gap-1.5"><span className="w-4 h-0.5 bg-emerald-600" />Net cash flow</li>
        <li className="flex items-center gap-1.5"><span className="w-3 h-3 border-l border-r border-violet-500" />Range of past forecast errors (only where shown)</li>
      </ul>

      <div className="overflow-x-auto">
        <svg viewBox={`0 0 ${width} ${height}`} className="w-full h-auto min-w-[640px]" role="group" aria-label="Cash flow chart">
          <defs>
            <pattern id={hatchId} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
              <rect width="6" height="6" fill="#e0f2fe" />
              <line x1="0" y1="0" x2="0" y2="6" stroke="#0284c7" strokeWidth="2" />
            </pattern>
          </defs>

          {ticks.map((t) => (
            <g key={t}>
              <line x1={left} y1={y(t)} x2={width - right} y2={y(t)} stroke="#f1f5f9" />
              <text x={left - 8} y={y(t) + 3} textAnchor="end" fontSize="10" fill="#94a3b8">{tick(t)}</text>
            </g>
          ))}
          {min < 0 && <line x1={left} y1={zeroY} x2={width - right} y2={zeroY} stroke="#94a3b8" />}

          {firstProjected > 0 && (
            <g>
              <line x1={left + firstProjected * group} y1={top - 14} x2={left + firstProjected * group} y2={height - bottom + 4}
                stroke="#94a3b8" strokeWidth="1.5" strokeDasharray="4 4" />
              <text x={left + firstProjected * group - 6} y={top - 18} textAnchor="end" fontSize="10" fontWeight="700" fill="#475569">ACTUAL</text>
              <text x={left + firstProjected * group + 6} y={top - 18} fontSize="10" fontWeight="700" fill="#0369a1">PROJECTED</text>
            </g>
          )}

          {columns.map((c, i) => {
            const cx = left + i * group + group / 2;
            const projected = c.kind === "projected";
            const partial = c.kind === "partial";
            const incomeX = cx - barW - 2;
            const expenseX = cx + 2;
            const bar = (v: number, x: number, color: string) => {
              const top0 = Math.min(y(v), zeroY);
              const h = Math.max(2, Math.abs(zeroY - y(v)));
              return (
                <rect x={x} y={top0} width={barW} height={h} rx="3"
                  fill={partial ? "#ffffff" : projected ? `url(#${hatchId})` : color}
                  stroke={partial ? "#94a3b8" : projected ? "#0369a1" : "none"}
                  strokeWidth={projected || partial ? 1.2 : 0}
                  strokeDasharray={projected ? "3 2" : partial ? "2 2" : undefined} />
              );
            };
            const band = c.expenseBand;
            return (
              <g key={c.month} tabIndex={0} role="img" aria-label={describe(c)}
                onMouseEnter={() => setActive(c)} onMouseLeave={() => setActive(null)}
                onFocus={() => setActive(c)} onBlur={() => setActive(null)}>
                <rect x={left + i * group} y={top} width={group} height={plotH} fill="transparent" />
                {c.income !== null && bar(c.income, incomeX, "#0284c7")}
                {bar(c.expenses, expenseX, "#64748b")}
                {projected && band?.lower != null && band.upper != null && (
                  <g stroke="#8b5cf6" strokeWidth="1.5">
                    <line x1={expenseX + barW / 2} y1={y(band.upper)} x2={expenseX + barW / 2} y2={y(band.lower)} />
                    <line x1={expenseX + 3} y1={y(band.upper)} x2={expenseX + barW - 3} y2={y(band.upper)} />
                    <line x1={expenseX + 3} y1={y(band.lower)} x2={expenseX + barW - 3} y2={y(band.lower)} />
                  </g>
                )}
                <text x={cx} y={height - 22} textAnchor="middle" fontSize="11" fontWeight={projected ? 700 : 500}
                  fill={projected ? "#0369a1" : "#475569"}>{shortMonth(c.month)}</text>
                {partial && <text x={cx} y={height - 9} textAnchor="middle" fontSize="9" fill="#94a3b8">so far</text>}
              </g>
            );
          })}

          {actualLine.length > 1 && <polyline points={actualLine.join(" ")} fill="none" stroke="#059669" strokeWidth="2" strokeLinejoin="round" />}
          {projectedLine.length > 1 && <polyline points={projectedLine.join(" ")} fill="none" stroke="#059669" strokeWidth="2" strokeDasharray="5 4" strokeLinejoin="round" />}
          {columns.map((c, i) =>
            c.net === null || c.kind === "partial" ? null : (
              <circle key={`n-${c.month}`} cx={left + i * group + group / 2} cy={y(c.net)} r="3.5"
                fill={c.kind === "projected" ? "#ffffff" : "#059669"} stroke="#059669" strokeWidth="2" />
            ),
          )}
        </svg>
      </div>

      <div className="mt-3 min-h-[3.25rem] rounded-xl bg-slate-50 border border-slate-100 px-4 py-2.5 text-xs text-slate-700" aria-live="polite">
        {active ? (
          <div>
            <p className="font-semibold text-slate-900">
              {monthLabel(active.month)} <span className="font-normal text-slate-500">· {KIND_LABEL[active.kind]}</span>
            </p>
            <p className="mt-0.5">
              Income {active.income === null ? "not projected" : formatCurrency(active.income)} · Spending {formatCurrency(active.expenses)} · Net{" "}
              {active.net === null ? "not projected" : signed(active.net, formatCurrency)}
            </p>
            {active.expenseBand?.lower != null && active.expenseBand.upper != null ? (
              <p className="text-violet-700 mt-0.5">
                Spending range from past forecast errors: {formatCurrency(active.expenseBand.lower)} – {formatCurrency(active.expenseBand.upper)}
              </p>
            ) : active.kind === "projected" ? (
              <p className="text-slate-500 mt-0.5">No range shown: too few past forecasts to build one.</p>
            ) : null}
            {active.factors.length > 0 && <p className="text-slate-500 mt-0.5">{tidyMoney(active.factors.join(" "))}</p>}
          </div>
        ) : (
          <p className="text-slate-500">Hover over a month, or Tab to it, to see its figures and what shaped them.</p>
        )}
      </div>

      <div className="sr-only">
      <table>
        <caption>Cash flow by month</caption>
        <thead>
          <tr><th>Month</th><th>Status</th><th>Income</th><th>Spending</th><th>Net</th></tr>
        </thead>
        <tbody>
          {columns.map((c) => (
            <tr key={c.month}>
              <td>{monthLabel(c.month)}</td><td>{KIND_LABEL[c.kind]}</td>
              <td>{c.income === null ? "not projected" : formatCurrency(c.income)}</td>
              <td>{formatCurrency(c.expenses)}</td>
              <td>{c.net === null ? "not projected" : formatCurrency(c.net)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>
    </div>
  );
}
