import { formatCurrency } from "../../../shared/utils/format";
import { signed } from "../format";
import type { ScenarioTotals } from "../types";

export interface ComparisonColumn {
  key: string;
  label: string;
  hint?: string;
  totals: ScenarioTotals;
  /** The first column is the reference the others are compared with. */
  interestSaved?: number | null;
}

const money = (v: number | null) => (v === null ? "—" : formatCurrency(v));

/**
 * Side-by-side figures over the same horizon. Differences against the first column are plain subtraction of
 * numbers the backend already calculated; nothing is re-estimated here.
 */
export default function ScenarioComparison({ columns, caption }: { columns: ComparisonColumn[]; caption: string }) {
  if (columns.length === 0) return null;
  const ref = columns[0].totals;
  const rows: { label: string; value: (t: ScenarioTotals) => number | null; spending?: boolean; fmt?: (v: number | null) => string }[] = [
    { label: "Income", value: (t) => t.income },
    { label: "Spending", value: (t) => t.expenses, spending: true },
    { label: "Net cash flow", value: (t) => t.net },
    { label: "Savings rate", value: (t) => t.savingsRatePercent, fmt: (v) => (v === null ? "—" : `${v.toFixed(1)}%`) },
  ];
  const showInterest = columns.some((c) => c.interestSaved !== undefined && c.interestSaved !== null);
  const maxNet = Math.max(...columns.map((c) => Math.abs(c.totals.net ?? 0)), 1);

  return (
    <div className="overflow-x-auto" data-testid="scenario-comparison">
      <table className="w-full text-xs text-left">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr className="text-slate-500 border-b border-slate-100">
            <th scope="col" className="py-2 pr-3 font-medium">Over the horizon</th>
            {columns.map((c) => (
              <th key={c.key} scope="col" className="py-2 px-2 font-medium text-right align-bottom">
                <span className="block text-slate-900 font-semibold">{c.label}</span>
                {c.hint && <span className="block text-[10px] text-slate-500 font-normal">{c.hint}</span>}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="tabular-nums">
          {rows.map((r) => (
            <tr key={r.label} className="border-b border-slate-50">
              <th scope="row" className="py-2 pr-3 font-semibold text-slate-900">{r.label}</th>
              {columns.map((c, i) => {
                const v = r.value(c.totals);
                const base = r.value(ref);
                const d = i === 0 || v === null || base === null || r.fmt ? null : v - base;
                return (
                  <td key={c.key} className="py-2 px-2 text-right">
                    <span className="font-semibold text-slate-900">{r.fmt ? r.fmt(v) : money(v)}</span>
                    {d !== null && d !== 0 && (
                      <span className={`block text-[10px] ${(r.spending ? d < 0 : d > 0) ? "text-emerald-600" : "text-rose-600"}`}>
                        {signed(d, formatCurrency)}
                      </span>
                    )}
                  </td>
                );
              })}
            </tr>
          ))}
          {showInterest && (
            <tr className="border-b border-slate-50">
              <th scope="row" className="py-2 pr-3 font-semibold text-slate-900">Loan interest saved (whole loan)</th>
              {columns.map((c) => (
                <td key={c.key} className="py-2 px-2 text-right font-semibold text-slate-900">
                  {c.interestSaved === undefined || c.interestSaved === null ? "—" : formatCurrency(c.interestSaved)}
                </td>
              ))}
            </tr>
          )}
        </tbody>
      </table>
      <div className="mt-3 space-y-1" aria-hidden="true">
        {columns.map((c) => {
          const net = c.totals.net;
          if (net === null) return null;
          return (
            <div key={c.key} className="flex items-center gap-2 text-[10px] text-slate-600">
              <span className="w-28 truncate">{c.label}</span>
              <div className="flex-1 h-2 rounded-full bg-slate-100 overflow-hidden">
                <div className={`h-full rounded-full ${net >= 0 ? "bg-sky-500" : "bg-rose-400"}`} style={{ width: `${Math.max(2, (Math.abs(net) / maxNet) * 100)}%` }} />
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
